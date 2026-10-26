@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.pe

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.pe.PeLoginViewModel
import cn.jnu.hub.feature.pe.peFeatureInit
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.theme.Dimens

/**
 * 「暨大通」体测登录页。
 *
 * 体测（师生云 s2.shishengyun.com）是独立 ASP.NET 系统，与学校统一认证（CAS）无关：
 * 必须用体测自己的账号（学号）+ 密码（初始为 8 位生日）+ 图形验证码登录。
 * 登录成功后 Cookie 进入共享 CookieJar，后续成绩 / 评分标准页面直接可用。
 */
@Composable
fun PeLoginRoute(onBack: () -> Unit, onSuccess: () -> Unit = {}) {
    val context = LocalContext.current
    LaunchedEffect(context) { peFeatureInit(context) }

    val vm: PeLoginViewModel = viewModel()
    val state by vm.state.collectAsState()
    val username by vm.username.collectAsState()
    val password by vm.password.collectAsState()
    val remember by vm.remember.collectAsState()
    val captcha by vm.captchaInput.collectAsState()
    val lastError by vm.lastError.collectAsState()

    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadLoginPage() }

    LaunchedEffect(state) {
        if (state is PeLoginViewModel.State.Success) onSuccess()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("体测登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets.statusBars,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenPadding, vertical = Dimens.lg),
        ) {
            Text(
                text = "体测系统使用独立账号",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Dimens.xs))
            Text(
                text = "与统一认证（jAccount）不同，这里需要用学号 + 体测密码登录。" +
                    "初始密码为 8 位生日数字（如 20050101）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Dimens.xl))

            when (val s = state) {
                is PeLoginViewModel.State.Preparing -> LoadingState(text = "正在打开体测登录页…")

                is PeLoginViewModel.State.Failed -> {
                    ErrorState(
                        title = "登录失败",
                        message = s.message,
                        actionText = "重新加载登录页",
                        onAction = { vm.loadLoginPage() },
                    )
                }

                else -> {
                    val ready = state as? PeLoginViewModel.State.Ready
                    val submitting = state is PeLoginViewModel.State.Submitting

                    // 登录失败提示：独立于 State，刷新验证码时不会被冲掉
                    if (lastError != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    lastError.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        Spacer(Modifier.height(Dimens.md))
                    }

                    OutlinedTextField(
                        value = username,
                        onValueChange = vm::setUsername,
                        label = { Text("学号") },
                        singleLine = true,
                        enabled = !submitting,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Dimens.md))

                    OutlinedTextField(
                        value = password,
                        onValueChange = vm::setPassword,
                        label = { Text("密码（初始为 8 位生日）") },
                        singleLine = true,
                        enabled = !submitting,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                        ),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Dimens.md))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = captcha,
                            onValueChange = vm::setCaptcha,
                            label = { Text("验证码") },
                            singleLine = true,
                            enabled = !submitting,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Dimens.md))
                        PeCaptchaBox(
                            bitmap = ready?.captcha,
                            loading = ready?.captchaLoading == true,
                            enabled = !submitting,
                            onRefresh = { vm.refreshCaptcha() },
                        )
                    }

                    Spacer(Modifier.height(Dimens.md))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = remember,
                            onCheckedChange = vm::setRemember,
                            enabled = !submitting,
                        )
                        Text(
                            text = "记住账号密码",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { vm.setRemember(!remember) }
                                .padding(vertical = Dimens.sm),
                        )
                    }

                    Spacer(Modifier.height(Dimens.lg))
                    Button(
                        onClick = { vm.submit() },
                        enabled = !submitting && ready != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Dimens.minTouchTarget),
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(Dimens.sm))
                            Text("登录中…")
                        } else {
                            Text("登录体测系统")
                        }
                    }

                    Spacer(Modifier.height(Dimens.lg))
                    Text(
                        text = "提示：验证码点图片可刷新。若一直提示密码错误，" +
                            "请在浏览器登录 s2.shishengyun.com 用「找回密码」重置后再试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                    )
                }
            }

            Spacer(Modifier.height(Dimens.xxxl))
        }
    }
}

/** 验证码图片框：点一下换一张 */
@Composable
private fun PeCaptchaBox(
    bitmap: android.graphics.Bitmap?,
    loading: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(112.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled && !loading, onClick = onRefresh),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )

            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "验证码，点击刷新",
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )

            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimens.iconSm),
                )
                Text(
                    text = "点击刷新",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
