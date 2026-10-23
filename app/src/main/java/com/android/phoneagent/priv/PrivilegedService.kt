package com.android.phoneagent.priv

import android.os.Process
import android.view.Surface
import com.android.phoneagent.IPrivileged

/** 运行在 Shizuku UserService 进程（shell uid）。必须有无参构造。 */
class PrivilegedService : IPrivileged.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun getUid(): Int = Process.myUid()

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int =
        throw UnsupportedOperationException("step 3")

    override fun launchApp(displayId: Int, component: String) = throw UnsupportedOperationException("step 4")
    override fun tap(displayId: Int, x: Float, y: Float) = throw UnsupportedOperationException("step 5")
    override fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) =
        throw UnsupportedOperationException("step 5")

    override fun back(displayId: Int) = throw UnsupportedOperationException("step 5")
    override fun setText(displayId: Int, text: String): Boolean = throw UnsupportedOperationException("step 6")
    override fun dumpTree(displayId: Int): String = throw UnsupportedOperationException("step 6")
    override fun release() {}
}
