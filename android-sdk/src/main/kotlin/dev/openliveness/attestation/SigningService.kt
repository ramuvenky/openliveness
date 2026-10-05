package dev.openliveness.attestation

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * ES256 (ECDSA P-256 + SHA-256) signing backed by the Android hardware
 * Keystore. The key is generated lazily on first use and persisted under
 * [KEY_ALIAS] so the public_key stays stable for the device lifetime.
 */
object SigningService {

    private const val KEY_ALIAS = "openliveness_key"
    private const val PROVIDER = "AndroidKeyStore"

    fun publicKeyBase64(): String {
        val cert = keyStore().getCertificate(KEY_ALIAS) ?: run {
            generateKey()
            keyStore().getCertificate(KEY_ALIAS)!!
        }
        val encoded = (cert.publicKey as ECPublicKey).encoded // X.509 SPKI
        return Base64.encodeToString(encoded, Base64.NO_WRAP)
    }

    /** Sign the canonical JSON bytes with the device key and return base64. */
    fun sign(canonicalJsonBytes: ByteArray): String {
        val privateKey = (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)
            ?.privateKey
            ?: run {
                generateKey()
                (keyStore().getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey
            }
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(canonicalJsonBytes)
        }
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun generateKey() {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        kpg.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build()
        )
        kpg.generateKeyPair()
    }
}
