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
import java.util.concurrent.atomic.AtomicReference

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
    /** Optional Layer-3 RGB tap. When non-null, [analyze] computes
     *  mean R/G/B across the three skin ROIs defined by
     *  [SkinRegionExtractor] and forwards an [RGBSample] per frame.
     *  Null (default) skips the extraction entirely so sessions that
     *  never consume rPPG pay no per-frame bitmap-scan cost.
     *
     *  Exposed via [LandmarkStream.rgbSink] so [CDLSession] can verify
     *  the Layer3RPPG it was handed is the SAME sink receiving frames,
     *  not a drift-silent second instance. */
    override val rgbSink: RGBSampleSink?,
    /** Shared with the result listener so analyze() can read the most
     *  recently detected landmarks for its RGB ROI math. One-frame lag
     *  (<33ms at 30fps) is well below the pulse band Layer 3 operates
     *  in (0.7-3 Hz → period 333-1430ms), so the lag doesn't degrade
     *  signal; it just avoids buffering bitmaps keyed by timestamp. */
    private val latestLandmarks: AtomicReference<FaceLandmarks?>,
    /** Carried into [tapRgb] as [SkinRegionExtractor]'s `flipX` arg.
     *  The channel ships MIRRORED landmarks (what Layer 2/5 consume),
     *  but the pixel buffer is un-mirrored sensor-raw; the extractor
     *  must un-flip the centroid x's for pixel ROIs to land on the
     *  correct side of the bitmap. See kdoc on [tapRgb]. */
    private val mirrorForFrontCamera: Boolean,
    /** Optional Layer-4 face-crop tap. When non-null, [analyze] extracts
     *  an 80×80 grayscale face crop at [FACE_CROP_INTERVAL_MS] cadence
     *  and forwards it. Null (default) skips the extraction entirely
     *  so sessions that haven't adopted Layer 4 pay no per-frame cost. */
    override val faceCropSink: FaceCropSink?,
) : LandmarkStream, ImageAnalysis.Analyzer {

    private val gate = ClosableGate()
    private val timestamps = TimestampMonotonizer()

    /** Reusable buffer for Bitmap.getPixels() — reallocated only if the
     *  camera stream's resolution changes. The ARGB_8888 contract packs
     *  each pixel into one int, so this is width*height ints (~1.2 MB
     *  for 640x480, the CameraX ImageAnalysis default). Allocating
     *  fresh per frame produced sustained GC pressure in the Layer2
     *  profile — amortising keeps 30fps on mid-range devices. */
    private var pixelsBuf: IntArray? = null

    /** Timestamp (ms, same clock as the one driving [analyze]) at which
     *  the last 80×80 crop was handed to [faceCropSink]. Layer 4 is a
     *  static-image classifier — feeding it at the camera's 30 fps would
     *  burn battery with no signal gain, so [tapFaceCrop] rate-limits to
     *  one call per [FACE_CROP_INTERVAL_MS]. @Volatile because the field
     *  is written on the analyzer executor and may be read by debug/
     *  observability code on another thread. */
    @Volatile
    private var lastFaceCropMs: Long = 0L

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
                tapRgb(bitmap, tsMs)
                tapFaceCrop(bitmap, tsMs)
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

    /**
     * Pull the current frame's pixels into [pixelsBuf], run the skin-
     * region extractor against the most-recently-detected landmarks,
     * and forward an [RGBSample] to [rgbSink]. No-ops when the sink is
     * null, no face has been detected yet, or the extractor returns
     * null (face off-frame, zero face width, etc.). Short-circuits
     * before `getPixels` to avoid the bitmap copy when there's no sink.
     *
     * **Mirror handling**: the horizontal flip we apply to landmarks
     * (`x → 1 - x`, see `faceLandmarksFromXYZ`) means the stored
     * landmark x-coords are in the already-mirrored frame of reference.
     * The pixel buffer is NOT mirrored — we read directly from the raw
     * sensor stream. We flip the landmark x's back here before
     * computing pixel ROIs so the ROI lands on the correct side of the
     * sensor-raw bitmap. Doing the un-flip at this one site keeps the
     * ratio-based Layer-2 detectors insulated from the physical
     * orientation of the bitmap.
     */
    private fun tapRgb(bitmap: android.graphics.Bitmap, tsMs: Long) {
        val sink = rgbSink ?: return
        val landmarks = latestLandmarks.get() ?: return
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val need = w * h
        // Reallocate when (a) no buffer yet, (b) too small for the
        // current frame, OR (c) the current buffer is more than 2× the
        // frame area — a camera that briefly bumped to 4K preview and
        // then dropped back to 480p would otherwise retain a ~8 MB
        // IntArray indefinitely. 2× hysteresis keeps us from thrashing
        // on normal small variations.
        val existing = pixelsBuf
        val buf = if (existing == null || existing.size < need || existing.size > need * 2) {
            IntArray(need).also { pixelsBuf = it }
        } else {
            existing
        }
        bitmap.getPixels(buf, 0, w, 0, 0, w, h)
        val rgb = SkinRegionExtractor.meanSkinRgb(
            pixels = buf,
            width = w,
            height = h,
            landmarks = landmarks,
            flipX = mirrorForFrontCamera,
        ) ?: return
        sink.onSample(RGBSample(
            red = rgb[0],
            green = rgb[1],
            blue = rgb[2],
            timestampSeconds = tsMs / 1000.0,
        ))
    }

    /**
     * Rate-limited counterpart to [tapRgb]: extracts an 80×80 grayscale
     * face crop and forwards it to [faceCropSink]. No-ops when the sink
     * is null, no face has been detected yet, we're inside the
     * [FACE_CROP_INTERVAL_MS] cooldown, or the extractor returns null
     * (face off-frame). Short-circuits before `getPixels` on all three
     * paths so a session without a Layer-4 wire-up pays nothing per
     * frame beyond one field read + one subtract.
     *
     * **Why re-read pixels when [tapRgb] already did**: writing a
     * second `getPixels(buf, …)` call here keeps the two taps
     * independent — a session that opts into Layer 4 but not Layer 3,
     * or vice versa, still works unchanged. The 1 Hz cadence means the
     * extra copy fires ~once per 30 analyzed frames; the fused cost is
     * dwarfed by MediaPipe's own per-frame mesh inference.
     *
     * **Mirror handling**: same convention as [tapRgb] — landmarks are
     * stored mirrored, bitmap is sensor-raw, [FaceCropExtractor] un-flips
     * x in landmark space before computing the pixel bbox.
     */
    private fun tapFaceCrop(bitmap: android.graphics.Bitmap, tsMs: Long) {
        val sink = faceCropSink ?: return
        if (tsMs - lastFaceCropMs < FACE_CROP_INTERVAL_MS) return
        val landmarks = latestLandmarks.get() ?: return
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val need = w * h
        val existing = pixelsBuf
        val buf = if (existing == null || existing.size < need || existing.size > need * 2) {
            IntArray(need).also { pixelsBuf = it }
        } else {
            existing
        }
        bitmap.getPixels(buf, 0, w, 0, 0, w, h)
        val gray = FaceCropExtractor.extractGrayFaceCrop(
            pixels = buf,
            width = w,
            height = h,
            landmarks = landmarks,
            flipX = mirrorForFrontCamera,
        ) ?: return
        // Set *after* a successful extraction so a transient face-lost
        // frame doesn't reset the clock and skip the next good frame.
        lastFaceCropMs = tsMs
        sink.onFrame(gray, FaceCropExtractor.OUT_SIZE)
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
         * Minimum interval between face-crop sink calls, in milliseconds.
         * Layer 4 is a static-image classifier evaluated once per frame
         * handed in — pushing it at the full camera rate (~30 fps) would
         * waste cycles since the LBP + Dense output is dominated by
         * texture statistics that change far slower than that.
         *
         * 1000 ms matches the "~1 Hz in production" note in
         * [FaceCropSink]'s threading docs and roughly tracks the
         * CDL challenge beat (each turn/blink step averages ~2 s), so
         * every challenge window produces 1-2 fresh scores to feed into
         * the final attestation.
         */
        const val FACE_CROP_INTERVAL_MS: Long = 1000L

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
            rgbSink: RGBSampleSink? = null,
            faceCropSink: FaceCropSink? = null,
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
            return fromBaseOptions(
                context = context,
                baseOptions = baseOptions,
                mirrorForFrontCamera = mirrorForFrontCamera,
                numFaces = numFaces,
                rgbSink = rgbSink,
                faceCropSink = faceCropSink,
            )
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
            rgbSink: RGBSampleSink? = null,
            faceCropSink: FaceCropSink? = null,
        ): MediaPipeLandmarkStream {
            val channel = Channel<LandmarkFrame>(
                capacity = CHANNEL_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val dropLogged = AtomicBoolean(false)
            val latestLandmarks = AtomicReference<FaceLandmarks?>(null)
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
                    // Publish BEFORE channel send so an analyze() racing
                    // on another thread sees landmarks no older than this
                    // frame — the staleness bound stays at 1 frame.
                    latestLandmarks.set(frame.landmarks)
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
            return MediaPipeLandmarkStream(
                landmarker = landmarker,
                channel = channel,
                rgbSink = rgbSink,
                latestLandmarks = latestLandmarks,
                mirrorForFrontCamera = mirrorForFrontCamera,
                faceCropSink = faceCropSink,
            )
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
