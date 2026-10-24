package com.example.ui.components

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.relay.data.ConfigDecoder
import com.example.relay.data.ProxyConfig
import com.example.relay.data.ProxyMode
import com.example.relay.data.ThemeMode
import com.example.util.CrashGuardian
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberDark
import com.example.ui.theme.CyberAccent
import com.example.ui.theme.CyberError
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.CyberWarning


@Composable
fun SettingsSection(
    config: ProxyConfig,
    isRunning: Boolean,
    onCleanIpChange: (String) -> Unit,
    onSocksPortChange: (Int) -> Unit,
    onHttpPortChange: (Int) -> Unit,
    onDnsServersChange: (String) -> Unit = {},
    onExcludedAppsChange: (Set<String>) -> Unit = {},
    onAutoStartChange: (Boolean) -> Unit = {},
    onAllowLanChange: (Boolean) -> Unit = {},
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onLanguageChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    var showSplitDialog by remember { mutableStateOf(false) }

    // Temporary editable text for fields; committed on focus loss / Done key
    var socksPortText by remember(config.socksPort) { mutableStateOf(config.socksPort.toString()) }
    var httpPortText by remember(config.httpPort) { mutableStateOf(config.httpPort.toString()) }
    var dnsText by remember(config.dnsServers) { mutableStateOf(config.dnsServers) }

    fun commitSocksPort() {
        val p = socksPortText.toIntOrNull()
        if (p != null && p in 1024..65535) {
            if (p != config.socksPort) onSocksPortChange(p)
        } else {
            socksPortText = config.socksPort.toString()
        }
    }

    fun commitHttpPort() {
        val p = httpPortText.toIntOrNull()
        if (p != null && p == 0) {
            if (p != config.httpPort) onHttpPortChange(0)
        } else if (p != null && p in 1024..65535) {
            if (p != config.httpPort) onHttpPortChange(p)
        } else {
            httpPortText = config.httpPort.toString()
        }
    }

    fun commitDns() {
        val cleaned = dnsText.trim()
        if (cleaned != config.dnsServers) onDnsServersChange(cleaned)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(CyberSurface)
            .border(1.dp, CyberBorder.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
            .padding(16.dp)
            .testTag("settings_section")
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberTextPrimary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.advanced_restart_hint),
                fontSize = 10.sp,
                color = CyberTextMuted
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Clean IP Field
            Text(
                text = stringResource(R.string.clean_ip_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = CyberTextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = config.cleanIp,
                onValueChange = onCleanIpChange,
                placeholder = { Text(stringResource(R.string.clean_ip_hint), fontSize = 12.sp, color = CyberTextMuted) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clean_ip_input"),
                enabled = !isRunning,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    focusedTextColor = CyberTextPrimary,
                    unfocusedTextColor = CyberTextPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Ports
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = socksPortText,
                    onValueChange = { str ->
                        socksPortText = str.filter { it.isDigit() }.take(5)
                    },
                    label = { Text(stringResource(R.string.port_socks), fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { state ->
                            if (!state.isFocused) commitSocksPort()
                        },
                    enabled = !isRunning,
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = httpPortText,
                    onValueChange = { str ->
                        httpPortText = str.filter { it.isDigit() }.take(5)
                    },
                    label = { Text(stringResource(R.string.port_http), fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { state ->
                            if (!state.isFocused) commitHttpPort()
                        },
                    enabled = !isRunning,
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
            }

            if (config.mode == ProxyMode.VPN_MODE) {
                Spacer(modifier = Modifier.height(12.dp))

                // DNS Servers
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = CyberTextMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.dns_label),
                        fontSize = 11.sp,
                        color = CyberTextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = dnsText,
                    onValueChange = { dnsText = it },
                    placeholder = { Text(stringResource(R.string.dns_hint), fontSize = 12.sp, color = CyberTextMuted) },
                    supportingText = { Text(stringResource(R.string.dns_support), fontSize = 10.sp, color = CyberTextMuted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            if (!state.isFocused) commitDns()
                        }
                        .testTag("dns_input"),
                    enabled = !isRunning,
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Auto-start on boot
            SettingSwitchRow(
                label = stringResource(R.string.auto_start_label),
                checked = config.autoStartOnBoot,
                enabled = true,
                testTag = "auto_start_switch",
                onCheckedChange = onAutoStartChange
            )

            // LAN binding
            SettingSwitchRow(
                label = stringResource(R.string.allow_lan_label),
                checked = config.allowLan,
                enabled = true,
                testTag = "allow_lan_switch",
                onCheckedChange = onAllowLanChange
            )
            if (config.allowLan) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.allow_lan_warning),
                    fontSize = 10.sp,
                    color = CyberWarning
                )
            }

            if (config.mode == ProxyMode.VPN_MODE) {
                Spacer(modifier = Modifier.height(14.dp))

                // Split tunneling
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FilterAlt,
                            contentDescription = null,
                            tint = CyberTextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.split_tunnel_label),
                            fontSize = 11.sp,
                            color = CyberTextSecondary
                        )
                    }
                    Text(
                        text = if (config.excludedApps.isEmpty()) {
                            stringResource(R.string.split_tunnel_none)
                        } else {
                            stringResource(R.string.split_tunnel_count, config.excludedApps.size)
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (config.excludedApps.isEmpty()) CyberTextMuted else CyberAccent
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { showSplitDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("split_tunnel_button"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyan)
                ) {
                    Text(stringResource(R.string.split_tunnel_manage), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Theme
            Text(
                text = stringResource(R.string.theme_label),
                fontSize = 11.sp,
                color = CyberTextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingChip(
                    label = stringResource(R.string.theme_system),
                    selected = config.themeMode == ThemeMode.SYSTEM,
                    testTag = "theme_chip_system",
                    onClick = { onThemeModeChange(ThemeMode.SYSTEM) }
                )
                SettingChip(
                    label = stringResource(R.string.theme_light),
                    selected = config.themeMode == ThemeMode.LIGHT,
                    testTag = "theme_chip_light",
                    onClick = { onThemeModeChange(ThemeMode.LIGHT) }
                )
                SettingChip(
                    label = stringResource(R.string.theme_dark),
                    selected = config.themeMode == ThemeMode.DARK,
                    testTag = "theme_chip_dark",
                    onClick = { onThemeModeChange(ThemeMode.DARK) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Language
            Text(
                text = stringResource(R.string.language_label),
                fontSize = 11.sp,
                color = CyberTextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingChip(
                    label = stringResource(R.string.lang_system),
                    selected = config.language.isBlank(),
                    testTag = "lang_chip_system",
                    onClick = { onLanguageChange("") }
                )
                SettingChip(
                    label = stringResource(R.string.lang_fa),
                    selected = config.language == "fa",
                    testTag = "lang_chip_fa",
                    onClick = { onLanguageChange("fa") }
                )
                SettingChip(
                    label = stringResource(R.string.lang_en),
                    selected = config.language == "en",
                    testTag = "lang_chip_en",
                    onClick = { onLanguageChange("en") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    val log = CrashGuardian.readLog(context)
                    if (log.isBlank()) {
                        Toast.makeText(context, context.getString(R.string.crash_log_empty), Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_log_subject))
                                putExtra(Intent.EXTRA_TEXT, log)
                            }
                            context.startActivity(
                                Intent.createChooser(share, context.getString(R.string.share_crash_log))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: Exception) {
                            Toast.makeText(context, context.getString(R.string.crash_log_empty), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberTextSecondary),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .testTag("share_crash_log_button")
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = CyberTextSecondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.share_crash_log),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    if (showSplitDialog) {
        SplitTunnelingDialog(
            selected = config.excludedApps,
            onSave = { apps ->
                onExcludedAppsChange(apps)
                showSplitDialog = false
            },
            onDismiss = { showSplitDialog = false }
        )
    }
}


@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = CyberTextSecondary,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = CyberDark,
                checkedTrackColor = CyberAccent,
                uncheckedThumbColor = CyberTextMuted,
                uncheckedTrackColor = CyberSurfaceVariant
            )
        )
    }
}

@Composable
private fun SettingChip(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CyberAccent.copy(alpha = 0.18f) else CyberDark.copy(alpha = 0.5f))
            .border(
                1.dp,
                if (selected) CyberAccent.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                RoundedCornerShape(10.dp)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) CyberAccent else CyberTextSecondary
        )
    }
}