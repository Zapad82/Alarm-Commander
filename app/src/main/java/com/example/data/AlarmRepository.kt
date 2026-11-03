package com.example.data

import kotlinx.coroutines.flow.Flow

class AlarmRepository(private val dao: AlarmDao) {

    val alarmsFlow: Flow<List<AlarmEntity>> = dao.observeAllAlarms()
    val logsFlow: Flow<List<AlarmLogEntity>> = dao.observeRecentLogs()

    suspend fun getAllAlarmsOnce(): List<AlarmEntity> = dao.getAllAlarmsOnce()

    suspend fun getEnabledAlarmsOnce(): List<AlarmEntity> = dao.getEnabledAlarmsOnce()

    suspend fun getAlarmById(id: Long): AlarmEntity? = dao.getAlarmById(id)

    suspend fun saveAlarm(alarm: AlarmEntity): Long {
        return if (alarm.id == 0L) {
            val newId = dao.insertAlarm(alarm)
            logEvent(
                alarmId = newId,
                alarmLabel = alarm.label,
                eventType = "ALARM_CREATED",
                state = AlarmState.SCHEDULED,
                details = "Создан рубеж ${alarm.formattedTime()} (${alarm.profileEnum.name}, миссии: ${alarm.missionChainCsv})"
            )
            newId
        } else {
            dao.updateAlarm(alarm)
            logEvent(
                alarmId = alarm.id,
                alarmLabel = alarm.label,
                eventType = "ALARM_UPDATED",
                state = if (alarm.enabled) AlarmState.SCHEDULED else AlarmState.DISMISSED,
                details = "Обновлён рубеж ${alarm.formattedTime()} (вкл=${alarm.enabled})"
            )
            alarm.id
        }
    }

    suspend fun deleteAlarm(alarm: AlarmEntity) {
        dao.deleteAlarm(alarm)
        logEvent(
            alarmId = alarm.id,
            alarmLabel = alarm.label,
            eventType = "ALARM_DELETED",
            state = AlarmState.DISMISSED,
            details = "Удалён будильник ${alarm.formattedTime()}"
        )
    }

    suspend fun logEvent(
        alarmId: Long,
        alarmLabel: String,
        eventType: String,
        state: AlarmState,
        details: String
    ) {
        dao.insertLog(
            AlarmLogEntity(
                alarmId = alarmId,
                alarmLabel = alarmLabel,
                timestamp = System.currentTimeMillis(),
                eventType = eventType,
                stateName = state.name,
                details = details
            )
        )
    }

    suspend fun clearLogs() {
        dao.clearAllLogs()
    }

    /**
     * Создаёт демо-будильники при первом запуске, чтобы пользователь сразу видел готовые сценарии.
     */
    suspend fun ensureSeededDefaults(): List<AlarmEntity> {
        val current = dao.getAllAlarmsOnce()
        if (current.isNotEmpty()) return current

        val starter1 = AlarmEntity(
            hour = 7,
            minute = 0,
            label = "Подъём • Рабочий рубеж",
            daysOfWeekCsv = "1,2,3,4,5",
            enabled = true,
            escalationProfile = EscalationProfile.HARD.name,
            missionChainCsv = "MATH,STEPS",
            mathDifficulty = MathDifficulty.MEDIUM.name,
            targetSteps = 50,
            allowSnooze = false,
            sunrisePreAlarmEnabled = true,
            antiReturnEnabled = true,
            backupWebhookEnabled = true,
            backupContactEnabled = true
        )
        val starter2 = AlarmEntity(
            hour = 8,
            minute = 30,
            label = "Субботний марш • Ванная QR",
            daysOfWeekCsv = "6,7",
            enabled = false,
            escalationProfile = EscalationProfile.EXTREME_SAFE.name,
            missionChainCsv = "QR,MATH",
            mathDifficulty = MathDifficulty.HARD.name,
            targetSteps = 75,
            qrExpectedPayload = "ALARM-COMMANDER-BATHROOM-01",
            allowSnooze = false,
            sunrisePreAlarmEnabled = true,
            antiReturnEnabled = true,
            backupWebhookEnabled = true,
            backupContactEnabled = false
        )

        val id1 = dao.insertAlarm(starter1)
        val id2 = dao.insertAlarm(starter2)
        logEvent(
            alarmId = id1,
            alarmLabel = starter1.label,
            eventType = "SYSTEM_INIT",
            state = AlarmState.SCHEDULED,
            details = "Инициализированы базовые тактические профили будильников"
        )
        return listOf(starter1.copy(id = id1), starter2.copy(id = id2))
    }
}
