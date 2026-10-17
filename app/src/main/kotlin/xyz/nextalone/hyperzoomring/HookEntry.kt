package xyz.nextalone.hyperzoomring

import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.hook.InputInterceptorHook

/**
 * 模块入口（LEGACY 那条，只给老框架用）。
 *
 * 现在 APK 里同时带着两条入口声明：
 *  - `assets/xposed_init` → 这个类（YukiHook 的注解生成的实现，走 `de.robv.android.xposed.*`）
 *  - `META-INF/xposed/module.prop` + `java_init.list` → [CrownModule]（libxposed API 102，不碰 legacy）
 * LSPosed 2.x 认 `targetApiVersion`，只会走后者，于是"此模块使用了已废弃且即将移除的功能"
 * 这条判据不再成立；LSPosed 1.x / 其它只认 `xposed_init` 的框架继续走这里，不会被我弄坏。
 *
 * 为什么两条都留着（不是偷懒）：只保留 modern 的话，老框架上模块直接不加载，
 * 他的变焦环会在别人手机上变成哑的；只保留 legacy 的话警告永远消不掉。
 * 框架自己二选一，代码上互不调用，唯一共用的是 [InputInterceptorHook] 那套运行时。
 */
@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {

    override fun onHook() = encase {
        loadSystem {
            // 走到这里就说明框架按 LEGACY（assets/xposed_init）把我们加载了。
            // 探针靠这一条区分两条入口：装了新 APK 却看不到 modern，就知道
            // 框架还在走老声明方式，废弃警告也就还挂着。
            xyz.nextalone.hyperzoomring.hook.DebugProbe.entryPath = "legacy"
            // 这份 prefs 只是**开机兜底快照**：实测 system_server 读不到 App 的私有文件，
            // 快照一旦建好就冻结（改文件、杀框架、整机重启都读不到新值）。
            // 真正的"改了立刻生效"走 ConfigSync 的推送通道（App 主动整份推过来），
            // 跨重启的自愈靠 ConfigSync 落到 Settings.Global 的那份快照（见 hzr_snapshot）。
            val configManager = ConfigManager.fromYukiPrefs(
                prefs(ConfigManager.PREFS_NAME)
            )
            InputInterceptorHook.hook(this, configManager)
        }
    }
}
