package dev.openliveness.sample

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.openliveness.CDLSession
import dev.openliveness.camera.CameraCapture
import dev.openliveness.layers.MediaPipeLandmarkStream
import dev.openliveness.parseQRPayload
import dev.openliveness.sensors.AmbientConditionsSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

/**
 * Dev harness for the openliveness Android SDK. Not a shippable app —
 * it exists so a human can install an APK, grant the camera permission,
 * paste a QR payload, and watch CDLSession.start() exercise the full
 * flow on-device, including Layer-2 MediaPipe challenges and the
 * on-screen prompt sequence.
 *
 * Wires in a real MediaPipeLandmarkStream when the face_landmarker.task
 * asset is present; absent asset falls back to allowStubMode=true so a
 * developer can still exercise WS plumbing without the ~3.8 MB model
 * file. The red banner stays visible in stub mode so screenshots can't
 * be mistaken for real liveness.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var promptView: TextView
    private lateinit var statusView: TextView
    private lateinit var ambientView: TextView
    private lateinit var qrInput: EditText
    private lateinit var startButton: Button
    private lateinit var controlsPanel: LinearLayout

    companion object {
        private const val TAG = "OpenLivenessSample"
        private const val MODEL_ASSET = "face_landmarker.task"
        // Download URL for the FaceLandmarker float16 model. Documented
        // in src/main/assets/.gitignore too; duplicated in the UI so a
        // developer hitting the empty-asset error sees it without
        // leaving the device.
        private const val MODEL_DOWNLOAD_URL =
            "https://storage.googleapis.com/mediapipe-models/face_landmarker/" +
                "face_landmarker/float16/latest/face_landmarker.task"

        // Flipped once per process so the banner / logcat line don't
        // spam every start() call. The banner itself remains visible
        // for the lifetime of the Activity so a screenshot of the sample
        // always carries the warning.
        private var stubWarningLogged = false
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        statusView.text = if (granted) "Camera permission granted" else "Camera permission denied"
        startButton.isEnabled = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        startButton.isEnabled = alreadyGranted
        statusView.text = if (alreadyGranted) {
            if (modelAssetAvailable()) "Ready. Paste QR payload."
            else "Model missing. Taking stub path — see banner."
        } else {
            "Tap \"Request camera\" to continue"
        }
    }

    private fun modelAssetAvailable(): Boolean =
        try { assets.list("")?.contains(MODEL_ASSET) == true } catch (_: Throwable) { false }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(previewView)

        // Dev-harness warning banner stays visible at all times so a
        // screenshot of the running sample always carries the warning.
        // STUB_MODE_RP_ALLOWLIST on the server is the operator's defense;
        // the banner is the developer's.
        val warningBanner = TextView(this).apply {
            text = "⚠ DEV HARNESS — do not point at a production relay"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(0xB7, 0x1C, 0x1C))
            setPadding(24, 24, 24, 24)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
        }
        root.addView(warningBanner)

        // Live readout of AmbientConditionsSampler.snapshot() during a
        // running session. The point of surfacing this is on-device
        // validation: a developer can cover the light sensor or shake
        // the phone and see the corresponding flag flip, confirming
        // that the sensor path reaches the attestation builder before
        // trusting a signed payload downstream. Hidden outside a
        // running session so it doesn't advertise a stale read.
        ambientView = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(0x80, 0, 0, 0))
            setPadding(24, 16, 24, 16)
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ).also {
                // Push below the warning banner so both are readable.
                // The exact offset isn't load-bearing — the banner has
                // WRAP_CONTENT height so a looser value works, but 128
                // leaves a visible gap on the hardware we test on
                // (Pixel 6 class) and still fits above the preview on
                // small screens.
                it.topMargin = 128
            }
        }
        root.addView(ambientView)

        promptView = TextView(this).apply {
            text = ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        root.addView(promptView)

        controlsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.argb(0xC0, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
        }
        qrInput = EditText(this).apply {
            hint = "Paste QR payload (base64)"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            // Bound the height so a long base64 payload scrolls inside
            // the field instead of growing the controls panel up over
            // the camera preview + prompt. Dev-harness QR payloads are
            // hundreds of chars; without a cap the field swallowed the
            // whole screen on paste and made the challenge unusable.
            minLines = 2
            maxLines = 3
            isVerticalScrollBarEnabled = true
        }
        statusView = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 24)
        }
        val permBtn = Button(this).apply {
            text = "Request camera"
            setOnClickListener { cameraPermission.launch(Manifest.permission.CAMERA) }
        }
        startButton = Button(this).apply {
            text = "Start liveness"
            setOnClickListener { runSession() }
        }
        controlsPanel.addView(qrInput)
        controlsPanel.addView(permBtn)
        controlsPanel.addView(startButton)
        controlsPanel.addView(statusView)
        root.addView(controlsPanel)

        return root
    }

    private fun runSession() {
        val raw = qrInput.text.toString().trim()
        if (raw.isEmpty()) {
            statusView.text = "Paste a QR payload first"
            return
        }
        startButton.isEnabled = false
        // Hide the paste+button panel while the session runs so the
        // camera preview and the challenge prompt are fully visible. The
        // panel is bottom-aligned WRAP_CONTENT, so with a long pasted
        // payload it otherwise covers the lower third of the screen —
        // including the prompt and most of the user's face in preview.
        // Restored in the finally below regardless of outcome.
        controlsPanel.visibility = View.GONE
        val useStub = !modelAssetAvailable()
        if (useStub && !stubWarningLogged) {
            Log.w(
                TAG,
                "⚠ DEV HARNESS: $MODEL_ASSET missing from assets; constructing " +
                "CDLSession with allowStubMode=true. Attestations will carry " +
                "stub_mode=true and the relay will refuse them unless the " +
                "relying_party is on STUB_MODE_RP_ALLOWLIST. Download the " +
                "model from $MODEL_DOWNLOAD_URL to exercise the real Layer-2 " +
                "challenge pipeline."
            )
            stubWarningLogged = true
        }

        lifecycleScope.launch {
            // Hoist refs out of runCatching so the finally can tear
            // them down even if construction fails mid-way (e.g., bad
            // QR, MediaPipe init throws). Previously these leaked when
            // parseQRPayload threw after fromAsset had already returned.
            var stream: MediaPipeLandmarkStream? = null
            var capture: CameraCapture? = null
            // Ambient sampler is constructed before CDLSession so the
            // UI poller below can read snapshots from the same instance
            // the session will consume. CDLSession owns start() / stop()
            // — the double-start idempotency guard in the sampler means
            // this is safe even if a future refactor pre-starts here.
            val ambientSampler = AmbientConditionsSampler(applicationContext)
            var ambientPoller: Job? = null
            val result = runCatching {
                val payload = parseQRPayload(raw)
                statusView.text = "Opening camera..."
                if (!useStub) {
                    // Model load + native graph init block the thread —
                    // hop to IO so the UI stays responsive during the
                    // ~hundred-ms cold start. The assignment to `stream`
                    // happens INSIDE the withContext lambda, not on the
                    // outer side of `=`: `x = withContext { factory() }`
                    // has a cancellation window between the lambda return
                    // and the outer resume where a parent-scope cancel
                    // orphans the created resource (fromAsset has already
                    // allocated the native FaceLandmarker). Assigning
                    // inside the lambda guarantees `stream` is set before
                    // any suspension point can observe a cancellation,
                    // so the finally below can always close it.
                    withContext(Dispatchers.IO) {
                        stream = MediaPipeLandmarkStream.fromAsset(
                            context = applicationContext,
                            modelAssetPath = MODEL_ASSET,
                            mirrorForFrontCamera = true,
                        )
                    }
                    capture = CameraCapture(applicationContext).also {
                        it.bind(this@MainActivity, previewView, stream)
                    }
                    // First prompt displayed before any frame flows so
                    // the user knows what to do during the opening lag
                    // (camera warm-up + first MediaPipe inference).
                    showPromptFor(payload.challenge_sequence.firstOrNull())
                } else {
                    promptView.text = "Stub mode — no camera needed"
                }

                val session = CDLSession(
                    payload = payload,
                    context = applicationContext,
                    landmarkStream = stream,
                    allowStubMode = useStub,
                    ambientSampler = ambientSampler,
                    onProgress = { progress, _ ->
                        // Camera coroutine delivers this on the IO
                        // dispatcher; hop to main before touching views.
                        lifecycleScope.launch {
                            advancePrompt(payload.challenge_sequence, progress)
                        }
                    },
                )
                // Live-poll the sampler's snapshot into the HUD while
                // the session runs so a developer can confirm the
                // low-light / motion flags flip on cue (cover the
                // sensor, shake the device) BEFORE the attestation is
                // signed. Session.start() owns the sampler's register /
                // unregister lifecycle; the poller just reads the
                // volatile fields and is thread-safe per snapshot()'s
                // kdoc. 300 ms is a lazy cadence that's still perceptible
                // to a human eye and doesn't flood the main thread.
                ambientView.visibility = View.VISIBLE
                ambientPoller = lifecycleScope.launch {
                    while (isActive) {
                        val a = ambientSampler.snapshot()
                        ambientView.text = "low_light=${a.lowLightDetected}  motion=${a.motionDetected}"
                        delay(300)
                    }
                }
                withContext(Dispatchers.IO) { session.start() }
            }
            ambientPoller?.cancel()
            ambientView.visibility = View.GONE
            // Release the camera + MediaPipe resources regardless of
            // outcome. CDLSession.runLiveness closes `stream` already,
            // but owning the close here too makes the lifecycle robust
            // against a construction-time failure that never reached
            // CDLSession. close() is idempotent on both sides.
            try { stream?.close() } catch (t: Throwable) { Log.w(TAG, "stream close failed", t) }
            try { capture?.close() } catch (t: Throwable) { Log.w(TAG, "capture close failed", t) }

            promptView.text = ""
            statusView.text = result.fold(
                onSuccess = { "accepted=${it.accepted}" },
                onFailure = { "error: ${it.javaClass.simpleName}: ${it.message}" }
            )
            startButton.isEnabled = true
            controlsPanel.visibility = View.VISIBLE
        }
    }

    /**
     * Map a wire string to the single-line instruction shown on screen.
     * Unknown values render the raw wire string so a dev using a new
     * sequence sees it rather than a blank prompt — the server has
     * already validated the string set, so an unknown value here means
     * the SDK is out of sync with the relay enum.
     */
    private fun promptLabel(wire: String?): String = when (wire) {
        "blink" -> "Blink now"
        "look_left" -> "Look left"
        "look_right" -> "Look right"
        "smile" -> "Smile"
        "nod" -> "Nod"
        null -> ""
        else -> wire
    }

    private fun showPromptFor(wire: String?) {
        promptView.text = promptLabel(wire)
    }

    private fun advancePrompt(sequence: List<String>, progress: Float) {
        if (sequence.isEmpty()) return
        if (progress >= 1.0f) {
            promptView.text = "Processing..."
            return
        }
        // emit() fires AFTER sequencer.currentIndex increments, so
        // progress = completedCount / total. The next prompt is index
        // completedCount (= floor(progress * total)) — clamped so a
        // rounding edge doesn't walk us off the end.
        val next = floor(progress * sequence.size).toInt().coerceIn(0, sequence.size - 1)
        showPromptFor(sequence[next])
    }
}
