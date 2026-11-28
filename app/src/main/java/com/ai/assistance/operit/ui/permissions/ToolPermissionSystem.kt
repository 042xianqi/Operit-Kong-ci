package com.ai.assistance.operit.ui.permissions

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.logging.AppLogger
import androidx.compose.material3.ColorScheme
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.core.tools.protocol.AITool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

// Define DataStore
private val Context.toolPermissionsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tool_permissions")

/**
 * Permission levels for tool operations
 */
enum class PermissionLevel {
    ALLOW,      // Allow automatically without asking
    ASK,        // Always ask
    FORBID;     // Never allow

    companion object {
        fun fromString(value: String?): PermissionLevel {
            return when (value) {
                "ALLOW" -> ALLOW
                "CAUTION" -> ASK
                "ASK" -> ASK
                "FORBID" -> FORBID
                else -> ASK  // Default to ASK
            }
        }
    }
}

/**
 * 工具操作对运行环境的效应，对齐 Operit2 `ToolEffect`
 * (`ToolPermissionSystem.rs:11`)。
 *
 * 为什么需要：Kong 原来只有 ALLOW/ASK/FORBID 一个维度，无法表达
 * "只读可放行、写入必须问" 这种最常见的策略。Operit2 用
 * `AiPermissionMode.ReadOnly` 挡掉所有 WRITE，Kong 只能用 FORBID
 * 一刀切，用户要么全放开要么全禁。
 */
enum class ToolEffect {
    /** 只读取环境，不改变任何状态。 */
    READ,
    /** 会写入文件、改设置、发请求或执行命令。 */
    WRITE;

    companion object {
        /**
         * 由工具名判定读数效应。
         *
         * `AITool` 只有 name/parameters/description，没有读写标记，
         * 因此只能按动词前缀判定。规则与 Operit2 的
         * `ToolEffect` 归类意图一致：查询类放行，变更类必须问。
         *
         * 判定失败的默认是 WRITE —— 宁可多问一次也不能静默放行。
         */
        private val READ_PREFIXES = listOf(
            "get_", "list_", "read_", "query_", "search_", "find_", "grep_",
            "scan_", "view_", "show_", "check_", "count_", "inspect_", "tree_",
            "stat_", "exists_", "download_", "fetch_", "browse_", "visit_",
        )

        fun of(tool: AITool): ToolEffect {
            val name = tool.name.lowercase()
            return if (READ_PREFIXES.any { name.startsWith(it) }) READ else WRITE
        }
    }
}

/**
 * 会话级放行名单，对齐 Operit2 `ToolPermissionSystem.sessionApprovedTools`
 * (`ToolPermissionSystem.rs:74`)。
 *
 * 用户在一次运行中选了 "本次允许"，后续同名工具不再重复弹窗；
 * 进程结束后自动失效，不落盘，避免把临时决定变成永久授权。
 */
internal object SessionApprovedTools {
    private val approved = ConcurrentHashMap.newKeySet<String>()

    fun approve(toolName: String) {
        if (toolName.isNotBlank()) approved.add(toolName)
    }

    fun isApproved(toolName: String): Boolean = approved.contains(toolName)

    fun revoke(toolName: String) {
        approved.remove(toolName)
    }

    fun clear() {
        approved.clear()
    }

    internal fun snapshot(): Set<String> = approved.toSet()
}

/**
 * Centralized tool permission system that manages both permission storage and checking
 */
class ToolPermissionSystem private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ToolPermissionSystem"
        private const val PERMISSION_REQUEST_TIMEOUT_MS = 60000L // 60 seconds timeout
        
        // DataStore keys
        private val MASTER_SWITCH = stringPreferencesKey("master_switch")
        
        // Default permission setting
        private val DEFAULT_MASTER_SWITCH = PermissionLevel.ASK.name
        
        @Volatile
        private var INSTANCE: ToolPermissionSystem? = null
        
        fun getInstance(context: Context): ToolPermissionSystem {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ToolPermissionSystem(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 工具权限存储：使用 "tool_permission_<tool_name>" 作为key
    private fun toolPermissionKey(toolName: String) = stringPreferencesKey("tool_permission_$toolName")
    
    // Permission request management
    private val mainHandler = Handler(Looper.getMainLooper())
    private val permissionRequestOverlay = PermissionRequestOverlay(context)
    private var currentPermissionCallback: ((ToolPermissionCheckResult) -> Unit)? = null
    private var permissionRequestInfo: Pair<AITool, String>? = null
    
    // 存储当前颜色方案
    private var currentColorScheme: ColorScheme? = null
    
    /**
     * 设置当前使用的颜色方案
     */
    fun setColorScheme(colorScheme: ColorScheme?) {
        this.currentColorScheme = colorScheme
        permissionRequestOverlay.setColorScheme(colorScheme)
    }
    
    // Permission request state flow
    private val _permissionRequestState = MutableStateFlow<Pair<AITool, String>?>(null)
    val permissionRequestState = _permissionRequestState.asStateFlow()
    
    // Permission level flows
    val masterSwitchFlow: Flow<PermissionLevel> = context.toolPermissionsDataStore.data.map { preferences ->
        PermissionLevel.fromString(preferences[MASTER_SWITCH] ?: DEFAULT_MASTER_SWITCH)
    }
    
    /** 同一次 DataStore 快照中的默认方式与全部单项规则，避免页面短暂显示相互矛盾的状态。 */
    val settingsFlow: Flow<ToolPermissionSettings> = context.toolPermissionsDataStore.data.map { preferences ->
        ToolPermissionSettings(
            master = PermissionLevel.fromString(preferences[MASTER_SWITCH] ?: DEFAULT_MASTER_SWITCH),
            overrides = preferences.asMap().entries.filter { it.key.name.startsWith("tool_permission_") }
                .associate { it.key.name.removePrefix("tool_permission_") to PermissionLevel.fromString(it.value as String) }
        )
    }

    /** 批量应用与跟随默认一次写入，只记录本次实际变更的旧值供撤销。 */
    suspend fun updateSettings(master: PermissionLevel? = null, overrides: Map<String, PermissionLevel?> = emptyMap()): ToolPermissionChange {
        var previousMaster: PermissionLevel? = null
        val previousOverrides = mutableMapOf<String, PermissionLevel?>()
        context.toolPermissionsDataStore.edit { preferences ->
            val currentMaster = PermissionLevel.fromString(preferences[MASTER_SWITCH] ?: DEFAULT_MASTER_SWITCH)
            if (master != null && master != currentMaster) {
                previousMaster = currentMaster
                preferences[MASTER_SWITCH] = master.name
            }
            overrides.forEach { (name, level) ->
                val key = toolPermissionKey(name)
                val previous = preferences[key]?.let { PermissionLevel.fromString(it) }
                if (previous != level) {
                    previousOverrides[name] = previous
                    if (level == null) preferences.remove(key) else preferences[key] = level.name
                }
            }
        }
        return ToolPermissionChange(previousMaster, previousOverrides)
    }

    /**
     * Get permission level flow for a specific tool
     * If no permission is set for the tool, returns ASK as default
     */
    fun getToolPermissionFlow(toolName: String): Flow<PermissionLevel> {
        return context.toolPermissionsDataStore.data.map { preferences ->
            val key = toolPermissionKey(toolName)
            PermissionLevel.fromString(preferences[key] ?: PermissionLevel.ASK.name)
        }
    }
    
    // MCP startup registers verified plugins concurrently, while permission checks may read this.
    private val operationDescriptionRegistry = ConcurrentHashMap<String, (AITool) -> String>()
    
    /**
     * Register a description generator for a tool
     */
    fun registerOperationDescription(toolName: String, descriptionGenerator: (AITool) -> String) {
        operationDescriptionRegistry[toolName] = descriptionGenerator
    }
    
    /**
     * Save permission level settings
     */
    suspend fun saveMasterSwitch(level: PermissionLevel) {
        context.toolPermissionsDataStore.edit { preferences ->
            preferences[MASTER_SWITCH] = level.name
        }
    }
    
    /**
     * Save permission level for a specific tool
     */
    suspend fun saveToolPermission(toolName: String, level: PermissionLevel) {
        context.toolPermissionsDataStore.edit { preferences ->
            val key = toolPermissionKey(toolName)
            preferences[key] = level.name
        }
    }
    
    suspend fun clearToolPermission(toolName: String) {
        context.toolPermissionsDataStore.edit { preferences ->
            val key = toolPermissionKey(toolName)
            preferences.remove(key)
        }
    }
    
    /**
     * Save permission levels for multiple tools at once
     */
    suspend fun saveToolPermissions(toolPermissions: Map<String, PermissionLevel>) {
        context.toolPermissionsDataStore.edit { preferences ->
            toolPermissions.forEach { (toolName, level) ->
                val key = toolPermissionKey(toolName)
                preferences[key] = level.name
            }
        }
    }
    
    /**
     * Get permission level for a specific tool (synchronous, for one-time reads)
     * If no permission is set for the tool, returns ASK as default
     */
    suspend fun getToolPermission(toolName: String): PermissionLevel {
        val preferences = context.toolPermissionsDataStore.data.first()
        val key = toolPermissionKey(toolName)
        return PermissionLevel.fromString(preferences[key] ?: PermissionLevel.ASK.name)
    }
    
    suspend fun getToolPermissionOverride(toolName: String): PermissionLevel? {
        val preferences = context.toolPermissionsDataStore.data.first()
        val key = toolPermissionKey(toolName)
        val stored = preferences[key]
        return stored?.let { PermissionLevel.fromString(it) }
    }
    
    /**
     * Get human-readable description of an operation
     */
    fun getOperationDescription(tool: AITool): String {
        return operationDescriptionRegistry[tool.name]?.invoke(tool) ?: context.getString(R.string.tool_permission_operation, tool.name)
    }
    
    /**
     * Check if a tool is allowed to execute
     */
    suspend fun checkToolPermission(tool: AITool): ToolPermissionCheckResult {
        AppLogger.d(TAG, "Starting permission check: ${tool.name}")
        
        val preferences = context.toolPermissionsDataStore.data.first()
        val masterSwitch = PermissionLevel.fromString(preferences[MASTER_SWITCH] ?: DEFAULT_MASTER_SWITCH)
        val key = toolPermissionKey(tool.name)
        val overrideLevel = preferences[key]?.let { PermissionLevel.fromString(it) }
        
        val permissionLevel = overrideLevel ?: masterSwitch

        // 会话级放行优先于 ASK：用户在本次运行里已经点过“本次允许”就不再弹窗。
        // 对应 Operit2 `ToolPermissionSystem.sessionApprovedTools`。
        // 只有 ALLOW 和会话放行才直接过，FORBID 不受影响。
        if (permissionLevel != PermissionLevel.FORBID && SessionApprovedTools.isApproved(tool.name)) {
            AppLogger.d(TAG, "Session-approved, granting: ${tool.name}")
            return ToolPermissionCheckResult.GRANTED
        }

        return when (permissionLevel) {
            PermissionLevel.ALLOW -> ToolPermissionCheckResult.GRANTED
            PermissionLevel.ASK -> requestPermission(tool)
            PermissionLevel.FORBID -> ToolPermissionCheckResult.DENIED
        }
    }
    
    /**
     * Request permission from the user to execute a tool
     */
    private suspend fun requestPermission(tool: AITool): ToolPermissionCheckResult {
        // Get operation description
        val operationDescription = getOperationDescription(tool)
        
        AppLogger.d(TAG, "Requesting permission: ${tool.name}")
        
        // Clear existing request
        currentPermissionCallback = null
        permissionRequestInfo = null
        _permissionRequestState.value = null
        
        // Set up new request
        val requestInfo = Pair(tool, operationDescription)
        permissionRequestInfo = requestInfo
        _permissionRequestState.value = requestInfo
        
        AppLogger.d(TAG, "Permission request state updated: ${tool.name}")
        
        return withTimeoutOrNull(PERMISSION_REQUEST_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                currentPermissionCallback = { result ->
                    AppLogger.d(TAG, "Permission result received: $result for ${tool.name}")
                    currentPermissionCallback = null
                    permissionRequestInfo = null
                    _permissionRequestState.value = null
                    continuation.resume(result)
                }
                
                // Start permission request on main thread
                mainHandler.post {
                    // Use overlay to show permission request
                    if (!permissionRequestOverlay.hasOverlayPermission()) {
                        AppLogger.w(TAG, "No overlay permission, requesting...")
                        permissionRequestOverlay.requestOverlayPermission()
                        currentPermissionCallback?.invoke(
                            ToolPermissionCheckResult.OVERLAY_PERMISSION_REQUIRED
                        )
                    } else {
                        permissionRequestOverlay.show(tool, operationDescription) { result ->
                            handlePermissionResult(result)
                        }
                    }
                }
            }
        } ?: run {
            AppLogger.d(TAG, "Permission request timed out: ${tool.name}")
            currentPermissionCallback = null
            permissionRequestInfo = null
            _permissionRequestState.value = null
            ToolPermissionCheckResult.CONFIRMATION_TIMEOUT
        }
    }
    
    /**
     * Handle permission request result
     */
    fun handlePermissionResult(result: PermissionRequestResult) {
        val permissionResult =
            when (result) {
                PermissionRequestResult.ALLOW -> ToolPermissionCheckResult.GRANTED
                PermissionRequestResult.DENY -> ToolPermissionCheckResult.DENIED
                PermissionRequestResult.ALWAYS_ALLOW -> {
                    permissionRequestInfo?.first?.let { tool ->
                        CoroutineScope(Dispatchers.IO).launch {
                            saveToolPermission(tool.name, PermissionLevel.ALLOW)
                        }
                    }
                    ToolPermissionCheckResult.GRANTED
                }
            }
        currentPermissionCallback?.invoke(permissionResult)
    }
    
    /**
     * Get current permission request info
     */
    fun getCurrentPermissionRequest(): Pair<AITool, String>? {
        return permissionRequestInfo
    }
    
    /**
     * Check if there is an active permission request
     */
    fun hasActivePermissionRequest(): Boolean {
        return permissionRequestInfo != null && currentPermissionCallback != null
    }
    
    /**
     * Refresh permission request state
     */
    fun refreshPermissionRequestState(): Boolean {
        return hasActivePermissionRequest()
    }
}
