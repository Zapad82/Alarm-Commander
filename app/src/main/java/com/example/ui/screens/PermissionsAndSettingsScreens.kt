package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.AlarmLogEntity
import com.example.mission.QrCodeUtils
import com.example.permissions.PermissionItemStatus
import com.example.permissions.PermissionManager
import com.example.settings.CommanderSettingsState
import com.example.settings.GitHubReleaseCheckResult
import com.example.ui.AppLanguageMode
import com.example.ui.AppThemeMode
import com.example.ui.LocalAppStrings
import com.example.ui.theme.RadarEmerald
import com.example.ui.theme.TacticalAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран пошаговой проверки и объяснения системных разрешений Android 8–16.
 */
@Composable
fun PermissionsScreen(
    permissions: List<PermissionItemStatus>,
    auditReport: com.example.permissions.SafetyAndPowerReport,
    permissionManager: PermissionManager,
    onRefresh: () -> Unit
) {
    val s = LocalAppStrings.current
    val context = LocalContext.current

    val runtimeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefresh()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("permissions_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                        RoundedCornerShape(14.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.securityPowerAuditTitle,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = s.batteryEcoStatusFormat(
                                    auditReport.batteryPercent,
                                    auditReport.isCharging,
                                    auditReport.isEcoModeRecommended
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onRefresh,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    auditReport.checks.forEach { check ->
                        val checkColor = if (check.passed) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = if (check.passed) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = check.title(s.isEnglish),
                                tint = checkColor,
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "[${check.category}] ${check.title(s.isEnglish)}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = checkColor
                                )
                                Text(
                                    text = check.details(s.isEnglish),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = s.permissionsChecklistTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = s.permissionsChecklistSub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(permissions, key = { it.id }) { item ->
            val statusColor = if (item.isGranted) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.primary
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
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
                                imageVector = if (item.isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = item.title(s.isEnglish),
                                tint = statusColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.title(s.isEnglish),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                item.isGranted -> s.permActive
                                item.isOptional -> s.permOptional
                                else -> s.permRequired
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.whyNeeded(s.isEnglish),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!item.isGranted) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                when (item.id) {
                                    "EXACT_ALARM" -> runCatching {
                                        context.startActivity(permissionManager.buildExactAlarmSettingsIntent())
                                    }
                                    "FULL_SCREEN_INTENT" -> runCatching {
                                        context.startActivity(permissionManager.buildFullScreenIntentSettings())
                                    }
                                    "POST_NOTIFICATIONS" -> {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            runtimeLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    "BATTERY_OPTIMIZATION" -> runCatching {
                                        context.startActivity(permissionManager.buildBatteryOptimizationSettingsIntent())
                                    }
                                    "CAMERA" -> {
                                        runtimeLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                    "ACTIVITY_RECOGNITION" -> {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            runtimeLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                        }
                                    }
                                    "RECORD_AUDIO" -> {
                                        runtimeLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                    "DND_BYPASS" -> runCatching {
                                        context.startActivity(permissionManager.buildDndPolicySettingsIntent())
                                    }
                                    "NFC_ADAPTER" -> runCatching {
                                        context.startActivity(permissionManager.buildNfcSettingsIntent())
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = item.actionLabel(s.isEnglish),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Экран локального журнала событий будильника (Room) с возможностью экспорта отчёта.
 */
@Composable
fun LogsScreen(
    logs: List<AlarmLogEntity>,
    onClearLogs: () -> Unit
) {
    val s = LocalAppStrings.current
    val context = LocalContext.current
    val formatter = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("logs_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = s.logsHeaderTitle(logs.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = s.logsHeaderSub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val report = buildString {
                                    appendLine("=== ALARM COMMANDER LOG REPORT ===")
                                    logs.take(50).forEach { e ->
                                        appendLine("${formatter.format(Date(e.timestamp))} | ${e.eventType} [${e.stateName}] | ${e.details}")
                                    }
                                }
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Alarm Commander — Event Log")
                                    putExtra(Intent.EXTRA_TEXT, report)
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(sendIntent, s.exportLogsBtn))
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = s.exportLogsBtn)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(s.exportLogsBtn, textAlign = TextAlign.Center)
                        }
                        OutlinedButton(
                            onClick = onClearLogs,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("clear_logs_button")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = s.clearLogsBtn)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(s.clearLogsBtn, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = s.emptyLogsLabel,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(s.emptyLogsLabel, textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            items(logs, key = { it.id }) { entry ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${entry.eventType} • [${entry.stateName}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatter.format(Date(entry.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = entry.details,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * Экран настроек языка, темы, обновлений через GitHub Releases, резервных каналов и QR-кода.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: CommanderSettingsState,
    githubReleaseState: GitHubReleaseCheckResult,
    apkSignatureSha256: String,
    onSetLanguageMode: (AppLanguageMode) -> Unit,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onCheckGithubUpdate: (String) -> Unit,
    onSaveSettings: (
        sunriseUrl: String,
        escalationUrl: String,
        contactName: String,
        contactPhone: String,
        qrPayload: String,
        nfcTagId: String
    ) -> Unit,
    onTestWebhook: (url: String, eventType: String) -> Unit,
    onTestSocialContact: (name: String, phone: String) -> Unit,
    onTestFutureStub: (String) -> Unit
) {
    val s = LocalAppStrings.current
    val context = LocalContext.current
    var sunriseUrl by remember(settings.sunriseWebhookUrl) { mutableStateOf(settings.sunriseWebhookUrl) }
    var escalationUrl by remember(settings.escalationWebhookUrl) { mutableStateOf(settings.escalationWebhookUrl) }
    var contactName by remember(settings.backupContactName) { mutableStateOf(settings.backupContactName) }
    var contactPhone by remember(settings.backupContactPhone) { mutableStateOf(settings.backupContactPhone) }
    var qrPayload by remember(settings.defaultQrPayload) { mutableStateOf(settings.defaultQrPayload) }
    var nfcTagId by remember(settings.defaultNfcTagId) { mutableStateOf(settings.defaultNfcTagId) }
    var githubRepoSlug by remember(settings.githubRepoSlug) { mutableStateOf(settings.githubRepoSlug) }

    val currentLangMode = AppLanguageMode.fromStorage(settings.languageMode)
    val currentThemeMode = AppThemeMode.fromStorage(settings.themeMode)

    val qrBitmap = remember(qrPayload) {
        QrCodeUtils.generateQrBitmap(qrPayload.ifBlank { "ALARM-COMMANDER-BATHROOM-01" }, 420)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Внешний вид и локализация (Тема: Светлая/Тёмная/Авто + Язык: Авто/RU/EN)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        RoundedCornerShape(16.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = s.appearanceAndLangTitle,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = s.appearanceAndLangTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = s.themeSectionLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = currentThemeMode == AppThemeMode.DARK,
                            onClick = { onSetThemeMode(AppThemeMode.DARK) },
                            label = { Text(s.themeDark) },
                            modifier = Modifier.testTag("theme_dark_chip")
                        )
                        FilterChip(
                            selected = currentThemeMode == AppThemeMode.LIGHT,
                            onClick = { onSetThemeMode(AppThemeMode.LIGHT) },
                            label = { Text(s.themeLight) },
                            modifier = Modifier.testTag("theme_light_chip")
                        )
                        FilterChip(
                            selected = currentThemeMode == AppThemeMode.SYSTEM,
                            onClick = { onSetThemeMode(AppThemeMode.SYSTEM) },
                            label = { Text(s.themeSystem) },
                            modifier = Modifier.testTag("theme_system_chip")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = s.languageSectionLabel,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = s.languageSectionLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = currentLangMode == AppLanguageMode.SYSTEM,
                            onClick = { onSetLanguageMode(AppLanguageMode.SYSTEM) },
                            label = { Text(s.langSystem) },
                            modifier = Modifier.testTag("lang_system_chip")
                        )
                        FilterChip(
                            selected = currentLangMode == AppLanguageMode.RU,
                            onClick = { onSetLanguageMode(AppLanguageMode.RU) },
                            label = { Text(s.langRussian) },
                            modifier = Modifier.testTag("lang_ru_chip")
                        )
                        FilterChip(
                            selected = currentLangMode == AppLanguageMode.EN,
                            onClick = { onSetLanguageMode(AppLanguageMode.EN) },
                            label = { Text(s.langEnglish) },
                            modifier = Modifier.testTag("lang_en_chip")
                        )
                    }
                }
            }
        }

        // 2. Обновление через GitHub Releases и отпечаток собственного сертификата подписи APK
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f),
                        RoundedCornerShape(16.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.VerifiedUser,
                            contentDescription = s.githubUpdateCardTitle,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = s.githubUpdateCardTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = s.githubUpdateSub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = s.currentVersionFormat(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = s.signingCertLabel(apkSignatureSha256),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = githubRepoSlug,
                        onValueChange = { githubRepoSlug = it },
                        label = { Text(s.githubRepoFieldLabel) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("github_repo_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { onCheckGithubUpdate(githubRepoSlug) },
                        enabled = !githubReleaseState.isChecking,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("check_github_update_button")
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = s.checkGithubUpdateBtn)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = s.checkGithubUpdateBtn,
                            textAlign = TextAlign.Center
                        )
                    }

                    val statusText = githubReleaseState.statusMessage(s.isEnglish)
                    if (statusText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (githubReleaseState.isUpdateAvailable) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val targetUrl = githubReleaseState.apkDownloadUrl.ifBlank {
                                githubReleaseState.htmlReleaseUrl.ifBlank {
                                    "https://github.com/${githubRepoSlug.trim()}/releases"
                                }
                            }
                            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(viewIntent) }
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("open_github_releases_button")
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = s.openGithubReleaseBtn)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = s.openGithubReleaseBtn,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // 3. Контрольный QR-код для печати/размещения в ванной комнате
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = s.qrGeneratorCardTitle,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = s.qrGeneratorCardTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = s.qrGeneratorSub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(170.dp)
                                .padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = qrPayload,
                        onValueChange = { qrPayload = it },
                        label = { Text(s.qrPayloadFieldLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Alarm Commander — QR Payload: $qrPayload"
                                )
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(shareIntent, s.shareQrPayloadBtn))
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = s.shareQrPayloadBtn)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(s.shareQrPayloadBtn, textAlign = TextAlign.Center)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nfcTagId,
                        onValueChange = { nfcTagId = it },
                        label = { Text(s.nfcFieldLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 4. Резервные каналы MVP: Webhook умного дома и Социальный контакт
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = s.backupChannelsCardTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = sunriseUrl,
                        onValueChange = { sunriseUrl = it },
                        label = { Text(s.sunriseWebhookFieldLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { onTestWebhook(sunriseUrl, "SUNRISE_T_MINUS_15") },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = s.testSunriseWebhookBtn)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(s.testSunriseWebhookBtn, textAlign = TextAlign.Center)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = escalationUrl,
                        onValueChange = { escalationUrl = it },
                        label = { Text(s.overdueWebhookFieldLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { onTestWebhook(escalationUrl, "OVERDUE_ESCALATION") },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = s.testOverdueWebhookBtn)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(s.testOverdueWebhookBtn, textAlign = TextAlign.Center)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text(s.contactNameFieldLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = contactPhone,
                        onValueChange = { contactPhone = it },
                        label = { Text(s.contactPhoneFieldLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onTestSocialContact(contactName, contactPhone) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text(s.testContactBtn, textAlign = TextAlign.Center)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            onSaveSettings(
                                sunriseUrl,
                                escalationUrl,
                                contactName,
                                contactPhone,
                                qrPayload,
                                nfcTagId
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RadarEmerald,
                            contentColor = Color.Black
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .testTag("save_settings_button")
                    ) {
                        Text(
                            text = s.saveBackupSettingsBtn,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // 5. Интерфейсы-заглушки (Stubs) для Post-MVP каналов
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = s.stubsCardTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = s.stubsCardSub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val stubs = if (s.isEnglish) {
                        listOf(
                            "FRIEND_CALL" to "Auto-Call Friend (VoIP/SIP Gateway)",
                            "SMART_SPEAKER" to "Living Room Smart Speaker (Cast/Matter)",
                            "BED_SHAKER" to "Under-Mattress Bed Shaker (BLE GATT)",
                            "SECOND_DEVICE" to "Second Device over Wi-Fi Direct"
                        )
                    } else {
                        listOf(
                            "FRIEND_CALL" to "Авто-звонок другу (VoIP/SIP шлюз)",
                            "SMART_SPEAKER" to "Умная колонка в гостиной (Cast/Matter)",
                            "BED_SHAKER" to "Вибро-шайба под матрас (BLE GATT)",
                            "SECOND_DEVICE" to "Второе устройство по Wi-Fi Direct"
                        )
                    }
                    stubs.forEach { (key, title) ->
                        OutlinedButton(
                            onClick = { onTestFutureStub(key) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = "Stub: $title",
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
