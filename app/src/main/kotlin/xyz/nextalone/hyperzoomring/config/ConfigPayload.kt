package xyz.nextalone.hyperzoomring.config

/**
 * 配置整份快照的线格式：`类型 \t 键 \t 值`，一行一条。
 *
 * 为什么要自己编码：App 改完设置，system_server **看不见**那份文件
 * （实测 `exists=false canRead=false`），YukiHook 的快照又是启动时冻结的
 * （改文件、杀框架、整机重启都读不到新值），`de.robv.android.xposed.XSharedPreferences`
 * 这个类在模块 classloader 里干脆不存在。所以配置只能由 App 主动推 ——
 * 而广播 extras 传不了 Map，只能压成一个字符串。
 *
 * 解码刻意做成"坏行跳过、绝不抛"：这条链路收到的是用户手机上的实时配置，
 * 解析失败最多退化成"这次没更新"，不能把异常送进 system_server。
 */
object ConfigPayload {
    /** 广播 action 与 extras 键名，两侧共用这一处定义 */
    const val ACTION = "xyz.nextalone.hyperzoomring.action.CONFIG_PUSH"
    const val EXTRA_PAYLOAD = "cfg"

    /** 钩子开机后来要配置：App 侧收到就回推一份 */
    const val ACTION_REQUEST = "xyz.nextalone.hyperzoomring.action.CONFIG_REQUEST"

    /**
     * 沉浸态（设置页那颗放大居中的表冠）的"借一段静默"信号：
     * App 打开沉浸态时发一次，带一个到期时间戳，期间钩子只把环事件广播给界面、
     * 不执行映射到的动作。
     *
     * 为什么必须静默：滚动类动作是**往屏幕正中合成一根真手指**拖出来的（落指带
     * 0.30–0.72 屏高），而沉浸态的表盘就在正中 —— 转一下环，自己注入的那根手指
     * 会被沉浸态当成点击，于是"一转就退出"。App 用租约方式续期（1 秒一次、3 秒到期），
     * 这样即使关闭时那条广播丢了，最多 3 秒后表冠也自动恢复。
     */
    const val ACTION_STAGE = "xyz.nextalone.hyperzoomring.action.STAGE"
    const val EXTRA_STAGE_UNTIL = "stage_until"

    /**
     * "用户正用手指在我们 App 里滑"的短租约。
     *
     * 为什么要单独一条：实测过（2026-10-16，`build/test_inject_conflict2.sh`）——
     * 我们注入的那根手指压在屏上时，真实那一滑只推动 57px（单独打能推 ~1480px），
     * 合计只比"纯注入"的 476px 多了 57px。也就是说我们和用户在抢同一根
     * pointerId=0 的手指，他会被当成第二根手指被列表忽略。
     * 规则很简单：**手在屏上时用户赢**，这段时间表冠不注入手指。
     */
    const val ACTION_TOUCH = "xyz.nextalone.hyperzoomring.action.TOUCH_LEASE"
    const val EXTRA_TOUCH_UNTIL = "touch_until"

    /** 签名级权限：只有与本应用同签名的进程能推配置，别的 app 伪造不了 */
    const val PERMISSION = "xyz.nextalone.hyperzoomring.permission.PUSH_CONFIG"

    private const val SEP = '\t'

    fun encode(all: Map<String, Any?>): String {
        val sb = StringBuilder(all.size * 24)
        for ((key, value) in all) {
            if (key.isBlank()) continue
            val (tag, text) = when (value) {
                is String -> 's' to value
                is Int -> 'i' to value.toString()
                is Long -> 'l' to value.toString()
                is Float -> 'f' to value.toString()
                is Boolean -> 'b' to value.toString()
                // 本项目所有集合都是逗号拼成字符串存的，没有 StringSet；真出现了就跳过它
                else -> continue
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(tag).append(SEP).append(key).append(SEP).append(sanitize(text))
        }
        return sb.toString()
    }

    fun decode(text: String?): Map<String, String> {
        if (text.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(16)
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val tag = line[0]
            if (tag !in charArrayOf('s', 'i', 'l', 'f', 'b')) continue
            val first = line.indexOf(SEP)
            val second = line.indexOf(SEP, first + 1)
            if (first < 0 || second < 0) continue
            val key = line.substring(first + 1, second)
            if (key.isEmpty()) continue
            out[key] = line.substring(second + 1)
        }
        return out
    }

    private fun sanitize(value: String): String =
        if (value.indexOf('\n') < 0 && value.indexOf('\t') < 0) value
        else buildString(value.length) {
            for (c in value) append(if (c == '\n' || c == '\t') ' ' else c)
        }
}

/**
 * 钩子侧的只读配置源：内容来自 App 推送的那一份快照。
 *
 * 语义上它是**权威**的：推送里没有的键就是被取消了（比如把手势从"向下滚"改成"无"），
 * 这时必须返回调用方的默认值，绝不能回落到启动快照里的旧值 —— 那正是"改了没反应"。
 */
internal class MemoryStore(private val values: Map<String, String>) : PrefsStore {

    override fun getString(key: String, default: String?): String? = values[key] ?: default

    override fun getInt(key: String, default: Int): Int = values[key]?.toIntOrNull() ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean =
        values[key]?.toBooleanStrictOrNull() ?: default

    // 钩子在 system_server 里，配置只有 App 能改；写操作一律丢弃
    override fun putString(key: String, value: String) {}
    override fun putInt(key: String, value: Int) {}
    override fun putBoolean(key: String, value: Boolean) {}
    override fun remove(key: String) {}
}
