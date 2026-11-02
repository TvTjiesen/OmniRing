package xyz.nextalone.hyperzoomring.config

import android.content.Context
import android.content.SharedPreferences
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge
import xyz.nextalone.hyperzoomring.ring.CrownDir
import xyz.nextalone.hyperzoomring.ring.ZoomRingConstants

/**
 * 旧存档里的"定方向滚动"与"启动应用"归一。
 *
 * - `scroll_up` / `scroll_down`：按用户要求删掉了，但他（以及升级上来的老用户）手机上现存的
 *   映射还写着这两个 id。
 * - `launch_app`：2026-11-02 起"无方向动作直接不支持"（业主 2026-11-01 拍板），旧存档里
 *   指向它的槽位一样会解析不出东西。
 *
 * 不归一的话 ActionRegistry 查不到动作，而 SensorRingDriver 是
 * `ActionRegistry.get(actionId) ?: return` —— 表冠会当场变成"转了没反应"，
 * 界面上那一格还会显示成已配置。读配置时就地归一，不需要迁移写回。
 */
internal fun migrateActionId(id: String?): String? = when (id) {
    "scroll_up", "scroll_down" -> "scroll"
    "launch_app" -> "scroll"
    else -> id
}

/**
 * 四槽位时代（≤ cfg45）的槽位名，PLUS / MINUS 各自按次序回落：先老"慢转"，再老"快转"。
 *
 * 为什么慢转优先：老存档里快慢两档可以配不同动作。快转那一档是"甩一下"的偶发态，
 * 慢转才是他日常真在用的 —— 合并时把慢转那档带过来最接近他的实感（业主 2026-11-02 批准）。
 * 旧键**只读不删**：装回 cfg45 的包，旧映射原样还在，行为瞬间回退。
 */
internal val LEGACY_SLOT_NAMES: Map<CrownDir, List<String>> = mapOf(
    CrownDir.PLUS to listOf("CW_SLOW", "CW_FAST"),
    CrownDir.MINUS to listOf("CCW_SLOW", "CCW_FAST"),
)

class ConfigManager private constructor(private val store: PrefsStore) {

    /**
     * 读一份映射：新键优先，没有才按 [LEGACY_SLOT_NAMES] 的次序落到旧键上。
     *
     * 写永远只写新键（见各 setter）；旧键**只读不删**，装回 cfg45 的包就回退到旧行为，
     * 不需要任何"迁移写回" —— 迁移一旦写坏，老用户的自定义映射就永久没了。
     */
    private fun readSlot(actionPrefix: String, dir: CrownDir, suffix: String): String? {
        store.getString("${actionPrefix}gesture_${dir.name}_$suffix", null)?.let { return it }
        LEGACY_SLOT_NAMES.getValue(dir).forEach { legacy ->
            store.getString("${actionPrefix}gesture_${legacy}_$suffix", null)?.let { return it }
        }
        return null
    }

    fun getActionId(dir: CrownDir): String? = migrateActionId(readSlot("", dir, "action_id"))

    fun setActionId(dir: CrownDir, actionId: String?) {
        if (actionId != null) {
            store.putString("gesture_${dir.name}_action_id", actionId)
        } else {
            store.remove("gesture_${dir.name}_action_id")
        }
    }

    fun getActionConfig(dir: CrownDir): String? = readSlot("", dir, "action_config")

    fun setActionConfig(dir: CrownDir, config: String?) {
        if (config != null) {
            store.putString("gesture_${dir.name}_action_config", config)
        } else {
            store.remove("gesture_${dir.name}_action_config")
        }
    }

    var speedThreshold: Int
        get() = store.getInt("speed_threshold", ZoomRingConstants.DEFAULT_SPEED_THRESHOLD)
        set(value) = store.putInt("speed_threshold", value)

    var isEnabled: Boolean
        get() = store.getBoolean("module_enabled", true)
        set(value) = store.putBoolean("module_enabled", value)

    /**
     * 默认映射只种一次的标记。
     * 没有它，他主动把所有槽位清空后，下次进界面又会被我们填回去。
     */
    var defaultsSeeded: Boolean
        get() = store.getBoolean("defaults_seeded", false)
        set(value) = store.putBoolean("defaults_seeded", value)

    /**
     * 老存档里是否还留着旧四槽（CW_SLOW/CW_FAST/CCW_SLOW/CCW_FAST）的映射 —— 只读探测，不写任何东西。
     * 三个作用域都查：全局前缀 ""、分应用的 `app_<pkg>_`、分场景的 `scene_<key>_`。
     * 界面拿它决定要不要弹一次性的"快转/慢转已合并到同一转向"提示（P1）。
     */
    fun hasLegacySlots(): Boolean {
        val prefixes = mutableListOf("")
        getConfiguredPackages().forEach { prefixes += "app_${it}_" }
        SceneType.entries.forEach { prefixes += "scene_${it.key}_" }
        val legacy = LEGACY_SLOT_NAMES.values.flatten()
        return prefixes.any { prefix ->
            legacy.any { slot ->
                store.getString("${prefix}gesture_${slot}_action_id", null) != null ||
                    store.getString("${prefix}gesture_${slot}_action_config", null) != null
            }
        }
    }

    /** 一次性迁移提示是否已经提示过（P1 用）。老存档归一不写回任何映射，所以只需要这个标记。 */
    var legacyNoticeShown: Boolean
        get() = store.getBoolean("legacy_notice_shown", false)
        set(value) = store.putBoolean("legacy_notice_shown", value)

    // --- Dispatch Mode ---
    var dispatchMode: DispatchMode
        get() = DispatchMode.fromKey(
            store.getString("dispatch_mode", DispatchMode.GLOBAL.key) ?: DispatchMode.GLOBAL.key
        )
        set(value) = store.putString("dispatch_mode", value.key)

    // --- Camera Override ---
    var overrideCamera: Boolean
        get() = store.getBoolean("override_camera", false)
        set(value) = store.putBoolean("override_camera", value)

    // --- 外观（只影响设置界面，不进 hook 热路径）---
    /** 0=跟随系统 1=浅色 2=深色 3/4/5=Material You 跟随/浅/深 */
    var themeMode: Int
        get() = store.getInt("theme_mode", 0)
        set(value) = store.putInt("theme_mode", value)

    /** Material You 主色；0 表示不指定（用系统壁纸取色） */
    var themeKeyColor: Int
        get() = store.getInt("theme_key_color", 0)
        set(value) = store.putInt("theme_key_color", value)

    /** 色彩规格与调色板风格，直接对应 Miuix 的 ThemeColorSpec / ThemePaletteStyle 序号 */
    var themeColorSpec: Int
        get() = store.getInt("theme_color_spec", 0)
        set(value) = store.putInt("theme_color_spec", value)

    var themePaletteStyle: Int
        get() = store.getInt("theme_palette_style", 0)
        set(value) = store.putInt("theme_palette_style", value)

    /** ""=跟随系统，"zh"=简体中文，"en"=English */
    var language: String
        get() = store.getString("language", "") ?: ""
        set(value) = store.putString("language", value)

    // --- 视频/音频进度环（表冠求对）---
    /**
     * 总开关：默认关。打开后"播放中 + 横屏（或竖屏白名单应用）"时，
     * 原本映射成滚动的动作自动改当进度环用；不需要的人完全不受影响。
     */
    var mediaSeekEnabled: Boolean
        get() = store.getBoolean("media_seek_enabled", false)
        set(value) = store.putBoolean("media_seek_enabled", value)

    /** 每单位旋转 = 多少毫秒进度，UI 上是个灵敏度滑条 */
    var seekMsPerUnit: Int
        get() = store.getInt("seek_ms_per_unit", 12)
        set(value) = store.putInt("seek_ms_per_unit", value)

    /** 转的时候是否在屏幕上显示"要跳到哪" */
    var seekHudEnabled: Boolean
        get() = store.getBoolean("seek_hud_enabled", true)
        set(value) = store.putBoolean("seek_hud_enabled", value)

    /**
     * 竖屏也允许把滚动换成 seek 的应用。
     * 竖屏默认不接管，因为他反馈过"竖屏状态下上下滑动和前后快进有概率会冲突"：
     * 竖屏刷列表时后台在放歌，若按 isMusicActive 就抢了滚动。只有明确是
     * 全屏播放器（横屏）或下面这些竖屏也是视频流的应用才接管。
     */
    var seekPortraitPackages: Set<String>
        get() {
            val raw = store.getString("seek_portrait_packages", null) ?: return DEFAULT_SEEK_PORTRAIT
            return raw.split(",").filter { it.isNotBlank() }.toSet()
        }
        set(value) = store.putString("seek_portrait_packages", value.joinToString(","))

    // --- Per-App ---
    /**
     * 每次现读，不做进程内缓存。
     *
     * 缓存过一次就再也刷不掉：钩子侧的 ConfigManager 是 system_server 里的长驻对象，
     * App 新加的分应用配置会被这份缓存挡住 —— 和"改了手势没反应"是同一类 bug。
     * 这里只是一次逗号分割，量级几百字节，不值当为它缓存。
     */
    fun getConfiguredPackages(): Set<String> {
        val raw = store.getString("per_app_configured_packages", null) ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun addConfiguredPackage(packageName: String) {
        val current = getConfiguredPackages().toMutableSet()
        current.add(packageName)
        store.putString("per_app_configured_packages", current.joinToString(","))
    }

    fun removeConfiguredPackage(packageName: String) {
        val current = getConfiguredPackages().toMutableSet()
        current.remove(packageName)
        if (current.isEmpty()) {
            store.remove("per_app_configured_packages")
        } else {
            store.putString("per_app_configured_packages", current.joinToString(","))
        }
        // 每个包清 6 个槽位名（新两槽 PLUS/MINUS + 旧四槽）各自的两份键。
        // 旧键也必须清：用户既然把这个包从"分应用"里移除了，回落链就不该还能翻出它的旧映射。
        val slots = CrownDir.entries.map { it.name } + LEGACY_SLOT_NAMES.values.flatten()
        slots.forEach { slot ->
            store.remove("app_${packageName}_gesture_${slot}_action_id")
            store.remove("app_${packageName}_gesture_${slot}_action_config")
        }
    }

    fun getAppActionId(packageName: String, dir: CrownDir): String? =
        migrateActionId(readSlot("app_${packageName}_", dir, "action_id"))

    fun setAppActionId(packageName: String, dir: CrownDir, actionId: String?) {
        val key = "app_${packageName}_gesture_${dir.name}_action_id"
        if (actionId != null) store.putString(key, actionId) else store.remove(key)
    }

    fun getAppActionConfig(packageName: String, dir: CrownDir): String? =
        readSlot("app_${packageName}_", dir, "action_config")

    fun setAppActionConfig(packageName: String, dir: CrownDir, config: String?) {
        val key = "app_${packageName}_gesture_${dir.name}_action_config"
        if (config != null) store.putString(key, config) else store.remove(key)
    }

    // --- Per-Scene ---
    fun getSceneActionId(scene: SceneType, dir: CrownDir): String? =
        migrateActionId(readSlot("scene_${scene.key}_", dir, "action_id"))

    fun setSceneActionId(scene: SceneType, dir: CrownDir, actionId: String?) {
        val key = "scene_${scene.key}_gesture_${dir.name}_action_id"
        if (actionId != null) store.putString(key, actionId) else store.remove(key)
    }

    fun getSceneActionConfig(scene: SceneType, dir: CrownDir): String? =
        readSlot("scene_${scene.key}_", dir, "action_config")

    fun setSceneActionConfig(scene: SceneType, dir: CrownDir, config: String?) {
        val key = "scene_${scene.key}_gesture_${dir.name}_action_config"
        if (config != null) store.putString(key, config) else store.remove(key)
    }

    // --- Unified Resolution ---
    fun resolveActionId(dir: CrownDir, foregroundPkg: String?, activeScene: SceneType?): String? =
        when (dispatchMode) {
            DispatchMode.GLOBAL -> getActionId(dir)
            DispatchMode.PER_APP -> {
                if (foregroundPkg != null && foregroundPkg in getConfiguredPackages()) {
                    getAppActionId(foregroundPkg, dir) ?: getActionId(dir)
                } else {
                    getActionId(dir)
                }
            }
            DispatchMode.PER_SCENE -> {
                if (activeScene != null) {
                    getSceneActionId(activeScene, dir) ?: getActionId(dir)
                } else {
                    getActionId(dir)
                }
            }
        }

    fun resolveActionConfig(dir: CrownDir, foregroundPkg: String?, activeScene: SceneType?): String? =
        when (dispatchMode) {
            DispatchMode.GLOBAL -> getActionConfig(dir)
            DispatchMode.PER_APP -> {
                if (foregroundPkg != null && foregroundPkg in getConfiguredPackages()) {
                    getAppActionConfig(foregroundPkg, dir) ?: getActionConfig(dir)
                } else {
                    getActionConfig(dir)
                }
            }
            DispatchMode.PER_SCENE -> {
                if (activeScene != null) {
                    getSceneActionConfig(activeScene, dir) ?: getActionConfig(dir)
                } else {
                    getActionConfig(dir)
                }
            }
        }

    internal interface TestablePrefsStore : PrefsStore

    companion object {
        const val PREFS_NAME = "hyperzoomring_config"

        /** 竖屏也算"在看视频"的常见应用（音乐类刻意不放进来的：竖屏听歌要的是滚动） */
        val DEFAULT_SEEK_PORTRAIT = setOf(
            "tv.danmaku.bili",            // 哔哩哔哩
            "com.bilibili.app.in",        // 哔哩哔哩（新版包名）
            "com.google.android.youtube",
            "com.ss.android.ugc.aweme",   // 抖音
            "com.smile.gifmaker",         // 快手
            "com.kuaishou.nebula",        // 快手极速版
            "com.tencent.qqvideo",        // 腾讯视频
            "com.qiyi.video",             // 爱奇艺
            "com.youku.phone",            // 优酷
            "com.mxtech.videoplayer.ad",  // MX Player
            "com.mxtech.videoplayer.pro",
            "org.videolan.vlc",
            "com.miui.gallery",           // 系统相册里的视频
            "com.miui.video",             // 小米视频
            // 音乐类（com.miui.player/qqmusic/网易云）刻意不放进竖屏名单：
            // 竖屏刷列表 + 后台放歌正是他说的冲突场景，那种情况要的是滚动
        )

        /**
         * App 进程内**唯一**一份 SharedPreferences 实例。
         *
         * 必须共用：ContextImpl 是按 (文件名, mode) 缓存实例的，两处用不同 mode 就会拿到
         * 两个互相不知道对方存在的对象 —— 各自维护一份内存快照，谁写盘就把对方刚写的整份盖没。
         * 这个坑真咬过：界面上改完映射，另一个入口一写就把 gesture_* 键全丢了。
         */
        @Volatile
        private var cachedPrefs: SharedPreferences? = null

        fun appPrefs(context: Context): SharedPreferences {
            cachedPrefs?.let { return it }
            synchronized(this) {
                cachedPrefs?.let { return it }
                val app = context.applicationContext
                val p = try {
                    @Suppress("DEPRECATION")
                    app.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
                } catch (_: SecurityException) {
                    // MODE_WORLD_READABLE 需要 Xposed/LSPosed 环境；普通 App 会抛，退回私有
                    app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
                cachedPrefs = p
                return p
            }
        }

        fun fromContext(context: Context): ConfigManager =
            ConfigManager(SharedPrefsStore(appPrefs(context)))

        fun fromYukiPrefs(prefs: YukiHookPrefsBridge): ConfigManager =
            ConfigManager(YukiPrefsStore(prefs))

        /** 钩子侧专用：包一层别处的 PrefsStore（读文件那份就是从这里进来的） */
        internal fun fromStore(store: PrefsStore): ConfigManager = ConfigManager(store)


        internal fun forTest(store: TestablePrefsStore): ConfigManager =
            ConfigManager(store)
    }
}

internal interface PrefsStore {
    fun getString(key: String, default: String?): String?
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}

private class SharedPrefsStore(private val prefs: SharedPreferences) : PrefsStore {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

private class YukiPrefsStore(private val prefs: YukiHookPrefsBridge) : PrefsStore {
    override fun getString(key: String, default: String?): String? {
        val result = prefs.getString(key, default ?: "")
        return if (result.isEmpty() && default == null) null else result
    }
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putString(key: String, value: String) { prefs.edit { putString(key, value) } }
    override fun putInt(key: String, value: Int) { prefs.edit { putInt(key, value) } }
    override fun putBoolean(key: String, value: Boolean) { prefs.edit { putBoolean(key, value) } }
    override fun remove(key: String) { prefs.edit { remove(key) } }
}
