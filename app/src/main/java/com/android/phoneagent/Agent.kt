package com.android.phoneagent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
    private const val IME_POLICY_HIDE = 2

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
    // 只读查询（dump）单独一个线程，不能排在被 UserActivityGuard 暂停的动作后面
    private val readIo = Executors.newSingleThreadExecutor()
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
        pauseWhileTyping = appContext.getSharedPreferences("agent", Context.MODE_PRIVATE).getBoolean("pause_while_typing", false)
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

    /** 主屏测试输入框的当前内容（MainActivity 同步过来），仅供 debug 回归脚本读取，比读界面树可靠。 */
    @Volatile var mainFieldText = ""

    /** 用户正在主屏打字，agent 动作被暂停。 */
    var waitingForUser by mutableStateOf(false)
        private set

    @Volatile private var cancelled = false

    /**
     * 创建虚拟屏时加 VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED：虚拟屏上的动作不再切走 top focused display。
     * 默认开；只有做对照实验时才关（debug 广播 config）。
     */
    @Volatile var stealTopFocusDisabled = true

    /** 「用户打字时暂停 agent」安全开关，默认关闭，持久化在 SharedPreferences。 */
    var pauseWhileTyping by mutableStateOf(false)
        private set

    fun updatePauseWhileTyping(on: Boolean) {
        pauseWhileTyping = on
        appContext.getSharedPreferences("agent", Context.MODE_PRIVATE).edit().putBoolean("pause_while_typing", on).apply()
    }
    private val guard = UserActivityGuard(
        priv = { priv },
        isCancelled = { cancelled },
        onWaiting = { waitingForUser = it; if (it) status = "等待用户输入完成" },
        enabled = { pauseWhileTyping },
    )

    /** 在后台线程执行特权调用，结果/异常写入 status 与 logcat。[guarded] 的动作会被 [UserActivityGuard] 包住。 */
    private fun call(what: String, guarded: Boolean = false, executor: java.util.concurrent.Executor = io, block: (IPrivileged) -> Unit) {
        executor.execute {
            val p = priv ?: return@execute report("$what 失败：UserService 未连接")
            try {
                if (guarded) guard.guarded(what) { block(p) } else block(p)
            } catch (t: java.util.concurrent.CancellationException) {
                report("$what 已取消")
            } catch (t: Throwable) {
                Log.e(TAG, "$what 失败", t)
                report("$what 失败：${t.message}")
            }
        }
    }

    fun startDisplay() {
        // 先拉起前台服务保活（必须在 App 可见时调用）
        cancelled = false
        appContext.startForegroundService(Intent(appContext, AgentService::class.java))
        createDisplay()
    }

    /** 释放 VirtualDisplay、ImageReader、UiAutomation，并停掉前台服务。 */
    fun stop() {
        cancelled = true   // 让正在等待用户的动作立即退出，否则「停止」会排在它后面
        call("停止") { stopNow(it) }
    }

    private fun stopNow(p: IPrivileged) {
        p.release()
        stopReader()
        preview = null
        displayId = -1
        appContext.stopService(Intent(appContext, AgentService::class.java))
        cancelled = false
        report("已停止并释放")
    }

    private fun createDisplay() = call("创建虚拟屏") { p ->
        stopReader()
        val thread = HandlerThread("preview").also { it.start() }
        val r = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, Handler(thread.looper))
        readerThread = thread
        reader = r
        displayId = p.createDisplay(r.surface, WIDTH, HEIGHT, DPI, stealTopFocusDisabled)
        // 虚拟屏上的窗口不许请求输入法，否则 App（如设置搜索页）会在用户的屏幕上弹出键盘。
        // 策略按 uniqueId 缓存，每次创建后都显式设置。
        p.setImePolicy(displayId, IME_POLICY_HIDE)
        report("虚拟屏已创建 displayId=$displayId")
    }

    fun launch(component: String = "com.android.settings/.Settings") = call("启动 $component", guarded = true) { p ->
        p.launchApp(displayId, component)
        report("已在 display $displayId 启动 $component")
    }

    fun tap(x: Float, y: Float) = call("点击", guarded = true) { it.tap(displayId, x, y); report("点击 ($x,$y)") }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) =
        call("滑动", guarded = true) { it.swipe(displayId, x1, y1, x2, y2, 300); report("滑动 ($x1,$y1)->($x2,$y2)") }

    fun back() = call("返回", guarded = true) { it.back(displayId); report("返回键") }

    fun setText(text: String) = call("写入文字", guarded = true) { report("写入文字 ${if (it.setText(displayId, text)) "成功" else "失败(无聚焦输入框)"}") }

    fun dumpTree(display: Int = displayId) = call("dump 界面树", executor = readIo) { Log.i(TAG, "tree:\n" + it.dumpTree(display)); report("界面树已输出到 logcat") }

    // 以下是输入法隔离实验用的入口
    fun setImePolicy(policy: Int) = call("IME 策略") { report("IME 策略 display=$displayId ${it.setImePolicy(displayId, policy)} -> $policy") }
    fun clickNode(x: Float, y: Float) = call("语义点击", guarded = true) { report("语义点击 ($x,$y) ${if (it.clickAt(displayId, x, y)) "成功" else "无可点击节点"}") }
    fun scrollNode(forward: Boolean) = call("语义滚动", guarded = true) { report("语义滚动 fwd=$forward ${if (it.scroll(displayId, forward)) "成功" else "失败"}") }
    // 禁用：作用于 top focused display，仅 scripts/back_test.sh 用来证明它会误操作主屏
    fun globalBack() = call("GLOBAL_BACK") { report("GLOBAL_ACTION_BACK ${if (it.globalBack()) "已执行" else "失败"}") }

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
