package dev.owreceipt.infra.auth

import dev.owreceipt.core.Player
import kotlinx.serialization.json.*

enum class RoleReadStatus { FOUND, SIGN_IN_REQUIRED, EMPTY, FAILED, INVALID }
data class RoleReadResult(val status: RoleReadStatus, val roles: List<Player> = emptyList(), val code: Int? = null)

/** Official ld5 Blizzard selector: accounts[].account -> role_id; sub_game_accounts are not roles. */
object OfficialRoleParser {
    fun parse(text: String): List<Player> = read(200, text).roles

    fun read(http: Int, text: String): RoleReadResult {
        val envelope = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return RoleReadResult(RoleReadStatus.INVALID)
        val code = (envelope["code"] as? JsonPrimitive)?.intOrNull
        if (http == 401 || code in setOf(410, 901)) return RoleReadResult(RoleReadStatus.SIGN_IN_REQUIRED, code = code)
        if (http !in 200..299 || code != 0) return RoleReadResult(RoleReadStatus.FAILED, code = code)
        val accounts = (envelope["result"] as? JsonObject)?.get("accounts") as? JsonArray
            ?: return RoleReadResult(RoleReadStatus.INVALID, code = code)
        if (accounts.isEmpty()) return RoleReadResult(RoleReadStatus.EMPTY, code = code)
        if (accounts.size > 100) return RoleReadResult(RoleReadStatus.INVALID, code = code)
        val roles = linkedMapOf<String, Player>()
        for (entry in accounts) {
            val account = entry as? JsonObject ?: return RoleReadResult(RoleReadStatus.INVALID, code = code)
            fun field(key: String) = (account[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            val id = field("account") ?: return RoleReadResult(RoleReadStatus.INVALID, code = code)
            val name = field("nickname") ?: return RoleReadResult(RoleReadStatus.INVALID, code = code)
            if (!id.matches(Regex("[0-9]{1,30}")) || name.isBlank() || name.length > 160)
                return RoleReadResult(RoleReadStatus.INVALID, code = code)
            roles[id] = Player(id, name, "已绑定战网角色")
        }
        return RoleReadResult(RoleReadStatus.FOUND, roles.values.toList(), code)
    }
}

