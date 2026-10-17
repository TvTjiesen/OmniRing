package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.R
import xyz.nextalone.hyperzoomring.util.Links
import xyz.nextalone.hyperzoomring.util.openUrl

private data class Credit(
    val name: String,
    val author: String,
    val license: String,
    val url: String,
    val useKey: Int,
)

/**
 * 致谢清单。
 *
 * 顺序按"贡献的代码量"从大到小：界面与玻璃效果占了大头，
 * 上游 HyperZoomRing 是这份代码的起点（fork 来源），放在最后一项但没漏。
 * GPL 要求的完整声明在仓库的 LICENSE / README / MODIFICATIONS.md 里，
 * 界面上不需要占一整屏。
 */
private val Credits = listOf(
    Credit(
        name = "Miuix",
        author = "compose-miuix-ui",
        license = "Apache-2.0",
        url = Links.MIUIX,
        useKey = R.string.credits_miuix_use,
    ),
    Credit(
        name = "AndroidLiquidGlass",
        author = "Kyant0",
        license = "Apache-2.0",
        url = Links.LIQUID_GLASS,
        useKey = R.string.credits_liquid_use,
    ),
    Credit(
        name = "YukiHookAPI",
        author = "HighCapable",
        license = "Apache-2.0",
        url = Links.YUKIHOOK,
        useKey = R.string.credits_yuki_use,
    ),
    Credit(
        name = "KavaRef",
        author = "HighCapable",
        license = "Apache-2.0",
        url = Links.KAVAREF,
        useKey = R.string.credits_kavaref_use,
    ),
    Credit(
        name = "Jetpack Compose",
        author = "The Android Open Source Project",
        license = "Apache-2.0",
        url = Links.COMPOSE,
        useKey = R.string.credits_compose_use,
    ),
    Credit(
        name = "LSPosed",
        author = "LSPosed Org",
        license = "GPL-3.0",
        url = Links.LSPOSED,
        useKey = R.string.credits_lsposed_use,
    ),
    Credit(
        name = "HyperZoomRing",
        author = "NextAlone",
        license = "GPL-3.0",
        url = Links.UPSTREAM,
        useKey = R.string.credits_upstream_use,
    ),
)

/** 致谢页：每一项写"用来做什么"，点一下直达仓库 */
@Composable
fun CreditsScreen(
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = pagePaddingDefault(),
) {
    val context = LocalContext.current

    Page(pagePadding = pagePadding, modifier = modifier) {
        item {
            Text(
                text = stringResource(R.string.credits_intro),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        item {
            Card {
                Column {
                    Credits.forEachIndexed { index, c ->
                        CreditRow(
                            name = c.name,
                            author = c.author,
                            license = c.license,
                            use = stringResource(c.useKey),
                            lastLine = index == Credits.lastIndex,
                            onClick = { context.openUrl(c.url) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditRow(
    name: String,
    author: String,
    license: String,
    use: String,
    lastLine: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Text(
            text = name,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = use,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = "$author · $license",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.72f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (!lastLine) {
        Hairline()
    }
}
