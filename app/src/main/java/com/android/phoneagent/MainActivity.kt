package com.android.phoneagent

import com.android.phoneagent.task.Agent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.android.phoneagent.ui.model.AgentProfile
import com.android.phoneagent.ui.nav.PhoneAgentApp
import com.android.phoneagent.ui.nav.Routes

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()   // Android 12+ 系统启动页：纯黑背景 + App 图标
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Agent.init(this)
        AgentProfile.init(this)
        // 回归脚本用：--es route debug 直接打开技术验证用的测试界面
        val start = if (intent.getStringExtra("route") == "debug") Routes.Debug else null
        com.android.phoneagent.task.FloatingController.install(this)
        com.android.phoneagent.ui.model.FocusRequest.target = intent.getStringExtra("focus")
        setContent { PhoneAgentApp(start) }
    }

    override fun onStart() {
        super.onStart()
        com.android.phoneagent.task.FloatingController.install(this).setForeground(true)   // App 在前台：小窗隐藏
    }

    override fun onStop() {
        super.onStop()
        com.android.phoneagent.task.FloatingController.install(this).setForeground(false)  // 用户离开：有任务就显示小窗
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("route") == "debug") recreate()
        intent.getStringExtra("focus")?.let { com.android.phoneagent.ui.model.FocusRequest.target = it }   // 从悬浮小窗来：定位到对应卡片
    }
}
