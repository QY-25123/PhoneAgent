# M1 感知：样本、验收和性能记录

日期 2026-10-23，Android 14 模拟器（Pixel 8），虚拟屏 1080×2400。

## 样本
录制时用 debug 命令 `perceive --es name X --ez raw true`（`scripts/m1_lib.sh` 里的 `perceive`），原始快照和截图只放本机 `local-samples/`（git 忽略）。
`scripts/m1_sanitize.py` 做白名单脱敏后写入 `app/src/test/resources/perception/`：快照里只有界面固定文案原样保留，其余换成等长占位文字，邮箱、姓名、地址、卡号专门替换；截图把改写过的文字和图片区域涂灰，缩到 720px 宽。
`scripts/m1_samples.sh` 在设备上对这些脱敏样本跑同一条感知管线（离线），结果在 `docs/perception/`（文本 + 标注截图）。

| 样本 | 页面 | 录制方式 |
|---|---|---|
| `cal_main` | Google 日历主页面 | 启动日历 |
| `cal_new_event` | 日历新建事项页（有标题框、保存） | 点「Nothing planned. Tap to create.」，**没有保存** |
| `gm_inbox` | Gmail 收件箱 | 启动 Gmail |
| `gm_compose` | Gmail 写邮件页 | 点 Compose，**空草稿，没有发送** |
| `amz_cart` | Amazon 购物车 | 点底部 Cart 标签 |
| `amz_after_proceed` | 点 Proceed to checkout 后弹出的 Prime 推广页 | **只点了 No thanks，没碰 Submit** |
| `amz_checkout` | 提交订单页 | **没有点任何下单相关按钮** |

## 性能（真机录制时的实测，虚拟屏在线）
「单次感知」＝ 一次读取界面树（含跨进程）+ 筛选 + 截图处理（全黑检查、标注、缩放、JPEG）+ 序列化，**不含等待页面稳定**。

| 样本 | 元素数 | 序列化文本（字符） | 读取 | 筛选 | 截图 | 序列化 | **单次感知** | 等待稳定 |
|---|---|---|---|---|---|---|---|---|
| cal_main | 28 | 1353 | 10ms | 13ms | 15ms | 3ms | **41ms** | 383ms（3 次读取） |
| cal_new_event | 30 | 1502 | 16ms | 12ms | 10ms | 2ms | **40ms** | 222ms |
| gm_inbox | 28 | 2713 | 17ms | 23ms | 14ms | 3ms | **57ms** | 424ms |
| gm_compose | 15 | 720 | 11ms | 7ms | 13ms | 1ms | **32ms** | 353ms |
| amz_cart | 57 | 4125 | 41ms | 24ms | 13ms | 5ms | **83ms** | 335ms |
| amz_after_proceed | 30 | 1975 | 10ms | 13ms | 11ms | 3ms | **37ms** | 227ms |
| amz_checkout | 33 | 1503 | 18ms | 10ms | 9ms | 3ms | **40ms** | 246ms |

目标是单次低于 1 秒：**全部在 100ms 以内**（最大的购物车页 434 个原始节点、57 个元素，83ms）。
（Amazon 首页 87ms，31 个元素，不在样本集里。）

## 验收（关键元素都出现在结果里）
由 `SamplesTest` 断言，全部通过：
- 日历主页面：新建入口（`Create new event and more`）、回到今天、日期选择、搜索、可滚动区域。
- 日历新建页：标题框（`Add title`，聚焦）、保存、取消、开始日期/时间、结束时间、全天开关。
- Gmail 收件箱：写邮件（`Compose`）、搜索框、邮件列表（可滚动）、至少 5 个邮件行。
- Gmail 写邮件页：收件人框（`To`）、主题框（`Subject`）、正文框（`Compose email`）、发送（`Send`）、附件。
- Amazon 购物车：`Proceed to checkout`、数量（描述里的 `Quantity is 1`）、小计、商品的 `Delete`。
- Amazon Prime 推广页：`No thanks`、`Submit`（感知照常列出，拦截是 safety 的事）。
- Amazon 提交订单页：`Place your order`、`Order total:` 和金额、`Items:`、更换支付方式。

共同性质：编号从 1 连续、从上到下排序；稳定标识在一个页面内唯一；元素都在屏幕范围内；文字长度不超限（合并文字 ≤80，自身文字 ≤200，超出处标注「…(共N字)」）；序列化可重复；JVM 上最大样本筛选 <200ms。

## 录制时的发现
1. **连接后第一次读界面树可能是空的**（刚建立 UiAutomation 连接，窗口列表还没就绪）。稳定检测把空树当空壳重试，所以没问题。
2. **Gmail 写邮件页的收件人框没有任何标签**，正文框的内容是占位文字 `Compose email`（也不一定带「占位」标志）。处理：①占位文字（`isShowingHintText`）当提示不当内容；②没有标签的输入框借用同一行左边最近的有文字元素当提示，所以收件人框显示为 `EditText "To"`。
3. **Amazon 购物车页的「Delete」控件有两处**：购物车商品和「Saved for later」里的商品都有 `Delete`（后者在屏幕外，滚动后才可见）。M3 的购物车改动拦截要区分这两处，并且按商品标识放行。
4. **Amazon 的 WebView 内容可以完整读到**（购物车 434 个节点、提交订单页 233 个），数量描述 `Increase quantity by one, Quantity is 1, <商品名>` 是读数量的依据；提交订单页没有数量文字（与 UV-6 一致）。
5. **Gmail 邮件行**的描述是「未读, 发件人, 主题, 摘要…」一整串，含逗号空段，已合并连续逗号；整行文字超过 80 字时在合并标签里截断标注。
6. **日历「Create」入口**：点到的是当天空白处的「Tap to create」，直接弹出新建事项页（不经过菜单）。
7. **没有遇到安全窗口黑屏**：全黑识别只有单元测试覆盖（合成像素：全黑、深色主题背景、纯黑页面上有少量内容）。
