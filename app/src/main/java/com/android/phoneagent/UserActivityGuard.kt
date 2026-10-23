package com.android.phoneagent

import android.util.Log
import java.util.concurrent.CancellationException

/**
 * 「用户打字时暂停 agent」的安全开关（默认关闭）。
 *
 * 虚拟屏用 VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED 创建后，虚拟屏上的动作不会再切走 top focused display，
 * 用户的键盘和输入目标已经不受影响（见 docs/ime-experiments.md）。这个守卫是额外一层保险：开启后，每个虚拟屏动作
 * 执行前如果主屏软键盘正在显示，就暂停，等键盘收起并持续 [quietMs] 后再继续。
 */
class UserActivityGuard(
    private val priv: () -> IPrivileged?,
    private val isCancelled: () -> Boolean,
    private val onWaiting: (Boolean) -> Unit,
    private val enabled: () -> Boolean,
    private val quietMs: Long = 2000,
    private val pollMs: Long = 300,
) {
    /** 执行一个虚拟屏动作。必须在后台线程调用。 */
    fun <T> guarded(what: String, action: () -> T): T {
        if (enabled()) awaitUserIdle(what)
        return action()
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

    private companion object {
        const val TAG = "PA"
    }
}
