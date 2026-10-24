package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.relay.data.ProxyMode
import com.example.ui.components.ConfigSection
import com.example.ui.components.LogConsole
import com.example.ui.components.ModeSwitch
import com.example.ui.components.QuickSetupCard
import com.example.ui.components.StatsCard
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberDark
import com.example.ui.theme.CyberAccent
import com.example.ui.theme.CyberOnAccent
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.ui.components.SettingsSection
import com.example.ui.theme.CyberSurface

@Composable
fun ProxyScreen(
    viewModel: ProxyViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val pingState by viewModel.pingState.collectAsState()

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Notification permission handled */ }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVpnPermissionGranted(context)
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.vpnPermissionRequest.collect { intent ->
            vpnPermissionLauncher.launch(intent)
        }
    }

    // Recreate activity only when the user explicitly changes language (not on first composition)
    val activity = context.findActivity()
    var appliedLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(config.language) {
        if (appliedLanguage != null && appliedLanguage != config.language) {
            activity?.recreate()
        }
        appliedLanguage = config.language
    }

    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    val homeScroll = rememberScrollState()
    val configScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = CyberDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    ((slideInHorizontally(animationSpec = tween(300)) { w -> dir * w / 10 } + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(300)) { w -> -dir * w / 10 } + fadeOut(tween(200)))) using SizeTransform(clip = false)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopStart,
                label = "main_tabs"
            ) { tab ->
                when (tab) {
                    MainTab.HOME -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(homeScroll)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MinimalHeader(mode = config.mode)

                        Spacer(modifier = Modifier.height(26.dp))

                        ConnectHero(
                            isRunning = isRunning,
                            mode = config.mode,
                            onClick = { viewModel.toggleProxy(context) }
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        // Switcher: Full Device VPN vs Proxy Only
                        ModeSwitch(
                            currentMode = config.mode,
                            isRunning = isRunning,
                            onModeSelected = { viewModel.updateMode(it) }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Live metrics card
                        StatsCard(
                            isRunning = isRunning,
                            stats = stats,
                            pingState = pingState,
                            onTestPing = { viewModel.testPing() }
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Quick Setup Card (Telegram 1-Tap & Copy addresses)
                        QuickSetupCard(
                            config = config,
                            isRunning = isRunning
                        )

                        Spacer(modifier = Modifier.height(28.dp))
                    }

                    MainTab.CONFIG -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(configScroll)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MinimalHeader(mode = config.mode)

                        Spacer(modifier = Modifier.height(18.dp))

                        // Configuration Form (Protected & Encrypted, Hidden keys)
                        ConfigSection(
                            config = config,
                            isRunning = isRunning,
                            onImportConfig = { viewModel.importConfigCode(it) },
                            onClearConfig = { viewModel.clearConfig() }
                        )

                        Spacer(modifier = Modifier.height(28.dp))
                    }

                    MainTab.LOGS -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MinimalHeader(mode = config.mode)

                        Spacer(modifier = Modifier.height(12.dp))

                        // Log window (full height)
                        LogConsole(
                            logs = logs,
                            onClearLogs = { viewModel.clearLogs() },
                            fillHeight = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    MainTab.SETTINGS -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(settingsScroll)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MinimalHeader(mode = config.mode)

                        Spacer(modifier = Modifier.height(18.dp))

                        // Ports, DNS, split tunneling, theme, language
                        SettingsSection(
                            config = config,
                            isRunning = isRunning,
                            onCleanIpChange = { viewModel.updateCleanIp(it) },
                            onSocksPortChange = { viewModel.updateSocksPort(it) },
                            onHttpPortChange = { viewModel.updateHttpPort(it) },
                            onDnsServersChange = { viewModel.updateDnsServers(it) },
                            onExcludedAppsChange = { viewModel.updateExcludedApps(it) },
                            onAutoStartChange = { viewModel.updateAutoStartOnBoot(it) },
                            onAllowLanChange = { viewModel.updateAllowLan(it) },
                            onThemeModeChange = { viewModel.updateThemeMode(it) },
                            onLanguageChange = { viewModel.updateLanguage(it) }
                        )

                        Spacer(modifier = Modifier.height(28.dp))
                    }
                }
            }

            BottomNav(
                selected = selectedTab,
                onSelect = { selectedTab = it }
            )
        }
    }
}

private enum class MainTab { HOME, CONFIG, LOGS, SETTINGS }

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.HOME -> Icons.Default.Home
    MainTab.CONFIG -> Icons.Default.Key
    MainTab.LOGS -> Icons.Default.Terminal
    MainTab.SETTINGS -> Icons.Default.Tune
}

private fun MainTab.labelRes(): Int = when (this) {
    MainTab.HOME -> R.string.tab_home
    MainTab.CONFIG -> R.string.tab_config
    MainTab.LOGS -> R.string.tab_logs
    MainTab.SETTINGS -> R.string.tab_settings
}

private val MainTab.testTag: String
    get() = when (this) {
        MainTab.HOME -> "tab_home_button"
        MainTab.CONFIG -> "tab_config_button"
        MainTab.LOGS -> "tab_logs_button"
        MainTab.SETTINGS -> "tab_settings_button"
    }

@Composable
private fun BottomNav(
    selected: MainTab,
    onSelect: (MainTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CyberDark)
            .padding(horizontal = 14.dp)
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(CyberSurface)
                .border(1.dp, CyberBorder.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
                .padding(horizontal = 5.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            MainTab.entries.forEach { tab ->
                val isSelected = tab == selected
                val itemBg by animateColorAsState(
                    targetValue = if (isSelected) CyberSurfaceVariant else Color.Transparent,
                    animationSpec = tween(220),
                    label = "navBg"
                )
                val itemBorder by animateColorAsState(
                    targetValue = if (isSelected) CyberBorder.copy(alpha = 0.9f) else Color.Transparent,
                    animationSpec = tween(220),
                    label = "navBorder"
                )
                val iconTint by animateColorAsState(
                    targetValue = if (isSelected) CyberTextPrimary else CyberTextMuted,
                    animationSpec = tween(220),
                    label = "navIcon"
                )
                val labelColor by animateColorAsState(
                    targetValue = if (isSelected) CyberTextPrimary else CyberTextMuted,
                    animationSpec = tween(220),
                    label = "navLabel"
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(17.dp))
                        .background(itemBg)
                        .border(1.dp, itemBorder, RoundedCornerShape(17.dp))
                        .clickable { onSelect(tab) }
                        .padding(vertical = 6.dp)
                        .testTag(tab.testTag),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = stringResource(tab.labelRes()),
                        fontSize = 9.5.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = labelColor
                    )
                }
            }
        }
    }
}

/**
 * Quiet, typographic top bar: inverted logo tile, app name and hardened badge.
 */
@Composable
private fun MinimalHeader(mode: ProxyMode) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color(0xFF232327)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield_wave),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                val titleWords = stringResource(R.string.app_title).uppercase().split(" ")
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = CyberTextPrimary, fontWeight = FontWeight.Bold)) {
                            append(titleWords.firstOrNull().orEmpty())
                        }
                        if (titleWords.size > 1) {
                            append("  ")
                            withStyle(SpanStyle(color = CyberTextMuted, fontWeight = FontWeight.Medium)) {
                                append(titleWords.drop(1).joinToString(" "))
                            }
                        }
                    },
                    fontSize = 15.sp,
                    letterSpacing = 2.6.sp
                )
                Text(
                    text = stringResource(if (mode == ProxyMode.VPN_MODE) R.string.app_mode_vpn else R.string.app_mode_proxy),
                    fontSize = 10.sp,
                    color = CyberTextMuted
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = CyberTextSecondary,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.badge_armored),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = CyberTextMuted,
                letterSpacing = 0.4.sp
            )
        }
    }
}

/**
 * Status hero card: a big breathing logo tile wrapped in sonar rings and a
 * slowly rotating dashed orbit while running, one graphite pill action button.
 */
@Composable
private fun ConnectHero(
    isRunning: Boolean,
    mode: ProxyMode,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current

    val transition = rememberInfiniteTransition(label = "hero")
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heroPulse"
    )
    val sonarA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "heroSonarA"
    )
    val sonarB by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(1300)
        ),
        label = "heroSonarB"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "heroSpin"
    )

    val signalColor by animateColorAsState(
        targetValue = if (isRunning) CyberCyan else CyberTextMuted,
        animationSpec = tween(500),
        label = "signalColor"
    )
    val statusColor by animateColorAsState(
        targetValue = if (isRunning) CyberCyan else CyberTextPrimary,
        animationSpec = tween(500),
        label = "statusColor"
    )
    val cardColor by animateColorAsState(
        targetValue = if (isRunning) CyberCyan.copy(alpha = 0.05f) else CyberSurfaceVariant.copy(alpha = 0.5f),
        animationSpec = tween(500),
        label = "cardColor"
    )
    val cardBorder by animateColorAsState(
        targetValue = if (isRunning) CyberCyan.copy(alpha = 0.30f) else CyberBorder.copy(alpha = 0.7f),
        animationSpec = tween(500),
        label = "cardBorder"
    )
    val tileIcon by animateColorAsState(
        targetValue = if (isRunning) CyberCyan else CyberTextSecondary,
        animationSpec = tween(500),
        label = "tileIcon"
    )
    val buttonColor by animateColorAsState(
        targetValue = if (isRunning) CyberCyan.copy(alpha = 0.10f) else CyberAccent,
        animationSpec = tween(380),
        label = "buttonColor"
    )
    val buttonBorder by animateColorAsState(
        targetValue = if (isRunning) CyberCyan.copy(alpha = 0.45f) else Color.Transparent,
        animationSpec = tween(380),
        label = "buttonBorder"
    )
    val buttonContent by animateColorAsState(
        targetValue = if (isRunning) CyberCyan else CyberOnAccent,
        animationSpec = tween(380),
        label = "buttonContent"
    )

    val tileBg = signalColor.copy(alpha = if (isRunning) 0.08f + 0.07f * breathe else 0.10f)
    val tileScale = 1f + (if (isRunning) 0.030f else 0.010f) * (breathe * 2f - 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(cardColor)
            .border(1.dp, cardBorder, RoundedCornerShape(28.dp))
            .padding(horizontal = 20.dp, vertical = 26.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier.size(136.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isRunning) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        listOf(sonarA, sonarB).forEach { progress ->
                            drawCircle(
                                color = signalColor.copy(alpha = (1f - progress) * 0.35f),
                                radius = size.minDimension / 2f * (0.30f + 0.70f * progress),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }
                    Canvas(
                        modifier = Modifier
                            .size(108.dp)
                            .rotate(spin)
                    ) {
                        drawCircle(
                            color = signalColor.copy(alpha = 0.5f),
                            radius = size.minDimension / 2f - 2.dp.toPx(),
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 16f), 0f)
                            )
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .graphicsLayer {
                            scaleX = tileScale
                            scaleY = tileScale
                        }
                        .clip(RoundedCornerShape(30.dp))
                        .background(tileBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield_wave),
                        contentDescription = null,
                        tint = tileIcon,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(if (isRunning) R.string.status_connected else R.string.status_offline),
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
                letterSpacing = 0.4.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = when {
                    isRunning -> stringResource(R.string.power_connected_hint)
                    mode == ProxyMode.VPN_MODE -> stringResource(R.string.power_connect_vpn)
                    else -> stringResource(R.string.power_connect_proxy)
                },
                fontSize = 12.sp,
                color = CyberTextMuted,
                letterSpacing = 0.2.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(buttonColor)
                    .border(1.dp, buttonBorder, RoundedCornerShape(28.dp))
                    .clickable(role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    }
                    .testTag("power_toggle_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = stringResource(if (isRunning) R.string.cd_disconnect else R.string.cd_connect),
                        tint = buttonContent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(if (isRunning) R.string.cd_disconnect else R.string.cd_connect),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = buttonContent,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
