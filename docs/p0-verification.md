# P0 技术验证记录

对应 `docs/PRD.md` 8.6 节。环境：Android 14（API 34）模拟器 Pixel 8，arm64。脚本在 `scripts/p0/`，共用函数 `scripts/p0/lib.sh`。
截图、界面树含个人信息（账号邮箱、聊天内容），**不提交**；脚本默认输出到 `/tmp/p0_out`。

| 项 | 能力 | 结论 | 状态 |
|---|---|---|---|
| P0-1 UV-5 | 三个目标 App 在虚拟屏运行 | Google 日历通过；Telegram、Amazon 未完成（见下） | 进行中 |
| P0-2 UV-6 | Amazon 结算页：安全窗口与界面树 | 未开始（Amazon 未安装） | 阻塞 |
| P0-3 UV-3 | 同一 App 冲突检测 | **通过**（吸收场景为启发式） | 完成 |
| P0-4 UV-2 | 任务移到主屏（交接） | 设置、日历通过；Amazon、Telegram 待补测 | 部分完成 |
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
- `Settings` 是 singleTask 风格的应用，启动器启动时的行为（新建任务）对别的 App 可能不同，在 Telegram、Google 日历上需要再确认（UV-5 完成后补测）。

---

## P0-4 UV-2 任务从虚拟屏移到主屏（交接）：设置、日历通过

脚本：`scripts/p0/uv2_handoff.sh`（全部断言通过）。Amazon、Telegram 的补测见 UV-5/UV-6 完成之后。

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
- 未覆盖：Telegram 和 Amazon 上的状态保持（Amazon 的购物流程含 WebView），UV-5/UV-6 完成后补测，结果回写本表。

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
- Google 日历、Telegram 在虚拟屏上运行时，悬浮窗**正常显示**（实测）。
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
