package xyz.nextalone.hyperzoomring.ring

/**
 * 一次分发该走哪条通道 —— 从 SensorRingDriver 的 if 链里抽出来的纯判断。
 *
 * 抽出来的理由：那条 if 链贴着 Android（Context / 注入器 / MediaSession），
 * 在 PC 上一条断言都写不了，而"某个动作悄悄走进死路"正是最难查的那类失效
 * （用户只会说"这个功能好像没用了"）。抽成纯函数后能把 18 个动作逐个钉住。
 *
 * 通道含义：
 *  - [Branch.SCROLL]   连续追目标：方向跟表冠实际转向，距离跟累计幅度；
 *  - [Branch.SEEK]     进度环接管：先试 MediaSession 绝对 seek，没有会话再横向拖播放器进度条；
 *  - [Branch.DRAG]     用户明确选了"拖进度条"，永远不去碰 MediaSession；
 *  - [Branch.DISCRETE] 转够一个阈值发一次（音量、亮度、切歌、相对 seek、启动应用…）。
 */
object ActionRouter {

    enum class Branch { SCROLL, SEEK, DRAG, DISCRETE }

    /** ScrubDragAction 的 id。这里写常量而不是引用那个 object：它带 Android 依赖，纯测试里加载不了 */
    private const val DRAG_ID = "video_scrub_drag"

    fun route(actionId: String, asSeek: Boolean): Branch = when {
        // 明确选了"拖"的，永远就是拖 —— 哪怕进度环总开关开着也不改道，
        // 因为他可能就是冲着"这台播放器没有会话、只能拖"才选的它
        actionId == DRAG_ID -> Branch.DRAG
        asSeek -> Branch.SEEK
        actionId.startsWith("scroll") -> Branch.SCROLL
        else -> Branch.DISCRETE
    }
}
