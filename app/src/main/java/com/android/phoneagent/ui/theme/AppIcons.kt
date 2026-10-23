package com.android.phoneagent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 描边线性图标，24dp 画布，1.75 描边，圆头圆角（docs/DESIGN.md 第 8 节）。着色由使用处的 tint 决定。 */
object AppIcons {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.75f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val menu = icon("menu", "M4 7h16", "M4 12h16", "M4 17h16")
    val plus = icon("plus", "M12 5v14", "M5 12h14")
    val mic = icon("mic", "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z", "M6 11a6 6 0 0 0 12 0", "M12 17v4")
    val send = icon("send", "M12 19V5", "M6 11l6-6 6 6")
    val stop = icon("stop", "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z")
    val pause = icon("pause", "M9 6v12", "M15 6v12")
    val play = icon("play", "M8 5l11 7-11 7z")
    val check = icon("check", "M5 12.5l4.5 4.5L19 7.5")
    val alert = icon("alert", "M12 4l9 16H3z", "M12 10v4", "M12 17h.01")
    val handoff = icon("handoff", "M14 4h6v6", "M20 4l-9 9", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
    val mail = icon("mail", "M4 6h16v12H4z", "M4 7l8 6 8-6")
    val calendar = icon("calendar", "M5 6h14v14H5z", "M5 10h14", "M9 3v4", "M15 3v4")
    val cart = icon("cart", "M3 4h2l2.4 11h10l2-8H7", "M9 20h.01", "M17 20h.01")
    val bell = icon("bell", "M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 21h4")
    val settings = icon(
        "settings", "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z", "M12 3v2", "M12 19v2", "M3 12h2", "M19 12h2",
        "M5.6 5.6l1.4 1.4", "M17 17l1.4 1.4", "M18.4 5.6L17 7", "M7 17l-1.4 1.4",
    )
    val chat = icon("chat", "M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-7l-5 4v-4H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z")
    val records = icon("records", "M6 4h12a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M9 9h6", "M9 13h6", "M9 17h3")
    val close = icon("close", "M6 6l12 12", "M18 6L6 18")
    val chevronRight = icon("chevronRight", "M9 6l6 6-6 6")
    val chevronLeft = icon("chevronLeft", "M15 6l-6 6 6 6")
    val chevronDown = icon("chevronDown", "M6 9l6 6 6-6")
    val chevronUp = icon("chevronUp", "M6 15l6-6 6 6")
    val screen = icon("screen", "M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M11 18h2")
    val shield = icon("shield", "M12 3l8 3v6c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V6z")
    val edit = icon("edit", "M4 20h4L19 9l-4-4L4 16z", "M13 7l4 4")
    val layers = icon("layers", "M12 4l9 5-9 5-9-5z", "M3 14l9 5 9-5")
}
