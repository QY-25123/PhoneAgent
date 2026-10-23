# 技术验证总结

本文档总结「安卓手机 Agent」底层能力的技术验证（spike）结论，作为需求文档的输入。验证范围：不接入 Claude，只验证「在用户看不见的虚拟屏上操作第三方 App，同时不影响用户使用手机」是否可行。

- **验证环境**：Android Studio 模拟器 Pixel 8，Android 14（API 34，arm64），带 Google Play；Shizuku 13.1.5，通过 adb 启动。
- **技术栈**：Kotlin + Jetpack Compose + Shizuku（api/provider），无 hiddenapibypass。
- **代码位置**：`app/src/main/java/com/android/phoneagent/`（`priv/` 是运行在 shell 进程里的特权代码）；回归脚本 `scripts/`；实验记录 `docs/ime-experiments.md`；已知问题 `docs/known-issues.md`。
- **总体结论**：**可行，全部能力验证可用。** 用户键盘/输入目标的隔离靠创建虚拟屏时的 `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED` 标志解决，不需要让 agent 避让用户（避让保留为默认关闭的安全开关）。

---

## 1. 结论一览

| # | 能力 | 结论 | 说明 |
|---|---|---|---|
| 1 | 以 shell 权限运行特权代码 | 可用 | Shizuku UserService，进程 uid=2000 |
| 2 | 创建 1080x2400 虚拟屏，画面输出到 ImageReader，不显示在真实屏幕 | 可用 | 真实屏幕无任何变化 |
| 3 | 在虚拟屏上启动指定 App | 可用 | `am start --display`；同一 App 两边不能同时使用（见 4.3） |
| 4 | App 内显示虚拟屏实时预览 | 可用 | 约每秒一帧 |
| 5 | 点击、滑动、返回键定向到虚拟屏 | 可用 | 返回键**必须**用带 displayId 的 KeyEvent |
| 6 | 向虚拟屏输入框写中文，不占用用户输入法 | 可用 | UiAutomation + `ACTION_SET_TEXT`，不经过输入法 |
| 7 | 前台服务保活 | 可用 | 切到后台 20 秒后仍正常工作 |
| 8 | 停止并释放，无残留 | 可用 | 需要先关掉虚拟屏上的任务再释放 |
| 9 | 用户在主屏打字时，agent 不影响用户的键盘和输入目标 | 可用 | 虚拟屏加 `STEAL_TOP_FOCUS_DISABLED` 标志；「用户打字时暂停」作为默认关闭的安全开关保留 |

---

## 2. 各能力的实现方式

### 2.1 Shizuku 与特权进程
- App 进程：`Shizuku.checkSelfPermission` / `requestPermission`（首次需要用户点一次授权弹窗，之后记住），再 `Shizuku.bindUserService` 绑定 `PrivilegedService`。
- 特权接口用 AIDL 定义（`IPrivileged.aidl`），所有需要 shell 权限的操作都在 UserService 进程里执行。
- `UserServiceArgs.version()` 用来强制重启旧的 UserService，开发期要手动递增（见已知问题 3）。
- **UserService 里没有 App Context**。构造函数里用 `ActivityThread.systemMain()` 拿系统 Context，包一层 `FakeContext`，把包名伪装成 `com.android.shell`（参考 scrcpy）。必须在构造函数（有 main looper 的线程）里做。

### 2.2 虚拟屏
- `DisplayManager` 通过反射构造（`DisplayManager(Context)` 是隐藏构造函数），再调用公开的 `createVirtualDisplay(name, w, h, dpi, surface, flags)`。
- **Surface 由 App 进程的 ImageReader 创建，通过 AIDL 传给 UserService**，画面直接在 App 进程取，不用跨进程传 Bitmap。
- flags（参考 scrcpy `--new-display`）：`OWN_CONTENT_ONLY`、`SUPPORTS_TOUCH`、`ROTATES_WITH_CONTENT`、`DESTROY_CONTENT_ON_REMOVAL`，以及 shell 才有权限使用的 `TRUSTED`、`OWN_DISPLAY_GROUP`、`ALWAYS_UNLOCKED`、`TOUCH_FEEDBACK_DISABLED`、`OWN_FOCUS`。**没有** `SHOULD_SHOW_SYSTEM_DECORATIONS`（不需要状态栏/导航栏）。
- 创建后立刻把虚拟屏的 IME 策略设为 `HIDE`（`IWindowManager.setDisplayImePolicy(displayId, 2)`），否则虚拟屏里的 App（如设置搜索页）会在用户的屏幕上弹出键盘。**该策略按 display 的 uniqueId 缓存，display 释放后仍然有效**，所以每次创建后都显式设置。
- 释放：`VirtualDisplay.release()`。

### 2.3 启动 App
- `am start --display <id> --activity-clear-task -n <包名/类名>`，在 UserService 里用 `ProcessBuilder` 执行。
- 原型只验证了「设置」。

### 2.4 预览
- `ImageReader`：`RGBA_8888`、`maxImages=2`，监听线程里 `acquireLatestImage`，**每帧都必须 `close`**，否则会堵塞生产者。
- 限流到每秒最多一帧，转成缩小的 Bitmap（540x1200）给 Compose 显示。
- `rowStride/pixelStride` 可能大于宽度，Bitmap 宽度要按 `rowStride/pixelStride` 创建再裁剪。
- **画面静止时不会有新帧**，预览不更新是正常的。

### 2.5 输入注入
- `InputManager.injectInputEvent`（反射），事件用隐藏方法 `InputEvent.setDisplayId` 定向到虚拟屏。
- 点击：DOWN → 50ms → UP。滑动：DOWN，再 15 步 MOVE（按时长均分），再 UP。事件 source 为 `SOURCE_TOUCHSCREEN`，注入模式为 ASYNC。
- 返回键：`KeyEvent`（device `VIRTUAL_KEYBOARD`，source `SOURCE_KEYBOARD`）加 `setDisplayId`。

### 2.6 写入中文
- UserService 里连接 `UiAutomation`：反射调用隐藏构造函数 `UiAutomation(Looper, IUiAutomationConnection)` 和 `connect(flags)`，**必须带 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`**，不关掉用户已启用的无障碍服务；回调 looper 要用另一个 `HandlerThread`，否则 `connect` 会自己等自己。
- serviceInfo 加上 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` 才能取到非默认屏幕的窗口，用 `getWindowsOnAllDisplays()[displayId]` 取虚拟屏的窗口。
- 写文字：找聚焦的输入节点（`findFocus(FOCUS_INPUT)`，没有则取第一个可编辑节点），`performAction(ACTION_SET_TEXT)`。**不经过输入法**，中文没有问题。
- 封装为 `UiAutomationHelper`，同一个连接还提供：`dump()`（界面树文本，后续读虚拟屏界面用）、`clickAt()`（语义点击）、`scroll()`。
- 目标输入框必须先获得焦点。目前的做法是先点击输入框（触摸），再 `ACTION_SET_TEXT`。

### 2.7 前台服务与停止
- `AgentService`：前台服务类型 `specialUse`，必须在 App 可见时 `startForegroundService`；Android 13+ 需要 `POST_NOTIFICATIONS`。服务本身只负责保活，虚拟屏由 `Agent` 单例持有。
- 停止顺序：取消正在等待的动作 → UiAutomation 断开 → **关掉虚拟屏上所有任务**（`IActivityTaskManager.removeTask`）→ 释放 display → 把焦点切回主屏 → 关闭 ImageReader → 停前台服务。
- 验证：停止后 `dumpsys display` 只剩 display 0，`dumpsys activity` 只剩 display 0，`dumpsys accessibility` 里的 `Ui Automation` 条目消失，服务消失。

### 2.8 键盘/输入目标隔离：STEAL_TOP_FOCUS_DISABLED 标志
- 创建虚拟屏时加 `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED`（Android 14 隐藏常量 `0x10000`，反射读取，要求同时有 `OWN_FOCUS`，shell 可用）。虚拟屏上启动 Activity、触摸、页面跳转都不再切走 top focused display，用户的键盘不被收起，输入也不被抢。
- 验证：守卫全关时，`ime_test` 6/6 步通过（键盘仍显示、焦点窗口和输入连接不变、top display 始终为 0、主屏仍能打字）；去掉标志的对照组全部失败。动作确实被执行（虚拟屏里写入了中文）。详见 `docs/ime-experiments.md`。
- 配合虚拟屏 IME 策略 `HIDE`（见 2.2），虚拟屏里的 App 不会在用户的屏幕上弹键盘。

### 2.9 安全开关：用户打字时暂停（UserActivityGuard，默认关闭）
主界面有开关，状态持久化。开启后，对虚拟屏的每个动作（启动、点击、滑动、写入、返回、语义点击/滚动）执行前检查主屏键盘是否显示（`dumpsys input_method` 的 `mInputShown`，约 45ms，每 300ms 一次）：显示就暂停，界面显示「等待用户输入完成」；键盘收起并**持续 2 秒**后继续。停止时会取消正在等待的动作。只读查询（dump 界面树）走单独的线程，不会被暂停的动作堵住。

早先版本的「动作后注入 `KEYCODE_UNKNOWN` UP 把焦点切回主屏」和「500ms/1500ms 补切」已删除（有标志后不需要）。

---

## 3. 验收结果

| 脚本 | 验证内容 | 结果 |
|---|---|---|
| `scripts/guard_test.sh` a | 主屏键盘弹出期间，agent 的点击和滑动被暂停；用户连续打字 3 次全部进入主屏输入框；键盘、焦点窗口、输入连接、top display 全程不变；界面显示「等待用户输入完成」；虚拟屏没有被操作 | PASS |
| `scripts/guard_test.sh` b | 键盘收起后 2315ms 动作自动继续（要求 ≥2000ms） | PASS |
| `scripts/guard_test.sh` c | 动作完成后 top display=0；此时在主屏发不带 displayId 的返回键，作用在主屏（PhoneAgent 被关掉），虚拟屏上的搜索页保持不变 | PASS |
| `scripts/back_test.sh` | 带 displayId 的 KeyEvent 在两种场景下都只作用于虚拟屏 | PASS |
| `scripts/ime_test.sh` | 默认配置（标志开、暂停关）：键盘弹出时对虚拟屏下达「点搜索框、写中文、返回、再进搜索页、返回」，动作真实执行；每步后键盘仍显示、焦点窗口和输入连接不变、top display 为 0、主屏仍能打字 | PASS（6/6） |
| `CFG='--ez steal false' scripts/ime_test.sh` | 对照：去掉标志 | FAIL（键盘被收起、top display 被切走），证明标志在起作用 |

`guard_test.sh` 测的是安全开关（脚本里会打开它）；`ime_test.sh` 测的是不依赖安全开关的隔离。

`scripts/regress.sh` 一次跑完以上全部。

---

## 4. 已知限制与约束（写需求时必须考虑）

### 4.1 键盘/输入目标隔离依赖 STEAL_TOP_FOCUS_DISABLED 标志
- **问题根因**：Android 只有一个输入法实例，只有一个「top focused display」。虚拟屏上任何 Activity 被启动或获得焦点（`am start --display`、触摸、语义点击引起的页面跳转），top focused display 就变成虚拟屏，主屏窗口失去焦点，系统随之收起用户的键盘，或把输入连接交给虚拟屏里的 App。`OWN_FOCUS` 只隔离窗口焦点，不隔离这件事；IME 策略 `HIDE` 只能避免连接被抢，不能避免键盘被收起（两者只能二选一）。
- **解决**：创建虚拟屏时加 `STEAL_TOP_FOCUS_DISABLED` 标志，从源头阻止（见 2.8）。**不需要 agent 避让用户**。
- **限制**：这是 Android 14 的隐藏常量，只在模拟器上验证；低版本没有，厂商 ROM 未知。标志不可用时，后备方案是打开「用户打字时暂停」开关，但那样不能保证键盘不被收起（见 `docs/known-issues.md` 第 6、7 条）。
- 实验数据见 `docs/ime-experiments.md`。

### 4.2 返回键必须用带 displayId 的 KeyEvent，禁用 GLOBAL_ACTION_BACK
`UiAutomation.performGlobalAction(GLOBAL_ACTION_BACK)` 没有 displayId 参数，作用于 top focused display。有了标志，top focused display 始终是主屏，所以它**一定**会对主屏执行返回，关掉用户正在用的 App。实测 PhoneAgent 的 MainActivity 被关掉、虚拟屏页面不变（`back_test.sh`）。

### 4.3 同一个 App 不能两边同时使用
虚拟屏正在运行某个 App 时，用户在主屏打开同一个 App：
- 显式组件启动：请求被虚拟屏上已有的实例吸收，主屏没反应。
- 以 action 方式启动并指定主屏：虚拟屏上的 task 被整个**移到主屏**，虚拟屏变空。

产品含义：agent 操作某个 App 期间，用户无法同时使用它；agent 需要检测 task 被移走并处理。

### 4.4 语义操作不能规避焦点问题
没有标志时，ACTION_CLICK、ACTION_SCROLL 引起的页面跳转同样会切走 top focused display，和触摸没有区别。有标志后两者都不再影响主屏。后续 agent 的动作可以用语义操作为主（比触摸更不依赖坐标），触摸作兜底。`ACTION_SET_TEXT` 本身不改变输入法状态，但要求目标输入框已聚焦。

### 4.5 对系统实现的依赖
- `STEAL_TOP_FOCUS_DISABLED` 标志只在 Android 14 模拟器上验证（见 4.1）。
- （历史）曾用「注入孤立的 `KEYCODE_UNKNOWN` UP 切回主屏焦点」，依赖 PhoneWindowManager 的实现，已删除；`setFocusedRootTask`、`moveTaskToFront`、`focusTopTask`、`moveRootTaskToDisplay` 都试过，不能切回。
- 键盘检测依赖虚拟屏 IME 策略为 HIDE。
- 大量隐藏 API 靠反射调用（`DisplayManager` 构造函数、`InputEvent.setDisplayId`、`UiAutomation` 构造函数和 `connect`、`IWindowManager`、`IActivityTaskManager`）。API 变化会失败，需要按系统版本验证。

### 4.6 虚拟屏必须在释放前清空
不关闭虚拟屏上的任务就释放 display，任务和窗口会残留在已销毁的 display 上；残留积累后，top focused display 曾卡在已销毁的 display 上，主屏所有窗口没有焦点，只能重启系统。现在 `release()` 会先关任务。详见 `docs/known-issues.md` 第 2 条。

### 4.7 其他
- UiAutomation 首次连接后，第一次读界面树可能还没填充，需要重试。
- 虚拟屏上的窗口不是 active window 时，仍能读取界面树和执行 `ACTION_SET_TEXT`（已验证），但要求 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`。
- 重装 App 会杀掉 App 进程，UserService 可能残留，需要版本号管理（已知问题 3）。
- Shizuku 在模拟器重启后需要重新启动，授权记录保留。

---

## 5. 尚未验证的内容

- **真机和厂商 ROM**：只在 AOSP 模拟器上验证，国产 ROM 对虚拟屏、焦点、输入法、后台限制的改动很大。
- **其他 Android 版本**：只验证了 Android 14。
- **主屏灭屏/锁屏**时虚拟屏是否继续工作、预览是否有帧。
- **安全窗口**（`FLAG_SECURE`，如银行、支付类 App）在虚拟屏上是否黑屏。
- **不支持 `ACTION_SET_TEXT` 的输入控件**（WebView、自绘控件、游戏）。
- **第三方 App** 在虚拟屏上的兼容性（只验证了系统设置）；对虚拟屏分辨率/多显示器支持差的 App 可能崩溃或拒绝启动。
- **Shizuku 之外的权限路径**：没有 Shizuku（无 root、无无线调试）时怎么办。
- **多个虚拟屏、多个 agent 并行**。
- **性能**：预览只验证了每秒一帧；动作延迟（点击后页面稳定需要多久）没有系统测量。
- **`STEAL_TOP_FOCUS_DISABLED` 标志在真机、厂商 ROM、其他 Android 版本上是否有效**（最重要）。
- **安全开关（用户打字时暂停）的竞态窗口**（用户在检查之后开始打字）的实际发生概率。
- `UserService` 异常退出后的自动恢复。
- **后台限制**：App 被系统杀掉、省电模式、Android 15+ 的前台服务限制。

---

## 6. 对需求文档的建议输入

1. 「agent 在虚拟屏工作，用户同时正常使用手机」在 Android 14 模拟器上成立，键盘和输入目标不受影响，不需要让 agent 避让用户。
2. 产品文案要明确「agent 操作某个 App 期间，用户无法同时使用它」（4.3）。
3. 把「真机/厂商 ROM/其他 Android 版本上 `STEAL_TOP_FOCUS_DISABLED` 是否有效」作为第一优先级的风险，尽快用真机复验第 1～9 项。标志不可用时才需要启用「用户打字时暂停」后备方案，并重新评估产品形态。
4. 「用户打字时暂停」目前是默认关闭的开关，如果产品需要，要定义等待多久后提示用户的超时策略。
