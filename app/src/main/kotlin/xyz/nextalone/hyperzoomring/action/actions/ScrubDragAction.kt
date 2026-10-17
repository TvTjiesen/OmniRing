package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import xyz.nextalone.hyperzoomring.action.Action
import xyz.nextalone.hyperzoomring.config.SceneType
import xyz.nextalone.hyperzoomring.ring.ScrubHoldModel

/**
 * 进度环第二条通道：横向拖播放器自己的进度条。
 *
 * 为什么需要它：真机实测小米自带播放器播放时一个 MediaSession 都没有，
 * 那条路对"看视频"完全控制不到东西。拖它自己的进度条则任何播放器都吃，
 * 而且拖动时播放器会自己显示"跳到 X:XX"的预览 —— 可视化是白送的。
 *
 * 换算关系刻意保持简单：转多少 = 拖多少像素，不猜"多少秒"，
 * 因为每个播放器"拖一屏等于跳多长"都不一样，猜了就必然对不上账。
 *
 * 一把拖到头怎么办：横屏拖的是播放器表面（横滑=快进），所以可以"接力"——
 * 抬手落定、从另一端重新落指接着拖，于是连续转环能一直快进下去。
 * 竖屏拖的是进度条本身，落点即跳转，绝对不能接力（会把播放头甩回开头），
 * 所以竖屏只允许在一把之内走完。
 *
 * 热调：hzr_scrub_y（拖拽所在的屏幕高度比例）、hzr_scrub_frames、
 *       hzr_scrub_px_per_unit、hzr_scrub_min_px、hzr_scrub_max_px（竖屏一把的长度）、
 *       hzr_scrub_max_px_land（横屏一把的长度，默认 1800=大半个屏宽）
 */
object ScrubDragAction : Action {
    private const val TAG = "HyperZoomRing"
    const val ID = "video_scrub_drag"

    override val id = ID
    override val injectsTouch = true
    override val displayName = "视频进度（拖播放器进度条）"

    /** 同 [MediaScrubAction]：进度类只在"播放中"场景可选，不和首页总开关抢全局槽位 */
    override val sceneOnly = SceneType.MEDIA_PLAYING

    private val model = ScrubHoldModel()

    @Volatile private var paramAt = 0L

    /** 横屏：拖画面中部（全屏播放器整块表面吃"横滑=快进"），避开底部 SeekBar 的跳转 */
    @Volatile private var yLand = 0.45f

    /** 竖屏：拖底部进度条。中部在 B站 这类页面会横向切卡片/分P，实测就是"没调进度反而左右滑" */
    @Volatile private var yPort = 0.93f

    /** 停手这么久才抬手 —— 决定播放器预览能挂多久 */
    @Volatile private var holdMs = 700L

    /** 一把拖拽的长度：横屏拖表面可以拖满大半个屏宽，竖屏拖进度条要短 */
    @Volatile private var passPxLand = 1800f
    @Volatile private var passPxPort = 900f

    override fun execute(context: Context, intensity: Float) {
        val step = if (lastDelta >= 0f) 60f else -60f
        onDelta(context, step, lastLandscape)
    }

    private var lastDelta = 0f

    /** 上一次转动时的横竖屏，execute() 走离散路径时复用，省一次 Display 查询 */
    @Volatile private var lastLandscape = false

    /**
     * @param landscape 由驱动算好传进来（它已经为门禁查过一次，别让动作层依赖 hook 包）
     * @return true = 这次旋转已由本通道接管；false = 注入器不可用，调用方可回落
     */
    fun onDelta(context: Context, deltaUnits: Float, landscape: Boolean): Boolean {
        refreshParams(context)
        if (deltaUnits != 0f) lastDelta = deltaUnits
        lastLandscape = landscape
        // 一把的长度按横竖屏分开：横屏要能连着接力快进，竖屏只能一把走完
        model.maxOffsetPx = if (landscape) passPxLand else passPxPort
        val now = SystemClock.uptimeMillis()
        val offset = model.onDelta(now, deltaUnits) ?: return true
        if (landscape && model.passBoundary != 0) {
            // 顶到这把的尽头还在同向推 = 他还要继续快进。落定这一把（抬手），
            // 从推送方向的另一端重新起把 —— 这就是真手指"拖到头松手再拖"的动作。
            // 竖屏不走这里：进度条上落点即跳转，重新落指会把播放头甩回开头。
            if (TouchScrollInjector.scrubEndPass(context)) {
                TouchScrollInjector.scrubNextStartAt(if (model.passBoundary > 0) 0.10f else 0.90f)
                model.beginPass()
            }
            return true
        }
        return TouchScrollInjector.scrub(context, offset, pickY(landscape), model.releaseAfterMs)
    }

    private fun pickY(landscape: Boolean): Float = if (landscape) yLand else yPort

    /** 诊断用：不经传感器自己起一次会话 */
    fun forceDrag(context: Context, offsetPx: Float, landscape: Boolean): Boolean {
        refreshParams(context)
        return TouchScrollInjector.scrub(context, offsetPx, pickY(landscape), model.releaseAfterMs)
    }

    private fun refreshParams(context: Context) {
        val now = System.currentTimeMillis()
        if (now - paramAt < 400) return
        paramAt = now
        try {
            val r = context.contentResolver
            model.pxPerUnit = Settings.Global.getFloat(r, "hzr_scrub_px_per_unit", model.pxPerUnit)
            // 一把的长度分横竖屏两个键（onDelta 里按当前朝向挑一个给 model）
            passPxPort = Settings.Global.getFloat(r, "hzr_scrub_max_px", passPxPort)
            passPxLand = Settings.Global.getFloat(r, "hzr_scrub_max_px_land", passPxLand)
            model.minPressPx = Settings.Global.getFloat(r, "hzr_scrub_min_px", model.minPressPx)
            model.idleMs = Settings.Global.getLong(r, "hzr_scrub_hold_ms", model.idleMs)
            yLand = Settings.Global.getFloat(r, "hzr_scrub_y", yLand)
            yPort = Settings.Global.getFloat(r, "hzr_scrub_y_port", yPort)
            holdMs = model.idleMs
        } catch (e: Throwable) {
            Log.d(TAG, "scrub drag params refresh failed", e)
        }
    }
}
