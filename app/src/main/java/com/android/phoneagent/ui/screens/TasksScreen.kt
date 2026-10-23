package com.android.phoneagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.phoneagent.ui.components.CardMain
import com.android.phoneagent.ui.components.CardSub
import com.android.phoneagent.ui.components.RoundIconButton
import com.android.phoneagent.ui.components.StructuredCard
import com.android.phoneagent.ui.theme.AppIcons
import com.android.phoneagent.ui.theme.AppTheme

data class TaskRecord(val title: String, val result: String, val kind: Int, val time: String, val icon: ImageVector)

val FakeTasks = listOf(
    TaskRecord("给 Alice 发邮件", "已发送 · 周五评审改到下午 3 点", 0, "今天 10:27", AppIcons.mail),
    TaskRecord("加日历事项", "已创建 · 周五 15:00–16:00", 0, "今天 09:58", AppIcons.calendar),
    TaskRecord("备好 Amazon 购物车", "已交接 · 停在提交订单页", 1, "昨天 21:14", AppIcons.cart),
    TaskRecord("加日历事项", "失败 · 日历没有响应", 2, "昨天 18:02", AppIcons.calendar),
)

/** 任务记录：按时间倒序。 */
@Composable
fun TasksScreen(onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.padding(AppTheme.space.s4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s3)) {
            RoundIconButton(AppIcons.menu, onMenu)
            Text("任务记录", style = AppTheme.type.title, color = c.text)
        }
        LazyColumn(
            contentPadding = PaddingValues(AppTheme.space.s4),
            verticalArrangement = Arrangement.spacedBy(AppTheme.space.s3),
        ) {
            items(FakeTasks) { t ->
                val tint = when (t.kind) { 0 -> c.success; 1 -> c.warning; else -> c.danger }
                StructuredCard(t.icon, t.title, iconTint = tint, trailing = { Text(t.time, style = AppTheme.type.caption, color = c.textSecondary) }) {
                    Column {
                        CardMain(t.result)
                        CardSub("点击回到对话里的这次任务")
                    }
                }
            }
        }
    }
}
