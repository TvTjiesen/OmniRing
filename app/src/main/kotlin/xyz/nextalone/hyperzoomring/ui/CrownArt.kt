package xyz.nextalone.hyperzoomring.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 表冠的矢量直绘。
 *
 * 为什么不用 painterResource(ic_launcher_foreground)：那是给桌面图标用的 108dp 画布，
 * 放大到 300dp 再用 graphicsLayer 缩放，等于把已经栅格化好的图再插值一次，
 * 用户一眼就看出来"分辨率太低、很粗糙"。这里直接按画布尺寸画几何，
 * 任意大小都是原生锐度，而且和启动图标共用同一套参数（tools/gen_icon.py 里那份）。
 */
object CrownArt {
    // 与 tools/gen_icon.py 保持一致的一组几何参数（108 单位画布，圆心 54）
    const val C = 54f
    const val TEETH = 30
    const val TOOTH_R0 = 21.8f
    const val TOOTH_R1 = 27.6f
    const val TOOTH_W = 2.05f
    const val RIM_R = 28.6f
    const val RIM_W = 1.1f
    const val DIAL_R = 19.2f
    const val INNER_R = 13.4f
    const val INNER_W = 2.3f
    const val DOT_R = 2.2f

    val METAL = Color(0xFFF1F5FB)
    val RIM = Color(0xFF8FA0B8)
    val DIAL = Color(0xFF0B0F18)
    val INNER = Color(0xFFDCE6F5)
    val RED = Color(0xFFE5322B)
}

/** 把 108 单位画布里的一个点换算到当前 DrawScope */
private fun DrawScope.u(v: Float): Float = size.minDimension / 108f * v

private fun DrawScope.center(): Offset = Offset(size.width / 2f, size.height / 2f)

/**
 * 表冠本体（不含外圈刻度）。[angle] 为旋转角，单位度。
 *
 * 画序：玻璃面 → 外圈 → 滚花齿 → 内对焦环（右侧留缺口，整体读作 "C"）→ 红点。
 * 红点在齿带里正好落在两个齿之间的空档（30 齿 = 每 12°，顶点 270° 不是 12 的整数倍），
 * 所以不会被齿压住。
 */
@Composable
fun CrownDial(
    angle: Float,
    modifier: Modifier = Modifier,
    metal: Color = CrownArt.METAL,
    dial: Color = CrownArt.DIAL,
    rim: Color = CrownArt.RIM,
    inner: Color = CrownArt.INNER,
    dot: Color = CrownArt.RED,
) {
    Canvas(modifier = modifier) {
        val c = center()
        rotate(angle, pivot = c) {
            // 玻璃面
            drawCircle(color = dial, radius = u(CrownArt.DIAL_R), center = c)
            // 外圈
            drawCircle(
                color = rim,
                radius = u(CrownArt.RIM_R),
                center = c,
                style = Stroke(width = u(CrownArt.RIM_W)),
            )
            // 滚花齿：30 根径向短线，圆头
            val w = u(CrownArt.TOOTH_W)
            for (k in 0 until CrownArt.TEETH) {
                val a = Math.toRadians(360.0 * k / CrownArt.TEETH)
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                drawLine(
                    color = metal,
                    start = c + Offset(dx * u(CrownArt.TOOTH_R0), dy * u(CrownArt.TOOTH_R0)),
                    end = c + Offset(dx * u(CrownArt.TOOTH_R1), dy * u(CrownArt.TOOTH_R1)),
                    strokeWidth = w,
                    cap = StrokeCap.Round,
                )
            }
            // 内对焦环：从 20° 顺时针扫到 340°，右侧留缺口
            drawArc(
                color = inner,
                startAngle = 20f,
                sweepAngle = 320f,
                useCenter = false,
                topLeft = c - Offset(u(CrownArt.INNER_R), u(CrownArt.INNER_R)),
                size = androidx.compose.ui.geometry.Size(u(CrownArt.INNER_R) * 2f, u(CrownArt.INNER_R) * 2f),
                style = Stroke(width = u(CrownArt.INNER_W), cap = StrokeCap.Round),
            )
            // 红点（12 点方向）
            drawCircle(color = dot, radius = u(CrownArt.DOT_R), center = c + Offset(0f, -u(24.7f)))
        }
    }
}

/**
 * 表冠仪表：外圈刻度环（**不随旋转动**）+ 顶部指针 + 本体 + 背后一团柔光。
 *
 * 这是"仪器"的读法：环不动、表冠动，指针指出当前角度 —— 和真相机上
 * 变焦环对着机身刻度是一回事。之前那版只有一颗图标孤零零浮在中间，
 * 所以显得粗糙。
 *
 * @param notchProgress 当前落在第几格的相位 0..1，用来点亮对应刻度
 */
@Composable
fun CrownInstrument(
    angle: Float,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF3E7BFA),
    glow: Color = accent.copy(alpha = 0.22f),
    tickCount: Int = 72,
    notchProgress: Float = 0f,
) {
    Canvas(modifier = modifier) {
        val c = center()
        val rOuter = size.minDimension / 2f

        // ① 背后柔光：让表冠"坐"在界面上，而不是贴一张图
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow, Color.Transparent),
                center = c,
                radius = rOuter * 0.95f,
            ),
            radius = rOuter * 0.95f,
            center = c,
        )

        // ② 刻度环（固定不动）：每 6 格一根长刻度，其余短的
        val ringR = rOuter * 0.955f
        for (k in 0 until tickCount) {
            val a = Math.toRadians(360.0 * k / tickCount - 90.0)
            val long = k % 6 == 0
            val len = rOuter * if (long) 0.075f else 0.038f
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            drawLine(
                color = Color.White.copy(alpha = if (long) 0.55f else 0.26f),
                start = c + Offset(dx * ringR, dy * ringR),
                end = c + Offset(dx * (ringR - len), dy * (ringR - len)),
                strokeWidth = if (long) 2.5f.dp.toPx() else 1.5f.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // ③ 12 点方向的指针：读的是"当前转到哪一格"
        val notch = ((notchProgress % 1f) + 1f) % 1f
        val pa = Math.toRadians(360.0 * notch - 90.0)
        val pdx = cos(pa).toFloat()
        val pdy = sin(pa).toFloat()
        drawLine(
            color = accent,
            start = c + Offset(pdx * rOuter * 0.86f, pdy * rOuter * 0.86f),
            end = c + Offset(pdx * rOuter * 1.0f, pdy * rOuter * 1.0f),
            strokeWidth = 3.5f.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** 一圈虚线，用来做"可旋转"的暗示（进入沉浸态时淡入） */
@Composable
fun DashedRing(
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.18f),
    modifier2: Float = 1f,
) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f * 0.98f
        drawCircle(
            color = color,
            radius = r,
            center = center(),
            style = Stroke(
                width = 1.5f.dp.toPx() * modifier2,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 16f), 0f),
            ),
        )
    }
}
