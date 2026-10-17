pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // 本地镜像优先：这台机到 dl.google.com 是**超时**（不是 404），Gradle 遇到连接失败
        // 会直接终止解析、不会继续往后一个仓库找 —— 于是 release 需要的
        // org.jetbrains.kotlin:compose-group-mapping 明明在 Maven Central 有，却永远拉不到。
        // 手工放进 localmaven/ 一次，之后 --offline 也能编 release。
        maven { url = uri(File(rootDir, "localmaven")) }
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
        maven("https://raw.githubusercontent.com/HighCapable/maven-repository/main/repository/releases") {
            content {
                includeGroupByRegex("com\\.highcapable.*")
            }
        }
    }
}

rootProject.name = "HyperZoomRing"
include(":app")
