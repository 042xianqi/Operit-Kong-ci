package com.ai.assistance.operit.terminal.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.data.PackageTool
import com.ai.assistance.operit.terminal.data.TerminalRuntime
import com.ai.assistance.operit.terminal.utils.SourceManager
import com.ai.assistance.operit.terminal.utils.SSHConfigManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.collect

enum class InstallStatus {
    CHECKING,
    INSTALLED,
    NOT_INSTALLED
}

data class PackageItem(
    val id: String,
    val name: String,
    val command: String,
    val description: String = "",
    val alpinePackage: String = "",
    val preinstalled: Boolean = false,
) {
    /** 按当前包管理器取实际包名。 */
    fun packageNameFor(tool: PackageTool): String =
        if (tool == PackageTool.APK && alpinePackage.isNotEmpty()) alpinePackage else command
}

data class PackageCategory(
    val id: String,
    val name: String,
    val description: String,
    val packages: List<PackageItem>
)

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    onSetup: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val sourceManager = remember { SourceManager(context) }
    val sshConfigManager = remember { SSHConfigManager(context) }
    
    // 检查SSH是否启用
    var isSSHEnabled by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        isSSHEnabled = sshConfigManager.isEnabled()
    }
    
    val packageCategories by remember {
        derivedStateOf {
            listOf(
                PackageCategory(
                    id = "base",
                    name = "基础工具",
                    description = "下载、版本控制、解压，几乎所有场景都需要",
                    packages = listOf(
                        PackageItem("curl", "curl", "curl", "命令行下载，Rust/Node 安装脚本的前置依赖", alpinePackage = "curl"),
                        PackageItem("wget", "wget", "wget", "另一个下载工具，部分脚本只认 wget", alpinePackage = "wget"),
                        PackageItem("git", "git", "git", "版本控制，克隆仓库必需", alpinePackage = "git"),
                        PackageItem("tar", "tar", "tar", "解压 tar.gz 与 tar.zst", alpinePackage = "tar"),
                        PackageItem("tar", "tar", "tar", "解压 tar.gz，Alpine 由 busybox 提供", alpinePackage = "tar", preinstalled = true),
                        PackageItem("unzip", "unzip", "unzip", "解压 zip，Alpine 由 busybox 提供", alpinePackage = "unzip", preinstalled = true),
                    )
                ),
                PackageCategory(
                    id = "build",
                    name = "编译工具链",
                    description = "编译 C/C++/Rust 原生库的最小集合",
                    packages = listOf(
                        PackageItem("gcc", "GCC", "build-essential", "C 编译器与链接器", alpinePackage = "gcc"),
                        PackageItem("gpp", "G++", "g++", "C++ 编译器", alpinePackage = "g++"),
                        PackageItem("make", "make", "make", "构建工具", alpinePackage = "make"),
                        PackageItem("cmake", "CMake", "cmake", "跨平台构建系统", alpinePackage = "cmake"),
                        PackageItem("ninja", "Ninja", "ninja-build", "更快的构建后端", alpinePackage = "ninja"),
                        PackageItem("musldev", "C 头文件", "libc6-dev", "编译原生库需要的系统头文件", alpinePackage = "musl-dev"),
                    )
                ),
                PackageCategory(
                    id = "python",
                    name = "Python",
                    description = "内置 Alpine 已带 python3.12 与 uv",
                    packages = listOf(
                        PackageItem("python3", "Python 3", "python3", "解释器本身", alpinePackage = "python3", preinstalled = true),
                        PackageItem("pip", "pip", "python3-pip", "包安装器", alpinePackage = "py3-pip"),
                        PackageItem("venv", "venv", "python3-venv", "虚拟环境", alpinePackage = "py3-virtualenv"),
                        PackageItem("uv", "uv", "pipx install uv", "极快的 Python 包管理器", alpinePackage = "uv", preinstalled = true),
                    )
                ),
                PackageCategory(
                    id = "nodejs",
                    name = "Node.js",
                    description = "内置 Alpine 已带 nodejs/npm/pnpm",
                    packages = listOf(
                        PackageItem("nodejs", "Node.js", "nodejs", "运行时", alpinePackage = "nodejs", preinstalled = true),
                        PackageItem("npm", "npm", "npm", "包管理器", alpinePackage = "npm", preinstalled = true),
                        PackageItem("pnpm", "pnpm", "pnpm", "更省磁盘的包管理器", alpinePackage = "pnpm", preinstalled = true),
                        PackageItem("typescript", "TypeScript", "typescript", "类型系统编译到 JS", alpinePackage = ""),
                    )
                ),
                PackageCategory(
                    id = "rust",
                    name = "Rust",
                    description = "编译 Rust 项目，本工程的 native 库需要",
                    packages = listOf(
                        PackageItem("rust", "rustup", "RUST_INSTALL_COMMAND", "Rust 工具链安装器", alpinePackage = "rust"),
                        PackageItem("cargo", "cargo", "cargo", "Rust 包管理器", alpinePackage = "cargo"),
                    )
                ),
                PackageCategory(
                    id = "go",
                    name = "Go",
                    description = "编译 Go 项目",
                    packages = listOf(
                        PackageItem("go", "Go", "golang-go", "Go 工具链", alpinePackage = "go"),
                    )
                ),
                PackageCategory(
                    id = "java",
                    name = "Java",
                    description = "编译与运行 JVM 项目",
                    packages = listOf(
                        PackageItem("jdk17", "OpenJDK 17", "openjdk-17-jdk", "JDK", alpinePackage = "openjdk17"),
                        PackageItem("gradle", "Gradle", "gradle", "构建工具", alpinePackage = "gradle"),
                    )
                ),
                PackageCategory(
                    id = "ssh",
                    name = "SSH",
                    description = "远程连接与反向隧道",
                    packages = listOf(
                        PackageItem("ssh", "SSH 客户端", "ssh", "远程登录", alpinePackage = "openssh-client"),
                        PackageItem("sshpass", "sshpass", "sshpass", "非交互提供密码", alpinePackage = "sshpass"),
                        PackageItem("sshd", "OpenSSH 服务器", "openssh-server", "反向隧道挂载本地文件", alpinePackage = "openssh"),
                    )
                ),
                PackageCategory(
                    id = "search",
                    name = "搜索与查看",
                    description = "代码检索与文件浏览",
                    packages = listOf(
                        PackageItem("ripgrep", "ripgrep", "ripgrep", "内容搜索，比 grep 快", alpinePackage = "ripgrep"),
                        PackageItem("fd", "fd", "fd-find", "文件名搜索", alpinePackage = "fd"),
                        PackageItem("fzf", "fzf", "fzf", "模糊查找", alpinePackage = "fzf"),
                        PackageItem("vim", "vim", "vim", "终端编辑器", alpinePackage = "vim"),
                        PackageItem("tmux", "tmux", "tmux", "会话保持与分屏", alpinePackage = "tmux"),
                    )
                ),
                PackageCategory(
                    id = "media",
                    name = "媒体处理",
                    description = "音视频与图片",
                    packages = listOf(
                        PackageItem("ffmpeg", "FFmpeg", "ffmpeg", "音视频转码", alpinePackage = "ffmpeg"),
                        PackageItem("imagemagick", "ImageMagick", "imagemagick", "图片转换", alpinePackage = "imagemagick"),
                    )
                ),
            )
        }
    }

    // 跟踪每个分类的展开状态
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }
    
    // 跟踪选中的包
    val selectedPackages = remember { mutableStateMapOf<String, Boolean>() }
    
    // 跟踪每个分类的全选状态
    val categorySelectAll = remember { mutableStateMapOf<String, Boolean>() }
    
    // 新增：跟踪包的安装状态
    val packageStatus = remember { mutableStateMapOf<String, InstallStatus>() }
    val terminalManager = remember(context) { TerminalManager.getInstance(context) }

    // 当前实例的包管理器：Alpine 是 apk，Ubuntu 是 apt。探测与安装都要跟它一致。
    val activeTool by produceState(PackageTool.APT, Unit) {
        value = runCatching { terminalManager.activeRuntime() }
            .getOrDefault(TerminalRuntime.DEBIAN_LIKE).packageTool
    }
    val coroutineScope = rememberCoroutineScope()
    var checkSessionId by remember { mutableStateOf<String?>(null) }

    // 创建一个用于检查的会话，并在Composable销毁时关闭它
    DisposableEffect(terminalManager) {
        val job = coroutineScope.launch {
            try {
                val session = terminalManager.createNewSession("setup-check")
                checkSessionId = session.id
                Log.d("SetupScreen", "Created check session: ${session.id}")
            } catch (e: Exception) {
                Log.e("SetupScreen", "Failed to create check session", e)
            }
        }

        onDispose {
            job.cancel()
            checkSessionId?.let {
                Log.d("SetupScreen", "Closing check session $it")
                terminalManager.closeSession(it)
            }
        }
    }

    // 当会话准备好后，开始检查包状态
    LaunchedEffect(checkSessionId) {
        val sessionId = checkSessionId ?: return@LaunchedEffect

        // 初始化所有包为检查中状态
        val allPackages = packageCategories.flatMap { it.packages }
        allPackages.forEach { pkg ->
            packageStatus[pkg.id] = InstallStatus.CHECKING
        }

        // 并发检查所有包
        allPackages.forEach { pkg ->
            launch {
                val isInstalled = checkPackageInstalled(terminalManager, sessionId, pkg, this, activeTool)
                if (isInstalled) {
                    packageStatus[pkg.id] = InstallStatus.INSTALLED
                    selectedPackages[pkg.id] = true
                } else {
                    packageStatus[pkg.id] = InstallStatus.NOT_INSTALLED
                }

                // 检查是否需要更新分类的全选状态
                val category = packageCategories.find { c -> c.packages.any { it.id == pkg.id } }
                category?.let { cat ->
                    val allInCategoryFinishedChecking = cat.packages.all { p -> packageStatus[p.id] != InstallStatus.CHECKING }
                    if (allInCategoryFinishedChecking) {
                        val allInCategorySelected = cat.packages.all { p -> selectedPackages[p.id] == true }
                        categorySelectAll[cat.id] = allInCategorySelected
                    }
                }
            }
        }
    }

    var showSetupDialog by remember { mutableStateOf(false) }
    val commandsToRun = remember { mutableStateOf<List<String>>(emptyList()) }

    if (showSetupDialog) {
        AlertDialog(
            onDismissRequest = { showSetupDialog = false },
            title = { Text(context.getString(com.ai.assistance.operit.terminal.R.string.setup_dialog_title)) },
            text = { Text(context.getString(com.ai.assistance.operit.terminal.R.string.setup_dialog_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showSetupDialog = false
                        // 在开始设置前，显式关闭检查会话
                        checkSessionId?.let { sid ->
                            Log.d("SetupScreen", "Closing check session $sid before starting setup.")
                            terminalManager.closeSession(sid)
                            checkSessionId = null // 防止 onDispose 重复关闭
                        }
                        onSetup(commandsToRun.value)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400))
                ) {
                    Text(context.getString(com.ai.assistance.operit.terminal.R.string.dialog_confirm), color = Color.White)
                }
            },
            dismissButton = {
                Button(
                    onClick = { showSetupDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A4A))
                ) {
                    Text(context.getString(com.ai.assistance.operit.terminal.R.string.dialog_cancel), color = Color.White)
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.Gray
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = context.getString(com.ai.assistance.operit.terminal.R.string.setup_title),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Text(
            text = context.getString(com.ai.assistance.operit.terminal.R.string.setup_subtitle),
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // SSH模式警告横幅
        if (isSSHEnabled) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFA500).copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠️",
                        fontSize = 20.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Column {
                        Text(
                            text = "SSH 模式警告",
                            color = Color(0xFFFFA500),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "本页面在 SSH 模式下检测不准确，请自行手动配置 pnpm 和 python。",
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // 包分类列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(packageCategories) { category ->
                CategoryCard(
                    category = category,
                    isExpanded = expandedCategories[category.id] ?: false,
                    onExpandToggle = { expandedCategories[category.id] = !expandedCategories.getOrDefault(category.id, false) },
                    selectedPackages = selectedPackages,
                    categorySelectAll = categorySelectAll,
                    packageStatus = packageStatus
                )
            }
        }

        // 底部按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A4A))
            ) {
                Text(context.getString(com.ai.assistance.operit.terminal.R.string.skip), color = Color.White)
            }
            
            Button(
                onClick = {
                    val tool = activeTool
                    val commands = mutableListOf<String>()

                    // 包管理器不同，索引刷新命令也不同。
                    when (tool) {
                        PackageTool.APK -> {
                            commands.add("apk update")
                        }
                        PackageTool.APT -> {
                            commands.add("apt-get update")
                            commands.add("apt-get --fix-broken install -y")
                            commands.add("dpkg --configure -a")
                            commands.add("apt upgrade -y")
                        }
                        PackageTool.DNF -> {
                            commands.add("dnf makecache")
                        }
                    }

                    // 为 pip / uv 设置国内镜像，永久生效。
                    commands.add("mkdir -p ~/.config/pip")
                    commands.add("echo '[global]' > ~/.config/pip/pip.conf")
                    commands.add("echo 'index-url = https://pypi.tuna.tsinghua.edu.cn/simple' >> ~/.config/pip/pip.conf")
                    commands.add("mkdir -p ~/.config/uv")
                    commands.add("echo 'index-url = \"https://pypi.tuna.tsinghua.edu.cn/simple\"' > ~/.config/uv/uv.toml")

                    val packagesToInstall = mutableListOf<String>()
                    val selectedNpmPackages = mutableListOf<String>()
                    val selectedCustomCommands = mutableListOf<String>()

                    packageCategories.forEach { category ->
                        category.packages.forEach { pkg ->
                            if (selectedPackages[pkg.id] != true) return@forEach
                            if (packageStatus[pkg.id] == InstallStatus.INSTALLED) return@forEach
                            when {
                                pkg.id == "rust" -> {
                                    val rustSource = sourceManager.getSelectedSource(PackageManagerType.RUST)
                                    val rustEnvCommand = sourceManager.getRustSourceEnvCommand(rustSource)
                                    selectedCustomCommands.add("$rustEnvCommand && curl -v --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y")
                                }
                                pkg.id == "uv" -> {
                                    val uvCommand = if (tool == PackageTool.APK) {
                                        "pip3 install --break-system-packages uv"
                                    } else {
                                        "pipx install uv"
                                    }
                                    selectedCustomCommands.add(uvCommand)
                                }
                                category.id == "nodejs" && pkg.id != "nodejs" -> {
                                    selectedNpmPackages.add(pkg.command)
                                }
                                else -> {
                                    packagesToInstall.add(pkg.packageNameFor(tool))
                                }
                            }
                        }
                    }

                    // rustup 需要 curl 与 C 工具链；Alpine 没有 build-essential。
                    if (selectedPackages.getOrDefault("rust", false) && packageStatus["rust"] != InstallStatus.INSTALLED) {
                        if (tool == PackageTool.APK) {
                            packagesToInstall.add("curl")
                            packagesToInstall.add("bash")
                            packagesToInstall.add("build-base")
                        } else {
                            packagesToInstall.add("curl")
                            packagesToInstall.add("build-essential")
                        }
                    }
                    // uv 在 APT 下依赖 pipx。
                    if (selectedPackages.getOrDefault("uv", false) &&
                        packageStatus["uv"] != InstallStatus.INSTALLED &&
                        tool == PackageTool.APT
                    ) {
                        packagesToInstall.add("pipx")
                    }

                    if (packagesToInstall.isNotEmpty()) {
                        commands.add(tool.installCommand(packagesToInstall.distinct()))
                    }

                    if (selectedCustomCommands.isNotEmpty()) {
                        commands.addAll(selectedCustomCommands)
                        if (selectedPackages.getOrDefault("uv", false) && tool == PackageTool.APT) {
                            commands.add("pipx ensurepath")
                        }
                    }

                    if (selectedNpmPackages.isNotEmpty()) {
                        commands.add("npm config set registry https://registry.npmmirror.com/")
                        commands.add("npm cache clean --force")
                        commands.add("pnpm add -g ${selectedNpmPackages.joinToString(" ")}")
                    }

                    
                    commandsToRun.value = commands
                    showSetupDialog = true
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400))
            ) {
                Text(context.getString(com.ai.assistance.operit.terminal.R.string.start_setup), color = Color.White)
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: PackageCategory,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    selectedPackages: MutableMap<String, Boolean>,
    categorySelectAll: MutableMap<String, Boolean>,
    packageStatus: Map<String, InstallStatus>
) {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 分类标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // 标题 - 第一行
                    Text(
                        text = category.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // Operit必须标签 - 第二行
                    if (category.id == "nodejs" || category.id == "python") {
                        Text(
                            text = "(${context.getString(com.ai.assistance.operit.terminal.R.string.operit_required)})",
                            color = Color(0xFFFFA500), // Orange color
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    // 描述 - 第三行
                    Text(
                        text = category.description,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                
                // 全选按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Checkbox(
                        checked = categorySelectAll[category.id] ?: false,
                        onCheckedChange = { selectAll ->
                            categorySelectAll[category.id] = selectAll
                            category.packages.forEach { pkg ->
                                if (packageStatus[pkg.id] != InstallStatus.INSTALLED) {
                                    selectedPackages[pkg.id] = selectAll
                                }
                            }
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF006400),
                            uncheckedColor = Color.Gray
                        )
                    )
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.select_all),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                
                // 展开/收起图标
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) context.getString(com.ai.assistance.operit.terminal.R.string.collapse) else context.getString(com.ai.assistance.operit.terminal.R.string.expand),
                    tint = Color.White
                )
            }
            
            // 包列表（可展开）
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                
                category.packages.forEach { pkg ->
                    PackageItem(
                        packageItem = pkg,
                        isSelected = selectedPackages[pkg.id] ?: false,
                        onSelectionChange = { selected ->
                            selectedPackages[pkg.id] = selected
                            // 检查是否需要更新全选状态
                            val allSelectedAfterChange = category.packages.all { p ->
                                selectedPackages[p.id] == true
                            }
                            categorySelectAll[category.id] = allSelectedAfterChange
                        },
                        status = packageStatus[pkg.id] ?: InstallStatus.NOT_INSTALLED
                    )
                }
            }
        }
    }
}

@Composable
private fun PackageItem(
    packageItem: PackageItem,
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    status: InstallStatus
) {
    val context = LocalContext.current
    val isInstalled = status == InstallStatus.INSTALLED
    val isChecking = status == InstallStatus.CHECKING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isInstalled) { onSelectionChange(!isSelected) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Checkbox(
                checked = isSelected || isInstalled,
                onCheckedChange = onSelectionChange,
                enabled = !isInstalled,
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF006400),
                    uncheckedColor = Color.Gray,
                    disabledCheckedColor = Color(0xFF006400).copy(alpha = 0.5f)
                )
            )
        }
        
        Column(
            modifier = Modifier.padding(start = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = packageItem.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                if (isInstalled) {
                    Text(
                        text = " (${context.getString(com.ai.assistance.operit.terminal.R.string.installed)})",
                        color = Color.Green.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            if (packageItem.description.isNotEmpty()) {
                Text(
                    text = packageItem.description,
                    color = Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun checkPackageInstalled(
    terminalManager: TerminalManager,
    sessionId: String,
    pkg: PackageItem,
    scope: CoroutineScope,
    tool: PackageTool
): Boolean {
    // apk info -e 对已装包输出包名，对缺失包没有任何输出但 rc=1。
    // 只看文本会把缺失误判成已装，因此统一以退出码为准。
    val probe: String = when (pkg.id) {
        "rust" -> "command -v rustc || test -x ~/.cargo/bin/rustc"
        "uv" -> "command -v uv"
        "nodejs" -> "command -v node"
        "npm" -> "command -v npm"
        "pnpm" -> "command -v pnpm"
        "typescript" -> "command -v tsc"
        "go" -> "command -v go"
        "ssh" -> "command -v ssh"
        "sshpass" -> "command -v sshpass"
        "sshd" -> "command -v sshd || test -x /usr/sbin/sshd"
        "gradle" -> "command -v gradle"
        "cargo" -> "command -v cargo"
        "pip" -> "command -v pip3 || command -v pip"
        else -> tool.queryCommand(pkg.packageNameFor(tool)).joinToString(" ")
    }
    val marker = "OPERIT_PROBE_RC="
    val command = "{ " + probe + " ; } 2>&1; echo " + marker + "\$?"
    val output = executeCommandAndGetOutput(terminalManager, sessionId, command, scope)
        ?: return false
    val rc = output.substringAfterLast(marker, "")
        .takeWhile { it.isDigit() }
        .toIntOrNull() ?: return false
    return rc == 0
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun executeCommandAndGetOutput(
    terminalManager: TerminalManager,
    sessionId: String,
    command: String,
    scope: CoroutineScope
): String? {
    val deferred = CompletableDeferred<String>()
    val output = StringBuilder()
    val commandId = UUID.randomUUID().toString()
    val collectorReady = CompletableDeferred<Unit>()

    val job = scope.launch {
        terminalManager.commandExecutionEvents
            .filter { it.sessionId == sessionId && it.commandId == commandId }
            .onStart { collectorReady.complete(Unit) }
            .collect { event ->
                output.append(event.outputChunk)
                if (event.isCompleted) {
                    if (!deferred.isCompleted) {
                        deferred.complete(output.toString())
                    }
                }
            }
    }

    collectorReady.await()
    terminalManager.switchToSession(sessionId)
    terminalManager.sendCommand(command, commandId)

    val result = withTimeoutOrNull(15000L) { // 15s timeout
        deferred.await()
    }
    
    job.cancel()
    return result
} 