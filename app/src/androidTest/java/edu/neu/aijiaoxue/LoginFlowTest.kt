package edu.neu.aijiaoxue

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.Session
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F0 验收：三类账号登录进入各自首页；错误提示不区分账号和密码；退出后回到登录页。 */
@RunWith(AndroidJUnit4::class)
class LoginFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun signOut() {
        Session.signOut(InstrumentationRegistry.getInstrumentation().targetContext)
        waitFor("登录")
    }

    private fun waitFor(text: String) =
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun login(account: String, password: String) {
        rule.onNode(hasSetTextAction() and hasText("账号")).performTextReplacement(account)
        rule.onNode(hasSetTextAction() and hasText("密码")).performTextReplacement(password)
        rule.onNode(hasText("登录") and !hasSetTextAction()).performClick()
    }

    @Test
    fun wrongPassword_andUnknownAccount_showSameMessage() {
        login("sup01", "wrong")
        waitFor("账号或密码错误")
        login("nobody", "123456")
        waitFor("账号或密码错误")
        assertNull(Session.currentUser)
    }

    @Test
    fun admin_landsOnAdminHome() {
        login("admin", "123456")
        waitFor("督导安排")
        rule.onNodeWithText("任务进度").assertExists()
        rule.onNodeWithText("课程检索").assertDoesNotExist()
    }

    @Test
    fun supervisor_landsOnSupervisorHome() {
        login(" sup01 ", "123456") // 账号首尾空格忽略
        waitFor("课程检索")
        rule.onNodeWithText("我的督导任务").assertExists()
        rule.onNodeWithText("督导安排").assertDoesNotExist()
    }

    @Test
    fun teacher_landsOnTeacherHome_thenLogoutReturnsToLogin() {
        login("tea01", "123456")
        waitFor("督导反馈")
        rule.onNodeWithText("改进待办").assertExists()

        rule.onNodeWithText("我的").performClick()
        waitFor("退出登录")
        rule.onAllNodesWithText("退出登录")[0].performClick()
        rule.onNodeWithText("退出").performClick()

        waitFor("登录")
        assertNull(Session.currentUser)
        // 导航图已按新用户重建，业务页面不在返回栈里
        rule.onNodeWithText("督导反馈").assertDoesNotExist()
    }
}
