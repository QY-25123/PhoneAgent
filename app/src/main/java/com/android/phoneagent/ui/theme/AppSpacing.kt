package com.android.phoneagent.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 间距令牌，基础单位 4dp（docs/DESIGN.md 第 4 节）。 */
@Immutable
class AppSpacing(
    val s1: Dp = 4.dp,
    val s2: Dp = 8.dp,
    val s3: Dp = 12.dp,
    val s4: Dp = 16.dp,
    val s5: Dp = 20.dp,
    val s6: Dp = 24.dp,
    val s8: Dp = 32.dp,
    val s10: Dp = 40.dp,
)

/** 圆角令牌（docs/DESIGN.md 第 5 节）。 */
@Immutable
class AppShapes(
    val small: RoundedCornerShape = RoundedCornerShape(8.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(12.dp),
    val large: RoundedCornerShape = RoundedCornerShape(20.dp),
    val card: RoundedCornerShape = RoundedCornerShape(24.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50),
    val circle: androidx.compose.ui.graphics.Shape = CircleShape,
)

/** 尺寸令牌：控件、图标、头像（docs/DESIGN.md 第 6 节）。 */
@Immutable
class AppSizes(
    val icon: Dp = 24.dp,
    val control: Dp = 44.dp,
    val inputBar: Dp = 48.dp,
    val button: Dp = 40.dp,
    val sendButton: Dp = 36.dp,
    val avatarSmall: Dp = 32.dp,
    val avatar: Dp = 64.dp,
    val avatarLarge: Dp = 96.dp,
    val capsule: Dp = 24.dp,
    val topBar: Dp = 96.dp,
    val tapDot: Dp = 24.dp,
    val hairline: Dp = 1.dp,
    val iconStroke: Dp = 1.75.dp,
    val thumbMax: Dp = 200.dp,
    val bodyMax: Dp = 240.dp,
)
