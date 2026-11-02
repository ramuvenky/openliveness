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

    /// Preconditions on the host (both emulator and USB device):
    ///   1. `python tools/stub-relay/server.py` running on 127.0.0.1:8787.
    ///   2. `adb reverse tcp:8787 tcp:8787` so the device's `localhost`
    ///      reaches the host port. Works for both AVD (emulator-5554)
    ///      and USB (serial-id). The older address `10.0.2.2` only
    ///      resolves to host loopback on the emulator — using `localhost`
    ///      + reverse-port keeps the test portable.
    @Test
    fun fullFlowAgainstStubRelay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionId = "sess-${System.currentTimeMillis()}"
        val payload = QRPayload(
            session_id = sessionId,
            challenge_id = "ch-1",
            challenge_sequence = listOf("blink", "turn_left"),
            relay_ws = "ws://localhost:8787/cdl/session/$sessionId/ws",
            relay_http = "http://localhost:8787",
            expires_at = "2099-01-01T00:00:00Z",
            version = "1.0"
        )

        // allowStubMode=true: this E2E runs without a camera / landmarkStream,
        // so it deliberately exercises the stub path. Production builds that
        // omit the stream get a hard throw instead.
        val response = CDLSession(payload, context, allowStubMode = true).start()

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

    /// S-NEW-1(b) — production builds must reject the stub-liveness path.
    /// A CDLSession constructed without a landmarkStream and with the
    /// default `allowStubMode=false` must throw a CDLException at
    /// start()-time rather than silently running stub scores. The ack
    /// must land first so the throw comes from runLiveness (the gate),
    /// not from the ack timeout — if a regression moved the stub path
    /// earlier in start() this test would still catch it, but the exact
    /// message assertion pins down the gate location.
    @Test
    fun startThrowsWhenStubModeDisallowedAndNoLandmarkStream() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer().also { mockRelay = it }
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                    // Ack immediately so start() reaches runLiveness, where
                    // the stub-gate throw lives.
                    ws.send("""{"type":"session_acknowledged"}""")
                }
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

        // Default allowStubMode=false, no landmarkStream — the production
        // configuration a downstream integrator ships. Must throw.
        val ex = assertThrows(CDLException::class.java) {
            runBlocking { CDLSession(payload, context).start() }
        }
        assertTrue(
            "expected stub-gate message, got: ${ex.message}",
            ex.message!!.contains("stub liveness path is disabled")
        )
    }

    /// Regression: when the WS handshake gets back a non-101 HTTP reply
    /// (relay misroute, bad path in the QR, auth proxy in front, ...),
    /// the CDLException must surface the HTTP status code so the
    /// integrator can tell "I pointed at the wrong path" apart from
    /// "the server is down". Before the fix the message was just
    /// "unexpected end of stream" — unactionable.
    @Test
    fun startSurfacesHttpStatusWhenWebSocketUpgradeIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer().also { mockRelay = it }
        // Plain 404 (no withWebSocketUpgrade) — okhttp's WS client sees
        // "expected 101, got 404" and triggers onFailure with a non-null
        // Response holding the code.
        server.enqueue(MockResponse().setResponseCode(404))
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
            runBlocking { CDLSession(payload, context, allowStubMode = true).start() }
        }
        assertTrue(
            "expected HTTP 404 in message, got: ${ex.message}",
            ex.message!!.contains("HTTP 404")
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
