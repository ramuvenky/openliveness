package dev.openliveness

import android.content.Context
import dev.openliveness.attestation.AttestationBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Entry point for the Android side of a CDL session. Construct from the
 * scanned QRPayload and a Context, call start(), and the SDK walks the
 * full Week 1 flow: open WebSocket → wait for session_acknowledged → run
 * liveness (stub) → POST signed attestation → return relay response.
 */
class CDLSession(val payload: QRPayload, private val context: Context) {

    private val relay = RelayClient(payload.relay_http)
    private val http = OkHttpClient()
    private var webSocket: WebSocket? = null

    suspend fun start(): RelayClient.CompleteResponse {
        openWebSocket()
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
        val response = relay.submitAttestation(payload.session_id, attestation)
        closeWebSocket()
        return response
    }

    // MARK: - WebSocket

    private val acknowledged = CompletableDeferred<Unit>()

    private suspend fun openWebSocket() = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(payload.relay_ws).build()
        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                val type = runCatching { JSONObject(text).optString("type") }.getOrNull()
                if (type == "session_acknowledged" && !acknowledged.isCompleted) {
                    acknowledged.complete(Unit)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!acknowledged.isCompleted) {
                    acknowledged.completeExceptionally(
                        CDLException("WebSocket failure: ${t.message}")
                    )
                }
            }
        })
    }

    private suspend fun waitForAcknowledgement() {
        acknowledged.await()
    }

    private fun closeWebSocket() {
        webSocket?.close(1000, null)
        webSocket = null
    }
}
