# 输入法隔离实验记录

目标：用户在主屏打字时，agent 的任何操作都不能改变用户的输入目标，也不能收起用户的键盘。

环境：Pixel 8 模拟器，Android 14（API 34）。虚拟屏 flags 含 `TRUSTED | OWN_DISPLAY_GROUP | OWN_FOCUS`，没有 `SHOULD_SHOW_SYSTEM_DECORATIONS`。回归脚本：`scripts/ime_test.sh`、`scripts/back_test.sh`。

## 检查项（每个动作后）
| 列 | 含义 |
|---|---|
| shown | 主屏 `mInputShown` 仍为 true |
| focus | `mCurFocusedWindow` 仍是基线（主屏窗口） |
| client | `mCurClient` 仍是基线（输入连接没被切给别的 App） |
| topdisp | `mTopFocusedDisplayId` 仍为 0 |
| typing | 在主屏软键盘点一个字母，主屏输入框确实收到 |

## 结果一：方案 A（IME 策略）× 操作方式，返回键用带 displayId 的 KeyEvent
所有行的 `0 基线` 都 PASS，所有行的总结果都 FAIL。

| 策略 | 操作 | 输入连接被切走(client) | 键盘被收起(shown) | top focused display 被切走 | 备注 |
|---|---|---|---|---|---|
| 1 FALLBACK_DISPLAY（系统默认） | 触摸 | **是**（切给 Settings 的 uid 10118） | 动作 a、b 时仍显示但接收输入的是虚拟屏；c1 起被收起 | 是 | 主屏键盘上点字母，文字进了虚拟屏搜索框 |
| 1 FALLBACK_DISPLAY | 无障碍 ACTION_CLICK | **是** | 同上 | 是 | 和触摸没有区别 |
| 2 HIDE | 触摸 | 否 | **是，a 起就被收起** | 是 | 只是不让虚拟屏抢连接，键盘被收起 |
| 2 HIDE | 无障碍 ACTION_CLICK | 否 | **是** | 是 | 同上 |
| 0 LOCAL | 触摸 | **是**，且来回切换 | 部分动作被收起 | 是 | 比默认更乱 |
| 0 LOCAL | 无障碍 ACTION_CLICK | **是** | 部分动作被收起 | 是 | 同上 |

## 结果二：隔离实验——只启动 App，不注入任何输入
主屏键盘弹出时，执行 `am start --display <虚拟屏> ...`（`scripts/ime_test.sh -l`）：

| 策略 | client | shown | topdisp |
|---|---|---|---|
| 1 FALLBACK_DISPLAY | 被切走（uid 1000） | 被收起 | 被切走 |
| 2 HIDE | 未切走 | 被收起 | 被切走 |

## 结果三：返回键对比（`scripts/back_test.sh`）
| 返回方式 | 场景 | 虚拟屏页面 | 主屏 App | 结果 |
|---|---|---|---|---|
| 带 displayId 的 KeyEvent | 虚拟屏是 top focused display | network → home | 不受影响 | PASS |
| 带 displayId 的 KeyEvent | 主屏是 top focused display | network → home | 不受影响 | PASS |
| GLOBAL_ACTION_BACK | 虚拟屏是 top focused display | network → home | 不受影响 | PASS |
| GLOBAL_ACTION_BACK | 主屏是 top focused display | **不变** | **MainActivity 被关掉** | **FAIL** |

## 结论
1. **根因是 top focused display 被切走，而不是输入方式。** 只要虚拟屏上有 Activity 被启动或获得焦点（`am start --display`、触摸、ACTION_CLICK 引起页面跳转都会），`mTopFocusedDisplayId` 就变成虚拟屏，主屏窗口失去焦点，系统随之处理输入法：
   - 默认策略下，虚拟屏上的页面（如设置搜索页）自己请求输入法，输入连接被它抢走；
   - HIDE 策略下连接保留，但键盘被收起。
   `OWN_FOCUS` 只让每块屏有各自的窗口焦点，不阻止 top focused display 的切换，也不隔离输入法。
2. **方案 A 无法达成目标**：HIDE 能避免连接被抢，但收起键盘，两者只能二选一。
3. **方案 C（无障碍语义操作）无法达成目标**：ACTION_CLICK 引起的页面跳转同样切走 top focused display，结果和触摸一样。ACTION_SET_TEXT 本身不改变输入法状态（已验证），但必须先让目标输入框获得焦点，而获得焦点这一步就会触发上面的问题。
4. **GLOBAL_ACTION_BACK 必须禁用**：它作用于 top focused display，主屏是 top focused display 时会误关主屏的 App。返回键一律用带 displayId 的 KeyEvent（两种场景都只作用于虚拟屏）。

## 后续决定（已被下一节的实验取代）
曾采用「用户打字时暂停」加「动作后用注入 KEYCODE_UNKNOWN 切回主屏焦点」，实现为 `UserActivityGuard`。随后的实验证明有更好的办法（下一节），暂停降级为默认关闭的安全开关，切焦点和补切逻辑已删除。

## 当时列出的候选方向
- **A. 让 agent 让位于用户打字**：每次操作前检查主屏 `mInputShown`/输入目标，用户正在打字时暂停 agent 操作。能保证不干扰用户，代价是用户打字期间 agent 会暂停。最稳妥，不依赖系统内部行为。
- **B. 阻止启动时抢前台**：用 `ActivityOptions.setAvoidMoveToFront()` 之类的选项启动虚拟屏上的 Activity，看能否不改变 top focused display。未验证，且只覆盖「启动」，不覆盖页面内跳转和触摸。
- **C. 事后把 top focused display 切回主屏**（如 `IWindowManager.moveDisplayToTopIfAllowed(0)`）并重新显示键盘。键盘已经被收起过，用户会看到闪一下，且输入连接可能已被切走，不满足「不能收起」。
- **D. 把 agent 放到另一个 Android 用户/独立会话**，让输入法实例彼此独立。改动大，原型阶段不适合。

## 实验：VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED（结论：有效）

创建虚拟屏时加上 `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED`（Android 14 隐藏常量，值 `0x10000`，反射读取；要求同时有 `OWN_FOCUS`，shell 身份可用）。`dumpsys display` 里能看到 `FLAG_OWN_FOCUS FLAG_STEAL_TOP_FOCUS_DISABLED`。

测试方法：关掉 `UserActivityGuard` 的暂停和切回焦点逻辑，跑 `scripts/ime_test.sh`（主屏键盘弹出，在虚拟屏依次：点搜索框 → 写中文 → 返回 → 再进搜索页 → 返回；每步后检查键盘是否显示、焦点窗口和输入连接是否不变、top focused display 是否仍为 0、主屏软键盘打字是否仍进主屏输入框）。IME 策略为 HIDE。

| 配置 | 键盘被收起 | 焦点窗口被切走 | 输入连接被抢 | top display 被切走 | 主屏打字仍有效 | 总结果 |
|---|---|---|---|---|---|---|
| 对照：不加标志，守卫关 | **是**，a 起就被收起 | 是 | 否（HIDE） | **是**（切到虚拟屏） | 否（键盘没了） | FAIL |
| 实验：加标志，守卫关 | 否 | 否 | 否 | **否，全程为 0** | **是** | **PASS（6/6 步）** |

补充验证（加标志、守卫关，手动）：
- `am start --display` 启动设置后，top display 仍是 0；
- 主屏键盘弹出时，在虚拟屏点击搜索框、写入「你好，世界」，动作确实执行了（虚拟屏预览里搜索框显示该文字，界面树里也有），主屏键盘仍显示，用户随后在主屏键盘上打的 `qq` 进入主屏输入框。

结论：标志从源头阻止了虚拟屏抢走 top focused display，用户的键盘不再被收起、输入也不再被抢。之前所有问题的根因（top focused display 被切走）被直接消除，不再需要守卫。

### 落地
- 默认创建虚拟屏时加该标志（`Agent.stealTopFocusDisabled`，仅对照实验时才关）。
- 删除「注入 KEYCODE_UNKNOWN 切回主屏」和「500ms/1500ms 补切」。
- 「用户打字时暂停」保留为安全开关（`UserActivityGuard`），**默认关闭**，主界面有开关，状态持久化。
- 回归：`guard_test.sh` 打开开关测暂停/恢复；`ime_test.sh` 用默认配置（标志开、暂停关）测隔离，`CFG='--ez steal false'` 可复现对照组的失败。
