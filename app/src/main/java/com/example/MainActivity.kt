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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alarm.AlarmService
import com.example.mission.MissionInput
import com.example.ui.AlarmCommanderViewModel
import com.example.ui.screens.ActiveAlarmScreen
import com.example.ui.screens.CommandCenterScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TacticalAmber

class MainActivity : ComponentActivity() {

    private val viewModel: AlarmCommanderViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockscreenWakeFlags()
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            MyApplicationTheme {
                AlarmCommanderApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
        enableNfcReaderIfAvailable()
    }

    override fun onPause() {
        disableNfcReader()
        super.onPause()
    }

    /**
     * Позволяет отображать экран будильника поверх заблокированного экрана и включать дисплей
     * при вызове через Full-Screen Intent (без опасного разрешения SYSTEM_ALERT_WINDOW).
     */
    private fun configureLockscreenWakeFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
fun AlarmCommanderApp(viewModel: AlarmCommanderViewModel) {
    val alarms by viewModel.alarmsFlow.collectAsStateWithLifecycle()
    val logs by viewModel.logsFlow.collectAsStateWithLifecycle()
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val permissions by viewModel.permissionsState.collectAsStateWithLifecycle()
    val activeAlarmState by viewModel.activeAlarmUiState.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.statusBannerMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var currentTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBannerMessage()
        }
    }

    // Если активен звонок будильника, фаза Рассвета T−15 или проверка Анти-возврата —
    // показываем полноэкранный боевой интерфейс с заблокированной кнопкой Назад
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
        return
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
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.header_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = TacticalAmber
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SlateSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = SlateSurface) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Alarm, contentDescription = "Штаб") },
                    label = { Text(stringResource(R.string.nav_command_center)) },
                    modifier = Modifier.testTag("nav_tab_command_center")
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = {
                        viewModel.refreshPermissions()
                        currentTab = 1
                    },
                    icon = { Icon(Icons.Default.Shield, contentDescription = "Разрешения") },
                    label = { Text(stringResource(R.string.nav_permissions)) },
                    modifier = Modifier.testTag("nav_tab_permissions")
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.History, contentDescription = "Журнал") },
                    label = { Text(stringResource(R.string.nav_logs)) },
                    modifier = Modifier.testTag("nav_tab_logs")
                )
                NavigationBarItem(
                    selected = currentTab == 3,
                    onClick = { currentTab = 3 },
                    icon = { Icon(Icons.Default.QrCode2, contentDescription = "Резерв и QR") },
                    label = { Text(stringResource(R.string.nav_settings)) },
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
                    onAcceptDisclaimer = viewModel::acceptDisclaimer,
                    onSaveAlarm = viewModel::saveAlarm,
                    onToggleAlarm = viewModel::toggleAlarmEnabled,
                    onDeleteAlarm = viewModel::deleteAlarm,
                    onTriggerTestAlarm = viewModel::triggerCombatTestAlarm,
                    onTriggerAntiReturnTest = viewModel::triggerAntiReturnTest
                )
                1 -> PermissionsScreen(
                    permissions = permissions,
                    permissionManager = viewModel.permissionManager,
                    onRefresh = viewModel::refreshPermissions
                )
                2 -> LogsScreen(
                    logs = logs,
                    onClearLogs = viewModel::clearEventLogs
                )
                3 -> SettingsScreen(
                    settings = settings,
                    onSaveSettings = viewModel::saveBackupAndSensorSettings,
                    onTestWebhook = viewModel::testSmartHomeWebhook,
                    onTestSocialContact = viewModel::testSocialContactChannel,
                    onTestFutureStub = viewModel::testFutureStubChannel
                )
            }
        }
    }
}
