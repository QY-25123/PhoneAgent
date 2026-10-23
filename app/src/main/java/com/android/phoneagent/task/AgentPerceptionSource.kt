package com.android.phoneagent.task

import android.graphics.Bitmap
import com.android.phoneagent.perception.PerceptionSource
import com.android.phoneagent.perception.RawSnapshot

/** 把 [Agent]（UserService 连接 + ImageReader 最新帧）接到感知模块。 */
class AgentPerceptionSource(private val displayId: Int) : PerceptionSource {
    override suspend fun snapshot(): RawSnapshot? = Agent.snapshot(displayId)?.let(RawSnapshot::parse)
    override fun screenshot(): Bitmap? = Agent.latestFrame()
}
