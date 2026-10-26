package com.android.phoneagent.task

import android.content.Context
import android.graphics.Bitmap
import com.android.phoneagent.agent.PhoneController
import com.android.phoneagent.agent.RunLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 真实环境：[Agent]（Shizuku + 虚拟屏）。运行记录写到 <外部私有目录>/runs/<时间>/。 */
class AgentTaskEnvironment(private val context: Context) : TaskEnvironment {
    override suspend fun prepare(): PhoneController {
        Agent.init(context)
        val displayId = try { Agent.ensureReady() } catch (e: IllegalStateException) { throw e }
        return AgentPhoneController(displayId)
    }

    override fun newRunLog(): RunLog {
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return RunRecorder(File(context.getExternalFilesDir(null), "runs/$name"))
    }

    override fun thumbnail(width: Int): Bitmap? {
        val f = Agent.latestFrame() ?: return null
        return Bitmap.createScaledBitmap(f, width, f.height * width / f.width, true)
    }

    /** 把虚拟屏上的 Amazon 任务移到主屏（display 0），然后释放虚拟屏。 */
    override suspend fun handoffToMain() {
        val id = Agent.displayId
        if (id >= 0) {
            val tasks = Agent.withPriv("列出任务") { it.listTasks(id) }
            // 每行：rootId childIds 组件 visible=…；取第一个 Amazon / 地图的根任务
            val root = tasks.lines().firstOrNull { it.contains("com.amazon.mShop") || it.contains("com.google.android.apps.maps") }?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
            if (root != null) Agent.withPriv("移到主屏") { it.moveTaskToDisplay(root, 0) }
        }
        release()
    }

    override suspend fun release() {
        if (Agent.displayId < 0) return
        // stop() 在特权线程上异步执行 release；等虚拟屏标记消失
        Agent.stop()
        repeat(40) { if (Agent.displayId >= 0) kotlinx.coroutines.delay(100) }
    }
}
