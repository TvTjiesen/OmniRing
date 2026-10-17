package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import xyz.nextalone.hyperzoomring.action.Action
import xyz.nextalone.hyperzoomring.config.SceneType
import xyz.nextalone.hyperzoomring.hud.CrownHud
import xyz.nextalone.hyperzoomring.ring.ScrubModel

/**
 * 视频/音频进度环（表冠语义）。
 *
 * 与旧实现的区别（旧实现被他判定为"没做好"）：
 *  - 旧：每攒够 0.7 秒就相对当前位置跳一次，且拿不到 MediaSession 就发媒体键。
 *    相对跳会把播放器自己的前进也算进去 -> 来回转时对不上账（抽搐）；
 *    媒体键是一次固定 ±10~15 秒的盲跳 -> "不知道快进到哪了直接就快进了"。
 *  - 新：会话开始先拍基准位置，之后只维护一个绝对目标（转多少 = 跳多少秒），
 *    按节流发绝对 seekTo，并挂一个小胶囊把"要跳到哪"显示出来。
 *
 * 拿不到播放器（无 MediaSession、无权限）时返回 false，让上层退回正常滚动，
 * 并且 5 秒内不再重试，避免每帧抛异常（功耗）。
 *
 * 热调：hzr_seek_ms_per_unit（每单位位置=多少毫秒）、hzr_hud（0=关浮窗）
 */
object MediaScrubAction : Action {
    private const val TAG = "HyperZoomRing"
    private const val DEF_MS_PER_UNIT = 12f
    private const val UNAVAILABLE_COOLDOWN_MS = 5000L
    private const val HUD_MIN_GAP_MS = 80L

    override val id = "video_seek"
    override val displayName = "视频/音频进度（表冠微调）"

    /**
     * 只在"播放中"场景可选，不进全局手势清单。
     * 用户反馈：全局槽位里再给一个"音视频进度"，会和首页那个"播放时把滚动改成调进度"
     * 总开关打架（同一个槽位两处都能定，改哪边都不一定生效）。总开关已经会把
     * 滚动改道成进度，所以全局清单里根本不需要它。
     */
    override val sceneOnly = SceneType.MEDIA_PLAYING

    private val model = ScrubModel()
    private var lastDeltaUnits = 0f
    private var snapshot: MediaHelper.Snapshot? = null
    private var unavailableUntil = 0L
    private var lastHudAt = 0L

    @Volatile private var paramAt = 0L

    /** 设置界面里的灵敏度；热参没设置时用它 */
    @Volatile private var uiMsPerUnit = DEF_MS_PER_UNIT

    /** 热参 hzr_seek_ms_per_unit，-1 = 未设置 */
    @Volatile private var hotMsPerUnit = -1f

    /** 设置界面里的浮窗开关 + 热参 hzr_hud，两者都开才显示 */
    @Volatile private var uiHud = true
    @Volatile private var hotHud = true
    @Volatile private var hudActive = true

    /** 直接把手势映射到本动作时的兜底：沿上一次的转向走一小步 */
    override fun execute(context: Context, intensity: Float) {
        val step = if (lastDeltaUnits >= 0f) 40f else -40f
        onDelta(context, step)
    }

    /**
     * @param deltaUnits 本次被采纳的旋转量（带符号单位，正=顺转=往后快进）
     * @return true = 已经被 seek 消化；false = 控制不了播放器，调用方应回落到滚动
     */
    fun onDelta(context: Context, deltaUnits: Float): Boolean {
        refreshParams(context)
        val now = SystemClock.uptimeMillis()
        if (now < unavailableUntil) return false

        lastDeltaUnits = deltaUnits
        val needSnap = model.needsSnapshot(now)
        val snap = if (needSnap) MediaHelper.snapshot(context).also { snapshot = it } else snapshot
        if (snap == null) {
            unavailableUntil = now + UNAVAILABLE_COOLDOWN_MS
            Log.i(TAG, "no controllable media session; seek 关闭 5 秒")
            return false
        }

        val cmd = model.onDelta(now, deltaUnits, snap.positionMs, snap.durationMs)
        if (hudActive && now - lastHudAt >= HUD_MIN_GAP_MS) {
            lastHudAt = now
            CrownHud.showOffset(context, model.offsetMs(), model.targetPosMs(), snap.durationMs)
        }
        if (cmd != null && !MediaHelper.seekToAbsolute(context, cmd)) {
            unavailableUntil = now + UNAVAILABLE_COOLDOWN_MS
            if (hudActive) CrownHud.hideNow(context)
            return false
        }
        return true
    }

    /** 会话彻底结束（比如切场景）时收掉浮窗 */
    fun finish(context: Context) {
        model.endSession()
        if (hudActive) CrownHud.hideNow(context)
    }

    /** 由驱动每 0.4 秒同步一次设置界面的浮窗开关（热参仍然有一票否决） */
    fun applyHud(context: Context, fromSettings: Boolean) {
        uiHud = fromSettings
        val next = uiHud && hotHud
        if (next == hudActive) return
        hudActive = next
        if (!next) CrownHud.hideNow(context)
    }

    fun applyMsPerUnit(value: Float) {
        if (value > 0) uiMsPerUnit = value
    }

    private fun refreshParams(context: Context) {
        val now = System.currentTimeMillis()
        if (now - paramAt < 400) return
        paramAt = now
        try {
            val r = context.contentResolver
            hotMsPerUnit = Settings.Global.getFloat(r, "hzr_seek_ms_per_unit", -1f)
            model.msPerUnit = if (hotMsPerUnit > 0) hotMsPerUnit else uiMsPerUnit
            model.minStepMs = Settings.Global.getFloat(r, "hzr_seek_min_step", 200f).toLong()
            model.throttleMs = Settings.Global.getFloat(r, "hzr_seek_throttle", 110f).toLong()
            hotHud = Settings.Global.getInt(r, "hzr_hud", 1) == 1
            val next = uiHud && hotHud
            if (!next && hudActive) CrownHud.hideNow(context)
            hudActive = next
        } catch (e: Throwable) {
            Log.d(TAG, "scrub params refresh failed", e)
        }
    }
}
