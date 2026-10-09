package dev.openliveness.attestation

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import dev.openliveness.CDLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Play Integrity wrapper. Issues the basic token request; server-side
 * verification lives in the relay. If Play Integrity is not available on
 * the device the caller should fall back to `assurance_level =
 * "software_only"` and omit `device_attestation`.
 */
class PlayIntegrityService(private val context: Context) {

    suspend fun requestIntegrityToken(nonce: String): String {
        val integrityManager = IntegrityManagerFactory.create(context)
        val request = IntegrityTokenRequest.builder()
            .setNonce(nonce)
            .build()

        return suspendCoroutine { continuation ->
            integrityManager.requestIntegrityToken(request)
                .addOnSuccessListener { response -> continuation.resume(response.token()) }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(
                        CDLException("Play Integrity failed: ${e.message}")
                    )
                }
        }
    }
}
