package com.android.phoneagent.task

import com.android.phoneagent.BuildConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** BuildConfig 里有 CLAUDE_API_KEY 字段（值只来自 local.properties；这里不断言、不打印它的内容）。 */
class ApiKeyTest {
    @Test
    fun buildConfigHasKeyField() {
        assertThat(BuildConfig.CLAUDE_API_KEY).isNotNull()
    }
}
