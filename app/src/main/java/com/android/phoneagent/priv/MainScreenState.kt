package com.android.phoneagent.priv

/** 主屏（display 0）的用户活动状态：软键盘是否显示。 */
class MainScreenState {
    /** `dumpsys input_method` 里的 mInputShown。虚拟屏的 IME 策略是 HIDE，所以 true 只会来自主屏上的用户。 */
    fun isImeShown(): Boolean {
        val p = ProcessBuilder("dumpsys", "input_method").redirectErrorStream(true).start()
        val shown = p.inputStream.bufferedReader().useLines { lines ->
            lines.firstOrNull { it.contains("mInputShown=") }?.contains("mInputShown=true") == true
        }
        p.destroy()
        return shown
    }
}
