# PhoneAgent

安卓手机 Agent：你在对话里说一件事，它在**用户看不见的虚拟屏**上替你操作第三方 App（Google 日历、Gmail、Amazon），你同时照常用手机。关键的事（发邮件、改购物车、下单付款）一定由你确认或亲手完成。

> MVP（`v0.1-mvp`）。目前只在 Android 14 模拟器上验证过。

## 核心能力
- **后台虚拟屏操作**：App 通过 Shizuku 获得 shell 权限，创建一块不显示在真实屏幕上的虚拟屏，在上面启动并操作目标 App；读界面树、截图、点击、输入都定向到虚拟屏。
- **不打扰用户**：虚拟屏带 `STEAL_TOP_FOCUS_DISABLED`，agent 操作不会抢走你的键盘、输入焦点和返回键；返回只用带屏幕编号的按键事件。
- **确认与交接**：
  - 发邮件：确认卡片展示的是从 Gmail 页面**实际读到**的收件人、主题、正文；你点发送后，对内容算哈希，点击发送时再核对一次，一致才放行一次（不一致重新确认）。
  - 改购物车：先出确认卡片，只放行你确认过的那组改动（特权侧硬拦截）。
  - Amazon 结算：走到提交订单页停下，点「去完成」把 Amazon 移到你的屏幕，下单和付款由你来。**下单（Buy Now / Place your order）和订阅 Prime 的按钮被不依赖模型的硬拦截挡住**。
- **对话为中心的界面**：追问和选项按钮、实时画面卡片、全屏观看、确认卡片、结果卡片；离开 App 时有悬浮小窗，点「去回答 / 去确认 / 去处理」回到对应卡片。

## 三个任务
| 任务 | 做什么 |
|---|---|
| 日历 | 缺信息一次问全；相对日期（明天、下周一）由程序算好对照表；意图预填新建页，本地核对一致后直接保存，再自查 |
| Gmail | 写好主题和正文，打开预填的写邮件页，读出实际内容给你确认（发送 / 修改 / 取消），发送后到「已发送」里核对；重试前先查「已发送」避免重复 |
| Amazon | 搜索 → 加购 → 结算 → 停在提交订单页 → 交接；购物车多件时先告诉你结算会包含全部商品；你明确要求时可以先删商品或改数量 |

## 架构
```
 ui（Compose 对话界面 / 悬浮小窗 / 引导）
  │  只依赖 task 暴露的事件
 task（TaskCoordinator：澄清 → 执行 → 结果；TaskEvent 事件流；悬浮小窗控制器）
  │
 agent（Clarifier 任务澄清 · AgentLoop 感知→决策→动作 · EmailFlow / ShoppingFlow · Claude 客户端）
  │            └─ 模型：claude-haiku-5-5（结构化输出）
 perception（界面树快照 → 带编号的元素 + 标注截图 + 稳定检测）
 safety（硬拦截规则、Gmail 发送闸门、购物车闸门、用户打字守卫）
  │
 privileged（Shizuku UserService，shell 权限：虚拟屏、输入注入、UiAutomation、意图启动）
```
细节：[`CLAUDE.md`](CLAUDE.md)（模块和依赖规则）、[`docs/PRD.md`](docs/PRD.md)、[`docs/dev-plan.md`](docs/dev-plan.md)、[`docs/DESIGN.md`](docs/DESIGN.md)。

## 如何运行
1. **环境**：Android Studio 模拟器（Pixel 8，Android 14，带 Google Play），登录 Google 账号并装好日历、Gmail、Amazon Shopping。
2. **Shizuku**：装 Shizuku，用 adb 或无线调试启动，并授权给 PhoneAgent（手机重启后要重新启动）。
3. **API Key**：在项目根目录 `local.properties` 里加一行 `CLAUDE_API_KEY=你的key`（该文件已被 `.gitignore`；Key 只会注入 **debug** 构建，不会进 Git）。
4. **构建安装**：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew installDebug`
5. **悬浮窗权限**（离开 App 时显示进度）：首次引导里点「去开启」，或 `adb shell appops set com.android.phoneagent SYSTEM_ALERT_WINDOW allow`。
6. 打开 PhoneAgent，在对话里说「帮我加个日历事项」试试。更多见 [`docs/how-to-try.md`](docs/how-to-try.md)。

测试：`./gradlew testDebugUnitTest`（单元测试，不联网）；`bash scripts/regress.sh`（设备上的底层回归：虚拟屏守卫、返回键、输入法隔离）。

> ⚠ `assembleDebug` 产出的 APK 里带着你的 API Key（BuildConfig），**不要提交、不要上传、不要分享**。`dist/` 已加入 `.gitignore`。

## 已知限制
- 只在 Android 14 模拟器验证；依赖 Android 14 的隐藏接口和 Shizuku，厂商 ROM 未知。
- 模型用 Haiku 5.5：快且便宜，但偶尔自查误判、澄清多追问。
- Gmail 取消流程不稳定（丢弃草稿后虚拟屏全黑，可能确认不了草稿箱），已知不处理。
- Amazon 页面是 WebView，用触摸点击；购物车改动的拦截按商品名匹配，名字相近的两件商品可能互相放行。
- 悬浮小窗样式朴素；对话历史只在进程存活时保留；没有应用商店发布准备（API Key 目前来自本地配置）。
- 完整列表见 [`docs/known-issues.md`](docs/known-issues.md)。

## 目录
`app/`（源码）· `docs/`（PRD、设计、开发计划、验证记录、`mvp/` 截图）· `scripts/`（回归、真实任务测试、样本脱敏等脚本）
