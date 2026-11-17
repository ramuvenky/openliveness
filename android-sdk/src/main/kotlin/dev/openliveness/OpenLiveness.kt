package dev.openliveness

import android.content.Context
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import dev.openliveness.camera.CameraCapture
import dev.openliveness.layers.Layer3RPPG
import dev.openliveness.layers.Layer4Texture
import dev.openliveness.layers.MediaPipeLandmarkStream
import dev.openliveness.layers.TextureClassifier
import dev.openliveness.sensors.AmbientConditionsSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Batteries-included entrypoint. A single suspend call that builds a
 * fully-wired [CDLSession] against the device's front camera, runs the
 * full liveness flow, tears down every native resource, and returns the
 * relay's decision.
 *
 * The integrator is responsible for exactly three things:
 *   1. Scanning / parsing the QR payload ([parseQRPayload]).
 *   2. Holding the runtime `CAMERA` permission.
 *   3. Owning a [LifecycleOwner] and a [PreviewView] attached to the view
 *      hierarchy at call time — CameraX binds to both.
 *
 * Everything else — MediaPipe face landmarker init, Layer-3 POS-rPPG
 * collection, Layer-4 LBP+Fourier TFLite inference, ambient sensor
 * sampling, WebSocket handshake, Play Integrity attestation, signed
 * payload submission — is handled internally. No manual layer wiring.
 *
 * Why this exists as a top-level function: the [CDLSession] constructor
 * still accepts injected [LandmarkStream] / [Layer3RPPG] / [Layer4Texture]
 * instances so unit and instrumented tests can drive deterministic fakes;
 * that constructor is the DI boundary and intentionally not ergonomic for
 * production. Production callers come through here.
 *
 * All assets ([OpenLiveness.MODEL_ASSET] + [TextureClassifier.ASSET_NAME])
 * ship inside the AAR. There is no download step and no "missing model"
 * failure mode — if the AAR is present, the models are present.
 *
 * **Blocking work** (native-model mmap, TFLite interpreter alloc) runs on
 * [Dispatchers.IO] inside this function; the caller is free to invoke it
 * from the main thread.
 */
object OpenLiveness {

    /** Asset path of the bundled MediaPipe FaceLandmarker (float16 ~3.8 MB).
     *  Shipped inside the SDK AAR under `src/main/assets/`. */
    const val MODEL_ASSET = "face_landmarker.task"

    private const val LOG_TAG = "OpenLiveness"

    /**
     * Run a liveness session end-to-end against the device's front camera.
     *
     * @param payload parsed QR payload from the RP's web flow.
     * @param context any Android [Context]; the SDK pins to
     *   `applicationContext` internally so an Activity isn't retained.
     * @param lifecycleOwner typically the Activity or Fragment hosting the
     *   preview; CameraX binds its use cases to this lifecycle.
     * @param previewView attached [PreviewView] that will render the
     *   front-camera feed during the challenge.
     * @param cloudProjectNumber Google Cloud project number for Play
     *   Integrity Standard. Pass `null` on AOSP fleets / CI to opt out of
     *   hardware attestation (the relay clusters those as software_only).
     * @param onProgress optional callback fired on every WS
     *   `processing_update` with the same `(progress, currentLayer)` pair
     *   the server sees. Invoked on the IO dispatcher — callers that
     *   touch Views must post to main themselves.
     *
     * @return [RelayClient.CompleteResponse] carrying the relay's
     *   accept/reject decision.
     *
     * @throws CDLException on WebSocket / handshake / timeout failures.
     * @throws IllegalArgumentException on QR / signature errors.
     */
    suspend fun runFrontCameraSession(
        payload: QRPayload,
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        cloudProjectNumber: Long? = null,
        onProgress: ((progress: Float, currentLayer: String) -> Unit)? = null,
    ): RelayClient.CompleteResponse {
        val appContext = context.applicationContext ?: context
        val ambientSampler = AmbientConditionsSampler(appContext)

        // Hoist refs so the finally can tear down even if a mid-way
        // constructor throws. Each resource owns a native handle
        // (TFLite interpreter, MediaPipe FaceLandmarker, CameraX use
        // cases) — leaking any of them eventually OOMs the process on
        // repeated starts.
        var textureClassifier: TextureClassifier? = null
        var layer4ToClose: Layer4Texture? = null
        var stream: MediaPipeLandmarkStream? = null
        var capture: CameraCapture? = null
        try {
            val layer3 = Layer3RPPG()
            // Model loads + native graph init block the thread — hop to
            // IO so the caller can invoke from the main dispatcher.
            // Assignments happen INSIDE the lambda so a parent-scope
            // cancellation between lambda-return and outer-resume can't
            // orphan the created native resources.
            val layer4 = withContext(Dispatchers.IO) {
                val tc = TextureClassifier.fromAssets(appContext)
                textureClassifier = tc
                Layer4Texture(classifier = tc).also { layer4ToClose = it }
            }
            withContext(Dispatchers.IO) {
                stream = MediaPipeLandmarkStream.fromAsset(
                    context = appContext,
                    modelAssetPath = MODEL_ASSET,
                    mirrorForFrontCamera = true,
                    rgbSink = layer3,
                    faceCropSink = layer4,
                )
            }
            val s = stream!!
            capture = CameraCapture(appContext).also { it.bind(lifecycleOwner, previewView, s) }

            val session = CDLSession(
                payload = payload,
                context = appContext,
                cloudProjectNumber = cloudProjectNumber,
                landmarkStream = s,
                layer3 = layer3,
                layer4 = layer4,
                ambientSampler = ambientSampler,
                onProgress = onProgress,
            )
            return withContext(Dispatchers.IO) { session.start() }
        } finally {
            try { stream?.close() } catch (t: Throwable) { Log.w(LOG_TAG, "stream close failed", t) }
            try { capture?.close() } catch (t: Throwable) { Log.w(LOG_TAG, "capture close failed", t) }
            // Close layer4 BEFORE its classifier — the Layer4 worker may
            // be mid-inference on an interpreter that's about to be freed.
            try { layer4ToClose?.close() } catch (t: Throwable) { Log.w(LOG_TAG, "layer4 close failed", t) }
            try { textureClassifier?.close() } catch (t: Throwable) { Log.w(LOG_TAG, "classifier close failed", t) }
        }
    }
}
