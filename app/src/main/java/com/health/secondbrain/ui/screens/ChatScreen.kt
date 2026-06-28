package com.health.secondbrain.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.health.secondbrain.llm.OnDeviceLlmService
import com.health.secondbrain.model.OrganRegistry
import com.health.secondbrain.ui.components.OrganAssetIcon
import com.health.secondbrain.ui.theme.Palette
import com.health.secondbrain.ui.theme.Type
import kotlinx.coroutines.launch

private sealed class ChatMsg {
    data class User(val text: String) : ChatMsg()
    data class Ai(val text: String) : ChatMsg()
    data object Typing : ChatMsg()
}

@Composable
fun ChatScreen(organId: String, onBack: () -> Unit) {
    val organ = OrganRegistry.byId(organId)
    val llm = remember { OnDeviceLlmService() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages by remember {
        mutableStateOf<List<ChatMsg>>(
            listOf(
                ChatMsg.Ai("Hi — I'm your ${organ.displayName.lowercase()} coach. " +
                    "I know your last 30 days. What do you want to look at?")
            )
        )
    }
    var input by remember { mutableStateOf("") }

    fun send(text: String) {
        if (text.isBlank()) return
        messages = messages + ChatMsg.User(text) + ChatMsg.Typing
        input = ""
        scope.launch {
            val reply = llm.generate(organ, OnDeviceLlmService.Prompt.Chat, text)
            messages = messages.filterNot { it is ChatMsg.Typing } + ChatMsg.Ai(reply)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(Palette.BgBase).statusBarsPadding()) {
        // header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", style = Type.titleScreen, color = Palette.TextPrimary,
                modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .background(organ.tint, CircleShape)
                    .border(1.dp, organ.accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                OrganAssetIcon(
                    organId = organ.id,
                    contentDescription = organ.displayName,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(2.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("${organ.displayName} coach", style = Type.bodyBold, color = Palette.TextPrimary)
                Text("● knows your data", style = Type.caption, color = Palette.Healthy)
            }
        }

        // Divider
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Border))

        // messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages) { msg ->
                when (msg) {
                    is ChatMsg.User -> UserBubble(msg.text)
                    is ChatMsg.Ai   -> AiBubble(msg.text)
                    ChatMsg.Typing  -> TypingBubble()
                }
            }
        }

        // input bar
        Row(
            Modifier
                .padding(horizontal = 14.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .fillMaxWidth()
                .background(Palette.Surface, RoundedCornerShape(24.dp))
                .border(1.dp, Palette.Border, RoundedCornerShape(24.dp))
                .padding(start = 16.dp, top = 9.dp, bottom = 9.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (input.isEmpty()) {
                    Text(
                        "Message your ${organ.displayName.lowercase()} coach…",
                        style = Type.bodySmall, color = Palette.TextMuted
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(
                        color = Palette.TextPrimary,
                        fontSize = 13.sp
                    ),
                    cursorBrush = SolidColor(Palette.TextPrimary),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .background(organ.accent, CircleShape)
                    .clickable { send(input) },
                contentAlignment = Alignment.Center
            ) {
                Text("↑", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .fillMaxWidth(0.78f)
                .wrapContentWidth(Alignment.End)
                .background(
                    Palette.UserBubble,
                    RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = Palette.UserBubbleText, style = Type.bodySmall)
        }
    }
}

@Composable
private fun AiBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier
                .fillMaxWidth(0.82f)
                .wrapContentWidth(Alignment.Start)
                .background(
                    Palette.AiBubble,
                    RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
                )
                .border(
                    1.dp,
                    Palette.Border,
                    RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
                )
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Text(text, color = Palette.AiBubbleText, style = Type.bodySmall)
        }
    }
}

@Composable
private fun TypingBubble() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            Modifier
                .background(Palette.AiBubble, RoundedCornerShape(18.dp))
                .border(1.dp, Palette.Border, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { i ->
                val scale by t.animateFloat(
                    1f, 1.4f,
                    infiniteRepeatable(
                        tween(600, delayMillis = i * 150, easing = FastOutSlowInEasing),
                        RepeatMode.Reverse
                    ),
                    label = "dot$i"
                )
                Box(
                    Modifier
                        .scale(scale)
                        .size(7.dp)
                        .background(Palette.TextMuted, CircleShape)
                )
            }
        }
    }
}
