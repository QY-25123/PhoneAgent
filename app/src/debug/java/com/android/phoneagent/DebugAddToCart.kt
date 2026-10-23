package com.android.phoneagent

import android.content.Context
import android.util.Log
import com.android.phoneagent.agent.AddToCartTask
import com.android.phoneagent.agent.AgentLoop
import com.android.phoneagent.agent.ClaudeModelClient
import com.android.phoneagent.agent.LoopConfig
import com.android.phoneagent.agent.LoopHost
import com.android.phoneagent.agent.ShoppingTask
import com.android.phoneagent.agent.StepInfo
import com.android.phoneagent.task.AgentTaskEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** debug：只做「搜索并加入购物车」（不结算、不改别的），用来把误删的商品加回去。 */
object DebugAddToCart {
    fun run(context: Context, product: String) {
        CoroutineScope(Dispatchers.Default).launch {
            val env = AgentTaskEnvironment(context.applicationContext)
            try {
                val phone = env.prepare()
                val host = object : LoopHost {
                    override fun onStep(info: StepInfo) { Log.i("PA", "ADDTOCART 步骤 ${info.number} ${info.did}｜${info.saw}") }
                    override suspend fun askUser(question: String, options: List<String>): String = options.firstOrNull() ?: "继续"
                }
                val r = AgentLoop(ClaudeModelClient(BuildConfig.CLAUDE_API_KEY), phone, host, LoopConfig(), log = env.newRunLog())
                    .run(AddToCartTask(ShoppingTask(product, 1, false, null)))
                Log.i("PA", "ADDTOCART 结果 $r")
            } catch (e: Throwable) {
                Log.i("PA", "ADDTOCART 失败 $e")
            } finally {
                env.release()
            }
        }
    }
}
