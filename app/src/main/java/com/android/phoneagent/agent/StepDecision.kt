package com.android.phoneagent.agent

import org.json.JSONObject

/** 模型某一步的输出：对页面的理解、理由、一个动作。 */
class StepDecision(val understanding: String, val reason: String, val action: Action) {
    companion object {
        /** 解析并校验；不合法抛 [ModelException]（说明哪里不对，会写进历史让模型下一步改正）。 */
        fun parse(json: String): StepDecision {
            val o = try { JSONObject(json) } catch (e: Exception) { throw ModelException("模型输出不是合法 JSON：${e.message}") }
            val a = o.optJSONObject("action") ?: throw ModelException("缺少 action")
            fun str(k: String) = if (a.isNull(k)) null else a.optString(k).takeIf { it.isNotBlank() }
            fun int(k: String) = if (a.isNull(k) || !a.has(k)) null else a.optInt(k)
            val action = when (val t = a.optString("type")) {
                "open_app" -> Action.OpenApp(str("app") ?: throw ModelException("open_app 缺少 app"))
                "tap" -> Action.Tap(int("index") ?: throw ModelException("tap 缺少 index"))
                "paste" -> Action.Paste(a.optString("text", "").takeIf { !a.isNull("text") } ?: throw ModelException("paste 缺少 text"), int("x")?.coerceIn(0, 1000), int("y")?.coerceIn(0, 1000))
                "tap_xy" -> Action.TapXY((int("x") ?: throw ModelException("tap_xy 缺少 x")).coerceIn(0, 1000), (int("y") ?: throw ModelException("tap_xy 缺少 y")).coerceIn(0, 1000))
                "type" -> Action.Type(int("index") ?: throw ModelException("type 缺少 index"), a.optString("text", "").takeIf { !a.isNull("text") } ?: throw ModelException("type 缺少 text"))
                "scroll" -> Action.Scroll(
                    when (str("direction")) { "up" -> Direction.UP; "down" -> Direction.DOWN; "left" -> Direction.LEFT; "right" -> Direction.RIGHT; else -> throw ModelException("scroll 缺少 direction") },
                    int("index"),
                )
                "back" -> Action.Back
                "press_enter" -> Action.PressEnter
                "wait" -> Action.Wait((int("seconds") ?: 2).coerceIn(1, 10))
                "ask_user" -> Action.AskUser(
                    str("question") ?: throw ModelException("ask_user 缺少 question"),
                    a.optJSONArray("options")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
                )
                "done" -> Action.Done(if (a.isNull("success")) true else a.optBoolean("success"), str("result") ?: "")
                else -> throw ModelException("未知动作类型：$t")
            }
            return StepDecision(o.optString("understanding", ""), o.optString("reason", ""), action)
        }
    }
}
