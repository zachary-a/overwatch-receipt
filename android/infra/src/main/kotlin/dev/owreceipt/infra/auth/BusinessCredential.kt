package dev.owreceipt.infra.auth

import dev.owreceipt.core.Player
import kotlinx.serialization.Serializable

@Serializable
data class BusinessCredential(
    val roleId: String,
    val token: String,
    val dts: String,
    val server: String,
    val player: Player,
) {
    override fun toString(): String = "BusinessCredential(<redacted>)"
}
class AuthenticationRequired : Exception("需要在应用内完成官方认证")
class CredentialRejected : Exception("战绩访问凭据已失效")
interface CredentialStore {
    suspend fun read(): BusinessCredential?
    suspend fun write(credential: BusinessCredential)
    suspend fun clear()
}
