package com.android.phoneagent.agent

import java.net.URLEncoder

/** Gmail 写邮件的 mailto 意图参数（交给特权侧 `am start --display N` 启动）。主题和正文放在 URI 的查询参数里（Gmail 对 EXTRA_* 不稳定）。 */
object MailIntent {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun uri(to: String, subject: String, body: String): String =
        "mailto:" + to.trim() + "?subject=" + enc(subject) + "&body=" + enc(body)

    fun args(to: String, subject: String, body: String): Array<String> =
        arrayOf("-a", "android.intent.action.SENDTO", "-p", AppCatalog.GMAIL, "-d", uri(to, subject, body))
}
