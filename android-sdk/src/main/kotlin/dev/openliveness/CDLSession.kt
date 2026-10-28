package dev.openliveness

import android.content.Context
import android.util.Log
import dev.openliveness.attestation.AttestationBuilder
import dev.openliveness.attestation.PlayIntegrityClient
import dev.openliveness.attestation.PlayIntegrityService
import dev.openliveness.layers.ChallengeResult
import dev.openliveness.layers.ChallengeSequencer
import dev.openliveness.layers.LandmarkStream
import dev.openliveness.layers.Layer2Runner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Entry point for the Android side of a CDL session. Construct from the
 * scanned QRPayload and a Context, call start(), and the SDK walks the
 * full flow: open WebSocket → wait for session_acknowledged → run
 * liveness → POST signed attestation → return relay response.
 */
class CDLSession(
    val payload: QRPayload,
    context: Context,
    /** Overridable so tests can exercise the timeout path without waiting 30 s. */
    private val ackTimeoutMs: Long = DEFAULT_ACK_TIMEOUT_MS,
    /** Overridable so tests can stub the Play Integrity dependency, which is
     *  unavailable off-device. Default resolves against the real service. */
    playIntegrity: PlayIntegrityClient? = null,
    /** Optional live-camera landmark source. When null, start() takes the
     *  Phase-1 stub path only if [allowStubMode] is true; otherwise it
     *  throws a CDLException at run time. When non-null, start() drives
     *  a ChallengeSequencer against the stream and the attestation
     *  reports the real Layer-2 result. */
    private val landmarkStream: LandmarkStream? = null,
    /** Escape hatch for the Phase-1 stub liveness path. Default `false`
     *  so a production integrator who forgets to inject a [landmarkStream]
     *  gets a loud CDLException at `start()` rather than silently shipping
     *  an SDK that always passes liveness. Unit tests and relay CI e2e
     *  that still need the stub path must set this to `true` explicitly
     *  — the explicit opt-in is the tripwire. The server-side `stub_mode`
     *  wire marker (see AttestationBuilder) is the defense-in-depth half
     *  of this gate: even if a hostile fork sets this flag to `true`,
     *  the relay can refuse `stub_mode: true` payloads from production
     *  session IDs. */
    private val allowStubMode: Boolean = false,
    /** UI-side progress callback. Fired on every WS processing_update
     *  with the same (progress, currentLayer) pair the server sees, so
     *  the sample app's prompt overlay can advance in lockstep with the
     *  server view. Invoked on the IO dispatcher — implementations that
     *  touch Views must post to main themselves. Default null so unit
     *  tests and headless callers aren't forced to supply one. */
    private val onProgress: ((progress: Float, currentLayer: String) -> Unit)? = null,
) {

    // Pin to applicationContext so the session never retains an Activity.
    // Phase 2's PlayIntegrityService captures this reference for the
    // lifetime of the request; if a caller passed an Activity we'd leak
    // the whole Activity + view hierarchy once start() suspends.
    private val context: Context = context.applicationContext ?: context
    private val playIntegrity: PlayIntegrityClient =
        playIntegrity ?: PlayIntegrityService(this.context)

    private val relay = RelayClient(payload.relay_http)
    private var webSocket: WebSocket? = null

    init {
        checkStubGate(allowStubMode, BuildConfig.STUB_PATH_ENABLED)
    }

    suspend fun start(): RelayClient.CompleteResponse {
        openWebSocket()
        try {
            waitForAcknowledgement()

            // M-4 / Phase 5 TODO: these are the default-constructed ambient
            // flags (all false). The orchestrator's low-light / motion
            // reweighting branches are therefore inert in Phase 3. Phase 5
            // wires in the real ALS + accelerometer readings via
            // AmbientConditionsSampler so the ambient-aware weights
            // actually kick in — until then this is a known inert path,
            // not a bug.
            val ambient = AmbientConditions()
            val (scores, challengeResult) = runLiveness(ambient)
            // Pre-compute the decision once so the attestation payload and
            // the signed assertion (future Phase 2+ platforms that need
            // signed client data) stay in sync even if the decision function
            // ever becomes non-deterministic.
            val decision = if (challengeResult != null) {
                LivenessOrchestrator().orchestrate(scores, ambient).decision
            } else {
                AttestationBuilder.stubLivenessDecision(scores)
            }
            val hw = requestDeviceAttestation()
            // `challengeResult == null` is the signal that runLiveness fell
            // back to the stub path (allowStubMode=true + no landmarkStream).
            // Stamp the wire marker so the relay can enforce production
            // policy — stub_mode=true outside allow-listed CI session IDs
            // is a server-side rejection.
            val attestation = AttestationBuilder.build(
                context = context,
                session = this,
                layerScores = scores,
                ambient = ambient,
                livenessDecision = decision,
                deviceAttestation = hw.token,
                hwAttestationStatus = hw.status,
                challengeResult = challengeResult,
                stubMode = (challengeResult == null),
            )
            return relay.submitAttestation(payload.session_id, attestation)
        } finally {
            closeWebSocket()
        }
    }

    /**
     * Return real Layer-2 scores + the sequencer's ChallengeResult when
     * a landmark stream was supplied; fall back to Phase-1 stub scores
     * (and null result) when [allowStubMode] is true. Throws otherwise —
     * a release-build integrator who forgets to inject a landmarkStream
     * gets a loud failure here instead of a silently-passing liveness
     * check. The null-result signal is still what tells `.build(...)`
     * to echo the legacy challenge_response and stamp `stub_mode=true`.
     */
    private suspend fun runLiveness(ambient: AmbientConditions): Pair<LayerScores, ChallengeResult?> {
        val stream = landmarkStream
        if (stream == null) {
            if (!allowStubMode) {
                throw CDLException(
                    "no landmarkStream provided and allowStubMode=false; " +
                    "stub liveness path is disabled in production builds — " +
                    "wire a real LandmarkStream or set allowStubMode=true in test harnesses"
                )
            }
            return AttestationBuilder.stubLayerScores() to null
        }
        val sequencer = ChallengeSequencer.fromWire(payload.challenge_sequence)
        val runner = Layer2Runner(sequencer, stream) { progress, layer ->
            sendProcessingUpdate(progress, layer)
            // Isolate integrator-supplied callback: a thrown View touch
            // or state check must not fail the session. CancellationException
            // still propagates (see invokeProgressCallbackSafely kdoc).
            val callbackError = invokeProgressCallbackSafely(onProgress, progress, layer)
            if (callbackError != null) {
                Log.w(LOG_TAG, "onProgress callback threw; continuing session", callbackError)
            }
        }
        val result = try {
            runner.run()
        } finally {
            // Release MediaPipe FaceLandmarker + result channel the
            // moment the sequencer finishes (or the stream errors out).
            // The default close() on LandmarkStream is a no-op so test
            // fakes aren't affected; production MediaPipeLandmarkStream
            // uses this to tear down native resources promptly rather
            // than waiting on GC.
            stream.close()
        }

        // Only layer2 is real in Phase 3; layers 3/4/5 stay at stub
        // confidence so the weighted sum doesn't collapse to a fail
        // just because other detectors haven't shipped yet. The hard
        // rule still triggers via layer2ChallengeCompleted == false
        // when the user doesn't complete the challenge.
        val scores = AttestationBuilder.stubLayerScores().copy(
            layer2ChallengeCompleted = result.completed,
            layer2ChallengeScore = if (result.completed) result.score else 0f
        )
        return scores to result
    }

    /**
     * Encode and send a processing_update frame. Silent-fails if the WS
     * is already closed — a lost progress frame must not fail the
     * session; the server's state machine advances on /complete, not on
     * progress breadcrumbs.
     */
    private fun sendProcessingUpdate(progress: Float, currentLayer: String) {
        // M-2: capture `webSocket` into a local before anything else so a
        // concurrent `closeWebSocket()` that nulls the property can't make
        // our `ws.send(...)` NPE on a torn read. OkHttp's WebSocket.send
        // is itself thread-safe and returns false on a closing connection
        // rather than throwing, so the local-capture pattern is sufficient
        // defense here — no explicit lock needed.
        val ws = webSocket ?: return
        val payload = JSONObject().apply {
            put("type", "processing_update")
            put("progress", progress.toDouble())
            put("current_layer", currentLayer)
        }
        // OkHttp's WebSocket.send returns false when the outbound buffer is
        // full or the socket is closing. Progress frames are advisory so we
        // still proceed — but a silent drop makes "why did the browser UI
        // freeze at 1/3?" un-diagnosable. DEBUG level since this is noise on
        // a healthy session; relevant only during triage. Rate-limited to
        // at most one line per DROP_LOG_INTERVAL_MS so a sustained congestion
        // doesn't flood logcat.
        if (!ws.send(payload.toString())) {
            val now = System.currentTimeMillis()
            if (now - lastDropLogMs >= DROP_LOG_INTERVAL_MS) {
                lastDropLogMs = now
                Log.d(LOG_TAG, "processing_update dropped (ws buffer full or closing)")
            }
        }
    }

    private var lastDropLogMs: Long = 0L

    /**
     * Result of the hardware-attestation attempt. `token` is non-null only
     * when `status == OK`; the status field lets the relay distinguish
     * "device cannot attest" (unsupported) from "device could attest but
     * the call failed" (failed) — the second is a downgrade-attack signal
     * when it clusters on previously-ok devices.
     */
    data class HardwareAttestationResult(val token: String?, val status: HwStatus)

    enum class HwStatus(val wire: String) {
        OK("ok"), UNSUPPORTED("unsupported"), FAILED("failed")
    }

    /**
     * Attempts hardware attestation via Play Integrity. Any failure
     * downgrades to `software_only` so the session stays alive, but the
     * status tag tells the server which bucket the fallback came from.
     *
     * CancellationException MUST re-throw: Kotlin coroutines' structured
     * concurrency relies on it to unwind nested suspends. Catching
     * `Exception` would swallow it and leave the parent scope believing
     * the child completed normally.
     */
    internal suspend fun requestDeviceAttestation(): HardwareAttestationResult = try {
        val nonce = relay.requestIntegrityNonce(payload.session_id)
        val token = playIntegrity.requestIntegrityToken(nonce)
        HardwareAttestationResult(token, HwStatus.OK)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PlayIntegrityUnavailableException) {
        // Environmental — device cannot do hardware attestation at all
        // (missing Play Services, outdated Play Store, etc.). Not a
        // downgrade signal: tag UNSUPPORTED so the server-side clustering
        // heuristic doesn't light up for every non-Play Android device.
        // Log at INFO because this is expected on AOSP / Huawei / etc.
        Log.i(LOG_TAG, "hardware attestation unavailable; tagging UNSUPPORTED", e)
        HardwareAttestationResult(null, HwStatus.UNSUPPORTED)
    } catch (e: Exception) {
        // The server sees a bucket label (ok / unsupported / failed) but no
        // detail — without a local log the `failed` bucket is opaque in
        // triage. The exception message is a system-API string (Play Services
        // status, OkHttp network error), not user data, so logging at WARN is
        // safe. Downstream treatment is still a soft downgrade to FAILED.
        Log.w(LOG_TAG, "hardware attestation failed; downgrading to FAILED", e)
        HardwareAttestationResult(null, HwStatus.FAILED)
    }

    // MARK: - WebSocket

    private val acknowledged = CompletableDeferred<Unit>()

    private suspend fun openWebSocket() = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(payload.relay_ws).build()
        webSocket = relay.http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                val type = runCatching { JSONObject(text).optString("type") }.getOrNull()
                if (type == "session_acknowledged" && !acknowledged.isCompleted) {
                    acknowledged.complete(Unit)
                }
            }

            // The onClosing/onClosed overrides are load-bearing: without them a
            // peer that closes cleanly before sending session_acknowledged
            // would leave `acknowledged` dangling forever and start() would
            // suspend indefinitely.
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                failIfPending(CDLException("WebSocket closing before acknowledgement ($code: $reason)"))
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                failIfPending(CDLException("WebSocket closed before acknowledgement ($code: $reason)"))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                failIfPending(CDLException("WebSocket failure: ${t.message}"))
            }

            private fun failIfPending(e: CDLException) {
                if (!acknowledged.isCompleted) {
                    acknowledged.completeExceptionally(e)
                }
            }
        })
    }

    /// Overall deadline for session_acknowledged. Together with the
    /// onClosing/onClosed overrides above this guarantees waitForAcknowledgement
    /// terminates — the listener covers "peer closed cleanly without an ack"
    /// and the timeout covers "peer went silent".
    private suspend fun waitForAcknowledgement() {
        try {
            withTimeout(ackTimeoutMs) {
                acknowledged.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw CDLException("timeout waiting for session_acknowledged")
        }
    }

    private fun closeWebSocket() {
        webSocket?.close(1000, null)
        webSocket = null
    }

    companion object {
        const val DEFAULT_ACK_TIMEOUT_MS: Long = 30_000
        private const val LOG_TAG = "OpenLiveness"
        /** Minimum spacing between processing_update-dropped log lines. One
         *  per second is enough to see a congestion event in triage without
         *  spamming 30 lines/sec on a chronically-stuck WS. */
        private const val DROP_LOG_INTERVAL_MS: Long = 1_000

        /**
         * Compile-time stub gate. [stubPathEnabled] is wired from
         * BuildConfig.STUB_PATH_ENABLED at the init-block call site — `true`
         * in debug AAR variants and `false` in release, declared in
         * android-sdk/build.gradle.kts. Refusing [allowStubMode] = true when
         * [stubPathEnabled] is false mirrors the iOS OPENLIVENESS_STUB_ALLOWED
         * SwiftPM flag: a release AAR cannot be constructed into the stub
         * path, regardless of what a hostile fork does to the default value
         * or the runtime caller.
         *
         * Extracted from init() so a JVM unit test can drive both branches
         * of the gate — BuildConfig.STUB_PATH_ENABLED is baked in at AAR
         * compile time and would otherwise be unreachable from an in-process
         * test. The server-side STUB_MODE_RP_ALLOWLIST remains the second
         * layer of defense.
         */
        @JvmStatic
        internal fun checkStubGate(allowStubMode: Boolean, stubPathEnabled: Boolean) {
            if (allowStubMode && !stubPathEnabled) {
                throw IllegalStateException(
                    "allowStubMode=true but stub path is disabled in this build variant " +
                    "(BuildConfig.STUB_PATH_ENABLED=false). The Phase-1 stub liveness path " +
                    "is compiled out of release builds — use the debug SDK variant for " +
                    "test harnesses, or wire a real LandmarkStream."
                )
            }
        }
    }
}
