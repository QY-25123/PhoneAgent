package com.android.phoneagent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import com.android.phoneagent.ui.gallery.RenderCatalog
import com.android.phoneagent.ui.model.AgentProfile

/** debug：按名字渲染某个界面，用来给设计稿截图。adb shell am start -n …/.ui.GalleryActivity --es screen chat_confirm --ez dark true */
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("screen") ?: "chat_empty"
        val dark = intent.getBooleanExtra("dark", true)
        AgentProfile.preview("小云", 2)
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        setContent { RenderCatalog(id, dark) }
    }
}
