package com.android.phoneagent.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter

// 每个界面一个深色、一个浅色 @Preview（在 Android Studio 里按目录条目逐个显示）。
@Preview(name = "深色", widthDp = 393, heightDp = 852, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun ScreenPreviewDark(@PreviewParameter(CatalogIds::class) id: String) = RenderCatalog(id, dark = true)

@Preview(name = "浅色", widthDp = 393, heightDp = 852, showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun ScreenPreviewLight(@PreviewParameter(CatalogIds::class) id: String) = RenderCatalog(id, dark = false)
