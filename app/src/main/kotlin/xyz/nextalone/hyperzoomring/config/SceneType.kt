package xyz.nextalone.hyperzoomring.config

enum class SceneType(val key: String, val displayName: String) {
    MEDIA_PLAYING("media_playing", "媒体播放中"),
    FLASHLIGHT_ON("flashlight_on", "手电筒已开启"),
    FULLSCREEN("fullscreen", "全屏模式（实验性）"),
}
