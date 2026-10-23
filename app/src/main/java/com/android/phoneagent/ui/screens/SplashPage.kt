package com.android.phoneagent.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import com.android.phoneagent.R
import com.android.phoneagent.ui.theme.AppTheme
import com.android.phoneagent.ui.theme.DarkColors

/** 启动页（系统 SplashScreen 之后）：纯黑背景，居中图标 + 名称，约 1 秒后淡出。始终使用深色令牌。 */
@Composable
fun SplashPage(onDone: () -> Unit, animateIn: Boolean = true) {
    val alpha = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animateIn) {
            alpha.animateTo(1f, tween(300))
            kotlinx.coroutines.delay(600)
            alpha.animateTo(0f, tween(300))
            onDone()
        }
    }
    Column(
        Modifier.fillMaxSize().background(DarkColors.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.alpha(alpha.value), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppTheme.space.s4)) {
            Image(painterResource(R.drawable.ic_app_mark), contentDescription = null, modifier = Modifier.size(AppTheme.sizes.avatarLarge))
            Text("PhoneAgent", style = AppTheme.type.title, color = DarkColors.text)
        }
    }
}
