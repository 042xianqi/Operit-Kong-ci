package com.ai.assistance.operit.ui.features.management.settings.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.R
import com.ai.assistance.operit.terminal.BundledEnvState
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.TerminalProfile
import com.ai.assistance.operit.terminal.data.TerminalProfileSettings
import com.ai.assistance.operit.terminal.utils.AlpineRuntime
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 终端实例注册表的设置页。
 *
 * 注册表本身（`TerminalProfileStore`）与切换/删除能力在 `modules/terminal` 里早就齐了，
 * 缺的只是一个入口。放在 app 层的原因：app → terminal 是单向依赖，
 * 而「去下载 Ubuntu」需要 `startActivity(OfflineResourcesActivity)`，
 * terminal 模块看不到 app 层的 Activity。
 */
internal class TerminalDistributionViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val manager = TerminalManager.getInstance(context)

    /** 「下载 Linux 发行版」列表的一行：清单数据 + 是否已就位 + 当前进度。 */
    internal data class DistributionRow(
        val distribution: com.ai.assistance.operit.terminal.data.TerminalDistribution,
        val installed: Boolean,
        val progress: com.ai.assistance.operit.data.resources.LinuxDistributionInstaller.Progress?,
    )

    private val _profiles = MutableStateFlow<List<TerminalProfile>>(emptyList())
    val profiles: StateFlow<List<TerminalProfile>> = _profiles.asStateFlow()

    private val _defaultProfileId = MutableStateFlow("")
    val defaultProfileId: StateFlow<String> = _defaultProfileId.asStateFlow()

    /**
     * 可下载发行版。清单来自 `TerminalDistributions`（与 Operit2 `distributions()` 同一份
     * URL/sha256），是否已就位由 Rust 的 `rootfsInstalled` 判定。
     */
    private val _distributions = MutableStateFlow<List<DistributionRow>>(emptyList())
    val distributions: StateFlow<List<DistributionRow>> = _distributions.asStateFlow()

    private var installJob: kotlinx.coroutines.Job? = null

    /** 内置 Alpine 的安装状态：设置页需要显示「准备中 / 失败原因 + 重试」。 */
    val bundledEnvState: StateFlow<BundledEnvState> = manager.bundledEnvState

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) { manager.loadProfileSettings() }
            adoptInstalledDistributions(settings)
            val current = withContext(Dispatchers.IO) { manager.loadProfileSettings() }
            _profiles.value = current.profiles
            _defaultProfileId.value = current.defaultProfileId
            refreshDistributions()
        }
    }

    /**
     * 把已经躺在 `installed-rootfs/<dir>` 里、但注册表里没有的实例登记进来。
     *
     * 老版本用 shell 脚本装的 Ubuntu 没有 profile 记录；不登记的话设置页会显示「已安装」
     * 却切不过去。判定用 Rust 的 `rootfsInstalled`（`bin/sh` 可解析 + `etc/`），
     * 目录不存在就什么也不做。
     */
    private suspend fun adoptInstalledDistributions(settings: TerminalProfileSettings) {
        withContext(Dispatchers.IO) {
            val known = settings.profiles.map { File(it.rootfsPath).absolutePath }.toSet()
            com.ai.assistance.operit.terminal.utils.TerminalDistributions
                .availableOnCurrentDevice()
                .forEach { distribution ->
                    val root =
                        File(
                            com.ai.assistance.operit.data.resources.LinuxDistributionInstaller
                                .instancesRoot(context),
                            distribution.rootfsDirectoryName,
                        )
                    if (root.absolutePath in known) return@forEach
                    if (!com.ai.assistance.operit.data.resources.LinuxDistributionInstaller
                            .isInstalled(context, distribution)
                    ) {
                        return@forEach
                    }
                    manager.upsertProfile(
                        TerminalProfile(
                            id = distribution.rootfsDirectoryName,
                            name = distribution.name,
                            distributionId = distribution.id,
                            rootfsPath = root.absolutePath,
                            installed = true,
                            createdAtMillis = root.lastModified(),
                        ),
                        makeDefault = false,
                    )
                }
        }
    }

    private suspend fun refreshDistributions() {
        val rows =
            withContext(Dispatchers.IO) {
                val current = _distributions.value.associateBy { it.distribution.id }
                com.ai.assistance.operit.terminal.utils.TerminalDistributions
                    .availableOnCurrentDevice()
                    .map { distribution ->
                        DistributionRow(
                            distribution = distribution,
                            installed =
                                com.ai.assistance.operit.data.resources.LinuxDistributionInstaller
                                    .isInstalled(context, distribution),
                            progress = current[distribution.id]?.progress,
                        )
                    }
            }
        _distributions.value = rows
    }

    /**
     * 下载并安装一个发行版。归档下载、sha256 校验、Rust 解压与实例登记都在
     * [com.ai.assistance.operit.data.resources.LinuxDistributionInstaller] 里，
     * 这里只负责进度与结果提示。
     */
    fun install(distributionId: String) {
        if (installJob?.isActive == true) return
        val row = _distributions.value.firstOrNull { it.distribution.id == distributionId } ?: return
        if (row.installed) return
        installJob =
            viewModelScope.launch {
                updateProgress(distributionId, com.ai.assistance.operit.data.resources.LinuxDistributionInstaller.Progress(
                    com.ai.assistance.operit.data.resources.models.ResourceDownloadPhase.CONNECTING, 0L, row.distribution.downloadBytes,
                ))
                try {
                    com.ai.assistance.operit.data.resources.LinuxDistributionInstaller.install(
                        context = context,
                        distribution = row.distribution,
                        // 必须具名：末尾 lambda 会绑到最后一个参数 isCancelled，而不是 onProgress。
                        onProgress = { progress -> updateProgress(distributionId, progress) },
                    )
                    _message.value = context.getString(R.string.terminal_dist_install_done, row.distribution.name)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _message.value = context.getString(
                        R.string.terminal_dist_install_failed,
                        failure.message ?: failure.toString(),
                    )
                } finally {
                    updateProgress(distributionId, null)
                    refresh()
                }
            }
    }

    private fun updateProgress(
        distributionId: String,
        progress: com.ai.assistance.operit.data.resources.LinuxDistributionInstaller.Progress?,
    ) {
        _distributions.value =
            _distributions.value.map { row ->
                if (row.distribution.id == distributionId) row.copy(progress = progress) else row
            }
    }

    fun select(profileId: String) {
        viewModelScope.launch {
            manager.switchActiveProfile(profileId).onFailure { failure ->
                _message.value = context.getString(
                    R.string.terminal_dist_switch_failed,
                    failure.message ?: failure.toString(),
                )
            }
            refresh()
        }
    }

    fun remove(profileId: String) {
        viewModelScope.launch {
            manager.removeProfile(profileId).onFailure { failure ->
                _message.value = context.getString(
                    R.string.terminal_dist_delete_failed,
                    failure.message ?: failure.toString(),
                )
            }
            refresh()
        }
    }

    fun retryBundledEnvironment() {
        viewModelScope.launch { manager.ensureBundledEnvironmentReady() }
    }

    fun clearMessage() {
        _message.value = null
    }
}

@Composable
internal fun TerminalDistributionSettingsScreen(
    model: TerminalDistributionViewModel = viewModel(),
) {
    val profiles by model.profiles.collectAsState()
    val distributions by model.distributions.collectAsState()
    val defaultProfileId by model.defaultProfileId.collectAsState()
    val envState by model.bundledEnvState.collectAsState()
    val message by model.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<TerminalProfile?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            model.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            when (val state = envState) {
                is BundledEnvState.Preparing -> Text(
                    stringResource(R.string.terminal_dist_preparing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is BundledEnvState.Failed -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.terminal_dist_failed, state.message.lineSequence().first()),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = model::retryBundledEnvironment) {
                        Text(stringResource(R.string.action_retry))
                    }
                }

                else -> Unit
            }

            if (profiles.isEmpty()) {
                Text(stringResource(R.string.terminal_dist_empty))
            }
            profiles.forEach { profile ->
                val isDefault = profile.id == defaultProfileId
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                profile.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (isDefault) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text(stringResource(R.string.terminal_dist_current)) },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(
                                    if (profile.distributionId == AlpineRuntime.PROFILE_ID) {
                                        R.string.terminal_dist_builtin
                                    } else {
                                        R.string.terminal_dist_downloaded
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                stringResource(
                                    if (isProfileReady(profile)) {
                                        R.string.terminal_dist_ready
                                    } else {
                                        R.string.terminal_dist_missing
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isProfileReady(profile)) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        Text(profile.rootfsPath, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { model.select(profile.id) },
                                enabled = !isDefault,
                            ) { Text(stringResource(R.string.terminal_dist_switch)) }
                            // 最后一个实例与当前实例不能删：与 removeProfile 的约束一致，
                            // 这里先禁用，避免让用户点了才弹错。
                            TextButton(
                                onClick = { pendingDelete = profile },
                                enabled = !isDefault && profiles.size > 1,
                            ) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.terminal_dist_download_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            distributions.forEach { row ->
                val distribution = row.distribution
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                distribution.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            when {
                                row.progress != null -> Text(
                                    stringResource(
                                        if (row.progress.phase == com.ai.assistance.operit.data.resources.models.ResourceDownloadPhase.INSTALLING) {
                                            R.string.terminal_dist_installing
                                        } else {
                                            R.string.terminal_dist_downloading
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                )

                                row.installed -> Text(
                                    stringResource(R.string.terminal_dist_installed_action),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )

                                else -> TextButton(onClick = { model.install(distribution.id) }) {
                                    Text(stringResource(R.string.terminal_dist_download_action))
                                }
                            }
                        }
                        Text(
                            stringResource(
                                R.string.terminal_dist_arch_size,
                                distribution.architecture,
                                String.format(java.util.Locale.US, "%.1f", distribution.downloadBytes / 1048576.0),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        row.progress?.let { progress ->
                            if (progress.totalBytes > 0) {
                                LinearProgressIndicator(
                                    progress = { progress.doneBytes.toFloat() / progress.totalBytes },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        if (distribution.notice.isNotBlank()) {
                            Text(
                                distribution.notice,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.terminal_dist_delete_title)) },
            text = { Text(stringResource(R.string.terminal_dist_delete_message, profile.name)) },
            confirmButton = {
                TextButton(onClick = {
                    model.remove(profile.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** 实例是否真的就位：`installed` 是登记时的快照，目录可能已被清理。 */
private fun isProfileReady(profile: TerminalProfile): Boolean =
    if (profile.distributionId == AlpineRuntime.PROFILE_ID) {
        AlpineRuntime.isInstalled(File(profile.rootfsPath))
    } else {
        File(profile.rootfsPath, "bin/bash").isFile
    }
