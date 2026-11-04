package com.example.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.MainActivity
import com.example.data.AlarmEntity
import java.util.Calendar

/**
 * Планировщик точных будильников через AlarmManager.setAlarmClock,
 * предварительного «Рассвета» (T−15 минут) и таймера «Анти-возврата в кровать».
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val ACTION_TRIGGER_ALARM = "com.example.action.TRIGGER_ALARM"
        const val ACTION_PRE_ALARM_SUNRISE = "com.example.action.PRE_ALARM_SUNRISE"
        const val ACTION_ANTI_RETURN_CHECK = "com.example.action.ANTI_RETURN_CHECK"
        const val ACTION_DISMISS_ANTI_RETURN = "com.example.action.DISMISS_ANTI_RETURN"

        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_IS_SUNRISE = "extra_is_sunrise"
        const val EXTRA_IS_ANTI_RETURN = "extra_is_anti_return"
        const val EXTRA_IS_TEST_RUN = "extra_is_test_run"

        /**
         * Вычисляет ближайшее время срабатывания в миллисекундах (epoch ms).
         * Дни недели в AlarmEntity: 1 = Понедельник .. 7 = Воскресенье.
         */
        fun computeNextTriggerEpochMs(
            hour: Int,
            minute: Int,
            daysOfWeek: Set<Int>,
            nowEpochMs: Long = System.currentTimeMillis()
        ): Long {
            val nowCal = Calendar.getInstance().apply { timeInMillis = nowEpochMs }
            val candidate = Calendar.getInstance().apply {
                timeInMillis = nowEpochMs
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (daysOfWeek.isEmpty()) {
                if (candidate.timeInMillis <= nowCal.timeInMillis) {
                    candidate.add(Calendar.DAY_OF_YEAR, 1)
                }
                return candidate.timeInMillis
            }

            for (offsetDays in 0..7) {
                val checkCal = (candidate.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, offsetDays)
                }
                val isoDay = calendarDayToIsoDay(checkCal.get(Calendar.DAY_OF_WEEK))
                if (isoDay in daysOfWeek && checkCal.timeInMillis > nowCal.timeInMillis) {
                    return checkCal.timeInMillis
                }
            }

            candidate.add(Calendar.DAY_OF_YEAR, 1)
            return candidate.timeInMillis
        }

        private fun calendarDayToIsoDay(calDay: Int): Int = when (calDay) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
    }

    /**
     * Планирует основной звонок T0 через setAlarmClock и (опционально) T−15 «Рассвет».
     * Возвращает рассчитанный timestamp T0.
     */
    fun scheduleAlarm(alarm: AlarmEntity, customTriggerEpochMs: Long? = null): Long {
        cancelAlarm(alarm.id)
        if (!alarm.enabled && customTriggerEpochMs == null) return -1L

        val triggerMs = customTriggerEpochMs ?: computeNextTriggerEpochMs(
            hour = alarm.hour,
            minute = alarm.minute,
            daysOfWeek = alarm.daysOfWeek
        )

        val triggerIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }
        val triggerPending = PendingIntent.getBroadcast(
            context,
            requestCodeForT0(alarm.id),
            triggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }
        val showPending = PendingIntent.getActivity(
            context,
            requestCodeForShow(alarm.id),
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canExact) {
            val clockInfo = AlarmManager.AlarmClockInfo(triggerMs, showPending)
            alarmManager.setAlarmClock(clockInfo, triggerPending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, triggerPending)
        }

        // Планируем T−15 минут «Рассвет», если до звонка больше 15 минут
        if (alarm.sunrisePreAlarmEnabled) {
            val leadMs = alarm.sunriseLeadMinutes.coerceAtLeast(1) * 60_000L
            val sunriseTriggerMs = triggerMs - leadMs
            if (sunriseTriggerMs > System.currentTimeMillis() + 5_000L) {
                val sunriseIntent = Intent(context, AlarmReceiver::class.java).apply {
                    action = ACTION_PRE_ALARM_SUNRISE
                    putExtra(EXTRA_ALARM_ID, alarm.id)
                    putExtra(EXTRA_IS_SUNRISE, true)
                }
                val sunrisePending = PendingIntent.getBroadcast(
                    context,
                    requestCodeForSunrise(alarm.id),
                    sunriseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        sunriseTriggerMs,
                        sunrisePending
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        sunriseTriggerMs,
                        sunrisePending
                    )
                }
            }
        }

        return triggerMs
    }

    /**
     * Планирует контрольную проверку «Анти-возврата в кровать» через delayMinutes после отключения.
     */
    fun scheduleAntiReturnCheck(alarmId: Long, delayMinutes: Int): Long {
        val checkAtMs = System.currentTimeMillis() + (delayMinutes.coerceAtLeast(1) * 60_000L)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ANTI_RETURN_CHECK
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_IS_ANTI_RETURN, true)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCodeForAntiReturn(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, checkAtMs, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, checkAtMs, pending)
        }
        return checkAtMs
    }

    fun cancelAntiReturnCheck(alarmId: Long) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ANTI_RETURN_CHECK
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCodeForAntiReturn(alarmId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    fun cancelAlarm(alarmId: Long) {
        listOf(
            requestCodeForT0(alarmId) to ACTION_TRIGGER_ALARM,
            requestCodeForSunrise(alarmId) to ACTION_PRE_ALARM_SUNRISE,
            requestCodeForAntiReturn(alarmId) to ACTION_ANTI_RETURN_CHECK
        ).forEach { (reqCode, act) ->
            val intent = Intent(context, AlarmReceiver::class.java).apply { action = act }
            val pending = PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                alarmManager.cancel(pending)
                pending.cancel()
            }
        }
    }

    private fun requestCodeForT0(alarmId: Long): Int = (10000 + (alarmId % 5000)).toInt()
    private fun requestCodeForSunrise(alarmId: Long): Int = (20000 + (alarmId % 5000)).toInt()
    private fun requestCodeForAntiReturn(alarmId: Long): Int = (30000 + (alarmId % 5000)).toInt()
    private fun requestCodeForShow(alarmId: Long): Int = (40000 + (alarmId % 5000)).toInt()
}
