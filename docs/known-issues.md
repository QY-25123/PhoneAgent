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
- **修复**：`VirtualDisplayHolder.release()` 先用 `DisplayTasks.removeAll()` 关掉虚拟屏上的所有任务，再释放 display；`PrivilegedService.release()` 最后把焦点切回主屏。修复后连续 3 轮「创建→启动→点击→停止」以及完整回归之后，`dumpsys activity` 里只剩 display 0，top focused display = 0，没有残留窗口。
- **恢复办法（如果再遇到）**：`adb reboot` 重启模拟器，然后用 `adb shell <Shizuku 的 lib/arm64/libshizuku.so 路径>` 重新启动 Shizuku（授权记录保留，不用再点弹窗）。

## 3. 升级后旧 UserService
重装 App 会杀掉 App 进程，但 UserService 进程（`com.android.phoneagent:priv`）可能残留一小段时间，仍持有旧代码和旧的 VirtualDisplay。目前靠把 `Shizuku.UserServiceArgs.version()` 调大来强制重启 UserService，开发期需要手动递增。正式版需要：
- 版本号随 `versionCode` 自动变化；
- 启动时检查并清理上一版本遗留的虚拟屏。

## 4. IME 策略按 uniqueId 缓存（实验时的陷阱）
`setDisplayImePolicy` 的结果按虚拟屏的 uniqueId（`virtual:com.android.shell,2000,PhoneAgent,0`）缓存，display 被释放后依然有效，会影响之后创建的同名虚拟屏。做输入法实验时必须每次显式设置策略，`scripts/ime_test.sh` 已经这样做。

## 5. GLOBAL_ACTION_BACK 不能用
`UiAutomation.performGlobalAction(GLOBAL_ACTION_BACK)` 没有 displayId 参数，作用于 top focused display。主屏是 top focused display 时会对**主屏**执行返回（实测 PhoneAgent 的 MainActivity 被关掉，虚拟屏页面不变）。必须禁用，返回键一律用带 displayId 的 KeyEvent。详见 `docs/ime-experiments.md`。

## 6. UserActivityGuard 的局限
- **竞态**：守卫在动作开始前检查一次键盘。用户在检查之后、动作执行期间开始打字，动作仍会执行并可能收起键盘。窗口约几十到几百毫秒。
- **焦点补切**：动作返回后页面切换可能还没发生（新 Activity 要几百毫秒才 resume 并抢焦点），所以立即切一次，再在 500ms、1500ms 后各补一次。页面切换超过 1.5 秒（冷启动大型 App）时，焦点会在补切之后又被抢走，要等下一个动作之后才会再切回。
- **切回焦点的方法依赖系统实现**：向主屏注入一个孤立的 `KEYCODE_UNKNOWN` UP 事件，靠 PhoneWindowManager 对带 displayId 的按键调用「把该屏移到最前」。只在 Android 14 模拟器上验证过，厂商 ROM 可能不同。`setFocusedRootTask`、`moveTaskToFront`、`focusTopTask`、`moveRootTaskToDisplay` 都试过，没有一个能切回。
- **键盘检测依赖 HIDE 策略**：用 `dumpsys input_method` 的 `mInputShown` 判断用户是否在打字，前提是虚拟屏的 IME 策略为 HIDE（Agent 创建虚拟屏后会设置），否则虚拟屏里的 App 自己弹出的键盘会被误判为用户在打字，守卫会一直等。
- **用户长时间打字，agent 会一直等**：目前没有超时。

## 以后的优化方向（暂不做）
- **方案 2：阻止虚拟屏抢 top focus**。`ActivityOptions.setAvoidMoveToFront()` 只覆盖「启动」，不覆盖页面内跳转和触摸。更值得试的是 Android 14 framework 里存在的 `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED`（创建虚拟屏时的 flag，需要 `OWN_FOCUS`），从源头禁止虚拟屏拿走 top focus，如果有效，守卫的「动作后切回焦点」和「用户打字时暂停」都可以大幅简化。**只确认了常量存在，没有验证效果。**
- **方案 4：把 agent 放到独立的 Android 用户/会话**，输入法实例和焦点彻底隔离。改动大，要重新评估 Shizuku、前台服务、跨用户通信。
