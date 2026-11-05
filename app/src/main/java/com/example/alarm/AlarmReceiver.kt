package com.example.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.MainActivity

/**
 * Приёмник сигналов от AlarmManager:
 * - T0 (ACTION_TRIGGER_ALARM)
 * - Сторожевой перезапуск (ACTION_WATCHDOG_REVIVE)
 * - Восстановление уведомления в шторке (ACTION_RESTORE_ALARM_NOTIFICATION)
 * - Предварительный рассвет T−15 (ACTION_PRE_ALARM_SUNRISE)
 * - Контроль анти-возврата в кровать (ACTION_ANTI_RETURN_CHECK)
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        val isTestRun = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_TEST_RUN, false)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val cpuWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlarmCommander::ReceiverTransitionWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(15_000L)
        }

        // Принудительно включаем физический экран при срабатывании будильника на заблокированном телефоне
        @Suppress("DEPRECATION")
        val screenWakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "AlarmCommander::ReceiverScreenWakeLock"
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire(15_000L) }
        }

        try {
            val serviceAction = when (action) {
                AlarmScheduler.ACTION_PRE_ALARM_SUNRISE -> AlarmService.ACTION_START_SUNRISE
                AlarmScheduler.ACTION_ANTI_RETURN_CHECK -> AlarmService.ACTION_START_ANTI_RETURN
                AlarmScheduler.ACTION_DISMISS_ANTI_RETURN -> AlarmService.ACTION_CONFIRM_ANTI_RETURN
                AlarmScheduler.ACTION_WATCHDOG_REVIVE -> AlarmService.ACTION_WATCHDOG_REVIVE
                AlarmScheduler.ACTION_RESTORE_ALARM_NOTIFICATION -> AlarmService.ACTION_RESTORE_NOTIFICATION
                else -> AlarmService.ACTION_START_ALARM
            }

            val isDismissOrRestoreOnly = action == AlarmScheduler.ACTION_DISMISS_ANTI_RETURN ||
                action == AlarmScheduler.ACTION_RESTORE_ALARM_NOTIFICATION

            if (!isDismissOrRestoreOnly) {
                // Синхронно помечаем активную тревогу до создания окна MainActivity,
                // чтобы окно мгновенно открылось поверх блокировки без запроса пароля/разблокировки
                AlarmService.markAlarmLaunchingFromReceiver(
                    alarmId = alarmId,
                    isSunrise = action == AlarmScheduler.ACTION_PRE_ALARM_SUNRISE,
                    isAntiReturn = action == AlarmScheduler.ACTION_ANTI_RETURN_CHECK
                )
            }

            val serviceIntent = Intent(context, AlarmService::class.java).apply {
                this.action = serviceAction
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmScheduler.EXTRA_IS_TEST_RUN, isTestRun)
            }
            ContextCompat.startForegroundService(context, serviceIntent)

            if (!isDismissOrRestoreOnly) {
                // Запускаем окно будильника напрямую из BroadcastReceiver (у которого есть системное разрешение BAL от setAlarmClock)
                runCatching {
                    val activityIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                        putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                        putExtra(AlarmScheduler.EXTRA_LAUNCH_FROM_ALARM, true)
                    }
                    context.startActivity(activityIntent)
                }
            }
        } finally {
            if (cpuWakeLock.isHeld) {
                runCatching { cpuWakeLock.release() }
            }
            if (screenWakeLock.isHeld) {
                runCatching { screenWakeLock.release() }
            }
        }
    }
}
