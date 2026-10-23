package com.android.phoneagent.perception

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.ByteArrayOutputStream

/** 在截图上画元素边框和编号，缩放到目标宽度并压成 JPEG（给模型看的那张图）。 */
object ScreenshotAnnotator {
    // 边框颜色按元素类型区分，方便一眼看出哪些能输入、哪些能点
    private val BOX_COLORS = mapOf(
        "EditText" to 0xFFFF6D00.toInt(), "Button" to 0xFF2962FF.toInt(), "ImageButton" to 0xFF2962FF.toInt(),
        "Checkbox" to 0xFF00C853.toInt(), "Switch" to 0xFF00C853.toInt(), "Scrollable" to 0xFFAA00FF.toInt(),
    )
    private const val DEFAULT_COLOR = 0xFFD50000.toInt()

    class Result(val jpeg: ByteArray, val width: Int, val height: Int)

    /**
     * [elements] 的坐标是屏幕像素，屏幕宽 [screenW]（位图 [src] 可以是缩小过的，只要宽高比一样）；输出宽度 [outW]。
     */
    fun annotate(src: Bitmap, elements: List<UiElement>, screenW: Int, outW: Int = 720, quality: Int = 70): Result {
        val scale = outW.toFloat() / screenW
        val outH = (src.height * outW.toFloat() / src.width).toInt()
        val bmp = Bitmap.createScaledBitmap(src, outW, outH, true).copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        val chip = Paint().apply { style = Paint.Style.FILL }
        val text = Paint().apply { color = Color.WHITE; textSize = 20f; isFakeBoldText = true; isAntiAlias = true }
        val tb = Rect()
        val placed = ArrayList<RectF>()
        // 大元素先画，小元素后画，避免大框盖住小框的编号
        for (e in elements.sortedByDescending { it.bounds.area }) {
            val col = BOX_COLORS[e.type] ?: DEFAULT_COLOR
            val l = e.bounds.left * scale; val t = e.bounds.top * scale
            val r = e.bounds.right * scale; val b = e.bounds.bottom * scale
            box.color = col
            c.drawRect(l, t, r, b, box)
            val label = e.index.toString()
            text.getTextBounds(label, 0, label.length, tb)
            val cw = tb.width() + 10f; val ch = tb.height() + 8f
            // 编号小标签：依次尝试框的左上、右上、左下、右下（内侧）和左上外侧，选第一个不压住已有标签的位置，
            // 避免「行」和「行里的头像」两个框左上角重合时编号互相盖住
            val spots = listOf(
                l to t, (r - cw) to t, l to (b - ch), (r - cw) to (b - ch), l to (t - ch),
            )
            val (cx, cy) = spots.firstOrNull { (x, y) -> placed.none { it.intersects(x, y, x + cw, y + ch) } } ?: spots[0]
            placed.add(RectF(cx, cy, cx + cw, cy + ch))
            chip.color = col
            c.drawRect(cx, cy, cx + cw, cy + ch, chip)
            c.drawText(label, cx + 5f, cy + ch - 4f, text)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        return Result(out.toByteArray(), outW, outH)
    }

    /** 截图是否全黑（安全窗口）：缩成小图用最近邻采样。 */
    fun isMostlyBlack(src: Bitmap): Boolean {
        val w = 180
        val h = (src.height * (w.toFloat() / src.width)).toInt()
        val small = Bitmap.createScaledBitmap(src, w, h, false)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        return ScreenAnomaly.isMostlyBlack(px)
    }
}
