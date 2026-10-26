package com.android.phoneagent.task

import com.android.phoneagent.agent.Action
import com.android.phoneagent.agent.ActionOutcome
import com.android.phoneagent.agent.AppCatalog
import com.android.phoneagent.agent.CalendarTask
import com.android.phoneagent.agent.CartChange
import com.android.phoneagent.agent.EmailTask
import com.android.phoneagent.agent.MailIntent
import com.android.phoneagent.agent.Direction
import com.android.phoneagent.agent.PhoneController
import com.android.phoneagent.perception.ElementAction
import com.android.phoneagent.perception.Perception
import com.android.phoneagent.perception.PerceptionResult
import com.android.phoneagent.perception.PerceiveOptions
import com.android.phoneagent.perception.ScreenState
import kotlinx.coroutines.delay
import java.time.ZoneId

/**
 * 真实的手机控制：感知走 [Perception]，动作经 UserService（硬拦截 SafetyGuard 在特权侧执行）和 [UserActivityGuard]。
 * tap 优先无障碍语义点击，失败再坐标点击；type 按元素编号定位到具体节点；back 只用带 displayId 的按键事件。
 */
class AgentPhoneController(private val displayId: Int, private val zone: ZoneId = ZoneId.systemDefault()) : PhoneController {
    private val perception = Perception(AgentPerceptionSource(displayId))

    /** Mark the target on the live view, then give the viewer a moment to see it before acting (demo pacing). */
    private suspend fun markTarget(x: Float, y: Float, screen: ScreenState) {
        Agent.lastTap = Agent.TapMark((x / screen.screenWidth).coerceIn(0f, 1f), (y / screen.screenHeight).coerceIn(0f, 1f), System.currentTimeMillis())
        delay(BEFORE_ACTION_MS)
    }

    override suspend fun perceive(): PerceptionResult = perception.perceive(displayId, PerceiveOptions())

    override suspend fun launchCalendarInsert(task: CalendarTask): ActionOutcome = run("日历插入事项") {
        Agent.withPriv("日历插入事项", guarded = true) {
            it.launchIntent(
                displayId,
                arrayOf(
                    "-a", "android.intent.action.INSERT", "-p", AppCatalog.CALENDAR,
                    "-d", "content://com.android.calendar/events",
                    "--es", "title", task.title,
                    "--el", "beginTime", task.beginMillis(zone).toString(),
                    "--el", "endTime", task.endMillis(zone).toString(),
                ),
            )
        }
        ActionOutcome(true, "ok")
    }

    override suspend fun launchEmailCompose(task: EmailTask): ActionOutcome = run("Gmail 写邮件") {
        // 只有给了邮箱地址才预填收件人；只有名字时留空，由模型在界面上从联系人建议里选
        val to = if (task.hasAddress) task.recipient else ""
        Agent.withPriv("Gmail 写邮件", guarded = true) { it.launchIntent(displayId, MailIntent.args(to, task.subject, task.body)) }
        ActionOutcome(true, "ok")
    }

    override suspend fun launchAmazon(url: String): ActionOutcome = run("打开 Amazon 链接") {
        Agent.withPriv("打开 Amazon 链接", guarded = true) { it.launchIntent(displayId, com.android.phoneagent.agent.AmazonIntent.args(url)) }
        ActionOutcome(true, "ok")
    }

    override suspend fun launchMaps(url: String): ActionOutcome = run("打开地图路线") {
        Agent.withPriv("打开地图路线", guarded = true) { it.launchIntent(displayId, com.android.phoneagent.agent.MapsIntent.args(url)) }
        delay(3500)   // 地图加载路线要几秒，太早读到的是地图主页
        ActionOutcome(true, "ok")
    }

    override suspend fun armWechatSend(hash: String): Boolean = try {
        Agent.withPriv("布防微信发送") { it.armWechatSendHash(displayId, hash) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { false }

    override suspend fun disarmWechatSend() { Agent.withPriv("撤销微信布防") { it.disarmWechatSend() } }

    override suspend fun openApp(app: String): ActionOutcome {
        val pkg = AppCatalog.resolve(app) ?: return ActionOutcome(false, "不允许打开「$app」：只能打开日历、Gmail、Amazon、微信、地图")
        return run("打开 $pkg") {
            Agent.withPriv("打开 $pkg", guarded = true) { it.launchApp(displayId, pkg) }
            // WeChat cold-starts slowly on a virtual display (a full-screen splash picture for 5-15 s): give it a head start
            if (pkg == AppCatalog.WECHAT) delay(WECHAT_COLD_START_MS)
            ActionOutcome(true, "ok")
        }
    }

    override suspend fun armGmailSend(hash: String): Boolean = try {
        Agent.withPriv("布防发送") { it.armGmailSendHash(displayId, hash) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { false }

    override suspend fun armCartChanges(changes: List<CartChange>) {
        val spec = changes.joinToString("\n") { "${it.action}\t${it.item}\t${it.quantity ?: ""}" }
        Agent.withPriv("布防购物车改动") { it.armCartChanges(spec) }
    }

    override suspend fun disarmCartChanges() { Agent.withPriv("撤销购物车布防") { it.disarmCartChanges() } }

    override suspend fun disarmGmailSend() { Agent.withPriv("撤销布防") { it.disarmGmailSend() } }

    override suspend fun execute(action: Action, screen: ScreenState): ActionOutcome {
        val out = when (action) {
            is Action.OpenApp -> {
                val pkg = AppCatalog.resolve(action.app) ?: return ActionOutcome(false, "不允许打开「${action.app}」：只能打开日历、Gmail、Amazon、微信、地图")
                run("打开 $pkg") { Agent.withPriv("打开 $pkg", guarded = true) { it.launchApp(displayId, pkg) }; ActionOutcome(true, "ok") }
            }
            is Action.Tap -> {
                val e = screen.elements.firstOrNull { it.index == action.index } ?: return ActionOutcome(false, "界面上没有编号 ${action.index} 的元素")
                val x = e.bounds.centerX.toFloat(); val y = e.bounds.centerY.toFloat()
                markTarget(x, y, screen)
                run("点击 #${action.index}") {
                    // 无障碍语义点击优先（不产生触摸事件）：按节点引用精确点击，界面变了/点不了再用坐标点击
                    val b = e.bounds
                    // Amazon 的页面是 WebView：无障碍点击常常「成功」但页面没反应（实测 Proceed to checkout），WebView 里直接用触摸点击
                    val semantic = screen.packageName != AppCatalog.AMAZON && e.nodeIndex >= 0 && Agent.withPriv("点击 #${action.index}", guarded = true) {
                        it.clickNode(displayId, e.windowId, e.nodeIndex, b.left, b.top, b.right, b.bottom)
                    }
                    if (!semantic) Agent.withPriv("点击 #${action.index}", guarded = true) { it.tap(displayId, x, y) }
                    ActionOutcome(true, if (semantic) "语义点击" else "坐标点击")
                }
            }
            is Action.TapXY -> {
                val x = action.x / 1000f * screen.screenWidth; val y = action.y / 1000f * screen.screenHeight
                markTarget(x, y, screen)
                run("点击 (${action.x},${action.y})") { Agent.withPriv("坐标点击", guarded = true) { it.tap(displayId, x, y) }; ActionOutcome(true, "坐标点击") }
            }
            is Action.Paste -> run("粘贴") {
                // 带坐标：先点那个位置（输入框）让它聚焦，再粘贴
                if (action.x != null && action.y != null) {
                    val px = action.x / 1000f * screen.screenWidth; val py = action.y / 1000f * screen.screenHeight
                    markTarget(px, py, screen)
                    Agent.withPriv("点击输入框", guarded = true) { it.tap(displayId, px, py) }
                    delay(500)
                }
                val ok = Agent.withPriv("粘贴", guarded = true) { it.pasteText(displayId, action.text) }
                if (ok) ActionOutcome(true, "粘贴") else ActionOutcome(false, "粘贴失败")
            }
            is Action.Type -> {
                val e = screen.elements.firstOrNull { it.index == action.index } ?: return ActionOutcome(false, "界面上没有编号 ${action.index} 的元素")
                if (ElementAction.EDIT !in e.actions) return ActionOutcome(false, "#${action.index} 不是输入框（${e.type}），请先点击打开输入框或选择别的元素")
                run("输入 #${action.index}") {
                    val b = e.bounds
                    // 必须写到这个编号对应的节点上（不依赖当前焦点）；节点引用失效时退回「坐标处的可编辑节点」
                    val ok = Agent.withPriv("输入", guarded = true) {
                        (e.nodeIndex >= 0 && it.setTextNode(displayId, e.windowId, e.nodeIndex, b.left, b.top, b.right, b.bottom, action.text)) ||
                            it.setTextAt(displayId, b.centerX.toFloat(), b.centerY.toFloat(), action.text)
                    }
                    if (ok) ActionOutcome(true, "ok") else ActionOutcome(false, "往 #${action.index} 写入失败（该处没有可编辑的节点）")
                }
            }
            is Action.Scroll -> {
                val e = action.index?.let { i -> screen.elements.firstOrNull { it.index == i } }
                val cx = (e?.bounds?.centerX ?: screen.screenWidth / 2).toFloat()
                val cy = (e?.bounds?.centerY ?: screen.screenHeight / 2).toFloat()
                if (action.dragSheet && e != null) {
                    // 拉开底部面板：从面板标题行的左侧空白处往上拖（起点在按钮或标签条上不会拖动面板）
                    val sx = screen.screenWidth * 0.28f
                    return run("拖动面板") { Agent.withPriv("拖动面板", guarded = true) { it.swipe(displayId, sx, cy, sx, (cy - 1300f).coerceAtLeast(120f), 500) }; ActionOutcome(true, "ok") }
                        .also { if (it.ok) delay(250) }
                }
                val d = 500f
                val maxY = screen.screenHeight - 40f   // 起点落在屏幕外（例如从底部面板的拖动条往上滑）时压回屏幕内
                val (x1, y1, x2, y2) = when (action.direction) {   // 手指的轨迹与内容移动方向相反
                    Direction.DOWN -> listOf(cx, (cy + d).coerceAtMost(maxY), cx, cy - d)
                    Direction.UP -> listOf(cx, cy - d, cx, (cy + d).coerceAtMost(maxY))
                    Direction.LEFT -> listOf(cx - d, cy, cx + d, cy)
                    Direction.RIGHT -> listOf(cx + d, cy, cx - d, cy)
                }
                run("滚动") { Agent.withPriv("滚动", guarded = true) { it.swipe(displayId, x1, y1, x2, y2, 350) }; ActionOutcome(true, "ok") }
            }
            Action.Back -> run("返回") { Agent.withPriv("返回", guarded = true) { it.back(displayId) }; ActionOutcome(true, "ok") }
            Action.PressEnter -> run("回车") { Agent.withPriv("回车", guarded = true) { it.pressKey(displayId, android.view.KeyEvent.KEYCODE_ENTER) }; ActionOutcome(true, "ok") }
            is Action.Wait, is Action.AskUser, is Action.Done -> ActionOutcome(true, "不需要执行")
        }
        if (out.ok) delay(AFTER_ACTION_MS)   // give the screen time to react (and the viewer time to follow); the next perception still waits for it to settle
        return out
    }

    private suspend fun run(what: String, block: suspend () -> ActionOutcome): ActionOutcome = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        val m = e.message ?: e.toString()
        // 硬拦截由 UserService 抛出 SecurityException("BLOCKED ...")：不执行，也不重试
        if (m.contains("BLOCKED")) ActionOutcome(false, m.substringAfter("BLOCKED").trim(), blocked = true)
        else ActionOutcome(false, "$what 失败：$m")
    }

    private companion object {
        /** Demo pacing: show the target for a moment before acting, and pause after acting. */
        const val BEFORE_ACTION_MS = 300L
        const val AFTER_ACTION_MS = 900L
        const val WECHAT_COLD_START_MS = 6000L
    }
}
