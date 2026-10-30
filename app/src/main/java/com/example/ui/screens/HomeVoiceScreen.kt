package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.actions.AppLaunchCandidate
import com.example.actions.PhoneActionOutcome
import com.example.actions.PhoneActionRequest
import com.example.data.local.AssetCategory
import com.example.network.AssistantOrbState
import com.example.network.LiveConnectionStatus
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.DeepNavyBackground
import com.example.ui.theme.DeepNavyCard
import com.example.ui.theme.DeepNavyElevated
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElectricBlueAccent
import com.example.ui.theme.SoftViolet
import com.example.ui.theme.StatusConnectedGreen
import com.example.ui.theme.StatusErrorRed
import com.example.ui.theme.StatusWarningAmber
import com.example.ui.theme.TextMutedDark
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.VioletAccent
import com.example.viewmodel.ConversationMessage
import com.example.viewmodel.MessageSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeVoiceScreen(
    orbState: AssistantOrbState,
    connectionStatus: LiveConnectionStatus,
    connectionDetail: String,
    isRecordingMic: Boolean,
    isPlayingVoice: Boolean,
    micLevel: Float,
    hasMicPermission: Boolean,
    messages: List<ConversationMessage>,
    pendingActionOutcome: PhoneActionOutcome?,
    onConnectLive: () -> Unit,
    onDisconnectLive: () -> Unit,
    onInterruptSpeech: () -> Unit,
    onConfirmPendingAction: (PhoneActionRequest) -> Unit,
    onSelectAmbiguousApp: (AppLaunchCandidate) -> Unit,
    onSelectOrManualDial: (String, String) -> Unit,
    onOpenWriteSettingsPermission: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onDismissPendingAction: () -> Unit,
    onClearConversation: () -> Unit,
    onOpenAssetsCategory: (AssetCategory) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DeepNavyBackground,
                        Color(0xFF0A1224),
                        DeepNavyBackground
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Top Header with Kamakhya AI Logo, Title, and Live Session Status
        HomeTopHeader(
            connectionStatus = connectionStatus,
            onConnectLive = onConnectLive,
            onDisconnectLive = onDisconnectLive,
            onClearConversation = onClearConversation
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 2. Status bar showing Connection, Recording, and Processing states
        LiveStatusTelemetryBar(
            orbState = orbState,
            connectionStatus = connectionStatus,
            connectionDetail = connectionDetail,
            isRecordingMic = isRecordingMic && hasMicPermission,
            onOpenSettings = onOpenSettings
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 3. Large Animated Luminous Orb (Layered Compose Canvas, Gradients & Circles)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(186.dp),
            contentAlignment = Alignment.Center
        ) {
            LuminousAssistantOrb(
                orbState = orbState,
                micLevel = if (isRecordingMic && hasMicPermission) micLevel else 0f,
                modifier = Modifier
                    .size(176.dp)
                    .testTag("assistant_luminous_orb")
                    .semantics {
                        contentDescription = "Kamakhya AI Voice Assistant Orb in ${orbState.name} state"
                    }
            )

            if (isPlayingVoice) {
                OutlinedButton(
                    onClick = onInterruptSpeech,
                    border = BorderStroke(1.dp, CyanAccent),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = DeepNavySurface.copy(alpha = 0.88f),
                        contentColor = CyanAccent
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .testTag("interrupt_speech_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Interrupt speech",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Interrupt Playback", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // 4. Interactive Confirmation / Disambiguation / Permission Card for Phone Actions
        AnimatedVisibility(visible = pendingActionOutcome != null) {
            pendingActionOutcome?.let { outcome ->
                PendingActionInteractiveCard(
                    outcome = outcome,
                    onConfirm = onConfirmPendingAction,
                    onSelectApp = onSelectAmbiguousApp,
                    onDialNumber = onSelectOrManualDial,
                    onOpenWriteSettings = onOpenWriteSettingsPermission,
                    onRequestContactsPermission = onRequestContactsPermission,
                    onDismiss = onDismissPendingAction
                )
            }
        }

        // 5. Conversation Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .testTag("conversation_message_list"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ConversationMessageCard(
                    message = msg,
                    onOpenCategory = onOpenAssetsCategory
                )
            }
        }

        // Voice-first mode: microphone streaming starts automatically after Connect Live.
        // There is intentionally no manual mic toggle, typed-message field, send button,
        // or preset-command chip row on this screen.
        Text(
            text = if (connectionStatus == LiveConnectionStatus.READY && isRecordingMic && hasMicPermission) {
                "Live conversation active • Speak naturally"
            } else {
                "Connect Live to start a continuous voice conversation"
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondaryDark,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun HomeTopHeader(
    connectionStatus: LiveConnectionStatus,
    onConnectLive: () -> Unit,
    onDisconnectLive: () -> Unit,
    onClearConversation: () -> Unit
) {
    val isConnectedOrConnecting = connectionStatus == LiveConnectionStatus.READY ||
        connectionStatus == LiveConnectionStatus.CONNECTING_HANDSHAKE ||
        connectionStatus == LiveConnectionStatus.WAITING_FOR_SETUP_COMPLETE

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(
                    modifier = Modifier
                        .size(38.dp)
                        .semantics { contentDescription = "Kamakhya AI Emblem" }
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(CyanAccent, ElectricBlueAccent, VioletAccent.copy(alpha = 0.2f))
                        ),
                        radius = size.minDimension / 2f,
                        center = center
                    )
                    drawCircle(DeepNavyBackground, size.minDimension * 0.24f, center)
                    drawCircle(CyanGlow, size.minDimension * 0.12f, center)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Kamakhya AI",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimaryDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Gemini Live 3.8 • Multimodal & Phone Actions",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onClearConversation,
                modifier = Modifier.testTag("clear_conversation_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = "Clear conversation cards",
                    tint = TextSecondaryDark
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = if (isConnectedOrConnecting) onDisconnectLive else onConnectLive,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                border = BorderStroke(
                    1.dp,
                    if (connectionStatus == LiveConnectionStatus.READY) StatusConnectedGreen
                    else CyanAccent.copy(alpha = 0.7f)
                ),
                modifier = Modifier
                    .widthIn(min = 142.dp)
                    .testTag("live_connect_disconnect_button")
            ) {
                Icon(
                    imageVector = if (isConnectedOrConnecting) Icons.Filled.LinkOff else Icons.Filled.Link,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (connectionStatus == LiveConnectionStatus.READY) StatusConnectedGreen else CyanAccent
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when (connectionStatus) {
                        LiveConnectionStatus.READY -> "Disconnect"
                        LiveConnectionStatus.CONNECTING_HANDSHAKE,
                        LiveConnectionStatus.WAITING_FOR_SETUP_COMPLETE -> "Connecting…"
                        else -> "Connect Live"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimaryDark,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun LiveStatusTelemetryBar(
    orbState: AssistantOrbState,
    connectionStatus: LiveConnectionStatus,
    connectionDetail: String,
    isRecordingMic: Boolean,
    onOpenSettings: () -> Unit
) {
    val dotColor = when (connectionStatus) {
        LiveConnectionStatus.READY -> StatusConnectedGreen
        LiveConnectionStatus.CONNECTING_HANDSHAKE,
        LiveConnectionStatus.WAITING_FOR_SETUP_COMPLETE -> StatusWarningAmber
        LiveConnectionStatus.ERROR -> StatusErrorRed
        LiveConnectionStatus.DISCONNECTED -> TextMutedDark
    }

    val stateLabel = when (orbState) {
        AssistantOrbState.DISCONNECTED -> "DISCONNECTED"
        AssistantOrbState.IDLE -> "IDLE • READY"
        AssistantOrbState.LISTENING -> "LISTENING (16 kHz PCM)"
        AssistantOrbState.PROCESSING -> "PROCESSING"
        AssistantOrbState.SPEAKING -> "SPEAKING (24 kHz PCM)"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .clickable { onOpenSettings() },
        shape = RoundedCornerShape(12.dp),
        color = DeepNavySurface,
        border = BorderStroke(1.dp, DeepNavyElevated)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$stateLabel — $connectionDetail",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondaryDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isRecordingMic) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "REC ●",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusErrorRed,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Animated Luminous Orb rendered purely with Jetpack Compose Canvas, layered circles,
 * radial/sweep gradients, and state-driven motion for:
 * IDLE, LISTENING, PROCESSING, SPEAKING, and DISCONNECTED.
 */
@Composable
fun LuminousAssistantOrb(
    orbState: AssistantOrbState,
    micLevel: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "kamakhya_orb_transition")

    val breathingPhase by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_phase"
    )

    val rotationDegrees by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbital_rotation"
    )

    val rippleWave by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "speaking_ripple"
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f

        when (orbState) {
            AssistantOrbState.DISCONNECTED -> {
                // Muted static concentric rings indicating inactive/disconnected state
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ElectricBlueAccent.copy(alpha = 0.18f),
                            DeepNavySurface.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxRadius * 0.85f
                    ),
                    radius = maxRadius * 0.85f,
                    center = center
                )
                drawCircle(
                    color = TextMutedDark.copy(alpha = 0.35f),
                    radius = maxRadius * 0.56f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1F3256),
                            Color(0xFF101B33)
                        ),
                        center = center,
                        radius = maxRadius * 0.42f
                    ),
                    radius = maxRadius * 0.42f,
                    center = center
                )
            }

            AssistantOrbState.IDLE -> {
                // Calm cyan-blue breathing luminous core
                val rOuter = maxRadius * 0.82f * breathingPhase
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            CyanAccent.copy(alpha = 0.35f),
                            ElectricBlueAccent.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = rOuter
                    ),
                    radius = rOuter,
                    center = center
                )
                drawCircle(
                    color = CyanAccent.copy(alpha = 0.45f),
                    radius = maxRadius * 0.58f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            CyanGlow,
                            ElectricBlueAccent,
                            DeepNavyCard
                        ),
                        center = center,
                        radius = maxRadius * 0.44f * breathingPhase
                    ),
                    radius = maxRadius * 0.44f * breathingPhase,
                    center = center
                )
            }

            AssistantOrbState.LISTENING -> {
                // Active microphone capture: responsive cyan + electric blue soundwave rings
                val dynamicBoost = 1f + (micLevel.coerceIn(0f, 1f) * 0.35f)
                val pulseRadius = maxRadius * (0.65f + 0.32f * rippleWave) * dynamicBoost.coerceAtMost(1.25f)

                drawCircle(
                    color = CyanAccent.copy(alpha = (1f - rippleWave) * 0.55f),
                    radius = pulseRadius,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            CyanGlow.copy(alpha = 0.65f),
                            ElectricBlueAccent.copy(alpha = 0.35f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxRadius * 0.88f
                    ),
                    radius = maxRadius * 0.88f,
                    center = center
                )
                rotate(degrees = rotationDegrees, pivot = center) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colors = listOf(CyanAccent, ElectricBlueAccent, VioletAccent, CyanAccent),
                            center = center
                        ),
                        radius = maxRadius * 0.56f * dynamicBoost.coerceAtMost(1.18f),
                        center = center,
                        style = Stroke(width = 4.dp.toPx())
                    )
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, CyanAccent, ElectricBlueAccent),
                        center = center,
                        radius = maxRadius * 0.42f * dynamicBoost.coerceAtMost(1.15f)
                    ),
                    radius = maxRadius * 0.42f * dynamicBoost.coerceAtMost(1.15f),
                    center = center
                )
            }

            AssistantOrbState.PROCESSING -> {
                // Rotating violet + cyan dual orbital rings
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            VioletAccent.copy(alpha = 0.45f),
                            ElectricBlueAccent.copy(alpha = 0.2f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxRadius * 0.86f
                    ),
                    radius = maxRadius * 0.86f,
                    center = center
                )
                rotate(degrees = rotationDegrees * 1.5f, pivot = center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(Color.Transparent, CyanAccent, VioletAccent, Color.Transparent),
                            center = center
                        ),
                        startAngle = 0f,
                        sweepAngle = 260f,
                        useCenter = false,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(SoftViolet, VioletAccent, DeepNavyCard),
                        center = center,
                        radius = maxRadius * 0.40f
                    ),
                    radius = maxRadius * 0.40f,
                    center = center
                )
            }

            AssistantOrbState.SPEAKING -> {
                // Multi-layered harmonic violet + cyan resonance waves
                val wave1 = maxRadius * (0.50f + 0.45f * rippleWave)
                val wave2 = maxRadius * (0.50f + 0.45f * ((rippleWave + 0.5f) % 1f))

                drawCircle(
                    color = CyanAccent.copy(alpha = (1f - rippleWave) * 0.5f),
                    radius = wave1,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
                drawCircle(
                    color = SoftViolet.copy(alpha = (1f - ((rippleWave + 0.5f) % 1f)) * 0.5f),
                    radius = wave2,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx())
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            CyanGlow,
                            VioletAccent,
                            ElectricBlueAccent
                        ),
                        center = center,
                        radius = maxRadius * 0.48f * breathingPhase
                    ),
                    radius = maxRadius * 0.48f * breathingPhase,
                    center = center
                )
            }
        }
    }
}

@Composable
private fun PendingActionInteractiveCard(
    outcome: PhoneActionOutcome,
    onConfirm: (PhoneActionRequest) -> Unit,
    onSelectApp: (AppLaunchCandidate) -> Unit,
    onDialNumber: (String, String) -> Unit,
    onOpenWriteSettings: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    var manualPhoneInput by rememberSaveable { mutableStateOf("") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .padding(vertical = 6.dp)
            .testTag("pending_action_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DeepNavyElevated),
        border = BorderStroke(1.dp, CyanAccent)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = "Action confirmation",
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Action Verification Required",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimaryDark
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss action card",
                        tint = TextSecondaryDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            when (outcome) {
                is PhoneActionOutcome.ConfirmationRequired -> {
                    Text(
                        text = outcome.promptMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryDark
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onConfirm(outcome.pendingRequest) },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = DeepNavyBackground),
                            modifier = Modifier.testTag("confirm_phone_action_button")
                        ) {
                            Text("Confirm & Proceed")
                        }
                        OutlinedButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                    }
                }

                is PhoneActionOutcome.AmbiguousAppSelection -> {
                    Text(
                        text = "Multiple installed apps matched '${outcome.query}'. Select the app to launch:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryDark
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        outcome.candidates.forEach { candidate ->
                            OutlinedButton(
                                onClick = { onSelectApp(candidate) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("${candidate.appLabel} (${candidate.packageName})")
                            }
                        }
                    }
                }

                is PhoneActionOutcome.AmbiguousContactSelection -> {
                    Text(
                        text = "Multiple contacts matched '${outcome.query}'. Choose which number to open in the dialer:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryDark
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        outcome.candidates.forEach { contact ->
                            OutlinedButton(
                                onClick = { onDialNumber(contact.displayName, contact.phoneNumber) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${contact.displayName} • ${contact.phoneNumber}")
                            }
                        }
                    }
                }

                is PhoneActionOutcome.WriteSettingsPermissionRequired -> {
                    Text(
                        text = outcome.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryDark
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onOpenWriteSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = DeepNavyBackground)
                    ) {
                        Text("Open 'Modify System Settings' Screen")
                    }
                }

                is PhoneActionOutcome.ContactsPermissionOrManualDialRequired -> {
                    Text(
                        text = outcome.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryDark
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualPhoneInput,
                        onValueChange = { manualPhoneInput = it },
                        label = { Text("Enter phone number manually") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_dial_number_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (manualPhoneInput.isNotBlank()) {
                                    onDialNumber(outcome.contactQuery, manualPhoneInput)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = DeepNavyBackground),
                            modifier = Modifier.testTag("open_dialer_manual_button")
                        ) {
                            Text("Open Dialer")
                        }
                        OutlinedButton(onClick = onRequestContactsPermission) {
                            Text("Grant Contacts Access")
                        }
                    }
                }

                else -> {}
            }
        }
    }
}

@Composable
private fun ConversationMessageCard(
    message: ConversationMessage,
    onOpenCategory: (AssetCategory) -> Unit
) {
    val isUser = message.sender == MessageSender.USER_TEXT || message.sender == MessageSender.USER_VOICE
    val isError = message.sender == MessageSender.SYSTEM_ERROR
    val isAction = message.sender == MessageSender.ACTION_ROUTER

    val containerColor = when {
        isError -> Color(0xFF2D121E)
        isAction -> Color(0xFF102837)
        isUser -> DeepNavyElevated
        else -> DeepNavyCard
    }

    val borderColor = when {
        isError -> StatusErrorRed.copy(alpha = 0.6f)
        isAction -> CyanAccent.copy(alpha = 0.5f)
        isUser -> ElectricBlueAccent.copy(alpha = 0.45f)
        else -> VioletAccent.copy(alpha = 0.35f)
    }

    val timeStr = remember(message.timestampMs) {
        SimpleDateFormat("hh:mm:ss a", Locale.US).format(Date(message.timestampMs))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        isError -> StatusErrorRed
                        isAction -> CyanAccent
                        isUser -> CyanGlow
                        else -> SoftViolet
                    }
                )
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMutedDark
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimaryDark
            )
            if (message.linkedCategory != null) {
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    onClick = { onOpenCategory(message.linkedCategory) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "View in Assets → ${message.linkedCategory.displayName}",
                        color = CyanAccent,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
