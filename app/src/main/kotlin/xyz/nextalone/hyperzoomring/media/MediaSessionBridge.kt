package xyz.nextalone.hyperzoomring.media

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 在 system_server 里访问 MediaSession 的桥。
 *
 * 为什么不直接用 MediaSessionManager：真机实测它在系统进程里 mService 是 null
 * （getActiveSessions 直接 NPE），因为系统进程取系统服务的方式和应用进程不同。
 * 这里跳过封装，直接拿 "media_session" 这个 binder 按 AIDL 说话。
 *
 * Android 16 的 ISession 已经被精简成只剩"给控制器"这一件事（实测方法表里
 * 没有 getMetadata/getPlaybackState/sendCommand，只有 getController()），
 * 所以：状态从 MediaControllerInfo 的字段里读，命令走 IMediaController.sendCommand。
 *
 * 一切反射都容错：任何一步拿不到就把整条通道标记不可用，上层自动回落到
 * "横向拖播放器进度条"那条通道，不会把功能做没。
 */
object MediaSessionBridge {
    private const val TAG = "HyperZoomRing"
    private const val SERVICE = "media_session"
    private const val TAGGING = "HyperZoomRing"
    private const val CURRENT_USER = 0
    private const val SYSTEM_UID = 1000

    @Volatile private var tried = false
    @Volatile var available = false; private set
    @Volatile private var lastError = ""

    private var ism: Any? = null
    private var mGetSessions: Method? = null
    private var sessionAsInterface: Method? = null
    private var mGetController: Method? = null
    private var ctrlAsInterface: Method? = null
    private var mSendCommand: Method? = null
    private var infoFields: Map<String, Field> = emptyMap()

    class Session(
        val controller: Any,
        val packageName: String?,
        val positionMs: Long,
        val durationMs: Long,
        val state: Int,
    )

    private val actionSeekTo by lazy {
        readString("android.media.session.MediaSession", "ACTION_SEEK_TO", "android.media.action.seekTo")
    }
    private val extraPosition by lazy {
        readString("android.media.session.MediaController", "EXTRA_SEEK_TO_POSITION", "android.media.extra.SEEK_TO_POSITION")
    }
    private val extraCallingUid by lazy {
        readString("android.media.session.MediaSession", "EXTRA_CALLING_UID", "android.media.extra.CALLING_UID")
    }
    private val extraCallingPid by lazy {
        readString("android.media.session.MediaSession", "EXTRA_CALLING_PID", "android.media.extra.CALLING_PID")
    }
    private val extraCallingPackage by lazy {
        readString("android.media.session.MediaSession", "EXTRA_CALLING_PACKAGE", "android.media.extra.CALLING_PACKAGE")
    }

    private fun readString(cls: String, field: String, fallback: String): String = try {
        Class.forName(cls).getField(field).get(null) as? String ?: fallback
    } catch (e: Throwable) {
        fallback
    }

    @Synchronized
    private fun ensure(): Boolean {
        if (tried) return available
        tried = true
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, SERVICE)
                ?: return fail("media_session binder 为 null")
            val stub = Class.forName("android.media.session.ISessionManager\$Stub")
            val i = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: return fail("ISessionManager 代理为 null")
            ism = i
            mGetSessions = i.javaClass.methods.firstOrNull { it.name == "getSessions" && it.parameterCount == 3 }
                ?: return fail("没有 getSessions")

            sessionAsInterface = Class.forName("android.media.session.ISession\$Stub")
                .getMethod("asInterface", IBinder::class.java)
            mGetController = Class.forName("android.media.session.ISession")
                .methods.firstOrNull { it.name == "getController" && it.parameterCount == 0 }
                ?: return fail("没有 ISession.getController")

            val ctrlCls = Class.forName("android.media.session.IMediaController")
            ctrlAsInterface = Class.forName("android.media.session.IMediaController\$Stub")
                .getMethod("asInterface", IBinder::class.java)
            mSendCommand = ctrlCls.methods.firstOrNull { it.name == "sendCommand" }
                ?: return fail("没有 IMediaController.sendCommand")
            available = true
            Log.i(TAG, "MediaSessionBridge ready send=${sig(mSendCommand!!)}")
        } catch (e: Throwable) {
            return fail("初始化异常 ${e.javaClass.simpleName}: ${e.message}")
        }
        return available
    }

    private fun fail(why: String): Boolean {
        lastError = why
        Log.w(TAG, "MediaSessionBridge 不可用：$why（绝对 seek 关闭，走拖进度条）")
        available = false
        return false
    }

    private fun sig(m: Method): String =
        m.name + m.parameterTypes.joinToString(",", "(", ")") { it.simpleName }

    /** 把 getController() 的返回拆成 (IMediaController, info 字段表) */
    private fun unwrap(raw: Any): Pair<Any, Map<String, Field>>? {
        return try {
            if (raw is IBinder) {
                val c = ctrlAsInterface?.invoke(null, raw) ?: return null
                return c to emptyMap()
            }
            // MediaControllerInfo：字段名按 AOSP 认，取不到就按类型猜
            val cls = raw.javaClass
            val fields = cls.fields.associateBy { it.name }
            infoFields = fields
            val ctrlField = cls.fields.firstOrNull {
                it.type.name == "android.media.session.IMediaController" ||
                    IBinder::class.java.isAssignableFrom(it.type)
            } ?: return null
            val value = ctrlField.get(raw) ?: return null
            val controller = if (value is IBinder) ctrlAsInterface?.invoke(null, value) else value
            controller?.let { it to fields }
        } catch (e: Throwable) {
            Log.d(TAG, "unwrap 失败", e)
            null
        }
    }

    private fun fieldOf(info: Map<String, Field>, name: String): Any? =
        runCatching { info[name]?.get(null) }.getOrNull()

    /** 所有会话（含没在播的），由调用方挑 */
    fun sessions(): List<Session> {
        if (!ensure()) return emptyList()
        val list = try {
            @Suppress("UNCHECKED_CAST")
            (mGetSessions?.invoke(ism, null, CURRENT_USER, TAGGING)) as? List<Any> ?: return emptyList()
        } catch (e: Throwable) {
            Log.w(TAG, "getSessions 失败", e)
            emptyList()
        }
        return list.mapNotNull { raw ->
            try {
                val session = sessionAsInterface?.invoke(null, raw) ?: raw
                val infoObj = mGetController?.invoke(session) ?: return@mapNotNull null
                val (controller, fields) = unwrap(infoObj) ?: return@mapNotNull null
                val st = fieldOf(fields, "playbackState") as? PlaybackState
                val md = fieldOf(fields, "metadata") as? MediaMetadata
                Session(
                    controller = controller,
                    packageName = fieldOf(fields, "packageName") as? String,
                    positionMs = st?.position ?: 0L,
                    durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                    state = st?.state ?: PlaybackState.STATE_NONE,
                )
            } catch (e: Throwable) {
                Log.d(TAG, "session 解析失败 ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    private val NON_PLAYER_PACKAGES = setOf(
        "com.android.server.telecom",
        "android",
        "com.android.systemui",
        "com.miui.securitycenter",
    )

    /** 挑一个最像"正在放"的会话。电话/系统 UI 的会话没有可拖的进度，跳过 */
    fun pick(): Session? {
        val all = sessions().filter { it.packageName !in NON_PLAYER_PACKAGES }
        if (all.isEmpty()) return null
        return all.firstOrNull { it.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull { it.durationMs > 0 }
            ?: all.firstOrNull()
    }

    fun seekTo(session: Session, positionMs: Long): Boolean {
        if (!ensure()) return false
        val m = mSendCommand ?: return false
        return try {
            val args = Bundle().apply {
                putLong(extraPosition, positionMs.coerceAtLeast(0L))
                putInt(extraCallingUid, SYSTEM_UID)
                putInt(extraCallingPid, Process.myPid())
                putString(extraCallingPackage, session.packageName ?: "android")
            }
            when (m.parameterCount) {
                2 -> m.invoke(session.controller, actionSeekTo, args)
                3 -> m.invoke(session.controller, actionSeekTo, args, SYSTEM_UID)
                else -> m.invoke(session.controller, actionSeekTo, args, SYSTEM_UID, session.packageName ?: "android")
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "sendCommand seekTo 失败", e)
            false
        }
    }

    /** 诊断用：说清楚到底看到了什么 */
    fun describe(): String {
        if (!ensure()) return "bridge unavailable: $lastError"
        val all = sessions()
        return "sessions=${all.size} " + all.joinToString("; ") {
            "${it.packageName} state=${it.state} pos=${it.positionMs} dur=${it.durationMs}"
        }
    }
}
