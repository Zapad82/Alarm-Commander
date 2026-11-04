package com.example

import android.app.KeyguardManager
import android.content.Context
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alarm.AlarmService
import com.example.data.AlarmState
import com.example.data.MissionType
import com.example.mission.MissionInput
import com.example.ui.AlarmCommanderViewModel
import com.example.ui.AppLanguageMode
import com.example.ui.AppThemeMode
import com.example.ui.EnglishStrings
import com.example.ui.LocalAppStrings
import com.example.ui.RussianStrings
import com.example.ui.localizeRuntimeText
import com.example.ui.screens.ActiveAlarmScreen
import com.example.ui.screens.CommandCenterScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: AlarmCommanderViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateLockscreenWakeFlags(AlarmService.activeUiState.value.isActive)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            AlarmCommanderApp(
                viewModel = viewModel,
                onSetNfcReaderActive = { active ->
                    if (active) enableNfcReaderIfAvailable() else disableNfcReader()
                },
                onSetLockscreenWakeActive = { active ->
                    updateLockscreenWakeFlags(active)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateLockscreenWakeFlags(AlarmService.activeUiState.value.isActive)
        viewModel.refreshPermissions()
    }

    override fun onPause() {
        disableNfcReader()
        super.onPause()
    }

    private fun updateLockscreenWakeFlags(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(active)
            setTurnScreenOn(active)
            if (active) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                km?.requestDismissKeyguard(this, null)
            }
        } else {
            @Suppress("DEPRECATION")
            val legacyFlags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            if (active) {
                window.addFlags(legacyFlags)
            } else {
                window.clearFlags(legacyFlags)
            }
        }
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun enableNfcReaderIfAvailable() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return
        runCatching {
            adapter.enableReaderMode(
                this,
                { tag ->
                    val hexId = tag?.id?.joinToString("") { "%02X".format(it) }.orEmpty()
                    if (hexId.isNotEmpty()) {
                        runOnUiThread {
                            AlarmService.submitMissionInput(MissionInput.NfcTagScanned(hexId))
                        }
                    }
                },
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null
            )
        }
    }

    private fun disableNfcReader() {
        val adapter = nfcAdapter ?: return
        runCatching { adapter.disableReaderMode(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmCommanderApp(
    viewModel: AlarmCommanderViewModel,
    onSetNfcReaderActive: (Boolean) -> Unit = {},
    onSetLockscreenWakeActive: (Boolean) -> Unit = {}
) {
    val alarms by viewModel.alarmsFlow.collectAsStateWithLifecycle()
    val logs by viewModel.logsFlow.collectAsStateWithLifecycle()
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val permissions by viewModel.permissionsState.collectAsStateWithLifecycle()
    val auditReport by viewModel.auditReportState.collectAsStateWithLifecycle()
    val activeAlarmState by viewModel.activeAlarmUiState.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.statusBannerMessage.collectAsStateWithLifecycle()
    val githubReleaseState by viewModel.githubReleaseState.collectAsStateWithLifecycle()

    val configuration = LocalConfiguration.current
    val systemLocale: Locale = if (configuration.locales.isEmpty) {
        Locale.getDefault()
    } else {
        configuration.locales[0]
    }

    val languageMode = AppLanguageMode.fromStorage(settings.languageMode)
    val isEnglish = languageMode.resolveIsEnglish(systemLocale)
    val appStrings = if (isEnglish) EnglishStrings else RussianStrings

    val themeMode = AppThemeMode.fromStorage(settings.themeMode)
    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> systemDark
    }

    MyApplicationTheme(darkTheme = useDarkTheme) {
        CompositionLocalProvider(LocalAppStrings provides appStrings) {
            val s = LocalAppStrings.current
            val snackbarHostState = remember { SnackbarHostState() }
            var currentTab by remember { mutableIntStateOf(0) }

            // Включаем показ поверх экрана блокировки ТОЛЬКО когда реально звенит будильник или идет проверка Анти-возврата
            DisposableEffect(activeAlarmState.isActive) {
                onSetLockscreenWakeActive(activeAlarmState.isActive)
                onDispose {
                    onSetLockscreenWakeActive(false)
                }
            }

            // Энергоэффективное управление NFC: включаем антенну NFC только на время активной миссии NFC
            val shouldEnableNfc = activeAlarmState.isActive &&
                activeAlarmState.escalation?.state == AlarmState.MISSION_IN_PROGRESS &&
                activeAlarmState.currentMissionType == MissionType.NFC

            DisposableEffect(shouldEnableNfc) {
                onSetNfcReaderActive(shouldEnableNfc)
                onDispose {
                    onSetNfcReaderActive(false)
                }
            }

            LaunchedEffect(bannerMessage) {
                val msg = bannerMessage
                if (!msg.isNullOrBlank()) {
                    snackbarHostState.showSnackbar(msg.localizeRuntimeText(s.isEnglish))
                    viewModel.clearBannerMessage()
                }
            }

            if (activeAlarmState.isActive) {
                ActiveAlarmScreen(
                    uiState = activeAlarmState,
                    isCameraPermissionGranted = viewModel.permissionManager.isCameraGranted(),
                    isActivityPermissionGranted = viewModel.permissionManager.isActivityRecognitionGranted(),
                    isRecordAudioPermissionGranted = viewModel.permissionManager.isRecordAudioGranted(),
                    onPermissionRefresh = viewModel::refreshPermissions,
                    onStartMissionClick = viewModel::onStartMissionClicked,
                    onSubmitMathAnswer = viewModel::submitMathAnswer,
                    onSubmitSteps = viewModel::submitStepProgress,
                    onSubmitQr = viewModel::submitQrScanned,
                    onSubmitNfc = viewModel::submitNfcScanned,
                    onSubmitVoicePhrase = viewModel::submitVoicePhrase,
                    onTogglePenaltySnooze = viewModel::setPenaltySnoozeMode,
                    onSubmitPenaltySnooze = { viewModel.submitPenaltySnoozePhrase(it) },
                    onDismissSunrise = viewModel::dismissSunriseSession,
                    onConfirmAntiReturnAwake = viewModel::confirmAntiReturnAwake
                )
                return@CompositionLocalProvider
            }

            if (currentTab != 0) {
                BackHandler { currentTab = 0 }
            }

            val grantedCount = permissions.count { it.isGranted }
            val totalCount = permissions.size.coerceAtLeast(1)

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = s.appTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = s.headerSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        actions = {
                            // Быстрое переключение языка: SYSTEM -> RU -> EN
                            TextButton(
                                onClick = {
                                    val nextLang = when (languageMode) {
                                        AppLanguageMode.SYSTEM -> AppLanguageMode.EN
                                        AppLanguageMode.EN -> AppLanguageMode.RU
                                        AppLanguageMode.RU -> AppLanguageMode.SYSTEM
                                    }
                                    viewModel.setLanguageMode(nextLang)
                                },
                                modifier = Modifier.testTag("top_bar_language_toggle")
                            ) {
                                val badge = when (languageMode) {
                                    AppLanguageMode.SYSTEM -> if (isEnglish) "AUTO:EN" else "АВТО:RU"
                                    AppLanguageMode.RU -> "RU"
                                    AppLanguageMode.EN -> "EN"
                                }
                                Text(
                                    text = badge,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Быстрое переключение темы: Тёмная <-> Светлая
                            IconButton(
                                onClick = {
                                    val nextTheme = if (useDarkTheme) AppThemeMode.LIGHT else AppThemeMode.DARK
                                    viewModel.setThemeMode(nextTheme)
                                },
                                modifier = Modifier.testTag("top_bar_theme_toggle")
                            ) {
                                Icon(
                                    imageVector = if (useDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = s.themeSectionLabel,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(
                            selected = currentTab == 0,
                            onClick = { currentTab = 0 },
                            icon = { Icon(Icons.Default.Alarm, contentDescription = s.navCommandCenter) },
                            label = {
                                Text(
                                    text = s.navCommandCenter,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.testTag("nav_tab_command_center")
                        )
                        NavigationBarItem(
                            selected = currentTab == 1,
                            onClick = {
                                viewModel.refreshPermissions()
                                currentTab = 1
                            },
                            icon = { Icon(Icons.Default.Shield, contentDescription = s.navPermissions) },
                            label = {
                                Text(
                                    text = s.navPermissions,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.testTag("nav_tab_permissions")
                        )
                        NavigationBarItem(
                            selected = currentTab == 2,
                            onClick = { currentTab = 2 },
                            icon = { Icon(Icons.Default.History, contentDescription = s.navLogs) },
                            label = {
                                Text(
                                    text = s.navLogs,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.testTag("nav_tab_logs")
                        )
                        NavigationBarItem(
                            selected = currentTab == 3,
                            onClick = { currentTab = 3 },
                            icon = { Icon(Icons.Default.QrCode2, contentDescription = s.navSettings) },
                            label = {
                                Text(
                                    text = s.navSettings,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.testTag("nav_tab_settings")
                        )
                    }
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (currentTab) {
                        0 -> CommandCenterScreen(
                            alarms = alarms,
                            disclaimerAccepted = settings.disclaimerAccepted,
                            grantedPermissionsCount = grantedCount,
                            totalPermissionsCount = totalCount,
                            defaultQrPayload = settings.defaultQrPayload,
                            defaultNfcTagId = settings.defaultNfcTagId,
                            defaultSignalMode = settings.defaultSignalMode,
                            defaultSoundSource = settings.defaultSoundSource,
                            defaultRingtoneUri = settings.customRingtoneUri,
                            defaultRingtoneTitle = settings.customRingtoneTitle,
                            onAcceptDisclaimer = viewModel::acceptDisclaimer,
                            onSaveAlarm = viewModel::saveAlarm,
                            onToggleAlarm = viewModel::toggleAlarmEnabled,
                            onDeleteAlarm = viewModel::deleteAlarm,
                            onTriggerTestAlarm = viewModel::triggerCombatTestAlarm,
                            onTriggerAntiReturnTest = viewModel::triggerAntiReturnTest
                        )
                        1 -> PermissionsScreen(
                            permissions = permissions,
                            auditReport = auditReport,
                            permissionManager = viewModel.permissionManager,
                            onRefresh = viewModel::refreshPermissions
                        )
                        2 -> LogsScreen(
                            logs = logs,
                            onClearLogs = viewModel::clearEventLogs
                        )
                        3 -> SettingsScreen(
                            settings = settings,
                            githubReleaseState = githubReleaseState,
                            apkSignatureSha256 = viewModel.apkSignatureSha256,
                            onSetLanguageMode = viewModel::setLanguageMode,
                            onSetThemeMode = viewModel::setThemeMode,
                            onUpdateSignalSettings = viewModel::updateGlobalSignalSettings,
                            onCheckGithubUpdate = viewModel::checkGithubUpdates,
                            onSaveSettings = viewModel::saveBackupAndSensorSettings,
                            onTestWebhook = viewModel::testSmartHomeWebhook,
                            onTestSocialContact = viewModel::testSocialContactChannel,
                            onTestFutureStub = viewModel::testFutureStubChannel
                        )
                    }
                }
            }
        }
    }
}
