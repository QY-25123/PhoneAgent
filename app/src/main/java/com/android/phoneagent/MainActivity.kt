package com.android.phoneagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.phoneagent.ui.theme.PhoneAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Agent.init(this)
        setContent {
            PhoneAgentTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(Modifier.padding(innerPadding).padding(16.dp)) {
                        Text("状态：${Agent.status}")
                        Button(onClick = { Agent.connect() }) { Text("连接 Shizuku") }
                        Button(onClick = { Agent.startDisplay() }) { Text("创建虚拟屏") }
                        Button(onClick = { Agent.launch() }) { Text("启动设置") }
                        Agent.preview?.let { Image(it, "preview", Modifier.height(300.dp)) }
                    }
                }
            }
        }
    }
}
