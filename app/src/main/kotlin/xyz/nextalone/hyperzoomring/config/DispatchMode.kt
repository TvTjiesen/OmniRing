package xyz.nextalone.hyperzoomring.config

enum class DispatchMode(val key: String, val displayName: String) {
    GLOBAL("global", "全局模式"),
    PER_APP("per_app", "分应用模式"),
    PER_SCENE("per_scene", "分场景模式"),
    ;

    companion object {
        fun fromKey(key: String): DispatchMode =
            entries.find { it.key == key } ?: GLOBAL
    }
}
