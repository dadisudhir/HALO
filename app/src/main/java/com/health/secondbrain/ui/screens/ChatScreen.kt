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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Handler
import android.os.Looper
import com.health.secondbrain.data.HaloHealthRepository
import com.health.secondbrain.data.HealthBackendMode
import com.health.secondbrain.llm.AgentVisual
import com.health.secondbrain.llm.AgentResponse
import com.health.secondbrain.llm.OnDeviceLlmService
import com.health.secondbrain.model.OrganNode
import com.health.secondbrain.ui.components.LineSpark
import com.health.secondbrain.ui.components.OrganAssetIcon
import com.health.secondbrain.ui.components.WeekBars
import com.health.secondbrain.ui.theme.Palette
import com.health.secondbrain.ui.theme.Type
import kotlinx.coroutines.launch

private sealed class ChatMsg {
    data class User(val text: String) : ChatMsg()
    data class Ai(val text: String) : ChatMsg()
    data class Status(val text: String) : ChatMsg()
    data class Graph(val organ: OrganNode, val visual: AgentVisual) : ChatMsg()
    data object Typing : ChatMsg()
}

@Composable
fun ChatScreen(
    organId: String,
    organ: OrganNode? = null,
    mode: HealthBackendMode,
    repository: HaloHealthRepository,
    onBack: () -> Unit,
) {
    val organ = organ ?: return MissingChatComponent(organId = organId, onBack = onBack)
    val context = LocalContext.current
    val llm = remember(context) { OnDeviceLlmService(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var messages by remember(organ.id) {
        mutableStateOf<List<ChatMsg>>(
            listOf(
                ChatMsg.Ai("Hi — I'm your ${organ.displayName.lowercase()} coach. " +
                    "Ask me what changed or what to look at next.")
            )
        )
    }
    var input by remember { mutableStateOf("") }

    fun send(text: String) {
        if (text.isBlank()) return
        if (messages.any { it is ChatMsg.Typing }) return
        messages = messages + ChatMsg.User(text) + ChatMsg.Typing
        input = ""
        scope.launch {
            var streamActive = true
            val reply = runCatching {
                val agentContext = repository.loadAgentContext(mode, organ)
                llm.generateChat(
                    context = agentContext,
                    userMessage = text,
                    onToken = { delta ->
                        if (delta.isBlank()) return@generateChat
                        mainHandler.post {
                            if (streamActive) {
                                messages = appendAssistantDelta(messages, delta)
                            }
                        }
                    },
                )
            }.getOrElse { error ->
                AgentResponse(
                    text = "HALO agent context is unavailable: ${error.message ?: error.javaClass.simpleName}",
                    statusLine = "context unavailable",
                )
            }
            streamActive = false
            val status = reply.statusLine?.let { ChatMsg.Status(it) }
            val graph = reply.visual
                ?.takeIf { it.domainId == organ.id }
                ?.let { ChatMsg.Graph(organ, it) }
            messages = finishAssistantMessage(messages, status, reply.text, graph)
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
                    iconAsset = organ.iconAsset,
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
                    is ChatMsg.Status -> StatusLine(msg.text)
                    is ChatMsg.Graph -> GraphBubble(msg.organ, msg.visual)
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input) }),
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

private fun appendAssistantDelta(messages: List<ChatMsg>, delta: String): List<ChatMsg> {
    val withoutTyping = messages.filterNot { it is ChatMsg.Typing }
    val last = withoutTyping.lastOrNull()
    return if (last is ChatMsg.Ai) {
        withoutTyping.dropLast(1) + last.copy(text = last.text + delta)
    } else {
        withoutTyping + ChatMsg.Ai(delta)
    }
}

private fun finishAssistantMessage(
    messages: List<ChatMsg>,
    status: ChatMsg.Status?,
    finalText: String,
    graph: ChatMsg.Graph? = null,
): List<ChatMsg> {
    val withoutTyping = messages.filterNot { it is ChatMsg.Typing }
    val withoutPartial = if (withoutTyping.lastOrNull() is ChatMsg.Ai) {
        withoutTyping.dropLast(1)
    } else {
        withoutTyping
    }
    return withoutPartial + listOfNotNull(status, ChatMsg.Ai(finalText), graph)
}

@Composable
private fun MissingChatComponent(organId: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.BgBase).statusBarsPadding().padding(18.dp)) {
        Text("‹", style = Type.titleScreen, color = Palette.TextPrimary,
            modifier = Modifier.clickable { onBack() })
        Spacer(Modifier.height(28.dp))
        Text("Coach unavailable", style = Type.titleScreen, color = Palette.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "$organId is not enabled in the backend health_components table.",
            style = Type.body,
            color = Palette.TextSecondary
        )
    }
}

@Composable
private fun StatusLine(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            text,
            style = Type.caption,
            color = Palette.TextMuted,
            textAlign = TextAlign.Center
        )
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
private fun GraphBubble(organ: OrganNode, visual: AgentVisual) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .background(Palette.SurfaceElev, RoundedCornerShape(18.dp))
                .border(
                    1.dp,
                    organ.accent.copy(alpha = 0.38f),
                    RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(organ.tint, CircleShape)
                        .border(1.dp, organ.accent.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    OrganAssetIcon(
                        iconAsset = organ.iconAsset,
                        contentDescription = organ.displayName,
                        modifier = Modifier
                            .size(23.dp)
                            .padding(2.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(visual.title, style = Type.bodyBold, color = Palette.TextPrimary)
                    Text(visual.caption, style = Type.caption, color = Palette.TextMuted)
                }
            }

            Spacer(Modifier.height(12.dp))
            LineSpark(
                values = organ.chart7Day,
                color = organ.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
            )

            Spacer(Modifier.height(10.dp))
            WeekBars(
                values = organ.activeZones,
                color = organ.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                organ.metrics.take(2).forEach { metric ->
                    Column(
                        Modifier
                            .weight(1f)
                            .background(Palette.Surface, RoundedCornerShape(12.dp))
                            .border(1.dp, Palette.Border, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(metric.label, style = Type.caption, color = Palette.TextMuted)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${metric.value} ${metric.deltaText}".trim(),
                            style = Type.bodyBold,
                            color = Palette.TextPrimary
                        )
                    }
                }
            }
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
