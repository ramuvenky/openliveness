package dev.openliveness.layers

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production LandmarkStream backed by MediaPipe FaceLandmarker in
 * LIVE_STREAM mode. Also implements ImageAnalysis.Analyzer so CameraX
 * can push frames directly: `cameraCapture.bind(owner, previewView,
 * stream)` wires the whole pipeline with no intermediate buffering.
 *
 * The adapter is one-session-use: [close] releases the native
 * FaceLandmarker and shuts the result channel. A second `start()` on
 * CDLSession would need a fresh instance — closing then re-using would
 * require re-initializing the TFLite graph which is slow enough to be
 * worth doing explicitly rather than hiding behind a reuse pattern.
 *
 * Front-camera note: the detectors assume a mirrored view (user looking
 * left → nose.x < face_center → `offset < -0.15`). CameraX ImageAnalysis
 * delivers the raw un-mirrored sensor stream, so `mirrorForFrontCamera`
 * must stay true when the caller binds this to a front-camera analysis
 * use case. The flip is applied to landmark x-coords after inference
 * (`1 - x`) which is cheaper than copying the bitmap per frame and
 * mathematically identical for the ratio-based gesture detectors.
 * No default on the mirror flag: a caller silently using the back camera
 * with mirror=true would get silently-wrong detection, so we force an
 * explicit choice at the call site.
 */
class MediaPipeLandmarkStream private constructor(
    private val landmarker: FaceLandmarker,
    private val channel: Channel<LandmarkFrame>,
) : LandmarkStream, ImageAnalysis.Analyzer {

    private val gate = ClosableGate()
    private val timestamps = TimestampMonotonizer()

    override suspend fun nextFrame(): LandmarkFrame? =
        channel.receiveCatching().getOrNull()

    override fun analyze(image: ImageProxy) {
        try {
            gate.ifOpen {
                // MediaPipe LIVE_STREAM requires monotonically-increasing
                // timestamps in milliseconds. ImageProxy.timestamp is
                // SystemClock.elapsedRealtimeNanos (monotonic by contract),
                // but nanos→ms division collapses sub-millisecond gaps —
                // the monotonizer bumps duplicates by +1 ms, below the
                // detectors' temporal resolution.
                val tsMs = timestamps.advance(image.imageInfo.timestamp / 1_000_000L)
                val bitmap = image.toBitmap()
                val mpImage = BitmapImageBuilder(bitmap).build()
                // Rotation is handed to MediaPipe as metadata so we avoid a
                // bitmap copy; the horizontal mirror is applied downstream
                // on the landmark coordinates themselves (see toLandmarkFrame).
                val ipo = ImageProcessingOptions.builder()
                    .setRotationDegrees(image.imageInfo.rotationDegrees)
                    .build()
                landmarker.detectAsync(mpImage, ipo, tsMs)
            }
        } catch (e: Throwable) {
            // Fail the stream cleanly rather than rethrowing — a thrown
            // analyzer propagates through CameraX's analysis executor
            // with implementation-defined behaviour (previous versions
            // tore down the preview). The runner sees a closed channel
            // and surfaces a terminal result to the orchestrator.
            Log.w(TAG, "analyze failed; closing stream", e)
            closeInternal()
        } finally {
            image.close()
        }
    }

    override fun close() = closeInternal()

    private fun closeInternal() {
        gate.close {
            try { landmarker.close() } catch (_: Throwable) {}
            channel.close()
        }
    }

    companion object {
        private const val TAG = "OpenLivenessMP"

        /**
         * Default channel capacity. Small on purpose: at 30fps the
         * sequencer consumes well faster than frames arrive, so the
         * channel is almost always empty. 16 gives a cushion for
         * transient scheduler stalls; DROP_OLDEST prevents a backlog
         * growing if the sequencer stalls while MediaPipe keeps producing.
         */
        private const val CHANNEL_CAPACITY = 16

        /**
         * Build a stream from a model file bundled in the app's assets.
         * The caller is responsible for shipping `face_landmarker.task`
         * (the float16 variant, ~3.8 MB) at the given asset path.
         *
         * **Blocking:** reads the asset bytes synchronously when
         * [expectedSha256] is supplied, and MediaPipe's native loader
         * mmaps + parses the ~3.8 MB TFLite graph before this returns.
         * Call off the main thread (e.g. inside `withContext(Dispatchers.IO)`).
         *
         * @param mirrorForFrontCamera `true` when the caller binds this
         *   to a `DEFAULT_FRONT_CAMERA` analysis use case. No default:
         *   silent wrongness on a back-camera binding is nastier than
         *   the one extra arg at the call site.
         * @param expectedSha256 optional lowercase hex SHA-256 (64 chars).
         *   When supplied, the asset bytes are verified before the native
         *   loader runs; a mismatch throws `IllegalArgumentException` so
         *   a swapped-model supply-chain attack fails fast.
         */
        fun fromAsset(
            context: Context,
            modelAssetPath: String,
            mirrorForFrontCamera: Boolean,
            expectedSha256: String? = null,
            numFaces: Int = 1,
        ): MediaPipeLandmarkStream {
            if (expectedSha256 != null) {
                val bytes = context.assets.open(modelAssetPath).use { it.readBytes() }
                require(verifyModelSha256(bytes, expectedSha256)) {
                    "model sha256 mismatch at asset path: $modelAssetPath"
                }
            }
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .build()
            return fromBaseOptions(context, baseOptions, mirrorForFrontCamera, numFaces)
        }

        /**
         * Advanced factory. Use this when the model lives on disk (e.g.
         * downloaded on first run) and you want to pass a direct
         * ByteBuffer or FileDescriptor via BaseOptions yourself.
         */
        fun fromBaseOptions(
            context: Context,
            baseOptions: BaseOptions,
            mirrorForFrontCamera: Boolean,
            numFaces: Int = 1,
        ): MediaPipeLandmarkStream {
            val channel = Channel<LandmarkFrame>(
                capacity = CHANNEL_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val dropLogged = AtomicBoolean(false)
            // Capture-before-use: setResultListener fires on MediaPipe's
            // own thread, so we can't close over `this` from the Builder
            // chain — we route the result through the channel instead,
            // which is thread-safe by Channel's own contract.
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(numFaces)
                .setResultListener { result, _ ->
                    val frame = toLandmarkFrame(result, mirrorForFrontCamera)
                        ?: return@setResultListener
                    val sendResult = channel.trySend(frame)
                    // trySend can fail if the channel is closed (shutting
                    // down) or — with DROP_OLDEST — never for buffer-full,
                    // but we log the first failure anyway so a mis-wired
                    // listener doesn't silently drop every frame. Logged
                    // once per stream; subsequent drops are suppressed to
                    // avoid flooding logcat.
                    if (sendResult.isFailure && dropLogged.compareAndSet(false, true)) {
                        Log.d(TAG, "landmark channel send failed; further drops suppressed")
                    }
                }
                .setErrorListener { _ ->
                    // MediaPipe inference errors are terminal per-frame
                    // on live stream mode. Close the channel so the
                    // runner sees end-of-stream and finalises with a
                    // not-completed ChallengeResult rather than hanging.
                    channel.close()
                }
                .build()
            val landmarker = try {
                FaceLandmarker.createFromOptions(context, options)
            } catch (t: Throwable) {
                // Native init failed (corrupt model, wrong task file,
                // out-of-memory on an underpowered device). The channel
                // was already open by the time we got here, so close it
                // explicitly to avoid leaking a Kotlin coroutine resource
                // on the way out.
                channel.close()
                throw t
            }
            return MediaPipeLandmarkStream(landmarker, channel)
        }

        /**
         * Conversion from MediaPipe output to our [LandmarkFrame]. Takes
         * the first detected face; returns null when no face was
         * detected or the point count doesn't match our 478-point model
         * (e.g., someone wires in a different task file). Null → the
         * runner just skips the frame.
         *
         * The MediaPipe-free unit-testable half of this conversion lives
         * in [faceLandmarksFromXYZ] (MediaPipeLandmarkConversion.kt) —
         * JVM unit tests can't load NormalizedLandmark because tasks-
         * vision ships native .so files that aren't on the test classpath.
         */
        internal fun toLandmarkFrame(
            result: FaceLandmarkerResult,
            mirror: Boolean,
        ): LandmarkFrame? {
            val first = result.faceLandmarks().firstOrNull() ?: return null
            if (first.size != FaceLandmarks.EXPECTED_COUNT) return null
            val xyz = ArrayList<Triple<Float, Float, Float>>(first.size)
            for (p in first) xyz.add(Triple(p.x(), p.y(), p.z()))
            val landmarks = faceLandmarksFromXYZ(xyz, mirror) ?: return null
            return LandmarkFrame(landmarks, result.timestampMs() / 1000.0)
        }
    }
}
