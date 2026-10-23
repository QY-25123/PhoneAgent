package com.android.phoneagent

import com.android.phoneagent.task.Agent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

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
            "key" -> Agent.pressKey(intent.getIntExtra("code", 66))
            "textat" -> Agent.setTextAt(intent.getIntExtra("x", 0).toFloat(), intent.getIntExtra("y", 0).toFloat(), intent.getStringExtra("t") ?: "")
            "armsend" -> Agent.armGmailSend(intent.getStringExtra("r") ?: "")
            "tasks" -> Agent.listTasks(intent.getIntExtra("display", Agent.displayId))
            "movetask" -> Agent.moveTask(intent.getIntExtra("task", -1), intent.getIntExtra("display", 0))
            "overlay" -> Agent.overlay(intent.getStringExtra("op") ?: "on", intent.getStringExtra("t") ?: "")
            "fps" -> Agent.fpsTest(intent.getIntExtra("sec", 10), intent.getBooleanExtra("swipe", true))
            "tap" -> Agent.tap(intent.getIntExtra("x", 540).toFloat(), intent.getIntExtra("y", 1200).toFloat())
            "swipe" -> Agent.swipe(540f, 1800f, 540f, 600f)
            "back" -> Agent.back()
            "text" -> Agent.setText(intent.getStringExtra("t") ?: "你好，世界")
            "dump" -> Agent.dumpTree(intent.getIntExtra("display", Agent.displayId), java.io.File(context.getExternalFilesDir(null), "tree.txt"))
            "snap" -> Agent.snapshotTo(java.io.File(context.getExternalFilesDir(null), "snap.json"), intent.getIntExtra("display", Agent.displayId))
            "perceive" -> Agent.perceiveTo(
                java.io.File(context.getExternalFilesDir(null), "perceive"),
                intent.getStringExtra("name") ?: "p${System.currentTimeMillis()}", intent.getBooleanExtra("raw", false), intent.getBooleanExtra("wait", true))
            "perceivesample" -> {
                // 离线：对 files/samples/NAME.{snap.json,png} 跑同一条感知管线（不需要连接虚拟屏）
                val dir = java.io.File(context.getExternalFilesDir(null), "samples")
                val name = intent.getStringExtra("name") ?: return
                val pending = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    try {
                        val res = com.android.phoneagent.perception.Perception(SampleSource(dir, name)).perceive(
                            0, com.android.phoneagent.perception.PerceiveOptions(
                                waitForStable = false,
                                extract = com.android.phoneagent.perception.ExtractOptions(),
                            ))
                        val out = java.io.File(dir, "out").apply { mkdirs() }
                        java.io.File(out, "$name.txt").writeText(res.text + "\n--- timings: ${res.timings}\n")
                        res.state.screenshotJpeg?.let { java.io.File(out, "$name.jpg").writeBytes(it) }
                        android.util.Log.i("PA", "SAMPLE $name elements=${res.state.elements.size} chars=${res.text.length} ${res.timings}")
                    } finally { pending.finish() }
                }
            }
            "claudetest" -> {
                // 连通性测试：一次最小的结构化输出调用（不含任何隐私内容）
                val pending = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    try {
                        val r = com.android.phoneagent.agent.ClaudeModelClient(com.android.phoneagent.BuildConfig.CLAUDE_API_KEY).complete(
                            com.android.phoneagent.agent.ModelRequest(
                                "test", "只输出 JSON。",
                                listOf(com.android.phoneagent.agent.ModelMessage("user", "把 12 加 30，结果放进 answer 字段。")),
                                """{"type":"object","properties":{"answer":{"type":"integer"}},"required":["answer"],"additionalProperties":false}""",
                            ))
                        android.util.Log.i("PA", "CLAUDETEST ok json=${r.json} usage=in${r.usage.inputTokens}/out${r.usage.outputTokens} ${r.latencyMs}ms stop=${r.stopReason}")
                    } catch (e: Throwable) {
                        android.util.Log.i("PA", "CLAUDETEST 失败: $e / ${e.cause}")
                    } finally { pending.finish() }
                }
            }
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
