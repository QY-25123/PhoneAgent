package com.android.phoneagent.agent

import com.android.phoneagent.perception.PerceptionResult
import org.json.JSONObject

/** 从截图转写的微信聊天页（界面树读不到时的兜底）。位置是截图上的比例 0–1000。 */
class ChatRead(
    val isChat: Boolean, val title: String, val inputText: String, val lastSent: String, val failedMark: Boolean,
    val sendX: Int?, val sendY: Int?,
)

object WechatVision {
    /** 让模型读一张微信截图；失败（没有截图、模型出错、输出不合法）返回 null。 */
    suspend fun read(model: ModelClient, screen: PerceptionResult, log: RunLog = NoopRunLog): ChatRead? {
        val shot = screen.state.screenshotJpeg ?: return null
        val req = ModelRequest(
            "wechat_read", Prompts.WECHAT_READ_SYSTEM,
            listOf(ModelMessage("user", "读这张微信截图，按 schema 输出 JSON。", listOf(shot))),
            Schemas.WECHAT_READ, 500, "low", Models.step,
        )
        val resp = try { model.complete(req).also { log.modelCall(req, it, null) } } catch (e: ModelException) { log.modelCall(req, null, e.message); return null }
        val o = try { JSONObject(resp.json) } catch (e: Exception) { return null }
        fun pos(k: String) = o.optInt(k, -1).takeIf { it in 0..1000 }
        return ChatRead(o.optBoolean("is_chat"), o.optString("title").trim(), o.optString("input_text").trim(), o.optString("last_sent").trim(), o.optBoolean("failed_mark"), pos("send_x"), pos("send_y"))
    }
}
