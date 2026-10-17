package xyz.nextalone.hyperzoomring.hook

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import xyz.nextalone.hyperzoomring.ring.ZoomRingConstants
import xyz.nextalone.hyperzoomring.util.Heartbeat

/**
 * Listens to the mt350x optical tracking sensor for direction data.
 * Initialization is deferred to a background thread to avoid blocking system_server startup.
 */
class ZoomRingSensorListener(private val context: Context) : SensorEventListener {

    private val tag = "HyperZoomRing"

    private companion object {
        const val PROBE_INTERVAL_MS = 2000L
    }

    /** Last known direction: 1 = CW, -1 = CCW, 0 = unknown. */
    @Volatile
    var lastDirection: Int = 0
        private set

    @Volatile
    var lastSensorTimestampMs: Long = 0
        private set

    @Volatile
    private var registered = false

    private val handlerThread = HandlerThread("ZoomRingSensor").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /**
     * Start sensor listening on a background thread.
     * Retries until SensorService is available (it starts after system_server init).
     */
    fun start() {
        handler.post { startWithRetry(retries = 20, delayMs = 3000L) }
        handler.postDelayed({ probeLoop() }, PROBE_INTERVAL_MS)
    }

    /**
     * 诊断探针节拍（2 秒一次，只在 hzr_probe 变化时做事）。
     * 放在这条已经存在的后台线程上，不占 system_server 主线程，也不额外开线程。
     */
    private fun probeLoop() {
        try {
            DebugProbe.tick(context)
            // 配置同步状态要重试着写：start() 那会儿 SettingsProvider 可能还没就绪
            ConfigSync.tick(context)
            // 心跳 2 秒落一次：内存里累加，不随样本刷 SettingsProvider
            Heartbeat.flush(context)
        } catch (e: Throwable) {
            Log.w(tag, "probe tick failed", e)
        }
        handler.postDelayed({ probeLoop() }, PROBE_INTERVAL_MS)
    }

    private fun startWithRetry(retries: Int, delayMs: Long) {
        if (registered) return
        if (retries <= 0) {
            Log.e(tag, "Gave up waiting for SensorService after all retries")
            return
        }

        try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (sensorManager == null) {
                Log.w(tag, "SensorManager not available yet, retrying in ${delayMs}ms ($retries left)")
                handler.postDelayed({ startWithRetry(retries - 1, delayMs) }, delayMs)
                return
            }

            val sensor = findOpticalTrackingSensor(sensorManager)
            if (sensor == null) {
                Log.w(tag, "mt350x sensor not found yet, retrying in ${delayMs}ms ($retries left)")
                handler.postDelayed({ startWithRetry(retries - 1, delayMs) }, delayMs)
                return
            }

            Log.i(tag, "Registering sensor listener: ${sensor.name} (type=${sensor.type})")
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            registered = true
            Log.i(tag, "Sensor listener registered successfully")
        } catch (e: Exception) {
            Log.w(tag, "Sensor registration failed, retrying in ${delayMs}ms ($retries left)", e)
            handler.postDelayed({ startWithRetry(retries - 1, delayMs) }, delayMs)
        }
    }

    private fun findOpticalTrackingSensor(sensorManager: SensorManager): Sensor? {
        val byType = sensorManager.getSensorList(ZoomRingConstants.SENSOR_TYPE_OPTICAL_TRACKING)
        if (byType.isNotEmpty()) return byType[0]

        return sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { sensor ->
            sensor.name.contains("mt350x", ignoreCase = true) ||
                sensor.name.contains("OPTICALTRACKING", ignoreCase = true)
        }
    }

    /** 由外部注入：每个传感器样本转交给传感器驱动器 */
    var onSample: ((FloatArray) -> Unit)? = null

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.isEmpty()) return
        // 单一事实来源：方向/幅度/速度全部交给 SensorRingDriver 判定
        try {
            onSample?.invoke(event.values)
        } catch (e: Throwable) {
            Log.w(tag, "sensor sample dispatch failed", e)
        }
        val deltaX = event.values[0]
        if (deltaX != 0f) {
            // 只数"真的动了"的样本：静止时传感器也会周期性上报，
            // 把它算进去会让界面的"已处理 N 次"变成没有意义的数字
            Heartbeat.onSample()
            lastDirection = if (deltaX > 0) 1 else -1
            lastSensorTimestampMs = System.currentTimeMillis()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
