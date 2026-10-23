package dev.owreceipt.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.owreceipt.core.SessionState
import dev.owreceipt.core.SessionStatus
import dev.owreceipt.ui.AccountScreen
import dev.owreceipt.ui.AppUiState
import dev.owreceipt.ui.UiAction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccountRetryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun readingRolesAfterLoginDoesNotRestartLoginNavigation() {
        val actions = mutableListOf<UiAction>()
        rule.setContent {
            MaterialTheme {
                AccountScreen(AppUiState(isDemo = false,
                    session = SessionState(status = SessionStatus.AUTHENTICATING),
                    authOrigin = "pay.ds.163.com"), actions::add)
            }
        }
        rule.onNodeWithText("重新读取绑定角色").performClick()
        rule.runOnIdle {
            assertEquals(1, actions.size)
            assertEquals("Read roles must query the existing session, not navigate to /login again",
                UiAction.ReadBoundRoles, actions.single())
        }
    }
}
