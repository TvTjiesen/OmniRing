package xyz.nextalone.hyperzoomring.hook

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import xyz.nextalone.hyperzoomring.config.SceneType

object SceneDetector {
    private const val TAG = "HyperZoomRing"
    private const val LANDSCAPE_TTL_MS = 800L

    @Volatile
    var torchState: Boolean = false
        private set

    @Volatile
    var fullscreenState: Boolean = false

    @Volatile private var landscape = false
    @Volatile private var landscapeAt = 0L

    fun init(context: Context) {
        // Delay torch callback registration to a background thread —
        // CameraService may not be ready during early system_server boot,
        // and getSystemService(CAMERA_SERVICE) blocks until it is.
        val thread = HandlerThread("HyperZoomRing-SceneDetector").apply { start() }
        Handler(thread.looper).postDelayed({
            initTorchCallback(context)
        }, 10_000) // 10s delay to ensure CameraService is up
    }

    fun detectActiveScene(context: Context): SceneType? {
        if (isMediaPlaying(context)) return SceneType.MEDIA_PLAYING
        if (torchState) return SceneType.FLASHLIGHT_ON
        if (fullscreenState) return SceneType.FULLSCREEN
        return null
    }

    /**
     * 当前是不是横屏。进度环靠这个区分"横屏看视频"和"竖屏刷列表 + 后台放歌"，
     * 带 0.8 秒缓存：传感器采样是 50Hz 级别，不能每次都去查 Display。
     */
    fun isLandscape(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - landscapeAt < LANDSCAPE_TTL_MS) return landscape
        landscapeAt = now
        landscape = try {
            val dm = context.getSystemService(DisplayManager::class.java)
            val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            d?.getRealMetrics(m)
            m.widthPixels > m.heightPixels
        } catch (e: Throwable) {
            Log.d(TAG, "landscape check failed", e)
            landscape
        }
        return landscape
    }

    private fun isMediaPlaying(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.isMusicActive
    }

    private fun initTorchCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    torchState = enabled
                }
            }, null)
            Log.i(TAG, "Torch callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register torch callback", e)
        }
    }
}
