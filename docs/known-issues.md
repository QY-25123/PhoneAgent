# 设计说明与后续规划

这份文档记录 PhoneAgent 在系统集成、安全和交互上的设计选择、当前边界，以及后续的演进方向。文件名沿用旧称，方便其他文档引用。

## 一、虚拟屏与系统集成

### 同一个 App 的归属
虚拟屏正在运行某个 App 时，主屏再启动同一个 App，两边会共用同一个 task：显式组件启动会被虚拟屏上的实例接住，action 方式启动则会把 task 整个移到主屏。设计上约定：Agent 执行期间，目标 App 归虚拟屏使用。后续可以在启动前检查目标 App 是否已有 task，并在发现 task 被移走后重新拉起。

### 虚拟屏的释放
释放 display 前，`VirtualDisplayHolder.release()` 先用 `DisplayTasks.removeAll()` 关闭虚拟屏上的全部任务，再释放 display。这样连续多轮「创建 → 启动 → 点击 → 停止」之后，`dumpsys activity` 里只剩 display 0，没有残留窗口。

### UserService 的版本
重装 App 后旧的 UserService 进程可能短暂残留，目前通过递增 `Shizuku.UserServiceArgs.version()` 强制重启。后续让版本号随 `versionCode` 自动变化，并在启动时清理上一版本遗留的虚拟屏。

### 输入法隔离
- `setDisplayImePolicy` 的结果按虚拟屏的 uniqueId 缓存，因此每次创建虚拟屏后都显式设置一次 HIDE 策略（`scripts/ime_test.sh` 同样如此）。
- 虚拟屏上的返回键一律用带 displayId 的 `KeyEvent`，不使用 `GLOBAL_ACTION_BACK`（它作用于 top focused display）。详见 `docs/ime-experiments.md`。
- 用户键盘和输入目标的隔离依赖 `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED`（Android 14 的隐藏常量），已在 Android 14 模拟器上验证。后续在真机和主流厂商 ROM 上逐一确认该标志的行为；标志不可用时，由 `UserActivityGuard`（「用户打字时暂停」开关）作为后备。
- 更彻底的隔离方案是把 Agent 放进独立的 Android 用户 / 会话，作为远期方向保留。

### UserActivityGuard
在动作开始前检查一次用户键盘状态，用 `dumpsys input_method` 的 `mInputShown` 判断，前提是虚拟屏 IME 策略为 HIDE（创建虚拟屏后已设置）。用户持续打字时 Agent 会等待。后续可以加入等待超时和动作执行中的二次检查。

## 二、安全机制

不可逆动作由 UserService 进程里的硬拦截把关，模型的输出绕不过它，并且规则可以脱离模型单独做单元测试。

| 动作 | 机制 |
|---|---|
| 下单、付款、订阅会员 | `SafetyRules` 按控件文字和资源 id 匹配（Buy Now、Place your order、Place Order、Prime、DashPass 等），结账页出现下单按钮时做页面级锁定 |
| 发送邮件 | 用户在确认卡片上确认的内容算哈希，点击发送时再核对一次，一致才放行一次（`SendGate`） |
| 微信发送 | 界面树可读时按「Send / 发送」按钮拦截，内容哈希绑定；界面树读不到时按像素区域拦截（聊天页右下角）；回车键在微信里一律拦截 |
| Amazon 购物车 | `CartGate` 拦截删除、数量加减、Save for later；只对流程布防的那一组改动放行 |
| Google 地图 | 只读，「Start / Navigate / Save」被拦截 |

微信的像素模式下，发送内容由 App 侧用截图核对（点发送前再读一次，不一致就重新确认）；界面树模式下由特权侧核对。

## 三、任务流程

### 交接
Amazon、DoorDash、地图任务结束后，虚拟屏保留着，等用户点「去完成 / 去查看」，把任务移到主屏（`moveRootTaskToDisplay`，再 `setFocusedRootTask` 让它显示在前面）；用户发起新任务或点停止时清理。DoorDash 交接后看到的可能是购物车页，点 Continue 即可进入结账。

### 邮件
Agent 写好主题和正文，读出页面上实际内容请用户确认，确认后发送，并到「已发送」里核对。「已发送」列表只显示收件人名字（邮箱 @ 前面的部分），名字和主题对得上就算数，不需要点开邮件。

### 微信
微信的无障碍树在虚拟屏上是一个空根节点，所以任务走「截图 + 坐标」模式：截图叠 0–1000 刻度网格，模型用 `tap_xy` 点击；写字用 `paste`（特权侧 `pasteText`：写系统剪贴板 + 粘贴键）；聊天页内容（标题、输入框文字、最后一条自己发的消息、失败标记、发送按钮位置）由 `WechatVision` 读截图转写。界面树能读到时仍优先用树（`WechatChat`）。首页聊天列表里有联系人时直接点进去，没有再用搜索。微信冷启动时先等待，并耐心等启动画面结束。

### Google 地图
目的地必填，出发地默认当前位置、出发时间默认现在。通过路线链接直接打开驾车路线页，读耗时，并把底部路线面板拉开读距离（`Action.Scroll(dragSheet)`）；目的地候选列表里，用户说了具体子地点（如 Terminal 1）就选对应一项，没指定则选主地点，几个不同的地点会先询问用户。

### DoorDash
打开 App → 搜餐厅 → 选菜品（口味或规格没有完全一样的，选界面上最接近的，其余选项按默认）→ 加购 → 在购物车核对份数（多加了会调回要求的数量，购物车里已有的其他菜品不动）→ 走到结账页停下 → 交接。结账页读到的订单总价是 Total before tip（不含小费）。

### Amazon
搜索 → 加购 → 结算 → 停在提交订单页 → 交接。Amazon 页面是 WebView，无障碍点击对它不总是生效，所以对它一律使用触摸点击，触摸点击同样经过 SafetyGuard 的区域拦截。

## 四、界面与实时画面

- 任务执行中（含 App 在前台）屏幕上显示「实时画面 + 结束任务」悬浮窗，「×」只收起小窗不停任务；点画面直接进入全屏观看页，全屏页打开期间悬浮窗自动隐藏。等待回答、确认、交接时保持文字小窗。需要「显示在其他应用上层」权限，没有权限就不显示。
- 悬浮窗和全屏观看页都让虚拟屏直接画到 `SurfaceView`（`VirtualDisplay.setSurface`），模拟器上约 60 帧/秒；Agent 自己读屏改用 `PixelCopy`（上限 10 帧/秒）。两个界面都不在时，画面自动退回 ImageReader。悬浮窗与全屏页之间的 Surface 切换由 `routeToView` / `routeToReader` 完成。真机上的帧率有待实测。
- 对话里只显示简短的结果：执行中是「正在执行任务…」，完成卡片只有一句话。每次重新启动 App，对话从空白开始。
- 演示节奏：每次点击前先在实时画面上标出目标位置（一个渐隐的小圆圈），随后执行动作，动作后短暂停顿，方便观看者跟上（`AgentPhoneController.BEFORE_ACTION_MS` / `AFTER_ACTION_MS`）。

## 五、模型

- 澄清、每步决策和自查都使用 Claude Haiku 5.5：速度快（日历循环 3 到 10 秒）、成本低；稳定性由代码补充：日期对照表由程序算好、提示词带示例、每步自查、失败自动重试。
- 结构化输出里联合类型字段上限是 16 个（API 约束），微信、地图、DoorDash 的澄清字段用普通字符串，空串表示没有（`Schemas.CLARIFY`）。
- 每次运行完整留痕：每步的标注截图、模型输入输出、耗时和费用。

## 六、后续规划

- **用户侧落地**：设置页里让用户填写自己的 Claude API Key 并存入系统 Keystore；首次打开的引导流程（检查并引导安装、启动、授权 Shizuku 与悬浮窗权限）；签名的 release APK 发布到 GitHub Releases。
- **真机与 ROM 适配**：在真机和主流厂商 ROM 上验证虚拟屏标志、输入法隔离和实时画面帧率；所有对系统隐藏接口的调用都收敛在 `privileged` 一层，适配只需要改这一层。
- **更多任务**：更多 App 的任务模板，地图支持设置出发时间，DoorDash 支持预约送达。
- **微信**：随着界面树读取方式的进展，逐步从截图模式迁移到界面树模式。
- **测试**：把任务流程纳入 `scripts/regress.sh`，目前它覆盖底层能力（虚拟屏守卫、返回键、输入法隔离）。
