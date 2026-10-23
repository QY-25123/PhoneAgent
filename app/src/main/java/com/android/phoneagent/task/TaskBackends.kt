package com.android.phoneagent.task

import android.content.Context
import com.android.phoneagent.BuildConfig
import com.android.phoneagent.agent.ClaudeModelClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 应用里唯一的任务入口（进程级单例）：Claude 客户端 + 真实环境。 */
object TaskBackends {
    @Volatile private var instance: TaskBackend? = null

    fun get(context: Context): TaskBackend = instance ?: synchronized(this) {
        instance ?: TaskCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            model = ClaudeModelClient(BuildConfig.CLAUDE_API_KEY),
            env = AgentTaskEnvironment(context.applicationContext),
        ).also { instance = it }
    }
}
