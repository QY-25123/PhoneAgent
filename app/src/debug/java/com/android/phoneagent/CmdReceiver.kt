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
            "launch" -> intent.getStringExtra("c")?.let { Agent.launch(it) } ?: Agent.launch()
            "clicktext" -> Agent.clickText(intent.getStringExtra("t") ?: "", intent.getBooleanExtra("touch", true), intent.getIntExtra("display", Agent.displayId))
            "shot" -> {
                val f = java.io.File(context.getExternalFilesDir(null), "shot.png")
                android.util.Log.i("PA", "SHOT ${Agent.saveShot(f)} ${f.absolutePath}")
            }
            "tasks" -> Agent.listTasks(intent.getIntExtra("display", Agent.displayId))
            "movetask" -> Agent.moveTask(intent.getIntExtra("task", -1), intent.getIntExtra("display", 0))
            "fps" -> Agent.fpsTest(intent.getIntExtra("sec", 10))
            "tap" -> Agent.tap(intent.getIntExtra("x", 540).toFloat(), intent.getIntExtra("y", 1200).toFloat())
            "swipe" -> Agent.swipe(540f, 1800f, 540f, 600f)
            "back" -> Agent.back()
            "text" -> Agent.setText(intent.getStringExtra("t") ?: "你好，世界")
            "dump" -> Agent.dumpTree(intent.getIntExtra("display", Agent.displayId), java.io.File(context.getExternalFilesDir(null), "tree.txt"))
            "imepolicy" -> Agent.setImePolicy(intent.getIntExtra("p", 2))
            "click" -> Agent.clickNode(intent.getIntExtra("x", 540).toFloat(), intent.getIntExtra("y", 540).toFloat())
            "scroll" -> Agent.scrollNode(intent.getBooleanExtra("fwd", true))
            "gback" -> Agent.globalBack()
            "config" -> {
                Agent.stealTopFocusDisabled = intent.getBooleanExtra("steal", Agent.stealTopFocusDisabled)
                Agent.keepFull = intent.getBooleanExtra("keepfull", Agent.keepFull)
                Agent.previewFps = intent.getIntExtra("fps", Agent.previewFps)
                Agent.updatePauseWhileTyping(intent.getBooleanExtra("pause", Agent.pauseWhileTyping))
            }
            "stop" -> Agent.stop()
            "maintext" -> android.util.Log.i("PA", "MAIN_FIELD=" + Agent.mainFieldText)
        }
    }
}
