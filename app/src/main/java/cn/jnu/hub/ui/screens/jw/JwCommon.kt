package cn.jnu.hub.ui.screens.jw

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.jnu.hub.ui.theme.Dimens

/**
 * 学号门禁卡片。
 *
 * 教务的学分/个人信息接口都需要显式学号，而 CAS 会话本身不提供「我是谁」，
 * 因此在拿不到学号时，在页面顶部让用户手动填一次并本地记住。
 */
@Composable
fun StudentIdGate(
    hint: String,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    initial: String = "",
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val submit = {
        val t = text.trim()
        if (t.isNotEmpty()) onSubmit(t)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Text(
                text = hint,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Dimens.sm))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("学号") },
                placeholder = { Text("例如 2024123456") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.height(Dimens.md))
            Button(
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = text.isNotBlank(),
            ) {
                Text("查 询")
            }
            Spacer(Modifier.height(Dimens.xs))
            Text(
                text = "学号只会保存在本机，用于教务系统查询。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 顶部信息条：一行小字说明当前数据口径 */
@Composable
fun InfoStrip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.md, vertical = Dimens.sm),
        )
    }
}

/** 键值行：左标签右内容 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
) {
    if (value.isBlank()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = if (emphasize) MaterialTheme.typography.bodyLarge
            else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
