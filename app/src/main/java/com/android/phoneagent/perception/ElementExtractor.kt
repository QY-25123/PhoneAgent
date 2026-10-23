package com.android.phoneagent.perception

class ExtractOptions(
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    /** 元素自己的文字/描述的最大长度 */
    val maxOwnTextChars: Int = 200,
    /** 可点击父节点合并子孙文字后的标签最大长度 */
    val maxMergedChars: Int = 80,
    /** 裁剪后宽或高小于这个值的节点视为不可见 */
    val minSizePx: Int = 2,
    /** 可点击父节点面积超过屏幕的这个比例时，不吞并里面的文字（防止整页被一个可点击容器吃掉） */
    val maxAbsorbAreaRatio: Double = 0.5,
)

class Extraction(
    val elements: List<UiElement>,
    val packageName: String?,
    val pageTitle: String?,
    /** 有占据屏幕相当面积的 WebView，但里面没有任何子节点（内容还没加载出来） */
    val webViewEmpty: Boolean,
    val truncatedTree: Boolean,
)

/**
 * 原始快照 → 带编号的元素列表（PRD 6.1 FR-LOOP-3 的「UI 树」那一半）。
 * 规则：只保留可见、在屏幕内、且可点击/可输入/可滚动/可勾选或有文字/描述的节点；弹窗遮住的下层元素丢弃；
 * 可点击容器吞并里面的纯文字子节点（文字合并成它的标签）；相同位置相同标签的重复节点合并。
 * 不做任何安全判断：「Place your order」之类照常出现，拦截是 safety 的事。
 */
object ElementExtractor {
    fun extract(snap: RawSnapshot, opts: ExtractOptions = ExtractOptions()): Extraction {
        val screen = Bounds(0, 0, opts.screenWidth, opts.screenHeight)
        val windows = snap.windows.sortedBy { it.layer }   // 从下到上
        val found = ArrayList<Cand>()
        var webViewEmpty = false

        for ((wi, w) in windows.withIndex()) {
            val winBounds = w.bounds.intersect(screen) ?: screen
            val n = w.nodes.size
            val parent = IntArray(n) { -1 }
            val stack = ArrayList<Int>()   // 当前路径上的节点下标
            for (i in 0 until n) {
                val d = w.nodes[i].depth
                while (stack.isNotEmpty() && w.nodes[stack.last()].depth >= d) stack.removeAt(stack.size - 1)
                parent[i] = stack.lastOrNull() ?: -1
                stack.add(i)
            }
            val clip = arrayOfNulls<Bounds>(n)
            for (i in 0 until n) {
                val node = w.nodes[i]
                val c = node.bounds.intersect(winBounds)
                if (node.visible && c != null && c.width >= opts.minSizePx && c.height >= opts.minSizePx) clip[i] = c
            }
            // WebView 空壳：可见、面积 ≥ 屏幕 20%、下一个节点不是它的子节点
            for (i in 0 until n) {
                val node = w.nodes[i]
                val c = clip[i] ?: continue
                if (node.className.contains("WebView") && c.area * 5 >= screen.area &&
                    (i + 1 >= n || w.nodes[i + 1].depth <= node.depth)
                ) webViewEmpty = true
            }

            // 纯文字节点被最近的可点击祖先吞并
            val absorbedInto = IntArray(n) { -1 }
            val absorbedTexts = HashMap<Int, MutableList<String>>()
            for (i in 0 until n) {
                val node = w.nodes[i]
                val c = clip[i] ?: continue
                if (isActionable(node) || !hasLabel(node)) continue
                var a = parent[i]
                while (a >= 0 && !(w.nodes[a].clickable && clip[a] != null)) a = parent[a]
                if (a < 0) continue
                val an = w.nodes[a]
                val ac = clip[a]!!
                if (an.scrollable || ac.area > screen.area * opts.maxAbsorbAreaRatio || !ac.containsRect(c)) continue
                absorbedInto[i] = a
                val t = (node.text ?: node.desc)?.let(::normalizeSpace)
                if (!t.isNullOrEmpty()) absorbedTexts.getOrPut(a) { ArrayList() }.add(t)
            }

            for (i in 0 until n) {
                val node = w.nodes[i]
                val c = clip[i] ?: continue
                if (absorbedInto[i] >= 0) continue
                val actionable = isActionable(node)
                if (!actionable && !hasLabel(node)) continue
                // 被更上层窗口盖住：中心点落在任何更高 layer 窗口的范围内
                val cx = c.centerX; val cy = c.centerY
                if (windows.any { o -> o.layer > w.layer && o.bounds.contains(cx, cy) }) continue

                val own = node.text?.isNotBlank() == true || node.desc?.isNotBlank() == true
                val merged = if (!own) absorbedTexts[i]?.let { mergeTexts(it, opts.maxMergedChars) } else null
                found.add(
                    Cand(
                        key = Triple(c, normalizeLabel(node, merged), kindOf(node)),
                        order = found.size,
                        el = UiElement(
                            index = 0, stableId = "", type = typeOf(node),
                            text = node.text?.takeIf { it.isNotBlank() }?.let { truncate(normalizeSpace(it), opts.maxOwnTextChars) },
                            desc = node.desc?.takeIf { it.isNotBlank() }?.let { truncate(normalizeSpace(it), opts.maxOwnTextChars) },
                            hint = node.hint?.takeIf { it.isNotBlank() }?.let { truncate(normalizeSpace(it), opts.maxOwnTextChars) },
                            mergedText = merged, resourceId = node.resourceId, className = node.className, bounds = c,
                            actions = actionsOf(node), focused = node.focused,
                            checked = if (node.checkable) node.checked else null,
                            enabled = !node.disabled, password = node.password,
                        ),
                    )
                )
            }
        }

        val deduped = dedupe(found)
        val sorted = deduped.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val numbered = StableIds.assign(sorted).mapIndexed { i, e -> e.copy(index = i + 1) }
        val top = windows.lastOrNull { it.type == 1 } ?: windows.lastOrNull()
        return Extraction(
            elements = numbered,
            packageName = top?.packageName,
            pageTitle = top?.title?.takeIf { it.isNotBlank() },
            webViewEmpty = webViewEmpty,
            truncatedTree = snap.truncated,
        )
    }

    private class Cand(val key: Triple<Bounds, String, String>, val order: Int, val el: UiElement)

    /** 相同位置、相同标签、相同种类的重复节点合并：操作取并集，优先保留有资源 id 的那个。 */
    private fun dedupe(cands: List<Cand>): List<UiElement> {
        val byKey = LinkedHashMap<Triple<Bounds, String, String>, UiElement>()
        for (c in cands) {
            val old = byKey[c.key]
            byKey[c.key] = if (old == null) c.el else {
                val base = if (old.resourceId == null && c.el.resourceId != null) c.el else old
                base.copy(actions = old.actions + c.el.actions, focused = old.focused || c.el.focused)
            }
        }
        return byKey.values.toList()
    }

    private fun isActionable(n: RawNode) = n.clickable || n.longClickable || n.editable || n.scrollable || n.checkable
    private fun hasLabel(n: RawNode) = !n.text.isNullOrBlank() || !n.desc.isNullOrBlank() || (n.editable && !n.hint.isNullOrBlank())

    private fun actionsOf(n: RawNode): Set<ElementAction> = buildSet {
        if (n.clickable) add(ElementAction.CLICK)
        if (n.longClickable) add(ElementAction.LONG_CLICK)
        if (n.editable) add(ElementAction.EDIT)
        if (n.checkable) add(ElementAction.TOGGLE)
        if (n.scrollable) {
            // 没有方向信息时（旧系统）两个方向都给
            val dirs = n.scrollForward || n.scrollBackward
            if (!dirs || n.scrollForward) add(ElementAction.SCROLL_FORWARD)
            if (!dirs || n.scrollBackward) add(ElementAction.SCROLL_BACKWARD)
        }
    }

    internal fun typeOf(n: RawNode): String {
        val cls = n.className.substringAfterLast('.')
        return when {
            n.editable -> "EditText"
            cls.contains("WebView") -> "WebView"
            cls.contains("CheckBox") -> "Checkbox"
            cls.contains("Switch") || cls.contains("Toggle") -> "Switch"
            cls.contains("RadioButton") -> "Radio"
            cls.contains("Spinner") -> "Dropdown"
            cls.contains("ImageButton") -> "ImageButton"
            cls.contains("ImageView") -> if (n.clickable) "ImageButton" else "Image"
            n.checkable -> "Checkbox"
            n.scrollable && !n.clickable -> "Scrollable"
            cls.contains("Button") -> "Button"
            n.clickable || n.longClickable -> "Button"
            cls.contains("TextView") || !n.text.isNullOrBlank() -> "Text"
            else -> "View"
        }
    }

    private fun kindOf(n: RawNode) = typeOf(n)

    private fun normalizeLabel(n: RawNode, merged: String?) =
        listOf(n.text, n.desc, n.hint, merged).joinToString("|") { normalizeSpace(it ?: "").lowercase() }

    internal fun normalizeSpace(s: String) = s.replace(Regex("\\s+"), " ").trim()

    internal fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max) + "…(共${s.length}字)"

    private fun mergeTexts(parts: List<String>, max: Int): String {
        val deduped = parts.filterIndexed { i, p -> i == 0 || p != parts[i - 1] }
        return truncate(deduped.joinToString(" "), max)
    }
}
