package com.android.phoneagent.perception

/** ScreenState → 给模型看的精简文本。每个元素一行：[编号|稳定标识] 类型 "文字" 操作；位置只在标注截图里。 */
object ScreenSerializer {
    fun toText(s: ScreenState): String {
        val sb = StringBuilder()
        sb.append("App: ").append(s.packageName ?: "未知")
        s.pageTitle?.let { sb.append("  标题: ").append(oneLine(it)) }
        sb.append("  屏幕: ").append(s.screenWidth).append('x').append(s.screenHeight)
        sb.append("  元素: ").append(s.elements.size).append('\n')
        val notes = buildList {
            addAll(s.notes)
        }
        if (notes.isNotEmpty()) sb.append("提示: ").append(notes.joinToString("；")).append('\n')
        for (e in s.elements) sb.append(line(e)).append('\n')
        return sb.toString()
    }

    fun line(e: UiElement): String {
        val sb = StringBuilder()
        sb.append('[').append(e.index).append('|').append(e.stableId).append("] ").append(e.type)
        val label = labelOf(e)
        if (label != null) sb.append(' ').append(label)
        else e.resourceId?.let { sb.append(" #").append(it.substringAfter('/')) }   // 没有文字的图标按钮，给个资源 id 线索
        if (e.password) sb.append(" [password]")
        if (e.actions.isNotEmpty()) sb.append(" [").append(e.actions.joinToString(",") { it.label }).append(']')
        e.checked?.let { sb.append(if (it) " checked" else " unchecked") }
        if (e.focused) sb.append(" focused")
        if (!e.enabled) sb.append(" disabled")
        return sb.toString()
    }

    /** 可输入框：`"提示" = "当前内容"`；其他：`"文字"`，描述与文字不同则附在括号里；都没有则用合并的子文字。 */
    private fun labelOf(e: UiElement): String? {
        if (e.actions.contains(ElementAction.EDIT)) {
            val name = e.hint ?: e.desc
            val value = e.text
            return when {
                name != null && value != null -> "${q(name)} = ${q(value)}"
                name != null -> q(name)
                value != null -> "= ${q(value)}"
                else -> null
            }
        }
        val main = e.text ?: e.desc ?: e.mergedText ?: return null
        val extra = if (e.text != null && e.desc != null && e.desc != e.text) " (${oneLine(e.desc)})" else ""
        return q(main) + extra
    }

    private fun q(s: String) = "\"" + oneLine(s).replace('"', '\'') + "\""
    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
