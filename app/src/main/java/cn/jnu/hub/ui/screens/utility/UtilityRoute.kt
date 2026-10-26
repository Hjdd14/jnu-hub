@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.utility

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jnu.hub.feature.utility.BillItem
import cn.jnu.hub.feature.utility.EnergyTypes
import cn.jnu.hub.feature.utility.PaymentRecord
import cn.jnu.hub.feature.utility.SubsidyItem
import cn.jnu.hub.feature.utility.UtilityCasProbe
import cn.jnu.hub.feature.utility.UtilityCredentialStore
import cn.jnu.hub.feature.utility.UtilityException
import cn.jnu.hub.feature.utility.UtilityIdentityResolver
import cn.jnu.hub.feature.utility.UtilityTicketBridge
import cn.jnu.hub.feature.utility.UtilityRepository
import cn.jnu.hub.feature.utility.UtilitySnapshot
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.QuotaBar
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.ChartColdWater
import cn.jnu.hub.ui.theme.ChartElectric
import cn.jnu.hub.ui.theme.ChartHotWater
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────── 状态 ─────────────────────────

data class UtilityUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val sessionExpired: Boolean = false,
    val snapshot: UtilitySnapshot? = null,
    val records: List<PaymentRecord> = emptyList(),
    val recordsTotal: Int = 0,
    val recordsLoading: Boolean = false,
    val recordsError: String? = null,
    /**
     * 失败原因：CAS 真的失效，需要用户去登录。
     * 只有在 BB 也打不开时才会为 true —— 否则不该让用户点「去登录」绕圈子。
     */
    val casExpired: Boolean = false,
    /**
     * 失败原因：CAS 有效，但缺水电户号（jnu-lp Cookie 不存在）。
     * 此时应引导「同步水电账号」或「手动填写户号」，而不是去登录。
     */
    val needsIdentity: Boolean = false,
    /** 已生效的户号（用于页面展示） */
    val activeAccount: String? = null,
)


class UtilityViewModel : ViewModel() {

    private val _state = MutableStateFlow(UtilityUiState())
    val state: StateFlow<UtilityUiState> = _state.asStateFlow()

    private var loadedOnce = false

    /** 页面 context，用于读取本地户号 / Cookie */
    private var appContext: Context? = null

    /** 首次进入时注入 Context，然后自动加载 */
    fun attach(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (!loadedOnce) load()
    }

    fun load() {
        val first = !loadedOnce
        _state.value = _state.value.copy(
            loading = first,
            refreshing = !first,
            error = null,
            sessionExpired = false,
            casExpired = false,
            needsIdentity = false,
        )
        viewModelScope.launch {
            runCatching { UtilityRepository.loadSnapshot(appContext) }
                .onSuccess { snapshot ->
                    loadedOnce = true
                    _state.value = _state.value.copy(
                        loading = false,
                        refreshing = false,
                        error = null,
                        sessionExpired = false,
                        casExpired = false,
                        needsIdentity = false,
                        activeAccount = snapshot.account.accountName,
                        snapshot = snapshot,
                    )
                    if (first) loadRecords(reset = true)
                }
                .onFailure { e ->
                    handleFailure(e)
                }
        }
    }

    /**
     * 失败分流 —— 这是之前「点去登录绕圈子」的根源。
     *
     * 水电查询失败有两种完全不同的原因，必须区分：
     * 1. CAS 真的失效（BB 也打不开）→ 才显示「去登录」
     * 2. CAS 有效但缺户号（jnu-lp 不存在）→ 显示「同步水电账号」+「手动填写」
     *
     * 过去用「有没有 jnu-lp」来猜，导致有 CAS 会话的用户被误导去登录，
     * 而登录完 CAS 仍然不会写 jnu-lp，形成死循环。现在改为实际探测 BB。
     */
    private suspend fun handleFailure(e: Throwable) {
        Log.d("UtilityRoute", "handleFailure type=" + e.javaClass.simpleName + " msg=" + e.message)

        // 只有「确实不是会话问题」的失败才直接显示错误文案，跳过探测。
        // 历史 bug：这里原本写 `if (e !is SessionExpired) return`，
        // 导致 Business / Parse 等失败永远不会进入探测分支，
        // 日志里一条 UtilityCasProbe 都看不到，用户也拿不到正确引导。
        val isSessionish = when (e) {
            is UtilityException.SessionExpired -> true
            is UtilityException.Business -> true
            is UtilityException.Network -> false
            is UtilityException.Parse -> false
            else -> true
        }

        if (!isSessionish) {
            _state.value = _state.value.copy(
                loading = false,
                refreshing = false,
                error = friendlyMessage(e),
            )
            return
        }

        // 会话类失败：实际探测 CAS 到底还在不在
        val casState = UtilityCasProbe.check()

        // 关键：只有「明确被踢回 CAS」才显示去登录。
        // UNKNOWN（网络异常等）一律按「缺户号」引导 —— 宁可让用户走同步/手填，
        // 也不能让他在「去登录 → 秒关 → 回来还是这页」之间打转。
        val showLogin = casState == UtilityCasProbe.State.LOGGED_OUT
        val showIdentity = !showLogin

        _state.value = _state.value.copy(
            loading = false,
            refreshing = false,
            sessionExpired = showLogin,
            casExpired = showLogin,
            needsIdentity = showIdentity,
            error = when {
                showLogin -> friendlyMessage(e)
                else -> if (casState == UtilityCasProbe.State.LOGGED_IN) {
                    "已登录统一认证，但还没获取到你的水电户号"
                } else {
                    "没能确认统一认证状态，也没获取到水电户号"
                }
            },
        )
    }

    /** 刚捕获到 CAS 票据，立即用它登录并刷新 */
    fun retryWithTicket() {
        loadedOnce = false
        load()
    }

    /**
     * 从「同步水电账号」的网页返回，但没有捕获到票据。
     * 此时仍刷新一次：网页端可能已经把 jnu-lp 写入 Cookie，
     * 若依旧失败，引导页会继续显示，用户可改用手动填写。
     */
    fun onReturnedWithoutTicket() {
        loadedOnce = false
        load()
    }

    /** 手动保存户号并重试 */
    fun submitManualAccount(userName: String, customerId: String) {
        val ctx = appContext ?: return
        if (userName.isBlank()) return
        UtilityCredentialStore(ctx).saveAccount(userName, customerId)
        loadedOnce = false
        load()
    }

    fun loadRecords(reset: Boolean = false) {
        val s = _state.value
        if (s.recordsLoading) return
        val startIdx = if (reset) 0 else s.records.size
        if (!reset && startIdx >= s.recordsTotal && s.recordsTotal > 0) return
        _state.value = s.copy(recordsLoading = true, recordsError = null)
        viewModelScope.launch {
            runCatching { UtilityRepository.loadPaymentRecords(appContext, startIdx, PAGE_SIZE) }
                .onSuccess { (list, total) ->
                    val cur = _state.value
                    val merged = if (reset) list else cur.records + list.filter { new ->
                        cur.records.none { it.logTime == new.logTime && it.value == new.value }
                    }
                    _state.value = cur.copy(
                        recordsLoading = false,
                        recordsError = null,
                        records = merged,
                        recordsTotal = total,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        recordsLoading = false,
                        recordsError = friendlyMessage(e),
                    )
                }
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}

private fun friendlyMessage(e: Throwable): String = when (e) {
    // 保守措辞：见 casExpired 分支的说明，不对具体失效原因下结论
    is UtilityException.SessionExpired -> "水电系统未识别到登录状态，请重新登录统一认证后再试"
    is UtilityException -> e.message ?: "加载失败"
    else -> e.message?.takeIf { it.isNotBlank() } ?: "加载失败，请稍后重试"
}

/**
 * 水电查询主页面。
 * 只读：查余额 / 补贴 / 账单 / 用量；不放缴费按钮（缴费是独立入口）。
 *
 * @param onBack 返回
 * @param onGoPayment 点击底部小字「充值请前往『水电缴费』」时回调
 * @param onLogin 会话过期时拉起登录
 */
@Composable
fun UtilityRoute(
    onBack: () -> Unit,
    onGoPayment: () -> Unit = {},
    onLogin: () -> Unit = {},
    onOpenWeb: ((String, String) -> Unit)? = null,
    viewModel: UtilityViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // 首次进入注入 Context 并自动加载（户号需要读本地存储 / Cookie）
    LaunchedEffect(Unit) { viewModel.attach(context) }

    // 从「同步水电账号」的 WebView 返回时的处理。
    // 用 firstResume 跳过首次进入，避免一进页面就被当成「从网页返回」。
    var firstResume by remember { mutableStateOf(true) }
    // 记录用户是否刚点过「同步水电账号」，只有这种情况才在返回时自动刷新
    var justOpenedSync by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (firstResume) {
                    firstResume = false
                } else if (justOpenedSync) {
                    justOpenedSync = false
                    if (UtilityTicketBridge.hasTicket) {
                        // 捕获到票据 → 立即换户号并刷新
                        viewModel.retryWithTicket()
                    } else {
                        // 从网页回来了但没拿到票据：刷新一次（可能已写入 jnu-lp），
                        // 若仍失败，引导页会继续显示，用户可改用手动填写
                        viewModel.onReturnedWithoutTicket()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("水电查询") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.snapshot != null) {
                        TextButton(onClick = { viewModel.load() }, enabled = !state.refreshing) {
                            Text(if (state.refreshing) "刷新中" else "刷新")
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingState("正在读取水电数据…")

                state.error != null && state.snapshot == null -> {
                    when {
                        // —— 情况一：CAS 确实探测为失效，才让用户去登录 ——
                        //
                        // 文案刻意保守：我们对「水电侧为何判定无会话」只能推断
                        // （GetUserInfo 的 ErrorMessage:"5" 含义未从网页端抓包确认），
                        // 所以不写死「统一认证已失效」这种确定性结论，避免误导用户。
                        state.casExpired -> ErrorState(
                            title = "未识别到登录状态",
                            message = "水电系统未识别到登录状态，请重新登录统一认证后再试。",
                            actionText = "去登录",
                            onAction = onLogin,
                        )

                        // —— 情况二：CAS 有效，只是缺水电户号 ——
                        // 这里绝不能放「去登录」：CAS 登录不会写入 jnu-lp，
                        // 用户点了只会被弹到 Blackboard 再回来，原地打转。
                        // 缺户号：引导同步或手填
                        state.needsIdentity -> IdentityHelperScreen(
                            message = state.error.orEmpty(),
                            onOpenWeb = onOpenWeb,
                            onSyncStarted = { justOpenedSync = true },
                            onRetry = { viewModel.load() },
                            onSubmitManual = { name, id -> viewModel.submitManualAccount(name, id) },
                        )

                        else -> ErrorState(
                            message = state.error.orEmpty(),
                            actionText = "重试",
                            onAction = { viewModel.load() },
                        )
                    }
                }

                state.snapshot != null -> {
                    UtilityContent(
                        snapshot = state.snapshot!!,
                        records = state.records,
                        recordsTotal = state.recordsTotal,
                        recordsLoading = state.recordsLoading,
                        recordsError = state.recordsError,
                        onLoadMoreRecords = { viewModel.loadRecords(reset = false) },
                        onRetryRecords = { viewModel.loadRecords(reset = true) },
                        onGoPayment = onGoPayment,
                    )
                }

                else -> EmptyState("暂无数据")
            }
        }
    }
}

/**
 * 户号识别失败时的引导页。
 *
 * 注意这里**刻意不放「去登录」**：
 * 用户此时 CAS 通常已经登录，而 CAS 登录不会写入 jnu-lp Cookie，
 * 因此去登录只会被弹到 Blackboard 再回来，问题依旧 —— 这就是之前的死循环。
 *
 * 正确做法是两条：
 * 1. 「同步水电账号」：打开水电站点，捕获 CAS 回跳的 ?ticket= 再换户号
 * 2. 「手动填写户号」：兜底，用户自己填 T 开头的户号，本地记住
 */
@Composable
private fun IdentityHelperScreen(
    message: String,
    onOpenWeb: ((String, String) -> Unit)?,
    onSyncStarted: () -> Unit,
    onRetry: () -> Unit,
    onSubmitManual: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var accountName by rememberSaveable { mutableStateOf("") }
    var customerId by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
    ) {
        Spacer(Modifier.height(Dimens.xxl))

        Text(
            "还差一步：绑定水电户号",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Dimens.sm))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.xs))
        Text(
            "统一认证已经登录好了，不需要重新登录 —— 只缺水电系统那边的户号信息。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Dimens.lg))

        // —— 方案一：去网页端同步 ——
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(Dimens.cardPadding)) {
                Text(
                    "方式一：同步水电账号",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    "点下面的按钮会打开水电站点。统一认证已登录时站点会带票据回跳，"
                        + "App 自动截获票据并换取你的水电户号，成功后本页自动加载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.md))
                Button(
                    onClick = {
                        UtilityTicketBridge.clear()
                        onSyncStarted()
                        val url = "https://pynhcx.jnu.edu.cn/ibsjnuweb/"
                        // 必须用 intentForTicket：开启票据捕获模式
                        runCatching {
                            context.startActivity(
                                cn.jnu.hub.feature.web.WebPageActivity.intentForTicket(
                                    context, url, "水电系统",
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("同步水电账号")
                }
                Spacer(Modifier.height(Dimens.sm))
                TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("已同步过，重试")
                }
            }
        }

        Spacer(Modifier.height(Dimens.cardSpacing))

        // —— 方案二：手动填户号 ——
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(Dimens.cardPadding)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "方式二：手动填写水电户号",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起" else "自动识别失败？手动填写")
                    }
                }

                if (expanded) {
                    Spacer(Modifier.height(Dimens.sm))

                    // 大字提示：这是用户唯一必填的东西
                    Text(
                        "请输入你的水电户号（以 T 开头，如 T00001）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Dimens.xs))
                    Text(
                        "在宿舍水表/电表卡上可以看到，或询问宿管。填一次就会记住。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Dimens.md))

                    OutlinedTextField(
                        value = accountName,
                        onValueChange = { accountName = it.trim().uppercase() },
                        label = { Text("水电户号") },
                        placeholder = { Text("T00001") },
                        singleLine = true,
                        isError = accountName.isNotBlank() && !accountName.startsWith("T"),
                        supportingText = if (accountName.isNotBlank() && !accountName.startsWith("T")) {
                            { Text("户号通常以大写字母 T 开头") }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Dimens.sm))
                    OutlinedTextField(
                        value = customerId,
                        onValueChange = { customerId = it.filter { c -> c.isDigit() } },
                        label = { Text("客户号（选填）") },
                        placeholder = { Text("00000000") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Dimens.md))
                    Button(
                        onClick = { onSubmitManual(accountName, customerId) },
                        enabled = accountName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("保存并使用")
                    }
                }
            }
        }

        Spacer(Modifier.height(Dimens.xxl))
    }
}


@Composable
private fun UtilityContent(
    snapshot: UtilitySnapshot,
    records: List<PaymentRecord>,
    recordsTotal: Int,
    recordsLoading: Boolean,
    recordsError: String?,
    onLoadMoreRecords: () -> Unit,
    onRetryRecords: () -> Unit,
    onGoPayment: () -> Unit,
) {
    var selectedEnergy by rememberSaveable { mutableIntStateOf(EnergyTypes.ELECTRIC) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.screenPadding,
            end = Dimens.screenPadding,
            top = Dimens.sm,
            bottom = Dimens.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        // ── 余额 ──
        item { BalanceCard(snapshot) }

        if (snapshot.warnings.isNotEmpty()) {
            item { WarningCard(snapshot.warnings) }
        }

        // ── 补贴 ──
        item { SubsidyCard(snapshot.subsidies) }

        // ── 本期账单 ──
        item { BillCard(snapshot.bills) }

        // ── 每日用量折线图 ──
        item {
            SectionCard(
                title = "每日用量趋势",
                actionText = null,
            ) {
                EnergyTabs(selected = selectedEnergy, onSelect = { selectedEnergy = it })
                Spacer(Modifier.height(Dimens.md))
                val series = snapshot.dailyUsage.firstOrNull { it.energyType == selectedEnergy }
                UsageLineChart(
                    points = series?.points.orEmpty(),
                    unit = EnergyTypes.defaultUnit(selectedEnergy),
                    color = energyColor(selectedEnergy),
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
            }
        }

        // ── 月度消费柱状图 ──
        item {
            SectionCard(title = "月度消费（近 12 个月）") {
                val series = snapshot.monthly.firstOrNull { it.energyType == selectedEnergy }
                MonthlyBarChart(
                    points = series?.points.orEmpty(),
                    unit = EnergyTypes.defaultUnit(selectedEnergy),
                    color = energyColor(selectedEnergy),
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                )
                Spacer(Modifier.height(Dimens.sm))
                Text(
                    text = "单位：${EnergyTypes.defaultUnit(selectedEnergy)} / 月",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 充值记录 ──
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = Dimens.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "充值记录",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (recordsTotal > 0) {
                    Text(
                        "共 $recordsTotal 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        when {
            recordsError != null && records.isEmpty() -> item {
                ErrorState(
                    title = "充值记录加载失败",
                    message = recordsError,
                    onAction = onRetryRecords,
                )
            }

            records.isEmpty() && !recordsLoading -> item {
                EmptyState("暂无充值记录")
            }

            else -> items(records, key = { "${it.logTime}-${it.value}-${it.paymentType}" }) { record ->
                PaymentRecordRow(record)
            }
        }

        if (records.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Dimens.md),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    when {
                        recordsLoading -> CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )

                        records.size < recordsTotal -> TextButton(onClick = onLoadMoreRecords) {
                            Text("加载更多")
                        }

                        else -> Text(
                            "没有更多了",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── 底部小字 ──
        item {
            Box(Modifier.fillMaxWidth().padding(top = Dimens.sm), contentAlignment = Alignment.Center) {
                Text(
                    text = "充值请前往『水电缴费』",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onGoPayment)
                        .padding(horizontal = Dimens.md, vertical = Dimens.sm),
                )
            }
        }
    }
}

@Composable
private fun BalanceCard(snapshot: UtilitySnapshot) {
    val account = snapshot.account
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (snapshot.isLowBalance) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Dimens.cardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "账户余额",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Dimens.sm))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "¥",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp, end = 2.dp),
                )
                Text(
                    text = account.balance?.let { "%.2f".format(it) } ?: "--",
                    style = MaterialTheme.typography.displaySmall,
                    color = if (snapshot.isLowBalance) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (snapshot.isLowBalance) {
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    "余额低于 %.0f 元，记得及时充值".format(UtilitySnapshot.LOW_BALANCE_THRESHOLD),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!account.accountName.isNullOrBlank()) {
                Spacer(Modifier.height(Dimens.md))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        "账号 ${account.accountName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Dimens.md, vertical = Dimens.xs),
                    )
                }
            }
        }
    }
}

@Composable
private fun WarningCard(warnings: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            warnings.forEach { w ->
                Text(
                    "· $w",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SubsidyCard(subsidies: List<SubsidyItem>) {
    SectionCard(title = "补贴余量") {
        if (subsidies.isEmpty()) {
            Text(
                "暂无补贴数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            subsidies.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(Dimens.md))
                // QuotaBar 的 used 语义是「已用」，填充比例 = used/total，
                // 右侧文字 = (total - used)/total。补贴要展示「剩余」，所以传 used = 总额 - 剩余：
                // 于是条形填充 = 剩余占比，文字 = 剩余 / 总额。
                QuotaBar(
                    label = item.displayName,
                    used = (item.totalValue - item.availableValue).coerceIn(0.0, item.totalValue),
                    total = item.totalValue,
                    unit = item.itemUnit,
                    color = energyColor(item.itemType),
                )
            }
        }
    }
}

@Composable
private fun BillCard(bills: List<BillItem>) {
    SectionCard(title = "本期账单") {
        if (bills.isEmpty()) {
            Text(
                "本期暂无账单",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            bills.forEachIndexed { index, bill ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = Dimens.md),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(energyColor(bill.energyType)),
                    )
                    Spacer(Modifier.width(Dimens.sm))
                    Text(
                        bill.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = buildString {
                            append(bill.energyValue?.let { "%.2f".format(it) } ?: "--")
                            append(" ")
                            append(EnergyTypes.defaultUnit(bill.energyType))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Dimens.md))
                    Text(
                        bill.chargeValue?.let { "¥%.2f".format(it) } ?: "--",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (bill.preValue != null || bill.curValue != null) {
                    Spacer(Modifier.height(Dimens.xs))
                    Text(
                        text = "起止码 %s → %s".format(
                            bill.preValue?.let { "%.2f".format(it) } ?: "--",
                            bill.curValue?.let { "%.2f".format(it) } ?: "--",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EnergyTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        EnergyTypes.ordered.forEach { type ->
            val isSelected = type == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(type) },
                label = { Text(EnergyTypes.label(type)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = energyColor(type).copy(alpha = 0.18f),
                    selectedLabelColor = energyColor(type),
                ),
            )
        }
    }
}

private val recordFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

@Composable
private fun PaymentRecordRow(record: PaymentRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.cardPadding, vertical = Dimens.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        record.paymentType.ifBlank { "记录" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (record.itemType.isNotBlank()) {
                        Spacer(Modifier.width(Dimens.sm))
                        Text(
                            record.itemType,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (record.logTime > 0) recordFmt.format(Date(record.logTime)) else "--",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "¥%.2f".format(record.value),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 三种能源的品牌图表色 */
private fun energyColor(type: Int): Color = when (type) {
    EnergyTypes.ELECTRIC -> ChartElectric
    EnergyTypes.COLD_WATER -> ChartColdWater
    EnergyTypes.HOT_WATER -> ChartHotWater
    else -> ChartElectric
}
