package com.resqmesh.app.keys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.resqmesh.mesh.crypto.NodeKeys
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps this phone's Ed25519 private key (F-03, TRD 2.11). The 32-byte key is encrypted with an
 * AES-GCM key that lives inside Android Keystore and never leaves it; only the ciphertext is stored.
 */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadOrCreate(): NodeKeys {
        prefs.getString(KEY_WRAPPED, null)?.let { stored ->
            try {
                return NodeKeys.fromPrivateKey(unwrap(stored))
            } catch (e: GeneralSecurityException) {
                // The Keystore key is gone (e.g. app data restored to a new phone): start a new identity.
                Log.w(TAG, "Stored node key could not be decrypted; creating a new one", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Stored node key is corrupt; creating a new one", e)
            }
        }
        val keys = NodeKeys.generate()
        // commit, not apply: the identity must be on disk before the first packet is signed with it.
        prefs.edit(commit = true) { putString(KEY_WRAPPED, wrap(keys.exportPrivateKey())) }
        return keys
    }

    private fun wrap(privateKey: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        return Base64.encodeToString(iv + cipher.doFinal(privateKey), Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): ByteArray {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE) { "Too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
        return cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "KeyVault"
        const val PREFS = "resqmesh_keys"
        const val KEY_WRAPPED = "node_key_wrapped"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "resqmesh_node_key_wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
