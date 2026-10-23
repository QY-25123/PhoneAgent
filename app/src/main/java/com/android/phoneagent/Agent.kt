package com.android.phoneagent

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.phoneagent.priv.PrivilegedService
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** 进程内单例：持有 Shizuku 连接和 UserService，UI 与 adb 自测入口共用。 */
object Agent {
    private const val TAG = "PA"
    private const val REQ_CODE = 1

    var status by mutableStateOf("未连接")
        private set

    const val WIDTH = 1080
    const val HEIGHT = 2400
    const val DPI = 420

    /** 虚拟屏预览（缩小后的最新一帧，每秒最多更新一次）。 */
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var displayId by mutableStateOf(-1)
        private set

    private var reader: ImageReader? = null
    private var readerThread: HandlerThread? = null
    private var lastFrameAt = 0L

    private var priv: IPrivileged? = null
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var appContext: Context

    private val conn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            priv = IPrivileged.Stub.asInterface(binder)
            io.execute { report("UserService 已连接，uid=${priv?.uid}") }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            priv = null
            report("UserService 断开")
        }
    }

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { report("Shizuku binder 已到达"); }
        Shizuku.addBinderDeadListener { priv = null; report("Shizuku binder 已死") }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) bindService() else report("Shizuku 授权被拒绝")
        }
    }

    /** 检查 Shizuku、申请授权、绑定 UserService。 */
    fun connect() {
        if (!Shizuku.pingBinder()) return report("Shizuku 未运行")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            report("请求 Shizuku 授权…")
            Shizuku.requestPermission(REQ_CODE)
        } else bindService()
    }

    private fun bindService() {
        val args = Shizuku.UserServiceArgs(ComponentName(appContext.packageName, PrivilegedService::class.java.name))
            .processNameSuffix("priv").daemon(false).version(2)
        Shizuku.bindUserService(args, conn)
        report("正在绑定 UserService…")
    }

    /** 在后台线程执行特权调用，结果/异常写入 status 与 logcat。 */
    private fun call(what: String, block: (IPrivileged) -> Unit) {
        io.execute {
            val p = priv ?: return@execute report("$what 失败：UserService 未连接")
            try {
                block(p)
            } catch (t: Throwable) {
                Log.e(TAG, "$what 失败", t)
                report("$what 失败：${t.message}")
            }
        }
    }

    fun startDisplay() = call("创建虚拟屏") { p ->
        stopReader()
        val thread = HandlerThread("preview").also { it.start() }
        val r = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, Handler(thread.looper))
        readerThread = thread
        reader = r
        displayId = p.createDisplay(r.surface, WIDTH, HEIGHT, DPI)
        report("虚拟屏已创建 displayId=$displayId")
    }

    fun launch(component: String = "com.android.settings/.Settings") = call("启动 $component") { p ->
        p.launchApp(displayId, component)
        report("已在 display $displayId 启动 $component")
    }

    fun tap(x: Float, y: Float) = call("点击") { it.tap(displayId, x, y); report("点击 ($x,$y)") }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) =
        call("滑动") { it.swipe(displayId, x1, y1, x2, y2, 300); report("滑动 ($x1,$y1)->($x2,$y2)") }

    fun back() = call("返回") { it.back(displayId); report("返回键") }

    fun setText(text: String) = call("写入文字") { report("写入文字 ${if (it.setText(displayId, text)) "成功" else "失败(无聚焦输入框)"}") }

    fun dumpTree() = call("dump 界面树") { Log.i(TAG, "tree:\n" + it.dumpTree(displayId)); report("界面树已输出到 logcat") }

    private fun onFrame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < 1000) return
            lastFrameAt = now
            val plane = img.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(full, 0, 0, img.width, img.height), 540, 1200, true)
            full.recycle()
            preview = small.asImageBitmap()
        } finally {
            img.close()
        }
    }

    private fun stopReader() {
        reader?.close()
        reader = null
        readerThread?.quitSafely()
        readerThread = null
    }

    private fun report(msg: String) {
        Log.i(TAG, msg)
        status = msg
    }
}
