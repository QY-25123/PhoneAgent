# P0 技术验证记录

对应 `docs/PRD.md` 8.6 节。环境：Android 14（API 34）模拟器 Pixel 8，arm64。脚本在 `scripts/p0/`，共用函数 `scripts/p0/lib.sh`。
截图、界面树含个人信息（账号邮箱、聊天内容），**不提交**；脚本默认输出到 `/tmp/p0_out`。

| 项 | 能力 | 结论 | 状态 |
|---|---|---|---|
| P0-1 UV-5 | 三个目标 App 在虚拟屏运行 | Google 日历通过；Telegram、Amazon 未完成（见下） | 进行中 |
| P0-2 UV-6 | Amazon 结算页：安全窗口与界面树 | 未开始（Amazon 未安装） | 阻塞 |
| P0-3 UV-3 | 同一 App 冲突检测 | **通过**（吸收场景为启发式） | 完成 |
| P0-4 UV-2 | 任务移到主屏（交接） | 设置、日历通过；Amazon、Telegram 待补测 | 部分完成 |
| P0-5 UV-4 | 悬浮窗 | — | — |
| P0-6 UV-1 | 全屏观看帧率 | — | — |

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
