package com.android.phoneagent.ui.nav

import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.phoneagent.ui.debug.DebugScreen
import com.android.phoneagent.ui.model.AgentProfile
import com.android.phoneagent.ui.model.ChatController
import com.android.phoneagent.ui.screens.ChatScreen
import com.android.phoneagent.ui.screens.DrawerContent
import com.android.phoneagent.ui.screens.DrawerScaffold
import com.android.phoneagent.ui.screens.FakeTasks
import com.android.phoneagent.ui.screens.FullScreenWatch
import com.android.phoneagent.ui.screens.MainPage
import com.android.phoneagent.ui.screens.OnboardingScreen
import com.android.phoneagent.ui.screens.SettingsScreen
import com.android.phoneagent.ui.screens.SplashPage
import com.android.phoneagent.ui.screens.TasksScreen
import com.android.phoneagent.ui.theme.PhoneAgentTheme

object Routes {
    const val Splash = "splash"
    const val Onboarding = "onboarding"
    const val Main = "main"
    const val FullScreen = "fullscreen"
    const val Settings = "settings"
    const val Debug = "debug"
}

/** 应用导航：启动页 → 引导（首次）/ 对话首页；对话首页带左侧抽屉（对话、任务记录）；全屏观看、设置、开发者调试。 */
@Composable
fun PhoneAgentApp(startRoute: String? = null) {
    val nav = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 对话首页接入真实流程（澄清 → 执行）；假数据演示留在开发者调试和画廊里
    val controller = remember { ChatController(backend = com.android.phoneagent.task.TaskBackends.get(context)) }
    val dark = AgentProfile.darkTheme
    val start = startRoute ?: Routes.Splash
    val activity = androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity
    androidx.compose.runtime.DisposableEffect(dark) {
        val style = if (dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }
    PhoneAgentTheme(darkTheme = dark) {
        NavHost(nav, startDestination = start, modifier = Modifier.fillMaxSize()) {
            composable(Routes.Splash) {
                SplashPage(onDone = {
                    nav.navigate(if (AgentProfile.exists) Routes.Main else Routes.Onboarding) { popUpTo(Routes.Splash) { inclusive = true } }
                })
            }
            composable(Routes.Onboarding) {
                OnboardingScreen(onFinish = { name, avatar ->
                    AgentProfile.save(name, avatar)
                    nav.navigate(Routes.Main) { popUpTo(Routes.Onboarding) { inclusive = true } }
                })
            }
            composable(Routes.Main) {
                var page by remember { mutableStateOf(MainPage.Chat) }
                var open by remember { mutableStateOf(false) }
                DrawerScaffold(
                    open = open, onOpenChange = { open = it },
                    drawer = {
                        DrawerContent(
                            current = page,
                            onSelect = { page = it; open = false },
                            onSettings = { open = false; nav.navigate(Routes.Settings) },
                            recent = FakeTasks.take(3).map { it.title },
                        )
                    },
                ) {
                    when (page) {
                        MainPage.Chat -> ChatScreen(controller, onMenu = { open = true }, onOpenFullScreen = { nav.navigate(Routes.FullScreen) })
                        MainPage.Tasks -> TasksScreen(onMenu = { open = true })
                    }
                }
            }
            composable(Routes.FullScreen) { FullScreenWatch(controller, onExit = { nav.popBackStack() }) }
            composable(Routes.Settings) { SettingsScreen(onBack = { nav.popBackStack() }, onDebug = { nav.navigate(Routes.Debug) }) }
            // 回归脚本直接打开调试界面时它是起始页，返回键应该关掉 Activity（和原来的首页行为一致）
            composable(Routes.Debug) { DebugScreen(onBack = { if (!nav.popBackStack()) activity.finish() }) }
        }
    }
}
