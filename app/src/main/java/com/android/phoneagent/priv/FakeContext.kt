package com.android.phoneagent.priv

import android.content.Context
import android.content.ContextWrapper

/** shell 进程里没有 App Context：借 ActivityThread 的系统 Context，并伪装成 com.android.shell。 */
class FakeContext private constructor(base: Context) : ContextWrapper(base) {
    override fun getPackageName() = "com.android.shell"
    override fun getOpPackageName() = "com.android.shell"
    override fun getApplicationContext(): Context = this

    companion object {
        /** 必须在有 main looper 的线程（UserService 构造函数里）调用。 */
        fun create(): FakeContext {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            val sys = at.getMethod("getSystemContext").invoke(thread) as Context
            return FakeContext(sys)
        }
    }
}
