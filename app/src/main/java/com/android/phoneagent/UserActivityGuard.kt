package com.android.phoneagent

import android.util.Log
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 保证 agent 不干扰用户在主屏上的输入：
 * - 动作前：主屏软键盘正在显示（用户在打字）就暂停，等键盘收起并持续 [quietMs] 后再继续；
 * - 动作后：用户没在打字就把 top focused display 切回主屏。
 * 依据：虚拟屏上任何 Activity 启动/获得焦点都会切走 top focused display，从而收起或劫持用户的键盘（见 docs/ime-experiments.md）。
 */
class UserActivityGuard(
    private val priv: () -> IPrivileged?,
    private val isCancelled: () -> Boolean,
    private val onWaiting: (Boolean) -> Unit,
    private val quietMs: Long = 2000,
    private val pollMs: Long = 300,
    private val settleDelaysMs: List<Long> = listOf(500, 1500),
) {
    private val settle = Executors.newSingleThreadScheduledExecutor()

    /** 在守卫保护下执行一个虚拟屏动作。必须在后台线程调用。 */
    fun <T> guarded(what: String, action: () -> T): T {
        awaitUserIdle(what)
        try {
            return action()
        } finally {
            restoreFocusIfIdle(what)
        }
    }

    private fun imeShown() = priv()?.isMainImeShown() ?: false

    private fun awaitUserIdle(what: String) {
        if (!imeShown()) return
        Log.i(TAG, "GUARD 用户正在输入，暂停：$what")
        onWaiting(true)
        val start = System.currentTimeMillis()
        var quietSince = 0L
        try {
            while (true) {
                if (isCancelled()) throw CancellationException("已停止")
                val now = System.currentTimeMillis()
                if (imeShown()) {
                    quietSince = 0
                } else {
                    if (quietSince == 0L) {
                        quietSince = now
                        Log.i(TAG, "GUARD 键盘已收起，开始计时 ${quietMs}ms：$what")
                    }
                    if (now - quietSince >= quietMs) break
                }
                Thread.sleep(pollMs)
            }
        } finally {
            onWaiting(false)
        }
        Log.i(TAG, "GUARD 恢复执行：$what（共等待 ${System.currentTimeMillis() - start}ms）")
    }

    /**
     * 动作返回时页面切换往往还没发生（点击后新 Activity 要过几百毫秒才 resume 并抢走焦点），所以除了立即切回，
     * 还在 [settleDelaysMs] 之后各补切一次。每次切之前都重新确认用户没在打字。
     */
    private fun restoreFocusIfIdle(what: String) {
        restoreOnce(what, "立即")
        for (delay in settleDelaysMs) settle.schedule({ restoreOnce(what, "${delay}ms 后") }, delay, TimeUnit.MILLISECONDS)
    }

    private fun restoreOnce(what: String, when_: String) {
        try {
            val p = priv() ?: return
            if (p.isMainImeShown()) {
                Log.i(TAG, "GUARD 用户正在输入，不切焦点（$when_）：$what")
                return
            }
            p.restoreMainFocus()
            Log.i(TAG, "GUARD 已把焦点切回主屏（$when_）：$what")
        } catch (t: Throwable) {
            Log.w(TAG, "GUARD 切回主屏失败", t)
        }
    }

    private companion object {
        const val TAG = "PA"
    }
}
