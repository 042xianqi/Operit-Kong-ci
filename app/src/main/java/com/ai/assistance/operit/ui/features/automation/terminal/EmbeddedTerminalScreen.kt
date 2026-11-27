package com.ai.assistance.operit.ui.features.automation.terminal

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.ai.assistance.operit.ui.theme.script.text.ThemeText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.ui.SettingsScreen
import com.ai.assistance.operit.terminal.ui.SetupScreen
import com.ai.assistance.operit.terminal.utils.TerminalFontConfigManager
import com.ai.assistance.operit.terminal.utils.VirtualKeyboardConfigManager
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 会话导航由工作台负责，终端自身只布局画布与快捷键。 */
enum class EmbeddedTerminalPage { CONSOLE, SETUP, SETTINGS }

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EmbeddedTerminalScreen(page: EmbeddedTerminalPage, onPageChange: (EmbeddedTerminalPage) -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { TerminalManager.getInstance(context) }
    val state by manager.terminalState.collectAsState()
    val scope = rememberCoroutineScope()
    when (page) {
        EmbeddedTerminalPage.SETTINGS -> SettingsScreen(onBack = { onPageChange(EmbeddedTerminalPage.CONSOLE) })
        EmbeddedTerminalPage.SETUP -> SetupScreen(
            onBack = { onPageChange(EmbeddedTerminalPage.CONSOLE) },
            onSetup = { commands ->
                scope.launch { manager.sendCommand(commands.joinToString(" && ")) }
                onPageChange(EmbeddedTerminalPage.CONSOLE)
            },
        )
        EmbeddedTerminalPage.CONSOLE -> {
            val session = state.currentSession
            // 工作区终端页是纯展示组件，不负责建会话；但用户可能直接从
            // 工作台切到这一页而没有任何会话（Tab 恢复、进程重建等）。
            // 这里补一个幂等兜底：只在“没有会话、没有在加载、没有错误”时建一次，
            // 避免出现永久的「暂无终端会话」空状态。
            LaunchedEffect(Unit) {
                if (state.sessions.isEmpty() && !state.isLoading && state.error == null) {
                    manager.createNewSession(
                        context.getString(R.string.workspace_terminal_session_title, 1),
                    )
                }
            }
            if (session == null) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.isLoading) CircularProgressIndicator()
                        Text(state.error ?: stringResource(R.string.workspace_terminal_empty), color = Color.White.copy(alpha = 0.72f))
                    }
                }
            } else key(session.id) {
                val config = remember { TerminalFontConfigManager.getInstance(context).loadRenderConfig() }
                val keyboard = remember { VirtualKeyboardConfigManager.getInstance(context).loadLayout() }
                // `WindowInsets.ime` / `navigationBars` 的 getter 是 @Composable，只能在组合里取到
                // 这两个不可变对象；每帧变化的 `getBottom()` 反而可以放到绘制/协程里读。
                val imeInsets = WindowInsets.ime
                val navigationInsets = WindowInsets.navigationBars
                val committedImeBottom = rememberTerminalImeViewport(imeInsets, navigationInsets)
                var ctrl by remember { mutableStateOf(false) }
                var alt by remember { mutableStateOf(false) }
                val sendInput: (String) -> Unit = { text ->
                    manager.sendInput(applyTerminalModifiers(text, ctrl, alt))
                    ctrl = false
                    alt = false
                }
                Column(Modifier.fillMaxSize().background(Color.Black).navigationBarsPadding()) {
                    if (session.isInitializing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                    }
                    // 不传 Canvas 页签：顶部只保留工作台这一套会话导航。
                    CanvasTerminalScreen(
                        emulator = session.ansiParser,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        config = config,
                        pty = session.pty,
                        // 动画期间保持画布顶部固定，避免整块内容上移后又在提交尺寸时跳回。
                        committedImeBottomInsetPx = committedImeBottom,
                        onInput = sendInput,
                        sessionId = session.id,
                        onScrollOffsetChanged = manager::saveScrollOffset,
                        getScrollOffset = manager::getScrollOffset,
                    )
                    TerminalShortcutBar(
                        layout = keyboard, ctrl = ctrl, alt = alt,
                        onToggleCtrl = { ctrl = !ctrl }, onToggleAlt = { alt = !alt },
                        onInput = sendInput,
                        modifier = Modifier.graphicsLayer {
                            // 在绘制阶段读 IME inset：动画每帧只失效这一层，
                            // 不会把整个终端（Canvas + 快捷键栏）重组一遍。
                            val inset =
                                (imeInsets.getBottom(this) - navigationInsets.getBottom(this))
                                    .coerceAtLeast(0)
                            translationY = -inset.toFloat()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 动画期间只移动快捷键，键盘稳定后再提交 PTY 尺寸。
 *
 * 用 `snapshotFlow` 订阅 IME 高度，而不是在组合里直接读 inset：
 * `getBottom()` 是快照状态，放在组合里读会让整棵终端在 IME 动画的每一帧重组；
 * 放进协程后只有 `committed` 变化时才触发一次布局。
 */
@Composable
private fun rememberTerminalImeViewport(imeInsets: WindowInsets, navigationInsets: WindowInsets): Int {
    val density = LocalDensity.current
    var committed by remember { mutableIntStateOf(0) }
    LaunchedEffect(density, imeInsets, navigationInsets) {
        var previous = 0
        snapshotFlow {
            (imeInsets.getBottom(density) - navigationInsets.getBottom(density)).coerceAtLeast(0)
        }.distinctUntilChanged().collectLatest { target ->
            val shrinking = target < previous
            previous = target
            if (shrinking) committed = 0
            else {
                delay(160)
                committed = target
            }
        }
    }
    return committed
}
