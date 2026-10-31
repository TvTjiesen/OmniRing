package xyz.nextalone.hyperzoomring.ui.launch

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.ui.CrownArt
import xyz.nextalone.hyperzoomring.ui.CrownDial
import kotlin.math.cos
import kotlin.math.sin

/**
 * 冷启动进入动画：**表冠被拨一下，停在某一格**。
 *
 * 为什么是这个内容（不是照着相机那套光圈/快门搬）：本 App 只讲一件事 ——
 * 把那圈物理变焦环接到任意动作上。所以进入动画的主角必须是环自己：
 * 环落位 → 一圈刻度依次亮起 → 整圈单向往前转过一个角度（看得见的弧线 = 这一下拨了多少）→
 * 那一格被点亮 → 浮出名字 → 整层淡出。相机的节奏（错峰亮起、先亮再滑到目标）搬过来了，
 * 主体、图形、颜色全是我们自己的，素材一个都没搬（那是小米/徕卡的资产）。
 *
 * 只在**本进程的第一次冷启动**放（[SplashLaunch]）：从后台切回来再播一次进入动画，
 * 只会让人觉得 App 重启装死了。系统"减弱动效"开着时走 reduced 档 ——
 * 不是不动，是直接给终态再留一次极短淡出，避免闪一下空白。
 *
 * 时间一律来自 [LaunchAnim]（纯函数 + 单测钉住单调、不过冲、总时长不超 1000ms），这里只负责画。
 */
object SplashLaunch {
    /** 进程级："这一次进程里放过没有" */
    var played = false
}

/** 动画缩放为 0 = 用户开了"减弱动效" */
private fun reducedMotion(context: android.content.Context): Boolean =
    runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

@Composable
fun LaunchOverlay(title: String, subtitle: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val reduced = remember { reducedMotion(context) }
    var tMs by remember { mutableFloatStateOf(0f) }
    val base = MiuixTheme.colorScheme.background
    val accent = MiuixTheme.colorScheme.primary
    val onBase = MiuixTheme.colorScheme.onSurface

    LaunchedEffect(Unit) {
        if (reduced) {
            tMs = Float.MAX_VALUE
            delay(LaunchAnim.reducedTotalMs())
        } else {
            val t0 = android.os.SystemClock.uptimeMillis()
            while (true) {
                tMs = (android.os.SystemClock.uptimeMillis() - t0).toFloat()
                if (tMs >= LaunchAnim.totalMs()) break
                withFrameNanos { }
            }
        }
        onDone()
    }

    val tl = tMs.toLong()
    val a = if (reduced) LaunchAnim.reducedCrownAlpha() else LaunchAnim.crownAlpha(tl)
    val s = if (reduced) LaunchAnim.reducedCrownScale() else LaunchAnim.crownScale(tl)
    val rot = if (reduced) LaunchAnim.reducedCrownRotation() else LaunchAnim.crownRotation(tl)
    val label = if (reduced) LaunchAnim.reducedLabelAlpha() else LaunchAnim.labelAlpha(tl)
    val layer = if (reduced) 0f else LaunchAnim.layerAlpha(tl)
    // 转到第几格：环过了多少角度，就点亮第几格（参照里"高亮滑到目标"的那一段）
    val hot = ((rot / LaunchAnim.CROWN_SWEEP_DEG) * (LaunchAnim.TICK_COUNT - 1))
        .toInt().coerceIn(0, LaunchAnim.TICK_COUNT - 1)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = layer }
            .background(base),
        contentAlignment = Alignment.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(280.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val rOuter = size.minDimension / 2f
                // 背后一团柔光：让表冠"坐"在画面里，而不是贴着一张图
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.20f), Color.Transparent),
                        center = c,
                        radius = rOuter * 0.92f,
                    ),
                    radius = rOuter * 0.92f,
                    center = c,
                )
                val ringR = rOuter * 0.90f
                for (k in 0 until LaunchAnim.TICK_COUNT) {
                    val alpha = if (reduced) LaunchAnim.reducedTickAlpha(k) else LaunchAnim.tickAlpha(tl, k)
                    if (alpha <= 0.002f) continue
                    val ang = Math.toRadians(360.0 * k / LaunchAnim.TICK_COUNT - 90.0)
                    val dx = cos(ang).toFloat()
                    val dy = sin(ang).toFloat()
                    val hotNow = k == hot
                    val long = hotNow || k % 3 == 0
                    val len = rOuter * (if (long) 0.105f else 0.055f)
                    drawLine(
                        color = if (hotNow) accent else onBase.copy(alpha = 0.5f),
                        start = c + Offset(dx * ringR, dy * ringR),
                        end = c + Offset(dx * (ringR - len), dy * (ringR - len)),
                        strokeWidth = if (hotNow) 3.6f.dp.toPx() else 2.0f.dp.toPx(),
                        cap = StrokeCap.Round,
                        alpha = alpha,
                    )
                }
                // 拨过的角度留在画面上：这段弧就是"这一下转了多少"的直白表达
                if (rot > 0.5f) {
                    val rr = rOuter * 0.78f
                    drawArc(
                        color = accent.copy(alpha = 0.65f),
                        startAngle = -90f,
                        sweepAngle = rot,
                        useCenter = false,
                        topLeft = c - Offset(rr, rr),
                        size = Size(rr * 2f, rr * 2f),
                        style = Stroke(width = 2.4f.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            // 表冠本体：缩放落在 0.84→1 且不许过冲（单测钉着），只往一个方向转
            Box(modifier = Modifier.graphicsLayer { scaleX = s; scaleY = s }, contentAlignment = Alignment.Center) {
                CrownDial(
                    angle = rot,
                    // 进场淡入只走这里一份：每支颜色各带 alpha，别再叠一层 graphicsLayer alpha
                    // （叠了会得到"比设计值暗一半"的观感，而且两处算同一个量迟早不同步）
                    modifier = Modifier.size(136.dp),
                    dial = CrownArt.DIAL.copy(alpha = a),
                    rim = CrownArt.RIM.copy(alpha = a),
                    inner = CrownArt.INNER.copy(alpha = a),
                    metal = CrownArt.METAL.copy(alpha = a),
                    dot = CrownArt.RED.copy(alpha = a),
                )
            }
            // 名字 + 那句 tagline：进入动画也得说清"这个 App 是干什么的"，
            // 只放一个图标会让人以为是相机的启动页（那是别人家的东西）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = onBase.copy(alpha = label * 0.9f),
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = onBase.copy(alpha = label * 0.58f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
