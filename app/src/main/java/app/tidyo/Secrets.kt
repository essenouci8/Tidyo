package app.tidyo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores small secrets (the AI key) encrypted with an Android Keystore key. */
class Secrets(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("tidyo_secure", Context.MODE_PRIVATE)
    private val alias = "tidyo_secret_key_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(name, "e:" + Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            // Some devices have a broken Keystore: fall back to the app's private storage.
            prefs.edit().putString(name, "p:" + Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)).apply()
        }
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        if (stored.startsWith("p:")) return try { String(Base64.decode(stored.substring(2), Base64.NO_WRAP), Charsets.UTF_8) } catch (e: Exception) { null }
        val raw = stored.removePrefix("e:")
        return try {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }
}
