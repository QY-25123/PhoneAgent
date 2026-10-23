package com.android.phoneagent.priv

import android.app.UiAutomation
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 在 shell 进程里连接 UiAutomation，用于读取指定屏幕的界面结构、向输入框写文字。
 * 连接时带 FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES，不会关掉用户已启用的无障碍服务。
 * 可复用：后续读界面树用 [windowRoots] / [dump]。
 */
class UiAutomationHelper {
    private var thread: HandlerThread? = null
    private var automation: UiAutomation? = null

    @Synchronized
    fun connect(): UiAutomation {
        automation?.let { return it }
        // 回调要在另一个线程的 looper 上跑，否则 connect() 会自己等自己
        val t = HandlerThread("uiautomation").also { it.start() }
        try {
            val connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor()
                .apply { isAccessible = true }.newInstance()
            val ua = UiAutomation::class.java
                .getDeclaredConstructor(Looper::class.java, Class.forName("android.app.IUiAutomationConnection"))
                .apply { isAccessible = true }.newInstance(t.looper, connection)
            UiAutomation::class.java.getDeclaredMethod("connect", Int::class.java)
                .apply { isAccessible = true }.invoke(ua, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            // 需要 INTERACTIVE_WINDOWS 才能取到非默认屏幕上的窗口
            ua.serviceInfo = ua.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            thread = t
            automation = ua
            return ua
        } catch (e: Throwable) {
            t.quitSafely()
            throw e
        }
    }

    @Synchronized
    fun close() {
        automation?.let { ua ->
            runCatching { UiAutomation::class.java.getDeclaredMethod("disconnect").apply { isAccessible = true }.invoke(ua) }
        }
        automation = null
        thread?.quitSafely()
        thread = null
    }

    /** 指定屏幕上所有窗口的根节点（调用方负责不长期持有）。 */
    fun windowRoots(displayId: Int): List<AccessibilityNodeInfo> {
        val windows = connect().windowsOnAllDisplays[displayId] ?: return emptyList()
        return windows.mapNotNull { it.root }
    }

    /** 向指定屏幕上的输入框写文字（ACTION_SET_TEXT，不经过输入法）：优先聚焦的，否则第一个可编辑节点。 */
    fun setTextOnFocused(displayId: Int, text: String): Boolean {
        for (root in windowRoots(displayId)) {
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirst(root) { it.isEditable && it.isFocused }
                ?: findFirst(root) { it.isEditable }
                ?: continue
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    /** 对坐标处最深的可点击节点执行 ACTION_CLICK（语义点击，不产生触摸事件）。 */
    fun clickAt(displayId: Int, x: Int, y: Int): Boolean {
        for (root in windowRoots(displayId).asReversed()) {
            val node = deepestAt(root, x, y) { it.isClickable } ?: continue
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    fun scroll(displayId: Int, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        for (root in windowRoots(displayId)) {
            val node = findFirst(root) { it.isScrollable } ?: continue
            return node.performAction(action)
        }
        return false
    }

    fun globalBack(): Boolean = connect().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

    private fun deepestAt(n: AccessibilityNodeInfo, x: Int, y: Int, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
        if (!r.contains(x, y) || !n.isVisibleToUser) return null
        for (i in n.childCount - 1 downTo 0) {
            n.getChild(i)?.let { c -> deepestAt(c, x, y, pred)?.let { return it } }
        }
        return if (pred(n)) n else null
    }

    /** 找文案/描述包含 [needle] 的可见节点，返回它或最近的可点击祖先：区域和标签。 */
    fun findClickableByText(displayId: Int, needle: String): Pair<AccessibilityNodeInfo, String>? {
        for (root in windowRoots(displayId).asReversed()) {
            val n = findFirst(root) {
                it.isVisibleToUser && ("${it.text ?: ""} ${it.contentDescription ?: ""}").contains(needle, ignoreCase = true)
            } ?: continue
            var p: AccessibilityNodeInfo? = n
            var i = 0
            while (p != null && !p.isClickable && i++ < 6) p = p.parent
            return (if (p != null && p.isClickable) p else n) to "${n.text ?: ""} ${n.contentDescription ?: ""}".trim()
        }
        return null
    }

    /** 硬拦截检查：页面级锁定 + 控件级区域。返回拦截原因，null 表示放行。 */
    fun blockReason(displayId: Int, x: Int?, y: Int?): String? {
        val roots = windowRoots(displayId)
        SafetyGuard.pageLock(roots)?.let { return "页面级锁定：当前页面含「$it」，不允许点击或输入" }
        if (x != null && y != null) SafetyGuard.blockedAt(roots, x, y)?.let { return it }
        return null
    }

    /** 界面树的文本形式：每行一个节点。 */
    fun dump(displayId: Int): String {
        val sb = StringBuilder()
        for (w in connect().windowsOnAllDisplays[displayId].orEmpty()) {
            sb.append("WINDOW display=").append(w.displayId).append(" id=").append(w.id).append(" type=").append(w.type)
                .append(" title=").append(w.title).append(" focused=").append(w.isFocused).append(" active=").append(w.isActive).append('\n')
            w.root?.let { dumpNode(it, 1, sb) }
        }
        return sb.toString()
    }

    private fun dumpNode(n: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
        sb.append("  ".repeat(depth)).append(n.className).append(" [").append(r.flattenToString()).append(']')
        n.viewIdResourceName?.let { sb.append(" id=").append(it) }
        n.text?.let { sb.append(" text=\"").append(it).append('"') }
        n.contentDescription?.let { sb.append(" desc=\"").append(it).append('"') }
        if (n.isClickable) sb.append(" clickable")
        if (n.isEditable) sb.append(" editable")
        if (n.isFocused) sb.append(" focused")
        sb.append('\n')
        for (i in 0 until n.childCount) n.getChild(i)?.let { dumpNode(it, depth + 1, sb) }
    }

    private fun findFirst(n: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(n)) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> findFirst(c, pred)?.let { return it } }
        return null
    }
}
