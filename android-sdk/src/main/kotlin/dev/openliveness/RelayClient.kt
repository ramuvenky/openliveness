package dev.openliveness

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP client for the relay endpoints the mobile SDK calls. Mirrors
 * the iOS RelayClient — same endpoints, same error semantics.
 */
class RelayClient(baseURL: String) {

    private val base: String = if (baseURL.contains("://")) baseURL else "https://$baseURL"
    internal val http = OkHttpClient()
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val stringMapAdapter = moshi.adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )

    data class CompleteResponse(
        val accepted: Boolean,
        @Json(name = "attestation_token") val attestationToken: String?
    )

    suspend fun requestKeyChallenge(keyId: String): String {
        val resp = postMap("/cdl/device/register", mapOf("platform" to "android", "key_id" to keyId))
        return resp["challenge"] ?: throw CDLException("missing challenge in /cdl/device/register response")
    }

    suspend fun registerDevice(keyId: String, attestation: String): String {
        val resp = postMap(
            "/cdl/device/attest",
            mapOf("platform" to "android", "key_id" to keyId, "attestation" to attestation)
        )
        return resp["device_token"] ?: throw CDLException("missing device_token in /cdl/device/attest response")
    }

    suspend fun requestIntegrityNonce(sessionId: String): String {
        val resp = postMap("/cdl/session/$sessionId/integrity-nonce", emptyMap())
        return resp["nonce"] ?: throw CDLException("missing nonce in integrity-nonce response")
    }

    /**
     * Submits the pre-canonicalized, signed attestation bytes. Taking a
     * ByteArray here (not a Map) guarantees the wire bytes match what the
     * AttestationBuilder signed — see AttestationBuilder.build.
     */
    suspend fun submitAttestation(sessionId: String, attestation: ByteArray): CompleteResponse {
        val body = postBytes("/cdl/session/$sessionId/complete", attestation)
        return parseCompleteResponse(body)
    }

    /**
     * Parses the /complete response with explicit per-field errors so
     * malformed responses throw CDLException rather than silently coercing
     * to accepted=false. Extracted so the validation behavior is unit-
     * testable without a full HTTP round-trip.
     */
    internal fun parseCompleteResponse(body: String): CompleteResponse {
        if (body.isBlank()) throw CDLException("empty /complete response")
        val parsed = try {
            mapAdapter.fromJson(body)
        } catch (e: Exception) {
            // Moshi raises EOFException / JsonDataException for malformed input;
            // wrap so callers only ever see CDLException from this path.
            throw CDLException("malformed /complete response: ${e.message}")
        } ?: throw CDLException("malformed /complete response: null")
        val accepted = parsed["accepted"] as? Boolean
            ?: throw CDLException("malformed /complete response: missing or non-boolean 'accepted'")
        val token = when (val v = parsed["attestation_token"]) {
            null -> null
            is String -> v
            else -> throw CDLException("malformed /complete response: 'attestation_token' must be string")
        }
        return CompleteResponse(accepted, token)
    }

    // MARK: - internals

    private suspend fun postMap(path: String, body: Map<String, String>): Map<String, String> {
        val json = stringMapAdapter.toJson(body)
        val respBody = postJson(path, json)
        return stringMapAdapter.fromJson(respBody) ?: emptyMap()
    }

    private suspend fun postJson(path: String, json: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(base + path)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw CDLException("HTTP ${resp.code}: $text")
            }
            text
        }
    }

    private suspend fun postBytes(path: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(base + path)
            .post(bytes.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw CDLException("HTTP ${resp.code}: $text")
            }
            text
        }
    }
}
