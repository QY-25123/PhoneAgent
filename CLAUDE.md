# PhoneAgent

## 项目目标
安卓手机 Agent App。最终形态：App 调用 Claude API，在用户看不见的虚拟屏幕（VirtualDisplay）上操作第三方 App；用户同时可正常使用自己的手机，真实屏幕和输入法不受影响。

**当前阶段：功能开发**（技术验证和界面设计已完成）。路线图和各里程碑的验收见 `docs/dev-plan.md`（M0 整理结构 → M1 感知 → M2 澄清+执行循环 → M3 安全 → M4 完成验证 → M5 界面接真实数据 → M6 三任务打磨），产品需求见 `docs/PRD.md`（v0.6）。三个任务：Google 日历加事项、Gmail 写并发送邮件（发送前用户确认）、Amazon 把购物车带到结算页（含用户明确要求时的购物车修改）。

## 运行环境
- Android Studio 模拟器：Pixel 8，Android 14（API 34），带 Google Play
- 已通过 adb 启动 Shizuku
- applicationId：`com.android.phoneagent`，minSdk 30

## 技术栈
Kotlin + Jetpack Compose + Shizuku（api / provider）。参考 scrcpy server 的 `--new-display` 实现（反射调用系统隐藏接口）。

## 工作方式
- **先给计划，用户确认后再写代码。** 不要未经确认就实现。
- 每个阶段结束前说明如何验证；遇到系统接口行为不确定时先做最小实验，不要猜。
- 保持简单：不做超出当前里程碑的抽象；新依赖要说明理由。
- **每个里程碑完成必须：单元测试全绿（`./gradlew testDebugUnitTest`）+ `scripts/regress.sh` 全 PASS**，再汇报。
- **安全规则先写测试再实现**（M3）；硬拦截要能脱离真实模型单独测试。
- 红线：绝不在 Amazon 下单，不点 Buy Now / Place your order / Try Prime / Join Prime；测试邮件只发给 `qijie_jerry@163.com`；不删除用户已有数据；含个人信息的截图和界面转储不提交。
- 提交信息简洁，不擅自 push。

## 模块划分与依赖规则
代码在 `app/src/main/java/com/android/phoneagent/`，按包划分（不拆 Gradle 模块）：
| 包 | 职责 |
|---|---|
| `privileged` | UserService（shell uid）、AIDL 客户端侧封装、虚拟屏、输入注入、UiAutomation（技术验证的核心代码） |
| `perception` | 读界面树和截图，生成带编号的元素列表（`ScreenState`、`UiElement`）；M1 实现 |
| `agent` | 任务澄清、执行循环、动作定义（`Action`）、Claude API 调用；M2 实现 |
| `safety` | 硬拦截规则（`SafetyGuard`）、`UserActivityGuard`、确认令牌（M3） |
| `verification` | 完成验证（`Verdict`、`Evidence`）；M4 实现 |
| `task` | 任务状态（`TaskState`）、步数/记录、任务存储；现有 `Agent` 单例暂放这里（M2 随执行循环拆分） |
| `ui` | Compose 界面（现用假数据） |

依赖方向：`ui → task → agent → (perception, safety, verification) → privileged`。
- **界面只依赖 `task` 暴露的状态，不直接调用下层**（M5 前调试页 `ui/debug` 例外，它直接用 `task.Agent`）。
- `agent` 不直接碰 `privileged`：读经 `perception`，所有动作必经 `safety` 检查。
- `safety` 的规则类（`SafetyGuard`）只依赖 Android 视图类型，不依赖 `privileged`；它被 UserService 进程里的 `privileged` 调用（硬拦截在特权侧执行，模型绕不过）。
- 模块间的数据结构是 `ScreenState` / `Action` / `TaskState`（草案，随里程碑调整并同步 `docs/dev-plan.md`）。

## 任务流程与模型（M2）
- 对话页输入 → `task.TaskCoordinator`（澄清 → 执行 → 结果，事件经 `TaskBackend` 给界面）→ `agent.Clarifier`（PRD 6.9）→ `agent.AgentLoop`（观察→决策→一个动作→执行，30 步 / 60 次模型调用 / 连续 3 次失败 / 连续 3 步无变化 / 同一界面重复同一动作 都会停）。
- 模型在 `agent.Models`：澄清用 `claude-sonnet-5-5`，每步决策和自查用 `claude-haiku-5-5`（更快更便宜）；debug 命令 `model --es clarify|step|verify <模型名>` 可临时切换做对比。调用走官方 Anthropic Java SDK（`ClaudeModelClient`，结构化输出）。
- 日历任务：先用「插入事项」意图（`IPrivileged.launchIntent`）在虚拟屏打开新建页并预填；本地核对一致就走快速通道直接点保存（`CalendarFastPath`），再自查一次；对不上交给模型。
- 点击/输入按感知给出的节点引用精确执行（`clickNode` / `setTextNode`），不按坐标猜节点；失败才退回坐标。
- 每次任务记录在 `<外部私有目录>/runs/<时间>/`（每步标注截图、模型输入输出、token、耗时、费用）。
- 真实测试脚本（会产生真实模型费用和真实日历事项，标题带 `[TAG]` 前缀，默认 `PA测试`）：`scripts/m2_task.sh "任务文字" [追问的回答...]`、`TAG=xxx CASES="B C" scripts/m2_t1_suite.sh`、`scripts/m2_verify_event.sh`（用日历内容提供者核对事项）。
- M1 脚本：`scripts/m1_record.sh`（重录样本）、`scripts/m1_sanitize.py`（脱敏）、`scripts/m1_samples.sh`（离线感知）、`scripts/m1_safety_prime.sh`（Prime 订阅按钮拦截的设备验证）。

## API Key
- 开发用 Key 只放 `local.properties` 的 `CLAUDE_API_KEY`（该文件已在 `.gitignore`），构建时注入 **debug** 的 `BuildConfig.CLAUDE_API_KEY`；release 构建不注入。缺失时为空字符串，构建不失败。
- Key 不进代码、日志、Git、截图。发布版由用户在设置里输入并存 Keystore（M5）。

## 调试与自测
- debug 构建带 `CmdReceiver`（`src/debug`，要求发送方持有 `DUMP` 权限，仅 adb shell 可发）：
  `adb shell am broadcast -a com.android.phoneagent.CMD -n com.android.phoneagent/.CmdReceiver --es cmd <connect|display|launch|tap|swipe|back|text|dump|stop>`
- **常用命令**（需要 `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`，adb 在 `$ANDROID_HOME/platform-tools`，`ANDROID_HOME` 见 `local.properties` 的 `sdk.dir`）：
  - 构建并安装：`./gradlew installDebug`
  - 单元测试：`./gradlew testDebugUnitTest`（测试在 `app/src/test`；JUnit + Truth + mockk + coroutines-test；JSON 用 `org.json` 的 JVM 实现）
  - 回归：`bash scripts/regress.sh`（需要模拟器在线、Shizuku 已运行并授权；约 3～5 分钟，放后台跑并轮询，不要在一次前台命令里等）
  - 设计稿截图：`bash scripts/design/render_screens.sh`
- 构建需要 Android Studio 自带 JBR：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew installDebug`
- 特权代码在 `privileged/`（UserService，shell uid；Shizuku 进程名后缀仍是 `priv`）；`UiAutomationHelper` 是可复用的界面树/输入模块。
- 回归脚本在 `scripts/`：`regress.sh`（总入口）= `guard_test.sh` + `back_test.sh` + `ime_test.sh`。改动 `privileged/`、`safety/`、`task/Agent` 后都要跑。结论和约束见 `docs/spike-summary.md`、`docs/known-issues.md`。
- 虚拟屏必须带 `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED` 创建（否则用户的键盘会被收起/抢走）；虚拟屏上的动作经过 `UserActivityGuard`（「用户打字时暂停」安全开关，默认关闭）；返回键必须用带 displayId 的 KeyEvent，禁止 `GLOBAL_ACTION_BACK`。
- **界面**（v0.5，对话为中心）在 `app/src/main/java/com/android/phoneagent/ui/`：`theme/`（颜色、字体、间距、圆角、尺寸、图标令牌）、`components/`、`screens/`、`model/`（假数据和演示脚本）、`nav/`（Navigation Compose）、`gallery/`（界面目录和 @Preview）。视觉规范 `docs/DESIGN.md`。**业务界面只能用主题令牌，不写颜色/dp/sp 字面量。**界面阶段用假数据，不接入 agent 逻辑，不改 `privileged/`、`safety/` 等核心代码。
- 技术验证用的测试界面保留在「设置 → 开发者调试」。回归脚本用 `am start -n com.android.phoneagent/.MainActivity --es route debug` 直接打开它（该入口下返回键会关闭 Activity）。
- 渲染设计稿截图：`scripts/design/render_screens.sh`（debug 的 `GalleryActivity` + `adb screencap`，输出到 `docs/design/screens/`）。
