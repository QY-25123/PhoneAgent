package com.android.phoneagent.priv

import android.os.IBinder

/** 虚拟屏上任务的清理：释放 display 之前先把上面的任务都关掉，避免窗口/任务残留在已销毁的 display 上。 */
class DisplayTasks {
    private val atm: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "activity_task") as IBinder
        Class.forName("android.app.IActivityTaskManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    fun removeAll(displayId: Int) {
        val infos = atm.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.java).invoke(atm, displayId) as List<*>
        val removeTask = atm.javaClass.getMethod("removeTask", Int::class.java)
        for (info in infos) {
            info ?: continue
            val cls = info.javaClass
            val ids = (cls.getField("childTaskIds").get(info) as IntArray).toMutableList()
            ids += cls.getField("taskId").getInt(info)
            for (id in ids.distinct()) runCatching { removeTask.invoke(atm, id) }
        }
    }
}
