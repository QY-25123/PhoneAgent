package com.android.phoneagent.ui.model

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** agent 的名字和头像（本机保存），以及外观设置。 */
object AgentProfile {
    private const val PREFS = "agent"
    var name by mutableStateOf("")
        private set
    var avatar by mutableIntStateOf(0)
        private set
    var darkTheme by mutableStateOf(true)
        private set
    private var ctx: Context? = null

    val exists: Boolean get() = name.isNotBlank()

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        val p = ctx!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        name = p.getString("agent_name", "") ?: ""
        avatar = p.getInt("agent_avatar", 0)
        darkTheme = p.getBoolean("dark_theme", true)
    }

    fun save(newName: String, newAvatar: Int) {
        name = newName.trim().take(12)
        avatar = newAvatar.coerceIn(0, 5)
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("agent_name", name)?.putInt("agent_avatar", avatar)?.apply()
    }

    fun setDark(dark: Boolean) {
        darkTheme = dark
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("dark_theme", dark)?.apply()
    }

    /** 画廊/预览用：不落盘。 */
    fun preview(newName: String, newAvatar: Int) {
        name = newName; avatar = newAvatar
    }
}
