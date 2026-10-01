package com.federicopaglioni.blinko

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/** Gyroscope + accelerometer for the recorder header and the `still` flag in remote stats. */
class MotionMonitor(ctx: Context) : SensorEventListener {
    private val mgr = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @Volatile var gyro = FloatArray(3); private set
    @Volatile var accel = FloatArray(3); private set
    /** Rotation rate magnitude (rad/s), low-pass filtered. */
    @Volatile var level = 0.0; private set
    val isStill get() = level < 0.05

    fun start() {
        mgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        mgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() { mgr.unregisterListener(this) }

    fun latest(): FloatArray { val g = gyro; val a = accel; return floatArrayOf(g[0], g[1], g[2], a[0], a[1], a[2]) }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                gyro = e.values.copyOf(3)
                val m = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                level = 0.9 * level + 0.1 * abs(m)
            }
            Sensor.TYPE_ACCELEROMETER -> accel = e.values.copyOf(3)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
