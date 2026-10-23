package com.android.phoneagent.agent

import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 用官方 Anthropic Java SDK 调用 claude-sonnet-5-5（PRD：模型定为 sonnet-5-5）。
 * 结构化输出：output_config.format = json_schema，回复的第一个文本块就是符合 schema 的 JSON。
 * Sonnet 5.5 的约束：不传 temperature/top_p/top_k，不用强制 tool_choice，不能关思考（用 effort 控制深度）。
 */
class ClaudeModelClient(
    private val apiKey: String,
    private val model: String = MODEL,
) : ModelClient {
    private val client: AnthropicClient by lazy {
        if (apiKey.isBlank()) throw ModelException("没有配置 Claude API Key（local.properties 的 CLAUDE_API_KEY）")
        AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    }

    override suspend fun complete(req: ModelRequest): ModelResponse = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        try {
            val schema = JSONObject(req.schemaJson)
            val b = JsonOutputFormat.Schema.builder()
            for (k in schema.keys()) b.putAdditionalProperty(k, JsonValue.from(toJava(schema.get(k))))
            val params = MessageCreateParams.builder()
                .model(req.model ?: model)
                .maxTokens(req.maxTokens)
                .system(req.system)
                .outputConfig(
                    OutputConfig.builder()
                        .effort(effortOf(req.effort))
                        .format(JsonOutputFormat.builder().schema(b.build()).build())
                        .build()
                )
                // 按官方建议在 Claude API 上开启服务端拒答回退（失败时下面会去掉再试一次）
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            for (m in req.messages) {
                val blocks = ArrayList<ContentBlockParam>()
                for (img in m.jpegImages) {
                    blocks += ContentBlockParam.ofImage(
                        ImageBlockParam.builder().source(
                            Base64ImageSource.builder().data(Base64.encodeToString(img, Base64.NO_WRAP))
                                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()
                        ).build()
                    )
                }
                blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(m.text).build())
                if (m.role == "assistant") params.addAssistantMessageOfBlockParams(blocks) else params.addUserMessageOfBlockParams(blocks)
            }
            val resp = try {
                client.messages().create(params.build())
            } catch (e: AnthropicServiceException) {
                // 回退参数不被接受（例如组织没有该 beta）：去掉再试一次
                if (e.statusCode() == 400 && (e.message ?: "").contains("fallback", ignoreCase = true)) {
                    client.messages().create(params.build().toBuilder().removeAdditionalHeaders("anthropic-beta").removeAdditionalBodyProperty("fallbacks").build())
                } else throw e
            }
            val stop = resp.stopReason().map { it.toString() }.orElse(null)
            if (stop == "refusal") throw ModelException("模型拒绝了这次请求")
            val text = resp.content().firstNotNullOfOrNull { it.text().orElse(null)?.text() }
                ?: throw ModelException("模型没有返回内容（stop=$stop）")
            val u = resp.usage()
            ModelResponse(
                json = text,
                usage = ModelUsage.of(req.model ?: model, u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0), u.cacheCreationInputTokens().orElse(0)),
                latencyMs = (System.nanoTime() - t0) / 1_000_000,
                stopReason = stop,
                model = req.model ?: model,
            )
        } catch (e: ModelException) {
            throw e
        } catch (e: AnthropicServiceException) {
            throw ModelException("Claude API 返回错误 ${e.statusCode()}：${e.message}", e)
        } catch (e: Exception) {
            throw ModelException("调用 Claude 失败：${e.message}", e)
        }
    }

    private fun effortOf(s: String) = when (s) {
        "high" -> OutputConfig.Effort.HIGH
        "medium" -> OutputConfig.Effort.MEDIUM
        else -> OutputConfig.Effort.LOW
    }

    private fun toJava(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { toJava(v.get(it)) }
        is org.json.JSONArray -> List(v.length()) { toJava(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    companion object {
        const val MODEL = Models.SONNET
    }
}
