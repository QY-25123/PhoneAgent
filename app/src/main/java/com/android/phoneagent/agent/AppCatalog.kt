package com.android.phoneagent.agent

/** open_app 允许打开的 App（只有三类任务用到的）。其他一律拒绝（PRD FR-SAFE-9：不跟随任务范围外的应用）。 */
object AppCatalog {
    const val CALENDAR = "com.google.android.calendar"
    const val GMAIL = "com.google.android.gm"
    const val AMAZON = "com.amazon.mShop.android.shopping"

    private val byName = mapOf(
        "calendar" to CALENDAR, "google calendar" to CALENDAR, "日历" to CALENDAR, "谷歌日历" to CALENDAR,
        "gmail" to GMAIL, "mail" to GMAIL, "邮件" to GMAIL,
        "amazon" to AMAZON, "亚马逊" to AMAZON,
    )

    /** 名字或包名 → 包名；不在允许范围内返回 null。 */
    fun resolve(app: String): String? {
        val k = app.trim().lowercase()
        return byName[k] ?: k.takeIf { it in setOf(CALENDAR, GMAIL, AMAZON) }
    }
}
