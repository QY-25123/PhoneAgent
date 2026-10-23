package com.android.phoneagent.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import com.android.phoneagent.ui.theme.AppAvatarPalette
import com.android.phoneagent.ui.theme.AppTheme

/**
 * agent 头像：6 个原创矢量形象（形体渐变 + 两个圆眼 + 嘴形）。
 * [workPulse] 每增加一次就做一次「开始工作」小动画（弹跳 + 眨眼）；[breathing] 打开执行中的轻微呼吸。
 */
@Composable
fun AgentAvatar(index: Int, size: Dp, modifier: Modifier = Modifier, workPulse: Int = 0, breathing: Boolean = false, animate: Boolean = true) {
    val bounce = remember { Animatable(0f) }
    val blink = remember { Animatable(1f) }
    LaunchedEffect(workPulse) {
        if (workPulse > 0 && animate) {
            bounce.animateTo(1f, tween(120, easing = FastOutSlowInEasing))
            bounce.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
            blink.animateTo(0.2f, tween(80))
            blink.animateTo(1f, tween(80))
        }
    }
    val breath = if (breathing && animate) {
        rememberInfiniteTransition(label = "breath").animateFloat(1f, 1.03f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "b").value
    } else 1f
    val bounceDistance = AppTheme.space.s1
    Canvas(
        modifier.size(size).graphicsLayer {
            translationY = -bounce.value * bounceDistance.toPx()
            scaleX = breath; scaleY = breath
        },
    ) { drawAvatar(index.coerceIn(0, 5), blink.value) }
}

private fun DrawScope.drawAvatar(index: Int, eyeOpen: Float) {
    val (top, bottom) = AppAvatarPalette.gradients[index]
    val brush = Brush.verticalGradient(listOf(top, bottom))
    val w = size.width
    val h = size.height
    when (index) {
        0 -> drawCircle(brush, radius = w * 0.46f, center = center)
        1 -> drawRoundRect(brush, Offset(w * 0.06f, h * 0.06f), Size(w * 0.88f, h * 0.88f), CornerRadius(w * 0.28f))
        2 -> {
            val p = Path().apply {
                moveTo(w * 0.5f, h * 0.05f)
                cubicTo(w * 0.62f, h * 0.22f, w * 0.92f, h * 0.40f, w * 0.92f, h * 0.62f)
                cubicTo(w * 0.92f, h * 0.84f, w * 0.73f, h * 0.95f, w * 0.5f, h * 0.95f)
                cubicTo(w * 0.27f, h * 0.95f, w * 0.08f, h * 0.84f, w * 0.08f, h * 0.62f)
                cubicTo(w * 0.08f, h * 0.40f, w * 0.38f, h * 0.22f, w * 0.5f, h * 0.05f)
                close()
            }
            drawPath(p, brush)
        }
        3 -> {
            drawCircle(brush, radius = w * 0.24f, center = Offset(w * 0.30f, h * 0.56f))
            drawCircle(brush, radius = w * 0.30f, center = Offset(w * 0.52f, h * 0.44f))
            drawCircle(brush, radius = w * 0.24f, center = Offset(w * 0.72f, h * 0.58f))
            drawRoundRect(brush, Offset(w * 0.28f, h * 0.52f), Size(w * 0.46f, h * 0.30f), CornerRadius(w * 0.12f))
        }
        4 -> {
            val p = Path().apply {
                moveTo(w * 0.02f, h * 0.52f)
                cubicTo(w * 0.10f, h * 0.30f, w * 0.30f, h * 0.14f, w * 0.5f, h * 0.14f)
                cubicTo(w * 0.70f, h * 0.14f, w * 0.90f, h * 0.30f, w * 0.98f, h * 0.52f)
                cubicTo(w * 0.90f, h * 0.74f, w * 0.70f, h * 0.88f, w * 0.5f, h * 0.88f)
                cubicTo(w * 0.30f, h * 0.88f, w * 0.10f, h * 0.74f, w * 0.02f, h * 0.52f)
                close()
            }
            drawPath(p, brush)
        }
        else -> {
            drawCircle(brush, radius = w * 0.40f, center = Offset(w * 0.5f, h * 0.58f))
            val leaf = Path().apply {
                moveTo(w * 0.50f, h * 0.20f)
                cubicTo(w * 0.52f, h * 0.06f, w * 0.70f, h * 0.04f, w * 0.80f, h * 0.08f)
                cubicTo(w * 0.78f, h * 0.20f, w * 0.64f, h * 0.28f, w * 0.50f, h * 0.20f)
                close()
            }
            drawPath(leaf, AppAvatarPalette.leaf)
        }
    }
    // 脸：两个圆眼 + 嘴
    val eyeY = h * (if (index == 3) 0.50f else 0.54f)
    val eyeR = w * 0.055f
    for (dx in listOf(0.36f, 0.64f)) {
        val c = Offset(w * dx, eyeY)
        drawOval(AppAvatarPalette.face, Offset(c.x - eyeR, c.y - eyeR * eyeOpen), Size(eyeR * 2, eyeR * 2 * eyeOpen))
    }
    val mouthY = eyeY + h * 0.14f
    val stroke = Stroke(width = w * 0.04f, cap = StrokeCap.Round)
    when (index) {
        0, 2, 5 -> drawArc(AppAvatarPalette.face, 20f, 140f, false, Offset(w * 0.42f, mouthY - h * 0.05f), Size(w * 0.16f, h * 0.10f), style = stroke)
        1 -> drawCircle(AppAvatarPalette.face, radius = w * 0.035f, center = Offset(w * 0.5f, mouthY + h * 0.01f))
        3 -> drawLine(AppAvatarPalette.face, Offset(w * 0.44f, mouthY), Offset(w * 0.56f, mouthY), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        else -> drawArc(AppAvatarPalette.face, 0f, 180f, true, Offset(w * 0.42f, mouthY - h * 0.04f), Size(w * 0.16f, h * 0.10f))
    }
}
