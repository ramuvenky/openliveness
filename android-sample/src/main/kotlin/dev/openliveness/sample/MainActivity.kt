package dev.openliveness.sample

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.openliveness.CDLSession
import dev.openliveness.parseQRPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dev harness for the openliveness Android SDK. This is NOT a shippable
 * app — it exists only so a human can install an APK, grant the camera
 * permission, paste a QR payload, and watch CDLSession.start() exercise
 * the full flow on-device.
 *
 * Phase-3 Step-5 wiring (MediaPipe LandmarkStream + prompt UI) plugs in
 * where the TODO below sits.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var qrInput: EditText
    private lateinit var statusView: TextView
    private lateinit var startButton: Button

    companion object {
        private const val TAG = "OpenLivenessSample"
        // Flipped once per process so the banner / logcat line don't
        // spam every start() call. The banner itself remains visible for
        // the lifetime of the Activity so a screenshot of the sample
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
            "Camera permission granted"
        } else {
            "Tap \"Request camera\" to continue"
        }
    }

    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            gravity = Gravity.TOP
        }
        // Dev-harness warning banner. The sample constructs CDLSession
        // with allowStubMode=true (see runSession below), which means the
        // Phase-1 hardcoded "always pass" liveness scores are emitted.
        // The server allowlist (STUB_MODE_RP_ALLOWLIST) is the operator's
        // defense; the banner is the developer's. Keep it visible on every
        // screenshot so anyone reviewing the sample's output can't
        // mistake it for real liveness.
        val warningBanner = TextView(this).apply {
            text = "⚠ DEV HARNESS — allowStubMode=true, do not point at a production relay"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(0xB7, 0x1C, 0x1C)) // material red 900
            setPadding(24, 24, 24, 24)
            gravity = Gravity.CENTER
        }
        qrInput = EditText(this).apply {
            hint = "Paste QR payload (base64)"
            minLines = 3
        }
        statusView = TextView(this).apply {
            text = ""
            setPadding(0, 32, 0, 32)
        }
        val permBtn = Button(this).apply {
            text = "Request camera"
            setOnClickListener { cameraPermission.launch(Manifest.permission.CAMERA) }
        }
        startButton = Button(this).apply {
            text = "Start liveness"
            setOnClickListener { runSession() }
        }
        root.addView(warningBanner)
        root.addView(qrInput)
        root.addView(permBtn)
        root.addView(startButton)
        root.addView(statusView)
        return root
    }

    private fun runSession() {
        val raw = qrInput.text.toString().trim()
        if (raw.isEmpty()) {
            statusView.text = "Paste a QR payload first"
            return
        }
        startButton.isEnabled = false
        statusView.text = "Running..."
        // Loud logcat line, once per process. Prefixed with the ⚠ glyph so
        // a `logcat | grep '⚠'` triage pass surfaces it immediately. If the
        // operator ever points this sample at a non-allowlisted relay they
        // get a server 403 (ERR_STUB_MODE_FORBIDDEN) — this log tells them
        // which side of the gate they tripped.
        if (!stubWarningLogged) {
            Log.w(
                TAG,
                "⚠ DEV HARNESS: constructing CDLSession with allowStubMode=true. " +
                "Attestations will carry stub_mode=true and the relay will refuse " +
                "them unless the relying_party is on STUB_MODE_RP_ALLOWLIST."
            )
            stubWarningLogged = true
        }
        lifecycleScope.launch {
            val result = runCatching {
                val payload = parseQRPayload(raw)
                // TODO(Phase 3 Step 5): construct and inject a MediaPipe-
                // backed LandmarkStream here. Until then, allowStubMode=true
                // keeps the plumbing exercisable end-to-end against a relay.
                val session = CDLSession(
                    payload = payload,
                    context = applicationContext,
                    allowStubMode = true,
                )
                withContext(Dispatchers.IO) { session.start() }
            }
            statusView.text = result.fold(
                onSuccess = { "accepted=${it.accepted}" },
                onFailure = { "error: ${it.javaClass.simpleName}: ${it.message}" }
            )
            startButton.isEnabled = true
        }
    }
}
