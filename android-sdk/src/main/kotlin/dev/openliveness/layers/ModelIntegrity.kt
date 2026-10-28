package dev.openliveness.layers

import java.security.MessageDigest

/**
 * SHA-256 verification for the FaceLandmarker `.task` asset. A caller
 * who pins the model's expected hash gets a loud, early failure if a
 * build pipeline (or hostile fork) swaps the bytes — matching the
 * stub_mode defence pattern elsewhere in the SDK.
 *
 * The comparison is constant-time so a mismatch doesn't leak byte-wise
 * timing. SHA-256 collisions are a non-issue for this scope (benign
 * swap detection, not adversarial preimage); the constant-time compare
 * is cheap enough to add anyway.
 */
internal fun verifyModelSha256(bytes: ByteArray, expectedHex: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val actualHex = StringBuilder(digest.size * 2).apply {
        for (b in digest) append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
    }.toString()
    val normalizedExpected = expectedHex.lowercase()
    return constantTimeEquals(actualHex, normalizedExpected)
}

private val HEX = charArrayOf('0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f')

private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var r = 0
    for (i in a.indices) r = r or (a[i].code xor b[i].code)
    return r == 0
}
