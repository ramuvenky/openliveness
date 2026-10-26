package dev.openliveness

import android.util.Base64
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

@JsonClass(generateAdapter = true)
data class QRPayload(
    val session_id: String,
    val challenge_id: String,
    val challenge_sequence: List<String>,
    val relay_ws: String,
    val relay_http: String,
    val expires_at: String,
    val version: String
)

data class LayerScores(
    var layer2ChallengeCompleted: Boolean = false,
    var layer2ChallengeScore: Float = 0f,
    var layer3PulseDetected: Boolean = false,
    var layer3BpmInRange: Boolean = false,
    var layer3RppgConfidence: Float = 0f,
    var layer4LbpScore: Float = 0.85f,     // stub
    var layer4FourierScore: Float = 0.85f, // stub
    var layer5BehavioralScore: Float = 0f,
    var layer5MicrosaccadeDetected: Boolean = false
)

data class AmbientConditions(
    var lowLightDetected: Boolean = false,
    var motionDetected: Boolean = false,
    var frontCameraConfirmed: Boolean = true
)

data class LivenessResult(
    val decision: String,
    val assuranceLevel: String,
    val layerScores: LayerScores
)

/**
 * SDK-wide error type. The optional cause lets classifying wrappers
 * (e.g. PlayIntegrityUnavailableException) chain the underlying Play
 * Services / network exception for crash reports and triage.
 */
open class CDLException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown by PlayIntegrityService when Play Integrity reports a code that
 * means "this device cannot do hardware attestation at all" (missing Play
 * Services, API not available, cannot bind to service, outdated Play Store)
 * — distinct from codes that mean "device could, but the call failed".
 * CDLSession catches this and maps to HwStatus.UNSUPPORTED so legitimate
 * AOSP / Huawei / older Android devices don't light up the server's
 * downgrade-attack heuristic that watches for the FAILED bucket.
 */
class PlayIntegrityUnavailableException(message: String, cause: Throwable? = null) :
    CDLException(message, cause)

private val moshi: Moshi by lazy {
    Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
}

/** Base64-decode the QR raw value then JSON-parse into a QRPayload. */
fun parseQRPayload(rawValue: String): QRPayload {
    val bytes = try {
        Base64.decode(rawValue, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        throw CDLException("QR not base64: ${e.message}")
    }
    val json = String(bytes, Charsets.UTF_8)
    val adapter = moshi.adapter(QRPayload::class.java)
    return adapter.fromJson(json) ?: throw CDLException("QR payload JSON parse failed")
}
