package com.android.phoneagent.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 字体层级令牌（docs/DESIGN.md 第 3 节）。字号 sp，行高为 4 的倍数。 */
@Immutable
class AppTypography(
    val display: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    val title: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    val body: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    val bodyStrong: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    val label: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    val caption: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
)
