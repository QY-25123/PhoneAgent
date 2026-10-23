package com.android.phoneagent.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** 颜色令牌。取值和提取位置见 docs/DESIGN.md 第 2 节。 */
@Immutable
class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceInput: Color,
    val surfaceControl: Color,
    val surfaceControlDeep: Color,
    val border: Color,
    val borderStrong: Color,
    val divider: Color,
    val dividerInCard: Color,
    val selected: Color,
    val bubbleTop: Color,
    val bubbleBottom: Color,
    val accent: Color,
    val accentContainer: Color,
    val primary: Color,
    val onPrimary: Color,
    val text: Color,
    val textOnBubble: Color,
    val onBubble: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val placeholder: Color,
    val iconDisabled: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val scrim: Color,
)

val DarkColors = AppColors(
    background = Color(0xFF000000),
    surface = Color(0xFF1F1F1F),
    surfaceInput = Color(0xFF141414),
    surfaceControl = Color(0xFF1B1B1B),
    surfaceControlDeep = Color(0xFF121212),
    border = Color(0xFF2B2B2B),
    borderStrong = Color(0xFF353535),
    divider = Color(0xFF1E1E1E),
    dividerInCard = Color(0xFF1A1A1A),
    selected = Color(0xFF2D2E30),
    bubbleTop = Color(0xFF2963C2),
    bubbleBottom = Color(0xFF3070DD),
    accent = Color(0xFF9FFBFE),
    accentContainer = Color(0xFF2559D3),
    primary = Color(0xFF2559D3),
    onPrimary = Color(0xFFFFFFFF),
    text = Color(0xFFF5F5F5),
    textOnBubble = Color(0xFFFFFFFF),
    onBubble = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF9EA0A5),
    textTertiary = Color(0xFF606266),
    placeholder = Color(0xFF6D6E71),
    iconDisabled = Color(0xFF4A4B4E),
    success = Color(0xFF5BD69A),
    warning = Color(0xFFF2B84B),
    danger = Color(0xFFFF6B6B),
    scrim = Color(0x99000000),
)

val LightColors = AppColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF2F2F4),
    surfaceInput = Color(0xFFF7F7F9),
    surfaceControl = Color(0xFFEEEEF1),
    surfaceControlDeep = Color(0xFFE9E9ED),
    border = Color(0xFFDADBE0),
    borderStrong = Color(0xFFC9CAD0),
    divider = Color(0xFFE4E4E9),
    dividerInCard = Color(0xFFE1E1E6),
    selected = Color(0xFFE3E5EB),
    bubbleTop = Color(0xFF2A64C6),
    bubbleBottom = Color(0xFF3070DD),
    accent = Color(0xFF1F5FD0),
    accentContainer = Color(0xFFDCE8FF),
    primary = Color(0xFF1F5FD0),
    onPrimary = Color(0xFFFFFFFF),
    text = Color(0xFF0D0D0F),
    textOnBubble = Color(0xFF0D0D0F),
    onBubble = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF62646A),
    textTertiary = Color(0xFF8D8F96),
    placeholder = Color(0xFF9A9CA2),
    iconDisabled = Color(0xFFC4C5CB),
    success = Color(0xFF1F9D63),
    warning = Color(0xFFB7791F),
    danger = Color(0xFFD14343),
    scrim = Color(0x52000000),
)

/** agent 头像色板（原创配色，两个主题共用）。 */
object AppAvatarPalette {
    val gradients: List<Pair<Color, Color>> = listOf(
        Color(0xFF8DB8FF) to Color(0xFF3F78E0),
        Color(0xFF8CF0D0) to Color(0xFF2BAE8A),
        Color(0xFFFFC9A8) to Color(0xFFF0804E),
        Color(0xFFCDB8FF) to Color(0xFF7A5AE0),
        Color(0xFFFFEB94) to Color(0xFFE5B72E),
        Color(0xFFFFB8D2) to Color(0xFFE0558A),
    )
    val face = Color(0xFF101214)
    val leaf = Color(0xFF3FBF8A)
}
