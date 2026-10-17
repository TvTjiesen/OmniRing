plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "xyz.nextalone.hyperzoomring"
    compileSdk = 37

    defaultConfig {
        applicationId = "xyz.nextalone.hyperzoomring"
        minSdk = 35
        targetSdk = 35
        // 装机验收已经跑了十几轮，还有一堆已知问题没收敛 —— 按他的要求版本号要动起来并以 beta 结尾。
        // 口径：versionCode = 第几轮装机（每次交付 +1），versionName 跟着轮次走高、
        // 始终以 -beta 结尾（这是测试项目，他明确要求不要停在 1.0.0）。
        versionCode = 42
        versionName = "1.32.0-beta"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("KEYSTORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                // 没给正式签名库时用**调试库**签 release：
                // 1) R8/资源收缩这条链路照样能量（体积、启动、真机行为都和正式签名一致）；
                // 2) 签名和手机上已装的 debug 包**同一个 key**，`install -r` 直接覆盖 ——
                //    换签名的话必须先卸载，而卸载会把 LSPosed 的模块记录和作用域一起带走，
                //    在他日常机上这是不可接受的（启用类操作必须他在场）。
                // 真要发布时把 KEYSTORE_FILE/PASSWORD/ALIAS 四个环境变量给全即可。
                val dbg = File(System.getProperty("user.home"), ".android/debug.keystore")
                if (dbg.exists()) {
                    storeFile = dbg
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // 资源收缩：只跟着 R8 的可达性走。我们这里 res 只有 0.56MB + arsc 1.46MB，
            // 大头是 dex（41MB），所以这一项是"顺手"，真正省的是 R8 把没用到的
            // Compose/Miuix/YukiHook 代码整段删掉。
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseConfig = signingConfigs.findByName("release")
            if (releaseConfig?.storeFile != null) {
                signingConfig = releaseConfig
            }
        }
    }

    lint {
        // assembleRelease 默认会挂 lintVitalRelease，它的 classpath 要从 dl.google.com 拉
        // androidx 制品 —— 这台机到那儿是超时，构建直接卡死。我们这轮的验收靠真机实测
        // （体积/CPU/帧/探针回读），不靠 lint，所以关掉它，不是"忽略问题"。
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Miuix 0.9.2 会把 kotlin-stdlib-common 抬到 2.4.0，那个包在这台机器上下不下来
// （制品在 Maven Central 上是存在的，只是这条网络路径取不到），而它从 1.8 起就是个空的
// 兼容壳 → 钉回编译器同版本，构建就能完全离线跑，不需要 VPN。
configurations.all {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib-common:2.3.20")
}

dependencies {
    // YukiHookAPI
    implementation(libs.yukihookapi.api)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    compileOnly(libs.xposed.api)
    // 框架在运行时注入，不能打进包（打进去会和 LSPosed 自己给的那份冲突）
    compileOnly(libs.libxposed.api)
    ksp(libs.yukihookapi.ksp.xposed)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.activity)
    implementation(libs.compose.navigation)

    // Miuix UI
    implementation(libs.miuix)
    implementation(libs.miuix.core)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)

    // Test
    testImplementation(libs.junit)
}
