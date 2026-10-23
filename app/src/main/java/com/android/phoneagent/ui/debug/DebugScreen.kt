package com.android.phoneagent.ui.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.phoneagent.Agent

/**
 * 技术验证用的测试界面（原来的首页）。开发期保留，从「设置 → 开发者调试」进入。
 * 回归脚本通过 `am start -n com.android.phoneagent/.MainActivity --es route debug` 直接打开它。
 * 这里是测试工具，不属于产品界面，不受设计令牌约束。
 */
@Composable
fun DebugScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var x by rememberSaveable { mutableStateOf("540") }
    var y by rememberSaveable { mutableStateOf("740") }
    var text by rememberSaveable { mutableStateOf("你好，世界") }
    LaunchedEffect(text) { Agent.mainFieldText = text }
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("状态：${Agent.status}")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(Agent.pauseWhileTyping, { Agent.updatePauseWhileTyping(it) })
            Text("用户打字时暂停 agent（安全开关）")
        }
        if (Agent.waitingForUser) {
            Text("等待用户输入完成", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Agent.connect() }) { Text("连接") }
            Button(onClick = { Agent.startDisplay() }) { Text("创建虚拟屏") }
            Button(onClick = { Agent.launch() }) { Text("启动设置") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(x, { x = it }, Modifier.weight(1f), label = { Text("x") }, singleLine = true)
            OutlinedTextField(y, { y = it }, Modifier.weight(1f), label = { Text("y") }, singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Agent.tap(x.toFloatOrNull() ?: 0f, y.toFloatOrNull() ?: 0f) }) { Text("点击") }
            Button(onClick = { Agent.swipe(540f, 1800f, 540f, 600f) }) { Text("上滑") }
            Button(onClick = { Agent.back() }) { Text("返回") }
        }
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("要写入虚拟屏的文字 / 主屏测试输入框") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Agent.setText(text) }) { Text("写入文字") }
            Button(onClick = { Agent.dumpTree() }) { Text("dump 界面树") }
            Button(onClick = { Agent.stop() }) { Text("停止") }
        }
        Agent.preview?.let {
            SideEffect { Agent.uiFrames.incrementAndGet() }   // 统计界面实际收到的帧（UV-1）
            Image(it, "preview", Modifier.height(480.dp))
        }
    }
    }
}
