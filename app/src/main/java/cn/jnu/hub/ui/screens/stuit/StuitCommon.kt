@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.stuit

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.jnu.hub.ui.theme.Dimens

/**
 * 学工四个页面共用的 Scaffold。
 * 统一遵守项目约定：contentWindowInsets = WindowInsets(0,0,0,0)，TopAppBar 吃状态栏。
 */
@Composable
fun StuitScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                windowInsets = WindowInsets.statusBars,
            )
        },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/** 标签 + 值 的一行，用于详情信息列表 */
@Composable
fun StuitInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Dimens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value.ifBlank { "-" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 小圆角标记（及格 / 重修 / 缓考等） */
@Composable
fun StuitTag(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = Dimens.sm, vertical = 3.dp),
        )
    }
}


/**
 * 学工四页共用的「需要同步会话」判定。
 *
 * 四个页面都要处理同一个场景：stuit 没有自己的业务会话时，请求会被踢回 CAS。
 * 与其在四个 ViewModel 里各写一遍，不如统一在这里判定，
 * 保证四个页面给出的引导文案和重试行为完全一致。
 */
fun Throwable.isStuitSessionMissing(): Boolean {
    if (this is cn.jnu.hub.feature.stuit.StuitException.SessionExpired) return true
    val msg = message.orEmpty()
    return msg.contains("同步一次登录状态") || msg.contains("需要先同步")
}

/** 学工页面统一的错误文案（区分「缺会话」与「真失败」） */
fun Throwable.stuitUserMessage(): String = when {
    isStuitSessionMissing() -> "学工系统需要先同步一次登录状态"
    this is cn.jnu.hub.feature.stuit.StuitException -> message ?: "加载失败"
    else -> message?.takeIf { it.isNotBlank() } ?: "加载失败，请稍后重试"
}

/**
 * 协程取消不算错误。
 *
 * ViewModel 里切学年/重试时会 job.cancel() 掉上一个请求，取消会以
 * CancellationException 的形式抛进 catch (t: Throwable)。如果不识别它，
 * 旧请求的取消会覆盖新请求已经写好的状态，界面就会闪一下莫名其妙的报错。
 * 出现这种情况时返回 true，调用方应当直接 return，不要改状态。
 */
fun Throwable.isStuitCancellation(): Boolean =
    this is kotlinx.coroutines.CancellationException ||
        (this is java.util.concurrent.CancellationException)

/**
 * 学工四页共用的同步弹层宿主。
 *
 * 用法（四个页面完全一致）：
 * 1. Route 顶部：val sync = rememberStuitSync { vm.load() }
 * 2. 状态分支：is XxxState.NeedsSession -> StuitNeedsSessionScreen(onSync = { sync.open() })
 * 3. Route 末尾：sync.Host()  —— 渲染同步弹层，成功后自动回调重试
 *
 * 注意：KDoc 正文里不要出现「星号 + 斜杠 + 星号」这类连续字符，
 * Kotlin 会把它当成块注释开头，把后面的代码整体吞掉。
 */
@Stable
class StuitSyncController(
    private val onSynced: () -> Unit,
) {
    var showDialog by mutableStateOf(false)
        private set

    fun open() {
        showDialog = true
    }

    fun close() {
        showDialog = false
    }

    @Composable
    fun Host() {
        if (showDialog) {
            StuitSyncDialog(
                onSynced = {
                    showDialog = false
                    onSynced()
                },
                onDismiss = { showDialog = false },
            )
        }
    }
}

@Composable
fun rememberStuitSync(onSynced: () -> Unit): StuitSyncController {
    val cb by rememberUpdatedState(onSynced)
    return remember { StuitSyncController { cb() } }
}
/** 分数两位小数，空值显示 "-" */
fun fmtScore(value: Double?, digits: Int = 2): String {
    if (value == null || value.isNaN() || value.isInfinite()) return "-"
    return "%.${digits}f".format(value)
}
