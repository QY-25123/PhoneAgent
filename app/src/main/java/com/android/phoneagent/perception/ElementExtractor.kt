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
    /** 同一窗口内：面积至少占屏幕这个比例、内部足够「满」的后绘制子树，被认为遮住了它下面的元素 */
    val occluderMinAreaRatio: Double = 0.2,
    /** 子树叶子节点覆盖其区域的比例达到这个值才算「不透明」（稀疏的透明容器，例如折叠的搜索栏，不算） */
    val occluderMinFill: Double = 0.5,
)

/** 通用文字：按钮上只有这种词时看不出真实含义，需要取覆盖它的文字或资源 id。 */
object GenericLabels {
    private val WORDS = setOf(
        "submit", "button", "ok", "okay", "continue", "next", "done", "confirm", "yes", "go", "click", "link", "image", "icon", "view", "more",
    )
    fun isGeneric(s: String?) = s != null && s.trim().trimEnd('.').lowercase() in WORDS
}

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
            val subtreeEnd = IntArray(n)   // 不含：i 的子树是 [i, subtreeEnd[i])
            for (i in n - 1 downTo 0) {
                var e = i + 1
                while (e < n && w.nodes[e].depth > w.nodes[i].depth) e = subtreeEnd[e]
                subtreeEnd[i] = e
            }
            val paint = paintRank(w.nodes, parent)
            val occluders = findOccluders(w.nodes, clip, subtreeEnd, screen, opts)
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

                // 同一窗口内被后绘制的「满」子树盖住（例如底部弹出的新建事项面板盖住下面的工具栏）
                if (occluders.any { (oi, ob) -> (oi >= subtreeEnd[i] || oi < i) && paint[oi] > paint[i] && ob.contains(cx, cy) }) continue

                val ownLabel = node.text?.takeIf { it.isNotBlank() } ?: node.desc?.takeIf { it.isNotBlank() }
                val own = ownLabel != null
                val generic = GenericLabels.isGeneric(ownLabel)
                var fromOverlap = false
                var merged = if (!own || generic) absorbedTexts[i]?.let { mergeTexts(it, opts.maxMergedChars) } else null
                if (generic && merged == null) {
                    // 通用文字的按钮：取中心落在它边界内的其他文字节点（网页里按钮文字常是兄弟节点）
                    val over = ArrayList<String>()
                    for (j in 0 until n) {
                        val o = w.nodes[j]
                        val oc = clip[j] ?: continue
                        if (j == i || o.text.isNullOrBlank() || GenericLabels.isGeneric(o.text) || isActionable(o) || o.text.count { it.isLetter() } < 3) continue
                        if (c.contains(oc.centerX, oc.centerY)) over.add(normalizeSpace(o.text))
                    }
                    if (over.isNotEmpty()) { merged = mergeTexts(over, opts.maxMergedChars); fromOverlap = true }
                }
                // 可点击元素的标签统一按合并标签的长度限制（Gmail 邮件行、Amazon 的「Delete <商品名>」描述都很长）；纯文字保留更长
                val ownLimit = if (node.clickable) opts.maxMergedChars else opts.maxOwnTextChars
                val idHint = node.resourceId ?: ancestorId(w.nodes, parent, i)
                found.add(
                    Cand(
                        key = Triple(c, normalizeLabel(node, merged), kindOf(node)),
                        order = found.size,
                        el = UiElement(
                            index = 0, stableId = "", type = typeOf(node),
                            text = node.text?.takeIf { it.isNotBlank() && !node.showingHint }?.let { truncate(normalizeSpace(it), ownLimit) },
                            desc = node.desc?.takeIf { it.isNotBlank() }?.let { truncate(normalizeSpace(it), ownLimit) },
                            hint = (node.hint?.takeIf { it.isNotBlank() } ?: node.text?.takeIf { node.showingHint && it.isNotBlank() })
                                ?.let { truncate(normalizeSpace(it), opts.maxOwnTextChars) },
                            mergedText = merged, resourceId = node.resourceId, className = node.className, bounds = c,
                            actions = actionsOf(node), focused = node.focused,
                            checked = if (node.checkable) node.checked else null,
                            enabled = !node.disabled, password = node.password, idHint = idHint, mergedFromOverlap = fromOverlap,
                            windowId = w.id, nodeIndex = i,
                        ),
                    )
                )
            }
        }

        val deduped = labelEditableByRow(dedupe(found))
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

    /**
     * 绘制次序：先序遍历，同一父节点的子节点按 drawingOrder（再按树里顺序）排。数值大的画在上面。
     * 无障碍树里的子节点顺序不是绘制顺序（例如协调布局里的底部面板先出现在树里，却画在工具栏上面）。
     */
    internal fun paintRank(nodes: List<RawNode>, parent: IntArray): IntArray {
        val n = nodes.size
        val children = Array(n + 1) { ArrayList<Int>() }   // 下标 n 是虚拟根
        for (i in 0 until n) children[if (parent[i] < 0) n else parent[i]].add(i)
        val rank = IntArray(n)
        var next = 0
        // 用显式栈避免深树栈溢出
        val stack = ArrayDeque<Int>().apply { add(n) }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (i < n) rank[i] = next++
            children[i].sortedWith(compareBy({ nodes[it].drawingOrder }, { it })).asReversed().forEach { stack.addLast(it) }
        }
        return rank
    }

    /** 最近祖先（最多向上 4 层）的资源 id，跳过系统通用 id。 */
    private fun ancestorId(nodes: List<RawNode>, parent: IntArray, i: Int): String? {
        var p = parent[i]; var k = 0
        while (p >= 0 && k++ < 4) {
            val id = nodes[p].resourceId
            if (id != null && !id.startsWith("android:id/")) return id
            p = parent[p]
        }
        return null
    }

    /** 「有实际内容」的节点：可操作，或有文字/描述，或是图片/文字/按钮类控件。空的 View/FrameLayout（透明的触摸拦截层）不算。 */
    private fun isFootprint(n: RawNode): Boolean {
        if (n.clickable || n.longClickable || n.editable || n.checkable) return true
        if (!n.text.isNullOrBlank() || !n.desc.isNullOrBlank()) return true
        val c = n.className.substringAfterLast('.')
        return c.endsWith("ImageView") || c.endsWith("ImageButton") || c.endsWith("TextView") || c.endsWith("Button") || c.endsWith("Switch") || c.endsWith("CheckBox")
    }

    /**
     * 同一窗口内「盖住别人」的节点：可见、面积不小、画得更靠后（见 [paintRank]）、且内部足够满（有内容的节点覆盖区域的比例够高）。
     * 返回 (节点下标, 它的裁剪后边界)。稀疏或空的透明容器（折叠的搜索栏全屏框、拦截触摸的透明 View）覆盖率低，不算遮挡。
     */
    internal fun findOccluders(nodes: List<RawNode>, clip: Array<Bounds?>, subtreeEnd: IntArray, screen: Bounds, opts: ExtractOptions): List<Pair<Int, Bounds>> {
        val out = ArrayList<Pair<Int, Bounds>>()
        val minArea = screen.area * opts.occluderMinAreaRatio
        for (i in nodes.indices) {
            val b = clip[i] ?: continue
            if (b.area < minArea || subtreeEnd[i] - i < 3) continue
            val cell = 40
            val cols = (b.width + cell - 1) / cell; val rows = (b.height + cell - 1) / cell
            val covered = BooleanArray(cols * rows)
            for (j in i + 1 until subtreeEnd[i]) {
                if (!isFootprint(nodes[j])) continue
                val lb = clip[j]?.intersect(b) ?: continue
                for (r in (lb.top - b.top) / cell..(lb.bottom - 1 - b.top) / cell) for (c in (lb.left - b.left) / cell..(lb.right - 1 - b.left) / cell) covered[r * cols + c] = true
            }
            if (covered.count { it }.toDouble() / covered.size >= opts.occluderMinFill) out.add(i to b)
        }
        return out
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

    /**
     * 没有任何标签的输入框（Gmail 的「收件人」框就是这样），用同一行左边最近的有文字元素当提示：
     * 垂直方向有重叠、右边缘不超过输入框左边缘、距离最近。
     */
    private fun labelEditableByRow(els: List<UiElement>): List<UiElement> = els.map { e ->
        if (!e.actions.contains(ElementAction.EDIT) || e.hint != null || e.desc != null || e.text != null) return@map e
        val label = els.asSequence()
            .filter { o -> o !== e && !o.actions.contains(ElementAction.EDIT) && labelText(o) != null }
            .filter { o -> o.bounds.right <= e.bounds.left + 10 && o.bounds.top < e.bounds.bottom && o.bounds.bottom > e.bounds.top }
            .minByOrNull { o -> e.bounds.left - o.bounds.right }
        if (label == null) e else e.copy(hint = labelText(label))
    }

    private fun labelText(e: UiElement) = e.text ?: e.desc ?: e.mergedText

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
            cls.contains("TextView") || !n.text.isNullOrBlank() || !n.desc.isNullOrBlank() -> "Text"
            else -> "View"
        }
    }

    private fun kindOf(n: RawNode) = typeOf(n)

    private fun normalizeLabel(n: RawNode, merged: String?) =
        listOf(n.text, n.desc, n.hint, merged).joinToString("|") { normalizeSpace(it ?: "").lowercase() }

    internal fun normalizeSpace(s: String) =
        s.replace(Regex("\\s+"), " ").replace(Regex("(\\s*,){2,}"), ",").trim()

    internal fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max) + "…(共${s.length}字)"

    private fun mergeTexts(parts: List<String>, max: Int): String {
        val deduped = parts.filterIndexed { i, p -> i == 0 || p != parts[i - 1] }
        return truncate(deduped.joinToString(" "), max)
    }
}
