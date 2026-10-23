package com.android.phoneagent.priv

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface

/** 以 shell 身份创建/释放 VirtualDisplay（参考 scrcpy --new-display 的 flags）。 */
class VirtualDisplayHolder(private val context: FakeContext) {
    private var display: VirtualDisplay? = null

    fun create(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        release()
        val dm = DisplayManager::class.java.getDeclaredConstructor(android.content.Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        val d = dm.createVirtualDisplay("PhoneAgent", width, height, dpi, surface, FLAGS)
            ?: error("createVirtualDisplay 返回 null")
        display = d
        return d.display.displayId
    }

    fun release() {
        display?.release()
        display = null
    }

    private companion object {
        const val PUBLIC = 1 shl 0
        const val OWN_CONTENT_ONLY = 1 shl 3
        const val SUPPORTS_TOUCH = 1 shl 6
        const val ROTATES_WITH_CONTENT = 1 shl 7
        const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val TRUSTED = 1 shl 10
        const val OWN_DISPLAY_GROUP = 1 shl 11
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val OWN_FOCUS = 1 shl 14

        const val FLAGS = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or
            DESTROY_CONTENT_ON_REMOVAL or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or
            TOUCH_FEEDBACK_DISABLED or OWN_FOCUS
    }
}
