package com.android.phoneagent.agent

import com.android.phoneagent.perception.Bounds
import com.android.phoneagent.perception.ElementAction
import com.android.phoneagent.perception.PerceptionResult
import com.android.phoneagent.perception.PerceptionTimings
import com.android.phoneagent.perception.ScreenState
import com.android.phoneagent.perception.UiElement
import com.android.phoneagent.perception.WechatChat
import com.android.phoneagent.perception.WechatDraft
import com.android.phoneagent.safety.ConfirmToken
import com.android.phoneagent.safety.SafetyRules
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

private fun el(i: Int, type: String, top: Int, text: String? = null, desc: String? = null, hint: String? = null, actions: Set<ElementAction> = emptySet(), w: Int = 1000, left: Int = 40) =
    UiElement(i, "w$i".padEnd(4, 'x'), type, text, desc, hint, null, null, "android.widget.Button", Bounds(left, top, left + w, top + 100), actions, false, null, true)

private fun screen(pkg: String, els: List<UiElement>) =
    PerceptionResult(ScreenState(2, pkg, "t", 1080, 2400, 0, els, screenshotJpeg = byteArrayOf(1)), "App", PerceptionTimings(0, 1, 1, 1, 1, 1))

private val CLICK = setOf(ElementAction.CLICK)
private val EDIT = setOf(ElementAction.EDIT, ElementAction.CLICK)

/** 合成微信聊天页：标题、聊天记录、输入框（[input]）、有字时才有「Send」。[sent] 为聊天记录里最后一条自己发的消息。 */
fun wechatChat(title: String = "文件传输助手", input: String = "", sent: String? = null, failed: Boolean = false): PerceptionResult {
    val els = ArrayList<UiElement>()
    els += el(1, "Text", 80, text = title, w = 400, left = 340)
    sent?.let { els += el(2, "Text", 800, text = it) }
    if (failed) els += el(3, "ImageView", 800, desc = "Send failed, tap to resend", actions = CLICK)
    els += el(4, "EditText", 2200, text = input.ifEmpty { null }, hint = "", actions = EDIT, w = 700)
    if (input.isNotEmpty()) els += el(5, "Button", 2200, text = "Send", actions = CLICK, w = 150, left = 850)
    return screen(WechatChat.PKG, els)
}

@OptIn(ExperimentalCoroutinesApi::class)
class WechatMapsTest {
    // ---------- 安全规则 ----------
    @Test fun wechatSendButtonIsRecognisedOnlyInWechat() {
        for (l in listOf("Send", "send", "发送", "Send Send")) assertThat(SafetyRules.isWechatSend("com.tencent.mm", l)).isTrue()
        assertThat(SafetyRules.isWechatSend("com.google.android.gm", "Send")).isFalse()
        for (l in listOf("Sending", "Send a message", "More", "")) assertThat(SafetyRules.isWechatSend("com.tencent.mm", l)).isFalse()
    }

    @Test fun mapsStartAndSaveAreForbidden() {
        for (l in listOf("Start", "Start Start", "Navigate", "Save", "Save Save trip", "Save trip", "开始")) assertThat(SafetyRules.isMapsForbidden("com.google.android.apps.maps", l)).isTrue()
        for (l in listOf("Start location, Your location", "Driving mode: 30 minutes", "Transit mode: 1 hour", "Options", "Share")) assertThat(SafetyRules.isMapsForbidden("com.google.android.apps.maps", l)).isFalse()
        assertThat(SafetyRules.isMapsForbidden("com.android.settings", "Start")).isFalse()
    }

    @Test fun wechatHashDependsOnRecipientAndText() {
        val a = ConfirmToken.hashWechat(WechatDraft("文件传输助手", "你好"))
        assertThat(ConfirmToken.hashWechat(WechatDraft("文件传输助手", "你好  "))).isEqualTo(a)
        assertThat(ConfirmToken.hashWechat(WechatDraft("文件传输助手", "你好!"))).isNotEqualTo(a)
        assertThat(ConfirmToken.hashWechat(WechatDraft("小王", "你好"))).isNotEqualTo(a)
    }

    // ---------- 读微信聊天页 ----------
    @Test fun wechatChatIsReadFromTheScreen() {
        val s = wechatChat(input = "测试消息").state
        assertThat(WechatChat.read(s)).isEqualTo(WechatDraft("文件传输助手", "测试消息"))
        assertThat(WechatChat.sendIndex(s)).isEqualTo(5)
        assertThat(WechatChat.sentOk(wechatChat(input = "", sent = "测试消息").state, "测试消息")).isTrue()
        assertThat(WechatChat.sentOk(wechatChat(input = "", sent = "测试消息", failed = true).state, "测试消息")).isFalse()
        assertThat(WechatChat.sentOk(wechatChat(input = "测试消息").state, "测试消息")).isFalse()   // 还在输入框里
    }

    // ---------- 微信流程（不再让用户确认：流程自己布防一次） ----------
    private class Host : WechatHost {
        val said = ArrayList<String>()
        override fun onStep(info: StepInfo) {}
        override suspend fun askUser(question: String, options: List<String>) = "好"
        override fun say(text: String) { said += text }
    }

    private val task = WechatTask("文件传输助手", "测试消息")
    private fun flow(model: ModelClient, phone: FakePhone) = WechatFlow(model, phone, Host(), LoopConfig(), { NOW }, NoopRunLog, sleep = {})
    private val ready = wechatChat(input = "测试消息")
    private val done = wechatChat(input = "", sent = "测试消息")
    private fun sendTaps(p: FakePhone) = p.executed.filterIsInstance<Action.Tap>().filter { it.index == 5 }

    @Test fun sendsWithoutAskingAndArmsExactlyTheReadContent() = runTest {
        lateinit var phone: FakePhone
        phone = FakePhone({ if (sendTaps(phone).isNotEmpty()) done else ready })
        val r = flow(FakeModel(listOf(stepJson("done", success = true, result = "ok"))), phone).run(task) as WechatResult.Sent
        assertThat(phone.wechatArmed).containsExactly(ConfirmToken.hashWechat(WechatDraft("文件传输助手", "测试消息")))
        assertThat(sendTaps(phone)).hasSize(1)
        assertThat(phone.wechatDisarms).isEqualTo(1)
        assertThat(phone.opened).contains("wechat")
        assertThat(r.draft.text).isEqualTo("测试消息")
    }

    @Test fun refusedArmingMeansNothingIsSent() = runTest {
        val phone = FakePhone({ ready }).also { it.wechatArmResult = { false } }
        val r = flow(FakeModel(listOf(stepJson("done", success = true, result = "ok"))), phone).run(task)
        assertThat(r).isInstanceOf(WechatResult.Failed::class.java)
        assertThat(sendTaps(phone)).isEmpty()
    }

    @Test fun blockedSendIsReportedAndNotRetried() = runTest {
        val phone = FakePhone({ ready }, outcome = { _, a -> if (a is Action.Tap && a.index == 5) ActionOutcome(false, "被拦截", blocked = true) else ActionOutcome(true, "ok") })
        val r = flow(FakeModel(listOf(stepJson("done", success = true, result = "ok"))), phone).run(task)
        assertThat(r).isInstanceOf(WechatResult.Failed::class.java)
        assertThat(sendTaps(phone)).hasSize(1)
    }

    @Test fun redExclamationAfterSendIsReportedAsFailure() = runTest {
        val failed = wechatChat(input = "", sent = "测试消息", failed = true)
        lateinit var phone: FakePhone
        phone = FakePhone({ if (sendTaps(phone).isNotEmpty()) failed else ready })
        val r = flow(FakeModel(listOf(stepJson("done", success = true, result = "ok"))), phone).run(task)
        assertThat(r).isInstanceOf(WechatResult.Failed::class.java)
    }

    // ---------- 地图 ----------
    @Test fun durationsParse() {
        assertThat(MapsReader.parseMinutes("30 minutes")).isEqualTo(30)
        assertThat(MapsReader.parseMinutes("1 hour 18 minutes")).isEqualTo(78)
        assertThat(MapsReader.parseMinutes("7 hours 12 minutes")).isEqualTo(432)
        assertThat(MapsReader.parseMinutes("1 hr 18")).isEqualTo(78)
        assertThat(MapsReader.parseMinutes("2 hr")).isEqualTo(120)
        assertThat(MapsReader.parseMinutes("45 min")).isEqualTo(45)
        assertThat(MapsReader.parseMinutes("")).isNull()
        assertThat(MapsReader.fmtMinutes(78)).isEqualTo("1 小时 18 分钟")
    }

    private fun mapsRoute(bicycling: String = "2 hours 15 minutes", dist: String? = "(21 mi)"): PerceptionResult {
        val els = arrayListOf(
            el(1, "Button", 100, desc = "Driving mode: 30 minutes", actions = CLICK), el(2, "Button", 100, desc = "Transit mode: 1 hour 18 minutes", actions = CLICK),
            el(3, "Button", 100, desc = "Walking mode: 7 hours 12 minutes", actions = CLICK), el(4, "Button", 100, desc = "Bicycling mode: $bicycling", actions = CLICK),
            el(5, "Button", 2000, text = "Start", desc = "Start", actions = CLICK), el(6, "Button", 1900, desc = "Options", actions = CLICK),
        )
        if (dist != null) els += el(7, "Text", 1500, text = dist)
        return screen(AppCatalog.MAPS, els)
    }

    private val chooser = screen(AppCatalog.MAPS, listOf(
        el(1, "Text", 100, text = "Choose destination"),
        el(2, "Button", 300, desc = "Directions to Springfield, IL", actions = CLICK), el(3, "Button", 400, desc = "Directions to Springfield, MO", actions = CLICK)))

    @Test fun routePageAndCandidatesAreDetected() {
        assertThat(MapsReader.isRoutePage(mapsRoute().state)).isTrue()
        assertThat(MapsReader.isRoutePage(chooser.state)).isFalse()
        assertThat(MapsReader.candidates(chooser.state)).containsExactly("Springfield, IL", "Springfield, MO").inOrder()
        assertThat(MapsReader.distance(mapsRoute().state)).isEqualTo("21 mi")
        assertThat(MapsReader.durationTexts(mapsRoute(bicycling = "").state)).doesNotContainKey(TravelMode.BICYCLING)
    }

    private class MHost : MapsHost {
        val asked = ArrayList<String>()
        override fun onStep(info: StepInfo) {}
        override suspend fun askUser(question: String, options: List<String>): String { asked += question; return "Springfield, IL" }
        override fun say(text: String) {}
    }

    @Test fun mapsFlowReadsAllModesMarksFastestAndNeverTapsStart() = runTest {
        val phone = FakePhone({ mapsRoute() })
        val r = MapsFlow(FakeModel(listOf("{}")), phone, MHost(), LoopConfig(), { NOW }, NoopRunLog, sleep = {}).run(MapsTask("旧金山机场", null, null)) as MapsResult.Done
        assertThat(phone.mapsUrls.single()).contains("destination=%E6%97%A7%E9%87%91%E5%B1%B1%E6%9C%BA%E5%9C%BA")
        assertThat(phone.mapsUrls.single()).doesNotContain("origin=")
        assertThat(r.rows.map { it.mode }).containsExactly(TravelMode.DRIVING, TravelMode.TRANSIT, TravelMode.WALKING, TravelMode.BICYCLING).inOrder()
        assertThat(r.fastest).isEqualTo(TravelMode.DRIVING)
        assertThat(r.rows.first().distance).isEqualTo("21 mi")
        assertThat(phone.executed.filterIsInstance<Action.Tap>().map { it.index }).doesNotContain(5)   // 5 是 Start
    }

    @Test fun unavailableModeIsMarked() = runTest {
        val phone = FakePhone({ mapsRoute(bicycling = "") })
        val r = MapsFlow(FakeModel(listOf("{}")), phone, MHost(), LoopConfig(), { NOW }, NoopRunLog, sleep = {}).run(MapsTask("x", "y", null)) as MapsResult.Done
        assertThat(r.rows.last().available).isFalse()
        assertThat(phone.mapsUrls.single()).contains("origin=y")
    }

    @Test fun ambiguousDestinationAsksTheUser() = runTest {
        var n = 0
        val phone = FakePhone({ if (n++ < 2) chooser else mapsRoute() })
        val host = MHost()
        val model = FakeModel(listOf(stepJson("ask_user", question = "哪个 Springfield？", options = listOf("Springfield, IL", "Springfield, MO")), stepJson("tap", index = 2), stepJson("done", success = true, result = "ok")))
        val r = MapsFlow(model, phone, host, LoopConfig(), { NOW }, NoopRunLog, sleep = {}).run(MapsTask("Springfield", null, null))
        assertThat(host.asked).hasSize(1)
        assertThat(r).isInstanceOf(MapsResult.Done::class.java)
    }

    // ---------- 澄清 ----------
    private fun clarifier(reply: String) = Clarifier(FakeModel(listOf(reply)), { NOW })

    @Test fun clarifyWechatNeedsContactAndMessage() = runTest {
        val ready = clarifier(clarifyJson("wechat", wechatContact = "文件传输助手", wechatMessage = "测试消息")).clarify(listOf(DialogTurn(true, "x"))) as ClarifyOutcome.ReadyWechat
        assertThat(ready.task).isEqualTo(WechatTask("文件传输助手", "测试消息"))
        val ask = clarifier(clarifyJson("wechat", wechatContact = "小王", question = "想给小王发什么？")).clarify(listOf(DialogTurn(true, "x"))) as ClarifyOutcome.Ask
        assertThat(ask.question).contains("小王")
    }

    @Test fun clarifyMapsOnlyNeedsDestination() = runTest {
        val r = clarifier(clarifyJson("maps", mapsDestination = "旧金山机场")).clarify(listOf(DialogTurn(true, "x"))) as ClarifyOutcome.ReadyMaps
        assertThat(r.task).isEqualTo(MapsTask("旧金山机场", null, null))
        assertThat(r.restatement).contains("当前位置")
        assertThat(clarifier(clarifyJson("maps")).clarify(listOf(DialogTurn(true, "x")))).isInstanceOf(ClarifyOutcome.Ask::class.java)
        val full = clarifier(clarifyJson("maps", mapsDestination = "渡轮大厦", mapsOrigin = "斯坦福大学")).clarify(listOf(DialogTurn(true, "x"))) as ClarifyOutcome.ReadyMaps
        assertThat(full.task.origin).isEqualTo("斯坦福大学")
    }
}
