@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.payment

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.payment.Buildings
import cn.jnu.hub.feature.payment.PaymentApi
import cn.jnu.hub.feature.payment.PaymentException
import cn.jnu.hub.feature.payment.PaymentField
import cn.jnu.hub.feature.payment.PaymentForm
import cn.jnu.hub.feature.payment.PaymentStore
import cn.jnu.hub.feature.payment.PaymentSubmitResult
import cn.jnu.hub.feature.payment.PaymentValidator
import cn.jnu.hub.feature.payment.addressLabel
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ───────────────────────── 状态 ─────────────────────────

data class PaymentUiState(
    val form: PaymentForm = PaymentForm(),
    val errors: Map<PaymentField, String> = emptyMap(),
    val showConfirm: Boolean = false,
    val submitting: Boolean = false,
    /** 提交结果：待交给 WebView 打开 */
    val pendingUrl: String? = null,
    val pendingHtml: String? = null,
    val submitError: String? = null,
    val restoredFromMemory: Boolean = false,
)

class PaymentViewModel : ViewModel() {

    private var store: PaymentStore? = null

    private val _state = MutableStateFlow(PaymentUiState())
    val state: StateFlow<PaymentUiState> = _state.asStateFlow()

    /** Activity/Fragment 首次进入时注入 Context，读取本地记忆 */
    fun attach(context: Context) {
        if (store != null) return
        val s = PaymentStore(context)
        store = s
        val saved = s.load()
        val hasSaved = saved.jnuId.isNotBlank() || saved.roomNo.isNotBlank()

        // 上次金额：只有是合法正数才回填，且只有恰好等于某个快捷值时才不算「自定义」
        val lastMoney = s.lastMoney()
        val lastAmount = lastMoney.toDoubleOrNull()?.takeIf { it > 0 }
        val quickValues = listOf(50.0, 100.0, 200.0, 500.0)
        val isQuick = lastAmount != null && quickValues.any { it == lastAmount }

        _state.value = _state.value.copy(
            form = saved.copy(
                money = when {
                    lastAmount == null -> ""
                    isQuick -> lastAmount.toInt().toString()
                    else -> lastMoney
                },
                useCustomMoney = lastAmount != null && !isQuick,
            ),
            restoredFromMemory = hasSaved,
        )
    }

    fun update(form: PaymentForm) {
        _state.value = _state.value.copy(form = form, submitError = null)
    }

    /** 选择了快捷金额 */
    fun selectQuickAmount(value: Int) {
        _state.value = _state.value.copy(
            form = _state.value.form.copy(money = value.toString(), useCustomMoney = false),
        )
    }

    fun setCustomMoney(value: String) {
        _state.value = _state.value.copy(
            form = _state.value.form.copy(money = value, useCustomMoney = true),
        )
    }

    /** 点击「确认充值」：先校验，再弹二次确认 */
    fun requestConfirm() {
        val errors = PaymentValidator.validate(_state.value.form).associate { it.field to it.message }
        _state.value = _state.value.copy(
            errors = errors,
            showConfirm = errors.isEmpty(),
            submitError = null,
        )
    }

    fun dismissConfirm() {
        _state.value = _state.value.copy(showConfirm = false)
    }

    /** 确认后真正提交 */
    fun confirmAndSubmit() {
        val form = _state.value.form
        _state.value = _state.value.copy(
            showConfirm = false,
            submitting = true,
            submitError = null,
        )
        viewModelScope.launch {
            try {
                val result = PaymentApi.submit(form)
                when (result) {
                    is PaymentSubmitResult.Redirect -> {
                        store?.save(form)
                        _state.value = _state.value.copy(submitting = false, pendingUrl = result.url)
                    }

                    is PaymentSubmitResult.InlinePage -> {
                        store?.save(form)
                        _state.value = _state.value.copy(
                            submitting = false,
                            pendingHtml = result.html,
                        )
                    }

                    is PaymentSubmitResult.Failed ->
                        _state.value = _state.value.copy(submitting = false, submitError = result.message)
                }
            } catch (e: PaymentException) {
                _state.value = _state.value.copy(
                    submitting = false,
                    submitError = e.message ?: "提交失败，请稍后重试",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    submitting = false,
                    submitError = e.message?.takeIf { it.isNotBlank() } ?: "提交失败，请稍后重试",
                )
            }
        }
    }

    /** 导航层消费掉待打开的地址 */
    fun consumePending() {
        _state.value = _state.value.copy(pendingUrl = null, pendingHtml = null)
    }
}

// ───────────────────────── 页面 ─────────────────────────

/**
 * 水电缴费入口。由 Lead 在导航里接上（路由自带或外部传入）。
 *
 * @param onBack 返回
 * @param onOpenWeb 打开官方支付页：(url, title) -> Unit；为 null 时用内置 WebPageActivity 打开
 */
@Composable
fun PaymentRoute(
    onBack: () -> Unit,
    onOpenWeb: ((String, String) -> Unit)? = null,
    viewModel: PaymentViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.attach(context) }

    // 提交成功 → 打开官方支付页
    LaunchedEffect(state.pendingUrl, state.pendingHtml) {
        val url = state.pendingUrl
        val html = state.pendingHtml
        when {
            url != null -> {
                openPaymentPage(context, onOpenWeb, url)
                viewModel.consumePending()
            }

            html != null -> {
                // 服务端直接把收银台 HTML 回了过来（内嵌表单）。
                // WebView 加载 data: URL 会被拦截且相对资源全失效，
                // 所以这里改走「订单已创建 → 打开官方收银台」，
                // 官方页面会按会话/订单号展示待支付单。
                openPaymentPage(context, onOpenWeb, PaymentApi.payPageUrl)
                viewModel.consumePending()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("水电缴费") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Dimens.screenPadding),
        ) {
            Spacer(Modifier.height(Dimens.sm))

            // 商户信息
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(Dimens.cardPadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            PaymentApi.MERCHANT_NAME,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(Dimens.xs))
                        Text(
                            "商户号 ${PaymentApi.MERCHANT_ID}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "免登录",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                RoundedCornerShape(50),
                            )
                            .padding(horizontal = Dimens.md, vertical = Dimens.xs),
                    )
                }
            }

            if (state.restoredFromMemory) {
                Spacer(Modifier.height(Dimens.sm))
                Text(
                    "已自动填充上次填写的信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(Dimens.lg))

            // ── 表单 ──
            PaymentFieldInput(
                label = "学工号",
                required = true,
                value = state.form.jnuId,
                error = state.errors[PaymentField.JNU_ID],
                keyboardType = KeyboardType.Text,
                onValueChange = { viewModel.update(state.form.copy(jnuId = it)) },
            )
            PaymentFieldInput(
                label = "姓名",
                required = true,
                value = state.form.username,
                error = state.errors[PaymentField.USERNAME],
                onValueChange = { viewModel.update(state.form.copy(username = it)) },
            )
            PaymentFieldInput(
                label = "校园卡号",
                required = false,
                value = state.form.cardNo,
                error = state.errors[PaymentField.CARD_NO],
                keyboardType = KeyboardType.Number,
                onValueChange = { viewModel.update(state.form.copy(cardNo = it)) },
            )
            PaymentFieldInput(
                label = "手机号码",
                required = true,
                value = state.form.phone,
                error = state.errors[PaymentField.PHONE],
                keyboardType = KeyboardType.Phone,
                onValueChange = { viewModel.update(state.form.copy(phone = it)) },
            )

            BuildingDropdown(
                value = state.form.building,
                error = state.errors[PaymentField.BUILDING],
                onSelect = { viewModel.update(state.form.copy(building = it)) },
            )

            PaymentFieldInput(
                label = "房间号",
                required = true,
                value = state.form.roomNo,
                error = state.errors[PaymentField.ROOM_NO],
                onValueChange = { viewModel.update(state.form.copy(roomNo = it)) },
            )

            Spacer(Modifier.height(Dimens.lg))

            // ── 充值金额 ──
            Text(
                "充值金额",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Dimens.sm))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
            ) {
                state.form.quickAmounts.forEach { amount ->
                    val selected = !state.form.useCustomMoney && state.form.money == amount.toString()
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.selectQuickAmount(amount) },
                        label = { Text("¥$amount") },
                        modifier = Modifier.weight(1f),
                    )
                }
                FilterChip(
                    selected = state.form.useCustomMoney,
                    onClick = { viewModel.setCustomMoney(if (state.form.useCustomMoney) state.form.money else "") },
                    label = { Text("自定义") },
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.form.useCustomMoney) {
                Spacer(Modifier.height(Dimens.md))
                PaymentFieldInput(
                    label = "自定义金额（元）",
                    required = true,
                    value = state.form.money,
                    error = state.errors[PaymentField.MONEY],
                    keyboardType = KeyboardType.Decimal,
                    onValueChange = { viewModel.setCustomMoney(it) },
                )
            } else {
                state.errors[PaymentField.MONEY]?.let {
                    Spacer(Modifier.height(Dimens.xs))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            state.submitError?.let { msg ->
                Spacer(Modifier.height(Dimens.md))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(Dimens.cardPadding),
                    )
                }
            }

            Spacer(Modifier.height(Dimens.xl))

            Button(
                onClick = { viewModel.requestConfirm() },
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth().height(Dimens.minTouchTarget),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(Dimens.sm))
                    Text("提交中…")
                } else {
                    Text("确认充值")
                }
            }

            Spacer(Modifier.height(Dimens.md))
            Text(
                "将跳转到学校官方支付页面完成付款，App 不会收集任何支付信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Dimens.xxl))
        }
    }

    if (state.showConfirm) {
        ConfirmDialog(
            form = state.form,
            onDismiss = { viewModel.dismissConfirm() },
            onConfirm = { viewModel.confirmAndSubmit() },
        )
    }
}

@Composable
private fun PaymentFieldInput(
    label: String,
    required: Boolean,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = {
            Text(
                if (required) label else "$label（选填）",
            )
        },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = if (error != null) {
            { Text(error) }
        } else null,
    )
    Spacer(Modifier.height(Dimens.sm))
}

@Composable
private fun BuildingDropdown(value: String, error: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text("楼栋") },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
            isError = error != null,
            supportingText = if (error != null) { { Text(error) } } else null,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Buildings.ALL.forEach { b ->
                DropdownMenuItem(
                    text = { Text(b) },
                    onClick = {
                        onSelect(b)
                        expanded = false
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(Dimens.sm))
}

@Composable
private fun ConfirmDialog(
    form: PaymentForm,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认充值信息") },
        text = {
            Column {
                ConfirmRow("学工号", form.jnuId.trim())
                ConfirmRow("姓名", form.username.trim())
                ConfirmRow("楼栋房间", form.addressLabel())
                ConfirmRow("手机号码", form.phone.trim())
                HorizontalDivider(Modifier.padding(vertical = Dimens.sm))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("充值金额", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "¥%.2f".format(PaymentValidator.amountOf(form)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认并前往支付") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("再改改") }
        },
    )
}

@Composable
private fun ConfirmRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 打开支付页：优先交给导航层，否则用内置 WebPageActivity */
private fun openPaymentPage(
    context: Context,
    onOpenWeb: ((String, String) -> Unit)?,
    url: String,
) {
    if (onOpenWeb != null) {
        onOpenWeb(url, "水电缴费")
        return
    }
    runCatching {
        context.startActivity(
            cn.jnu.hub.feature.web.WebPageActivity.intent(context, url, "水电缴费"),
        )
    }
}
