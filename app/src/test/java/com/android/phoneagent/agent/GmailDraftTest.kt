package com.android.phoneagent.agent

import com.android.phoneagent.perception.Draft
import com.android.phoneagent.perception.GmailDraft
import com.android.phoneagent.safety.ConfirmToken
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GmailDraftTest {
    @Test fun readsRecipientSubjectAndBodyFromTheComposePage() {
        val d = GmailDraft.read(gmailScreen().state)!!
        assertThat(d.to).containsExactly("qijie_jerry@163.com")
        assertThat(d.subject).isEqualTo("[PA测试] 周五评审")
        assertThat(d.body).isEqualTo("你好，周五评审改到下午3点。谢谢")
    }

    @Test fun senderAccountInTheFromRowIsNotARecipient() {
        val d = GmailDraft.read(gmailScreen().state)!!
        assertThat(d.to).doesNotContain("test.user@example.com")
    }

    @Test fun placeholderBodyAndHintSubjectCountAsEmpty() {
        val d = GmailDraft.read(gmailScreen(subject = "", body = "").state)!!
        assertThat(d.subject).isEmpty()
        assertThat(d.body).isEmpty()
    }

    @Test fun twoRecipientsAreBothRead() {
        val s = gmailScreen().state
        val extra = s.elements.first { it.index == 6 }.copy(index = 9, text = "b@x.com", desc = "b@x.com, b@x.com")
        val d = GmailDraft.read(s.let { com.android.phoneagent.perception.ScreenState(it.displayId, it.packageName, it.pageTitle, 1080, 2400, 0, it.elements + extra) })!!
        assertThat(d.to).containsExactly("qijie_jerry@163.com", "b@x.com")
    }

    @Test fun findsTheSendButtonOnlyOnTheComposePage() {
        val s = gmailScreen().state
        assertThat(GmailDraft.sendIndex(s)).isEqualTo(2)
        assertThat(GmailDraft.isCompose(s)).isTrue()
        assertThat(GmailDraft.isCompose(inboxScreen("Compose").state)).isFalse()
        assertThat(GmailDraft.read(inboxScreen("Compose").state)).isNull()
    }

    @Test fun nonGmailPagesAreNotDrafts() {
        assertThat(GmailDraft.read(gmailScreen(pkg = "com.google.android.calendar").state)).isNull()
        assertThat(GmailDraft.isCompose(gmailScreen(pkg = "com.google.android.calendar").state)).isFalse()
    }

    @Test fun editingTheBodyChangesTheHashAndLayoutNoiseDoesNot() {
        val a = ConfirmToken.hash(GmailDraft.read(gmailScreen().state)!!)
        val b = ConfirmToken.hash(GmailDraft.read(gmailScreen(body = "你好，周五评审改到下午3点。谢谢 ").state)!!)
        val c = ConfirmToken.hash(GmailDraft.read(gmailScreen(body = "你好，周五评审改到下午4点。谢谢").state)!!)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }

    @Test fun draftDataClassEquality() {
        assertThat(Draft(listOf("a@b.com"), "s", "b")).isEqualTo(Draft(listOf("a@b.com"), "s", "b"))
    }
}
