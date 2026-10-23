package com.android.phoneagent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import com.android.phoneagent.ui.components.RoundIconButton
import com.android.phoneagent.ui.theme.AppIcons
import com.android.phoneagent.ui.theme.AppTheme

enum class MainPage { Chat, Tasks }

/** 左侧抽屉容器：打开时把主页面向右推开（圆角 + 压暗），不使用底部导航栏。 */
@Composable
fun DrawerScaffold(open: Boolean, onOpenChange: (Boolean) -> Unit, drawer: @Composable () -> Unit, content: @Composable () -> Unit) {
    val c = AppTheme.colors
    BackHandler(enabled = open) { onOpenChange(false) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .pointerInput(open) {
                detectHorizontalDragGestures { _, dx ->
                    if (!open && dx > 24f) onOpenChange(true)
                    if (open && dx < -24f) onOpenChange(false)
                }
            },
    ) {
        val drawerWidth = maxWidth * 0.78f
        val progress by animateFloatAsState(if (open) 1f else 0f, label = "drawer")
        Box(Modifier.width(drawerWidth).fillMaxHeight()) { drawer() }
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset((drawerWidth.toPx() * progress).toInt(), 0) }
                .clip(if (progress > 0f) AppTheme.shapes.card else RectangleShape),
        ) {
            content()
            if (progress > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(c.scrim.copy(alpha = c.scrim.alpha * progress))
                        .clickable { onOpenChange(false) },
                )
            }
        }
    }
}

/** 抽屉内容：App 名称、「对话」「任务记录」两项、底部设置按钮。 */
@Composable
fun DrawerContent(current: MainPage, onSelect: (MainPage) -> Unit, onSettings: () -> Unit, recent: List<String>) {
    val c = AppTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = AppTheme.space.s4),
    ) {
        Text("PhoneAgent", style = AppTheme.type.display, color = c.text, modifier = Modifier.padding(top = AppTheme.space.s8, bottom = AppTheme.space.s8))
        DrawerItem(AppIcons.chat, "对话", current == MainPage.Chat) { onSelect(MainPage.Chat) }
        Spacer(Modifier.height(AppTheme.space.s1))
        DrawerItem(AppIcons.records, "任务记录", current == MainPage.Tasks) { onSelect(MainPage.Tasks) }
        Box(Modifier.padding(vertical = AppTheme.space.s5).fillMaxWidth().height(AppTheme.sizes.hairline).background(c.divider))
        Text("最近任务", style = AppTheme.type.label, color = c.textTertiary)
        Spacer(Modifier.height(AppTheme.space.s2))
        if (recent.isEmpty()) Text("做完的任务会出现在这里", style = AppTheme.type.caption, color = c.textSecondary)
        recent.forEach {
            Text(it, style = AppTheme.type.body, color = c.textSecondary, maxLines = 1, modifier = Modifier.padding(vertical = AppTheme.space.s2))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(bottom = AppTheme.space.s2).fillMaxWidth().height(AppTheme.sizes.hairline).background(c.divider))
        DrawerItem(AppIcons.settings, "设置", false, onSettings)   // 图标 + 文字，一眼能认出是设置
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(AppTheme.sizes.inputBar)
            .clip(AppTheme.shapes.pill)
            .background(if (selected) c.selected else c.background)
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.space.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s3),
    ) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(AppTheme.sizes.icon))
        Text(label, style = AppTheme.type.body, color = c.text)
    }
}
