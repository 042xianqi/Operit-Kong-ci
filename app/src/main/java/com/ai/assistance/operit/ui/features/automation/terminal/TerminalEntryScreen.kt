package com.ai.assistance.operit.ui.features.automation.terminal

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.resources.OfflineResources
import com.ai.assistance.operit.terminal.BundledEnvState
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.ui.features.management.resources.OfflineResourcesActivity
import com.ai.assistance.operit.util.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 所有终端入口共用资源检查，准备好会话后交给聊天工作台显示。 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalEntryScreen(
    initialPage: EmbeddedTerminalPage,
    onOpenTerminal: (EmbeddedTerminalPage) -> Unit,
    onGoBack: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf<String?>(null) }
    var failureText by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(initialPage, attempt) {
        try {
            val manager = TerminalManager.getInstance(context)
            // 首启时内置 Alpine 可能还在解压：等它就绪（最多 120 s），而不是跳去下载页。
            if (!withContext(Dispatchers.IO) { OfflineResources.terminalRuntimeReady(context) }) {
                statusText = context.getString(R.string.terminal_alpine_preparing)
                val settled = withTimeoutOrNull(ALPINE_PREPARE_TIMEOUT_MS) {
                    // 解压放在 TerminalManager 自己的作用域里：这里超时只放弃等待，
                    // 不会把 158 MB 的安装一起取消。
                    manager.prepareBundledEnvironmentAsync()
                    manager.bundledEnvState.first {
                        it is BundledEnvState.Ready || it is BundledEnvState.Failed
                    }
                }
                statusText = null
                if (settled == null) {
                    failureText = context.getString(R.string.terminal_alpine_prepare_timeout)
                    return@LaunchedEffect
                }
                // 失败必须可见：显示原因首行 + 重试，而不是静默跳到资源页。
                (manager.bundledEnvState.value as? BundledEnvState.Failed)?.let { failed ->
                    failureText = context.getString(
                        R.string.terminal_alpine_prepare_failed,
                        failed.message.lineSequence().first(),
                    )
                    return@LaunchedEffect
                }
            }
            val resourcesReady = withContext(Dispatchers.IO) {
                OfflineResources.terminalRuntimeReady(context) ||
                    OfflineResources.isReady(context, OfflineResources.UBUNTU)
            }
            if (!resourcesReady) {
                context.startActivity(
                    Intent(context, OfflineResourcesActivity::class.java)
                        .putExtra(OfflineResourcesActivity.EXTRA_GROUP, OfflineResources.UBUNTU)
                )
                onGoBack()
                return@LaunchedEffect
            }
            // 配置命令需要真实会话，不能直接在未安装环境里打开包检查页面。
            if (manager.terminalState.value.sessions.isEmpty()) {
                manager.createNewSession(context.getString(R.string.workspace_terminal_session_title, 1))
            }
            onOpenTerminal(initialPage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("TerminalEntry", "打开终端失败", e)
            onError(context.getString(R.string.workspace_terminal_failed))
            onGoBack()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val failure = failureText
            if (failure == null) {
                CircularProgressIndicator()
                statusText?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(failure, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    failureText = null
                    // 重试 = 再跑一次预热；幂等，失败会再次落到 Failed。
                    TerminalManager.getInstance(context).prepareBundledEnvironmentAsync()
                    attempt++
                }) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

/** 首启解压 158 MB 在低端机可能超过 1 分钟，给足 120 s 再报失败。 */
private const val ALPINE_PREPARE_TIMEOUT_MS = 120_000L
