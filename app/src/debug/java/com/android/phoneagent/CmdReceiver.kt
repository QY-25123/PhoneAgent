package com.android.phoneagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 仅供 adb 自测：am broadcast -a com.android.phoneagent.CMD --es cmd connect */
class CmdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Agent.init(context)
        when (intent.getStringExtra("cmd")) {
            "connect" -> Agent.connect()
            "display" -> Agent.startDisplay()
            "launch" -> Agent.launch()
            "tap" -> Agent.tap(intent.getIntExtra("x", 540).toFloat(), intent.getIntExtra("y", 1200).toFloat())
            "swipe" -> Agent.swipe(540f, 1800f, 540f, 600f)
            "back" -> Agent.back()
            "text" -> Agent.setText(intent.getStringExtra("t") ?: "你好，世界")
            "dump" -> Agent.dumpTree(intent.getIntExtra("display", Agent.displayId))
            "imepolicy" -> Agent.setImePolicy(intent.getIntExtra("p", 2))
            "click" -> Agent.clickNode(intent.getIntExtra("x", 540).toFloat(), intent.getIntExtra("y", 540).toFloat())
            "scroll" -> Agent.scrollNode(intent.getBooleanExtra("fwd", true))
            "gback" -> Agent.globalBack()
            "stop" -> Agent.stop()
        }
    }
}
