package dev.openliveness

import android.content.Context
import dev.openliveness.attestation.AttestationBuilder
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
    private val context: Context,
    /** Overridable so tests can exercise the timeout path without waiting 30 s. */
    private val ackTimeoutMs: Long = DEFAULT_ACK_TIMEOUT_MS,
) {

    private val relay = RelayClient(payload.relay_http)
    private var webSocket: WebSocket? = null

    suspend fun start(): RelayClient.CompleteResponse {
        openWebSocket()
        try {
            waitForAcknowledgement()

            val scores = AttestationBuilder.stubLayerScores()
            val ambient = AmbientConditions()
            val attestation = AttestationBuilder.build(
                context = context,
                session = this,
                layerScores = scores,
                ambient = ambient,
                deviceAttestation = null
            )
            return relay.submitAttestation(payload.session_id, attestation)
        } finally {
            closeWebSocket()
        }
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
    }
}
