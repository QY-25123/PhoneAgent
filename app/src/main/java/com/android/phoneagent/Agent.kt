package com.android.phoneagent

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.phoneagent.priv.PrivilegedService
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** 进程内单例：持有 Shizuku 连接和 UserService，UI 与 adb 自测入口共用。 */
object Agent {
    private const val TAG = "PA"
    private const val REQ_CODE = 1

    var status by mutableStateOf("未连接")
        private set

    private var priv: IPrivileged? = null
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var appContext: Context

    private val conn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            priv = IPrivileged.Stub.asInterface(binder)
            io.execute { report("UserService 已连接，uid=${priv?.uid}") }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            priv = null
            report("UserService 断开")
        }
    }

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { report("Shizuku binder 已到达"); }
        Shizuku.addBinderDeadListener { priv = null; report("Shizuku binder 已死") }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) bindService() else report("Shizuku 授权被拒绝")
        }
    }

    /** 检查 Shizuku、申请授权、绑定 UserService。 */
    fun connect() {
        if (!Shizuku.pingBinder()) return report("Shizuku 未运行")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            report("请求 Shizuku 授权…")
            Shizuku.requestPermission(REQ_CODE)
        } else bindService()
    }

    private fun bindService() {
        val args = Shizuku.UserServiceArgs(ComponentName(appContext.packageName, PrivilegedService::class.java.name))
            .processNameSuffix("priv").daemon(false).version(1)
        Shizuku.bindUserService(args, conn)
        report("正在绑定 UserService…")
    }

    private fun report(msg: String) {
        Log.i(TAG, msg)
        status = msg
    }
}
