// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package xyz.nextalone.hyperzoomring.ui.liquid

// Mirrors Kyant0/AndroidLiquidGlass's `CombinedBackdrop` (Apache 2.0) —
// https://github.com/Kyant0/AndroidLiquidGlass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * 把两层 backdrop 叠成一层来采样：[first] 是被压在底下的（页面那一层），[second] 是叠上去的
 * （整排 tab 的镜像行）。底栏滑块要同时折射"页面内容"和"tab 内容"，只能一次拿一个 backdrop，
 * 所以用这个把它们串起来。
 *
 * 2026-10-16 备注：这个文件被我误删过一次（当时连着滑块一起砍），这份是按
 * `build/omniring-33-cfg27.apk` 里 dexdump 出来的字段表 / 方法签名 / 行号表重建的，
 * 语义与 cfg27 一致：`isCoordinatesDependent` 取两者的或，残差沿 [first] 走，
 * `drawBackdrop` 先画 [first] 再画 [second]。
 */
class CombinedBackdrop(
    val first: Backdrop,
    val second: Backdrop,
) : Backdrop {

    override val isCoordinatesDependent: Boolean =
        first.isCoordinatesDependent || second.isCoordinatesDependent

    override val offsetResidualX: Float get() = first.offsetResidualX

    override val offsetResidualY: Float get() = first.offsetResidualY

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        with(first) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        with(second) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
    }
}

@Composable
fun rememberCombinedBackdrop(first: Backdrop, second: Backdrop): Backdrop =
    remember(first, second) { CombinedBackdrop(first, second) }
