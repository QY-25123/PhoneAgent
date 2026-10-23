package com.android.phoneagent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.android.phoneagent.perception.PerceptionSource
import com.android.phoneagent.perception.RawSnapshot
import java.io.File

/** debug：离线感知来源，读 [dir] 下的 NAME.snap.json 和 NAME.png（脱敏样本），不需要虚拟屏。 */
class SampleSource(dir: File, name: String) : PerceptionSource {
    private val snap = RawSnapshot.parse(File(dir, "$name.snap.json").readText())
    private val frame: Bitmap? = File(dir, "$name.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    override suspend fun snapshot(): RawSnapshot = snap
    override fun screenshot(): Bitmap? = frame
}
