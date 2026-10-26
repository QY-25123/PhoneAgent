package com.android.phoneagent.agent

import com.android.phoneagent.perception.ElementAction
import com.android.phoneagent.perception.ScreenState
import java.net.URLEncoder

/** 路线任务：目的地必填；出发地默认「当前位置」，出发时间默认「现在」。 */
data class MapsTask(val destination: String, val origin: String?, val departure: String?) {
    fun restatement(): String = "地图：" + (origin?.let { "从「$it」" } ?: "从当前位置") + "到「$destination」，" + (departure?.let { "$it 出发" } ?: "现在出发") + "，查驾车 / 公交 / 步行 / 骑行的耗时"
}

enum class TravelMode(val key: String, val zh: String, val tab: String) {
    DRIVING("driving", "驾车", "Driving"), TRANSIT("transit", "公共交通", "Transit"),
    WALKING("walking", "步行", "Walking"), BICYCLING("bicycling", "骑行", "Bicycling"),
}

/** 一种交通方式的结果；[minutes] 为 null 表示不可用。 */
data class RouteRow(val mode: TravelMode, val minutes: Int?, val durationText: String?, val distance: String?) {
    val available get() = minutes != null
}

object MapsIntent {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    fun url(task: MapsTask, mode: TravelMode = TravelMode.DRIVING): String =
        "https://www.google.com/maps/dir/?api=1" + (task.origin?.let { "&origin=${enc(it)}" } ?: "") +
            "&destination=${enc(task.destination)}&travelmode=${mode.key}"
    fun args(url: String) = arrayOf("-a", "android.intent.action.VIEW", "-p", AppCatalog.MAPS, "-d", url)
}

/** 读 Google 地图路线页：各交通方式的耗时在标签的描述里（「Driving mode: 30 minutes」），距离在展开的路线面板里「(21 mi)」。 */
object MapsReader {
    private val TAB = Regex("^(Driving|Transit|Walking|Bicycling) mode:\\s*(.*)$")
    private val DIST = Regex("\\(\\s*([\\d.,]+)\\s*(mi|km|ft|m)\\s*\\)")
    private val HOURS = Regex("(\\d+)\\s*(?:hours?|hr|h|小时)")
    private val MINS = Regex("(\\d+)\\s*(?:minutes?|mins?|min|分钟|分)")
    private val DAYS = Regex("(\\d+)\\s*(?:days?|天)")

    private fun fix(s: String) = s.replace(' ', ' ').replace(' ', ' ').trim()
    private fun labels(e: com.android.phoneagent.perception.UiElement) = listOfNotNull(e.desc, e.text, e.mergedText).map(::fix)

    fun isMaps(s: ScreenState) = s.packageName == AppCatalog.MAPS

    /** 已经是路线页：有「Start」或「Options」（面板拉开后 Start 可能在屏幕外）、有交通方式标签、没有目的地选择列表 */
    fun isRoutePage(s: ScreenState): Boolean = isMaps(s) &&
        s.elements.none { it.text?.let(::fix) == "Choose destination" || it.text?.let(::fix) == "Choose starting point" } &&
        s.elements.any { e -> labels(e).any { it.startsWith("Driving mode:") } } &&
        s.elements.any { e -> labels(e).any { it == "Start" || it == "Options" || it == "Swap start and destination" } }

    /** 目的地/出发地选择列表里的候选（「Directions to X」） */
    fun candidates(s: ScreenState): List<String> =
        s.elements.flatMap { labels(it) }.filter { it.startsWith("Directions to ") }.map { it.removePrefix("Directions to ") }.distinct()

    /** 解析耗时文字：「1 hour 18 minutes」「30 min」「1 hr 18」→ 分钟；空/无法解析 → null */
    fun parseMinutes(t: String?): Int? {
        val s = fix(t ?: "")
        if (s.isEmpty()) return null
        val d = DAYS.find(s)?.groupValues?.get(1)?.toInt() ?: 0
        val h = HOURS.find(s)?.groupValues?.get(1)?.toInt()
        var m = MINS.find(s)?.groupValues?.get(1)?.toInt()
        // 标签上的简写「1 hr 18」「2 hr 15」：小时后面跟的裸数字是分钟
        if (m == null && h != null) m = Regex("(?:hours?|hr|h)\\s*(\\d+)\\s*$").find(s)?.groupValues?.get(1)?.toInt()
        if (h == null && m == null && d == 0) return null
        return d * 1440 + (h ?: 0) * 60 + (m ?: 0)
    }

    fun durationTexts(s: ScreenState): Map<TravelMode, String> {
        val out = LinkedHashMap<TravelMode, String>()
        for (e in s.elements) for (l in labels(e)) TAB.matchEntire(l)?.let { m ->
            val mode = TravelMode.values().first { it.tab == m.groupValues[1] }
            if (m.groupValues[2].isNotBlank()) out.putIfAbsent(mode, m.groupValues[2])
        }
        return out
    }

    /** 展开的路线面板里第一个「(21 mi)」 */
    fun distance(s: ScreenState): String? =
        s.elements.flatMap { labels(it) }.firstNotNullOfOrNull { DIST.find(it)?.let { m -> "${m.groupValues[1]} ${m.groupValues[2]}" } }

    fun tabIndex(s: ScreenState, mode: TravelMode): Int? =
        s.elements.filter { e -> labels(e).any { it.startsWith("${mode.tab} mode:") } }
            .let { c -> (c.firstOrNull { ElementAction.CLICK in it.actions } ?: c.firstOrNull())?.index }

    /** 底部路线面板的拖动条所在行（「Options」按钮同一行）：从这里往上滑展开面板 */
    fun sheetIndex(s: ScreenState): Int? =
        s.elements.firstOrNull { e -> labels(e).any { it == "Options" } }?.index

    fun noRoute(s: ScreenState) = s.elements.flatMap { labels(it) }.any { Regex("no routes? found|could not calculate|couldn't find a route|找不到路线", RegexOption.IGNORE_CASE).containsMatchIn(it) }

    fun fmtMinutes(m: Int): String = when {
        m < 60 -> "${m} 分钟"
        m % 60 == 0 -> "${m / 60} 小时"
        else -> "${m / 60} 小时 ${m % 60} 分钟"
    }
}

/** 把路线页打开（用链接；失败或遇到目的地候选列表时由模型处理，多个不同的地点会问用户）。已经是路线页就直接完成，不调用模型。 */
class MapsOpenTask(private val t: MapsTask) : LoopTask {
    override val taskText = "在 Google 地图里打开" + (t.origin?.let { "从「$it」" } ?: "从当前位置") + "到「${t.destination}」的路线页（只看，不导航）"
    override val confirmed = "目的地：${t.destination}；出发地：${t.origin ?: "当前位置"}；出发时间：${t.departure ?: "现在"}"
    override val leftovers = listOf("Google 地图可能停在搜索或路线页，没有开始导航")
    override val stepSystem = Prompts.stepSystem(Prompts.MAPS_OPEN_SECTION)
    override val verifySystem = Prompts.VERIFY_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = phone.launchMaps(MapsIntent.url(t))
    override val prelaunchOkNote = "用路线链接在虚拟屏打开了 Google 地图"
    override val prelaunchFailNote = "尝试用路线链接打开 Google 地图"
    override fun alreadyDone(screen: ScreenState) = MapsReader.isRoutePage(screen)
    override val fastClaim = "路线页已打开"
    override fun localVerify(screen: ScreenState): Pair<Boolean, String>? =
        if (MapsReader.isRoutePage(screen)) true to "已在路线页" else false to "还不是路线页（要有各交通方式标签和 Start 按钮）"
}

/** 用户指定了出发时间：在路线页的选项里把出发时间设好（只改这次路线的时间，不改任何设置）。 */
class MapsDepartTask(private val t: MapsTask) : LoopTask {
    override val taskText = "在 Google 地图路线页把出发时间设为「${t.departure}」"
    override val confirmed = "出发时间：${t.departure}（只改这次路线的出发时间）"
    override val leftovers = listOf("Google 地图的出发时间可能没有改成「${t.departure}」")
    override val stepSystem = Prompts.stepSystem(Prompts.MAPS_DEPART_SECTION)
    override val verifySystem = Prompts.VERIFY_SYSTEM
    override suspend fun prelaunch(phone: PhoneController) = ActionOutcome(true, "ok")
    override val prelaunchOkNote = "当前应该在路线页"
    override val prelaunchFailNote = ""
    override fun localVerify(screen: ScreenState): Pair<Boolean, String>? = null
}

interface MapsHost : LoopHost { fun say(text: String) }

sealed interface MapsResult {
    data class Done(val destination: String, val rows: List<RouteRow>, val fastest: TravelMode?, val note: String?) : MapsResult
    data class Failed(val reason: String, val leftovers: List<String>) : MapsResult
}

/**
 * 路线任务（只读）：链接打开路线页 → 依次点驾车/公交/步行/骑行标签，读耗时和距离 → 最后停在最快的方式上，等用户「去查看」。
 * 永远不点「Start」（特权侧硬拦截），不保存、不改设置。
 */
class MapsFlow(
    private val model: ModelClient,
    private val phone: PhoneController,
    private val host: MapsHost,
    private val cfg: LoopConfig = LoopConfig(),
    private val clock: () -> java.time.ZonedDateTime = { java.time.ZonedDateTime.now() },
    private val log: RunLog = NoopRunLog,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private fun loop() = AgentLoop(model, phone, host, cfg, clock, log, sleep)

    suspend fun run(task: MapsTask): MapsResult {
        val open = loop().run(MapsOpenTask(task))
        if (open is LoopResult.Failure) return MapsResult.Failed(open.reason, open.leftovers)
        var note: String? = null
        if (task.departure != null) {
            val r = loop().run(MapsDepartTask(task))
            if (r is LoopResult.Failure) note = "没能把出发时间设成「${task.departure}」，下面是现在出发的结果"
        }
        var screen = phone.perceive()
        if (!MapsReader.isRoutePage(screen.state)) return MapsResult.Failed("没能打开路线页", MapsOpenTask(task).leftovers)
        val durations = MapsReader.durationTexts(screen.state)
        val rows = ArrayList<RouteRow>()
        for (mode in TravelMode.values()) {
            val tab = MapsReader.tabIndex(screen.state, mode)
            if (tab != null) {
                host.onStep(StepInfo(null, "路线页", "切换到${mode.zh}", screen))
                phone.execute(Action.Tap(tab), screen.state)
                sleep(2000)
                screen = phone.perceive()
            }
            // 展开路线面板读距离（读不到就只给耗时）
            var dist = MapsReader.distance(screen.state)
            if (dist == null) MapsReader.sheetIndex(screen.state)?.let { sheet ->
                phone.execute(Action.Scroll(Direction.DOWN, sheet, dragSheet = true), screen.state)
                sleep(800)
                screen = phone.perceive()
                dist = MapsReader.distance(screen.state)
            }
            val text = MapsReader.durationTexts(screen.state)[mode] ?: durations[mode]
            val minutes = MapsReader.parseMinutes(text)
            rows += if (minutes != null) RouteRow(mode, minutes, text, dist) else RouteRow(mode, null, null, null)
        }
        val fastest = rows.filter { it.available }.minByOrNull { it.minutes!! }?.mode
        if (rows.none { it.available }) return MapsResult.Failed("没能读到任何交通方式的耗时", MapsOpenTask(task).leftovers)
        // 停在最快的方式上，用户点「去查看」时看到的就是它
        fastest?.let { f -> runCatching {
            val s = phone.perceive()
            MapsReader.tabIndex(s.state, f)?.let { phone.execute(Action.Tap(it), s.state) }
        } }
        return MapsResult.Done(task.destination, rows, fastest, note)
    }
}
