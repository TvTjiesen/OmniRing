package xyz.nextalone.hyperzoomring.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.R

/** 极角（度）。太靠近圆心时角度没意义，返回 NaN 让调用方跳过这一帧 */
private fun polar(x: Float, y: Float, cx: Float, cy: Float): Float {
    val dx = x - cx
    val dy = y - cy
    if (hypot(dx, dy) < 6f) return Float.NaN
    return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
}

/** 一次环事件对应的旋转角度（度）。快转步长更大，和物理环的手感一致 */
/**
 * 按"这一拍真实转了多少传感器单位"换算表盘角度 —— 同步的关键在这里。
 *
 * 之前是按手势枚举给固定值（慢转 12°、快转 30°）：手势只有两档，而你手上的幅度是连续的，
 * 所以"刻度永远追不上真实转动"（他 2026-10-16 报的）。改成 单位数 × 每单位度数，
 * 每单位度数默认按"物理环一整圈 ≈ 6000 单位"给（360/6000=0.06），
 * 想快想慢用 adb 热调：settings put global hzr_stage_deg_per_unit 0.12
 */
fun stageAngleDeltaUnits(direction: Int, units: Int, degPerUnit: Float): Float =
    if (direction == 0 || units == 0) 0f else direction * units * degPerUnit

/** 每单位传感器读数换多少度：adb 热调 `settings put global hzr_stage_deg_per_unit 0.12` */
@Volatile
private var degPerUnitCache = 0f

@Volatile
private var degPerUnitAt = 0L

fun stageDegPerUnit(context: android.content.Context): Float {
    val now = System.currentTimeMillis()
    if (now - degPerUnitAt > 2000L) {
        degPerUnitAt = now
        degPerUnitCache = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver, "hzr_stage_deg_per_unit", 0.06f
            )
        }.getOrDefault(0.06f)
    }
    return degPerUnitCache
}

fun stageAngleDelta(gesture: String): Float = when (gesture) {
    "CW_SLOW" -> 12f
    "CW_FAST" -> 30f
    "CCW_SLOW" -> -12f
    "CCW_FAST" -> -30f
    else -> 0f
}

private fun mix(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

/**
 * 表冠沉浸态。
 *
 * 上一版被说"粗糙、图标分辨率低"，两个原因都是真的：
 *  - 我用 painterResource 拿桌面图标那份 108dp 矢量，再 graphicsLayer 放大 1.06 倍，
 *    等于把已经栅格化好的图二次插值 → 糊；
 *  - 画面里只有一颗孤零零的图标浮在中间，没有任何"仪表"语境。
 *
 * 这一版：
 *  - 表冠改成**矢量直绘**（CrownArt，和启动图标同一组几何参数），任意尺寸原生锐度；
 *  - 外面套一圈**固定不动的刻度环 + 指针**：环不动、表冠动，读的就是真相机上
 *    "变焦环对着机身刻度"那件事；
 *  - 背后一团柔光让表冠"坐"在界面上；下方给出名字、版本、当前角度读数；
 *  - 进入时从头部位置放大飞到位（弹簧），退出点任意处或返回键；
 *  - 环事件驱动旋转（弹簧跟随，一格一格也不生硬），每跨 30° 一次段落脉冲；
 *  - 5 秒收不到环事件 → 提示原因，并允许手指直接转（降级不失功能）。
 */
@Composable
fun CrownStage(
    visible: Boolean,
    targetAngle: Float,
    ringAlive: Boolean,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    onDragAngle: (Float) -> Unit,
) {
    val shown = remember { Animatable(targetAngle) }
    val enter = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var lastNotch by remember { mutableFloatStateOf((targetAngle / 30f).roundToInt().toFloat()) }

    LaunchedEffect(targetAngle, visible) {
        if (!visible) return@LaunchedEffect
        scope.launch {
            shown.animateTo(targetAngle, spring(dampingRatio = 0.72f, stiffness = 420f, visibilityThreshold = 0.1f))
        }
        val notch = (targetAngle / 30f).roundToInt().toFloat()
        if (notch != lastNotch) {
            lastNotch = notch
            scope.launch {
                pulse.snapTo(1f)
                pulse.animateTo(0f, spring(dampingRatio = 0.38f, stiffness = 700f))
            }
        }
    }

    LaunchedEffect(visible) {
        enter.animateTo(if (visible) 1f else 0f, spring(dampingRatio = 0.86f, stiffness = 520f))
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(160)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { RectangleShape },
                            effects = { blur(40.dp.toPx(), 40.dp.toPx()) },
                            onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.30f)) },
                        )
                    } else {
                        Modifier.background(Color.Black.copy(alpha = 0.52f))
                    }
                )
                // 只有"点一下"才退出。上一版是 awaitFirstDown 立刻 onDismiss ——
                // 任何一次按下（包括想滚动、想在表冠上起手转）都会当场关掉沉浸态，
                // 用户看到的"点进去滚动失效、直接退出来"就是这一行。
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            contentAlignment = Alignment.Center,
        ) {
            val e = enter.value
            val k = 1f + 0.03f * pulse.value
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = e
                    val s = mix(0.62f, 1f, e)
                    scaleX = s
                    scaleY = s
                    translationY = mix(-300f, 0f, e)
                },
            ) {
                // 仪表：刻度环与指针固定，表冠本体随角度转
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(320.dp)) {
                    CrownInstrument(
                        angle = shown.value,
                        notchProgress = shown.value / 360f,
                        accent = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(320.dp),
                    )
                    CrownDial(
                        angle = shown.value,
                        modifier = Modifier
                            .size(236.dp)
                            .graphicsLayer {
                                scaleX = k
                                scaleY = k
                            }
                            // 手指也能转：环没通时的降级路径。位移极小当点击，交给外层退出
                            .pointerInput(visible) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    var last = polar(down.position.x, down.position.y, cx, cy)
                                    var moved = 0f
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) {
                                            // 抬手也要吃掉这次变化：转一圈很可能回到起点，
                                            // 外层只看"按下和抬起在同一点"就会判成点击 → 一转就退出
                                            if (moved > 0f) ch.consume()
                                            break
                                        }
                                        val a = polar(ch.position.x, ch.position.y, cx, cy)
                                        if (!a.isNaN() && !last.isNaN()) {
                                            var d = a - last
                                            if (d > 180f) d -= 360f
                                            if (d < -180f) d += 360f
                                            moved += abs(d)
                                            onDragAngle(d)
                                        }
                                        if (!a.isNaN()) last = a
                                        // 真转过就要让外层知道"这不是点击"（Main 通道里子节点先于父节点）
                                        if (moved > 0f) ch.consume()
                                    }
                                }
                            },
                    )
                }

                Text(
                    text = "${((shown.value % 360f) + 360f).roundToInt()}°",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = stringResource(R.string.app_name_full),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    text = stringResource(R.string.app_tagline),
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.66f),
                    modifier = Modifier.padding(top = 6.dp),
                )
                Box(modifier = Modifier.padding(top = 26.dp)) {
                    Box(
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (ringAlive) R.string.stage_hint_ring else R.string.stage_hint_fallback
                            ),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = if (ringAlive) 0.9f else 0.78f),
                        )
                    }
                }
            }
        }
    }
}
