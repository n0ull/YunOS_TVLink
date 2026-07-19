package app.tvlink.ui.widgets

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

actual class MotionSensor actual constructor() {
    private var sm: SensorManager? = null
    private var listener: SensorEventListener? = null

    actual fun start(onAccel: (x: Int, y: Int, z: Int) -> Unit, onGyro: (x: Int, y: Int, z: Int) -> Unit) {
        val ctx = AndroidPlatform.appContext
        val mgr = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = mgr
        val li = object : SensorEventListener {
            private var lastAccel = 0L
            private var lastGyro = 0L

            override fun onSensorChanged(e: SensorEvent) {
                val now = System.currentTimeMillis()
                when (e.sensor.type) {
                    // throttle to ~20Hz per type, scale per protocol (docs/re/02 §4.4)
                    Sensor.TYPE_ACCELEROMETER -> if (now - lastAccel >= 50) {
                        lastAccel = now
                        onAccel((e.values[0] * 2000).toInt(), (e.values[1] * -2000).toInt(), (e.values[2] * 1000).toInt())
                    }
                    Sensor.TYPE_GYROSCOPE -> if (now - lastGyro >= 50) {
                        lastGyro = now
                        onGyro((e.values[0] * 286.47888f).toInt(), (e.values[1] * 286.47888f).toInt(), (e.values[2] * 286.47888f).toInt())
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = li
        mgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            mgr.registerListener(li, it, SensorManager.SENSOR_DELAY_GAME)
        }
        mgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            mgr.registerListener(li, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    actual fun stop() {
        listener?.let { sm?.unregisterListener(it) }
        listener = null
    }
}
