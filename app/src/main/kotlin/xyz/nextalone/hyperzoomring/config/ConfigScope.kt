package xyz.nextalone.hyperzoomring.config

sealed class ConfigScope {
    data object Global : ConfigScope()
    data class App(val packageName: String) : ConfigScope()
    data class Scene(val scene: SceneType) : ConfigScope()
}
