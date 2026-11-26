package com.sonlet.app.feature.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sonlet.app.R
import io.github.releasekite.core.UpdatePhase
import io.github.releasekite.core.UpdateUiState
import java.util.Locale

/**
 * 自绘更新弹窗:版式对齐 DESIGN.md(平面表面 + 1dp 边界 + 12dp 圆角,不用阴影/tonal 叠加)。
 *
 * 只在需要用户注意的阶段出现;Idle/UpToDate/Checking/CheckFailed 一律不打扰。
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onRetry: () -> Unit,
    onOpenInstallPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    val info = when (phase) {
        is UpdatePhase.Available -> phase.info
        is UpdatePhase.Downloading -> phase.info
        is UpdatePhase.Verifying -> phase.info
        is UpdatePhase.NeedInstallPermission -> phase.info
        is UpdatePhase.DownloadFailed -> phase.info
        else -> null
    } ?: return
    val forced = (phase as? UpdatePhase.Available)?.forced == true

    Dialog(
        onDismissRequest = { if (!forced) onLater() },
        properties = DialogProperties(
            dismissOnBackPress = !forced,
            dismissOnClickOutside = !forced,
        ),
    ) {
        Surface(
            modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.update_title, info.versionName),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.update_size, formatSize(info.apkSizeBytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val notes = remember(info.notes) { summarizeNotes(info.notes) }
                if (notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(16.dp))
                when (phase) {
                    is UpdatePhase.Downloading -> {
                        val total = phase.totalBytes.coerceAtLeast(1L)
                        val ratio = (phase.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.update_downloading, (ratio * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdatePhase.Verifying -> Text(
                        text = stringResource(R.string.update_verifying),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is UpdatePhase.NeedInstallPermission -> {
                        Text(
                            text = stringResource(R.string.update_need_permission),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onOpenInstallPermission) {
                                Text(stringResource(R.string.update_grant))
                            }
                            TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
                        }
                    }
                    is UpdatePhase.DownloadFailed -> {
                        Text(
                            text = stringResource(R.string.update_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRetry) { Text(stringResource(R.string.update_retry)) }
                            if (!phase.forced || phase.escapeAllowed) {
                                TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
                            }
                        }
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
                        Button(onClick = onUpdate) { Text(stringResource(R.string.update_now)) }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 10) String.format(Locale.US, "%.0f MB", mb) else String.format(Locale.US, "%.1f MB", mb)
}

/**
 * GitHub 自动生成的变更列表带 Markdown、链接与 PR 条目,直接塞进弹窗会读成一堆 URL。
 * 这里只保留前几条要点:去掉标题/链接/Full Changelog,压缩空行。
 */
internal fun summarizeNotes(raw: String, maxLines: Int = 4): String {
    val url = Regex("https?://\\S+")
    // GitHub 自动生成的条目尾巴是 " by @user in <link>",去掉链接后残留的 "by @" 也没有信息量
    val author = Regex("""\s*by @\S+\s+in\s*$""")
    // 库会把 markdown 转成纯文本，星号已消失，所以噪音判定必须放在清洗之后
    val noise = listOf("What's Changed", "Full Changelog", "Contributors")
    // 输入可能是纯文本，也可能是 markdown，统一去掉 ** 与 # 的装饰
    val decoration = Regex("\\*\\*|^#{1,6}\\s*")
    return raw.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            line.removePrefix("* ")
                .removePrefix("- ")
                .replace(decoration, "")
                .replace(url, "")
                .replace(author, "")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .filterNot { line -> noise.any { line.startsWith(it, ignoreCase = true) } }
        .take(maxLines)
        .joinToString("\n")
}
