package edu.neu.aijiaoxue

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.Session
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实AppNavHost与已有演示数据，验证路由替换和ViewModel工厂。 */
@RunWith(AndroidJUnit4::class)
class Sprint1NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Before fun signOut() {
        Session.signOut(InstrumentationRegistry.getInstrumentation().targetContext)
        waitText("登录")
    }
    private fun waitText(text: String) = rule.waitUntil(20_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun login(account: String) {
        rule.onNode(hasSetTextAction() and hasText("账号")).performTextReplacement(account)
        rule.onNode(hasSetTextAction() and hasText("密码")).performTextReplacement("123456")
        rule.onNode(hasText("登录") and !hasSetTextAction()).performClick()
    }
    @Test fun adminCanOpenHistoryAndReadFullRecordThroughRealNavigation() {
        login("admin"); waitText("历史记录")
        rule.onNodeWithText("历史记录").performClick()
        waitText("历史督导记录"); waitText("操作系统")
        rule.onNodeWithText("操作系统").performClick()
        waitText("历史详情 · 只读"); waitText("81 分 · 良好 · 已归档")
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText("返回").performClick(); waitText("历史督导记录")
    }
    @Test fun teacherCanOpenTodoAndReturnHomeThroughRealNavigation() {
        login("tea01"); waitText("督导反馈")
        rule.onNodeWithText("改进待办").performClick()
        waitText("当前筛选下暂无改进待办")
        rule.onNodeWithText("返回").performClick(); waitText("督导反馈")
    }
}
