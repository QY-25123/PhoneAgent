package com.android.phoneagent;

import android.view.Surface;

// 运行在 Shizuku UserService 进程（shell uid）里的特权接口
interface IPrivileged {
    void destroy() = 16777114;

    int getUid() = 1;
    // 返回 displayId；失败抛异常
    int createDisplay(in Surface surface, int width, int height, int dpi) = 2;
    void launchApp(int displayId, String component) = 3;
    void tap(int displayId, float x, float y) = 4;
    void swipe(int displayId, float x1, float y1, float x2, float y2, long durationMs) = 5;
    void back(int displayId) = 6;
    boolean setText(int displayId, String text) = 7;
    String dumpTree(int displayId) = 8;
    // 释放 VirtualDisplay 和 UiAutomation
    void release() = 9;
}
