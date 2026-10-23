# PhoneAgent

## 项目目标
安卓手机 Agent App。最终形态：App 调用 Claude API，在用户看不见的虚拟屏幕（VirtualDisplay）上操作第三方 App；用户同时可正常使用自己的手机，真实屏幕和输入法不受影响。

**当前阶段：技术验证原型**，只验证底层能力，不接入 Claude，代码越简单越好：
1. 接入 Shizuku，通过 UserService 以 shell 权限运行特权代码
2. 创建 1080x2400 的 VirtualDisplay，输出到 ImageReader，不显示在真实屏幕
3. 在虚拟屏幕上启动指定 App（先用系统“设置”）
4. 本 App 界面显示虚拟屏幕实时小预览（每秒一帧）
5. 测试按钮：指定坐标点击、滑动、返回键（事件必须定向到虚拟屏幕）
6. 测试按钮：向虚拟屏幕当前聚焦输入框写入中文，不弹出/占用用户输入法
7. 前台服务保活，切到别的 App 后虚拟屏幕继续工作

## 运行环境
- Android Studio 模拟器：Pixel 8，Android 14（API 34），带 Google Play
- 已通过 adb 启动 Shizuku
- applicationId：`com.android.phoneagent`，minSdk 30

## 技术栈
Kotlin + Jetpack Compose + Shizuku（api / provider）。参考 scrcpy server 的 `--new-display` 实现（反射调用系统隐藏接口）。

## 工作方式
- **先给计划，用户确认后再写代码。** 不要未经确认就实现。
- 每个阶段结束前说明如何验证；遇到系统接口行为不确定时先做最小实验，不要猜。
- 保持简单：原型阶段不做架构抽象、不引入多余依赖。
- 提交信息简洁，不擅自 push。

## 调试与自测
- debug 构建带 `CmdReceiver`（`src/debug`，要求发送方持有 `DUMP` 权限，仅 adb shell 可发）：
  `adb shell am broadcast -a com.android.phoneagent.CMD -n com.android.phoneagent/.CmdReceiver --es cmd <connect|display|launch|tap|swipe|back|text|dump|stop>`
- 构建需要 Android Studio 自带 JBR：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew installDebug`
- 特权代码在 `priv/`（UserService，shell uid）；`UiAutomationHelper` 是可复用的界面树/输入模块。
