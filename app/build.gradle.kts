plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "xyz.nextalone.hyperzoomring"
    compileSdk = 37

    defaultConfig {
        /**
         * 装机用的应用 ID（2026-10-31 从上游的 `xyz.nextalone.hyperzoomring` 改成自己的）。
         *
         * 为什么非改不可：LSPosed 模块仓库的提交规则是"**应用 ID 必须属于你**"——
         * 要么你能证明控制那个域名（根域加 TXT `lsposed-modules-repo-verification=<github user>`），
         * 要么用 `io.github.<用户名>.<模块名>` 这条免域名通道；官方还说要开始检查
         * "仓库包名与发布的 APK 不符"。`xyz.nextalone.*` 是上游作者的域名族
         * （他在架上的另一个模块就叫 `ltd.nextalone.pkginstallerplus`），我们借用不了。
         * 判据与流程见 docs/LSPOSED-STORE.md。
         *
         * 代价说清楚：改 ID = 新 App。老包不会被覆盖安装，两个包同时启用会各自抓一次环，
         * 所以迁移必须"先卸载老的再装新的"，而卸载会带走 LSPosed 的模块记录、作用域
         * 和那份配置。App 首页对还装着老包的人有一条明确提示（HomeScreen 的 legacy 那条）。
         *
         * `namespace` 保持 `xyz.nextalone.hyperzoomring` 不动：源码的 package/import、
         * R 类、BuildConfig、KSP 生成的入口类名全都不受影响 —— 换的只是装机身份。
         * 代码里所有以包名为主键的东西（广播 action、签名级权限名、setPackage、
         * 已加载 APK 的过滤）一律从 BuildConfig.APPLICATION_ID 派生，别再写字面量。
         */
        applicationId = "io.github.tvtjiesen.omniring"
        minSdk = 35
        targetSdk = 35
        // 口径：versionCode = 第几轮交付（每次 +1），versionName 跟着轮次走高。
        // 1.39 及之前一律以 -beta 结尾（测试项目，他明确要求不要停在 1.0.0）；
        // 1.40.0 起去掉 -beta —— 因为这一版要进 LSPosed 商店，商店页是给普通用户看的，
        // 而且仓库要求 release tag = "<versionCode>-<versionName>"，带 beta 看着就像测试包。
        // 以后再进新一轮实测，重新回到 x.y.z-beta 的写法。
        versionCode = 53
        versionName = "1.40.2"
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

    /**
     * 「只留传统入口」的发布开关：`./gradlew -Phzr.legacyOnly=true assembleRelease`
     *
     * 为什么要这个开关（2026-10-31 在自建虚拟台 bench_a15 上做的对照实验）：
     * 现在包里**同时**声明了两条入口 —— libxposed 102（META-INF/xposed/module.prop +
     * java_init.list，走 CrownModule）和传统 YukiHook（assets/xposed_init，走 HookEntry）。
     * LSPosed 2.2.1 认这个组合（真机探针回显 entry=modern102、6 个钩点位全装上），
     * 但**老版本 LSPosed（v1.11.0）撞上双声明时一条都不加载**：模块在管理器里看着是
     * "已启用"，system_server 里连 dex 都没进来。酷友的 HyperOS 3 机器我们没法预装，
     * 他们手上大概率就是老版本 —— 一个"装完静默不工作"的包对远程测试等于自杀。
     *
     * 关掉 libxposed 声明后，任何版本的 Xposed/LSPosed 都只走传统入口，
     * 这条入口在本工程里是完整可用的（它本来就是上游原版唯一的那条）。
     * 判"这版到底走的哪条"不用猜：探针 8192 回显的 `entry=` 就是加载时记下来的真值。
     */
    packaging {
        resources {
            if (providers.gradleProperty("hzr.legacyOnly").orNull == "true") {
                excludes += "META-INF/xposed/**"
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
