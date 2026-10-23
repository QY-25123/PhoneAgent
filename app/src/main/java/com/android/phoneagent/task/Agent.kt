package com.android.phoneagent.task

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
import com.android.phoneagent.AgentService
import com.android.phoneagent.FloatingWindow
import com.android.phoneagent.IPrivileged
import com.android.phoneagent.perception.Perception
import com.android.phoneagent.perception.PerceiveOptions
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch as launchCoroutine
import com.android.phoneagent.privileged.PrivilegedService
import com.android.phoneagent.safety.UserActivityGuard
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

    private var floating: FloatingWindow? = null
    fun overlay(op: String, text: String = "") {
        if (floating == null) floating = FloatingWindow(appContext, onOpen = { Log.i(TAG, "点击小窗") })
        val stopAction = com.android.phoneagent.FloatAction("停止") { stop() }
        when (op) { "on", "text" -> floating?.show("执行中", text.ifEmpty { "执行中" }, listOf(stopAction)); "off" -> floating?.hide() }
    }

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
        lastFull = null
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
        readerHandler = Handler(thread.looper)
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

    fun dumpTree(display: Int = displayId, saveTo: java.io.File? = null) = call("dump 界面树", executor = readIo) {
        val tree = it.dumpTree(display)
        saveTo?.writeText(tree)
        Log.i(TAG, "tree:\n$tree")
        report("界面树已输出到 logcat")
    }

    /** 在特权线程上执行一个调用（协程版）。[guarded] 的动作会经过 [UserActivityGuard]。异常原样抛出。 */
    suspend fun <T> withPriv(what: String, guarded: Boolean = false, block: (IPrivileged) -> T): T =
        kotlinx.coroutines.withContext(io.asCoroutineDispatcher()) {
            val p = priv ?: throw IllegalStateException("UserService 未连接")
            if (guarded) guard.guarded(what) { block(p) } else block(p)
        }

    /**
     * 确保能操作虚拟屏：连接 Shizuku/UserService、创建虚拟屏、等到有画面。返回 displayId。
     * 失败抛 [IllegalStateException]（message 是给用户看的原因）。
     */
    suspend fun ensureReady(): Int {
        keepFull = true
        if (previewFps < 5) previewFps = 5   // 感知要拿刚稳定下来的那一帧
        if (priv == null) {
            connect()
            repeat(50) { if (priv == null) kotlinx.coroutines.delay(300) }
            if (priv == null) throw IllegalStateException("没有连上 Shizuku。请确认 Shizuku 在运行并已授权给本应用")
        }
        if (displayId < 0) {
            startDisplay()
            repeat(50) { if (displayId < 0) kotlinx.coroutines.delay(300) }
            if (displayId < 0) throw IllegalStateException("准备失败，请重试")
        }
        repeat(20) { if (lastFull == null) kotlinx.coroutines.delay(250) }
        return displayId
    }

    /** 感知：取虚拟屏的原始快照（JSON）。返回 null 表示未连接。 */
    fun snapshot(display: Int = displayId): String? = priv?.snapshotTree(display)

    /** debug：在虚拟屏上用 am start 启动一个意图（只放行日历插入/Gmail mailto）。 */
    fun launchIntent(args: Array<String>) = call("启动意图", guarded = true) { it.launchIntent(displayId, args); report("已启动意图") }

    /** 感知：虚拟屏最新一帧的全分辨率位图（需要 [keepFull]）。 */
    fun latestFrame(): Bitmap? = lastFull

    /**
     * debug：对虚拟屏当前页面做一次完整感知，结果写到 files/perceive/<name>.{txt,jpg}；
     * [raw] 时另存原始快照 .snap.json 和原始截图 .png（用来录制测试样本，可能含个人信息，不要提交）。
     */
    fun perceiveTo(dir: java.io.File, name: String, raw: Boolean, waitForStable: Boolean = true) {
        keepFull = true
        val oldFps = previewFps
        if (previewFps < 5) previewFps = 5   // 画面采集频率调高，保证拿到的是刚稳定下来的那一帧
        perceiveScope.launchCoroutine {
            try {
                dir.mkdirs()
                val res = Perception(AgentPerceptionSource(displayId)).perceive(displayId, PerceiveOptions(waitForStable = waitForStable))
                java.io.File(dir, "$name.txt").writeText(
                    res.text + "\n--- timings: ${res.timings}\n--- secureWindow=${res.state.secureWindow} treeEmpty=${res.state.treeEmpty} chars=${res.text.length}\n")
                res.state.screenshotJpeg?.let { java.io.File(dir, "$name.jpg").writeBytes(it) }
                if (raw) {
                    snapshot(displayId)?.let { java.io.File(dir, "$name.snap.json").writeText(it) }
                    latestFrame()?.let { b -> java.io.File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
                report("PERCEIVE $name elements=${res.state.elements.size} chars=${res.text.length} ${res.timings}")
            } catch (e: Throwable) {
                report("PERCEIVE $name 失败: $e")
            } finally {
                previewFps = oldFps
            }
        }
    }
    private val perceiveScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** debug：取一份快照存文件，并记录耗时和大小（M1 性能测量）。 */
    fun snapshotTo(file: java.io.File, display: Int = displayId) = call("快照", executor = readIo) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val json = it.snapshotTree(display)
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        file.writeText(json)
        report("SNAPSHOT ${ms}ms ${json.length}B ${file.absolutePath}")
    }

    /** debug：验证期间发测试邮件只能发给这个白名单地址。 */
    fun armGmailSend(recipient: String) = call("布防发送", executor = readIo) {
        require(recipient.equals("qijie_jerry@163.com", true)) { "测试邮件只能发给 qijie_jerry@163.com" }
        report("布防发送 $recipient → ${it.armGmailSend(displayId, recipient)}")
    }

    fun setTextAt(x: Float, y: Float, text: String) = call("写入文字(按坐标)", guarded = true) { report("写入文字(按坐标) ($x,$y) ${if (it.setTextAt(displayId, x, y, text)) "成功" else "失败(该处没有可编辑节点)"}") }

    fun pressKey(code: Int) = call("按键 $code", guarded = true) { it.pressKey(displayId, code); report("按键 $code") }

    fun listTasks(display: Int) = call("任务列表", executor = readIo) { Log.i(TAG, "TASKS display=$display\n" + it.listTasks(display)); report("任务列表已输出") }
    fun moveTask(taskId: Int, display: Int) = call("移动任务", executor = readIo) { it.moveTaskToDisplay(taskId, display); report("已移动任务 $taskId → display $display") }

    /** 按文案点击（硬拦截生效）。结果写入 status/logcat。 */
    fun clickText(text: String, touch: Boolean, display: Int = displayId) = call("按文案点击「$text」", guarded = display == displayId) { report("clickText「$text」→ ${it.clickText(display, text, touch)}") }

    // 以下是输入法隔离实验用的入口
    fun setImePolicy(policy: Int) = call("IME 策略") { report("IME 策略 display=$displayId ${it.setImePolicy(displayId, policy)} -> $policy") }
    fun clickNode(x: Float, y: Float) = call("语义点击", guarded = true) { report("语义点击 ($x,$y) ${if (it.clickAt(displayId, x, y)) "成功" else "无可点击节点"}") }
    fun scrollNode(forward: Boolean) = call("语义滚动", guarded = true) { report("语义滚动 fwd=$forward ${if (it.scroll(displayId, forward)) "成功" else "失败"}") }
    // 禁用：作用于 top focused display，仅 scripts/back_test.sh 用来证明它会误操作主屏
    fun globalBack() = call("GLOBAL_BACK") { report("GLOBAL_ACTION_BACK ${if (it.globalBack()) "已执行" else "失败"}") }

    // 画面采集频率：默认 1 fps（非全屏，仅供观察）；全屏观看时调高。可用 debug 广播 config 修改
    @Volatile var previewFps = 1
    @Volatile var keepFull = false          // debug：保留最近一帧的全分辨率位图，供 shot 保存
    @Volatile private var lastFull: Bitmap? = null
    private var readerHandler: Handler? = null
    private var catchUpPending = false
    val framesAvailable = java.util.concurrent.atomic.AtomicLong()   // ImageReader 回调次数（虚拟屏实际产出的帧）
    val framesProcessed = java.util.concurrent.atomic.AtomicLong()   // 转成位图并交给界面的帧
    val uiFrames = java.util.concurrent.atomic.AtomicLong()          // 界面实际收到并重组的帧（MainActivity 里统计）

    private fun onFrame(r: ImageReader) {
        framesAvailable.incrementAndGet()
        val wait = 1000L / previewFps - (android.os.SystemClock.uptimeMillis() - lastFrameAt)
        if (wait > 0) {
            // 太快了先不取。但要补一次：否则画面静止前的最后一帧会被丢掉，预览停在旧画面
            if (!catchUpPending) {
                catchUpPending = true
                readerHandler?.postDelayed({ catchUpPending = false; processLatest(r) }, wait)
            }
            return
        }
        processLatest(r)
    }

    private fun processLatest(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            lastFrameAt = android.os.SystemClock.uptimeMillis()
            val plane = img.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
            if (keepFull) lastFull = cropped
            val small = Bitmap.createScaledBitmap(cropped, 540, 1200, true)
            if (cropped !== full) full.recycle()
            preview = small.asImageBitmap()
            framesProcessed.incrementAndGet()
        } finally {
            img.close()
        }
    }

    /** debug：把最近一帧的全分辨率画面保存成 PNG（需要先 config keepfull=true）。 */
    fun saveShot(file: java.io.File): Boolean {
        val b = lastFull ?: return false
        file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return true
    }

    /** debug：在虚拟屏上持续上下滑动 [seconds] 秒，统计虚拟屏产出的帧数和实际转成预览的帧数。 */
    fun fpsTest(seconds: Int, swipe: Boolean = true) = call("fps 测试", executor = readIo) { p ->
        framesAvailable.set(0); framesProcessed.set(0); uiFrames.set(0)
        val end = System.currentTimeMillis() + seconds * 1000L
        var up = true
        while (System.currentTimeMillis() < end) {
            if (!swipe) { Thread.sleep(100); continue }
            if (up) p.swipe(displayId, 540f, 1900f, 540f, 500f, 350) else p.swipe(displayId, 540f, 500f, 540f, 1900f, 350)
            up = !up
        }
        Thread.sleep(500)
        report("FPS 结果：${seconds}s 内虚拟屏产出 ${framesAvailable.get()} 帧（${framesAvailable.get() / seconds.toFloat()} fps），转成预览 ${framesProcessed.get()} 帧（${framesProcessed.get() / seconds.toFloat()} fps），界面显示 ${uiFrames.get()} 帧（${uiFrames.get() / seconds.toFloat()} fps），previewFps=$previewFps")
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
