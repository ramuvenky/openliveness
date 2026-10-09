package dev.openliveness

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Locks in the Medium-severity fix for RelayClient.submitAttestation:
 * malformed /complete responses must throw CDLException, not silently
 * coerce to accepted=false.
 */
class RelayClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RelayClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = RelayClient(server.url("/").toString().trimEnd('/'))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `submitAttestation returns parsed response on success`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"accepted":true,"attestation_token":"tok-xyz"}""")
        )
        val resp = client.submitAttestation("s1", mapOf("ping" to "pong"))
        assertEquals(true, resp.accepted)
        assertEquals("tok-xyz", resp.attestationToken)
    }

    @Test
    fun `submitAttestation accepts null attestation_token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"accepted":false}"""))
        val resp = client.submitAttestation("s1", mapOf("ping" to "pong"))
        assertEquals(false, resp.accepted)
        assertNull(resp.attestationToken)
    }

    @Test
    fun `submitAttestation throws when accepted is missing`() {
        server.enqueue(MockResponse().setBody("""{"attestation_token":"tok"}"""))
        val ex = assertThrows(CDLException::class.java) {
            runBlocking { client.submitAttestation("s1", mapOf("ping" to "pong")) }
        }
        assertTrue(ex.message!!.contains("accepted"))
    }

    @Test
    fun `submitAttestation throws when accepted is wrong type`() {
        server.enqueue(MockResponse().setBody("""{"accepted":"yes"}"""))
        val ex = assertThrows(CDLException::class.java) {
            runBlocking { client.submitAttestation("s1", mapOf("ping" to "pong")) }
        }
        assertTrue(ex.message!!.contains("accepted"))
    }

    @Test
    fun `submitAttestation throws when attestation_token is wrong type`() {
        server.enqueue(
            MockResponse().setBody("""{"accepted":true,"attestation_token":123}""")
        )
        val ex = assertThrows(CDLException::class.java) {
            runBlocking { client.submitAttestation("s1", mapOf("ping" to "pong")) }
        }
        assertTrue(ex.message!!.contains("attestation_token"))
    }

    @Test
    fun `submitAttestation surfaces non-2xx with body`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val ex = assertThrows(CDLException::class.java) {
            runBlocking { client.submitAttestation("s1", mapOf("ping" to "pong")) }
        }
        assertTrue(ex.message!!.contains("500"))
        assertTrue(ex.message!!.contains("boom"))
    }
}
