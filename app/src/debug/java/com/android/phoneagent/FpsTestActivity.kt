package com.android.phoneagent

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View

/** debug：持续动画（每帧都变化）的测试页面，用来在虚拟屏上制造稳定的高帧率画面源，测量画面传输能力（UV-1）。 */
class FpsTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(object : View(this as Context) {
            private val p = Paint().apply { textSize = 90f }
            private var n = 0
            override fun onDraw(c: Canvas) {
                c.drawColor(Color.WHITE)
                for (i in 0 until 12) {
                    p.color = Color.HSVToColor(floatArrayOf(((n * 3 + i * 30) % 360).toFloat(), 0.8f, 0.9f))
                    c.drawRect(((n * 17 + i * 90) % width).toFloat(), i * 190f, ((n * 17 + i * 90) % width) + 300f, i * 190f + 150f, p)
                }
                p.color = Color.BLACK
                c.drawText("frame $n", 60f, 2250f, p)
                n++
                postInvalidateOnAnimation()
            }
        })
    }
}
