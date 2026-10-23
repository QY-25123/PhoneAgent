package com.android.phoneagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.android.phoneagent.ui.components.AgentAvatar
import com.android.phoneagent.ui.components.RoundIconButton
import com.android.phoneagent.ui.model.AgentProfile
import com.android.phoneagent.ui.theme.AppIcons
import com.android.phoneagent.ui.theme.AppTheme

/** 设置：修改名字和头像、Shizuku 和权限状态、外观、开发者调试入口。 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onDebug: () -> Unit, persist: Boolean = true) {
    val c = AppTheme.colors
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(AgentProfile.name) }
    var avatar by remember { mutableIntStateOf(AgentProfile.avatar) }
    val shizuku = remember { SystemStatus.shizuku() }
    val notif = remember { SystemStatus.notificationsGranted(ctx) }
    val overlay = remember { SystemStatus.overlayGranted(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(AppTheme.space.s4),
        verticalArrangement = Arrangement.spacedBy(AppTheme.space.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s3)) {
            RoundIconButton(AppIcons.chevronLeft, onBack)
            Text("设置", style = AppTheme.type.title, color = c.text)
        }

        Section("助手") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s4)) {
                AgentAvatar(avatar, AppTheme.sizes.avatar, animate = false)
                Box(
                    Modifier
                        .weight(1f)
                        .height(AppTheme.sizes.inputBar)
                        .clip(AppTheme.shapes.pill)
                        .background(c.surfaceInput)
                        .border(AppTheme.sizes.hairline, c.border, AppTheme.shapes.pill)
                        .padding(horizontal = AppTheme.space.s5),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        name, { name = it.take(12); if (persist) AgentProfile.save(name, avatar) }, singleLine = true,
                        textStyle = AppTheme.type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            AvatarPicker(avatar) { avatar = it; if (persist) AgentProfile.save(name, it) }
        }

        Section("状态") {
            StatusRow(AppIcons.shield, "Shizuku", SystemStatus.shizukuText(shizuku), shizuku == 2)
            StatusRow(AppIcons.bell, "通知权限", if (notif) "已允许" else "未允许", notif)
            StatusRow(AppIcons.layers, "悬浮窗权限", if (overlay) "已允许" else "未允许", overlay)
        }

        Section("外观") {
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s2)) {
                Segment("深色", AppTheme.isDark) { AgentProfile.setDark(true) }
                Segment("浅色", !AppTheme.isDark) { AgentProfile.setDark(false) }
            }
        }

        Section("开发者") {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onDebug).padding(vertical = AppTheme.space.s2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s3),
            ) {
                Icon(AppIcons.settings, null, tint = c.text, modifier = Modifier.size(AppTheme.sizes.icon))
                Text("开发者调试", style = AppTheme.type.body, color = c.text, modifier = Modifier.weight(1f))
                Icon(AppIcons.chevronRight, null, tint = c.textTertiary, modifier = Modifier.size(AppTheme.sizes.icon))
            }
            Text("技术验证用的测试界面（虚拟屏、点击、写文字等），仅开发期保留。", style = AppTheme.type.caption, color = c.textSecondary)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.s2)) {
        Text(title, style = AppTheme.type.label, color = c.textTertiary, modifier = Modifier.padding(start = AppTheme.space.s2))
        Column(
            Modifier.fillMaxWidth().clip(AppTheme.shapes.card).background(c.surface).padding(AppTheme.space.s4),
            verticalArrangement = Arrangement.spacedBy(AppTheme.space.s3),
        ) { content() }
    }
}

@Composable
private fun StatusRow(icon: ImageVector, label: String, status: String, ok: Boolean) {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s3)) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(AppTheme.sizes.icon))
        Text(label, style = AppTheme.type.body, color = c.text, modifier = Modifier.weight(1f))
        Text(status, style = AppTheme.type.label, color = if (ok) c.success else c.warning)
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        Modifier
            .height(AppTheme.sizes.button)
            .clip(AppTheme.shapes.pill)
            .background(if (selected) c.selected else c.surfaceControl)
            .border(AppTheme.sizes.hairline, if (selected) c.accent else c.border, AppTheme.shapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.space.s5),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = AppTheme.type.label, color = if (selected) c.accent else c.text) }
}
