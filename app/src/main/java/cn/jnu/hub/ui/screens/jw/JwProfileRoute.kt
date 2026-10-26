@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package cn.jnu.hub.ui.screens.jw

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.jw.JwApi
import cn.jnu.hub.feature.jw.JwProfile
import cn.jnu.hub.feature.jw.JwProfileViewModel
import cn.jnu.hub.feature.jw.JwUiState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens
import coil.compose.AsyncImage

@Composable
fun JwProfileRoute(
    onBack: () -> Unit,
    viewModel: JwProfileViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val xh by viewModel.xh.collectAsStateWithLifecycle()
    val needStudentId by viewModel.needStudentId.collectAsStateWithLifecycle()
    val sync = rememberJwSync(viewModel)

    LaunchedEffect(Unit) { viewModel.ensureLoaded() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("个人信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 点按 = 重新查询（清缓存强制重抓）
                    // 长按 = 重置 OkHttp 探测标志：教务从 403 恢复后，用户可自助切回直连
                    IconButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.combinedClickable(
                            onClick = { viewModel.refresh() },
                            onLongClick = {
                                cn.jnu.hub.feature.jw.JwApi.resetOkHttpRejectedFlag()
                                viewModel.refresh()
                            },
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "重新查询（长按重探直连）",
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is JwUiState.Loading -> LoadingState(text = "正在获取学籍信息…")
                is JwUiState.SessionRequired -> JwSessionRequiredState(onSync = sync.request)
                is JwUiState.PermissionDenied -> JwSessionRequiredState(
                    onSync = sync.request,
                    permissionDenied = true,
                )
                is JwUiState.Error -> {
                    if (needStudentId || xh.isBlank()) {
                        Column(
                            Modifier.fillMaxSize().padding(Dimens.screenPadding),
                            verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
                        ) {
                            StudentIdGate(
                                hint = "个人信息查询需要你的学号，填写一次后本机记住",
                                onSubmit = viewModel::submitStudentId,
                            )
                        }
                    } else {
                        ErrorState(
                            message = s.message,
                            onAction = { viewModel.load() },
                        )
                    }
                }
                is JwUiState.Content -> ProfileBody(s.value)
            }
        }
    }
}

@Composable
private fun ProfileBody(profile: JwProfile) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        // 头部身份卡
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                Modifier.padding(Dimens.cardPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar()
                Spacer(Modifier.width(Dimens.lg))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = profile.XM,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Dimens.xs))
                    Text(
                        text = profile.XH,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (profile.YXDM_DISPLAY.isNotBlank()) {
                        Spacer(Modifier.height(Dimens.xs))
                        Text(
                            text = profile.YXDM_DISPLAY,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionCard(title = "学籍信息") {
            KeyValueRow("专业", profile.ZYMC)
            KeyValueRow("年级", profile.NJDM_DISPLAY)
            KeyValueRow("培养层次", profile.PYCCDM_DISPLAY)
            KeyValueRow("校区", profile.XQDM_DISPLAY)
            KeyValueRow("入学年月", profile.RXNY)
        }

        SectionCard(title = "个人信息") {
            KeyValueRow("性别", profile.XBDM_DISPLAY)
            KeyValueRow("出生日期", profile.CSRQ)
            KeyValueRow("民族", profile.MZDM_DISPLAY)
            KeyValueRow("政治面貌", profile.ZZMMDM_DISPLAY)
            KeyValueRow("身份证号", profile.maskedSfzjh)
            KeyValueRow("考生号", profile.KSH)
        }

        Text(
            text = "数据来自教务系统学籍信息，若与实际情况不符请联系教务处更新。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = Dimens.md),
        )
    }
}

/** 教务头像：GET showImageBydsForZPGL.do，Cookie 由共享 CookieJar 提供 */
@Composable
private fun Avatar(size: androidx.compose.ui.unit.Dp = 64.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size / 2),
        )
        AsyncImage(
            model = JwApi.avatarUrl(),
            contentDescription = "学生照片",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}