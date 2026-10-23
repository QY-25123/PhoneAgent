package com.android.phoneagent.agent

/** 结构化输出用的 JSON Schema（所有字段 required、additionalProperties=false，可空字段用 anyOf null）。 */
object Schemas {
    private fun nullable(t: String) = """{"anyOf":[{"type":"$t"},{"type":"null"}]}"""
    private const val STRINGS = """{"type":"array","items":{"type":"string"}}"""

    /** 任务澄清（PRD FR-CLR-1）。 */
    val CLARIFY = """
    {"type":"object","additionalProperties":false,
     "required":["task_type","title","date","start_time","duration_minutes","recipient","email_content","email_subject","email_body","product","use_cart","quantity","cart_request","missing","assumed","question","options","mapped_from","reply"],
     "properties":{
       "task_type":{"type":"string","enum":["calendar","email","shopping","unsupported","chitchat"]},
       "title":${nullable("string")},
       "date":${nullable("string")},
       "start_time":${nullable("string")},
       "duration_minutes":${nullable("integer")},
       "recipient":${nullable("string")},
       "email_content":${nullable("string")},
       "email_subject":${nullable("string")},
       "email_body":${nullable("string")},
       "product":${nullable("string")},
       "use_cart":${nullable("boolean")},
       "quantity":${nullable("integer")},
       "cart_request":${nullable("string")},
       "missing":$STRINGS,
       "assumed":$STRINGS,
       "question":${nullable("string")},
       "options":$STRINGS,
       "mapped_from":${nullable("string")},
       "reply":${nullable("string")}
     }}""".trimIndent()

    /** 执行循环的每一步：对页面的理解、理由、一个动作。 */
    val STEP = """
    {"type":"object","additionalProperties":false,
     "required":["understanding","reason","action"],
     "properties":{
       "understanding":{"type":"string"},
       "reason":{"type":"string"},
       "action":{"type":"object","additionalProperties":false,
         "required":["type","app","index","text","direction","seconds","question","options","success","result"],
         "properties":{
           "type":{"type":"string","enum":["open_app","tap","type","scroll","back","press_enter","wait","ask_user","done"]},
           "app":${nullable("string")},
           "index":${nullable("integer")},
           "text":${nullable("string")},
           "direction":{"anyOf":[{"type":"string","enum":["up","down","left","right"]},{"type":"null"}]},
           "seconds":${nullable("integer")},
           "question":${nullable("string")},
           "options":$STRINGS,
           "success":${nullable("boolean")},
           "result":${nullable("string")}
         }}
     }}""".trimIndent()

    /** 购物车改动计划：用户的要求 + 购物车页面 → 逐项改动清单（给确认卡片用）。 */
    val CART_PLAN = """
    {"type":"object","additionalProperties":false,
     "required":["changes","question"],
     "properties":{
       "changes":{"type":"array","items":{"type":"object","additionalProperties":false,
         "required":["item","action","quantity"],
         "properties":{
           "item":{"type":"string"},
           "action":{"type":"string","enum":["delete","set_quantity"]},
           "quantity":{"anyOf":[{"type":"integer"},{"type":"null"}]}
         }}},
       "question":{"anyOf":[{"type":"string"},{"type":"null"}]}
     }}""".trimIndent()

    /** done 之后的自查。 */
    val VERIFY = """
    {"type":"object","additionalProperties":false,
     "required":["verified","evidence","problem"],
     "properties":{
       "verified":{"type":"boolean"},
       "evidence":{"type":"string"},
       "problem":${nullable("string")}
     }}""".trimIndent()
}
