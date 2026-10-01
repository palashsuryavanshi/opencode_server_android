package com.palash.opencodeserver.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.ConnectionStatus
import com.palash.opencodeserver.model.ServerStatus
import com.palash.opencodeserver.model.SessionStatus
import com.palash.opencodeserver.ui.theme.*

@Composable
fun StatusPill(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val (dotColor, label, bg, border) = when (status) {
        ConnectionStatus.CONNECTED -> Quadruple(DevAccentEmerald, "Connected", DevAccentEmeraldSubtle, DevAccentEmerald.copy(alpha = 0.3f))
        ConnectionStatus.DEMO -> Quadruple(DevAccentCyan, "Demo Mode", DevAccentCyanSubtle, DevAccentCyan.copy(alpha = 0.35f))
        ConnectionStatus.CONNECTING -> Quadruple(DevAccentAmber, "Connecting...", DevAccentAmberSubtle, DevAccentAmber.copy(alpha = 0.3f))
        ConnectionStatus.DISCONNECTED -> Quadruple(DevTextMuted, "Disconnected", DevSurfaceElevated, DevBorder)
        ConnectionStatus.ERROR -> Quadruple(DevAccentRed, "Error", DevAccentRedSubtle, DevAccentRed.copy(alpha = 0.3f))
    }

    BaseStatusPill(
        dotColor = dotColor,
        label = label,
        backgroundColor = bg,
        borderColor = border,
        isPulsing = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.DEMO,
        modifier = modifier.testTag("status_pill_connection"),
        onClick = onClick
    )
}

@Composable
fun ServerStatusPill(
    status: ServerStatus,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val (dotColor, label, bg, border) = when (status) {
        ServerStatus.RUNNING -> Quadruple(DevAccentEmerald, "Running :4096", DevAccentEmeraldSubtle, DevAccentEmerald.copy(alpha = 0.3f))
        ServerStatus.DEMO -> Quadruple(DevAccentCyan, "Demo :4096", DevAccentCyanSubtle, DevAccentCyan.copy(alpha = 0.35f))
        ServerStatus.STARTING -> Quadruple(DevAccentAmber, "Starting...", DevAccentAmberSubtle, DevAccentAmber.copy(alpha = 0.3f))
        ServerStatus.STOPPED -> Quadruple(DevTextMuted, "Stopped", DevSurfaceElevated, DevBorder)
        ServerStatus.ERROR -> Quadruple(DevAccentRed, "Error", DevAccentRedSubtle, DevAccentRed.copy(alpha = 0.3f))
    }

    BaseStatusPill(
        dotColor = dotColor,
        label = label,
        backgroundColor = bg,
        borderColor = border,
        isPulsing = status == ServerStatus.STARTING,
        modifier = modifier.testTag("status_pill_server"),
        onClick = onClick
    )
}

@Composable
fun SessionStatusPill(
    status: SessionStatus,
    modifier: Modifier = Modifier
) {
    val (dotColor, label, bg, border) = when (status) {
        SessionStatus.IDLE -> Quadruple(DevAccentCyan, "Idle", DevAccentCyanSubtle, DevAccentCyan.copy(alpha = 0.3f))
        SessionStatus.THINKING -> Quadruple(DevAccentPurple, "Thinking", Color(0x1FA78BFA), DevAccentPurple.copy(alpha = 0.3f))
        SessionStatus.WORKING -> Quadruple(DevAccentAmber, "Working", DevAccentAmberSubtle, DevAccentAmber.copy(alpha = 0.3f))
        SessionStatus.WAITING -> Quadruple(DevAccentCyan, "Waiting", DevAccentCyanSubtle, DevAccentCyan.copy(alpha = 0.3f))
        SessionStatus.COMPLETED -> Quadruple(DevAccentEmerald, "Completed", DevAccentEmeraldSubtle, DevAccentEmerald.copy(alpha = 0.3f))
        SessionStatus.ERROR -> Quadruple(DevAccentRed, "Error", DevAccentRedSubtle, DevAccentRed.copy(alpha = 0.3f))
    }

    BaseStatusPill(
        dotColor = dotColor,
        label = label,
        backgroundColor = bg,
        borderColor = border,
        isPulsing = status == SessionStatus.THINKING || status == SessionStatus.WORKING,
        modifier = modifier.testTag("status_pill_session"),
        onClick = null
    )
}

@Composable
private fun BaseStatusPill(
    dotColor: Color,
    label: String,
    backgroundColor: Color,
    borderColor: Color,
    isPulsing: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val alphaAnim by if (isPulsing) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        )
    } else {
        rememberUpdatedState(1.0f)
    }

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .then(clickableModifier)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = alphaAnim))
        )
        Text(
            text = label,
            color = DevTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = DevMonospace
        )
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
