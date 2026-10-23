package com.android.phoneagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import com.android.phoneagent.ui.model.FloatState
import com.android.phoneagent.ui.theme.AppIcons
import com.android.phoneagent.ui.theme.AppTheme
import com.android.phoneagent.ui.theme.DarkColors
import com.android.phoneagent.ui.theme.LocalAppColors
import androidx.compose.runtime.CompositionLocalProvider

/** 悬浮小窗的单个状态：药丸，状态图标 + 一行文字 + 按钮。小窗固定使用深色令牌（浮在别的 App 上）。 */
@Composable
fun FloatingPill(state: FloatState, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalAppColors provides DarkColors) {
        val c = AppTheme.colors
        val (icon, tint) = floatLook(state)
        Row(
            modifier
                .clip(AppTheme.shapes.pill)
                .background(c.surface)
                .border(AppTheme.sizes.hairline, c.border, AppTheme.shapes.pill)
                .padding(horizontal = AppTheme.space.s4, vertical = AppTheme.space.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s2),
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(AppTheme.sizes.icon))
            Text(state.text, style = AppTheme.type.label, color = c.text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            state.actions.forEachIndexed { i, a ->
                val last = i == state.actions.lastIndex
                Text(
                    a, style = AppTheme.type.label,
                    color = if (a == "停止") c.danger else if (last && a != "关闭") c.accent else c.textSecondary,
                    modifier = Modifier.clickable { }.padding(horizontal = AppTheme.space.s1),
                )
            }
        }
    }
}

@Composable
private fun floatLook(s: FloatState): Pair<ImageVector, Color> {
    val c = DarkColors
    return when (s) {
        FloatState.RUNNING -> AppIcons.screen to c.accent
        FloatState.WAIT_TYPING -> AppIcons.pause to c.textSecondary
        FloatState.NEED_ANSWER -> AppIcons.chat to c.accent
        FloatState.WAIT_CONFIRM -> AppIcons.mail to c.warning
        FloatState.HANDOFF -> AppIcons.handoff to c.warning
        FloatState.DONE -> AppIcons.check to c.success
        FloatState.FAILED -> AppIcons.alert to c.danger
        FloatState.PAUSED -> AppIcons.pause to c.textSecondary
        FloatState.CONFLICT -> AppIcons.alert to c.warning
        FloatState.STOPPED -> AppIcons.stop to c.textSecondary
    }
}

/** 设计稿用：全部 10 个状态，叠在一个示意的桌面背景上。 */
@Composable
fun FloatingStatesScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF3A4A6B), Color(0xFF14161C))))
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(AppTheme.space.s4),
        verticalArrangement = Arrangement.spacedBy(AppTheme.space.s3),
    ) {
        Text("悬浮小窗 · 10 个状态", style = AppTheme.type.title, color = Color.White)
        FloatState.entries.forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.s1)) {
                Text(s.title, style = AppTheme.type.caption, color = Color(0xFFB8BECC))
                FloatingPill(s, Modifier.fillMaxWidth())
            }
        }
    }
}
