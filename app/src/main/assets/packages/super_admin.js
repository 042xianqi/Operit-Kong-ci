/*
METADATA
{
    "name": "super_admin",
    "display_name": {
        "zh": "超级管理员",
        "en": "Super Admin"
    },
    "description": {
        "zh": "超级管理员工具集，提供终端命令和Shell操作的高级功能。terminal 工具运行在 proot 容器中，默认实例是 Alpine（包管理器 apk，不是 apt）。运行前先确认发行版：`cat /etc/os-release`。已有工具不要重复安装：node/npm/pnpm/python3/uv 在 Alpine 中已预装。安装软件必须用对应包管理器：Alpine 用 `apk add`，Ubuntu/Debian 用 `apt-get install`，Fedora 用 `dnf install`；在 Alpine 上用 apt 会失败。shell 工具通过 Shizuku/Root 直接执行 Android 系统命令。",
        "en": "Super admin toolkit for terminal and shell operations. The terminal tool runs inside a proot container whose default instance is Alpine (package manager is apk, NOT apt). Check the distribution first with `cat /etc/os-release`. Do not reinstall what already exists: node/npm/pnpm/python3/uv are preinstalled on Alpine. Always use the matching package manager: `apk add` on Alpine, `apt-get install` on Ubuntu/Debian, `dnf install` on Fedora; using apt on Alpine fails. The shell tool executes Android system commands directly via Shizuku/Root."
    },
    "enabledByDefault": true,
    "category": "System",
    "tools": [
        {
            "name": "terminal",
            "description": {
                "zh": "在 proot 容器中执行命令并收集输出。默认实例是 Alpine：包管理器是 apk，不要用 apt。首次使用先运行 `cat /etc/os-release` 确认发行版，或直接调 terminal_env 一次拿到全貌。已预装（不必重装）：node、npm、pnpm、python3、uv、bash、busybox。未预装：curl、wget、git、ssh、gcc、make、cmake。安装方式按发行版：Alpine `apk add <pkg>`，Ubuntu/Debian `apt-get install -y <pkg>`，Fedora `dnf install -y <pkg>`。常用命令：查发行版 `cat /etc/os-release`，查已装包 `apk info -e <pkg>`，列文件 `ls -la`，搜索内容 `grep -rn 'x' .`，看磁盘 `df -h`，看进程 `ps aux`，解压 `tar -xzf a.tar.gz` / `busybox unzip a.zip`。已挂载 sdcard 与 storage，可直接访问 Android 存储。会话内工作目录保持，上下文连贯。强烈建议每次显式传 timeoutMs。禁止使用 `set -e`、`set -o errexit` 等会改变 shell 退出行为的命令，这会导致会话退出并返回失败。未传 timeoutMs 时前台默认 15 秒；background=true 不使用该默认值。超时会精确取消本次命令。",
                "en": "Run commands inside a proot container and collect output. Default instance is Alpine: package manager is apk, not apt. Run `cat /etc/os-release` first, or call terminal_env once for the full picture. Preinstalled (no need to install): node, npm, pnpm, python3, uv, bash, busybox. Not preinstalled: curl, wget, git, ssh, gcc, make, cmake. Install per distribution: `apk add <pkg>` on Alpine, `apt-get install -y <pkg>` on Ubuntu/Debian, `dnf install -y <pkg>` on Fedora. Common commands: distribution `cat /etc/os-release`, installed package `apk info -e <pkg>`, list files `ls -la`, search content with grep, disk `df -h`, processes `ps aux`, extract `tar -xzf a.tar.gz` / `busybox unzip a.zip`. sdcard and storage are mounted, so Android storage is directly accessible. Working directory persists across commands. Strongly recommend passing timeoutMs explicitly. Do not use `set -e` or `set -o errexit`, which change shell exit behavior and can terminate the session with a failure. Without timeoutMs, foreground defaults to 15s; background=true does not use that default. Timeout cancels only this command."
            },
            "parameters": [
                {
                    "name": "command",
                    "description": {
                        "zh": "要执行的命令",
                        "en": "Command to execute."
                    },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "background",
                    "description": {
                        "zh": "是否在后台运行命令,\"true\" 表示后台执行并立即返回,适合启动服务器等长时间运行的任务（AI 不会收到该命令的输出结果），\"false\" 或未提供则前台执行并等待并返回命令结果",
                        "en": "Run command in background. 'true' runs in background and returns immediately (good for long-running tasks like servers; AI will not receive output). 'false' or omitted runs in foreground and returns the command result."
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "timeoutMs",
                    "description": {
                        "zh": "可选超时（毫秒，最低3000ms）。强烈建议显式传入；未传时前台默认15000ms，background=true时不使用默认超时。",
                        "en": "Optional timeout (ms, minimum 3000ms). Strongly recommended to pass explicitly; if omitted, foreground defaults to 15000ms, and background=true does not use the default timeout."
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "terminal_env",
            "description": {
                "zh": "探测当前终端实例的真实环境，返回发行版、包管理器、已安装与缺失的常用工具。安装任何软件前先调这个，避免在 Alpine 上用 apt 或重装已预装工具。返回结构：distro、packageManager、installCommand、installed（可直接用）、missing（需安装）、preinstalled（发行版自带，一般不必重装）。",
                "en": "Probe the real environment of the current terminal instance: distribution, package manager, and which common tools are installed or missing. Call this before installing anything, to avoid using apt on Alpine or reinstalling preinstalled tools. Returns distro, packageManager, installCommand, installed (ready to use), missing (need install), preinstalled (shipped by the distro)."
            },
            "parameters": []
        },
        {
            "name": "terminal_wait",
            "description": {
                "zh": "等待同一终端会话中的上一条命令执行完成。与 sleep 不同，本工具会在命令实际完成时提前返回，而不是固定睡眠。超时时仅取消本次排队等待；使用 terminal_poll 等待托管任务不会取消任务。",
                "en": "Wait until the previous command in the same terminal session finishes. Unlike sleep, this tool can return early as soon as the command actually completes. Timeout cancels only this queued wait. Use terminal_poll to wait for a managed task without cancellation."
            },
            "parameters": [
                {
                    "name": "sessionId",
                    "description": {
                        "zh": "可选目标会话ID。不传则使用当前对话的默认会话；无 chatId 时为 super_admin_default_session。",
                        "en": "Optional target session ID. If omitted, uses the current chat's default session; without chatId it is super_admin_default_session."
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "timeoutMs",
                    "description": {
                        "zh": "可选超时（毫秒，最低3000ms）。未传时默认300000ms（5分钟）。",
                        "en": "Optional timeout (ms, minimum 3000ms). Defaults to 300000ms (5 minutes) if omitted."
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "terminal_start",
            "description": {
                "zh": "启动托管终端命令，返回 runId 和当前状态。yieldMs 只限制本次等待；timeoutMs 限制含排队的总执行时间。命令仍运行时用 terminal_poll 查询同一 runId，不要重跑。output/screen 是替换快照。",
                "en": "Start a managed terminal command and return runId/status. yieldMs limits this wait; timeoutMs limits total execution including queue time. Poll the same runId; do not restart. output/screen are replacement snapshots."
            },
            "parameters": [
                {
                    "name": "command",
                    "description": {
                        "zh": "要执行的命令",
                        "en": "要执行的命令"
                    },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "sessionId",
                    "description": {
                        "zh": "可选会话 ID；未传则创建或获取当前对话默认会话",
                        "en": "可选会话 ID；未传则创建或获取当前对话默认会话"
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "timeoutMs",
                    "description": {
                        "zh": "执行期限，至少 3000ms，默认 1800000ms",
                        "en": "执行期限，至少 3000ms，默认 1800000ms"
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "yieldMs",
                    "description": {
                        "zh": "单次等待 0～30000ms，默认 10000ms；到期不取消命令",
                        "en": "单次等待 0～30000ms，默认 10000ms；到期不取消命令"
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "terminal_poll",
            "description": {
                "zh": "查询同一 runId 的输出尾部、动态画面和状态；等待结束不取消任务，不发送探测命令。失效 ID 会报错，不自动重跑。",
                "en": "Poll output tail, screen and status for the same runId without cancelling or submitting probes. Expired IDs fail explicitly."
            },
            "parameters": [
                {
                    "name": "runId",
                    "description": {
                        "zh": "terminal_start 返回的任务 ID",
                        "en": "terminal_start 返回的任务 ID"
                    },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "yieldMs",
                    "description": {
                        "zh": "单次等待 0～30000ms，默认 10000ms",
                        "en": "单次等待 0～30000ms，默认 10000ms"
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "terminal_cancel",
            "description": {
                "zh": "精确取消指定 runId；排队任务只移除自己，运行任务中断后不能恢复时退役会话。查看 cancellationOutcome 确认清理结果。",
                "en": "Cancel the specified runId and inspect cancellationOutcome. Queued tasks only remove themselves; unrecoverable running sessions are retired."
            },
            "parameters": [
                {
                    "name": "runId",
                    "description": {
                        "zh": "terminal_start 返回的任务 ID",
                        "en": "terminal_start 返回的任务 ID"
                    },
                    "type": "string",
                    "required": true
                }
            ]
        },
        {
            "name": "terminal_getscreen",
            "description": {
                "zh": "获取目标终端会话可见屏幕内容（仅一屏，不包含历史滚动缓冲）。可通过 sessionId 指定会话；不传则使用当前对话的默认会话。",
                "en": "Get the visible screen content for the target terminal session (single screen only, no scrollback history). Pass sessionId to target a session; if omitted, uses the current chat's default session."
            },
            "parameters": [
                {
                    "name": "sessionId",
                    "description": {
                        "zh": "可选目标会话ID。不传则使用当前对话的默认会话；无 chatId 时为 super_admin_default_session。",
                        "en": "Optional target session ID. If omitted, uses the current chat's default session; without chatId it is super_admin_default_session."
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "terminal_input",
            "description": {
                "zh": "向目标终端会话写入输入。可通过 sessionId 指定会话；不传则使用当前对话的默认会话。input 与 control 至少传一个。常见用法：先写 input，再写 control=enter 提交；control=ctrl 且 input=c 可发送 Ctrl+C。",
                "en": "Write input to the target terminal session. Pass sessionId to target a session; if omitted, uses the current chat's default session. Provide at least one of input or control. Typical usage: send input first, then control=enter to submit; use control=ctrl with input=c for Ctrl+C."
            },
            "parameters": [
                {
                    "name": "sessionId",
                    "description": {
                        "zh": "可选目标会话ID。不传则使用当前对话的默认会话；无 chatId 时为 super_admin_default_session。",
                        "en": "Optional target session ID. If omitted, uses the current chat's default session; without chatId it is super_admin_default_session."
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "input",
                    "description": {
                        "zh": "写入终端的文本",
                        "en": "Text to write to terminal."
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "control",
                    "description": {
                        "zh": "控制键，例如 enter / tab / esc / ctrl",
                        "en": "Control key, e.g. enter / tab / esc / ctrl."
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "shell",
            "description": {
                "zh": "通过Shizuku/Root权限直接在Android系统中执行Shell命令。运行环境：直接访问Android系统，具有系统级权限，适用于需要操作Android系统底层的场景（如pm、am等系统命令）。一次性调用默认30秒，可用timeoutMs延长；Root调用独占进程，超时或取消仅清理本次进程组。输出合并并保留末尾256KiB；后台服务请自行重定向输入输出。",
                "en": "Execute shell commands directly on Android with Shizuku/Root. Environment: direct Android system access with system-level privileges, suitable for low-level commands such as pm/am. One-shot calls default to 30s; set timeoutMs to extend. Root calls own an isolated process group, cleaned up on timeout or cancellation. Output is merged and limited to the last 256KiB; redirect daemon input/output explicitly."
            },
            "parameters": [
                {
                    "name": "command",
                    "description": {
                        "zh": "要执行的Shell命令",
                        "en": "Shell command to execute."
                    },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "timeoutMs",
                    "description": {
                        "zh": "本次执行期限，正整数毫秒，默认30000。超时返回失败，不自动重试；可根据需要延长。",
                        "en": "Execution deadline in positive integer milliseconds; defaults to 30000. Timeout fails without automatic retry; increase for longer commands."
                    },
                    "type": "number",
                    "required": false
                }
            ]
        }
    ]
}*/
const superAdmin = (function () {
    const MAX_INLINE_TERMINAL_OUTPUT_CHARS = 12000;
    const DEFAULT_FOREGROUND_TIMEOUT_MS = 15000;
    const DEFAULT_WAIT_TIMEOUT_MS = 300000;
    const MIN_TIMEOUT_MS = 3000;
    const DEFAULT_TERMINAL_SESSION_NAME = "super_admin_default_session";
    const BACKGROUND_TERMINAL_SESSION_PREFIX = "super_admin_background";
    function getCurrentChatSessionSuffix() {
        const chatId = getChatId();
        if (chatId === undefined) {
            return "";
        }
        const normalizedChatId = chatId.trim();
        if (!normalizedChatId) {
            return "";
        }
        return normalizedChatId.replace(/[^a-zA-Z0-9._-]+/g, "_");
    }
    function getDefaultTerminalSessionName() {
        const chatSuffix = getCurrentChatSessionSuffix();
        return chatSuffix
            ? `${DEFAULT_TERMINAL_SESSION_NAME}_${chatSuffix}`
            : DEFAULT_TERMINAL_SESSION_NAME;
    }
    function getBackgroundTerminalSessionName() {
        const chatSuffix = getCurrentChatSessionSuffix();
        const prefix = chatSuffix
            ? `${BACKGROUND_TERMINAL_SESSION_PREFIX}_${chatSuffix}`
            : BACKGROUND_TERMINAL_SESSION_PREFIX;
        return `${prefix}_${Date.now()}`;
    }
    async function persistTerminalOutputIfTooLong(command, result) {
        const outputStr = typeof result?.output === "string"
            ? result.output
            : String(result?.output ?? "");
        if (outputStr.length <= MAX_INLINE_TERMINAL_OUTPUT_CHARS) {
            return null;
        }
        await Tools.Files.mkdir(OPERIT_CLEAN_ON_EXIT_DIR, true);
        const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
        const rand = Math.floor(Math.random() * 1000000);
        const filePath = `${OPERIT_CLEAN_ON_EXIT_DIR}/terminal_output_${timestamp}_${rand}.log`;
        await Tools.Files.write(filePath, outputStr, false);
        return {
            command,
            output: "(saved_to_file)",
            exitCode: result?.exitCode,
            sessionId: result?.sessionId,
            timedOut: result?.timedOut === true,
            context_preserved: result?.contextPreserved,
            output_saved_to: filePath,
            output_chars: outputStr.length,
            operit_clean_on_exit_dir: OPERIT_CLEAN_ON_EXIT_DIR,
            hint: "Output is large and saved to file. Use read_file_part or grep_code to inspect it.",
        };
    }
    /**
     * 在 proot 容器中执行终端命令并收集输出结果（默认实例 Alpine，包管理器 apk）
     * 运行环境：proot 容器，已正确挂载 sdcard 与 storage 目录
     * 禁止使用 set -e / set -o errexit 等会改变 shell 退出行为的命令，否则可能导致终端会话退出并卡死
     * @param command - 要执行的命令
     * @param background - 是否后台运行（"true" 为后台执行并立即返回，适合启动服务器等长时间运行任务，AI 不会收到该命令的输出结果）
     * @param timeoutMs - 可选的超时时间（毫秒，最低 3000ms）。强烈建议显式传入；前台未传时默认 15000ms，后台模式不应用该默认值。
     */
    async function terminal(params) {
        try {
            if (!params.command) {
                throw new Error("命令不能为空");
            }
            const command = params.command;
            const background = params.background;
            const timeoutMs = params.timeoutMs;
            console.log(`执行终端命令: ${command}`);
            const isBackground = background === "true";
            let timeout;
            if (!isBackground) {
                if (timeoutMs !== undefined) {
                    const parsedTimeout = parseInt(timeoutMs, 10);
                    if (!Number.isFinite(parsedTimeout) || parsedTimeout < MIN_TIMEOUT_MS) {
                        throw new Error(`timeoutMs必须是整数且不少于${MIN_TIMEOUT_MS}毫秒`);
                    }
                    timeout = parsedTimeout;
                }
                else {
                    timeout = DEFAULT_FOREGROUND_TIMEOUT_MS;
                }
            }
            if (isBackground) {
                const session = await Tools.System.terminal.create(getBackgroundTerminalSessionName());
                const sessionId = session.sessionId;
                // 调用系统工具执行终端命令
                (async () => {
                    try {
                        await Tools.System.terminal.exec(sessionId, command);
                    }
                    catch (error) {
                        console.error(`[terminal/background] 错误: ${error.message}`);
                        console.error(error.stack);
                    }
                })();
                return {
                    command: command,
                    background: true,
                    sessionId: sessionId,
                    started: true
                };
            }
            // 创建或获取一个默认会话
            const session = await Tools.System.terminal.create(getDefaultTerminalSessionName());
            const sessionId = session.sessionId;
            // 调用系统工具执行终端命令
            const result = await Tools.System.terminal.exec(sessionId, command, timeout);
            const timedOut = result.timedOut === true;
            const persistedResult = await persistTerminalOutputIfTooLong(command, result);
            if (persistedResult) {
                persistedResult.timeoutMsUsed = timeout;
                return persistedResult;
            }
            return {
                command: command,
                output: result.output,
                exitCode: result.exitCode,
                sessionId: result.sessionId,
                timedOut: timedOut,
                timeoutMsUsed: timeout,
                context_preserved: result.contextPreserved
            };
        }
        catch (error) {
            console.error(`[terminal] 错误: ${error.message}`);
            console.error(error.stack);
            // 保留终端失败的结构化结果，让外层显示失败并保留输出和退出码。
            if (error.data && typeof error.data.exitCode === "number" && error.data.exitCode !== 0) {
                error.data.context_preserved = error.data.contextPreserved;
                return { success: false, message: error.message, data: error.data };
            }
            throw error;
        }
    }
    async function bash(params) {
        return terminal(params);
    }
    /**
     * 等待同一终端会话中的上一条命令执行完成
     * 原理：向同会话追加一个内部 marker 命令。由于会话按序执行，marker 开始执行即代表前序命令已完成。
     * @param sessionId - 可选会话ID；不传时使用当前对话的默认会话，无 chatId 时为 super_admin_default_session
     * @param timeoutMs - 可选超时（毫秒，最低 3000ms）；未传默认 300000ms
     */
    async function terminal_wait(params = {}) {
        try {
            const timeoutMs = params.timeoutMs;
            let timeout = DEFAULT_WAIT_TIMEOUT_MS;
            if (timeoutMs !== undefined) {
                const parsedTimeout = parseInt(timeoutMs, 10);
                if (!Number.isFinite(parsedTimeout) || parsedTimeout < MIN_TIMEOUT_MS) {
                    throw new Error(`timeoutMs必须是整数且不少于${MIN_TIMEOUT_MS}毫秒`);
                }
                timeout = parsedTimeout;
            }
            const session = params.sessionId
                ? { sessionId: params.sessionId }
                : await Tools.System.terminal.create(getDefaultTerminalSessionName());
            const sessionId = session.sessionId;
            const marker = `__OPERIT_TERMINAL_WAIT_DONE_${Date.now()}_${Math.floor(Math.random() * 1000000)}__`;
            const waitCommand = `printf '${marker}\\n'`;
            const startedAt = Date.now();
            const result = await Tools.System.terminal.exec(sessionId, waitCommand, timeout);
            const elapsedMs = Date.now() - startedAt;
            const timedOut = result?.timedOut === true;
            const outputStr = typeof result?.output === "string"
                ? result.output
                : String(result?.output ?? "");
            const markerSeen = outputStr.includes(marker);
            return {
                sessionId,
                timedOut,
                timeoutMsUsed: timeout,
                elapsedMs,
                waitCompleted: !timedOut && markerSeen,
                markerSeen,
                exitCode: result?.exitCode,
                context_preserved: result.contextPreserved
            };
        }
        catch (error) {
            console.error(`[terminal_wait] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    /**
     * 通过Shizuku/Root权限在Android系统中执行Shell命令
     * 运行环境：直接访问Android系统，具有系统级权限
     * @param command - 要执行的Shell命令
     */
    async function shell(params) {
        try {
            if (!params.command) {
                throw new Error("命令不能为空");
            }
            const command = params.command;
            console.log(`执行Shell命令: ${command}`);
            // 通过Shizuku/Root权限执行shell操作
            const result = await Tools.System.shell(command, params.timeoutMs);
            return {
                command: command,
                output: result.output,
                exitCode: result.exitCode
            };
        }
        catch (error) {
            console.error(`[shell] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    /**
     * 获取目标终端会话可见屏幕内容（仅一屏，不包含历史）
     * @param sessionId - 可选会话ID；不传时使用当前对话的默认会话，无 chatId 时为 super_admin_default_session
     */
    async function terminal_getscreen(params = {}) {
        try {
            const session = params.sessionId
                ? { sessionId: params.sessionId }
                : await Tools.System.terminal.create(getDefaultTerminalSessionName());
            const sessionId = session.sessionId;
            const result = await Tools.System.terminal.screen(sessionId);
            return {
                sessionId: result.sessionId ?? sessionId,
                rows: result.rows,
                cols: result.cols,
                content: result.content
            };
        }
        catch (error) {
            console.error(`[terminal_getscreen] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    /**
     * 向目标终端会话写入输入
     * @param sessionId - 可选会话ID；不传时使用当前对话的默认会话，无 chatId 时为 super_admin_default_session
     * @param input - 文本输入
     * @param control - 控制键
     */
    async function terminal_input(params = {}) {
        try {
            if (params.input === undefined && params.control === undefined) {
                throw new Error("input和control至少需要提供一个");
            }
            const session = params.sessionId
                ? { sessionId: params.sessionId }
                : await Tools.System.terminal.create(getDefaultTerminalSessionName());
            const sessionId = session.sessionId;
            const result = await Tools.System.terminal.input(sessionId, {
                input: params.input,
                control: params.control
            });
            return {
                sessionId,
                input: params.input,
                control: params.control,
                result
            };
        }
        catch (error) {
            console.error(`[terminal_input] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    /** 新入口保留任务 ID，查询请求结束后命令仍由应用持有。 */
    function taskMilliseconds(value, name, minimum, maximum = Number.MAX_SAFE_INTEGER) {
        if (value === undefined)
            return undefined;
        const parsed = Number(value);
        if (!/^\d+$/.test(value) || !Number.isSafeInteger(parsed) || parsed < minimum || parsed > maximum) {
            throw new Error(`${name}必须是${minimum}～${maximum}之间的整数毫秒`);
        }
        return parsed;
    }
    async function terminal_start(params) {
        try {
            if (!params.command.trim())
                throw new Error("命令不能为空");
            const timeoutMs = taskMilliseconds(params.timeoutMs, "timeoutMs", MIN_TIMEOUT_MS);
            const yieldMs = taskMilliseconds(params.yieldMs, "yieldMs", 0, 30000);
            const sessionId = params.sessionId === undefined
                ? (await Tools.System.terminal.create(getDefaultTerminalSessionName())).sessionId
                : params.sessionId;
            return await Tools.System.terminal.start(sessionId, params.command, { timeoutMs, yieldMs });
        }
        catch (error) {
            console.error("[terminal_start] 托管启动失败", error);
            throw error;
        }
    }
    async function terminal_poll(params) {
        try {
            const yieldMs = taskMilliseconds(params.yieldMs, "yieldMs", 0, 30000);
            return await Tools.System.terminal.poll(params.runId, { yieldMs });
        }
        catch (error) {
            console.error("[terminal_poll] 托管查询失败", error);
            throw error;
        }
    }
    async function terminal_cancel(params) {
        try {
            return await Tools.System.terminal.cancel(params.runId);
        }
        catch (error) {
            console.error("[terminal_cancel] 托管取消失败", error);
            throw error;
        }
    }
    /**
     * 探测当前终端实例的真实环境。
     *
     * 为什么需要它：默认实例是 Alpine，包管理器是 apk；AI 若按 Ubuntu 习惯用 apt，
     * 或者重装 node/python/uv 这些已预装工具，都会失败或浪费时间。
     * 一次探测把事实交给 AI，比在每处 prompt 里写死发行版更可靠。
     */
    async function terminal_env(params) {
        const probes = [
            ['bash', 'command -v bash'],
            ['curl', 'command -v curl'],
            ['wget', 'command -v wget'],
            ['git', 'command -v git'],
            ['node', 'command -v node'],
            ['npm', 'command -v npm'],
            ['pnpm', 'command -v pnpm'],
            ['python3', 'command -v python3'],
            ['uv', 'command -v uv'],
            ['gcc', 'command -v gcc'],
            ['make', 'command -v make'],
            ['cmake', 'command -v cmake'],
            ['rustc', 'command -v rustc'],
            ['cargo', 'command -v cargo'],
            ['go', 'command -v go'],
            ['java', 'command -v java'],
            ['ssh', 'command -v ssh'],
            ['tar', 'tar --help >/dev/null 2>&1'],
            ['unzip', 'busybox unzip 2>&1 | grep -q Usage'],
        ];
        // 一次 shell 调用拿全部结果，避免逐个命令启动会话的开销。
        const script = probes
            .map(([name, cmd]) => `${cmd} >/dev/null 2>&1 && echo "${name}=1" || echo "${name}=0"`)
            .join('; ');
        const probeScript = `cat /etc/os-release 2>/dev/null | grep -E "^(ID|VERSION_ID)="; ${script}; command -v apk >/dev/null 2>&1 && echo "pm=apk" || (command -v apt-get >/dev/null 2>&1 && echo "pm=apt" || (command -v dnf >/dev/null 2>&1 && echo "pm=dnf" || echo "pm=unknown"))`;
        try {
            const result = await Tools.System.terminal.execute({ command: probeScript, timeoutMs: 30000 });
            const output = String(result?.output ?? result ?? '');
            const flags = {};
            let distroId = '';
            let versionId = '';
            let packageManager = 'unknown';
            output.split('\n').forEach((line) => {
                const text = line.trim();
                if (!text) return;
                const eq = text.indexOf('=');
                if (eq <= 0) return;
                const key = text.slice(0, eq).trim();
                const value = text.slice(eq + 1).trim().replace(/^"|"$/g, '');
                if (key === 'ID') distroId = value;
                else if (key === 'VERSION_ID') versionId = value;
                else if (key === 'pm') packageManager = value;
                else flags[key] = value === '1';
            });
            const installed = Object.keys(flags).filter((k) => flags[k]);
            const missing = Object.keys(flags).filter((k) => !flags[k]);
            const installCommand = packageManager === 'apk' ? 'apk add'
                : packageManager === 'apt' ? 'apt-get install -y'
                : packageManager === 'dnf' ? 'dnf install -y'
                : null;
            return {
                distro: distroId || 'unknown',
                version: versionId || '',
                packageManager,
                installCommand,
                installed,
                missing,
                hint: installCommand
                    ? `安装缺失工具用：${installCommand} <pkg>`
                    : '未识别包管理器，请先用 cat /etc/os-release 确认发行版',
            };
        } catch (error) {
            console.error('[terminal_env] 环境探测失败', error);
            throw error;
        }
    }
    return {
        terminal,
        bash,
        terminal_wait,
        terminal_env,
        terminal_start,
        terminal_poll,
        terminal_cancel,
        terminal_getscreen,
        terminal_input,
        shell
    };
})();
// 逐个导出
exports.terminal = superAdmin.terminal;
exports.terminal_env = superAdmin.terminal_env;
exports.bash = superAdmin.bash;
exports.terminal_wait = superAdmin.terminal_wait;
exports.terminal_start = superAdmin.terminal_start;
exports.terminal_poll = superAdmin.terminal_poll;
exports.terminal_cancel = superAdmin.terminal_cancel;
exports.terminal_getscreen = superAdmin.terminal_getscreen;
exports.terminal_input = superAdmin.terminal_input;
exports.shell = superAdmin.shell;
