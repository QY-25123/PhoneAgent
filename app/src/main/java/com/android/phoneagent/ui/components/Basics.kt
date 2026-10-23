package com.android.phoneagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.android.phoneagent.ui.theme.AppTheme

enum class ButtonKind { Primary, Secondary, Danger, Ghost }

/** 圆形图标按钮（菜单、设置）。 */
@Composable
fun RoundIconButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, deep: Boolean = false, tint: Color = AppTheme.colors.text) {
    Box(
        modifier
            .size(AppTheme.sizes.control)
            .clip(AppTheme.shapes.circle)
            .background(if (deep) AppTheme.colors.surfaceControlDeep else AppTheme.colors.surfaceControl)
            .border(AppTheme.sizes.hairline, AppTheme.colors.borderStrong, AppTheme.shapes.circle)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(AppTheme.sizes.icon)) }
}

/** 胶囊按钮：主按钮、次按钮、危险按钮。 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = AppTheme.colors
    val bg = when (kind) {
        ButtonKind.Primary -> c.primary
        ButtonKind.Secondary, ButtonKind.Danger -> c.surfaceControl
        ButtonKind.Ghost -> Color.Transparent
    }
    val fg = when {
        !enabled -> c.iconDisabled
        kind == ButtonKind.Primary -> c.onPrimary
        kind == ButtonKind.Danger -> c.danger
        else -> c.text
    }
    val border = if (kind == ButtonKind.Secondary || kind == ButtonKind.Danger) BorderStroke(AppTheme.sizes.hairline, c.border) else null
    Row(
        modifier
            .height(AppTheme.sizes.button)
            .clip(AppTheme.shapes.pill)
            .background(if (enabled) bg else c.surfaceControl)
            .then(if (border != null) Modifier.border(border, AppTheme.shapes.pill) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AppTheme.space.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(AppTheme.sizes.icon))
            Box(Modifier.size(AppTheme.space.s1))
        }
        Text(text, style = AppTheme.type.label, color = fg)
    }
}

/** agent 名字胶囊标签。 */
@Composable
fun NameCapsule(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(AppTheme.sizes.capsule)
            .clip(AppTheme.shapes.pill)
            .background(AppTheme.colors.accentContainer)
            .padding(horizontal = AppTheme.space.s3),
        contentAlignment = Alignment.Center,
    ) { Text(name, style = AppTheme.type.label, color = AppTheme.colors.accent) }
}

/**
 * 结构化卡片：图标 + 标题行 → 分割线 → 内容（粗体主内容、灰色辅助信息、按钮）。
 * 确认卡片、实时画面卡片、结果卡片共用（docs/DESIGN.md 6.3）。
 */
@Composable
fun StructuredCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    iconTint: Color = AppTheme.colors.text,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(AppTheme.shapes.card)
            .background(AppTheme.colors.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(AppTheme.space.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(AppTheme.sizes.icon))
            Box(Modifier.size(AppTheme.space.s2))
            Text(title, style = AppTheme.type.title, color = AppTheme.colors.text, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Box(
            Modifier
                .padding(vertical = AppTheme.space.s3)
                .fillMaxWidth()
                .height(AppTheme.sizes.hairline)
                .background(AppTheme.colors.dividerInCard),
        )
        content()
    }
}

@Composable
fun CardMain(text: String, modifier: Modifier = Modifier) =
    Text(text, style = AppTheme.type.bodyStrong, color = AppTheme.colors.text, modifier = modifier)

@Composable
fun CardSub(text: String, modifier: Modifier = Modifier) =
    Text(text, style = AppTheme.type.caption, color = AppTheme.colors.textSecondary, modifier = modifier)
