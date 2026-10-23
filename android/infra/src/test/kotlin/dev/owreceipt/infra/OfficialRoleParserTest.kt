package dev.owreceipt.infra

import dev.owreceipt.infra.auth.OfficialRoleParser
import dev.owreceipt.infra.auth.RoleReadStatus
import org.junit.Assert.*
import org.junit.Test

class OfficialRoleParserTest {
    // All account identifiers below are synthetic test fixtures.
    // Contract from the official ld5 Blizzard selector: accounts[].account -> role_id,
    // accounts[].nickname -> name (2782.4237b899.async.js, module 39336).
    @Test fun blizzardAccountsBecomeSelectableRolesWithoutRoundingIds() {
        val body = """{"code":0,"result":{"accounts":[
            {"account":999999999999999999,"nickname":"玩家#1234","avatar":"unused"},
            {"account":"900000000000000002","nickname":"另一角色#5678"}],
            "recent_account":{"account":999999999999999999,"nickname":"玩家#1234"}}}"""
        val roles = OfficialRoleParser.parse(body)
        assertEquals(listOf("999999999999999999", "900000000000000002"), roles.map { it.id })
        assertEquals("玩家#1234", roles.first().battleTag)
    }
    @Test fun emptyBindingsAndChangedPayloadAreDifferentStates() {
        assertEquals(RoleReadStatus.EMPTY, OfficialRoleParser.read(200,
            """{"code":0,"result":{"accounts":[]}}""").status)
        assertEquals(RoleReadStatus.INVALID, OfficialRoleParser.read(200,
            """{"code":0,"result":{"other":[]}}""").status)
        assertEquals(RoleReadStatus.INVALID, OfficialRoleParser.read(200, "not json").status)
    }
    @Test fun authenticationAndOtherBusinessFailuresAreVisibleAndDistinct() {
        assertEquals(RoleReadStatus.SIGN_IN_REQUIRED, OfficialRoleParser.read(200,
            """{"code":410,"msg":"用户未登录"}""").status)
        val limited = OfficialRoleParser.read(429, """{"code":429}""")
        assertEquals(RoleReadStatus.FAILED, limited.status)
        assertEquals(429, limited.code)
        assertTrue(limited.roles.isEmpty())
    }
    @Test fun subAccountsAndUnrelatedNumbersNeverBecomeBoundRoles() {
        val body = """{"code":0,"result":{"accounts":[{"account":"12","nickname":"玩家#1234",
            "sub_game_accounts":[{"role_id":"666","nickname":"其他游戏"}]}]}}"""
        assertEquals(listOf("12"), OfficialRoleParser.parse(body).map { it.id })
        assertEquals(RoleReadStatus.INVALID, OfficialRoleParser.read(200,
            """{"code":0,"result":{"accounts":[{"account":"not-a-role","nickname":"bad"}]}}""").status)
    }
}
