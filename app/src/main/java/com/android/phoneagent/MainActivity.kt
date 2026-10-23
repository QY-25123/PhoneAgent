package com.android.phoneagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.phoneagent.ui.theme.PhoneAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Agent.init(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent {
            PhoneAgentTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var x by rememberSaveable { mutableStateOf("540") }
                    var y by rememberSaveable { mutableStateOf("740") }
                    var text by rememberSaveable { mutableStateOf("你好，世界") }
                    LaunchedEffect(text) { Agent.mainFieldText = text }
                    Column(
                        Modifier.padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("状态：${Agent.status}")
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
                        Agent.preview?.let { Image(it, "preview", Modifier.height(480.dp)) }
                    }
                }
            }
        }
    }
}
