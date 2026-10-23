package com.android.phoneagent.ui.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.android.phoneagent.ui.model.AgentProfile
import com.android.phoneagent.ui.model.ChatController
import com.android.phoneagent.ui.screens.ChatScreen
import com.android.phoneagent.ui.screens.DrawerContent
import com.android.phoneagent.ui.screens.DrawerScaffold
import com.android.phoneagent.ui.screens.FakeTasks
import com.android.phoneagent.ui.screens.FloatingStatesScreen
import com.android.phoneagent.ui.screens.FullScreenWatch
import com.android.phoneagent.ui.screens.MainPage
import com.android.phoneagent.ui.screens.OnboardingScreen
import com.android.phoneagent.ui.screens.SettingsScreen
import com.android.phoneagent.ui.screens.SplashPage
import com.android.phoneagent.ui.screens.TasksScreen
import com.android.phoneagent.ui.theme.PhoneAgentTheme

class CatalogEntry(val id: String, val title: String, val content: @Composable () -> Unit)

/** 设计稿目录：每个界面一个条目，用于 @Preview 和渲染截图（docs/design/screens/）。 */
object ScreenCatalog {
    private fun chat(kind: String): @Composable () -> Unit = {
        val c = remember { ChatController.preset(kind) }
        ChatScreen(c, onMenu = {}, onOpenFullScreen = {})
    }

    val entries: List<CatalogEntry> = listOf(
        CatalogEntry("splash", "启动页") { SplashPage(onDone = {}, animateIn = false) },
        CatalogEntry("onboarding_1_name", "引导 1/4：起名和选头像") { OnboardingScreen(onFinish = { _, _ -> }, startStep = 0, previewName = "小云", previewAvatar = 2) },
        CatalogEntry("onboarding_2_shizuku", "引导 2/4：Shizuku") { OnboardingScreen(onFinish = { _, _ -> }, startStep = 1) },
        CatalogEntry("onboarding_3_notification", "引导 3/4：通知权限") { OnboardingScreen(onFinish = { _, _ -> }, startStep = 2) },
        CatalogEntry("onboarding_4_overlay", "引导 4/4：悬浮窗权限") { OnboardingScreen(onFinish = { _, _ -> }, startStep = 3) },
        CatalogEntry("chat_empty", "对话首页：空状态", chat("empty")),
        CatalogEntry("chat_working", "对话：实时画面卡片", chat("working")),
        CatalogEntry("chat_confirm", "对话：确认卡片（等待确认）", chat("confirm")),
        CatalogEntry("chat_question", "对话：选项提问", chat("question")),
        CatalogEntry("chat_success", "对话：成功结果", chat("success")),
        CatalogEntry("chat_failure", "对话：失败结果", chat("failure")),
        CatalogEntry("chat_handoff", "对话：交接结果", chat("handoff")),
        CatalogEntry("fullscreen", "全屏观看") {
            val c = remember { ChatController.preset("working").also { it.markTap() } }
            FullScreenWatch(c, onExit = {})
        },
        CatalogEntry("fullscreen_steps", "全屏观看：步骤列表展开") {
            val c = remember { ChatController.preset("working") }
            FullScreenWatch(c, onExit = {}, startExpanded = true)
        },
        CatalogEntry("floating_states", "悬浮小窗：10 个状态") { FloatingStatesScreen() },
        CatalogEntry("drawer", "左侧抽屉") {
            val c = remember { ChatController.preset("success") }
            DrawerScaffold(
                open = true, onOpenChange = {},
                drawer = { DrawerContent(MainPage.Chat, onSelect = {}, onSettings = {}, recent = FakeTasks.take(3).map { it.title }) },
            ) { ChatScreen(c, onMenu = {}, onOpenFullScreen = {}) }
        },
        CatalogEntry("tasks", "任务记录") { TasksScreen(onMenu = {}) },
        CatalogEntry("settings", "设置页") { SettingsScreen(onBack = {}, onDebug = {}, persist = false) },
    )

    fun find(id: String) = entries.first { it.id == id }
}

/** 渲染某个目录条目（带主题）。 */
@Composable
fun RenderCatalog(id: String, dark: Boolean) {
    AgentProfile.preview("小云", 2)
    PhoneAgentTheme(darkTheme = dark) { ScreenCatalog.find(id).content() }
}

class CatalogIds : PreviewParameterProvider<String> {
    override val values: Sequence<String> = ScreenCatalog.entries.map { it.id }.asSequence()
}
