package com.android.phoneagent.perception

/**
 * 稳定标识：由「资源 id + 种类 + 规范化标签 + 同类中的出现序号」哈希得到 4 位字符，不含坐标，
 * 所以滚动或布局微调后同一个控件的标识不变。可输入控件的标签用 hint/desc 而不是当前内容（内容会随输入变化）。
 */
object StableIds {
    private const val ALPHABET = "0123456789abcdefghjkmnpqrstuvwxyz"   // 去掉容易混的 i l o
    private const val LEN = 4

    fun assign(elements: List<UiElement>): List<UiElement> {
        val seen = HashMap<String, Int>()
        val used = HashSet<String>()
        return elements.map { e ->
            val base = baseKey(e)
            val occurrence = seen.merge(base, 1, Int::plus)!! - 1
            var salt = 0
            var id: String
            do {
                id = hash("$base#$occurrence#$salt")
                salt++
            } while (!used.add(id))
            e.copy(stableId = id)
        }
    }

    internal fun baseKey(e: UiElement): String {
        val label = if (e.actions.contains(ElementAction.EDIT)) listOfNotNull(e.hint, e.desc).joinToString("|")
        else listOfNotNull(e.text, e.desc, e.mergedText).joinToString("|")
        return listOf(e.resourceId ?: "", e.type, label.lowercase()).joinToString("¦")
    }

    private fun hash(s: String): String {
        // FNV-1a 64 位，稳定且与平台无关（不用 String.hashCode 以外的随机性）
        var h = -0x340d631b7bdddcdbL
        for (ch in s) { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
        var x = h.toULong()
        val sb = StringBuilder()
        repeat(LEN) { sb.append(ALPHABET[(x % ALPHABET.length.toULong()).toInt()]); x /= ALPHABET.length.toULong() }
        return sb.toString()
    }
}
