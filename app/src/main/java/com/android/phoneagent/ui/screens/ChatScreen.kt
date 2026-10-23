package com.android.phoneagent.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import com.android.phoneagent.ui.components.AgentAvatar
import com.android.phoneagent.ui.components.AgentBubble
import com.android.phoneagent.ui.components.ConfirmCard
import com.android.phoneagent.ui.components.FailureCard
import com.android.phoneagent.ui.components.HandoffCard
import com.android.phoneagent.ui.components.InputBar
import com.android.phoneagent.ui.components.LiveCard
import com.android.phoneagent.ui.components.NameCapsule
import com.android.phoneagent.ui.components.QuestionCard
import com.android.phoneagent.ui.components.RoundIconButton
import com.android.phoneagent.ui.components.SuccessCard
import com.android.phoneagent.ui.components.UserBubble
import com.android.phoneagent.ui.model.AgentProfile
import com.android.phoneagent.ui.model.ChatController
import com.android.phoneagent.ui.model.ChatItem
import com.android.phoneagent.ui.model.ConfirmAction
import com.android.phoneagent.ui.theme.AppIcons
import com.android.phoneagent.ui.theme.AppTheme

/** 对话首页：顶部头像和名字胶囊、对话流、底部输入栏。 */
@Composable
fun ChatScreen(
    controller: ChatController,
    onMenu: () -> Unit,
    onOpenFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val items = controller.items

    LaunchedEffect(items.size, items.lastOrNull()) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size)
    }

    val send = {
        if (input.isNotBlank()) { controller.submit(input); input = "" }
    }

    Box(modifier.fillMaxSize().background(c.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AppTheme.space.s4, end = AppTheme.space.s4,
                top = AppTheme.sizes.topBar + AppTheme.space.s8 + AppTheme.space.s4,
                bottom = AppTheme.sizes.inputBar + AppTheme.space.s10 + AppTheme.space.s6,
            ),
            verticalArrangement = Arrangement.spacedBy(AppTheme.space.s3),
        ) {
            if (items.isEmpty()) {
                item("intro") { EmptyIntro(onExample = { controller.submit(it) }) }
            }
            items(items.size, key = { items[it].id }) { i ->
                val item = items[i]
                AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically { it / 8 }) {
                    ChatItemView(item, controller, onOpenFullScreen)
                }
            }
        }

        TopArea(onMenu = onMenu, workPulse = controller.workPulse, working = controller.busy)

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(c.background.copy(alpha = 0f), c.background)))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(horizontal = AppTheme.space.s4, vertical = AppTheme.space.s4),
        ) {
            InputBar(input, { input = it }, send, placeholder = "给 ${AgentProfile.name.ifBlank { "我" }} 发消息")
        }
    }
}

/** 顶部：左上角圆形菜单按钮，居中头像 + 名字胶囊；半透明渐隐背景。 */
@Composable
fun TopArea(onMenu: () -> Unit, workPulse: Int, working: Boolean, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to c.background.copy(alpha = 0.92f), 0.7f to c.background.copy(alpha = 0.8f), 1f to c.background.copy(alpha = 0f)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(AppTheme.sizes.topBar + AppTheme.space.s8),
    ) {
        RoundIconButton(AppIcons.menu, onMenu, Modifier.align(Alignment.TopStart).padding(start = AppTheme.space.s4, top = AppTheme.space.s4))
        Column(Modifier.align(Alignment.TopCenter).padding(top = AppTheme.space.s2), horizontalAlignment = Alignment.CenterHorizontally) {
            AgentAvatar(AgentProfile.avatar, AppTheme.sizes.avatar, workPulse = workPulse, breathing = working)
            NameCapsule(AgentProfile.name.ifBlank { "我" }, Modifier.offset(y = -AppTheme.space.s2))
        }
    }
}

@Composable
private fun EmptyIntro(onExample: (String) -> Unit) {
    val name = AgentProfile.name.ifBlank { "我" }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.s3)) {
        AgentBubble("我是$name。你说一件事，我在后台的虚拟屏上替你办，你照常用手机。我不会付款，发邮件前一定先让你确认。")
        Text("试试这些", style = AppTheme.type.label, color = AppTheme.colors.textTertiary, modifier = Modifier.padding(start = AppTheme.space.s2))
        ExampleRow(AppIcons.calendar, "周五下午帮我在日历加个和 Alice 的评审会议", onExample)
        ExampleRow(AppIcons.mail, "给 Alice 发邮件，说明周五评审改到下午 3 点", onExample)
        ExampleRow(AppIcons.cart, "把 Amazon 购物车带到下单页，我自己付款", onExample)
    }
}

@Composable
private fun ExampleRow(icon: ImageVector, text: String, onClick: (String) -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AppTheme.shapes.card)
            .background(c.surface)
            .clickable { onClick(text) }
            .padding(AppTheme.space.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.space.s3),
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(AppTheme.sizes.icon))
        Text(text, style = AppTheme.type.body, color = c.text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ChatItemView(item: ChatItem, controller: ChatController, onOpenFullScreen: () -> Unit) {
    when (item) {
        is ChatItem.User -> UserBubble(item.text)
        is ChatItem.Agent -> AgentBubble(item.text)
        is ChatItem.Live -> LiveCard(item, onOpen = onOpenFullScreen, onStop = { controller.stop() })
        is ChatItem.Confirm -> ConfirmCard(
            item,
            onSend = { controller.onConfirm(ConfirmAction.SEND) },
            onModify = { controller.onConfirm(ConfirmAction.MODIFY) },
            onCancel = { controller.onConfirm(ConfirmAction.CANCEL) },
        )
        is ChatItem.Question -> QuestionCard(item, onChoose = { controller.onOption(item.id, it) })
        is ChatItem.Success -> SuccessCard(item)
        is ChatItem.Failure -> FailureCard(item, onRetry = { controller.submit("重试") }, onSteps = onOpenFullScreen)
        is ChatItem.Handoff -> HandoffCard(item, onGo = { controller.goHandoff(item.id) })
    }
}
