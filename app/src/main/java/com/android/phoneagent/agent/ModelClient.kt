package com.android.phoneagent.agent

/** 一条发给模型的消息：文字 + 若干 JPEG 图片（标注截图）。 */
class ModelMessage(val role: String, val text: String, val jpegImages: List<ByteArray> = emptyList())

/**
 * 一次模型调用。[schemaJson] 是 JSON Schema（结构化输出，模型只能按它回复）。
 * [label] 只用于记录（clarify / step）。
 */
class ModelRequest(
    val label: String,
    val system: String,
    val messages: List<ModelMessage>,
    val schemaJson: String,
    val maxTokens: Long = 2000,
    /** low / medium / high：思考深度。步骤决策对延迟敏感，默认 low */
    val effort: String = "low",
)

class ModelUsage(val inputTokens: Long = 0, val outputTokens: Long = 0, val cacheReadTokens: Long = 0, val cacheWriteTokens: Long = 0) {
    operator fun plus(o: ModelUsage) = ModelUsage(inputTokens + o.inputTokens, outputTokens + o.outputTokens, cacheReadTokens + o.cacheReadTokens, cacheWriteTokens + o.cacheWriteTokens)

    /** 大致费用（美元）：claude-sonnet-5-5 输入 $2/MTok、输出 $10/MTok、缓存读 $0.20/MTok、缓存写按输入价 1.25 倍估算。 */
    val approxCostUsd: Double
        get() = (inputTokens * 2.0 + outputTokens * 10.0 + cacheReadTokens * 0.2 + cacheWriteTokens * 2.5) / 1_000_000
}

class ModelResponse(
    /** 模型返回的 JSON 文本（符合 schema） */
    val json: String,
    val usage: ModelUsage,
    val latencyMs: Long,
    val stopReason: String? = null,
)

/** 模型调用失败（网络、鉴权、拒答、格式不对等），[message] 是给用户看得懂的原因。 */
class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface ModelClient {
    suspend fun complete(req: ModelRequest): ModelResponse
}
