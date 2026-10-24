# 已知问题（暂不处理）

## 1. 同一 App 冲突
虚拟屏正在运行某个 App（例如「设置」）时，主屏也启动同一个 App，两边抢同一个 task：
- `am start -n com.android.settings/.Settings`（显式组件，主屏）：提示 `Activity not started, intent has been delivered to currently running top-most instance`，请求被虚拟屏上的实例吸收，主屏没有任何变化。
- `am start --display 0 -a android.settings.SETTINGS`（action 方式，指定主屏）：虚拟屏上的 Settings task 被整个**移到主屏**，虚拟屏变空。

影响：用户在主屏打开 agent 正在操作的 App，agent 的界面会被抢走或被替换。后续需要检测并处理（例如启动前检查目标 App 是否已有 task、agent 侧发现 task 被移走后重新拉起）。

## 2. 虚拟屏残留（已修复，保留记录）
最初 `dumpsys display` 在停止后没有残留，但 `dumpsys window` / `dumpsys activity` / SurfaceFlinger 里仍留着已释放 display 的容器，一次次创建/停止后越积越多（测到 display 4～55 共 18 个）。
- **原因**：释放 display 时上面的任务没有关掉，任务和窗口留在已销毁的 display 上。
- **严重后果**：积累到一定程度后，`mTopFocusedDisplayId` 曾卡在一个已销毁的 display（54）上，主屏所有窗口 `mCurrentFocus=null`，点主屏、按 Home、重开 App 都恢复不了，只能重启模拟器。
- **修复**：`VirtualDisplayHolder.release()` 先用 `DisplayTasks.removeAll()` 关掉虚拟屏上的所有任务，再释放 display。（早期版本在释放后还会注入按键把焦点切回主屏，加了 STEAL_TOP_FOCUS_DISABLED 标志后已删除，见第 6 条。）修复后连续 3 轮「创建→启动→点击→停止」以及完整回归之后，`dumpsys activity` 里只剩 display 0，top focused display = 0，没有残留窗口。
- **恢复办法（如果再遇到）**：`adb reboot` 重启模拟器，然后用 `adb shell <Shizuku 的 lib/arm64/libshizuku.so 路径>` 重新启动 Shizuku（授权记录保留，不用再点弹窗）。

## 3. 升级后旧 UserService
重装 App 会杀掉 App 进程，但 UserService 进程（`com.android.phoneagent:priv`）可能残留一小段时间，仍持有旧代码和旧的 VirtualDisplay。目前靠把 `Shizuku.UserServiceArgs.version()` 调大来强制重启 UserService，开发期需要手动递增。正式版需要：
- 版本号随 `versionCode` 自动变化；
- 启动时检查并清理上一版本遗留的虚拟屏。

## 4. IME 策略按 uniqueId 缓存（实验时的陷阱）
`setDisplayImePolicy` 的结果按虚拟屏的 uniqueId（`virtual:com.android.shell,2000,PhoneAgent,0`）缓存，display 被释放后依然有效，会影响之后创建的同名虚拟屏。做输入法实验时必须每次显式设置策略，`scripts/ime_test.sh` 已经这样做。

## 5. GLOBAL_ACTION_BACK 不能用
`UiAutomation.performGlobalAction(GLOBAL_ACTION_BACK)` 没有 displayId 参数，作用于 top focused display。主屏是 top focused display 时会对**主屏**执行返回（实测 PhoneAgent 的 MainActivity 被关掉，虚拟屏页面不变）。必须禁用，返回键一律用带 displayId 的 KeyEvent。详见 `docs/ime-experiments.md`。

## 6. 对 STEAL_TOP_FOCUS_DISABLED 标志的依赖
用户键盘和输入目标的隔离现在依赖 `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED`（Android 14 隐藏常量）。
- 只在 Android 14 模拟器上验证过。更低版本没有这个常量；厂商 ROM 是否保留、行为是否一致未知。
- 标志缺失或不生效时的后备方案是 `UserActivityGuard`（打开「用户打字时暂停」开关）。但后备方案不能把焦点切回主屏（试过的 `setFocusedRootTask`、`moveTaskToFront` 等都无效，只有注入孤立的 `KEYCODE_UNKNOWN` UP 有效，已删除），所以动作后键盘可能被收起。
- 标志是创建虚拟屏时的 flag，不能事后修改。

## 7. UserActivityGuard（安全开关）的局限
- **竞态**：只在动作开始前检查一次键盘。用户在检查之后、动作执行期间开始打字，动作仍会执行。窗口约几十到几百毫秒。
- **键盘检测依赖 HIDE 策略**：用 `dumpsys input_method` 的 `mInputShown` 判断，前提是虚拟屏 IME 策略为 HIDE（Agent 创建虚拟屏后会设置），否则虚拟屏里的 App 自己弹出的键盘会被误判成用户在打字。
- **用户长时间打字，agent 会一直等**：没有超时。

## 以后的优化方向（暂不做）
- **方案 4：把 agent 放到独立的 Android 用户/会话**，输入法实例和焦点彻底隔离。改动大，要重新评估 Shizuku、前台服务、跨用户通信。若标志在真机/厂商 ROM 上不可用，才需要考虑。

---

# MVP 阶段记录的已知问题（2026-10，M2/T2/T3 之后）

## 8. Gmail 取消流程不稳定（已决定不再处理）
取消邮件后要「丢弃草稿 + 核对草稿箱」。丢弃之后写邮件页关闭、虚拟屏常常全黑，agent 有时判断不了草稿是否已丢弃，会提示「请手动检查草稿箱」。已做的缓解：丢弃和核对分两步、核对前重新打开 Gmail、丢弃失败也继续核对草稿箱，修复后的版本没有再完整验证。取消时草稿箱里可能残留主题为 `[PA测试] …` 的草稿，需要用户手动清理。

## 9. 购物车「删除 / 改数量」的硬拦截（已做并在设备上验证）
特权侧 `CartGate`（`safety/CartGate.kt`）拦截购物车的删除、数量加减、Save for later / Move to cart：没布防一律拦截；用户在确认卡片上确认后，只对清单里的商品放行（删除一次、数量只朝目标方向）。单元测试 12 个。已知不足：
- 设备验证（`scripts/m4_cart_block.sh`、确认卡片流程）已通过：未确认的 Delete 被拦截且购物车不变；确认后放行，测试商品被删除。数量加减的放行方向只有单元测试，没有在设备上点过。
- 商品名匹配用「清单里的名字（至少 4 个字符）包含在控件描述里」，名字相近的两件商品可能互相放行。
- 「Saved for later」区里的 Delete 因为商品名不在清单里被拦截，但没有按所在区块区分。

## 10. Amazon 页面用触摸点击，不用无障碍点击
Amazon 的页面是 WebView：无障碍 `ACTION_CLICK` 返回成功但页面没反应（实测 Proceed to checkout）。`AgentPhoneController` 对 Amazon 一律用触摸点击；触摸点击会产生真实触摸事件，仍经过 SafetyGuard 的区域拦截。

## 11. 交接后虚拟屏一直保留，直到用户点「去完成」或开始新任务
Amazon 停在提交订单页后，虚拟屏和前台服务保留着等用户点「去完成」。用户一直不点的话它一直在；下一次发消息、或点停止时才会清理。

## 12. 模型与自查
- 全部用 Haiku 5.5：快（日历循环 3～10 秒）也便宜，但自查偶尔误判（同一天有多个相似事项时）、澄清偶尔多追问。已用代码算好的日期对照表、提示词示例、一次自动重试缓解。
- 自查提示词已放宽，T1 用例没有在最后一轮改动后重跑。

## 13. 全屏观看页
下滑手势曾连续触发导致整个 App 退出（已修：只触发一次，并加了「×」按钮）。步骤列表上滑展开仍用旧手势，没有重新验证。

## 14. 其他
- 澄清阶段对「只给名字的收件人」的联系人查找只靠模型在界面上操作，没有专门测试。
- 测试在日历里留下了 `[PA测试…]` 事项，Gmail 里发出了 `[PA测试]` 邮件，都没有自动清理。
- 回归脚本 `regress.sh` 只覆盖底层能力（守卫、返回键、输入法隔离），没有覆盖任务流程。

## 15. 悬浮小窗和界面（M5）
- 小窗需要「显示在其他应用上层」权限；模拟器上用 `adb shell appops set com.android.phoneagent SYSTEM_ALERT_WINDOW allow`。没有权限就不显示，没有引导界面。
- 小窗只做了文字和按钮，样式是原生 View，没有套用主题令牌，也没有处理深浅主题。
- Amazon 完整流程（搜索加购 → 结算 → 交接）已在最后一轮真机跑通，截图见 `docs/mvp/12～13`。
- 对话和实时画面的历史只在进程存活时保留（关掉 App 进程后丢失），没有落盘。
- 实时画面是每秒一张小图，不是视频；全屏观看看起来流畅度可以接受，没有做帧率测量。

## 16. 最后一轮发现
- 测试中多次出现「Amazon 加购成功但页面看起来没变化」，agent 会重复点 Add to cart；已在提示词里写明只点一次、看底部购物车数字，并有「同界面重复同动作就停」的保护。
- 之前几轮测试里，agent 曾把你购物车里原有的 QGeeM 转接器误删（那时还没有购物车硬拦截）。现在未确认的删除会被特权侧拦截。
- 引导页新增的按钮（打开 Shizuku、授权、先跳过）只做了编译和逻辑检查，没有在全新安装的设备上走过一遍；「去开启」跳转的系统「显示在其他应用上层」页面验证过能打开。
- 用户点「去完成」后的交接卡片状态由事件更新（修复了从别的入口触发时按钮残留）。
