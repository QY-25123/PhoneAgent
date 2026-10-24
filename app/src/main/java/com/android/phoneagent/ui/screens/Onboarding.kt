package com.android.phoneagent.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.android.phoneagent.ui.components.AgentAvatar
import com.android.phoneagent.ui.components.ButtonKind
import com.android.phoneagent.ui.components.PillButton
import com.android.phoneagent.ui.theme.AppIcons
import com.android.phoneagent.ui.theme.AppTheme

/** 系统状态（真实检查）：设置页和引导页共用。 */
object SystemStatus {
    fun notificationsGranted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun overlayGranted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** 0 未运行，1 未授权，2 正常。 */
    fun shizuku(): Int = try {
        if (!rikka.shizuku.Shizuku.pingBinder()) 0
        else if (rikka.shizuku.Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) 1 else 2
    } catch (_: Throwable) { 0 }

    fun shizukuText(s: Int) = when (s) { 0 -> "未运行"; 1 -> "未授权"; else -> "正常" }
}

/** 首次使用引导：起名并选头像 → Shizuku → 通知权限 → 悬浮窗权限。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onFinish: (name: String, avatar: Int) -> Unit, startStep: Int = 0, previewName: String = "", previewAvatar: Int = 0) {
    val c = AppTheme.colors
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(startStep) }
    var name by remember { mutableStateOf(previewName) }
    var avatar by remember { mutableIntStateOf(previewAvatar) }
    var tick by remember { mutableIntStateOf(0) }   // 重新检查
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    // 从系统设置、Shizuku 回来时自动刷新状态（不用再点「重新检查」）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .padding(AppTheme.space.s6),
        verticalArrangement = Arrangement.spacedBy(AppTheme.space.s6),
    ) {
        // 页码点
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s2)) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .height(AppTheme.space.s1)
                        .weight(1f)
                        .clip(AppTheme.shapes.pill)
                        .background(if (i <= step) c.accent else c.surfaceControl),
                )
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppTheme.space.s6)) {
        when (step) {
            0 -> {
                Text("先给我起个名字", style = AppTheme.type.display, color = c.text)
                Text("之后你可以随时在设置里改。名字和头像只保存在这台手机上。", style = AppTheme.type.body, color = c.textSecondary)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AgentAvatar(avatar, AppTheme.sizes.avatarLarge, workPulse = avatar) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(AppTheme.sizes.inputBar)
                        .clip(AppTheme.shapes.pill)
                        .background(c.surfaceInput)
                        .border(AppTheme.sizes.hairline, c.border, AppTheme.shapes.pill)
                        .padding(horizontal = AppTheme.space.s5),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (name.isEmpty()) Text("比如：小云", style = AppTheme.type.body, color = c.placeholder)
                    BasicTextField(
                        name, { name = it.take(12) }, singleLine = true,
                        textStyle = AppTheme.type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("选一个头像", style = AppTheme.type.label, color = c.textTertiary)
                AvatarPicker(avatar) { avatar = it }
            }
            1 -> {
                val s = remember(tick) { SystemStatus.shizuku() }
                PermissionStep(
                    AppIcons.shield, if (s == 0) "Shizuku 还没有运行" else "连接 Shizuku",
                    when (s) {
                        0 -> "没有检测到 Shizuku 在运行，我没法在后台操作应用。请打开 Shizuku，按它的提示启动（无线调试或 adb 都可以），启动后回到这里，状态会自动刷新。手机重启后需要重新启动一次。"
                        1 -> "Shizuku 已经在运行，但还没有授权给我。点下面的「授权」，在弹窗里选择允许。"
                        else -> "Shizuku 已连接，可以继续。"
                    },
                    status = "状态：${SystemStatus.shizukuText(s)}", ok = s == 2,
                )
            }
            2 -> {
                val ok = remember(tick) { SystemStatus.notificationsGranted(ctx) }
                PermissionStep(
                    AppIcons.bell, "允许通知",
                    "需要你确认或回答时，我会发通知。悬浮小窗可能被某些应用隐藏，通知是可靠的兜底。",
                    status = if (ok) "状态：已允许" else "状态：未允许", ok = ok,
                )
            }
            else -> {
                val ok = remember(tick) { SystemStatus.overlayGranted(ctx) }
                PermissionStep(
                    AppIcons.layers, "允许显示在其他应用上层",
                    "你离开这里时，我会在屏幕边缘放一个小窗显示进度，随时可以停止。小窗不会抢走你的键盘。",
                    status = if (ok) "状态：已允许" else "状态：未允许", ok = ok,
                )
            }
        }
        }
        // 按钮
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.s3)) {
            when (step) {
                0 -> PillButton("下一步", { step = 1 }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = name.isNotBlank())
                1 -> {
                    val s = remember(tick) { SystemStatus.shizuku() }
                    if (s == 0) PillButton("打开 Shizuku", {
                        val i = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (i != null) ctx.startActivity(i) else android.widget.Toast.makeText(ctx, "没有找到 Shizuku 应用，请先安装", android.widget.Toast.LENGTH_LONG).show()
                    }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
                    if (s == 1) PillButton("授权", { runCatching { rikka.shizuku.Shizuku.requestPermission(0) }; tick++ }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
                    PillButton("重新检查", { tick++ }, Modifier.fillMaxWidth())
                    PillButton(if (s == 2) "下一步" else "先跳过", { step = 2 }, Modifier.fillMaxWidth(), kind = if (s == 2) ButtonKind.Primary else ButtonKind.Secondary)
                }
                2 -> {
                    PillButton("允许通知", { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else tick++ }, Modifier.fillMaxWidth())
                    PillButton("下一步", { step = 3 }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
                }
                else -> {
                    PillButton("去开启", {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }, Modifier.fillMaxWidth())
                    PillButton("完成", { onFinish(name, avatar) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
                }
            }
        }
    }
}

@Composable
private fun PermissionStep(icon: ImageVector, title: String, body: String, status: String, ok: Boolean) {
    val c = AppTheme.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(AppTheme.sizes.avatarLarge).clip(AppTheme.shapes.circle).background(c.surface),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = c.accent, modifier = Modifier.size(AppTheme.space.s8)) }
    }
    Text(title, style = AppTheme.type.display, color = c.text)
    Text(body, style = AppTheme.type.body, color = c.textSecondary)
    Row(
        Modifier.clip(AppTheme.shapes.pill).background(c.surface).padding(horizontal = AppTheme.space.s4, vertical = AppTheme.space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s2),
    ) {
        Icon(if (ok) AppIcons.check else AppIcons.alert, null, tint = if (ok) c.success else c.warning, modifier = Modifier.size(AppTheme.sizes.icon))
        Text(status, style = AppTheme.type.label, color = c.text)
    }
}

/** 6 个预设头像的选择行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarPicker(selected: Int, onSelect: (Int) -> Unit) {
    val c = AppTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s4), verticalArrangement = Arrangement.spacedBy(AppTheme.space.s4)) {
        repeat(6) { i ->
            val on = i == selected
            Box(
                Modifier
                    .size(AppTheme.sizes.avatar + AppTheme.space.s2)
                    .clip(AppTheme.shapes.circle)
                    .border(if (on) AppTheme.sizes.iconStroke else AppTheme.sizes.hairline, if (on) c.accent else c.border, AppTheme.shapes.circle)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { AgentAvatar(i, AppTheme.sizes.avatar - AppTheme.space.s2, animate = false) }
        }
    }
}
