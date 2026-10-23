package com.android.phoneagent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮小窗：只显示文字和按钮，不获取输入焦点（FLAG_NOT_FOCUSABLE），可拖动，松手吸附到左右边缘。
 * 原型版（UV-4 验证用），状态机和样式后续再做。
 */
class FloatingWindow(context: Context, private val onStop: () -> Unit, private val onOpen: () -> Unit) {
    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var label: TextView? = null

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 400
        title = "PhoneAgent Overlay"
    }

    fun show(text: String) = main.post {
        if (root == null) {
            val dp = ctx.resources.displayMetrics.density
            val tv = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding((12 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt()) }
            val stop = TextView(ctx).apply {
                this.text = "停止"; setTextColor(Color.WHITE); textSize = 14f
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                setOnClickListener { Log.i("PA", "OVERLAY_STOP"); onStop() }
            }
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { setColor(0xE6303F9F.toInt()); cornerRadius = 20 * dp }
                addView(tv); addView(stop)
                setOnTouchListener(dragListener())
            }
            label = tv; root = r
            wm.addView(r, lp)
        }
        label?.text = text
    }

    fun update(text: String) = main.post { label?.text = text }

    fun hide() = main.post {
        root?.let { runCatching { wm.removeView(it) } }
        root = null; label = null
    }

    private fun dragListener() = object : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var moved = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (Math.abs(dx) > 16 || Math.abs(dy) > 16)) moved = true
                    if (moved) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { Log.i("PA", "OVERLAY_CLICK"); onOpen() }
                    else {
                        val w = ctx.resources.displayMetrics.widthPixels
                        lp.x = if (e.rawX < w / 2f) 0 else w - v.width   // 吸附到最近的左右边缘
                        wm.updateViewLayout(v, lp)
                        Log.i("PA", "OVERLAY_SNAP x=${lp.x} y=${lp.y}")
                    }
                }
            }
            return true
        }
    }
}
