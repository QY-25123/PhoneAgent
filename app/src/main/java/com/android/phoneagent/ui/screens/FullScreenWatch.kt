package com.android.phoneagent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.android.phoneagent.ui.components.ButtonKind
import com.android.phoneagent.ui.components.PillButton
import com.android.phoneagent.ui.model.ChatController
import com.android.phoneagent.ui.model.LiveStatus
import com.android.phoneagent.ui.theme.AppIcons
import com.android.phoneagent.ui.theme.AppTheme

/**
 * 全屏观看：顶部细条（状态 + 步骤序号）、约 85% 的画面（只能看，点击位置用小圆点标出）、
 * 底部按钮栏（暂停、停止），上滑展开步骤列表；返回手势或下滑退出。
 */
@Composable
fun FullScreenWatch(controller: ChatController, onExit: () -> Unit, startExpanded: Boolean = false) {
    val c = AppTheme.colors
    var expanded by remember { mutableStateOf(startExpanded) }
    val live = controller.activeLive
    BackHandler { if (expanded) expanded = false else onExit() }

    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        // 顶部细条
        Row(
            Modifier
                .fillMaxWidth()
                .height(AppTheme.sizes.button)
                .background(c.surface)
                .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy > 24f) onExit() } }
                .padding(horizontal = AppTheme.space.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(live?.status?.label ?: "没有进行中的任务", style = AppTheme.type.label, color = c.text, modifier = Modifier.weight(1f))
            Text("步骤 ${live?.step ?: 0}/${live?.total ?: 30}", style = AppTheme.type.caption, color = c.textSecondary)
        }
        // 画面区域（约 85%）
        BoxWithConstraints(
            Modifier
                .weight(if (expanded) 0.35f else 1f)
                .fillMaxWidth()
                .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy > 24f) onExit() } },
            contentAlignment = Alignment.Center,
        ) {
            if (live != null) {
                Image(
                    painterResource(live.thumb), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(AppTheme.space.s2).clip(AppTheme.shapes.small),
                )
                val tap = controller.tapPoint
                if (tap != null) TapDot(tap.first, tap.second)
            }
        }
        // 步骤列表（上滑展开）
        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            StepList(controller, Modifier.weight(1f, fill = false).heightIn(max = AppTheme.sizes.thumbMax * 2))
        }
        // 底部按钮栏
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -16f) expanded = true else if (dy > 16f) expanded = false } }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(AppTheme.space.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.space.s3),
        ) {
            Icon(if (expanded) AppIcons.chevronDown else AppIcons.chevronUp, null, tint = c.textTertiary, modifier = Modifier.size(AppTheme.sizes.icon))
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s3)) {
                PillButton(
                    if (controller.paused) "继续" else "暂停", { controller.togglePause() },
                    icon = if (controller.paused) AppIcons.play else AppIcons.pause,
                    enabled = live != null && live.status != LiveStatus.DONE && live.status != LiveStatus.STOPPED,
                )
                PillButton("停止", { controller.stop(); onExit() }, kind = ButtonKind.Danger, icon = AppIcons.stop, enabled = controller.busy)
            }
        }
    }
}

/** agent 的点击位置：出现后缩放淡出。 */
@Composable
private fun TapDot(fx: Float, fy: Float) {
    val c = AppTheme.colors
    val anim = remember(fx, fy) { Animatable(0f) }
    LaunchedEffect(fx, fy) { anim.animateTo(1f, tween(600)) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(AppTheme.space.s2)) {
        val s = AppTheme.sizes.tapDot
        Box(
            Modifier
                .offset(x = maxWidth * fx - s / 2, y = maxHeight * fy - s / 2)
                .size(s)
                .scale(0.6f + 0.4f * anim.value)
                .alpha(1f - anim.value * 0.4f)
                .clip(AppTheme.shapes.circle)
                .background(c.accent.copy(alpha = 0.6f))
                .border(AppTheme.sizes.iconStroke, c.accent, AppTheme.shapes.circle),
        )
    }
}

@Composable
private fun StepList(controller: ChatController, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    LazyColumn(modifier.fillMaxWidth().background(c.background), contentPadding = androidx.compose.foundation.layout.PaddingValues(AppTheme.space.s4), verticalArrangement = Arrangement.spacedBy(AppTheme.space.s3)) {
        itemsIndexed(controller.steps) { _, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s3)) {
                Box(
                    Modifier.size(AppTheme.sizes.icon).clip(AppTheme.shapes.circle).background(c.selected),
                    contentAlignment = Alignment.Center,
                ) { Text("${s.index ?: "-"}", style = AppTheme.type.caption, color = c.text) }
                Column {
                    Text("看到：${s.saw}", style = AppTheme.type.caption, color = c.textSecondary)
                    Text("做了：${s.did}", style = AppTheme.type.body, color = c.text)
                }
            }
        }
    }
}
