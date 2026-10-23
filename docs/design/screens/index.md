# 界面截图索引

这些截图由 `scripts/design/render_screens.sh` 渲染（debug 构建里的 `GalleryActivity` 在 Android 14 模拟器 Pixel 8 上逐个渲染界面，再用 `adb screencap` 保存），每个界面一张深色、一张浅色。
界面代码在 `app/src/main/java/com/android/phoneagent/ui/`；视觉规范见 `docs/DESIGN.md`；每个界面也有深色和浅色的 `@Preview`（`ui/gallery/Previews.kt`，Android Studio 里按目录条目逐个显示）。

注意：截图里的对话内容、画面缩略图都是**假数据**，本阶段不接入 agent 逻辑。对话页的截图是滚到最新消息的状态，所以顶部头像下面能看到上一张卡片从半透明顶栏下面滑过。

## 逐个界面

| 界面 | 说明 | 深色 | 浅色 |
|---|---|---|---|
| 启动页 | 图标 + 名称「PhoneAgent」，纯黑背景（始终深色令牌）；之前还有一个系统启动页（见 flow/0） | [splash_dark.png](splash_dark.png) | [splash_light.png](splash_light.png) |
| 引导 1/4：起名和选头像 | 输入名字（最长 12 个字符），从 6 个原创头像里选一个 | [onboarding_1_name_dark.png](onboarding_1_name_dark.png) | [onboarding_1_name_light.png](onboarding_1_name_light.png) |
| 引导 2/4：Shizuku 检查 | 显示真实的 Shizuku 状态（未运行/未授权/正常） | [onboarding_2_shizuku_dark.png](onboarding_2_shizuku_dark.png) | [onboarding_2_shizuku_light.png](onboarding_2_shizuku_light.png) |
| 引导 3/4：通知权限 | 真实权限状态；「允许通知」会弹系统授权框 | [onboarding_3_notification_dark.png](onboarding_3_notification_dark.png) | [onboarding_3_notification_light.png](onboarding_3_notification_light.png) |
| 引导 4/4：悬浮窗权限 | 真实权限状态；「去开启」跳转系统设置页；「完成」进入对话首页 | [onboarding_4_overlay_dark.png](onboarding_4_overlay_dark.png) | [onboarding_4_overlay_light.png](onboarding_4_overlay_light.png) |
| 对话首页：空状态 | agent 自我介绍 + 三个示例任务（点一个等于发出该任务）；顶部头像和名字胶囊，底部输入栏 | [chat_empty_dark.png](chat_empty_dark.png) | [chat_empty_light.png](chat_empty_light.png) |
| 对话：实时画面卡片 | 用户消息、agent 简短回复、实时画面卡片（缩略画面、当前步骤、步骤序号、停止按钮） | [chat_working_dark.png](chat_working_dark.png) | [chat_working_light.png](chat_working_light.png) |
| 对话：确认卡片 | T2 等待确认：收件人、主题、正文全文，发送 / 修改 / 取消；实时画面卡片折叠为「等你确认后发送」 | [chat_confirm_dark.png](chat_confirm_dark.png) | [chat_confirm_light.png](chat_confirm_light.png) |
| 对话：选项提问 | agent 在对话里提问，选项显示为可点击的按钮 | [chat_question_dark.png](chat_question_dark.png) | [chat_question_light.png](chat_question_light.png) |
| 对话：成功结果 | 「已完成」卡片，附证据截图和文字清单 | [chat_success_dark.png](chat_success_dark.png) | [chat_success_light.png](chat_success_light.png) |
| 对话：失败结果 | 失败原因 + 可能残留的内容 + 重试 / 查看步骤 | [chat_failure_dark.png](chat_failure_dark.png) | [chat_failure_light.png](chat_failure_light.png) |
| 对话：交接结果 | 说明停在哪里 + 「去完成」按钮 | [chat_handoff_dark.png](chat_handoff_dark.png) | [chat_handoff_light.png](chat_handoff_light.png) |
| 全屏观看 | 顶部状态和步骤序号、约 85% 的画面（点击位置圆点）、底部暂停 / 停止 | [fullscreen_dark.png](fullscreen_dark.png) | [fullscreen_light.png](fullscreen_light.png) |
| 全屏观看：步骤列表展开 | 上滑底部栏展开，每一步「看到了什么、做了什么」 | [fullscreen_steps_dark.png](fullscreen_steps_dark.png) | [fullscreen_steps_light.png](fullscreen_steps_light.png) |
| 悬浮小窗：10 个状态 | 执行中、等待用户输入完成、需要用户回答、等待确认、需要交接、完成、失败、已暂停、应用冲突、已停止（小窗固定深色令牌） | [floating_states_dark.png](floating_states_dark.png) | [floating_states_light.png](floating_states_light.png) |
| 左侧抽屉 | App 名称、「对话」「任务记录」、最近任务、底部设置按钮；主页面被向右推开并压暗 | [drawer_dark.png](drawer_dark.png) | [drawer_light.png](drawer_light.png) |
| 任务记录 | 按时间倒序，结构化卡片（图标 + 标题行、分割线、结果） | [tasks_dark.png](tasks_dark.png) | [tasks_light.png](tasks_light.png) |
| 设置页 | 名字和头像、Shizuku 与权限状态、外观（深色/浅色）、开发者调试入口 | [settings_dark.png](settings_dark.png) | [settings_light.png](settings_light.png) |

## 真机（模拟器）上走一遍流程的截图

在已安装的 App 上实际点击得到的截图（深色主题，名字「Nova」、头像 6）。流程：系统启动页 → 启动页 → 引导 → 对话首页 → 点示例任务 → agent 回复 → 实时画面卡片（步骤每隔几秒推进）→ 全屏观看 → 确认卡片 → 点发送 → 成功结果；另外抽屉、任务记录、设置。

| 文件 | 内容 |
|---|---|
| [flow/0_system_splash.png](flow/0_system_splash.png) | 系统启动页（SplashScreen）：纯黑背景 + App 图标 |
| [flow/1_chat_empty.png](flow/1_chat_empty.png) | 引导完成后的对话首页空状态 |
| [flow/2_fullscreen_watch.png](flow/2_fullscreen_watch.png) | 点击实时画面卡片进入全屏观看（带点击位置圆点） |
| [flow/3_confirm_card.png](flow/3_confirm_card.png) | 确认卡片：发送 / 修改 / 取消 |
| [flow/4_sending.png](flow/4_sending.png) | 点发送后：「已确认，正在发送…」，实时画面卡片继续推进 |
| [flow/5_success_result.png](flow/5_success_result.png) | 成功结果：已发送 + 证据 |
| [flow/6_drawer.png](flow/6_drawer.png) | 左侧抽屉 |
| [flow/7_task_records.png](flow/7_task_records.png) | 任务记录 |
| [flow/8_settings.png](flow/8_settings.png) | 设置页 |
