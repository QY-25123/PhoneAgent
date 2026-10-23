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
        override suspend fun release() { releases++ }
    }

    private fun TestScope.setup(replies: List<Any>, phone: PhoneController = FakePhone({ if (it == 0) editorScreen() else screenOf("日程", "x") })): Triple<TaskCoordinator, FakeEnv, MutableList<TaskEvent>> {
        val env = FakeEnv(phone)
        val c = TaskCoordinator(CoroutineScope(StandardTestDispatcher(testScheduler) + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> println("EXC $e"); e.printStackTrace(System.out) }), FakeModel(replies), env, { NOW })
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
        assertThat((events.first() as TaskEvent.AgentSaid).text).contains("现在去办")
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
            override suspend fun release() {}
        }
        val c = TaskCoordinator(CoroutineScope(StandardTestDispatcher(testScheduler) + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> println("EXC $e"); e.printStackTrace(System.out) }), FakeModel(listOf(ready)), env, { NOW })
        val events = ArrayList<TaskEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { c.events.toList(events) }
        c.submit("周二下午3点日历加 [PA测试] 评审会")
        settle()
        assertThat(events.filterIsInstance<TaskEvent.Failed>().single().reason).contains("Shizuku")
    }
}
