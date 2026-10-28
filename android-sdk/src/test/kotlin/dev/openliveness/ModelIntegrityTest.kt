package dev.openliveness

import dev.openliveness.layers.verifyModelSha256
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Covers the sha256 pinning that `MediaPipeLandmarkStream.fromAsset`
 * optionally runs over the FaceLandmarker model bytes. The guarantee
 * is: a hostile swap of face_landmarker.task fails fast at construction
 * instead of silently loading an attacker-chosen graph.
 */
class ModelIntegrityTest {

    private fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `matching sha256 verifies`() {
        val bytes = "the-real-model-bytes".toByteArray()
        assertTrue(verifyModelSha256(bytes, sha256Hex(bytes)))
    }

    @Test
    fun `mismatched sha256 fails verification`() {
        val bytes = "the-real-model-bytes".toByteArray()
        val wrongHash = sha256Hex("different-bytes".toByteArray())
        assertFalse(verifyModelSha256(bytes, wrongHash),
            "swapped bytes must fail — this is the supply-chain tripwire")
    }

    @Test
    fun `uppercase hex is accepted`() {
        val bytes = "foo".toByteArray()
        val hex = sha256Hex(bytes)
        assertTrue(verifyModelSha256(bytes, hex.uppercase()),
            "pin strings are often copy-pasted from docs that use uppercase hex")
    }

    @Test
    fun `wrong length hex fails without reading all bytes`() {
        val bytes = "foo".toByteArray()
        // Truncated hash of the right prefix is a classic shortening
        // attack — must not pass verification.
        val truncated = sha256Hex(bytes).substring(0, 32)
        assertFalse(verifyModelSha256(bytes, truncated))
    }

    @Test
    fun `empty bytes have known sha256`() {
        // SHA-256 of empty is a famous constant. Pin to catch a buggy
        // digest init (e.g., MessageDigest.getInstance mis-specced to
        // "SHA-1" would still return a plausible-looking hex).
        val empty = ByteArray(0)
        val emptySha256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertTrue(verifyModelSha256(empty, emptySha256))
    }

    @Test
    fun `one byte flip fails verification`() {
        val bytes = ByteArray(1024) { it.toByte() }
        val correctHash = sha256Hex(bytes)
        bytes[500] = (bytes[500] + 1).toByte()
        assertFalse(verifyModelSha256(bytes, correctHash),
            "a single byte flip must trip the pin — avalanche is the whole point of a cryptographic hash")
    }
}
