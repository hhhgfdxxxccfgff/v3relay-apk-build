package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.relay.data.ProxyStats
import com.example.ui.PingState
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberAccent
import com.example.ui.theme.CyberError
import com.example.ui.theme.CyberPurple
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.CyberWarning
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun StatsCard(
    isRunning: Boolean,
    stats: ProxyStats,
    pingState: PingState,
    onTestPing: () -> Unit,
    modifier: Modifier = Modifier
) {
    var uptimeFormatted by remember { mutableStateOf("00:00:00") }

    LaunchedEffect(isRunning, stats.startTimeMillis) {
        if (isRunning && stats.startTimeMillis > 0) {
            while (true) {
                val elapsedSec = (System.currentTimeMillis() - stats.startTimeMillis) / 1000
                val h = elapsedSec / 3600
                val m = (elapsedSec % 3600) / 60
                val s = elapsedSec % 60
                uptimeFormatted = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
                delay(1000)
            }
        } else {
            uptimeFormatted = "00:00:00"
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(CyberSurface)
            .border(1.dp, CyberBorder.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
            .padding(16.dp)
            .testTag("stats_card")
    ) {
        Column {
            // Top Row: status indicator on the left, interactive ping on the right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 2.dp)
                ) {
                    PulseDot(isRunning = isRunning)
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = stringResource(if (isRunning) R.string.status_connected else R.string.status_offline),
                        color = if (isRunning) CyberCyan else CyberTextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp
                    )
                }

                // Interactive Ping Pill (Always Clickable!)
                InteractivePingBadge(
                    pingState = pingState,
                    onClick = onTestPing
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Four compact metrics in a single minimal row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactMetric(
                    title = stringResource(R.string.metric_download),
                    value = formatBytes(stats.bytesDownloaded),
                    icon = Icons.Default.ArrowDownward,
                    iconColor = CyberCyan,
                    modifier = Modifier.weight(1f)
                )
                MetricDivider()
                CompactMetric(
                    title = stringResource(R.string.metric_upload),
                    value = formatBytes(stats.bytesUploaded),
                    icon = Icons.Default.ArrowUpward,
                    iconColor = CyberPurple,
                    modifier = Modifier.weight(1f)
                )
                MetricDivider()
                CompactMetric(
                    title = stringResource(R.string.metric_active_conns),
                    value = "${stats.activeConnections}",
                    icon = Icons.Default.Sensors,
                    iconColor = CyberPurple,
                    modifier = Modifier.weight(1f)
                )
                MetricDivider()
                CompactMetric(
                    title = stringResource(R.string.metric_uptime),
                    value = uptimeFormatted,
                    icon = Icons.Default.Timer,
                    iconColor = CyberWarning,
                    isMonospace = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(26.dp)
            .background(CyberBorder.copy(alpha = 0.6f))
    )
}

@Composable
private fun PulseDot(isRunning: Boolean) {
    val transition = rememberInfiniteTransition(label = "status_pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(
                if (isRunning) CyberCyan.copy(alpha = pulseAlpha) else CyberTextMuted.copy(alpha = 0.7f)
            )
    )
}

@Composable
private fun InteractivePingBadge(
    pingState: PingState,
    onClick: () -> Unit
) {
    val (bg, border, contentColor) = when (pingState) {
        is PingState.Testing -> Triple(CyberCyan.copy(alpha = 0.12f), CyberCyan.copy(alpha = 0.7f), CyberCyan)
        is PingState.Success -> {
            val c = when {
                pingState.latencyMs < 160 -> CyberCyan
                pingState.latencyMs < 320 -> CyberWarning
                else -> CyberError
            }
            Triple(c.copy(alpha = 0.12f), c.copy(alpha = 0.7f), c)
        }
        is PingState.Failed -> Triple(CyberError.copy(alpha = 0.12f), CyberError.copy(alpha = 0.7f), CyberError)
        PingState.Idle -> Triple(CyberCyan.copy(alpha = 0.08f), CyberBorder, CyberTextSecondary)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("ping_test_button"),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (pingState) {
                is PingState.Testing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.6.dp,
                        color = contentColor
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.ping_testing),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                }
                is PingState.Success -> {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = stringResource(R.string.cd_ping),
                        tint = contentColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.ping_ms, pingState.latencyMs),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = contentColor,
                        fontFamily = FontFamily.Monospace
                    )
                    val quality = stringResource(
                        when {
                            pingState.latencyMs < 160 -> R.string.quality_excellent
                            pingState.latencyMs < 320 -> R.string.quality_good
                            else -> R.string.quality_medium
                        }
                    )
                    Text(
                        text = stringResource(R.string.ping_quality_suffix, quality),
                        fontSize = 10.sp,
                        color = contentColor.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh),
                        tint = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                }
                is PingState.Failed -> {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_retry),
                        tint = contentColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (pingState.error.isBlank()) stringResource(R.string.ping_retry) else stringResource(R.string.ping_error, pingState.error),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 170.dp)
                    )
                }
                PingState.Idle -> {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = stringResource(R.string.cd_ping),
                        tint = contentColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.ping_test),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactMetric(
    title: String,
    value: String,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            color = CyberTextPrimary,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = title,
                fontSize = 9.sp,
                color = CyberTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
