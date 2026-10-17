-keep class xyz.nextalone.hyperzoomring.HookEntry
-keep class * extends com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

# KavaRef reflection API references classes not available on Android
-dontwarn java.lang.reflect.AnnotatedType

# ==== 2026-10-16 上 R8 之前先把"靠名字找类/找方法"的口子全部钉住 ====
# 判断依据很简单：凡是"字符串里出现类名/方法名"的地方，R8 改名换址后运行时都会找不到，
# 而我们这个模块**整个就是靠名字被框架加载的**。

# 1) LSPosed 从 META-INF/xposed/java_init.list 里按**全限定名**实例化入口类。
#    这个名字不在代码里出现，R8 会把类改名甚至删掉 → 模块静默失效。
-keep class xyz.nextalone.hyperzoomring.CrownModule { *; }
#    老入口（assets/xposed_init）同理，给 LSPosed 1.x 用的那条路。
-keep class xyz.nextalone.hyperzoomring.HookEntry { *; }

# 2) 我们自己反射读 Miuix 的采样层坐标（ui/PageBackdrop.kt 的 backdropHasCoordinates
#    按方法名前缀 "getLayerCoordinates" 找）。R8 一改名，判据永远返回 false，
#    表现就是"实时模糊莫名没了/底栏变纯色"，而且**不会报错**。
-keepclassmembers class top.yukonga.miuix.kmp.blur.LayerBackdrop {
    public <methods>;
    public <fields>;
}

# 3) 钩点是按"类全名 + 方法名 + 参数类型名"匹配的（hook/HookPlan.kt 的 HookTarget.matches）。
#    目标类是 framework 的，不受我们 R8 影响；但我们自己的回调/枚举要走 values()/valueOf()。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 4) YukiHook 的 KSP 生成物 + 它按名字反射的那批实现类
-keep class * extends com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit { *; }
-keep class com.highcapable.yukihookapi.** { *; }
-dontwarn com.highcapable.yukihookapi.**

# 5) Compose / Miuix 走 AGSL 与运行时 shader，方法名会被 RenderEffect 反射用到
-keep class top.yukonga.miuix.kmp.blur.** { *; }
-dontwarn top.yukonga.miuix.kmp.**

# 6) 崩溃栈要能看懂（否则以后真机排障全靠猜）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
