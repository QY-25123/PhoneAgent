package com.android.phoneagent.privileged

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

    /** 某块屏幕上的根任务（从上到下）：一行一个，`rootId taskId pkg/activity visible`。 */
    fun list(displayId: Int): String {
        val infos = atm.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.java).invoke(atm, displayId) as List<*>
        return infos.filterNotNull().joinToString("\n") { info ->
            val c = info.javaClass
            val top = c.getField("topActivity").get(info)
            "${c.getField("taskId").getInt(info)} ${(c.getField("childTaskIds").get(info) as IntArray).joinToString(",")} " +
                "${top ?: "-"} visible=${c.getField("visible").getBoolean(info)}"
        }
    }

    /** 把任务移到另一块屏幕（交接用）。 */
    fun moveToDisplay(taskId: Int, displayId: Int) {
        atm.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java).invoke(atm, taskId, displayId)
    }
}
