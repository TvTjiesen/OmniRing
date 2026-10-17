package xyz.nextalone.hyperzoomring.ui.liquid

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

/**
 * 顶栏"渐变纱"的竖向软窗口。
 *
 * 为什么需要它：上一版直接把 4 段不同模糊半径的矩形摞在一起，段与段之间是硬切，
 * 用户一眼就看到"分界线"。要做成真渐变，得让每一段只在自己的竖向窗口里出现，
 * 并且窗口两端是软边（smoothstep），相邻窗口互相重叠交叉淡入 —— 叠加之后
 * 模糊强度就是连续变化的，没有任何一条横线。
 *
 * 这是 AGSL（Android Runtime Shader），系统不支持时直接跳过，界面退化成不模糊，
 * 不会崩也不会画出一块黑的。
 */
private const val ALPHA_WINDOW_SHADER = """
uniform shader content;
uniform float2 res;
uniform float2 win;      // 窗口起止（0..1，相对层高度）
uniform float soft;      // 两端软边的宽度（0..1 比例）
half4 main(float2 xy) {
    half4 c = content.eval(xy);
    float y = xy.y / max(res.y, 1.0);
    float t = clamp((y - win.x) / max(win.y - win.x, 1e-4), 0.0, 1.0);
    float s = clamp(soft, 0.01, 0.5);
    float a = smoothstep(0.0, s, t) * (1.0 - smoothstep(1.0 - s, 1.0, t));
    return half4(c.rgb, c.a * a);
}
"""

/**
 * 只保留 [from, to]（0..1，相对本层高度）这一段，两端用 soft 比例的软边淡出。
 *
 * @param downscaleFactor 和 blur 一样要按降采样比例换算坐标，否则窗口会偏
 */
fun BackdropEffectScope.alphaWindow(
    from: Float,
    to: Float,
    soft: Float = 0.42f,
    downscaleFactor: Int = 1,
) {
    if (!isRuntimeShaderSupported()) return
    val sf = downscaleFactor.coerceAtLeast(1).toFloat()
    runtimeShaderEffect(
        key = "VeilAlphaWindow",
        shaderString = ALPHA_WINDOW_SHADER,
        uniformShaderName = "content",
    ) {
        setFloatUniform("res", size.width / sf, size.height / sf)
        setFloatUniform("win", from, to)
        setFloatUniform("soft", soft)
    }
}
