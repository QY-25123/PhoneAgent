package com.android.phoneagent.task

import com.android.phoneagent.agent.Action
import com.android.phoneagent.agent.ActionOutcome
import com.android.phoneagent.agent.AppCatalog
import com.android.phoneagent.agent.CalendarTask
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

    override suspend fun execute(action: Action, screen: ScreenState): ActionOutcome {
        val out = when (action) {
            is Action.OpenApp -> {
                val pkg = AppCatalog.resolve(action.app) ?: return ActionOutcome(false, "不允许打开「${action.app}」：只能打开日历、Gmail、Amazon")
                run("打开 $pkg") { Agent.withPriv("打开 $pkg", guarded = true) { it.launchApp(displayId, pkg) }; ActionOutcome(true, "ok") }
            }
            is Action.Tap -> {
                val e = screen.elements.firstOrNull { it.index == action.index } ?: return ActionOutcome(false, "界面上没有编号 ${action.index} 的元素")
                val x = e.bounds.centerX.toFloat(); val y = e.bounds.centerY.toFloat()
                run("点击 #${action.index}") {
                    // 无障碍语义点击优先（不产生触摸事件）：按节点引用精确点击，界面变了/点不了再用坐标点击
                    val b = e.bounds
                    val semantic = e.nodeIndex >= 0 && Agent.withPriv("点击 #${action.index}", guarded = true) {
                        it.clickNode(displayId, e.windowId, e.nodeIndex, b.left, b.top, b.right, b.bottom)
                    }
                    if (!semantic) Agent.withPriv("点击 #${action.index}", guarded = true) { it.tap(displayId, x, y) }
                    ActionOutcome(true, if (semantic) "语义点击" else "坐标点击")
                }
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
                val d = 500f
                val (x1, y1, x2, y2) = when (action.direction) {   // 手指的轨迹与内容移动方向相反
                    Direction.DOWN -> listOf(cx, cy + d, cx, cy - d)
                    Direction.UP -> listOf(cx, cy - d, cx, cy + d)
                    Direction.LEFT -> listOf(cx - d, cy, cx + d, cy)
                    Direction.RIGHT -> listOf(cx + d, cy, cx - d, cy)
                }
                run("滚动") { Agent.withPriv("滚动", guarded = true) { it.swipe(displayId, x1, y1, x2, y2, 350) }; ActionOutcome(true, "ok") }
            }
            Action.Back -> run("返回") { Agent.withPriv("返回", guarded = true) { it.back(displayId) }; ActionOutcome(true, "ok") }
            Action.PressEnter -> run("回车") { Agent.withPriv("回车", guarded = true) { it.pressKey(displayId, android.view.KeyEvent.KEYCODE_ENTER) }; ActionOutcome(true, "ok") }
            is Action.Wait, is Action.AskUser, is Action.Done -> ActionOutcome(true, "不需要执行")
        }
        if (out.ok) delay(400)   // 给界面一点反应时间，之后的感知还会等它稳定
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
}
