package xyz.nextalone.hyperzoomring.hook

/**
 * 方法指纹（不含任何 Android 类型，单测里可以直接构造）。
 *
 * 为什么要把它抽出来：102 入口下我们不再用 YukiHook 的 `allMethods(...)` DSL，
 * 而是自己从 `Class.declaredMethods` 里挑出要钩的那几个。挑错一个签名，
 * 轻则表冠没反应，重则把 system_server 的输入链路整个吃掉 —— 这件事必须能在
 * 电脑上、不装机就判对错，所以匹配规则写在这一个纯对象里。
 */
data class MethodSig(
    val owner: String,
    val name: String,
    /** 参数类型的简单名，例如 ["InputEvent", "int"] */
    val paramTypes: List<String>,
    /** 返回类型的简单名，例如 "boolean" / "void" / "int" */
    val returnType: String,
) {
    companion object {
        /**
         * 一律用 `simpleName`：`int`→"int"、`android.view.InputEvent`→"InputEvent"。
         * 注意数组是 JVM 的写法（`int[]`→"int[]"，不是 Kotlin 的 "IntArray"），
         * 单测里钉住了这一点；我们的匹配规则本来也只认类名和 int。
         */
        fun simple(t: Class<*>): String = t.simpleName
        fun of(owner: String, m: java.lang.reflect.Executable): MethodSig = MethodSig(
            owner = owner,
            name = if (m is java.lang.reflect.Constructor<*>) "<init>" else m.name,
            paramTypes = m.parameterTypes.map { simple(it) },
            returnType = when (m) {
                is java.lang.reflect.Method -> simple(m.returnType)
                else -> "void"
            },
        )
    }
}

/** 取消一次调用时要往回塞的值 —— 类型错了会当场把系统方法炸掉 */
sealed class CancelValue {
    /** 这个方法的返回类型我们不知道怎么造值，宁可不取消也不能返回错类型 */
    data object Unsafe : CancelValue()
    /** 返回类型是 void：直接不 proceed 就是"吞掉这次调用" */
    data object None : CancelValue()
    data class Of(val value: Any?) : CancelValue()
}

/**
 * 要钩的目标。每个目标自带"判定要不要吞掉这次调用"的策略，
 * 语义与旧 YukiHook 路径逐条对齐（resultFalse → false / resultTrue → true / 只观察 → 不吞）。
 */
enum class HookTarget(val owner: String, val methodName: String) {
    /** InputManagerService.filterInputEvent(InputEvent, int)：只有这条是精确签名 */
    FILTER_INPUT("com.android.server.input.InputManagerService", "filterInputEvent"),

    /** InputManagerService.injectInputEvent(...)：重载有好几个，全部钩（这台 ROM 上只有这条真的会被调到） */
    INJECT_INPUT("com.android.server.input.InputManagerService", "injectInputEvent"),

    /** InputManagerService.interceptKeyBeforeQueueing(...)：只观察，不改结果 */
    INTERCEPT_KEY("com.android.server.input.InputManagerService", "interceptKeyBeforeQueueing"),

    /** com.miui.server.input.util.ShortCutActionsUtils.launchCamera(...)：挡住转环顺手开相机 */
    LAUNCH_CAMERA("com.miui.server.input.util.ShortCutActionsUtils", "launchCamera");

    /** 该目标能不能"吞掉"调用（吞掉时该返回什么） */
    fun cancelFor(sig: MethodSig, truthy: Boolean): CancelValue = when (this) {
        INTERCEPT_KEY -> CancelValue.Unsafe // 只观察，永远不吞
        else -> when (sig.returnType) {
            "void" -> CancelValue.None
            "boolean", "Boolean" -> CancelValue.Of(truthy)
            else -> CancelValue.Unsafe
        }
    }

    /** 这个方法是本目标的钩点吗（owner 两边都是全限定名，别拿简单名比 —— 那样永远比不中） */
    fun matches(sig: MethodSig): Boolean =
        sig.owner == owner && sig.name == methodName && when (this) {
            FILTER_INPUT -> sig.paramTypes.size == 2 &&
                sig.paramTypes[0] == "InputEvent" &&
                (sig.paramTypes[1] == "int" || sig.paramTypes[1] == "Integer")

            else -> true // 其余目标沿用旧 DSL 的 allMethods(name) 语义：同名全钩
        }

    companion object {
        /** 给日志和单测用的稳定串 */
        fun describe(t: HookTarget, sigs: List<MethodSig>): String =
            "${t.name} -> " + (if (sigs.isEmpty()) "没找到" else sigs.joinToString("; ") {
                "${it.name}(${it.paramTypes.joinToString(",")})=${it.returnType}"
            })
    }
}
