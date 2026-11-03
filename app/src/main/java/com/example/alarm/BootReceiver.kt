package com.example.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.AlarmRepository
import com.example.data.AlarmState
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Восстанавливает все включённые будильники в AlarmManager после перезагрузки устройства.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context.applicationContext)
                val repo = AlarmRepository(db.alarmDao())
                val scheduler = AlarmScheduler(context.applicationContext)
                val enabledAlarms = repo.getEnabledAlarmsOnce()
                enabledAlarms.forEach { alarm ->
                    scheduler.scheduleAlarm(alarm)
                }
                repo.logEvent(
                    alarmId = 0L,
                    alarmLabel = "Система",
                    eventType = "BOOT_RESCHEDULED",
                    state = AlarmState.SCHEDULED,
                    details = "Перезагрузка ОС: восстановлено ${enabledAlarms.size} активных будильников"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
