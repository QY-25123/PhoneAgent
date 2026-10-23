package com.android.phoneagent.safety

import com.android.phoneagent.perception.Draft
import java.security.MessageDigest

/**
 * 确认令牌的内容哈希：用户在确认卡片上看到什么，哈希就算什么（收件人 + 主题 + 正文）。
 * 规范化：收件人小写、排序；主题压缩空白；正文统一换行、去掉每行行尾空格和首尾空行——排版噪声不改变哈希，内容变了一定变。
 */
object ConfirmToken {
    fun hash(d: Draft): String {
        val to = d.to.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().joinToString(",")
        val subject = d.subject.replace(Regex("\\s+"), " ").trim()
        val body = d.body.replace("\r\n", "\n").replace('\r', '\n').lines().joinToString("\n") { it.trimEnd() }.trim('\n', ' ')
        val canon = "to:$to\nsubject:$subject\nbody:$body"
        return MessageDigest.getInstance("SHA-256").digest(canon.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Gmail 发送的一次性放行闸门（PRD FR-SAFE-12、T2-R7）：用户确认后 [arm] 绑定内容哈希；
 * 点击发送的那一刻用页面**当前**内容的哈希调用 [check]：一致才放行，并且只放行一次；
 * 不一致（内容在确认之后变了）、没布防、已过期、读不到内容，一律拦截；任何一次校验都会让令牌作废。
 */
class SendGate(private val now: () -> Long = { System.currentTimeMillis() }, private val ttlMs: Long = 120_000) {
    private var armedHash: String? = null
    private var armedAt = 0L

    @Synchronized fun arm(hash: String) { armedHash = hash; armedAt = now() }
    @Synchronized fun disarm() { armedHash = null }
    @Synchronized fun isArmed() = armedHash != null && now() - armedAt <= ttlMs

    /** [consume] 为 false 时只看看会不会放行（例如滑动），不消耗令牌。 */
    @Synchronized fun check(currentHash: String?, consume: Boolean = true): Boolean {
        val ok = armedHash != null && now() - armedAt <= ttlMs && currentHash != null && currentHash == armedHash
        if (consume) armedHash = null
        return ok
    }
}
