package dev.openliveness.attestation

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityToken
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.model.IntegrityErrorCode
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import dev.openliveness.CDLException
import dev.openliveness.PlayIntegrityUnavailableException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Narrow seam over Play Integrity so CDLSession doesn't bind to the
 * concrete Play Services dependency. Prod code uses PlayIntegrityService;
 * tests inject a stub that throws / returns a fixed token. Interface
 * (rather than `open class`) because the only legitimate extension point
 * is test injection — not a surface we want production consumers overriding.
 *
 * The parameter is a request-bound hash (base64url SHA-256 of the
 * server-issued nonce concatenated with the liveness-decision fields the
 * SDK is about to post). Standard Play Integrity binds this value into
 * the hardware-signed verdict via `requestDetails.requestHash`, closing
 * the "hostile SDK fork flips liveness_decision after the fact" gap that
 * the Classic nonce-only API left open.
 */
interface PlayIntegrityClient {
    suspend fun requestIntegrityToken(requestHash: String): String
}

/**
 * Play Integrity Standard wrapper. On first use, warms up the provider
 * via `prepareIntegrityToken(cloudProjectNumber)` and caches it for the
 * lifetime of this service instance; subsequent `requestIntegrityToken`
 * calls short-circuit straight to the cached provider's `request(...)`.
 *
 * A production app should treat `PlayIntegrityService` as an application
 * singleton and pass it into every `CDLSession`, so warm-up amortises
 * across sessions. Constructing a fresh service per session still works —
 * it just pays the warm-up cost each time.
 *
 * If Play Integrity is unavailable on the device (missing Play Services,
 * outdated Play Store, AOSP ROM, etc.) warm-up fails with a
 * `PlayIntegrityUnavailableException`; `CDLSession.requestDeviceAttestation`
 * catches it and tags `HwStatus.UNSUPPORTED` so the server doesn't light up
 * its downgrade-attack clustering heuristic for every non-Play device.
 */
class PlayIntegrityService(
    private val context: Context,
    /**
     * Google Cloud project number backing the Play Integrity Standard API.
     * Required — Standard rejects the warm-up with `CLOUD_PROJECT_NUMBER_IS_INVALID`
     * if this doesn't match the project linked to the app's Play Console
     * entry. Find it in Cloud Console → Project Settings → Project number
     * (not the alphanumeric project ID).
     */
    private val cloudProjectNumber: Long,
) : PlayIntegrityClient {

    // One-shot cache guarded by a mutex so two concurrent CDLSession.start
    // calls (unusual, but legal) don't both drive a warm-up request. The
    // provider itself is designed for repeated use; we only need to prevent
    // the duplicate prepare call.
    @Volatile private var provider: StandardIntegrityTokenProvider? = null
    private val providerLock = Mutex()

    override suspend fun requestIntegrityToken(requestHash: String): String {
        val readyProvider = prepareOrGetProvider()
        val request = StandardIntegrityTokenRequest.builder()
            .setRequestHash(requestHash)
            .build()
        return suspendCoroutine { continuation ->
            readyProvider.request(request)
                .addOnSuccessListener { response: StandardIntegrityToken ->
                    continuation.resume(response.token())
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(classify(e))
                }
        }
    }

    private suspend fun prepareOrGetProvider(): StandardIntegrityTokenProvider {
        provider?.let { return it }
        return providerLock.withLock {
            // Re-check under the lock — another coroutine may have warmed
            // up while we waited.
            provider?.let { return@withLock it }
            val manager = IntegrityManagerFactory.createStandard(context)
            val request = PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber)
                .build()
            val warmed = suspendCoroutine<StandardIntegrityTokenProvider> { continuation ->
                manager.prepareIntegrityToken(request)
                    .addOnSuccessListener { p -> continuation.resume(p) }
                    .addOnFailureListener { e -> continuation.resumeWithException(classify(e)) }
            }
            provider = warmed
            warmed
        }
    }

    companion object {
        /**
         * Translate a Play Integrity failure into a typed exception. Codes
         * meaning "device cannot attest at all" become PlayIntegrityUnavailableException
         * (→ server-visible HwStatus.UNSUPPORTED, no downgrade-attack alarm).
         * Everything else, including transient / API-side failures AND Standard-
         * specific configuration errors, becomes CDLException (→ HwStatus.FAILED,
         * the signal the server clusters on).
         *
         * Handles both exception shapes because the Google SDK uses
         * [IntegrityServiceException] for Classic calls still in the library
         * and [StandardIntegrityException] for Standard calls — Standard is the
         * path this service takes, but Classic is kept classifiable so a stray
         * Classic failure doesn't fall through to a plain FAILED bucket.
         *
         * Visible for testing so the mapping can be pinned without having
         * to construct real Play Services exceptions in instrumentation tests.
         */
        internal fun classify(error: Throwable): Exception {
            val code = errorCodeOf(error) ?: return CDLException(
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

        private fun errorCodeOf(error: Throwable): Int? = when (error) {
            is IntegrityServiceException -> error.errorCode
            is StandardIntegrityException -> error.errorCode
            else -> null
        }

        // Codes that mean "this device/environment can never attest" — not a
        // downgrade signal. Deliberately conservative: NETWORK_ERROR, INTERNAL_ERROR,
        // and Standard-specific config errors (CLOUD_PROJECT_NUMBER_IS_INVALID,
        // APP_NOT_INSTALLED, GOOGLE_SERVER_UNAVAILABLE) stay in CDLException
        // so a sustained failure pattern still reaches the server's clustering
        // heuristic. Standard shares the integer values of the shared codes
        // with Classic (both use the same model namespace), so a single set
        // handles both exception types.
        private val UNAVAILABLE_CODES = setOf(
            IntegrityErrorCode.API_NOT_AVAILABLE,
            IntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
            IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
            IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
            IntegrityErrorCode.PLAY_STORE_NOT_FOUND,
            IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
            IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND,
            // Standard-side equivalents — same meaning, surfaced via
            // StandardIntegrityException. Enumerated explicitly so a future
            // code addition in either namespace is a visible omission here,
            // not a silent fallthrough into the FAILED bucket. Note the
            // Standard enum doesn't define PLAY_STORE_ACCOUNT_NOT_FOUND —
            // the Classic one is kept above in case a Classic throw ever
            // reaches us, but there's no Standard analogue to list here.
            StandardIntegrityErrorCode.API_NOT_AVAILABLE,
            StandardIntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
            StandardIntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
            StandardIntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
            StandardIntegrityErrorCode.PLAY_STORE_NOT_FOUND,
            StandardIntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
        )
    }
}
