@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.stuit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.stuit.StuitApi
import cn.jnu.hub.feature.stuit.StuitBasicInfo
import cn.jnu.hub.feature.stuit.StuitInfoField
import cn.jnu.hub.feature.stuit.maskIdCard
import cn.jnu.hub.feature.stuit.toStuitMessage
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface InfoState {
    data object Loading : InfoState
    /** 缺少学工系统业务会话，需要用户同步一次 */
    data object NeedsSession : InfoState
    data class Error(val message: String) : InfoState
    data class Ready(val info: StuitBasicInfo) : InfoState
}

class StuitBasicInfoViewModel : ViewModel() {
    var state by mutableStateOf<InfoState>(InfoState.Loading)
        private set

    private var job: Job? = null

    init { load() }

    fun load() {
        job?.cancel()
        state = InfoState.Loading
        job = viewModelScope.launch {
            state = try {
                InfoState.Ready(StuitApi.fetchBasicInfo())
            } catch (t: Throwable) {
                // 被取消的旧请求不要覆盖新请求的状态
                if (t.isStuitCancellation()) return@launch
                if (t.isStuitSessionMissing()) InfoState.NeedsSession
                else InfoState.Error(t.stuitUserMessage())
            }
        }
    }
}

@Composable
fun StuitBasicInfoRoute(
    onBack: () -> Unit,
    vm: StuitBasicInfoViewModel = viewModel(),
) {
    val sync = rememberStuitSync { vm.load() }

    StuitScaffold(title = "学生基本信息", onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = vm.state) {
                is InfoState.Loading -> LoadingState("正在加载基本信息…")
                is InfoState.NeedsSession -> StuitNeedsSessionScreen(onSync = { sync.open() })
                is InfoState.Error -> ErrorState(
                    message = s.message,
                    onAction = { vm.load() },
                    modifier = Modifier.align(Alignment.Center),
                )
                is InfoState.Ready -> InfoContent(s.info)
            }
        }
    }

    sync.Host()
}

@Composable
private fun InfoContent(info: StuitBasicInfo) {
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
        item { AvatarCard(info) }

        val rest = info.remainingFields()
        if (rest.isNotEmpty()) {
            item { DetailCard(rest) }
        }
    }
}

@Composable
private fun AvatarCard(info: StuitBasicInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(Modifier.padding(Dimens.xl), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (info.avatarUrl != null) {
                    AsyncImage(
                        model = info.avatarUrl,
                        contentDescription = "头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = info.name.take(1).ifBlank { "生" },
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(Dimens.lg))
            Column(Modifier.weight(1f)) {
                Text(
                    text = info.name.ifBlank { "未获取到姓名" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    text = listOf(info.studentId, info.gender)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    text = info.college,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DetailCard(fields: List<StuitInfoField>) {
    SectionCard(title = "详细资料") {
        Column {
            fields.forEachIndexed { index, field ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Dimens.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = field.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(112.dp),
                    )
                    Text(
                        text = displayValue(field),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (index < fields.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                }
            }
        }
    }
}

/** 身份证号脱敏（前 3 后 4），其它字段原样展示 */
private fun displayValue(field: StuitInfoField): String =
    if (field.label.contains("身份证件号") || field.label.contains("身份证号")) {
        maskIdCard(field.value)
    } else {
        field.value
    }
