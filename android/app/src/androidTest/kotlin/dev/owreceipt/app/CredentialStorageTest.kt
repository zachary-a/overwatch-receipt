package dev.owreceipt.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.owreceipt.core.Player
import dev.owreceipt.infra.auth.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class CredentialStorageTest {
    @Test fun credentialIsEncryptedAndReopensWithKeystoreKey() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "official-session.enc")
        val store = EncryptedCredentialStore(context)
        val credential = BusinessCredential("123456789012345678", "synthetic-secret-never-on-a-receipt", "2026", "1",
            Player("123456789012345678", "StorageTest#1234", "测试角色"))
        try {
            store.write(credential)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(credential.token))
            assertEquals(credential, EncryptedCredentialStore(context).read())
            file.writeBytes(byteArrayOf(1, 12, 0))
            assertNull(EncryptedCredentialStore(context).read())
            assertFalse(file.exists())
            store.write(credential); store.clear()
            assertFalse(file.exists())
        } finally { store.clear() }
    }
}
