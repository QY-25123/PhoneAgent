package com.android.phoneagent.privileged

import android.os.Process
import android.view.Surface
import com.android.phoneagent.IPrivileged

/** 运行在 Shizuku UserService 进程（shell uid）。必须有无参构造。 */
class PrivilegedService : IPrivileged.Stub() {
    // 构造函数在 UserService 的 main looper 线程执行，ActivityThread 必须在这里初始化
    private val context = FakeContext.create()
    private val display = VirtualDisplayHolder(context)
    private val input = InputInjector()
    private val ui = UiAutomationHelper()
    private val mainScreen = MainScreenState()
    private val tasks = DisplayTasks()

    override fun destroy() {
        System.exit(0)
    }

    override fun getUid(): Int = Process.myUid()

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int, stealTopFocusDisabled: Boolean): Int =
        display.create(surface, width, height, dpi, stealTopFocusDisabled)

    // 原型阶段直接用 am start --display；--activity-clear-task 避免复用主屏上已有的 task。
    // component 不含 "/" 时当作包名，解析它的 LAUNCHER activity。
    override fun launchApp(displayId: Int, component: String) {
        val target = if ("/" in component) component else resolveLauncher(component)
        val p = ProcessBuilder("am", "start", "--display", displayId.toString(), "--activity-clear-task", "-n", target)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (out.contains("Error")) error(out.trim())
    }

    /** 只放行三种意图：Amazon 链接（VIEW + https://www.amazon.com/ + -p Amazon）、日历「插入事项」（-a INSERT + content://com.android.calendar/...）和 Gmail 写邮件（-a SENDTO + mailto: + -p Gmail）；其他一律拒绝。 */
    override fun launchIntent(displayId: Int, amArgs: Array<String>) {
        val calendarInsert = amArgs.contains("android.intent.action.INSERT") && amArgs.any { it.startsWith("content://com.android.calendar/") }
        val mailCompose = amArgs.contains("android.intent.action.SENDTO") && amArgs.any { it.startsWith("mailto:") } && amArgs.contains("com.google.android.gm")
        val amazonView = amArgs.contains("android.intent.action.VIEW") && amArgs.any { it.startsWith("https://www.amazon.com/") } && amArgs.contains("com.amazon.mShop.android.shopping")
        require(calendarInsert || mailCompose || amazonView) { "不允许的意图" }
        val cmd = listOf("am", "start", "--display", displayId.toString(), "--activity-clear-task") + amArgs
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (out.contains("Error")) error(out.trim())
    }

    private fun resolveLauncher(pkg: String): String {
        val p = ProcessBuilder("cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", pkg)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out.lines().lastOrNull { "/" in it } ?: error("找不到 $pkg 的启动 Activity：$out")
    }

    /** 硬拦截：命中就抛 SecurityException，动作不执行（SafetyGuard）。 */
    private fun guardPoint(displayId: Int, x: Float?, y: Float?) {
        ui.blockReason(displayId, x?.toInt(), y?.toInt())?.let { throw SecurityException("BLOCKED $it") }
    }

    override fun tap(displayId: Int, x: Float, y: Float) {
        guardPoint(displayId, x, y)
        input.tap(displayId, x, y)
    }

    override fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        ui.blockReason(displayId, x1.toInt(), y1.toInt(), consume = false)?.takeIf { !it.startsWith("页面级") }?.let { throw SecurityException("BLOCKED $it") }
        input.swipe(displayId, x1, y1, x2, y2, durationMs)
    }

    override fun clickText(displayId: Int, text: String, touch: Boolean): String {
        val (node, label) = ui.findClickableByText(displayId, text) ?: return "NOT_FOUND"
        val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        guardPoint(displayId, r.centerX().toFloat(), r.centerY().toFloat())
        if (touch) input.tap(displayId, r.centerX().toFloat(), r.centerY().toFloat())
        else node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        return "CLICKED「$label」at ${r.centerX()},${r.centerY()}"
    }

    override fun pressKey(displayId: Int, keyCode: Int) {
        guardPoint(displayId, null, null)
        input.key(displayId, keyCode)
    }

    override fun back(displayId: Int) = input.key(displayId, android.view.KeyEvent.KEYCODE_BACK)
    override fun setText(displayId: Int, text: String): Boolean {
        guardPoint(displayId, null, null)
        return ui.setTextOnFocused(displayId, text)
    }
    override fun dumpTree(displayId: Int): String = ui.dump(displayId)
    override fun snapshotTree(displayId: Int): String = ui.snapshot(displayId)
    override fun setImePolicy(displayId: Int, policy: Int): Int {
        val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window"))
        val cls = wm.javaClass
        val old = cls.getMethod("getDisplayImePolicy", Int::class.java).invoke(wm, displayId) as Int
        if (policy >= 0) cls.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, displayId, policy)
        return old
    }

    override fun setTextAt(displayId: Int, x: Float, y: Float, text: String): Boolean {
        guardPoint(displayId, null, null)
        return ui.setTextAt(displayId, x.toInt(), y.toInt(), text)
    }

    override fun clickAt(displayId: Int, x: Float, y: Float): Boolean {
        guardPoint(displayId, x, y)
        return ui.clickAt(displayId, x.toInt(), y.toInt())
    }
    override fun clickNode(displayId: Int, windowId: Int, nodeIndex: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        guardPoint(displayId, (left + right) / 2f, (top + bottom) / 2f)
        return ui.clickNode(displayId, windowId, nodeIndex, left, top, right, bottom)
    }

    override fun setTextNode(displayId: Int, windowId: Int, nodeIndex: Int, left: Int, top: Int, right: Int, bottom: Int, text: String): Boolean {
        guardPoint(displayId, null, null)
        return ui.setTextNode(displayId, windowId, nodeIndex, left, top, right, bottom, text)
    }

    /** 虚拟屏上 Gmail 草稿当前内容的哈希（在特权进程里自己读，不信任调用方）；不是写邮件页返回 null。 */
    private fun currentDraftHash(displayId: Int): String? = runCatching {
        val ext = com.android.phoneagent.perception.ElementExtractor.extract(com.android.phoneagent.perception.RawSnapshot.parse(ui.snapshot(displayId)))
        val st = com.android.phoneagent.perception.ScreenState(displayId, ext.packageName, ext.pageTitle, 1080, 2400, 0, ext.elements)
        com.android.phoneagent.perception.GmailDraft.read(st)?.let { com.android.phoneagent.safety.ConfirmToken.hash(it) }
    }.getOrNull()

    override fun armGmailSendHash(displayId: Int, hash: String): Boolean {
        if (currentDraftHash(displayId) != hash) { com.android.phoneagent.safety.SafetyGuard.sendGate.disarm(); return false }
        com.android.phoneagent.safety.SafetyGuard.draftHashProvider = { currentDraftHash(displayId) }
        com.android.phoneagent.safety.SafetyGuard.sendGate.arm(hash)
        return true
    }

    override fun disarmGmailSend() = com.android.phoneagent.safety.SafetyGuard.sendGate.disarm()

    override fun armCartChanges(spec: String) {
        val list = spec.lines().filter { it.isNotBlank() }.map { l ->
            val p = l.split('\t')
            com.android.phoneagent.safety.CartGate.Change(p.getOrElse(1) { "" }, p[0], p.getOrNull(2)?.toIntOrNull())
        }
        com.android.phoneagent.safety.SafetyGuard.cartGate.arm(list)
    }

    override fun disarmCartChanges() = com.android.phoneagent.safety.SafetyGuard.cartGate.disarm()

    override fun scroll(displayId: Int, forward: Boolean) = ui.scroll(displayId, forward)
    override fun globalBack() = ui.globalBack()

    override fun isMainImeShown() = mainScreen.isImeShown()
    override fun armGmailSend(displayId: Int, recipient: String): Boolean = ui.armGmailSend(displayId, recipient)
    override fun listTasks(displayId: Int): String = tasks.list(displayId)
    override fun moveTaskToDisplay(taskId: Int, displayId: Int) = tasks.moveToDisplay(taskId, displayId)

    override fun release() {
        ui.close()
        display.release()
    }
}
