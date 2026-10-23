package com.android.phoneagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import com.android.phoneagent.ui.theme.AppIcons
import com.android.phoneagent.ui.theme.AppTheme

/** 用户消息：右侧蓝色气泡，纵向轻微渐变。 */
@Composable
fun UserBubble(text: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier
                .widthIn(max = maxWidth * 0.8f)
                .clip(AppTheme.shapes.large)
                .background(Brush.verticalGradient(listOf(AppTheme.colors.bubbleTop, AppTheme.colors.bubbleBottom)))
                .padding(horizontal = AppTheme.space.s4, vertical = AppTheme.space.s3),
        ) { Text(text, style = AppTheme.type.body, color = AppTheme.colors.onBubble) }
    }
}

/** agent 消息：大圆角深灰卡片，不带头像。 */
@Composable
fun AgentBubble(text: String, modifier: Modifier = Modifier, content: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxWidth().padding(end = AppTheme.space.s8)) {
        Column(
            Modifier
                .clip(AppTheme.shapes.card)
                .background(AppTheme.colors.surface)
                .padding(AppTheme.space.s4),
            verticalArrangement = Arrangement.spacedBy(AppTheme.space.s2),
        ) {
            Text(text, style = AppTheme.type.body, color = AppTheme.colors.textOnBubble)
            content?.invoke()
        }
    }
}

/** 选项按钮（提问用）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OptionRow(options: List<String>, chosen: Int?, onChoose: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s2), verticalArrangement = Arrangement.spacedBy(AppTheme.space.s2)) {
        options.forEachIndexed { i, label ->
            val picked = chosen == i
            val dim = chosen != null && !picked
            Box(
                Modifier
                    .height(AppTheme.sizes.button)
                    .clip(AppTheme.shapes.pill)
                    .background(if (picked) AppTheme.colors.selected else AppTheme.colors.surfaceControl)
                    .border(AppTheme.sizes.hairline, if (picked) AppTheme.colors.accent else AppTheme.colors.border, AppTheme.shapes.pill)
                    .clickable(enabled = chosen == null) { onChoose(i) }
                    .padding(horizontal = AppTheme.space.s4),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = AppTheme.type.label,
                    color = if (picked) AppTheme.colors.accent else if (dim) AppTheme.colors.textTertiary else AppTheme.colors.text,
                )
            }
        }
    }
}

/** 底部输入栏：胶囊，左 +（禁用），中输入框，右麦克风（禁用）/ 发送。 */
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(AppTheme.sizes.inputBar)
            .clip(AppTheme.shapes.pill)
            .background(c.surfaceInput)
            .border(AppTheme.sizes.hairline, c.border, AppTheme.shapes.pill)
            .padding(horizontal = AppTheme.space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(AppTheme.sizes.control), contentAlignment = Alignment.Center) {
            Icon(AppIcons.plus, null, tint = c.iconDisabled, modifier = Modifier.size(AppTheme.sizes.icon))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = AppTheme.type.body, color = c.placeholder, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = AppTheme.type.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isEmpty()) {
            Box(Modifier.size(AppTheme.sizes.control), contentAlignment = Alignment.Center) {
                Icon(AppIcons.mic, null, tint = c.iconDisabled, modifier = Modifier.size(AppTheme.sizes.icon))
            }
        } else {
            Box(
                Modifier
                    .size(AppTheme.sizes.sendButton)
                    .clip(AppTheme.shapes.circle)
                    .background(c.primary)
                    .clickable(onClick = onSend),
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.send, null, tint = c.onPrimary, modifier = Modifier.size(AppTheme.sizes.icon)) }
            Box(Modifier.size(AppTheme.space.s1))
        }
    }
}
