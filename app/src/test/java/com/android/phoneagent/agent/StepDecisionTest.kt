package com.android.phoneagent.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class StepDecisionTest {
    private fun parse(json: String) = StepDecision.parse(json)

    @Test fun parsesEveryActionType() {
        assertThat(parse(stepJson("open_app", app = "calendar")).action).isEqualTo(Action.OpenApp("calendar"))
        assertThat(parse(stepJson("tap", index = 7)).action).isEqualTo(Action.Tap(7))
        assertThat(parse(stepJson("type", index = 3, text = "[PA测试] 评审会")).action).isEqualTo(Action.Type(3, "[PA测试] 评审会"))
        assertThat(parse(stepJson("scroll", direction = "down")).action).isEqualTo(Action.Scroll(Direction.DOWN))
        assertThat(parse(stepJson("scroll", direction = "up", index = 2)).action).isEqualTo(Action.Scroll(Direction.UP, 2))
        assertThat(parse(stepJson("back")).action).isEqualTo(Action.Back)
        assertThat(parse(stepJson("press_enter")).action).isEqualTo(Action.PressEnter)
        assertThat(parse(stepJson("wait", seconds = 3)).action).isEqualTo(Action.Wait(3))
        assertThat(parse(stepJson("ask_user", question = "几点？", options = listOf("3点", "4点"))).action).isEqualTo(Action.AskUser("几点？", listOf("3点", "4点")))
        assertThat(parse(stepJson("done", success = true, result = "已创建")).action).isEqualTo(Action.Done(true, "已创建"))
        assertThat(parse(stepJson("done", success = false, result = "没权限")).action).isEqualTo(Action.Done(false, "没权限"))
    }

    @Test fun keepsUnderstandingAndReason() {
        val d = parse(stepJson("back", understanding = "在新建页", reason = "退出"))
        assertThat(d.understanding).isEqualTo("在新建页")
        assertThat(d.reason).isEqualTo("退出")
    }

    @Test fun waitSecondsAreClampedAndDefaulted() {
        assertThat(parse(stepJson("wait", seconds = 99)).action).isEqualTo(Action.Wait(10))
        assertThat(parse(stepJson("wait")).action).isEqualTo(Action.Wait(2))
    }

    @Test fun typeMayWriteEmptyText() {
        assertThat(parse(stepJson("type", index = 3, text = "")).action).isEqualTo(Action.Type(3, ""))
    }

    @Test fun rejectsIncompleteOrUnknownActions() {
        for (bad in listOf(stepJson("tap"), stepJson("type", index = 1), stepJson("open_app"), stepJson("scroll"), stepJson("ask_user"), stepJson("fly"))) {
            assertThrows(ModelException::class.java) { parse(bad) }
        }
        assertThrows(ModelException::class.java) { parse("{}") }
        assertThrows(ModelException::class.java) { parse("不是json") }
    }

    @Test fun onlyExecutedActionsCountAsSteps() {
        assertThat(Action.Tap(1).countsAsStep).isTrue()
        assertThat(Action.Back.countsAsStep).isTrue()
        assertThat(Action.OpenApp("calendar").countsAsStep).isTrue()
        assertThat(Action.Wait(1).countsAsStep).isFalse()
        assertThat(Action.AskUser("?").countsAsStep).isFalse()
        assertThat(Action.Done(true, "").countsAsStep).isFalse()
    }
}
