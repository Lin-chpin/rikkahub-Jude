package me.rerere.rikkahub.data.ai.gemini

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class GeminiOAuthCredentialStore(
    context: Context,
    private val json: Json,
) {
    private val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    fun read(): GeminiStoredAccount? {
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            require(bytes.size > IV_SIZE)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH, iv))
            json.decodeFromString<GeminiStoredAccount>(cipher.doFinal(encrypted).decodeToString())
        }.onFailure { error ->
            Log.w(TAG, "Could not read encrypted Gemini credentials (${error::class.java.simpleName})")
        }.getOrNull()
    }

    fun write(account: GeminiStoredAccount) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.encodeToString(account).encodeToByteArray())
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(cipher.iv)
            output.write(encrypted)
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    fun clear() {
        file.delete()
        File(file.parentFile, "$FILE_NAME.tmp").delete()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val TAG = "GeminiOAuthCredentialStore"
        const val FILE_NAME = "gemini_oauth_credentials.enc"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rikkahub_gemini_oauth"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH = 128
    }
}
