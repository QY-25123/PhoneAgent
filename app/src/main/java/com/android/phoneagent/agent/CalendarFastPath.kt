package com.android.phoneagent.agent

import com.android.phoneagent.perception.ElementAction
import com.android.phoneagent.perception.ScreenState
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 日历快速通道：「插入事项」意图已经把标题和起止时间预填好，如果页面上的标题、日期、开始和结束时间都和已确认信息
 * 一致（代码本地核对，不调用模型），就直接点「保存」，省掉两次模型调用。任何一项对不上都返回 null，交给模型处理。
 * 只认英文界面的日期格式（Oct 28）和 12/24 小时制时间；其他语言一律不走快速通道。
 */
object CalendarFastPath {
    private val H12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val H24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val MON = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** 「保存」按钮的元素编号；不满足条件返回 null。 */
    fun saveIndex(task: CalendarTask, s: ScreenState): Int? {
        val els = s.elements
        if (s.packageName != AppCatalog.CALENDAR) return null
        els.firstOrNull { ElementAction.EDIT in it.actions && it.text?.trim() == task.title } ?: return null
        val save = els.firstOrNull { e ->
            ElementAction.CLICK in e.actions && (e.text ?: e.desc ?: e.mergedText)?.trim()?.let { it == "Save" || it == "保存" } == true
        } ?: return null
        val blob = els.joinToString("\n") { listOfNotNull(it.text, it.desc, it.mergedText).joinToString(" ") }
        fun hasTime(t: LocalTime) = blob.contains(t.format(H12)) || blob.contains(t.format(H24))
        if (!hasTime(task.start) || !hasTime(task.end)) return null
        if (!blob.contains(task.date.format(MON))) return null
        return save.index
    }
}
