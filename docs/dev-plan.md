# PhoneAgent 开发计划（功能阶段）

| 项 | 内容 |
|---|---|
| 版本 | v0.1 |
| 日期 | 2026-10-23 |
| 依据 | `docs/PRD.md` v0.6、`docs/spike-summary.md`、`docs/p0-verification.md` |
| 前置 | 界面（v0.5）已确认；P0 技术验证全部通过 |

## 0. 工作约定

- **每个里程碑完成的条件**：单元测试全绿（`./gradlew testDebugUnitTest`）+ `scripts/regress.sh` 全部 PASS + 本里程碑的验收项逐条有结果记录。任何一项不满足不进入下一个里程碑。
- 安全规则**先写测试再实现**（M3）。
- 涉及真实模型的测试（集成测试）数量少而精，单独标注，不放进默认的单元测试；每次运行前说明预计花费。
- 红线不变：**绝不在 Amazon 下单、不点 Buy Now / Place your order / Try Prime / Join Prime**；测试邮件只发给 `qijie_jerry@163.com`；不删除用户已有数据；含个人信息的截图和界面转储不提交。
- API Key 从 `local.properties` 的 `CLAUDE_API_KEY` 注入 BuildConfig，不进代码和 Git（M0）。发布版的 Key 由用户在设置里输入，存 Keystore（PRD NFR-KEY-1），在 M5 实现。
- 测试时间要短：每个里程碑的真机/模拟器验收控制在一个短脚本内，优先保证「可用、流畅」。
- 提交信息：`mN: 简述`；不擅自 push。

## 模块与依赖（M0 落地）

```
ui ──▶ task ──▶ agent ──▶ perception ──▶ privileged
                  │  └──▶ safety ──────────▶ privileged
                  └──▶ verification ─▶ perception
```
- `ui` 只依赖 `task` 暴露的状态（`StateFlow<TaskState>` 和少量命令），不直接调用下层。
- `agent` 不直接碰 `privileged`：经 `perception`（读）和 `safety`（所有动作的必经检查）。
- `privileged` 不依赖其他业务包。

---

## M0 整理结构（本步，见下方「第 0 步计划」）
- **目标**：不加功能，按模块重新组织代码，建立单元测试、API Key 注入，更新 CLAUDE.md。
- **涉及模块**：全部（只移动/重命名），新增接口定义。
- **验收**：①构建通过；②`regress.sh` 三个脚本全 PASS；③示例单元测试运行通过；④`CLAUDE_API_KEY` 在 BuildConfig 中可读且 `git grep` 找不到 Key；⑤UI 点击流程与整理前一致（假数据流程）。
- **测试**：示例单元测试（证明环境可用）；`regress.sh`。

## M1 感知
- **目标**：读取虚拟屏的界面树和截图，生成**带编号的元素列表**和**标注截图**，供模型使用。
- **涉及模块**：`perception`（新）、`privileged`（`dumpTree`、截图）。
- **内容**：界面树解析为 `ScreenState`（元素：编号、文字、描述、resource-id、类名、可点击/可编辑/可滚动、边界、所属窗口）；过滤不可见/重复/无意义节点；在截图上画编号框；输出给模型的精简文本。WebView 内容有加载延迟：提供「等界面稳定」（连续两次相同才返回，有超时）。
- **验收**：在虚拟屏打开 Gmail、日历、Amazon 购物车页，各生成一份 `ScreenState`：关键元素（发送按钮、标题输入框、数量文字）能被找到且编号稳定；标注截图肉眼可核对；一次感知耗时记录下来（目标 ≤1.5 秒，未达标写明）。
- **测试**：单元测试——用保存的界面树 XML 样例（已脱敏，放 `app/src/test/resources`）测解析、过滤、编号、文本输出；集成脚本——三个 App 各一次。

## M2 Agent（分两部分）
**M2a 任务澄清**（PRD 6.9）
- **目标**：对话输入 → 结构化判断（任务类型/已知/缺失/追问/复述/不支持）→ 追问或开始。
- **涉及模块**：`agent`（`Clarifier`、`ClaudeClient`）。接入 `claude-sonnet-5-5`，用结构化输出（`output_config.format` 的 JSON schema）；Android 上用 Anthropic 的 Java SDK（Kotlin 可用）；Key 来自 BuildConfig。
- **验收**：PRD 6.9 的六种情形全部通过；追问一次问全；相对日期用手机当前日期换算；解析失败时的降级话术；澄清不碰手机。
- **测试**：单元测试（假模型实现，覆盖六种情形 + 结构非法 + 轮数上限 + 日期换算）；少量真实模型集成测试（每种情形 1～2 条，标注花费）。

**M2b 执行循环**（PRD 6.1、6.2）
- **目标**：观察→决策→执行→确认效果的循环；定义动作集；步数上限 30、模型调用上限 60；**T1 日历任务跑通**。
- **涉及模块**：`agent`（`ActionLoop`、`Action`、提示词）、`perception`、`privileged`、`task`（步数、记录）。
- **验收**：「周三 15:00 加『和 Alice 评审方案』」（日期换成具体日期）在虚拟屏上创建出事项，**暂不要求完成验证**（M4）；循环遵守步数/调用数上限；停止按钮在一步之内生效并清理虚拟屏；每步有「看到/做了/效果」记录。
- **测试**：单元测试——动作解析与校验（非法动作拒绝）、步数口径、上限触发、停止；用录制的 `ScreenState` 序列 + 假模型做循环回放测试；集成：T1 真实跑 3 次，记录步数和耗时。

## M3 安全
- **目标**：把 PRD 6.7 的规则做成**不依赖模型**的硬拦截，并实现 Gmail 发送确认和 Amazon 交接。
- **涉及模块**：`safety`（`SafetyGuard`、`UserActivityGuard`、`ConfirmToken`）、`agent`（动作必经 `safety`）、`task`。
- **顺序**：**先写测试，再实现**。
  1. 拦截规则测试（表驱动）：**购物车修改（FR-SAFE-14）**——无令牌时删除/数量加减/数量输入/「稍后购买」全部拦截，有令牌时只放行清单内的那几项；Buy Now / Place your order / 提交订单 / 立即购买 / `buy-now-button`、`placeOrder` 等 resource-id；Try Prime / Join Prime；删除类；密码/验证码输入框；Gmail 发送按钮（无令牌）；文字、描述、resource-id 三种匹配；页面级锁（提交订单页）。
  2. 确认令牌测试（`ConfirmToken` 通用：Gmail 与购物车共用一套）：令牌绑定「收件人 + 主题 + 正文」的哈希，或购物车的「改动清单（商品标识、动作、目标数量）」，一次性；购物车按项消耗，购物车内容与确认时不一致整体作废；内容变了令牌作废；过期/重复使用无效；未确认不可放行。
  3. 实现并通过。
- **验收**：⓪购物车修改（PRD AC-T3-11～16）：确认卡片逐项列出改动，确认前购物车不动，只放行清单内的改动，完成验证购物车与清单一致，`Save for later` 被拦截，agent 不主动修改；①Gmail：草稿填好后出确认卡片，展示的是从草稿**实际读到**的内容；点「发送」后放行一次，且只对这封邮件；点「修改」后旧令牌作废；点「取消」丢弃本任务创建的草稿；②Amazon：到提交订单页停下，交接给用户，agent 无法点击 Place your order（用模型被诱导点击的测试用例证明被拦截）；③用户在主屏的操作不受影响（`regress.sh`）。
- **测试**：上述单元测试；`regress.sh`；Gmail 发送集成测试只发给 `qijie_jerry@163.com`；Amazon 只到结算页，不下单。**购物车修改的集成测试：只对测试时自己加入的商品做改动**（先加入一个测试商品，再改数量、再删除它），用户购物车里已有的那件商品**绝不触碰**，不去结算、不下单。

## M4 完成验证
- **目标**：任务完成与否由**独立的验证步骤**判断（PRD 6.3），不信任执行循环自己说「做完了」。
- **涉及模块**：`verification`、`perception`。
- **内容**：T3 购物车修改：重新读购物车，商品和数量与确认清单一致，清单之外保持不变（T3-R12）；T1 在日历列表读取标题和起止时间逐项比对；T2 等撤销发送时间后在「已发送」找到该邮件，核对收件人和主题；T3 在购物车页核对商品与数量（`Quantity is N`），在提交订单页核对商品名与小计，且「Place your order」可见；比对失败的处理（修改本任务创建的内容后重验 / 判失败并写明期望 vs 实际和残留）。
- **验收**：三个任务各一次正向通过；各构造一次负向（例如故意改错时间、收件人）能判失败；失败说明含残留内容（FR-ERR-6）。
- **测试**：单元测试——比对逻辑（含时区/12 小时制/大小写/空白）、失败说明生成；集成：三任务各 1 正 1 负。

## M5 界面接真实数据 + 悬浮小窗 + 设计调整
- **目标**：用真实的 `task` 状态替换假数据；小窗接真实状态；处理 `docs/design/backlog.md` 的 6 条调整。
- **涉及模块**：`ui`、`task`、`FloatingWindow`（含购物车修改确认卡片，PRD 7.3.2a）；设置（API Key 输入与 Keystore 存储、「用户打字时暂停」开关）。
- **验收**：对话里的追问/复述/实时画面卡片/确认卡片/结果消息全部来自真实流程；小窗状态与 PRD 7.4 状态表一致；从小窗点「去回答 / 去确认」定位到对应卡片；backlog 6 条处理完并重新渲染截图。
- **测试**：`task` 状态机单元测试（状态转换表）；UI 用 Compose 测试覆盖关键卡片（确认卡片的按钮、选项按钮、示例点击只填入不发送）；手动走查清单。

## M6 三任务端到端打磨
- **目标**：T1、T2、T3 从对话输入到结果消息，完整、流畅、可重复。
- **内容**：冲突处理（开始前等待 / 运行中中断 + 重试）、重试防重复检查、错误说明、通知、耗时与步数优化、提示词打磨。
- **验收**：每个任务连续 5 次成功率记录；PRD 5.x 的验收用例（AC-T1-1…、AC-T2-…、AC-T3-…）逐条有结果；`regress.sh` 全绿。
- **测试**：任务级验收脚本（新增，放 `scripts/e2e/`）；时间控制在短脚本内。

---

# 第 0 步（M0）计划 —— 待确认后执行

## 0.1 包结构

均为包，不拆 Gradle 模块。根包 `com.android.phoneagent`：

| 包 | 内容 | 来源 |
|---|---|---|
| `privileged` | `PrivilegedService`、`VirtualDisplayHolder`、`InputInjector`、`UiAutomationHelper`、`FakeContext`、`DisplayTasks`、`MainScreenState`、AIDL 客户端封装 | 现 `priv/` |
| `perception` | 空壳 + `ScreenState` 等数据结构（M1 实现） | 新 |
| `agent` | 空壳 + `Action`（M2 实现） | 新 |
| `safety` | `SafetyGuard`、`UserActivityGuard` | 现 `priv/SafetyGuard`、根包 `UserActivityGuard` |
| `verification` | 空壳 + 结果数据结构 | 新 |
| `task` | `TaskState`、`TaskRepository`（对 UI 暴露状态）；M0 里先包一层现有的 `Agent` 单例 | 新 / 现 `Agent.kt` 拆分 |
| `ui` | 保持现状；`ChatController` 暂用假数据，M5 切换 | 现有 |
| 根包 | `MainActivity`、`AgentService`、`FloatingWindow`（M5 再归位） | 现有 |

**注意**：`priv/` 里的 `SafetyGuard` 运行在 UserService 进程（shell uid）内。移到 `safety` 包后，类仍要被 UserService 加载。M0 做法：包名改变但仍在同一个 APK/classpath，AIDL 接口不变；**不改变它在哪个进程里运行**。若某个类必须同时被两个进程用，放进 `safety`，且不引用 Android 应用层 API。移动文件会改类名，UserService 的启动参数里有类名，需同步修改。这是 M0 唯一有风险的地方，改完立刻跑 `regress.sh`。

## 0.2 模块之间的接口与数据结构（只定义，M1 起使用）

```kotlin
// perception
data class ScreenState(
    val displayId: Int, val packageName: String?, val timestampMs: Long,
    val elements: List<UiElement>, val screenshot: ByteArray?)
data class UiElement(
    val index: Int, val text: String?, val desc: String?, val resourceId: String?,
    val className: String, val bounds: Rect, val clickable: Boolean,
    val editable: Boolean, val scrollable: Boolean, val checked: Boolean?)

// agent
sealed interface Action {
    data class Launch(val pkgOrComponent: String) : Action
    data class Tap(val index: Int) : Action
    data class Type(val index: Int, val text: String) : Action
    data class Scroll(val index: Int?, val direction: Direction) : Action
    data object Back : Action
    data class Wait(val ms: Long) : Action
    data class Ask(val question: String, val options: List<String>) : Action
    data class Finish(val claim: String) : Action      // 只是「自称完成」，是否完成由 verification 判断
}

// task
sealed interface TaskState {
    data object Idle : TaskState
    data class Clarifying(val question: String, val options: List<String>) : TaskState
    data class Running(val step: Int, val maxSteps: Int, val log: List<StepRecord>) : TaskState
    data class WaitingUser(val ask: Ask) : TaskState          // 提问或 T2 确认
    data class Conflict(val app: String, val interrupted: Boolean) : TaskState
    data class Done(val evidence: Evidence) : TaskState
    data class Failed(val reason: String, val leftovers: List<String>) : TaskState
    data class Stopped(val reason: String) : TaskState
    data class HandedOff(val note: String) : TaskState
}
```
- `Action` 用元素**编号**而不是坐标，坐标由 `perception` 的当前 `ScreenState` 换算；`safety` 在换算后的元素上做拦截检查（文字/描述/resource-id）。
- UI 只订阅 `StateFlow<TaskState>`，命令有 `submit(text)`、`answer(...)`、`confirmSend()/edit()/cancel()`、`stop()`、`retry()`。
- 上面是草案，细节在 M1/M2 实现时调整，调整后同步回本文件。

## 0.3 API Key 注入
- `local.properties` 里已有 `CLAUDE_API_KEY`（M0 开始前我不读取它的值）。
- `app/build.gradle.kts`：读取 `local.properties`（不存在或没有该项时用空字符串，构建不失败），`buildFeatures { buildConfig = true }`，`buildConfigField("String", "CLAUDE_API_KEY", ...)`。
- 检查：`git grep` 与已提交历史中找不到 Key；`local.properties` 在 `.gitignore` 里（确认）；日志、`CmdReceiver` 不输出 Key；发布构建（release）**不注入**该字段（空字符串），避免把开发 Key 打进发布包。
- 注意：BuildConfig 里的字符串会进 APK，**仅限开发使用**；M5 做用户输入 Key + Keystore 后，开发 Key 路径仅在 debug 构建保留。

## 0.4 单元测试环境
- 依赖：JUnit 4、`kotlinx-coroutines-test`、`mockk`、`truth`（或 `kotlin.test` 断言，二选一，我倾向 Truth 便于读失败信息）、`org.json` 的 JVM 实现（`org.json:json`，测试用，因为 `android.jar` 里的 JSON 在 JVM 单测里是空实现）、`unitTests.isReturnDefaultValues = true`。
- 示例测试：`agent` 包放一个纯 Kotlin 的小函数测试（例如数据结构/日期换算的最小版本），证明 `./gradlew testDebugUnitTest` 能跑。
- 资源：`app/src/test/resources/` 放脱敏后的界面树样例（M1 用）。

## 0.5 CLAUDE.md 更新
- 项目阶段改为「功能开发」，删除「技术验证原型：代码越简单越好」等过时表述。
- 加入：模块划分和依赖规则（0.1 的图）；UI 只依赖 `task`；`safety` 先测试后实现。
- 常用命令：构建、单元测试、回归脚本、渲染设计稿。
- 每个里程碑完成必须跑单元测试和 `regress.sh`。
- API Key 规则：只来自 `local.properties`，不进代码和 Git。

## 0.6 执行顺序与验证
1. 先跑一遍 `regress.sh` 记下基线（含之前没在 UI 提交后重跑的 `back_test.sh`、`ime_test.sh`）。
2. 逐包移动，每移一个包就编译；`privileged`/`safety` 移动后立即跑 `regress.sh`。
3. 新增空壳包和数据结构；`task` 包包住现有 `Agent` 单例，UI 的调试页继续可用。
4. BuildConfig 注入 → 单元测试环境 → 示例测试。
5. 更新 CLAUDE.md；点一遍假数据 UI 流程（启动→引导→对话→卡片→全屏→设置→开发者调试）。
6. 提交：`m0: 按模块整理结构、API Key 注入、单元测试环境`。
- **不做**：新功能、改行为、改 AIDL 接口语义、改 UI。

## 0.7 风险
| 风险 | 缓解 |
|---|---|
| UserService 按类名启动，移动包后找不到类 | 先改并单独测一次 `connect`；`regress.sh` 兜底 |
| `Agent.kt` 是大单例，拆分容易改变行为 | M0 只做「包一层」，不拆内部逻辑，真正拆分在 M2 随执行循环一起做 |
| BuildConfig 开启后构建配置缓存/AGP 9 的 DSL 差异 | 用 AGP 9 的新 DSL 写法，先小改动试构建 |
| `local.properties` 缺失时 CI/别人构建失败 | 缺失时用空字符串，并在澄清阶段给出「未配置 Key」的友好提示（M2a） |

# 已确认的决定（2026-10-23）

1. 澄清阶段的模型调用单独计数，不占 60 次上限，澄清最多 6 轮。
2. 「用购物车里现有的」：跳过搜索和加购，直接去结算；Amazon 结算包含购物车全部商品，多件时告知「结算会包含购物车里的全部 N 件商品」，让用户选「全部结算」或「调整购物车」（PRD T3-R0）。
3. 澄清用结构化输出；优先官方 Java SDK，有兼容问题退回 OkHttp。
4. 单元测试：JUnit + Truth + mockk + coroutines-test。
5. 新增购物车修改（M3 实现）：仅用户明确要求；执行前确认卡片逐项列改动；硬拦截只放行已确认的改动，与 Gmail 发送确认共用 `ConfirmToken`；完成验证购物车与改动一致；不允许「稍后购买」。
6. 执行顺序：先单独提交文档 → 跑 `regress.sh` 记基线 → M0 分部分提交 → M0 完成后跑单元测试和 `regress.sh`，通过后停下来汇报。
