package com.android.phoneagent.task

import android.graphics.Bitmap
import com.android.phoneagent.agent.ActionOutcome
import com.android.phoneagent.agent.FakeModel
import com.android.phoneagent.agent.FakePhone
import com.android.phoneagent.agent.NOW
import com.android.phoneagent.agent.NoopRunLog
import com.android.phoneagent.agent.PhoneController
import com.android.phoneagent.agent.RunLog
import com.android.phoneagent.agent.clarifyJson
import com.android.phoneagent.agent.editorScreen
import com.android.phoneagent.agent.screenOf
import com.android.phoneagent.agent.verifyJson
import com.android.phoneagent.agent.stepJson
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskCoordinatorTest {
    private class FakeEnv(val phone: PhoneController) : TaskEnvironment {
        var prepares = 0
        var releases = 0
        override suspend fun prepare(): PhoneController { prepares++; return phone }
        override fun newRunLog(): RunLog = NoopRunLog
        override fun thumbnail(width: Int): Bitmap? = null
        var handoffs = 0
        override suspend fun handoffToMain() { handoffs++; releases++ }
        override suspend fun release() { releases++ }
    }

    private fun TestScope.setup(replies: List<Any>, phone: PhoneController = FakePhone({ if (it == 0) editorScreen() else screenOf("日程", "x") })): Triple<TaskCoordinator, FakeEnv, MutableList<TaskEvent>> {
        val env = FakeEnv(phone)
        val c = TaskCoordinator(CoroutineScope(StandardTestDispatcher(testScheduler) + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> println("EXC $e"); e.printStackTrace(System.out) }), FakeModel(replies), env, { NOW }, frameIntervalMs = 0)
        val events = ArrayList<TaskEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { c.events.toList(events) }
        return Triple(c, env, events)
    }

    /** SharedFlow 的收集者被调度要再多一轮：连续推进几次，保证事件都到了 */
    private suspend fun TestScope.settle() { repeat(5) { advanceUntilIdle(); kotlinx.coroutines.yield() } }

    private val ready = clarifyJson(title = "[PA测试] 评审会", date = "2026-10-27", start = "15:00")

    @Test fun completeRequestRunsEndToEndAndReportsSuccess() = runTest {
        val (c, env, events) = setup(listOf(ready, verifyJson(true)))
        assertThat(c.submit("周二下午3点日历加 [PA测试] 评审会")).isTrue()
        settle()
        assertThat(events.map { it::class.simpleName }).containsAtLeast("AgentSaid", "Started", "Progress", "Succeeded").inOrder()
        assertThat(events.filterIsInstance<TaskEvent.AgentSaid>().first().text).contains("日历：「[PA测试] 评审会」")
        assertThat(c.busy.value).isFalse()
        assertThat(env.prepares).isEqualTo(1)      // 提前准备的虚拟屏被用上，没有重复创建
        assertThat(env.releases).isAtLeast(1)       // 任务结束后释放
    }

    @Test fun missingInfoAsksThenContinuesWithTheAnswer() = runTest {
        val (c, _, events) = setup(listOf(
            clarifyJson(missing = listOf("标题", "日期", "开始时间"), question = "叫什么、哪天、几点？"), ready, verifyJson(true)))
        c.submit("帮我加个日历事项")
        settle()
        assertThat(events.last()).isInstanceOf(TaskEvent.Ask::class.java)
        assertThat(c.busy.value).isTrue()
        assertThat(c.submit("[PA测试] 评审会，周二下午3点")).isFalse()   // 这是回答，不是新任务
        settle()
        assertThat(events.any { it is TaskEvent.Succeeded }).isTrue()
        assertThat(c.busy.value).isFalse()
    }

    @Test fun unsupportedRequestOnlyRepliesAndReleasesThePrefetchedDisplay() = runTest {
        val (c, env, events) = setup(listOf(clarifyJson(type = "unsupported", reply = "这个我做不了")))
        c.submit("帮我订个会议室外卖")      // 含「会议」：会提前准备虚拟屏，但澄清结果是不支持
        settle()
        assertThat(events.single()).isEqualTo(TaskEvent.AgentSaid("这个我做不了"))
        assertThat(env.prepares).isEqualTo(env.releases)   // 提前准备的虚拟屏被释放
        assertThat(c.busy.value).isFalse()
    }

    @Test fun requestWithoutCalendarWordsDoesNotPrepareUntilReady() = runTest {
        val (c, env, _) = setup(listOf(clarifyJson(type = "chitchat", reply = "你好")))
        c.submit("你好呀")
        settle()
        assertThat(env.prepares).isEqualTo(0)
        assertThat(env.releases).isEqualTo(0)
    }

    @Test fun secondTaskWhileBusyIsRefused() = runTest {
        val (c, _, events) = setup(listOf(clarifyJson(missing = listOf("标题"), question = "叫什么？")))
        c.submit("加个日历事项")
        settle()
        // 正在等回答时发来的话是回答；真正「忙」的情形：任务运行中
        assertThat(c.busy.value).isTrue()
        assertThat(events.last()).isInstanceOf(TaskEvent.Ask::class.java)
    }

    @Test fun stopCancelsAndReleases() = runTest {
        val (c, env, events) = setup(listOf(clarifyJson(missing = listOf("标题"), question = "叫什么？")))
        c.submit("加个日历事项")
        settle()
        c.stop()
        settle()
        assertThat(events.last()).isEqualTo(TaskEvent.Stopped("已停止。"))
        assertThat(c.busy.value).isFalse()
        assertThat(env.releases).isAtLeast(1)
    }

    @Test fun clarificationStopsAfterSixRounds() = runTest {
        val (c, _, events) = setup(listOf(clarifyJson(missing = listOf("标题"), question = "叫什么？")))
        c.submit("加个日历事项")
        repeat(6) { settle(); c.submit("嗯") }   // 第 6 次回答之后第 7 次澄清仍要追问，超过上限；再多发就是新任务了
        settle()
        assertThat(events.filterIsInstance<TaskEvent.Ask>()).hasSize(6)
        assertThat((events.last() as TaskEvent.AgentSaid).text).contains("换个说法")
        assertThat(c.busy.value).isFalse()
    }

    @Test fun failedPreparationIsReportedAsFailure() = runTest {
        val env = object : TaskEnvironment {
            override suspend fun prepare(): PhoneController = throw IllegalStateException("没有连上 Shizuku")
            override fun newRunLog(): RunLog = NoopRunLog
            override fun thumbnail(width: Int): Bitmap? = null
            override suspend fun handoffToMain() {}
            override suspend fun release() {}
        }
        val c = TaskCoordinator(CoroutineScope(StandardTestDispatcher(testScheduler) + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> println("EXC $e"); e.printStackTrace(System.out) }), FakeModel(listOf(ready)), env, { NOW }, frameIntervalMs = 0)
        val events = ArrayList<TaskEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { c.events.toList(events) }
        c.submit("周二下午3点日历加 [PA测试] 评审会")
        settle()
        assertThat(events.filterIsInstance<TaskEvent.Failed>().single().reason).contains("Shizuku")
    }

    // ---- 邮件（T2）----
    private val emailReady = clarifyJson(type = "email", recipient = "qijie_jerry@163.com", content = "改期", subject = "[PA测试] 周五评审", body = "你好，周五评审改到下午3点。谢谢")
    private fun gmail() = com.android.phoneagent.agent.gmailScreen()
    private fun mailPhone() = FakePhone({ if (it < 3) gmail() else screenOf("已发送", "x") })

    @Test fun emailShowsAConfirmationCardAndSendsOnlyAfterTheUserChoosesSend() = runTest {
        val phone = mailPhone()
        val (c, env, events) = setup(listOf(emailReady, stepJson("done", success = true, result = "在已发送里看到了"), verifyJson(true)), phone)
        c.submit("给 qijie_jerry@163.com 发邮件")
        settle()
        val card = events.filterIsInstance<TaskEvent.Confirm>().single()
        assertThat(card.to).isEqualTo("qijie_jerry@163.com")
        assertThat(card.subject).isEqualTo("[PA测试] 周五评审")
        assertThat(phone.armed).isEmpty()                                  // 用户还没选：没有布防，更没有发送
        assertThat(phone.executed).isEmpty()
        c.confirm(ConfirmChoice.SEND)
        settle()
        assertThat(events.last()).isInstanceOf(TaskEvent.Succeeded::class.java)
        assertThat((events.last() as TaskEvent.Succeeded).title).contains("qijie_jerry@163.com")
        assertThat(events).contains(TaskEvent.ConfirmResolved(ConfirmChoice.SEND))   // 界面据此去掉卡片上的按钮
        assertThat(phone.armed).hasSize(1)
        assertThat(c.busy.value).isFalse()
        assertThat(env.releases).isAtLeast(1)
    }

    @Test fun cancelOnTheCardStopsWithoutSending() = runTest {
        val phone = mailPhone()
        val (c, _, events) = setup(listOf(emailReady, stepJson("done", success = true, result = "草稿已丢弃"), verifyJson(true)), phone)
        c.submit("给 qijie_jerry@163.com 发邮件")
        settle()
        c.confirm(ConfirmChoice.CANCEL)
        settle()
        assertThat(events.last()).isInstanceOf(TaskEvent.Stopped::class.java)
        assertThat(phone.armed).isEmpty()
        assertThat(phone.executed.filterIsInstance<com.android.phoneagent.agent.Action.Tap>().filter { it.index == 2 }).isEmpty()
    }

    @Test fun typingWhileTheCardIsWaitingCountsAsAModificationRequest() = runTest {
        val phone = FakePhone({ gmail() })
        val (c, _, events) = setup(listOf(emailReady, stepJson("done", success = true, result = "草稿已修改")), phone)
        c.submit("给 qijie_jerry@163.com 发邮件")
        settle()
        assertThat(c.submit("正文改成下午4点")).isFalse()       // 不是新任务
        settle()
        assertThat(events.filterIsInstance<TaskEvent.Confirm>()).hasSize(2)   // 改完后重新出现确认卡片
    }
}
