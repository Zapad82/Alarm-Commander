package com.example.mission

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Трекер шагов для миссии «Шагомер».
 * Использует аппаратный Sensor.TYPE_STEP_DETECTOR / Sensor.TYPE_STEP_COUNTER,
 * а если на устройстве нет выделенного шагомера — автоматически включает резервный детектор
 * шагов по пикам ускорения акселерометра (Sensor.TYPE_ACCELEROMETER).
 */
class StepSensorTracker(
    context: Context,
    private val onStepChanged: (stepsCount: Int, sensorSourceLabel: String) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepDetectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val stepCounterSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val accelerometerSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var initialCounterBaseline: Int? = null
    private var accumulatedSteps: Int = 0
    private var lastAccelPeakMs: Long = 0L
    private var activeSourceLabel: String = "Ожидание датчика"

    fun startTracking() {
        accumulatedSteps = 0
        initialCounterBaseline = null
        when {
            stepDetectorSensor != null -> {
                activeSourceLabel = "Аппаратный STEP_DETECTOR"
                sensorManager.registerListener(this, stepDetectorSensor, SensorManager.SENSOR_DELAY_UI)
            }
            stepCounterSensor != null -> {
                activeSourceLabel = "Аппаратный STEP_COUNTER"
                sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_UI)
            }
            accelerometerSensor != null -> {
                activeSourceLabel = "Акселерометр (импульсный шагомер)"
                sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME)
            }
            else -> {
                activeSourceLabel = "Датчик шагов не обнаружен"
            }
        }
        onStepChanged(accumulatedSteps, activeSourceLabel)
    }

    fun stopTracking() {
        runCatching { sensorManager.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val ev = event ?: return
        when (ev.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                accumulatedSteps += ev.values.size.coerceAtLeast(1)
                onStepChanged(accumulatedSteps, activeSourceLabel)
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSinceBoot = ev.values.firstOrNull()?.toInt() ?: return
                val baseline = initialCounterBaseline ?: totalSinceBoot.also {
                    initialCounterBaseline = it
                }
                accumulatedSteps = (totalSinceBoot - baseline).coerceAtLeast(0)
                onStepChanged(accumulatedSteps, activeSourceLabel)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = ev.values[0]
                val y = ev.values[1]
                val z = ev.values[2]
                val magnitude = sqrt((x * x + y * y + z * z).toDouble())
                val now = System.currentTimeMillis()
                // Порог шага ~12.8 м/с² с анти-дребезгом 330 мс
                if (magnitude > 12.8 && now - lastAccelPeakMs > 330L) {
                    lastAccelPeakMs = now
                    accumulatedSteps++
                    onStepChanged(accumulatedSteps, activeSourceLabel)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
