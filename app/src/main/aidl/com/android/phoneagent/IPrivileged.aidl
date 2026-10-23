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

    // 测试用：草稿里有该收件人才布防，只对这一封放行一次 Gmail 发送点击（FR-SAFE-12 原型）
    boolean armGmailSend(int displayId, String recipient) = 19;

    // 某块屏幕上的根任务列表（UV-3 冲突检测 / UV-2 交接用）
    String listTasks(int displayId) = 17;
    // 把根任务移到另一块屏幕
    void moveTaskToDisplay(int taskId, int displayId) = 18;
}
