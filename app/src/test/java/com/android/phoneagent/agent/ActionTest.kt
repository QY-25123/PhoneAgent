package com.android.phoneagent.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 示例测试：证明单元测试环境（JUnit + Truth）能跑。M2 起换成真正的动作解析/校验测试。 */
class ActionTest {
    @Test
    fun ask_defaultsToNoOptions() {
        assertThat(Action.Ask("想改成几点？").options).isEmpty()
    }

    @Test
    fun finish_isOnlyAClaim() {
        val a: Action = Action.Finish("已创建")
        assertThat(a).isInstanceOf(Action.Finish::class.java)
        assertThat(a).isEqualTo(Action.Finish("已创建"))
    }
}
