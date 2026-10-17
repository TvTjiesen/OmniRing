package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import xyz.nextalone.hyperzoomring.action.Action
import xyz.nextalone.hyperzoomring.config.SceneType

object FlashlightBrightnessUpAction : Action {
    override val id = "flashlight_brightness_up"
    override val displayName = "手电筒亮度增大"
    override val sceneOnly = SceneType.FLASHLIGHT_ON

    override fun execute(context: Context, intensity: Float) {
        FlashlightHelper.adjust(context, intensity, up = true)
    }
}

object FlashlightBrightnessDownAction : Action {
    override val id = "flashlight_brightness_down"
    override val displayName = "手电筒亮度减小"
    override val sceneOnly = SceneType.FLASHLIGHT_ON

    override fun execute(context: Context, intensity: Float) {
        FlashlightHelper.adjust(context, intensity, up = false)
    }
}

internal object FlashlightHelper {
    private const val TAG = "HyperZoomRing"

    @Volatile
    private var currentLevel: Int = -1  // -1 = unknown

    @Volatile
    private var callbackRegistered: Boolean = false

    private var cachedCameraId: String? = null
    private var cachedMaxLevel: Int = 0

    private val handlerThread by lazy {
        HandlerThread("HyperZoomRing-Flashlight").apply { start() }
    }
    private val handler by lazy { Handler(handlerThread.looper) }

    fun adjust(context: Context, intensity: Float, up: Boolean) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            ensureCallback(cm)

            val cameraId = getCameraId(cm) ?: return
            val maxLevel = getMaxLevel(cm, cameraId)
            if (maxLevel <= 1) return

            val current = when {
                currentLevel in 1..maxLevel -> currentLevel
                else -> maxLevel / 2  // first use, start from middle
            }
            val step = (intensity * (maxLevel / 4f)).toInt().coerceAtLeast(1)
            val newLevel = if (up) {
                (current + step).coerceAtMost(maxLevel)
            } else {
                (current - step).coerceAtLeast(1)
            }

            cm.turnOnTorchWithStrengthLevel(cameraId, newLevel)
            currentLevel = newLevel
        } catch (e: Exception) {
            Log.w(TAG, "Failed to adjust flashlight brightness", e)
        }
    }

    private fun ensureCallback(cm: CameraManager) {
        if (callbackRegistered) return
        try {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (!enabled) currentLevel = -1
                }

                override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
                    currentLevel = newStrengthLevel
                }
            }, handler)  // use handler with looper
            callbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register torch callback", e)
            // Callback registration failed — adjust() still works,
            // just without external level sync. Not fatal.
        }
    }

    private fun getCameraId(cm: CameraManager): String? {
        cachedCameraId?.let { return it }
        val id = cm.cameraIdList.firstOrNull { id ->
            val chars = cm.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        cachedCameraId = id
        return id
    }

    private fun getMaxLevel(cm: CameraManager, cameraId: String): Int {
        if (cachedMaxLevel > 0) return cachedMaxLevel
        val chars = cm.getCameraCharacteristics(cameraId)
        val level = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 0
        cachedMaxLevel = level
        return level
    }
}
