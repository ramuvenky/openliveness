package dev.openliveness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for CDLSession's WebSocket handshake. The happy-path
 * E2E against the stub relay stays as `fullFlowAgainstStubRelay`; the
 * additional tests use MockWebServer's WebSocket upgrade so each failure
 * mode (silent peer, clean close before ack) can be forced deterministically.
 */
@RunWith(AndroidJUnit4::class)
class CDLSessionE2ETest {

    private var mockRelay: MockWebServer? = null

    @After
    fun tearDown() {
        mockRelay?.shutdown()
        mockRelay = null
    }

    @Test
    fun fullFlowAgainstStubRelay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionId = "sess-${System.currentTimeMillis()}"
        val payload = QRPayload(
            session_id = sessionId,
            challenge_id = "ch-1",
            challenge_sequence = listOf("blink", "turn_left"),
            relay_ws = "ws://10.0.2.2:8787/cdl/session/$sessionId/ws",
            relay_http = "http://10.0.2.2:8787",
            expires_at = "2099-01-01T00:00:00Z",
            version = "1.0"
        )

        val response = CDLSession(payload, context).start()

        assertEquals(true, response.accepted)
        assertNotNull(response.attestationToken)
        assertTrue(response.attestationToken!!.startsWith("stub-attest-"))
    }

    /// Locks in the High-severity fix: if the peer closes the WebSocket
    /// cleanly BEFORE sending session_acknowledged, start() must throw a
    /// CDLException rather than suspending forever. Prior to the fix,
    /// CDLSession overrode only onMessage/onFailure and would hang.
    @Test
    fun startThrowsWhenWebSocketClosesBeforeAcknowledgement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer().also { mockRelay = it }
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                    // Immediately start a clean close instead of sending ack.
                    ws.close(1000, "no ack")
                }
            })
        )
        server.start()
        val wsUrl = server.url("/cdl/session/x/ws").toString().replaceFirst("http", "ws")
        val payload = QRPayload(
            session_id = "x",
            challenge_id = "c",
            challenge_sequence = listOf("blink"),
            relay_ws = wsUrl,
            relay_http = server.url("/").toString().trimEnd('/'),
            expires_at = "2099-01-01T00:00:00Z",
            version = "1.0"
        )

        val ex = assertThrows(CDLException::class.java) {
            runBlocking { CDLSession(payload, context).start() }
        }
        assertTrue(
            "expected close-before-ack message, got: ${ex.message}",
            ex.message!!.contains("acknowledgement")
        )
    }

    /// Locks in the High-severity fix: if the peer opens the WebSocket but
    /// never emits a frame, start() must terminate via the ack timeout
    /// instead of suspending forever. Uses a short timeout so the test is
    /// quick.
    @Test
    fun startTimesOutWhenPeerStaysSilent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer().also { mockRelay = it }
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                // Deliberately do nothing on open — simulate a silent peer.
                // Echoing the client's close is still required: without it the
                // server-side WebSocket stays half-open after CDLSession's
                // timeout closes the client end, and MockWebServer.shutdown()
                // in @After throws "Gave up waiting for queue to shut down".
                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(code, reason)
                }
            })
        )
        server.start()
        val wsUrl = server.url("/cdl/session/x/ws").toString().replaceFirst("http", "ws")
        val payload = QRPayload(
            session_id = "x",
            challenge_id = "c",
            challenge_sequence = listOf("blink"),
            relay_ws = wsUrl,
            relay_http = server.url("/").toString().trimEnd('/'),
            expires_at = "2099-01-01T00:00:00Z",
            version = "1.0"
        )

        val started = System.currentTimeMillis()
        val ex = assertThrows(CDLException::class.java) {
            runBlocking { CDLSession(payload, context, ackTimeoutMs = 500).start() }
        }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("expected timeout message, got: ${ex.message}", ex.message!!.contains("timeout"))
        assertTrue(
            "ack timeout should fire within a few seconds, took ${elapsed}ms",
            elapsed < 5_000
        )
    }
}
