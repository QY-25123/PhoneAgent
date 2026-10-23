package com.android.phoneagent.safety

import com.android.phoneagent.perception.Draft
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SendGateTest {
    private var t = 0L
    private fun gate() = SendGate(now = { t }, ttlMs = 1000)

    @Test fun unarmedGateAlwaysBlocks() {
        val g = gate()
        assertThat(g.check("abc")).isFalse()
        assertThat(g.check(null)).isFalse()
        assertThat(g.isArmed()).isFalse()
    }

    @Test fun matchingHashIsAllowedExactlyOnce() {
        val g = gate(); g.arm("h1")
        assertThat(g.check("h1")).isTrue()
        assertThat(g.check("h1")).isFalse()      // 一次性
        assertThat(g.isArmed()).isFalse()
    }

    @Test fun changedContentIsBlockedAndTheTokenIsVoided() {
        val g = gate(); g.arm("h1")
        assertThat(g.check("h2")).isFalse()
        assertThat(g.check("h1")).isFalse()      // 令牌已作废，要重新确认
    }

    @Test fun unreadableDraftIsBlocked() {
        val g = gate(); g.arm("h1")
        assertThat(g.check(null)).isFalse()
        assertThat(g.isArmed()).isFalse()
    }

    @Test fun expiredTokenIsBlocked() {
        val g = gate(); g.arm("h1")
        t = 1001
        assertThat(g.isArmed()).isFalse()
        assertThat(g.check("h1")).isFalse()
    }

    @Test fun peekingDoesNotConsume() {
        val g = gate(); g.arm("h1")
        assertThat(g.check("h1", consume = false)).isTrue()
        assertThat(g.check("h1", consume = false)).isTrue()
        assertThat(g.check("h1")).isTrue()
        assertThat(g.check("h1")).isFalse()
    }

    @Test fun rearmingReplacesThePreviousToken() {
        val g = gate(); g.arm("old"); g.arm("new")
        assertThat(g.check("old")).isFalse()
        g.arm("new")
        assertThat(g.check("new")).isTrue()
    }

    @Test fun disarmVoidsTheToken() {
        val g = gate(); g.arm("h1"); g.disarm()
        assertThat(g.check("h1")).isFalse()
    }

    // ---- 内容哈希 ----
    private val d = Draft(listOf("Qijie_Jerry@163.com"), "[PA测试] 周五评审", "你好，\n周五评审改到下午3点。\n谢谢")

    @Test fun hashIsStableAndIgnoresLayoutNoise() {
        val h = ConfirmToken.hash(d)
        assertThat(h).hasLength(64)
        assertThat(ConfirmToken.hash(d.copy(to = listOf(" qijie_jerry@163.com ")))).isEqualTo(h)           // 大小写、空格
        assertThat(ConfirmToken.hash(d.copy(subject = "[PA测试]   周五评审 "))).isEqualTo(h)               // 主题空白
        assertThat(ConfirmToken.hash(d.copy(body = "你好，  \r\n周五评审改到下午3点。\r\n谢谢\n\n"))).isEqualTo(h)   // 换行和行尾空格
    }

    @Test fun anyRealChangeChangesTheHash() {
        val h = ConfirmToken.hash(d)
        assertThat(ConfirmToken.hash(d.copy(to = listOf("other@163.com")))).isNotEqualTo(h)
        assertThat(ConfirmToken.hash(d.copy(to = d.to + "x@y.com"))).isNotEqualTo(h)
        assertThat(ConfirmToken.hash(d.copy(subject = "[PA测试] 周五评审（改期）"))).isNotEqualTo(h)
        assertThat(ConfirmToken.hash(d.copy(body = d.body + "！"))).isNotEqualTo(h)
        assertThat(ConfirmToken.hash(d.copy(body = d.body.replace("3点", "4点")))).isNotEqualTo(h)
    }

    @Test fun recipientOrderDoesNotMatter() {
        val a = Draft(listOf("a@x.com", "b@x.com"), "s", "b")
        assertThat(ConfirmToken.hash(a)).isEqualTo(ConfirmToken.hash(a.copy(to = listOf("b@x.com", "a@x.com"))))
    }

    @Test fun fieldsCannotBeShiftedIntoEachOther() {
        // 主题和正文互换内容，哈希必须不同
        assertThat(ConfirmToken.hash(Draft(listOf("a@x.com"), "A", "B"))).isNotEqualTo(ConfirmToken.hash(Draft(listOf("a@x.com"), "B", "A")))
    }
}
