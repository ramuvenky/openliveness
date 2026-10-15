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

class CDLException(message: String) : Exception(message)

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
