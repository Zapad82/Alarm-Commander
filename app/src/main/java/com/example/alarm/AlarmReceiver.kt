package com.example.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Приёмник сигналов от AlarmManager:
 * - T0 (ACTION_TRIGGER_ALARM)
 * - Предварительный рассвет T−15 (ACTION_PRE_ALARM_SUNRISE)
 * - Контроль анти-возврата в кровать (ACTION_ANTI_RETURN_CHECK)
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        val isTestRun = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_TEST_RUN, false)

        // Кратковременный WakeLock на время передачи управления в AlarmService
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val tempWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlarmCommander::ReceiverTransitionWakeLock"
        )
        tempWakeLock.acquire(10_000L)

        try {
            val serviceAction = when (action) {
                AlarmScheduler.ACTION_PRE_ALARM_SUNRISE -> AlarmService.ACTION_START_SUNRISE
                AlarmScheduler.ACTION_ANTI_RETURN_CHECK -> AlarmService.ACTION_START_ANTI_RETURN
                AlarmScheduler.ACTION_DISMISS_ANTI_RETURN -> AlarmService.ACTION_CONFIRM_ANTI_RETURN
                AlarmScheduler.ACTION_WATCHDOG_REVIVE -> AlarmService.ACTION_WATCHDOG_REVIVE
                else -> AlarmService.ACTION_START_ALARM
            }

            val serviceIntent = Intent(context, AlarmService::class.java).apply {
                this.action = serviceAction
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmScheduler.EXTRA_IS_TEST_RUN, isTestRun)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } finally {
            if (tempWakeLock.isHeld) {
                tempWakeLock.release()
            }
        }
    }
}
