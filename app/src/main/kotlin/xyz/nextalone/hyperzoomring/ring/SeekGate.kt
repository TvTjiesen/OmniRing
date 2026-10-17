package xyz.nextalone.hyperzoomring.ring

/**
 * 「这一把到底该调进度还是该滚动」的判定，抽成纯函数好在 PC 上把场景都跑一遍。
 *
 * 他反馈的冲突是：竖屏刷列表时后台在放歌，模块只看 isMusicActive 就把滚动换成了
 * seek，于是列表滚不动。规则收敛成四条：
 *  1. 总开关关着就绝不接管（除非 He 明确把动作映射成「视频/音频进度」）；
 *  2. **横屏 + 前台是视频应用 = 接管，暂停也算**（暂停时 isMusicActive 为假，
 *     旧规则会直接判成"不该接管"，于是转环没反应 —— 他报的这条）；
 *  3. 横屏 + 正在播放 = 看片，接管（覆盖不在名单里的播放器）；
 *  4. 竖屏只有"正在播放 + 落在视频白名单"才接管，音乐类一律不接管（那种场景要的是滚动）。
 */
object SeekGate {
    fun shouldSeek(
        switchOn: Boolean,
        actionId: String,
        mediaPlaying: Boolean,
        landscape: Boolean,
        foregroundPkg: String?,
        portraitAllowlist: Set<String>,
        videoApps: Set<String> = portraitAllowlist,
    ): Boolean {
        if (actionId == ACTION_SEEK) return true
        if (!switchOn) return false
        if (!actionId.startsWith("scroll")) return false
        if (landscape) {
            if (foregroundPkg != null && foregroundPkg in videoApps) return true
            return mediaPlaying
        }
        return mediaPlaying && foregroundPkg != null && foregroundPkg in portraitAllowlist
    }

    const val ACTION_SEEK = "video_seek"
}
