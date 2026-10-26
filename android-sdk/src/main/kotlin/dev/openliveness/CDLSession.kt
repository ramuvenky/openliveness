package dev.openliveness

import android.content.Context
import android.util.Log
import dev.openliveness.attestation.AttestationBuilder
import dev.openliveness.attestation.PlayIntegrityClient
import dev.openliveness.attestation.PlayIntegrityService
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

    suspend fun start(): RelayClient.CompleteResponse {
        openWebSocket()
        try {
            waitForAcknowledgement()

            val scores = AttestationBuilder.stubLayerScores()
            val ambient = AmbientConditions()
            // Pre-compute the decision once so the attestation payload and
            // the signed assertion (future Phase 2+ platforms that need
            // signed client data) stay in sync even if the decision function
            // ever becomes non-deterministic.
            val decision = AttestationBuilder.livenessDecision(scores)
            val hw = requestDeviceAttestation()
            val attestation = AttestationBuilder.build(
                context = context,
                session = this,
                layerScores = scores,
                ambient = ambient,
                livenessDecision = decision,
                deviceAttestation = hw.token,
                hwAttestationStatus = hw.status,
            )
            return relay.submitAttestation(payload.session_id, attestation)
        } finally {
            closeWebSocket()
        }
    }

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
    }
}
