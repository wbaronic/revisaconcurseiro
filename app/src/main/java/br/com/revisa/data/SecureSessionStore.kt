package br.com.revisa.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val userId: String
)

/** Tokens are encrypted with a non-exportable, per-project Android Keystore key. */
internal class SecureSessionStore(context: Context, projectUrl: String) {
    private val projectId = MessageDigest.getInstance("SHA-256")
        .digest(projectUrl.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
    private val alias = "revisa.session.$projectId"
    private val preferences = context.applicationContext.getSharedPreferences("revisa_session_$projectId", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    fun load(): AuthSession? {
        val encrypted = preferences.getString("payload", null) ?: return null
        return try {
            val iv = Base64.decode(preferences.getString("iv", null) ?: error("Missing IV"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(projectId.toByteArray(Charsets.UTF_8))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
            AuthSession(json.getString("access_token"), json.getString("refresh_token"),
                json.getLong("expires_at_ms"), json.getString("user_id"))
                .takeIf { it.accessToken.isNotBlank() && it.refreshToken.isNotBlank() && it.userId.isNotBlank() }
        } catch (_: Exception) {
            // A restored backup, invalidated key, or damaged record cannot become a usable session.
            clear()
            null
        }
    }

    fun save(session: AuthSession) {
        val json = JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("expires_at_ms", session.expiresAtMillis)
            .put("user_id", session.userId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(projectId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit()
            .putString("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()) { "Não foi possível salvar sua sessão com segurança." }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Não foi possível remover a sessão deste aparelho." }
    }
}
