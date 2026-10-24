package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.relay.data.ProxyConfig
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberAccent
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun QuickSetupCard(
    config: ProxyConfig,
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val httpEnabled = config.httpPort > 0

    val lanIp by produceState<String?>(initialValue = null, config.allowLan, isRunning) {
        value = if (config.allowLan && isRunning) {
            withContext(Dispatchers.IO) { getLanIpv4Address() }
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(CyberSurface)
            .border(1.dp, CyberBorder.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
            .padding(16.dp)
            .testTag("quick_setup_card")
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.quick_setup_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextPrimary
                    )
                }

                Text(
                    text = stringResource(if (isRunning) R.string.local_proxy_active else R.string.local_proxy_inactive),
                    fontSize = 11.sp,
                    color = if (isRunning) CyberCyan else CyberTextMuted
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Telegram One-Tap Connect Button
            Button(
                onClick = {
                    val tgUri = "tg://socks?server=127.0.0.1&port=${config.socksPort}"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(tgUri))
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        copyToClipboard(context, tgUri, context.getString(R.string.toast_telegram_copied))
                    }
                },
                enabled = isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("telegram_connect_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF229ED9),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF229ED9).copy(alpha = 0.3f),
                    disabledContentColor = Color.White.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.NearMe,
                        contentDescription = stringResource(R.string.cd_telegram),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.telegram_connect),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Copy Address Buttons (SOCKS5 & HTTP)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CopyAddressChip(
                    label = "SOCKS5",
                    address = "127.0.0.1:${config.socksPort}",
                    accentColor = CyberAccent,
                    onCopy = {
                        copyToClipboard(context, "127.0.0.1:${config.socksPort}", context.getString(R.string.toast_socks_copied))
                    },
                    modifier = Modifier.weight(1f)
                )

                CopyAddressChip(
                    label = "HTTP",
                    address = if (httpEnabled) "127.0.0.1:${config.httpPort}" else stringResource(R.string.http_off),
                    accentColor = if (httpEnabled) CyberCyan else CyberTextMuted,
                    enabled = httpEnabled,
                    onCopy = {
                        if (httpEnabled) {
                            copyToClipboard(context, "127.0.0.1:${config.httpPort}", context.getString(R.string.toast_http_copied))
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            if (lanIp != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val lanAddress = "$lanIp:${config.socksPort}"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CyberSurfaceVariant.copy(alpha = 0.55f))
                        .border(0.5.dp, CyberBorder.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) {
                            copyToClipboard(context, lanAddress, context.getString(R.string.toast_lan_copied))
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lan,
                        contentDescription = null,
                        tint = CyberAccent,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.lan_address_label),
                        fontSize = 10.sp,
                        color = CyberTextMuted
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = lanAddress,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CyberTextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun CopyAddressChip(
    label: String,
    address: String,
    accentColor: Color,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CyberSurfaceVariant.copy(alpha = 0.55f))
            .border(0.5.dp, CyberBorder.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onCopy)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Text(
                    text = address,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) CyberTextPrimary else CyberTextMuted,
                    fontFamily = FontFamily.Monospace
                )
            }
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.cd_copy),
                tint = accentColor.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "$label: $text", Toast.LENGTH_SHORT).show()
}

private fun getLanIpv4Address(): String? {
    return try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.firstNotNullOfOrNull { nif ->
            if (!nif.isUp || nif.isLoopback) return@firstNotNullOfOrNull null
            nif.inetAddresses.toList()
                .firstOrNull { addr -> !addr.isLoopbackAddress && addr is Inet4Address }
                ?.hostAddress
        }
    } catch (_: Throwable) {
        null
    }
}
