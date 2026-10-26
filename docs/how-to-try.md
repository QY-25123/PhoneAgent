# 怎么在模拟器上试用

适用于 debug 构建（开发 Key 来自 `local.properties` 的 `CLAUDE_API_KEY`，release 构建没有 Key，不能用）。

## 一、准备（一次性）
1. 模拟器：Pixel 8，Android 14，带 Google Play；已登录 Google 账号（日历、Gmail 用同一个账号）。
2. Shizuku 已通过 adb 启动并授权给 PhoneAgent（重启模拟器后要重新启动 Shizuku）。
3. 安装：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew installDebug`（或在 Android Studio 里点运行）。

4. **悬浮小窗权限**（只有离开 App 时才用得上）：`adb shell appops set com.android.phoneagent SYSTEM_ALERT_WINDOW allow`。没开的话任务照常跑，只是离开 App 后没有小窗。

## 二、在对话里用
打开 PhoneAgent，首页是对话页。**点示例只会把文字填进输入框，不会发送**，你可以改一改再点发送。

### 日历
- 「周三下午3点帮我在日历加个 [PA测试] 评审会」→ 对话里先显示一行「日历：「…」· 10月28日（周三）15:00–16:00」，几秒后出现「已完成」卡片（带日程截图和依据）。
- 「帮我加个日历事项」→ agent 会一次问全缺的信息（标题、哪天、几点），能给选项时显示成按钮。
- 「提醒我明天下午2点交报告」→ 做成日历事项，复述里会写明。
- 相对日期（明天、下周一、周五）由程序算好对照表，直接换成具体日期并在复述里写出来，不会追问是哪一天。

### 邮件（发送前一定要你确认）
- 「给 qijie_jerry@163.com 发邮件，说周五的评审改到下午3点」→ agent 写好主题和正文，在虚拟屏上打开 Gmail 写邮件页，**从页面上实际读出收件人、主题、正文**，在对话里显示确认卡片（发送 / 修改 / 取消）。
  - **发送**：agent 核对页面内容与卡片一致后才放行一次，点击发送，等「撤销发送」时间结束，到「已发送」里核对。
  - **修改**：直接在输入栏说怎么改（也可以点「修改」再说），agent 改完会再显示一张新的确认卡片。
  - **取消**：丢弃本次创建的草稿，并确认草稿箱没有残留。
- **测试时只发给 `qijie_jerry@163.com`，主题加 `[PA测试]` 前缀。**

### DoorDash（agent 永远不下单、不订阅 DashPass）
- 「在 DoorDash 点一个 Shake Shack 的 Double ShackBurger，其他选项都用默认」：agent 搜餐厅、加购一份、核对购物车，走到结账页停下，对话里出现「需要你来完成」卡片，点「去完成」，DoorDash 会出现在你的屏幕上，下单和付款由你来完成。
- **注意**：加购会改你真实的 DoorDash 购物车。

### Amazon（agent 永远不下单、不订阅 Prime）
- 「帮我把购物车带到结算页」：购物车里有商品时，agent 走到提交订单页停下，对话里出现「需要你来完成」卡片（小计、总价），点「去完成」，Amazon 会出现在你的屏幕上，下单和付款由你来完成。购物车是空的会直接告诉你。
- 「在亚马逊搜 XX，买一个，走到提交订单页停下」：搜索、加购、结算，同样停在提交订单页。
- 购物车里有多件商品时，会告诉你「结算会包含全部 N 件」，让你选「全部结算」或「调整购物车」；你明确要求删除或改数量时，先出现确认卡片，确认后才会动购物车，并且只能动清单里的那几项（特权侧硬拦截）。
- **注意**：加购和删除会改你真实的 Amazon 购物车。

### 离开 App 时：悬浮小窗
任务进行中切到别的 App，屏幕边缘会出现小窗：执行中（带「停止」）、需要回答（可直接点选项，或「去回答」）、等待确认（「去确认」）、需要交接（「去处理」）、完成、失败。点「去回答 / 去确认 / 去处理」会打开 PhoneAgent 并滚动到对应卡片。小窗可以拖动，松手吸附到左右边缘。App 在前台时不显示。

### 看 agent 在做什么
任务开始后对话里出现实时画面卡片（缩略画面每秒刷新、当前步骤、步骤序号、停止按钮）；点缩略画面进入全屏观看，左上角「×」或下滑退出（任务继续在后台跑），上滑展开步骤列表。随时可以点「停止」，后台画面会被清理。

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

## 六、关键画面截图
`docs/mvp/`：启动页（带名称）、邮件确认卡片、小窗「等待确认」、从小窗打开并定位到卡片、全屏观看、邮件发送结果、日历追问和结果、Amazon 空购物车提示。个人信息（发件账号、姓名等）已涂掉。

## 微信和地图（2026-10-26）
- 微信（要先在模拟器上登录，停在「微信」首页）：对话页说「给 Jerry 发微信：测试消息，请忽略」→ 出确认卡片（发给谁、消息内容）→ 发送 / 修改 / 取消。脚本：`scripts/m2_task.sh "给 Jerry 发微信：测试消息1，请忽略" @SEND`，修改用 `@MODIFY "把内容改成：…" @SEND`，取消用 `@CANCEL`。
- 地图：「去旧金山机场要多久」「从斯坦福大学到旧金山渡轮大厦各种方式要多久」→ 卡片列出驾车 / 公交 / 步行 / 骑行的耗时和距离，标出最快，点「去查看」把地图移到主屏。
- 悬浮窗：任务执行时屏幕右上出现「实时画面 + 结束任务」（模拟器上先 `adb shell appops set com.android.phoneagent SYSTEM_ALERT_WINDOW allow`）。
