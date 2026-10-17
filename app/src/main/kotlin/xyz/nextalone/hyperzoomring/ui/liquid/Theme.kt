// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
//
// 本目录（ui/liquid）整体取自 Miuix 官方示例的 iOS 液态玻璃导航条
//   github.com/compose-miuix-ui/miuix  example/shared/src/commonMain/kotlin/component/liquid/
// 而 Miuix 那份又是 Kyant0/AndroidLiquidGlass（Apache-2.0）的改编。
// 保留原始 SPDX 头，改动仅为：改包名、把示例里的 isInDarkTheme 换成本地实现。
package xyz.nextalone.hyperzoomring.ui.liquid

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * 示例里这个函数读的是 demo 自己的 LocalColorMode（应用内主题覆盖），
 * 本模块没有做主题覆盖，所以直接跟系统深浅色走。
 */
@Composable
internal fun isInDarkTheme(): Boolean = isSystemInDarkTheme()
