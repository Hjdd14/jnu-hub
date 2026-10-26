@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.stuit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.stuit.*
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface RankState {
    data object Loading : RankState
    /** 缺少学工系统业务会话，需要用户同步一次 */
    data object NeedsSession : RankState
    data class Error(val message: String) : RankState
    data class Ready(val rows: List<StuitRankRow>) : RankState
}

class StuitRankingViewModel : ViewModel() {
    var state by mutableStateOf<RankState>(RankState.Loading)
        private set

    private var job: Job? = null

    init { load() }

    fun load() {
        job?.cancel()
        state = RankState.Loading
        job = viewModelScope.launch {
            state = try {
                RankState.Ready(StuitApi.fetchRanking())
            } catch (t: Throwable) {
                // 被取消的旧请求不要覆盖新请求的状态
                if (t.isStuitCancellation()) return@launch
                if (t.isStuitSessionMissing()) RankState.NeedsSession
                else RankState.Error(t.stuitUserMessage())
            }
        }
    }
}

@Composable
fun StuitRankingRoute(
    onBack: () -> Unit,
    vm: StuitRankingViewModel = viewModel(),
) {
    val sync = rememberStuitSync { vm.load() }

    StuitScaffold(
        title = "专业排名",
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.load() }) {
                Text("刷新", style = MaterialTheme.typography.labelLarge)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = vm.state) {
                is RankState.Loading -> LoadingState("正在加载专业排名…")
                is RankState.NeedsSession -> StuitNeedsSessionScreen(onSync = { sync.open() })
                is RankState.Error -> ErrorState(
                    message = s.message,
                    onAction = { vm.load() },
                    modifier = Modifier.align(Alignment.Center),
                )
                is RankState.Ready ->
                    if (s.rows.isEmpty()) {
                        EmptyState(
                            title = "暂无排名数据",
                            message = "本学年综合测评结果尚未公布，公布后这里会显示各项专业排名",
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        RankingList(s.rows)
                    }
            }
        }
    }

    sync.Host()
}

@Composable
private fun RankingList(rows: List<StuitRankRow>) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.screenPadding,
            end = Dimens.screenPadding,
            top = Dimens.sm,
            bottom = Dimens.xxxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        items(rows) { row -> RankingCard(row) }
    }
}

@Composable
private fun RankingCard(row: StuitRankRow) {
    val subtitle = listOf(row.college, row.major, if (row.grade.isNotBlank()) row.grade + "级" else "")
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    SectionCard(title = row.template.ifBlank { "综合测评" }) {
        Column {
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.md))
            }

            // 综测总分：更醒目的一行
            row.total?.let { total ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "综测总分",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = fmtScore(total.value),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    RankBadge(total)
                }
                Spacer(Modifier.height(Dimens.md))
            }

            // 三大块
            MetricRow("基础性素养", row.basic)
            MetricRow("学业成绩", row.academic)
            MetricRow("综合素养", row.comprehensive)

            // 五育
            val five = StuitRankFiveEdu(
                moral = row.moral,
                intellectual = row.intellectual,
                physical = row.physical,
                aesthetic = row.aesthetic,
                labor = row.labor,
            ).items()
            if (five.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.sm))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(Modifier.height(Dimens.sm))
                five.forEach { (name, value) -> MetricRow(name, value) }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: RankedValue?) {
    if (value == null) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = fmtScore(value.value),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(Dimens.md))
        RankBadge(value)
    }
}

/** 「31 | 44.29%」小徽标 */
@Composable
private fun RankBadge(value: RankedValue) {
    if (value.rank == null && value.percentile == null) {
        Spacer(Modifier.width(56.dp))
        return
    }
    val text = buildString {
        value.rank?.let { append(it.toString()).append(" 名") }
        if (value.rank != null && value.percentile != null) append("  ")
        value.percentile?.let { append("%.2f%%".format(it)) }
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = Dimens.sm, vertical = 3.dp),
        )
    }
}
