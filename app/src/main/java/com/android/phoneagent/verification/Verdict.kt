package com.android.phoneagent.verification

/** 完成验证的结果（M4 实现）。证据是读到的文字，不是模型的转述。 */
class Evidence(val lines: List<String>)

sealed interface Verdict {
    data class Passed(val evidence: Evidence) : Verdict
    data class Failed(val expected: String, val actual: String, val leftovers: List<String>) : Verdict
}
