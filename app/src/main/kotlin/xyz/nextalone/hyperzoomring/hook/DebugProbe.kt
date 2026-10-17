package xyz.nextalone.hyperzoomring.hook

import android.content.Context
import android.provider.Settings
import android.util.Log
import xyz.nextalone.hyperzoomring.action.actions.MediaHelper
import xyz.nextalone.hyperzoomring.action.actions.ScrubDragAction
import xyz.nextalone.hyperzoomring.action.actions.TouchScrollInjector
import xyz.nextalone.hyperzoomring.hud.CrownHud
import xyz.nextalone.hyperzoomring.media.MediaSessionBridge
import kotlin.math.abs

/**
 * 诊断探针：`settings put global hzr_probe <位图>` 触发，结果写 logcat。
 *
 * 为什么要有它：浮窗（system_server 里自己 addView）和 MediaSession 控制权这两件事
 * 都不该等"他转动变焦环看视频"时才发现不行。给一条不需要硬件参与、
 * 不用重新编译就能跑的验证路径，测完把 hzr_probe 清 0 即可。
 *
 * 位图：1=画一次浮窗，2=读播放器快照，4=对当前会话做一次 +1 秒 seek，
 * 8=拖一次进度条，16/32/64=滚动自测，128=导出当前手势映射，512=配置来源体检，
 * 2048=注入器体检（"有概率丢失触控"要先看清闸门和手指状态），
 * 8192=入口体检（legacy 还是 102、装了几个钩点、等 Context 等了多久）。
 */
object DebugProbe {
    private const val TAG = "HyperZoomRing"

    /**
     * 探针版本号。装机后 system_server 里跑的**不一定**是新 dex（lspd 会缓存模块内容，
     * 实测出现过 App 进程是新代码、system_server 还是旧代码的情况），
     * 所以每条回显都带上这个戳，一眼看出到底加载的是哪一版。
     */
    const val BUILD_TAG = "cfg38"

    /**
     * 哪条入口真的把模块带进了 system_server：`legacy` / `modern102` / `none`。
     *
     * 为什么要在探针里回显它：LSPosed 报"废弃 API"的判据是**加载方式**，而这 Exactly
     * 是"装了新 APK 却分不清框架到底走了哪条入口"的那种事 —— 上次改 101 时日志一条没有，
     * 光看 hzr_cfg 里有没有 build 根本分不清"modern 入口没被回调"还是"回调了但没装钩子"。
     * 有了这个字段，一次探针就能同时看到：版本号 + 入口 + 装了几个钩点 + 等了多久才拿到 Context。
     */
    @Volatile
    var entryPath: String = "none"

    @Volatile private var lastCmd = 0L

    fun tick(context: Context) {
        val cmd = try {
            Settings.Global.getLong(context.contentResolver, "hzr_probe", 0L)
        } catch (e: Throwable) {
            0L
        }
        if (cmd == 0L || cmd == lastCmd) return
        lastCmd = cmd
        Log.i(TAG, "PROBE start cmd=$cmd")

        if (cmd and 1L != 0L) {
            try {
                CrownHud.showOffset(context, 3200f, 754_000L, 1_320_000L)
                Log.i(TAG, "PROBE hud: showOffset 已调用（能不能看到请配合截图）")
            } catch (e: Throwable) {
                Log.e(TAG, "PROBE hud threw", e)
            }
        }
        // 128 = 把"四个全局手势现在分别映射到哪个动作"写回 Settings.Global。
        // 用户报"改了全局手势没生效"，而配置是热读的，光看界面猜不出到底断在哪一环：
        // App 写的那份 prefs / hook 读的那份 prefs / 解析出来的动作 id，是三个不同的地方。
        // 有了这一条，改完映射不用转环也能端到端确认。
        if (cmd and 128L != 0L) {
            val cfg = InputInterceptorHook.currentConfig
            if (cfg == null) {
                Log.e(TAG, "PROBE map: 还没有 ConfigManager（钩子没装好？）")
            } else {
                val line = xyz.nextalone.hyperzoomring.ring.GestureType.entries.joinToString("|") { g ->
                    "${g.name}:${cfg.getActionId(g) ?: "-"}"
                }
                runCatching { Settings.Global.putString(context.contentResolver, "hzr_map", line) }
                Log.i(TAG, "PROBE map=$line")
            }
        }

        if (cmd and 2L != 0L) {
            Log.i(TAG, "PROBE session=${MediaHelper.describeSession(context)}")
            val snap = try {
                MediaHelper.snapshot(context)
            } catch (e: Throwable) {
                Log.e(TAG, "PROBE snapshot threw ${e.javaClass.name}", e)
                null
            }
            Log.i(TAG, "PROBE snapshot=${snap?.let { "${it.positionMs}/${it.durationMs}" } ?: "null"}")
        }
        if (cmd and 4L != 0L) {
            val s = MediaSessionBridge.pick()
            if (s == null) {
                Log.i(TAG, "PROBE seek: 没有可控的 MediaSession（会走拖进度条那条通道）")
            } else {
                val target = s.positionMs + 5000L
                val ok = MediaSessionBridge.seekTo(s, target)
                // 等一下再读一次：只有位置真的变了，才叫 seek 成功
                Thread.sleep(500L)
                val after = MediaSessionBridge.pick()
                val afterPos = after?.positionMs ?: -1L
                val diff = if (after != null) abs(after.positionMs - target) else -1L
                Log.i(
                    TAG,
                    "PROBE seek ${s.positionMs} -> $target ok=$ok 实测 pos=$afterPos 误差=${diff}ms"
                )
            }
        }
        if (cmd and 8L != 0L) {
            // 这一版 Android 的 AIDL 到底长什么样：一次看清楚，别猜签名
            for (cls in listOf("android.media.session.ISession", "android.media.session.IMediaController")) {
                try {
                    val names = Class.forName(cls).methods.sortedBy { it.name }.joinToString(" | ") { m ->
                        m.name + m.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
                    }
                    Log.i(TAG, "PROBE $cls: $names")
                } catch (e: Throwable) {
                    Log.e(TAG, "PROBE dump $cls failed", e)
                }
            }
        }
        if (cmd and 16L != 0L) {            val ok = ScrubDragAction.forceDrag(context, 320f, SceneDetector.isLandscape(context))
            Log.i(TAG, "PROBE scrub 会话已起 ok=$ok：偏移 320px，按住到停手后才抬手（截图看预览挂多久）")
        }
        // 32/64：不经传感器直接推滚动目标，用来在电脑上自证注入链路和两个方向都活着
        if (cmd and 32L != 0L) {
            TouchScrollInjector.feed(context, 500f)
            Log.i(TAG, "PROBE scroll +500px（内容向下）")
        }
        if (cmd and 64L != 0L) {
            TouchScrollInjector.feed(context, -500f)
            Log.i(TAG, "PROBE scroll -500px（内容向上）")
        }
        // 512 = 配置通道回显：推送到底通不通、现在生效的是哪一份。
        // 实测 system_server 读不到 App 的私有 prefs（exists=false/canRead=false），
        // 快照又是冻结的，所以配置只能靠 App 推 —— 这条就是证明那条通道活着。
        if (cmd and 512L != 0L) {
            val cw = InputInterceptorHook.currentConfig?.getActionId(
                xyz.nextalone.hyperzoomring.ring.GestureType.CW_SLOW
            ) ?: "-"
            val age = if (ConfigSync.lastAppliedAt == 0L) -1L
            else System.currentTimeMillis() - ConfigSync.lastAppliedAt
            val line = "pushed=${ConfigSync.appliedCount} keys=${ConfigSync.lastKeyCount} " +
                "src=${ConfigSync.lastSource} " +
                "stage=${ConfigSync.stagePaused()} " +
                "bootRestore=${runCatching { Settings.Global.getString(context.contentResolver, ConfigSync.KEY_BOOT_RESTORE) }.getOrNull()} " +
                "snap=${runCatching { Settings.Global.getString(context.contentResolver, ConfigSync.KEY_SNAPSHOT)?.length }.getOrNull()}B " +
                "ageMs=$age CW_SLOW=$cw build=$BUILD_TAG reg=${ConfigSync.registerError ?: "ok"}"
            Log.i(TAG, "PROBE cfg $line")
            runCatching { Settings.Global.putString(context.contentResolver, "hzr_cfg", line) }
        }
        // 2048 = 注入器体检。他报"有概率丢失触控，表冠和触摸都会死"，而这两种失效
        // 在界面上长得一模一样：闸门在冷却 / 我们那根手指还压在屏幕上 / 注入通道返回假。
        // 先把三者分清，再谈修。
        if (cmd and 2048L != 0L) {
            val line = "inj=${TouchScrollInjector.diagnostics()} build=$BUILD_TAG"
            Log.i(TAG, "PROBE $line")
            runCatching { Settings.Global.putString(context.contentResolver, "hzr_inj", line) }
        }
        // 8192 = 入口体检。消废弃警告这件事只能靠"框架到底走了哪条入口"来判，
        // 而 legacy 和 modern 在界面上看起来一模一样（都能有心跳、都可能没装钩子）。
        // 这一条把入口名、102 装了几个钩点、等 Context 等了多久一次性写回来，
        // 不用转环、不用重编译，也能判断"modern 入口到底活着没"。
        if (cmd and 8192L != 0L) {
            val line = "entry=$entryPath ${CrownHooks.diagnostics()} " +
                "bootWait=${CrownHooks.Boot.contextWaitMs} build=$BUILD_TAG"
            Log.i(TAG, "PROBE $line")
            runCatching { Settings.Global.putString(context.contentResolver, "hzr_entry", line) }
        }
        Log.i(TAG, "PROBE done")
    }

}