package com.android.phoneagent.task

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.android.phoneagent.FloatAction
import com.android.phoneagent.FloatingWindow
import com.android.phoneagent.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 用户离开 App 时的悬浮小窗（PRD 7.4）：随任务事件切换状态——
 * 执行中 / 需要用户回答 / 等待确认 / 需要交接 / 完成 / 失败。点「需要回答 / 等待确认 / 需要交接」打开 App 并定位到对应卡片。
 * App 在前台时不显示（对话里已经有卡片）。需要「显示在其他应用上层」权限，没有就不显示。
 */
class FloatingController private constructor(context: Context, private val backend: TaskBackend) {
    private val ctx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val window = FloatingWindow(ctx) { open(focus) }

    private class State(val heading: String, val text: String, val actions: List<FloatAction>)

    private var state: State? = null
    private var focus: String? = null          // 点小窗要定位到的卡片
    private var dismissed = false
    private var foreground = false
    private var stepText = ""
    private var lastThumb: android.graphics.Bitmap? = null

    init {
        scope.launch { backend.events.collect { handle(it) } }
    }

    fun setForeground(fg: Boolean) { foreground = fg; render() }

    private fun open(target: String?) {
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (target != null) putExtra("focus", target) },
        )
    }

    private fun stop() = FloatAction("停止") { backend.stop() }
    private fun close() = FloatAction("关闭") { dismissed = true; render() }
    private fun go(label: String, target: String) = FloatAction(label, primary = true) { open(target) }

    private fun running(text: String) { state = State("执行中", text, listOf(stop())); focus = null }

    private fun handle(e: TaskEvent) {
        when (e) {
            is TaskEvent.UserSaid -> { dismissed = false }
            is TaskEvent.Started -> { dismissed = false; lastThumb = null; running(e.title) }
            is TaskEvent.Progress -> { e.thumb?.let { lastThumb = it }; stepText = "正在执行任务"; running(stepText) }
            is TaskEvent.Ask -> {
                focus = "question"
                state = State("需要回答", e.text, e.options.take(2).map { o -> FloatAction(o) { backend.submit(o) } } + go("去回答", "question"))
            }
            is TaskEvent.Confirm -> {
                focus = "confirm"
                state = State("等待确认", if (e.kind == "cart") "确认修改购物车" else "待发送：${e.subject}", listOf(go("去确认", "confirm"), stop()))
            }
            is TaskEvent.ConfirmResolved -> running(stepText.ifEmpty { "继续中" })
            is TaskEvent.Handoff -> { focus = "handoff"; state = State("需要交接", "已到提交订单页", listOf(go("去处理", "handoff"), close())) }
            is TaskEvent.HandoffDone -> { state = null; focus = null }
            is TaskEvent.Succeeded -> { focus = null; state = State("完成", e.title, listOf(close())) }
            is TaskEvent.Failed -> { focus = null; state = State("失败", e.reason.take(60), listOf(close())) }
            is TaskEvent.Stopped -> state = null
            is TaskEvent.AgentSaid -> if (state == null || state?.heading == "完成") Unit
            is TaskEvent.Frame -> { lastThumb = e.thumb; if (state?.heading == "执行中") window.showLive(e.thumb, { backend.stop() }, { dismissed = true; render() }, { open(null) }); return }
        }
        render()
    }

    private fun render() {
        val s = state
        if (s == null || dismissed || !Settings.canDrawOverlays(ctx)) { window.hide(); window.hideLive(); return }
        if (s.heading == "执行中") {
            // 任务在执行：不管 App 在不在前台，都显示「实时画面 + 结束任务」小窗
            window.hide()
            window.showLive(lastThumb, { backend.stop() }, { dismissed = true; render() }, { open(null) })
            return
        }
        window.hideLive()
        if (foreground) window.hide() else window.show(s.heading, s.text, s.actions)
    }

    companion object {
        @Volatile private var instance: FloatingController? = null

        /** 进程内只建一个；MainActivity 创建时调用。 */
        fun install(context: Context): FloatingController = instance ?: synchronized(this) {
            instance ?: FloatingController(context, TaskBackends.get(context)).also { instance = it }
        }
    }
}
