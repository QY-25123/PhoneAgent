package com.android.phoneagent.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.android.phoneagent.ui.model.ChatItem
import com.android.phoneagent.ui.model.ConfirmState
import com.android.phoneagent.ui.model.LiveStatus
import com.android.phoneagent.ui.theme.AppIcons
import com.android.phoneagent.ui.theme.AppTheme

/** 实时画面卡片：缩略画面 + 当前步骤 + 步骤序号 + 停止按钮；点击进入全屏观看。 */
@Composable
fun LiveCard(item: ChatItem.Live, onOpen: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val active = item.status == LiveStatus.RUNNING || item.status == LiveStatus.WAITING || item.status == LiveStatus.PAUSED
    StructuredCard(
        icon = AppIcons.screen,
        title = item.title,
        modifier = modifier,
        iconTint = if (item.status == LiveStatus.RUNNING) c.accent else c.text,
        trailing = { Text(item.status.label, style = AppTheme.type.label, color = statusColor(item.status)) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.s2)) {
            Box(Modifier.fillMaxWidth().clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
                Image(
                    painterResource(item.thumb), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.height(AppTheme.sizes.thumbMax).clip(AppTheme.shapes.small),
                )
            }
            CardMain(item.stepText)
            CardSub("步骤 ${item.step}/${item.total} · 点击画面全屏观看")
            if (active) {
                Row(Modifier.padding(top = AppTheme.space.s2)) {
                    PillButton("停止", onStop, kind = ButtonKind.Danger, icon = AppIcons.stop)
                }
            }
        }
    }
}

@Composable
private fun statusColor(s: LiveStatus) = when (s) {
    LiveStatus.RUNNING -> AppTheme.colors.accent
    LiveStatus.WAITING, LiveStatus.PAUSED -> AppTheme.colors.warning
    LiveStatus.DONE -> AppTheme.colors.success
    LiveStatus.STOPPED -> AppTheme.colors.textSecondary
}

/** 确认卡片（T2 等待确认）：收件人、主题、正文全文；发送 / 修改 / 取消。 */
@Composable
fun ConfirmCard(item: ChatItem.Confirm, onSend: () -> Unit, onModify: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    StructuredCard(icon = AppIcons.mail, title = "待发送的邮件", modifier = modifier, iconTint = AppTheme.colors.warning) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.s2)) {
            Column {
                CardSub("收件人"); CardMain(item.to)
            }
            Column {
                CardSub("主题"); CardMain(item.subject)
            }
            Column {
                CardSub("正文")
                Box(Modifier.heightIn(max = AppTheme.sizes.bodyMax).verticalScroll(rememberScrollState())) {
                    Text(item.body, style = AppTheme.type.body, color = AppTheme.colors.text)
                }
            }
            when (item.state) {
                ConfirmState.PENDING -> {
                    CardSub("发送后不能撤回，Gmail 只有几秒的撤销时间。")
                    Row(Modifier.padding(top = AppTheme.space.s2), horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s2)) {
                        PillButton("发送", onSend, kind = ButtonKind.Primary, icon = AppIcons.send)
                        PillButton("修改", onModify, icon = AppIcons.edit)
                        PillButton("取消", onCancel, kind = ButtonKind.Danger)
                    }
                }
                ConfirmState.SENDING -> StateLine("已确认，正在发送…", AppTheme.colors.accent)
                ConfirmState.SENT -> StateLine("已发送", AppTheme.colors.success)
                ConfirmState.CANCELLED -> StateLine("已取消，草稿已丢弃", AppTheme.colors.textSecondary)
                ConfirmState.SUPERSEDED -> StateLine("已被新版本取代", AppTheme.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun StateLine(text: String, color: androidx.compose.ui.graphics.Color) =
    Text(text, style = AppTheme.type.label, color = color, modifier = Modifier.padding(top = AppTheme.space.s2))

/** 选项类提问：agent 消息 + 选项按钮。 */
@Composable
fun QuestionCard(item: ChatItem.Question, onChoose: (Int) -> Unit, modifier: Modifier = Modifier) {
    AgentBubble(item.text, modifier) { OptionRow(item.options, item.chosen, onChoose) }
}

/** 成功结果：证据截图或文字清单。 */
@Composable
fun SuccessCard(item: ChatItem.Success, modifier: Modifier = Modifier) {
    StructuredCard(icon = AppIcons.check, title = "已完成", modifier = modifier, iconTint = AppTheme.colors.success) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.s2)) {
            CardMain(item.title)
            CardSub(item.detail)
            if (item.evidenceImage != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        painterResource(item.evidenceImage), contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.height(AppTheme.sizes.thumbMax).clip(AppTheme.shapes.small),
                    )
                }
            }
            if (item.evidenceLines.isNotEmpty()) {
                CardSub("证据")
                item.evidenceLines.forEach { Text("· $it", style = AppTheme.type.caption, color = AppTheme.colors.textSecondary) }
            }
        }
    }
}

/** 失败结果：原因 + 可能残留的内容。 */
@Composable
fun FailureCard(item: ChatItem.Failure, onRetry: () -> Unit, onSteps: () -> Unit, modifier: Modifier = Modifier) {
    StructuredCard(icon = AppIcons.alert, title = "没能完成", modifier = modifier, iconTint = AppTheme.colors.danger) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.s2)) {
            CardMain(item.reason)
            if (item.residue.isNotEmpty()) {
                CardSub("可能残留的内容")
                item.residue.forEach { Text("· $it", style = AppTheme.type.caption, color = AppTheme.colors.textSecondary) }
            }
            Row(Modifier.padding(top = AppTheme.space.s2), horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s2)) {
                PillButton("重试", onRetry, kind = ButtonKind.Primary)
                PillButton("查看步骤", onSteps)
            }
        }
    }
}

/** 交接结果：说明停在哪里 + 「去完成」。 */
@Composable
fun HandoffCard(item: ChatItem.Handoff, onGo: () -> Unit, modifier: Modifier = Modifier) {
    StructuredCard(icon = AppIcons.handoff, title = "需要你来完成", modifier = modifier, iconTint = AppTheme.colors.warning) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.s2)) {
            CardMain(item.where)
            CardSub(item.summary)
            if (item.done) {
                Text("已把页面移到你的屏幕", style = AppTheme.type.label, color = AppTheme.colors.success, modifier = Modifier.padding(top = AppTheme.space.s2))
            } else {
                Row(Modifier.padding(top = AppTheme.space.s2)) { PillButton("去完成", onGo, kind = ButtonKind.Primary, icon = AppIcons.handoff) }
            }
        }
    }
}
