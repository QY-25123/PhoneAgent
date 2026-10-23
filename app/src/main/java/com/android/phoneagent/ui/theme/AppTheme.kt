package com.android.phoneagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalAppColors = staticCompositionLocalOf { DarkColors }
val LocalAppTypography = staticCompositionLocalOf { AppTypography() }
val LocalAppSpacing = staticCompositionLocalOf { AppSpacing() }
val LocalAppShapes = staticCompositionLocalOf { AppShapes() }
val LocalAppSizes = staticCompositionLocalOf { AppSizes() }
val LocalIsDark = staticCompositionLocalOf { true }

/** 业务界面只通过它取主题值。 */
object AppTheme {
    val colors: AppColors @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val type: AppTypography @Composable @ReadOnlyComposable get() = LocalAppTypography.current
    val space: AppSpacing @Composable @ReadOnlyComposable get() = LocalAppSpacing.current
    val shapes: AppShapes @Composable @ReadOnlyComposable get() = LocalAppShapes.current
    val sizes: AppSizes @Composable @ReadOnlyComposable get() = LocalAppSizes.current
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalIsDark.current
}

/** 默认深色主题（不跟随系统）。旧的测试界面也用它包裹。 */
@Composable
fun PhoneAgentTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val c = if (darkTheme) DarkColors else LightColors
    val m3 = if (darkTheme) {
        darkColorScheme(background = c.background, surface = c.surface, onSurface = c.text, onBackground = c.text, primary = c.primary, onPrimary = c.onPrimary)
    } else {
        lightColorScheme(background = c.background, surface = c.surface, onSurface = c.text, onBackground = c.text, primary = c.primary, onPrimary = c.onPrimary)
    }
    CompositionLocalProvider(
        LocalAppColors provides c,
        LocalIsDark provides darkTheme,
    ) {
        MaterialTheme(colorScheme = m3, content = content)
    }
}
