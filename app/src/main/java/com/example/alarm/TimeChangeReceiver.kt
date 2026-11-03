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
 * Перепланирует все включённые будильники при ручном изменении времени или смене часового пояса.
 */
class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
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
                    eventType = "TIME_CHANGE_RESCHEDULED",
                    state = AlarmState.SCHEDULED,
                    details = "Изменение системного времени/пояса: пересчитано ${enabledAlarms.size} будильников"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
