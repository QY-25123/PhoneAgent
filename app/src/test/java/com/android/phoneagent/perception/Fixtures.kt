package com.android.phoneagent.perception

/** 合成快照的小工具：只写测试关心的字段。 */
fun node(
    depth: Int, cls: String = "android.widget.TextView", b: List<Int>, res: String? = null, text: String? = null, desc: String? = null,
    hint: String? = null, vis: Boolean = true, clk: Boolean = false, edit: Boolean = false, scr: Boolean = false,
    foc: Boolean = false, sh: Boolean = false, chkable: Boolean = false, chk: Boolean = false, sf: Boolean = false, sb: Boolean = false,
) = RawNode(
    depth, cls, Bounds(b[0], b[1], b[2], b[3]), res, text, desc, hint, vis, clickable = clk, editable = edit, scrollable = scr,
    focused = foc, showingHint = sh, checkable = chkable, checked = chk, scrollForward = sf, scrollBackward = sb,
)

fun window(
    nodes: List<RawNode>, layer: Int = 1, type: Int = 1, pkg: String? = "com.example", title: String? = null,
    b: List<Int> = listOf(0, 0, 1080, 2400),
) = RawWindow(1000 + layer, type, layer, title, true, true, Bounds(b[0], b[1], b[2], b[3]), pkg, nodes)

fun snap(vararg ws: RawWindow, truncated: Boolean = false) = RawSnapshot(2, 0L, truncated, ws.toList())

val FULL = listOf(0, 0, 1080, 2400)
