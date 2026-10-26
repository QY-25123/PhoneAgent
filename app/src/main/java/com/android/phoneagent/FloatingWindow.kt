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
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** 小窗上的一个按钮。 */
class FloatAction(val label: String, val primary: Boolean = false, val onClick: () -> Unit)

/**
 * 悬浮小窗：只显示文字和按钮，不获取输入焦点（FLAG_NOT_FOCUSABLE），可拖动，松手吸附到左右边缘。
 * 点文字区域 [onOpen]（打开 App 并定位到对应卡片），按钮由 [show] 传入（PRD 7.4 的各状态不同）。
 */
class FloatingWindow(context: Context, private val onOpen: () -> Unit) {
    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var body: TextView? = null
    private var buttons: LinearLayout? = null
    private val dp = ctx.resources.displayMetrics.density

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

    private fun px(v: Int) = (v * dp).toInt()

    /** 显示或更新：[heading] 是状态名（执行中、等待确认……），[text] 是一句话，[actions] 是按钮。 */
    fun show(heading: String, text: String, actions: List<FloatAction>) = main.post {
        if (root == null) {
            val h = TextView(ctx).apply { setTextColor(0xFFB0BEFF.toInt()); textSize = 11f }
            val t = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 14f; maxLines = 2; maxWidth = px(220) }
            val textCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(h); addView(t) }
            val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(14), px(8), px(6), px(8))
                background = GradientDrawable().apply { setColor(0xF0202A6B.toInt()); cornerRadius = px(22).toFloat() }
                addView(textCol); addView(btns)
                setOnTouchListener(dragListener())
            }
            title = h; body = t; buttons = btns; root = r
            wm.addView(r, lp)
        }
        title?.text = heading
        body?.text = text
        buttons?.removeAllViews()
        for (a in actions) {
            buttons?.addView(TextView(ctx).apply {
                this.text = a.label
                setTextColor(if (a.primary) 0xFFFFFFFF.toInt() else 0xFFC5CBFF.toInt())
                textSize = 14f
                setPadding(px(12), px(8), px(12), px(8))
                if (a.primary) background = GradientDrawable().apply { setColor(0xFF3D5AFE.toInt()); cornerRadius = px(16).toFloat() }
                setOnClickListener { Log.i("PA", "OVERLAY_ACTION ${a.label}"); a.onClick() }
            })
        }
    }

    // ---------------- 执行中的「实时画面」小窗：虚拟屏画面 + 「结束任务」 ----------------
    private var liveRoot: LinearLayout? = null
    private var liveImage: ImageView? = null
    private val liveLp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.END; x = px(12); y = px(96); title = "PhoneAgent Live" }

    /** 显示或更新执行中的实时画面小窗：[thumb] 是虚拟屏当前画面（可为 null），[onStop] 结束任务，[onClose] 只收起小窗（任务继续），点标题或画面 [onOpen] 打开 App。 */
    fun showLive(thumb: Bitmap?, onStop: () -> Unit, onClose: () -> Unit, onOpen: () -> Unit = {}) = main.post {
        if (liveRoot == null) {
            val title = TextView(ctx).apply { text = "实时画面"; setTextColor(0xFF2B3A4A.toInt()); textSize = 14f; setOnClickListener { Log.i("PA", "OVERLAY_CLICK"); onOpen() }; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val close = TextView(ctx).apply {
                text = "×"; setTextColor(0xFF6B7C8F.toInt()); textSize = 20f; gravity = Gravity.CENTER
                setPadding(px(10), 0, px(2), 0)
                setOnClickListener { Log.i("PA", "OVERLAY_ACTION 关闭实时画面"); onClose() }
            }
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(title); addView(close) }
            val img = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setOnClickListener { Log.i("PA", "OVERLAY_CLICK"); onOpen() }
                setBackgroundColor(0xFF22303F.toInt())
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, px(10).toFloat())
                }
            }
            val stop = TextView(ctx).apply {
                text = "结束任务"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
                setPadding(0, px(10), 0, px(10))
                background = GradientDrawable().apply { setColor(0xFF6FA8DC.toInt()); cornerRadius = px(20).toFloat() }
                setOnClickListener { Log.i("PA", "OVERLAY_ACTION 结束任务"); onStop() }
            }
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(10), px(8), px(10), px(10))
                background = GradientDrawable().apply { setColor(0xF2F2F7FC.toInt()); cornerRadius = px(18).toFloat(); setStroke(px(1), 0x33000000) }
                elevation = px(8).toFloat()
                addView(head, LinearLayout.LayoutParams(px(168), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(img, LinearLayout.LayoutParams(px(168), px(300)).apply { topMargin = px(6); bottomMargin = px(8) })
                addView(stop, LinearLayout.LayoutParams(px(168), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            liveImage = img; liveRoot = r
            wm.addView(r, liveLp)
        }
        thumb?.let { liveImage?.setImageBitmap(it) }
    }

    fun hideLive() = main.post {
        liveRoot?.let { runCatching { wm.removeView(it) } }
        liveRoot = null; liveImage = null
    }

    fun hide() = main.post {
        root?.let { runCatching { wm.removeView(it) } }
        root = null; title = null; body = null; buttons = null
    }

    val isShowing get() = root != null || liveRoot != null

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
                    }
                }
            }
            return true
        }
    }
}
