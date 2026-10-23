package com.android.phoneagent.priv

import android.os.Process
import android.view.Surface
import com.android.phoneagent.IPrivileged

/** 运行在 Shizuku UserService 进程（shell uid）。必须有无参构造。 */
class PrivilegedService : IPrivileged.Stub() {
    // 构造函数在 UserService 的 main looper 线程执行，ActivityThread 必须在这里初始化
    private val context = FakeContext.create()
    private val display = VirtualDisplayHolder(context)

    override fun destroy() {
        System.exit(0)
    }

    override fun getUid(): Int = Process.myUid()

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int =
        display.create(surface, width, height, dpi)

    // 原型阶段直接用 am start --display；--activity-clear-task 避免复用主屏上已有的 task
    override fun launchApp(displayId: Int, component: String) {
        val p = ProcessBuilder("am", "start", "--display", displayId.toString(), "--activity-clear-task", "-n", component)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (out.contains("Error")) error(out.trim())
    }
    override fun tap(displayId: Int, x: Float, y: Float) = throw UnsupportedOperationException("step 5")
    override fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) =
        throw UnsupportedOperationException("step 5")

    override fun back(displayId: Int) = throw UnsupportedOperationException("step 5")
    override fun setText(displayId: Int, text: String): Boolean = throw UnsupportedOperationException("step 6")
    override fun dumpTree(displayId: Int): String = throw UnsupportedOperationException("step 6")
    override fun release() {
        display.release()
    }
}
