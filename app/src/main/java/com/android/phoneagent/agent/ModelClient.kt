package com.android.phoneagent.agent

/** 一条发给模型的消息：文字 + 若干 JPEG 图片（标注截图）。 */
class ModelMessage(val role: String, val text: String, val jpegImages: List<ByteArray> = emptyList())

/**
 * 一次模型调用。[schemaJson] 是 JSON Schema（结构化输出，模型只能按它回复）。
 * [label] 只用于记录（clarify / step）。
 */
/** 各环节用的模型。澄清、每步决策、自查对延迟敏感，默认用更快的 Haiku；可用 debug 命令 model 临时切换做对比。 */
object Models {
    const val SONNET = "claude-sonnet-5-5"
    const val HAIKU = "claude-haiku-5-5"
    @Volatile var clarify = HAIKU
    @Volatile var step = HAIKU
    @Volatile var verify = HAIKU

    /** 每百万 token 的价格（美元）：输入、输出、缓存读。Haiku 5.5 的价格是 100K 以内提示的档位。 */
    fun price(model: String) = when (model) {
        HAIKU -> doubleArrayOf(0.10, 0.50, 0.01)
        else -> doubleArrayOf(2.0, 10.0, 0.20)
    }
}

class ModelRequest(
    val label: String,
    val system: String,
    val messages: List<ModelMessage>,
    val schemaJson: String,
    val maxTokens: Long = 2000,
    /** low / medium / high：思考深度。步骤决策对延迟敏感，默认 low */
    val effort: String = "low",
    /** null 用客户端默认模型 */
    val model: String? = null,
)

class ModelUsage(
    val inputTokens: Long = 0, val outputTokens: Long = 0, val cacheReadTokens: Long = 0, val cacheWriteTokens: Long = 0,
    /** 按所用模型的价格算出的大致费用（美元）；缓存写按输入价 1.25 倍估算 */
    val approxCostUsd: Double = 0.0,
) {
    operator fun plus(o: ModelUsage) = ModelUsage(
        inputTokens + o.inputTokens, outputTokens + o.outputTokens, cacheReadTokens + o.cacheReadTokens, cacheWriteTokens + o.cacheWriteTokens,
        approxCostUsd + o.approxCostUsd,
    )

    companion object {
        fun of(model: String, input: Long, output: Long, cacheRead: Long, cacheWrite: Long): ModelUsage {
            val p = Models.price(model)
            return ModelUsage(input, output, cacheRead, cacheWrite, (input * p[0] + output * p[1] + cacheRead * p[2] + cacheWrite * p[0] * 1.25) / 1_000_000)
        }
    }
}

class ModelResponse(
    /** 模型返回的 JSON 文本（符合 schema） */
    val json: String,
    val usage: ModelUsage,
    val latencyMs: Long,
    val stopReason: String? = null,
    val model: String? = null,
)

/** 模型调用失败（网络、鉴权、拒答、格式不对等），[message] 是给用户看得懂的原因。 */
class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface ModelClient {
    suspend fun complete(req: ModelRequest): ModelResponse
}
