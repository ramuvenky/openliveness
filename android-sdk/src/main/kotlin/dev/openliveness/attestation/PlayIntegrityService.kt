package dev.openliveness.attestation

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.model.IntegrityErrorCode
import dev.openliveness.CDLException
import dev.openliveness.PlayIntegrityUnavailableException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Narrow seam over Play Integrity so CDLSession doesn't bind to the
 * concrete Play Services dependency. Prod code uses PlayIntegrityService;
 * tests inject a stub that throws / returns a fixed token. Interface
 * (rather than `open class`) because the only legitimate extension point
 * is test injection — not a surface we want production consumers overriding.
 */
interface PlayIntegrityClient {
    suspend fun requestIntegrityToken(nonce: String): String
}

/**
 * Play Integrity wrapper. Issues the basic token request; server-side
 * verification lives in the relay. If Play Integrity is not available on
 * the device the caller should fall back to `assurance_level =
 * "software_only"` and omit `device_attestation`.
 */
class PlayIntegrityService(private val context: Context) : PlayIntegrityClient {

    override suspend fun requestIntegrityToken(nonce: String): String {
        val integrityManager = IntegrityManagerFactory.create(context)
        val request = IntegrityTokenRequest.builder()
            .setNonce(nonce)
            .build()

        return suspendCoroutine { continuation ->
            integrityManager.requestIntegrityToken(request)
                .addOnSuccessListener { response -> continuation.resume(response.token()) }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(classify(e))
                }
        }
    }

    companion object {
        /**
         * Translate a Play Integrity failure into a typed exception. Codes
         * meaning "device cannot attest at all" become PlayIntegrityUnavailableException
         * (→ server-visible HwStatus.UNSUPPORTED, no downgrade-attack alarm).
         * Everything else, including transient / API-side failures, becomes
         * CDLException (→ HwStatus.FAILED, the signal the server clusters on).
         *
         * Visible for testing so the mapping can be pinned without having
         * to construct real Play Services exceptions in instrumentation tests.
         */
        internal fun classify(error: Throwable): Exception {
            val code = (error as? IntegrityServiceException)?.errorCode ?: return CDLException(
                "Play Integrity failed: ${error.message}",
                error,
            )
            return if (code in UNAVAILABLE_CODES) {
                PlayIntegrityUnavailableException(
                    "Play Integrity unavailable (code=$code)",
                    error,
                )
            } else {
                CDLException("Play Integrity failed (code=$code): ${error.message}", error)
            }
        }

        // Codes that mean "this device/environment can never attest" — not a
        // downgrade signal. Deliberately conservative: NETWORK_ERROR and the
        // transient / retriable buckets stay in CDLException so a sustained
        // failure pattern still reaches the server's clustering heuristic.
        private val UNAVAILABLE_CODES = setOf(
            IntegrityErrorCode.API_NOT_AVAILABLE,
            IntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
            IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
            IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
            IntegrityErrorCode.PLAY_STORE_NOT_FOUND,
            IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
            IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND,
        )
    }
}
