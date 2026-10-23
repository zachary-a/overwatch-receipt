package dev.owreceipt.infra.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EncryptedCredentialStore(context: Context) : CredentialStore {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "official-session.enc"))
    private val keyAlias = "owreceipt.official-session.v1"
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override suspend fun read(): BusinessCredential? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.baseFile.exists()) return@withLock null
            try {
                val bytes = file.readFully()
                val buffer = ByteBuffer.wrap(bytes)
                require(buffer.get().toInt() == 1)
                val ivLength = buffer.get().toInt()
                require(ivLength == 12)
                val iv = ByteArray(ivLength).also { buffer.get(it) }
                val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                }
                val clear = cipher.doFinal(encrypted)
                try { json.decodeFromString<BusinessCredential>(clear.toString(Charsets.UTF_8)) }
                finally { clear.fill(0) }
            } catch (_: Exception) {
                // Revoked/invalidated key or corrupt record requires official authentication again.
                file.delete()
                runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias) }
                null
            }
        }
    }
    override suspend fun write(credential: BusinessCredential) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val clear = json.encodeToString(credential).toByteArray(Charsets.UTF_8)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
                val encrypted = cipher.doFinal(clear)
                val record = ByteBuffer.allocate(2 + cipher.iv.size + encrypted.size)
                    .put(1).put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
                val output = file.startWrite()
                try { output.write(record); file.finishWrite(output) }
                catch (e: Exception) { file.failWrite(output); throw e }
            } finally { clear.fill(0) }
        }
    }
    override suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock { file.delete() } }
}
