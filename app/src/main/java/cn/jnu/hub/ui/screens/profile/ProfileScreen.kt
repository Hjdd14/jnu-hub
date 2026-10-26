package cn.jnu.hub.ui.screens.profile

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import cn.jnu.hub.core.network.HttpClient
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.jnu.hub.ui.navigation.Routes
import cn.jnu.hub.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 真实会话状态：之前这里把「未登录」写死了，导致明明登录了也一直显示未登录
    var loggedIn by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var userName by remember { mutableStateOf<String?>(null) }

    suspend fun refreshState() {
        checking = true
        loggedIn = runCatching {
            cn.jnu.hub.core.session.SessionManager.probeCas()
        }.getOrDefault(false)
        userName = runCatching {
            HttpClient.cookieJar().snapshot()
                .firstOrNull { it.name.equals("JUID", true) || it.name.equals("jnuUser", true) }
                ?.value
        }.getOrNull()
        checking = false
    }

    // 每次进入页面都重新探一次（会话可能刚被刷新）
    LaunchedEffect(Unit) { refreshState() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            onNavigate(Routes.SESSION_DEBUG)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("我的", style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                windowInsets = WindowInsets.statusBars,
                modifier = Modifier.windowInsetsPadding(WindowInsets(0, 0, 0, 0)),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Dimens.screenPadding,
                end = Dimens.screenPadding,
                bottom = Dimens.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
        ) {
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(Dimens.cardPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(Dimens.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    checking -> "检查登录状态…"
                                    loggedIn -> "已登录"
                                    else -> "未登录"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                when {
                                    checking -> "正在读取各系统会话"
                                    loggedIn -> userName?.takeIf { it.isNotBlank() }
                                        ?: "统一身份认证会话有效"
                                    else -> "登录后可同步水电、课程、体测数据"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                launcher.launch(
                                    Intent(context, cn.jnu.hub.feature.login.LoginActivity::class.java),
                                )
                            },
                        ) { Text(if (loggedIn) "重新登录" else "登录") }
                    }
                }
            }
            item {
                SettingsGroup("账号") {
                    SettingRow("登录状态与会话", "查看各系统剩余时长") { onNavigate(Routes.SESSION_DEBUG) }
                    SettingRow("师资查询", "搜索教师信息") { onNavigate(Routes.FACULTY) }
                }
            }
            item {
                SettingsGroup("外观") {
                    SettingRow("深色模式", "跟随系统") { onNavigate(Routes.SESSION_DEBUG) }
                }
            }
            item {
                SettingsGroup("关于") {
                    SettingRow("版本", "1.0.0") { }
                    SettingRow("免责声明", "非官方客户端") { }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Dimens.xs, bottom = Dimens.sm),
        )
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(vertical = Dimens.xs)) { content() }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = Dimens.cardPadding, vertical = Dimens.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}