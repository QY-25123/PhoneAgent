# 怎么在模拟器上试用

适用于 debug 构建（开发 Key 来自 `local.properties` 的 `CLAUDE_API_KEY`，release 构建没有 Key，不能用）。

## 一、准备（一次性）
1. 模拟器：Pixel 8，Android 14，带 Google Play；已登录 Google 账号（日历、Gmail 用同一个账号）。
2. Shizuku 已通过 adb 启动并授权给 PhoneAgent（重启模拟器后要重新启动 Shizuku）。
3. 安装：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew installDebug`（或在 Android Studio 里点运行）。

## 二、在对话里用
打开 PhoneAgent，首页是对话页。**点示例只会把文字填进输入框，不会发送**，你可以改一改再点发送。

### 日历
- 「周三下午3点帮我在日历加个 [PA测试] 评审会」→ agent 复述一句话并开始，几秒后回复「日历里已有…」。
- 「帮我加个日历事项」→ agent 会一次问全缺的信息（标题、哪天、几点），能给选项时显示成按钮。
- 「提醒我明天下午2点交报告」→ 做成日历事项，复述里会写明。
- 相对日期（明天、下周一、周五）由程序算好对照表，直接换成具体日期并在复述里写出来，不会追问是哪一天。

### 邮件（发送前一定要你确认）
- 「给 qijie_jerry@163.com 发邮件，说周五的评审改到下午3点」→ agent 写好主题和正文，在虚拟屏上打开 Gmail 写邮件页，**从页面上实际读出收件人、主题、正文**，在对话里显示确认卡片（发送 / 修改 / 取消）。
  - **发送**：agent 核对页面内容与卡片一致后才放行一次，点击发送，等「撤销发送」时间结束，到「已发送」里核对。
  - **修改**：直接在输入栏说怎么改（也可以点「修改」再说），agent 改完会再显示一张新的确认卡片。
  - **取消**：丢弃本次创建的草稿，并确认草稿箱没有残留。
- **测试时只发给 `qijie_jerry@163.com`，主题加 `[PA测试]` 前缀。**

### 看 agent 在做什么
任务开始后对话里出现实时画面卡片（缩略画面、当前步骤、步骤序号、停止按钮）；点缩略画面进入全屏观看，上滑展开步骤列表。随时可以点「停止」，虚拟屏会被清理。

## 三、运行记录
每次任务保存在设备上：`/sdcard/Android/data/com.android.phoneagent/files/runs/<时间>/`
- `call_NN_<类型>.json`：每次模型调用的输入、输出、耗时、token
- `step_NN.jpg` / `step_NN.json`：每个循环迭代的标注截图、界面文本、模型的决定、执行结果
- `summary.json`：总耗时、模型调用数、token、大致费用

取出：`adb pull /sdcard/Android/data/com.android.phoneagent/files/runs/ ./runs`

## 四、命令行测试脚本（会产生真实模型费用和真实日历事项/邮件）
```
scripts/m2_task.sh "任务文字" ["对追问的回答" ...] ["@SEND"|"@MODIFY"|"@CANCEL" ...]   # 提交一条任务并等结果
TAG=PA测试X CASES="B C D E F" scripts/m2_t1_suite.sh                                # 日历 5 个用例
scripts/m2_verify_event.sh "标题" "yyyy-MM-dd HH:mm" "yyyy-MM-dd HH:mm"               # 用日历内容提供者核对事项
scripts/regress.sh                                                                    # 底层回归
```
单元测试：`./gradlew testDebugUnitTest`。

## 五、模型
澄清、每步决策、自查都用 `claude-haiku-5-5`（更快更便宜）。debug 命令可以临时切换做对比：
`adb shell am broadcast -a com.android.phoneagent.CMD -n com.android.phoneagent/.CmdReceiver --es cmd model --es step claude-sonnet-5-5`
