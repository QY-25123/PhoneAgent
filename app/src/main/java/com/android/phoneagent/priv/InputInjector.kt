package com.android.phoneagent.priv

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/** 通过 InputManager.injectInputEvent 注入事件，并用 InputEvent.setDisplayId 定向到指定屏幕。 */
class InputInjector {
    private val manager = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
    private val inject: Method = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
    private val setDisplayId: Method = InputEvent::class.java.getMethod("setDisplayId", Int::class.java)

    private fun send(event: InputEvent, displayId: Int) {
        setDisplayId.invoke(event, displayId)
        val ok = inject.invoke(manager, event, INJECT_ASYNC) as Boolean
        if (!ok) error("injectInputEvent 返回 false")
    }

    private fun touch(displayId: Int, action: Int, downTime: Long, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 1f, 1f, 0, 1f, 1f, 0, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            send(e, displayId)
        } finally {
            e.recycle()
        }
    }

    fun tap(displayId: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        touch(displayId, MotionEvent.ACTION_DOWN, t, x, y)
        Thread.sleep(50)
        touch(displayId, MotionEvent.ACTION_UP, t, x, y)
    }

    fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val t = SystemClock.uptimeMillis()
        val steps = 15
        touch(displayId, MotionEvent.ACTION_DOWN, t, x1, y1)
        for (i in 1..steps) {
            Thread.sleep(durationMs / steps)
            val f = i / steps.toFloat()
            touch(displayId, MotionEvent.ACTION_MOVE, t, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f)
        }
        touch(displayId, MotionEvent.ACTION_UP, t, x2, y2)
    }

    fun key(displayId: Int, keyCode: Int) {
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val now = SystemClock.uptimeMillis()
            send(
                KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
                displayId,
            )
        }
    }

    private companion object {
        const val INJECT_ASYNC = 0
    }
}
