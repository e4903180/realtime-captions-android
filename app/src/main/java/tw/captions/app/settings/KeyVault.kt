package tw.captions.app.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user's own cloud API keys on the phone, encrypted with an AES key that lives in the
 * Android Keystore (it never leaves the secure hardware / keystore daemon). Keys are never logged
 * and never sent anywhere except to the provider they belong to.
 */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val data = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data, 0, 12))
            String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(name: String) = prefs.edit().remove(name).apply()

    /** "••••1a2b" for display; null when nothing is stored. */
    fun hint(name: String): String? = get(name)?.takeLast(4)?.let { "••••$it" }

    /** Plain (non-secret) settings next to a key, e.g. the Azure region. */
    fun putPlain(name: String, value: String) = prefs.edit().putString("plain_$name", value).apply()
    fun getPlain(name: String): String? = prefs.getString("plain_$name", null)

    companion object {
        private const val ALIAS = "captions-api-keys"
        const val AZURE_KEY = "azure_key"
        const val AZURE_REGION = "azure_region"
    }
}
