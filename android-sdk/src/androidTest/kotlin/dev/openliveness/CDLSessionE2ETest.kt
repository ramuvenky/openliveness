package dev.openliveness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives CDLSession.start() against the local stub relay reached through
 * the emulator loopback (10.0.2.2 → host 127.0.0.1). Validates the Week 2
 * milestone: phone → WS acknowledged → signed attestation → relay accepts.
 */
@RunWith(AndroidJUnit4::class)
class CDLSessionE2ETest {

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
}
