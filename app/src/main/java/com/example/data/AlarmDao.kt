package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {

    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC")
    fun observeAllAlarms(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC")
    suspend fun getAllAlarmsOnce(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getEnabledAlarmsOnce(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id LIMIT 1")
    suspend fun getAlarmById(id: Long): AlarmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: AlarmEntity): Long

    @Update
    suspend fun updateAlarm(alarm: AlarmEntity)

    @Delete
    suspend fun deleteAlarm(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteAlarmById(id: Long)

    @Query("SELECT * FROM alarm_logs ORDER BY timestamp DESC LIMIT 250")
    fun observeRecentLogs(): Flow<List<AlarmLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AlarmLogEntity): Long

    @Query("DELETE FROM alarm_logs")
    suspend fun clearAllLogs()
}
