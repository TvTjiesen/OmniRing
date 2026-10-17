package xyz.nextalone.hyperzoomring.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** 极角（度）。太靠近圆心时角度没意义，返回 NaN 让调用方跳过这一帧 */
private fun polarOf(x: Float, y: Float, cx: Float, cy: Float): Float {
    val dx = x - cx
    val dy = y - cy
    if (hypot(dx, dy) < 6f) return Float.NaN
    return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
}

/**
 * 可交互的表冠头图：按住能拧，松手弹簧回零位；没拧就是点击（进沉浸态）。
 *
 * 画法和沉浸态共用 [CrownDial] 那套矢量直绘 —— 之前这里拿桌面图标位图再放大，
 * 用户一眼就看出"分辨率太低、很粗糙"。
 *
 * 角度用普通状态而不是 Animatable：awaitEachGesture 里面是受限挂起作用域，
 * Animatable 的 snapTo 那种挂起函数调不出来；回弹交给外面的 LaunchedEffect。
 */
@Composable
fun InteractiveCrown(
    modifier: Modifier = Modifier,
    diameter: Dp = 92.dp,
    onClick: () -> Unit = {},
) {
    var angle by remember { mutableFloatStateOf(0f) }
    var springBack by remember { mutableStateOf(0L) }
    var press by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(springBack) {
        if (springBack == 0L) return@LaunchedEffect
        if (abs(angle) < 0.05f) {
            angle = 0f
            return@LaunchedEffect
        }
        animate(
            initialValue = angle,
            targetValue = 0f,
            animationSpec = spring(dampingRatio = 0.42f, stiffness = 220f),
        ) { v, _ -> angle = v }
    }

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        CrownDial(
            angle = angle,
            modifier = Modifier
                .size(diameter)
                .graphicsLayer {
                    val s = 1.06f * (1f + 0.05f * press)
                    scaleX = s
                    scaleY = s
                    cameraDistance = 14f * density
                    translationY = 2f.dp.toPx() * press
                }
                .pointerInput(diameter) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        press = 1f
                        var totalMoved = 0f
                        var last = polarOf(down.position.x, down.position.y, cx, cy)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val a = polarOf(change.position.x, change.position.y, cx, cy)
                            if (!a.isNaN() && !last.isNaN()) {
                                var delta = a - last
                                // 跨过 ±180° 时折回 (-180,180]，否则绕到边界会突跳一下
                                if (delta > 180f) delta -= 360f
                                if (delta < -180f) delta += 360f
                                angle += delta
                                totalMoved += abs(delta)
                            }
                            if (!a.isNaN()) last = a
                        }
                        press = 0f
                        // 没怎么转 = 用户想点开它，不是想拧
                        if (totalMoved < 6f) onClick() else springBack = System.nanoTime()
                    }
                },
        )
    }
}
