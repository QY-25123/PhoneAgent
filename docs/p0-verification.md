# P0 技术验证记录

对应 `docs/PRD.md` 8.6 节。环境：Android 14（API 34）模拟器 Pixel 8，arm64。脚本在 `scripts/p0/`，共用函数 `scripts/p0/lib.sh`。
截图、界面树含个人信息（账号邮箱、聊天内容），**不提交**；脚本默认输出到 `/tmp/p0_out`。

| 项 | 能力 | 结论 | 状态 |
|---|---|---|---|
| P0-1 UV-5 | 三个目标 App（日历、Gmail、Amazon）在虚拟屏运行 | **通过**（日历、Gmail、Amazon 都能运行并读到所需文字；有几条需要写进 PRD 的限制） | 完成 |
| P0-2 UV-6 | Amazon 结算页：安全窗口与界面树 | **通过：T3 成立**（提交订单页不是安全窗口，画面和界面树都可读，硬拦截有效）；数量要在购物车页核对 | 完成 |
| P0-3 UV-3 | 同一 App 冲突检测 | **通过**（设置、Gmail、日历三个 App 结果一致；吸收场景为启发式） | 完成 |
| P0-4 UV-2 | 任务移到主屏（交接） | 设置、日历通过；Gmail 的移动本身通过，草稿内容保留项因测试脚本时序问题未单独验证 | 完成（有一项未单独验证） |
| P0-5 UV-4 | 悬浮窗 | **通过**，但有一条重要限制（某些 App 会让所有悬浮窗消失） | 完成 |
| P0-6 UV-1 | 全屏观看帧率 | **通过**（10 fps 目标和 5 fps 底线在模拟器上都达到；CPU 路径有成本） | 完成 |

---

## P0-3 UV-3 同一 App 冲突检测：通过

脚本：`scripts/p0/uv3_conflict.sh`（全部断言通过）。

### 方法
`IActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)` 列出某块屏幕上的根任务（任务 id、顶部 Activity、是否可见）。新增 `DisplayTasks.list()`，经 AIDL `listTasks(displayId)` 暴露。

### 结果
| 场景 | 现象 | 能否检测 | 检测方法 |
|---|---|---|---|
| ① 开始前，用户在主屏前台用着目标 App | 主屏 display 0 的第一个可见根任务就是该 App | **能**，无误报 | 轮询 `listTasks(0)`：第一个 `visible=true` 的根任务的包名。验证了三种状态：PhoneAgent、设置、桌面（按 Home 后不再识别为设置） |
| ② 运行中，用户从**启动器图标**打开同一 App（真实用户路径） | 启动器在主屏新建了一个任务；**虚拟屏上的任务消失**（被抢走），主屏出现该 App | **能** | 轮询 `listTasks(虚拟屏)`：自己的任务 id 消失 |
| ② 运行中，`am start --display 0 -n` | 虚拟屏上的任务被整个**移到主屏**，虚拟屏变空 | **能** | 同上 |
| ③ 不带 display 的组件启动（被虚拟屏实例「吸收」） | 任务列表**完全不变**；主屏没有任何反应（`Activity not started, intent has been delivered to currently running top-most instance`）；`dumpsys activity` 的 `launchCount` 也不变 | 任务列表**检测不到**；事件日志里有信号 | `logcat -b events` 里出现孤立的 `wm_task_moved: [任务id,…]`（**没有**伴随的 `wm_create_activity`）。注意：虚拟屏内部导航也会产生 `wm_task_moved`，但同时有 `wm_create_activity`，所以要靠「无创建」和「agent 自己没有动作」来区分，是**启发式**，误报率需要在真实任务中实测 |
| ④ 无误报：虚拟屏内部导航、返回 | 任务 id 不变，主屏前台不变 | — | 连续点击进入子页、返回，任务列表稳定 |

### 对 PRD 的影响
- FR-CON-3（开始前等待）、FR-CON-4（运行中被抢走判中断）：**可实现**，用任务列表轮询即可（建议 300～500ms 一次），不需要额外的系统接口。
- FR-CON-6（吸收）：用户**真实**的打开路径（启动器图标）是被「抢走」而不是「吸收」，所以吸收主要出现在没有 display 信息的启动来源（例如 shell、部分深链接/通知）。任务列表检测不到，事件日志有启发式信号；**v1 按已知限制处理**，启发式检测列为后续优化。
- 通过标准达成（①②都能稳定检测、无误报），不需要降级。
- 一个实现细节：任务被抢走后，应立即停止对虚拟屏的操作，否则 agent 的触摸会落在已经不属于它的 display 上。

### 注意
### 补测：Gmail、Google 日历（T2 改为 Gmail 后）
脚本：`scripts/p0/uv3_apps.sh`，对两个 App 各跑四个场景，**结果与设置完全一致**：
| 场景 | Gmail | Google 日历 |
|---|---|---|
| ① 开始前，用户在主屏打开该 App → 识别为主屏前台；回桌面后不再识别 | ✅ | ✅ |
| ② 运行中，用户点启动器图标 → 虚拟屏任务消失、App 出现在主屏（可检测） | ✅ | ✅ |
| ③ 不带 display 的启动 → 被吸收，任务列表不变；事件日志里有孤立的 `wm_task_moved`（`wm_create_activity`×0） | ✅ 同设置 | ✅ 同设置 |
| ④ 虚拟屏内部操作（返回、滑动）→ 任务 id 不变，无误报 | ✅ | ✅ |
结论：冲突检测方案对三个 App 都适用，不依赖具体 App 的启动模式。T2（Gmail）的「开始前等待」和「运行中被抢走→中断」都可实现。

---

## P0-4 UV-2 任务从虚拟屏移到主屏（交接）：设置、日历通过

脚本：`scripts/p0/uv2_handoff.sh`（全部断言通过）。Gmail、Amazon 的补测见 UV-5/UV-6 完成之后。

### 方法
`IActivityTaskManager.moveRootTaskToDisplay(taskId, 0)`（经 AIDL `moveTaskToDisplay`，任务 id 来自 `listTasks`）。PRD 8.6 里提到的 `moveRootTaskToDisplay` 只验证过「同一 display 会报错」，这里验证了从虚拟屏移到主屏。

### 结果
| 检查 | 设置（Network & internet 子页） | 日历（新建事项草稿，已输入标题未保存） |
|---|---|---|
| 任务出现在主屏最上面并可见 | ✅ | ✅ |
| 虚拟屏变空 | ✅ | ✅ |
| 是同一个 Activity 实例（`dumpsys` 里 ActivityRecord 标识不变，事件日志没有 `wm_destroy_activity`），没有销毁重建 | ✅ | ✅ |
| 页面状态保留 | ✅ 仍是 Network & internet 子页 | ✅ 草稿标题「UV2 草稿 draft-123」仍在 |
| top focused display | 0（主屏），移动后正常 | 0 |
| 停止后虚拟屏清理 | ✅ 只剩 display 0 | ✅ |

### 结论
- **通过标准达成**：移动后页面状态与移动前一致，Activity 不重建、不崩溃，不需要降级。
- FR-HO-4/5（交接）**可实现**。移动是即时的（约 1 秒内完成）。
- 实现细节：移动后任务位于主屏最上面，屏幕由用户接管；agent 必须在移动前停止对该 App 的所有操作（FR-HO-3）。
- 未覆盖：Gmail 和 Amazon 上的状态保持（Amazon 的购物流程含 WebView），UV-5/UV-6 完成后补测，结果回写本表。

---

## P0-5 UV-4 悬浮窗：通过（有限制）

脚本：`scripts/p0/uv4_overlay.sh`（全部断言通过）。原型：`FloatingWindow`（`TYPE_APPLICATION_OVERLAY`，`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`，只显示文字和「停止」按钮，可拖动，松手吸附左右边缘）。需要 `SYSTEM_ALERT_WINDOW` 权限（验证时用 `adb shell appops set com.android.phoneagent SYSTEM_ALERT_WINDOW allow`，真实使用走系统设置页引导）。

### 测试方法
主屏输入框弹出键盘，记录基线（键盘是否显示、`mCurFocusedWindow`、`mCurClient`、`mTopFocusedDisplayId`），然后依次：显示小窗 → 拖动 → 再拖到另一侧 → 点击小窗主体 → 在虚拟屏上滑动 → 点小窗上的停止；每一步后检查基线是否不变，并在主屏软键盘上点一个字母确认仍能打字。

### 结果
| 检查 | 结果 |
|---|---|
| 窗口带 `FLAG_NOT_FOCUSABLE`，实际显示在屏幕上 | ✅ |
| 显示、拖动、点击期间：键盘仍显示，焦点窗口、输入连接、top focused display 都不变 | ✅ |
| 以上每一步之后，用户在主屏软键盘上打字仍进入主屏输入框 | ✅ |
| 拖动并吸附：拖到右侧吸附到右边缘（右边界=1080），再拖到左侧吸附到左边缘（左边界=0） | ✅ |
| 点击小窗主体 → 点击回调；点停止按钮 → `Agent.stop()` 被调用 | ✅ |
| 虚拟屏上有动作（滑动）时，小窗仍显示，键盘/焦点不受影响 | ✅ |

### 重要限制：目标 App 可以让所有悬浮窗消失
验证中发现：当「设置」在虚拟屏上运行时，悬浮窗被系统**隐藏**（`dumpsys window` 里 `mForceHideNonSystemOverlayWindow=true`，`isReadyForDisplay()=false`）。这是因为设置会对敏感页面设置「隐藏非系统悬浮窗」标志，而这个标志**对整个系统生效，不区分屏幕**：即使该 App 只在虚拟屏上，用户主屏上的悬浮窗也会消失。
- Google 日历在虚拟屏上运行时，悬浮窗**正常显示**（实测）；Gmail 待实测。
- **Amazon 需要在结算相关页面实测**（UV-6 的一部分）：付款类页面常设置该标志。
- 这意味着小窗**不能作为唯一的状态通道**。PRD 里的系统通知（FR-NOTIF-2）要从「补充」升级为**必需的兜底**：状态变化（尤其是需要交接、需要回答、失败）必须发通知；并且 agent 要能检测到小窗被隐藏（`isReadyForDisplay`）。

### 对 PRD 的修改
- FR-FW-3：验证通过，去掉 ⚠。
- 新增 FR-FW-12（小窗可能被目标 App 隐藏，状态必须有通知兜底），FR-NOTIF-2 改为必需。
- 风险表新增一条。

---

## P0-6 UV-1 全屏观看的帧率：通过（模拟器；有成本）

脚本：`scripts/p0/uv1_fps.sh`。画面源是 debug 里的 `FpsTestActivity`（每帧都变化的动画，在虚拟屏上约 60 fps 产出）；主屏上 PhoneAgent 在前台显示预览。统计 10 秒内的三个数：虚拟屏产出的帧数（`ImageReader` 回调）、转成位图的帧数（CPU 路径）、界面实际重组/显示的帧数（`Image` 的 `SideEffect` 计数）。采集频率 `previewFps` 可设。

### 方法与结果
当前实现路径：`ImageReader`（RGBA_8888）→ CPU 复制成 Bitmap → 缩放到 540x1200 → Compose `Image`。为了在采集频率低于画面源频率时**不丢掉静止前的最后一帧**，改成「太快就先不取，延迟补取一次」（原来的实现会让预览停在旧画面）。

| 设定的采集频率 | 虚拟屏产出 | 转成预览 | 界面显示 | 主进程 CPU（占单核） |
|---|---|---|---|---|
| 60 | 63.3 fps | 63.3 fps | 62.3 fps | 空闲时可达；满负荷约占满一个核（99%） |
| 30 | 31.7 fps | 31.4 fps | 31.4 fps | — |
| **10（目标）** | **9.8 fps** | 9.8 fps | **9.8 fps** | **约 25%** |
| **5（底线）** | **5.2 fps** | 5.2 fps | **5.2 fps** | — |

数据说明：前四行来自一次干净的测量；CPU 数据来自另一次测量。模拟器所在主机负载高时数字会明显下降（同样设定 60 fps 时只有约 17 fps，且进程占满一个核），所以**不能把「60 fps」当作保证**，要关注的是 10 fps 附近的行为。

### 结论
- **通过标准达成**：5 fps 底线和 10 fps 目标，界面实际显示的帧率都达到了设定值，不需要降级。
- **有成本**：10 fps 时 PhoneAgent 主进程大约占一个核的 25%；采集频率提到 60 fps 时一个核会被占满。CPU 路径的瓶颈是每帧 10MB 的复制和缩放（1080x2400 RGBA）。
- **真机未验证**：模拟器跑在 Apple 芯片主机上，真机的 CPU 和内存带宽不同。如果真机上 10 fps 达不到或耗电太高，优化方向已经明确：①`ImageReader` 用 `HardwareBuffer` 零拷贝（`USAGE_GPU_SAMPLED_IMAGE` + `Bitmap.wrapHardwareBuffer`）；②全屏观看时把虚拟屏的输出 Surface 换成 `SurfaceView` 的 Surface（`VirtualDisplay.setSurface`），agent 的截图改用 `PixelCopy` 取；③降低预览缩放尺寸。这些都是 PRD 里「画面采集架构」的备选，不需要现在做。
- 对 PRD 的影响：FR-FS-2、NFR-PERF-1 不变（10 目标 / 5 底线）；NFR-PERF-3 增加「全屏观看会占用约 1/4 个核」的备注；采集架构按现有 ImageReader 路径起步，优化方案作为后备。

### 实现中顺带修的问题
预览原先按「距离上一帧 ≥1 秒才处理」丢帧，画面静止前的最后一帧会被丢掉，预览停在旧画面。已改成延迟补取（`Agent.onFrame` / `processLatest`）。

---

## P0-1 UV-5 三个目标 App 在虚拟屏运行：Google 日历、Gmail 通过（Amazon 见下一节）

T2 已由 Telegram 改为 Gmail（PRD v0.4），Telegram 相关验证全部取消。以下是手动逐步探索加脚本（`scripts/p0/`）得到的结果。截图、界面树含账号邮箱和邮件内容，**未提交**。

### Google 日历
| 检查 | 结果 |
|---|---|
| 在虚拟屏上启动并正常渲染 | ✅ |
| 界面树可读：日程视图里每个事项是 `View` 节点，`contentDescription` 形如「Halloween, All day: 」；新建事项页里日期、时间、标题都有文字 | ✅ |
| `ACTION_SET_TEXT` 写中文+英文标题（「P0测试事项 Alice review」） | ✅，标题框读回来一致 |
| 取消草稿：「Cancel」→「Discard this event?」→「Discard」 | ✅（未保存任何事项） |

### Gmail（T2 的目标 App）
| 检查 | 结果 |
|---|---|
| 在虚拟屏上启动、首次引导（欢迎页、账号设置页、Meet 引导） | ✅，都能用界面树文字点掉 |
| **首次弹出系统通知权限对话框**（`com.android.permissioncontroller`，也出现在虚拟屏上） | 按 PRD 6.7.3「不授予任何权限」点「Don't allow」。注意：**每次启动 Gmail 都会再弹一次**（直到拒绝两次），agent 需要稳定处理这个弹窗 |
| 新建邮件：收件人、主题、正文各自的节点 | 收件人：`EditText`（在 `peoplekit_autocomplete_chip_group` 里，没有 id）；主题：`id=subject`；**正文是 WebView**（`wc_body`），里面的 contenteditable 节点是 `EditText` |
| 写收件人：`ACTION_SET_TEXT` 写入邮箱地址 | ✅ 文字写进去了，但**不会自动变成芯片**：界面出现联系人建议行「Add recipient / qijie_jerry@163.com」（`peoplekit_listview_contact_method`，界面树可读），点这一行才生成芯片。生成后界面树里有 `desc="Recipients: qijie_jerry@163.com,"` 和 `peoplekit_chip` 按钮 |
| 联系人建议（只给名字时的查找）| 建议行在界面树里可读（上面那一行就是建议）；只给名字的情形（多个匹配/找不到）预期同样靠这个列表，**未单独测** |
| 写主题、写正文（WebView） | ✅ `ACTION_SET_TEXT` 在 WebView 内的可编辑节点上生效，中文英文混排，界面树读回一致 |
| 发送按钮 | `id=send`，`desc="Send"`，位置 [827,20,954,146]，写完内容后变为可用 |
| 点发送后的反馈 | 收件箱里出现 snackbar：`id=snackbar_main_content`，文字「Sent」，操作按钮 `action_text="Undo"`，界面树可读。**撤销提示持续时间超过 13 秒**（默认的撤销窗口是 5 秒，但 snackbar 存在得更久），所以「撤销窗口结束」要用固定等待而不是「等 snackbar 消失」 |
| 完成验证：「已发送」文件夹 | ✅ 打开抽屉 → Sent，第一封就是刚发的：`senders="To: qijie_jerry"`（**只显示收件人的名字部分，不是完整地址**）、`subject`、`snippet`、`date`。**要拿到完整地址，必须打开这封邮件，点「to qijie_jerry」展开，`to_details` 里是 `qijie_jerry@163.com`** |
| 丢弃草稿 | ✅ 撰写界面「More options」→「Discard」，**没有确认对话框**，草稿箱变为「Nothing in Drafts」。草稿详情页（从草稿箱点进去）的「Discard drafts」按钮有确认对话框（OK/Cancel） |
| 草稿自动保存 | ⚠ 撰写到一半 App 被杀掉（验证中重装 App 导致），Gmail 自动保存了一份草稿（收件人写在了正文里的那次错误输入），草稿箱出现 1 份。**这印证了 T2-R9：任务开始/重试前必须检查草稿箱，并只处理自己创建的草稿** |

### Gmail 发送按钮硬拦截（FR-SAFE-12 原型）：通过
`SafetyGuard` 新增 Gmail 发送按钮识别（包名 `com.google.android.gm` 且 `id` 以 `:id/send` 结尾，或描述为 Send/发送）。默认一律拦截；`armGmailSend(收件人)` 只有在草稿里确实有该收件人时才布防 120 秒，只放行一次。验证期间**测试邮件只能发给 `qijie_jerry@163.com`**，布防入口里写死了这个白名单。
| 检查 | 结果 |
|---|---|
| 未布防，按文案点「Send」 | ✅ `BLOCKED 命中 Gmail 发送按钮` |
| 未布防，按坐标点击发送按钮区域（890,83） | ✅ 被拦截 |
| 未布防，语义点击（`ACTION_CLICK`） | ✅ 被拦截 |
| 布防后点击一次 | ✅ 放行，邮件发出（只发了一封，收件人 qijie_jerry@163.com） |
| 放行用完后再点发送（新草稿，收件人相同） | ✅ 被拦截（一次性生效） |

**原型的不足（实现 T2 时要补）**：布防只绑定了收件人，没有绑定主题和正文的内容哈希、也没有令牌过期之外的其他失效条件；PRD T2-R7 要求「内容一致才放行」，需要在正式实现里补上。

### 本次验证发现的、需要写进 PRD 的点
1. **「向当前聚焦的输入框写文字」不可靠**：Gmail 撰写界面里收件人框和 WebView 正文**都**显示 `focused`，我的实现先写到了正文里。动作必须能**指定目标节点**（按界面树里的节点/坐标），PRD 6.2 的「输入文字」要改。
2. 收件人需要**两步**：写入文字 + 点击联系人建议行，才会生成芯片；验证要读 `Recipients:` 描述。
3. 「已发送」列表里收件人只有名字，完整地址需要**打开邮件并展开**；完成验证因此至少多 3 步（打开邮件、展开、读取），要计入步数预算。
4. 每次启动 Gmail 都会弹系统通知权限对话框。

### UV-2 补充：Gmail
`scripts/p0/uv2_handoff.sh` 的 Gmail 段：移动本身的检查全部通过（任务出现在主屏最上面并可见、虚拟屏变空、Activity 是同一个实例、事件日志里没有 `wm_destroy_activity`）。**「草稿内容仍在」没有单独验证**：脚本里点「Add recipient」建议行偶尔找不到，草稿没填成（测试时序问题，不是移动的问题）。日历草稿已经证明「移动后输入内容保留」，Gmail 是同一机制，风险低；T2 本身没有交接，这一项对 v1 影响很小。

---

## P0-1 UV-5 续：Amazon

| 检查 | 结果 |
|---|---|
| 在虚拟屏上启动并正常渲染 | ✅（登录态正常，首页、搜索、商品页、购物车、结算页都正常） |
| 界面树可读（含网页内容） | ✅ **但有加载延迟**：页面刚出现时界面树只有外壳（WebView 一个节点，11 行），等几秒后才有网页内容（几百到两千多行）。**读取界面树必须带等待/重试**，不能一次读不到就判定失败 |
| 搜索 | 点搜索框 → 输入框是 `EditText`（`rs_search_src_text`），`ACTION_SET_TEXT` 写入成功；**没有出现建议列表**，需要回车提交：新增了向虚拟屏发送带 displayId 的按键（回车），搜索结果出现，商品条目在界面树里有「Go to detail page for "商品名…"」 |
| 商品页 | ✅ 「Add to Cart」可见（底部固定条，`desc="Add to Cart"`）；数量选择在 `mobileQuantityDropDown`；**「Buy Now」在网页里的节点是 `id=buy-now-button`、文案却是「Submit」**——靠文案匹配拦截不到，**必须同时匹配 id** |
| 购物车页 | ✅ 「Proceed to checkout (1 item)」、小计、商品、**数量有明确描述**（`Quantity is 1`，`Increase quantity by one`）；**测试开始前购物车里已经有 1 件商品**（用户说已清空，但显示 1 件）。没有改动它 |
| 去结算后的中间页 | 出现「Prime for Young Adults」推广页（Try Prime for Young Adults / **No thanks**）。点「No thanks」继续。**「Try Prime」「Join Prime」是订阅（会扣费），必须也加入硬拦截** |

## P0-2 UV-6 Amazon 结算页：通过，T3 成立

到达最终的「Place your order」页（没有点击任何下单按钮）。

| 检查 | 结果 |
|---|---|
| 画面是否黑屏（`FLAG_SECURE`） | **不是**：虚拟屏截图完整可读（总价、地址、支付方式都能看到），证据截图可用 |
| 界面树 | ✅ 完整可读：`placeYourOrder`（容器）、**`placeOrder` 按钮，文案「Place your order」**、`Items:`/`Shipping & handling:`/`Order total:` 金额、`Delivering to …` 地址、`Paying with Mastercard …`、`changePaymentOptionLink`、`change-delivery-link`。商品名可以在界面树里找到 |
| 数量 | ⚠ 最终页里**没有直接的数量文字**（只有一个编码过的节点 id 和商品小计），所以**数量要在购物车页核对**（`Quantity is N`），最终页核对商品名和小计 |
| 硬拦截（页面级锁定） | ✅ 在这一页点无害控件（「Change delivery address」）被拦截：`BLOCKED 页面级锁定：当前页面含「」`（命中的是节点 id `placeOrder`，文案为空，说明 id 匹配生效）。**为了安全没有测试直接点「Place your order」和「Buy Now」**：页面级锁定已经证明会拦住这一页的所有点击 |
| 悬浮窗 | ✅ Amazon 的结算页在虚拟屏上时，悬浮窗正常显示（没有被隐藏） |
| 回退 | 返回两次回到购物车，购物车仍是原来的 1 件，**没有下单**（全程未点击 Place your order / Buy Now / Try Prime） |

### 结论
- **T3 成立**：能到达提交订单页；页面可读（画面+界面树）；硬拦截有效；不是安全窗口，不需要降级。
- 需要调整的点：①数量改在**购物车页**核对；②最终页核对商品名、小计、「Place your order」按钮可见；③中间会出现 Prime 推广页（点「No thanks」）；④**「Try Prime / Join Prime / Start free trial」等订阅按钮要加入硬拦截**；⑤硬拦截必须匹配**节点 id**（`buy-now-button`、`placeOrder`）；⑥读界面树要等页面加载（重试）；⑦购物车里可能已有别的商品（T3-R7 已覆盖）。
