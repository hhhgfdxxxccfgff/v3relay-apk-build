package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.relay.data.ProxyMode
import com.example.ui.theme.CyberAccent
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberOnAccent
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextSecondary

/**
 * Minimal segmented control: one slim track, a graphite thumb that slides
 * between the two modes with a soft easing curve.
 */
@Composable
fun ModeSwitch(
    currentMode: ProxyMode,
    isRunning: Boolean,
    onModeSelected: (ProxyMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CyberSurfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, CyberBorder.copy(alpha = 0.6f), shape)
            .padding(4.dp)
            .testTag("mode_switch_container")
    ) {
        val gap = 4.dp
        val segmentWidth = (maxWidth - gap) / 2
        val indicatorOffset by animateDpAsState(
            targetValue = if (currentMode == ProxyMode.VPN_MODE) 0.dp else segmentWidth + gap,
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "segIndicator"
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CyberAccent)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            SegmentTab(
                label = stringResource(R.string.mode_seg_vpn),
                icon = Icons.Default.VpnKey,
                isSelected = currentMode == ProxyMode.VPN_MODE,
                enabled = !isRunning,
                onClick = { onModeSelected(ProxyMode.VPN_MODE) },
                modifier = Modifier.weight(1f).testTag("mode_vpn_button")
            )

            SegmentTab(
                label = stringResource(R.string.mode_seg_proxy),
                icon = Icons.Default.Lan,
                isSelected = currentMode == ProxyMode.PROXY_ONLY,
                enabled = !isRunning,
                onClick = { onModeSelected(ProxyMode.PROXY_ONLY) },
                modifier = Modifier.weight(1f).testTag("mode_proxy_button")
            )
        }
    }
}

@Composable
private fun SegmentTab(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) CyberOnAccent else CyberTextSecondary,
        animationSpec = tween(260),
        label = "segContent"
    )
    val iconColor by animateColorAsState(
        targetValue = if (isSelected) CyberOnAccent.copy(alpha = 0.8f) else CyberTextMuted,
        animationSpec = tween(260),
        label = "segIcon"
    )

    Row(
        modifier = modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.55f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(15.dp)
        )

        Spacer(modifier = Modifier.width(7.dp))

        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            letterSpacing = 0.3.sp
        )
    }
}
