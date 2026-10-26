package com.android.phoneagent;

import android.view.Surface;

// 运行在 Shizuku UserService 进程（shell uid）里的特权接口
interface IPrivileged {
    void destroy() = 16777114;

    int getUid() = 1;
    // 返回 displayId；失败抛异常
    int createDisplay(in Surface surface, int width, int height, int dpi, boolean stealTopFocusDisabled) = 2;
    void launchApp(int displayId, String component) = 3;
    void tap(int displayId, float x, float y) = 4;
    void swipe(int displayId, float x1, float y1, float x2, float y2, long durationMs) = 5;
    void back(int displayId) = 6;
    boolean setText(int displayId, String text) = 7;
    String dumpTree(int displayId) = 8;
    // 释放 VirtualDisplay 和 UiAutomation
    void release() = 9;

    // 输入法隔离实验：设置虚拟屏的 IME 策略（0=LOCAL 1=FALLBACK_DISPLAY 2=HIDE），返回旧值
    int setImePolicy(int displayId, int policy) = 10;
    // 无障碍语义操作：对坐标处最深的可点击节点执行 ACTION_CLICK
    boolean clickAt(int displayId, float x, float y) = 11;
    // 对虚拟屏上第一个可滚动节点执行 ACTION_SCROLL_FORWARD/BACKWARD
    boolean scroll(int displayId, boolean forward) = 12;
    // UiAutomation.performGlobalAction(GLOBAL_ACTION_BACK)，无 displayId 参数
    // 禁用：作用于 top focused display，会误操作主屏。仅供 scripts/back_test.sh 证明用
    boolean globalBack() = 13;

    // UserActivityGuard 用：主屏软键盘是否正在显示（用户正在打字）
    boolean isMainImeShown() = 14;

    // 按文案找控件并点击（touch=true 用触摸，否则 ACTION_CLICK）。命中硬拦截抛 SecurityException。返回描述字符串或 NOT_FOUND
    String clickText(int displayId, String text, boolean touch) = 16;

    // 向坐标处的可编辑节点写文字（按节点定位，不依赖「当前聚焦」）
    boolean setTextAt(int displayId, float x, float y, String text) = 20;

    // 向指定屏幕发送一个按键（带 displayId），如回车提交搜索
    void pressKey(int displayId, int keyCode) = 21;

    // 测试用：草稿里有该收件人才布防，只对这一封放行一次 Gmail 发送点击（FR-SAFE-12 原型）
    boolean armGmailSend(int displayId, String recipient) = 19;

    // 某块屏幕上的根任务列表（UV-3 冲突检测 / UV-2 交接用）
    String listTasks(int displayId) = 17;
    // 感知用：指定屏幕上所有窗口和节点的原始快照（JSON，格式见 perception/RawSnapshot）
    String snapshotTree(int displayId) = 22;

    // 在虚拟屏上用 am start 启动一个 Intent（只允许日历的「插入事项」意图，参数由 agent 拼好）
    void launchIntent(int displayId, in String[] amArgs) = 23;

    // 按感知给出的节点引用（窗口 id + 先序下标 + 边界）精确点击/写字：重新遍历同一棵树取到那个节点，边界对不上（界面变了）就返回 false。
    // 比按坐标找「最深的可点击节点」可靠：坐标处可能叠着多个节点（例如底部面板下面被盖住的工具栏）
    boolean clickNode(int displayId, int windowId, int nodeIndex, int left, int top, int right, int bottom) = 24;
    boolean setTextNode(int displayId, int windowId, int nodeIndex, int left, int top, int right, int bottom, String text) = 25;

    // Gmail 发送的内容绑定放行：特权侧自己读草稿当前内容算哈希，与 hash 一致才布防（一次性、120 秒），点击发送的那一刻再校验一次
    boolean armGmailSendHash(int displayId, String hash) = 26;
    void disarmGmailSend() = 27;

    // 购物车改动的一次性放行：spec 每行 `action\titem\tquantity`（action 是 delete 或 set_quantity）。没布防时购物车的删除/数量控件一律拦截
    void armCartChanges(String spec) = 28;
    void disarmCartChanges() = 29;

    // 微信发送的内容绑定放行（同 Gmail）：特权侧自己读聊天页（对方名 + 输入框文字）算哈希，一致才布防（一次性、120 秒）
    boolean armWechatSendHash(int displayId, String hash) = 30;
    void disarmWechatSend() = 31;

    // 界面树读不到时（微信）写文字：把 text 放进系统剪贴板，再向该屏幕注入粘贴键（调用前要先点中输入框）
    boolean pasteText(int displayId, String text) = 32;

    // 把虚拟屏的输出改接到另一个 Surface（悬浮窗的 SurfaceView，或回到 ImageReader）
    void setDisplaySurface(in Surface surface) = 33;

    // 把根任务移到另一块屏幕
    void moveTaskToDisplay(int taskId, int displayId) = 18;
}
