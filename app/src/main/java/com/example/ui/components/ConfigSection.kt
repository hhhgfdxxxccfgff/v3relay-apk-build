package com.example.ui.components

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
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
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberDark
import com.example.ui.theme.CyberAccent
import com.example.ui.theme.CyberError
import com.example.qr.QrScanActivity
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.CyberWarning

@Composable
fun ConfigSection(
    config: ProxyConfig,
    isRunning: Boolean,
    onImportConfig: (String) -> Unit,
    onClearConfig: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var configCodeInput by remember { mutableStateOf("") }
    var showReplaceInput by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    val hasConfig = config.workerUrl.isNotBlank() && config.secret.isNotBlank()

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val contents = try {
                result.data?.getStringExtra(QrScanActivity.EXTRA_RESULT)
            } catch (e: Exception) {
                null
            }
            if (!contents.isNullOrBlank()) {
                val res = ConfigDecoder.decodeConfig(contents)
                if (res.isSuccess) {
                    onImportConfig(contents)
                    configCodeInput = ""
                    showReplaceInput = false
                    importError = null
                    Toast.makeText(context, context.getString(R.string.toast_config_imported), Toast.LENGTH_SHORT).show()
                } else {
                    configCodeInput = contents
                    importError = res.exceptionOrNull()?.message ?: context.getString(R.string.err_invalid_config)
                    Toast.makeText(context, importError, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val launchScanner: () -> Unit = {
        try {
            scanLauncher.launch(Intent(context, QrScanActivity::class.java))
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.toast_qr_perm_needed), Toast.LENGTH_LONG).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchScanner()
        } else {
            Toast.makeText(context, context.getString(R.string.toast_qr_perm_needed), Toast.LENGTH_LONG).show()
        }
    }

    val onQrScanClick: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(CyberSurface)
            .border(1.dp, CyberBorder.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
            .padding(16.dp)
            .testTag("config_section")
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
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = CyberAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.config_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberAccent.copy(alpha = 0.15f))
                        .border(0.5.dp, CyberAccent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = stringResource(if (hasConfig) R.string.config_active else R.string.config_none),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (hasConfig) CyberAccent else CyberTextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Active Protected Profile Card
            if (hasConfig && !showReplaceInput) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CyberDark.copy(alpha = 0.85f))
                        .border(0.5.dp, CyberAccent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = CyberAccent,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (config.configName.isNotBlank()) config.configName else stringResource(R.string.config_profile_default),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberTextPrimary,
                                    maxLines = 1
                                )
                                Text(
                                    text = stringResource(R.string.config_protected_hint),
                                    fontSize = 11.sp,
                                    color = CyberTextMuted,
                                    maxLines = 1
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { showReplaceInput = true },
                                enabled = !isRunning,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(34.dp).testTag("replace_config_button"),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.btn_replace), fontSize = 11.sp)
                            }

                            if (onClearConfig != null) {
                                Spacer(modifier = Modifier.width(6.dp))
                                OutlinedButton(
                                    onClick = onClearConfig,
                                    enabled = !isRunning,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(34.dp).testTag("delete_config_button"),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberError),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.cd_clear), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                // Config Import Field
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.config_import_label),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = CyberTextSecondary
                    )

                    if (hasConfig) {
                        Text(
                            text = stringResource(R.string.btn_cancel),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberCyan,
                            modifier = Modifier
                                .clickable(role = Role.Button) { showReplaceInput = false }
                                .padding(4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = configCodeInput,
                        onValueChange = {
                            configCodeInput = it
                            if (importError != null) importError = null
                        },
                        placeholder = { Text(stringResource(R.string.config_input_hint), fontSize = 12.sp, color = CyberTextMuted) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("config_code_input"),
                        singleLine = false,
                        maxLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            focusedTextColor = CyberTextPrimary,
                            unfocusedTextColor = CyberTextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // QR Scan button
                    OutlinedButton(
                        onClick = onQrScanClick,
                        enabled = !isRunning,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(52.dp).testTag("qr_scan_button"),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyan)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = stringResource(R.string.qr_scan),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Paste & Auto-Import button
                    Button(
                        onClick = {
                            try {
                                val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipMgr.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                                    if (text.isNotBlank()) {
                                        val testDecode = ConfigDecoder.decodeConfig(text)
                                        if (testDecode.isSuccess) {
                                            onImportConfig(text)
                                            configCodeInput = ""
                                            showReplaceInput = false
                                            importError = null
                                            Toast.makeText(context, context.getString(R.string.toast_config_imported), Toast.LENGTH_SHORT).show()
                                        } else {
                                            configCodeInput = text
                                            importError = testDecode.exceptionOrNull()?.message ?: context.getString(R.string.err_invalid_config)
                                            Toast.makeText(context, context.getString(R.string.toast_config_pasted), Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.toast_clipboard_empty), Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, context.getString(R.string.toast_clipboard_empty), Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Throwable) {
                                Toast.makeText(context, context.getString(R.string.toast_clipboard_error), Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !isRunning,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                        modifier = Modifier.height(52.dp).testTag("paste_config_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = stringResource(R.string.cd_paste),
                            tint = CyberDark,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (importError != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyberError.copy(alpha = 0.15f))
                            .border(0.5.dp, CyberError.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = importError!!,
                            fontSize = 11.sp,
                            color = CyberError,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (configCodeInput.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val res = ConfigDecoder.decodeConfig(configCodeInput)
                            if (res.isSuccess) {
                                onImportConfig(configCodeInput)
                                configCodeInput = ""
                                showReplaceInput = false
                                importError = null
                                Toast.makeText(context, context.getString(R.string.toast_config_applied), Toast.LENGTH_SHORT).show()
                            } else {
                                importError = res.exceptionOrNull()?.message ?: context.getString(R.string.err_invalid_config)
                                Toast.makeText(context, importError, Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberAccent)
                    ) {
                        Text(stringResource(R.string.btn_apply_config), fontWeight = FontWeight.Bold, color = CyberDark)
                    }
                }
            }
        }
    }
}
