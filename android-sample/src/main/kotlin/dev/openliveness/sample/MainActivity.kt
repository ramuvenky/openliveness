package dev.openliveness.sample

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
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
import dev.openliveness.OpenLiveness
import dev.openliveness.parseQRPayload
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * Dev harness for the openliveness Android SDK. Not a shippable app — it
 * exists so a human can install an APK, grant the camera permission,
 * paste a QR payload, and watch the full flow run on-device.
 *
 * The integration is exactly what a downstream app would ship: parse the
 * payload, pass it to [OpenLiveness.runFrontCameraSession] with a
 * [PreviewView] and a [LifecycleOwner], render prompts off the progress
 * callback. No manual layer wiring; the SDK owns MediaPipe + Layer-3/4 +
 * ambient sampling + Play Integrity + the WebSocket.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var promptView: TextView
    private lateinit var statusView: TextView
    private lateinit var qrInput: EditText
    private lateinit var startButton: Button
    private lateinit var controlsPanel: LinearLayout

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
        statusView.text = if (alreadyGranted) "Ready. Paste QR payload."
        else "Tap \"Request camera\" to continue"
    }

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
        // camera preview and the challenge prompt are fully visible.
        controlsPanel.visibility = View.GONE

        lifecycleScope.launch {
            val result = runCatching {
                val payload = parseQRPayload(raw)
                statusView.text = "Opening camera..."
                showPromptFor(payload.challenge_sequence.firstOrNull())
                OpenLiveness.runFrontCameraSession(
                    payload = payload,
                    context = applicationContext,
                    lifecycleOwner = this@MainActivity,
                    previewView = previewView,
                    onProgress = { progress, _ ->
                        lifecycleScope.launch {
                            advancePrompt(payload.challenge_sequence, progress)
                        }
                    },
                )
            }

            promptView.text = ""
            statusView.text = result.fold(
                onSuccess = { "accepted=${it.accepted}" },
                onFailure = { "error: ${it.javaClass.simpleName}: ${it.message}" }
            )
            startButton.isEnabled = true
            controlsPanel.visibility = View.VISIBLE
        }
    }

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
        val next = floor(progress * sequence.size).toInt().coerceIn(0, sequence.size - 1)
        showPromptFor(sequence[next])
    }
}
