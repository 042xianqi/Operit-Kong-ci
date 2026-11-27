//! 终端输出压缩的 Rust 实现，与 Kotlin `TerminalOutputCompressor` 同语义。
//!
//! 为什么下沉：`compress()` 在每次工具输出超过 2000 字符时都会跑一遍，内部有
//! 约 20 个正则（SGR 剥离、诊断词、结构化探测、6 类构建工具的进度行）。
//! Kotlin 侧每条都要走 java.util.regex，长输出（几十万行 Gradle 日志）时
//! 正则回溯和 Matcher 分配是明显的 CPU 与 GC 压力。这里改为字节级手写扫描。
//!
//! 与 Kotlin 的对应关系逐条注释在函数上，避免两边漂移。

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

const MIN_LEN: usize = 2000;
const MIN_RUN: usize = 4;
const MIN_SAVED: usize = 256;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Tool {
    Gradle,
    Maven,
    Cargo,
    Node,
    Pytest,
    Go,
}

impl Tool {
    pub fn strategy(self) -> &'static str {
        match self {
            Tool::Gradle => "gradle-progress",
            Tool::Maven => "maven-downloads",
            Tool::Cargo => "cargo-progress",
            Tool::Node => "node-test-passes",
            Tool::Pytest => "pytest-passes",
            Tool::Go => "go-test-passes",
        }
    }
}

/// 去掉 SGR 颜色序列 `ESC [ digits/; m`。对应 Kotlin `sgr`。
pub fn strip_sgr(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i] == 0x1b && i + 1 < bytes.len() && bytes[i + 1] == b'[' {
            let mut j = i + 2;
            while j < bytes.len() && (bytes[j].is_ascii_digit() || bytes[j] == b';') {
                j += 1;
            }
            if j < bytes.len() && bytes[j] == b'm' {
                i = j + 1;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// 命令行 token 允许的字符集，对应 Kotlin `commandToken`。
fn is_command_token_char(c: u8) -> bool {
    c.is_ascii_alphanumeric()
        || matches!(c, b'_' | b'.' | b'/' | b':' | b'=' | b'+' | b'@' | b'%' | b',' | b'-')
}

fn is_command_token(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(is_command_token_char)
}

/// 诊断关键词，对应 Kotlin `diagnostic`（IGNORE_CASE）。
/// 注意 `^[ew]:[ \t]` 是行首锚点，单独处理。
const DIAG_WORDS: &[&str] = &[
    "error",
    "warn",
    "fail",
    "exception",
    "assert",
    "traceback",
    "panic",
    "fatal",
    "stacktrace",
    "错误",
    "警告",
    "失败",
    "异常",
    "断言",
];

fn has_diagnostic(text: &str) -> bool {
    let trimmed = text.trim_start();
    // ^[ew]:[ \t]
    let tb = trimmed.as_bytes();
    if tb.len() >= 3
        && (tb[0] == b'e' || tb[0] == b'w')
        && tb[1] == b':'
        && (tb[2] == b' ' || tb[2] == b'\t')
    {
        return true;
    }
    let lower = text.to_lowercase();
    DIAG_WORDS.iter().any(|w| lower.contains(w))
}

/// 机器可读选项前缀，对应 Kotlin `machineOptions`。
const MACHINE_OPTIONS: &[&str] = &[
    "json", "format", "porcelain", "message-format", "xml", "csv", "junit", "report",
    "output", "result", "list", "collect-only", "tap", "null",
];

/// 识别是哪个构建工具，对应 Kotlin `identifyTool`。
pub fn identify_tool(command: &str) -> Option<Tool> {
    if command.contains('\n') || command.contains('\r') {
        return None;
    }
    let tokens: Vec<&str> = command
        .trim()
        .split(|c| c == ' ' || c == '\t')
        .filter(|s| !s.is_empty())
        .collect();
    if tokens.is_empty() {
        return None;
    }
    if tokens.iter().any(|t| !is_command_token(t)) {
        return None;
    }
    let args: Vec<&str> = tokens[1..].to_vec();
    // 任一参数是机器可读选项就放弃压缩。
    for a in &args {
        if let Some(rest) = a.strip_prefix('-') {
            let opt = rest.trim_start_matches('-').to_lowercase();
            let head = opt.split('=').next().unwrap_or("");
            if head == "z" || head == "0" {
                return None;
            }
            if MACHINE_OPTIONS.iter().any(|m| opt.starts_with(m)) {
                return None;
            }
        }
    }
    let first = tokens[0];
    match first {
        "gradle" | "gradlew" | "./gradlew" => {
            if args.iter().any(|a| is_gradle_task(a.rsplit(':').next().unwrap_or(""))) {
                Some(Tool::Gradle)
            } else {
                None
            }
        }
        "mvn" | "mvnw" | "./mvnw" => {
            if args
                .iter()
                .any(|a| matches!(*a, "compile" | "test" | "package" | "verify" | "install"))
            {
                Some(Tool::Maven)
            } else {
                None
            }
        }
        "cargo" => match args.first() {
            Some(&"build") | Some(&"test") | Some(&"check") => Some(Tool::Cargo),
            _ => None,
        },
        "npm" | "pnpm" => match args.first() {
            Some(&"test") | Some(&"build") => Some(Tool::Node),
            Some(&"run") if matches!(args.get(1), Some(&"test") | Some(&"build")) => Some(Tool::Node),
            _ => None,
        },
        "pytest" => Some(Tool::Pytest),
        "go" => match args.first() {
            Some(&"test") => Some(Tool::Go),
            _ => None,
        },
        _ => None,
    }
}

/// 对应 Kotlin `gradleTask` 正则：build|check|test(?:[A-Z]...)?|assemble...|compile...
fn is_gradle_task(s: &str) -> bool {
    if matches!(s, "build" | "check") {
        return true;
    }
    for prefix in ["test", "assemble", "compile"] {
        if let Some(rest) = s.strip_prefix(prefix) {
            if rest.is_empty() {
                return true;
            }
            if rest.chars().next().map(|c| c.is_ascii_uppercase()).unwrap_or(false) {
                return true;
            }
        }
    }
    false
}

/// 判断一行是否是「可折叠的进度/成功日志」。对应 Kotlin `progress` 表。
pub fn is_progress_line(tool: Tool, text: &str) -> bool {
    match tool {
        Tool::Gradle => is_gradle_progress(text),
        Tool::Maven => is_maven_download(text),
        Tool::Cargo => is_cargo_progress(text),
        Tool::Node => is_node_pass(text),
        Tool::Pytest => is_pytest_pass(text),
        Tool::Go => is_go_pass(text),
    }
}

/// `> Task :name[ UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED]`
fn is_gradle_progress(text: &str) -> bool {
    let rest = match text.strip_prefix("> Task :") {
        Some(v) => v,
        None => return false,
    };
    let name = rest.split(' ').next().unwrap_or("");
    if name.is_empty() {
        return false;
    }
    if !name
        .bytes()
        .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'.' | b':' | b'-'))
    {
        return false;
    }
    let tail = rest[name.len()..].trim_start();
    tail.is_empty()
        || matches!(tail, "UP-TO-DATE" | "FROM-CACHE" | "NO-SOURCE" | "SKIPPED")
}

/// `[INFO] Downloading from x: http...` / `Downloaded`
fn is_maven_download(text: &str) -> bool {
    let rest = if let Some(v) = text.strip_prefix("[INFO] Downloading from ") {
        v
    } else if let Some(v) = text.strip_prefix("[INFO] Downloaded from ") {
        v
    } else {
        return false;
    };
    let (_, url) = match rest.split_once(": ") {
        Some(v) => v,
        None => return false,
    };
    url.starts_with("http://") || url.starts_with("https://")
}

/// `  Compiling foo v1.2.3` / `Checking` / `Downloaded`；或 `test mod ... ok`
fn is_cargo_progress(text: &str) -> bool {
    if text.starts_with("test ") && text.ends_with(" ... ok") {
        return true;
    }
    let indent = text.len() - text.trim_start().len();
    if indent > 4 {
        return false;
    }
    let rest = text.trim_start();
    for kw in ["Compiling ", "Checking ", "Downloaded "] {
        if let Some(body) = rest.strip_prefix(kw) {
            let name = body.split(' ').next().unwrap_or("");
            if name.is_empty() {
                return false;
            }
            if !name
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'-'))
            {
                return false;
            }
            // 后面应出现 ` v<digit>`
            let after = &body[name.len()..];
            return after.starts_with(" v") && after.as_bytes().get(2).map_or(false, |c| c.is_ascii_digit());
        }
    }
    false
}

/// Node TAP：`ok 12 - name`；或 `PASS path.test.js`
fn is_node_pass(text: &str) -> bool {
    if let Some(rest) = text.strip_prefix("ok ") {
        let num = rest.split(' ').next().unwrap_or("");
        if !num.is_empty() && num.bytes().all(|c| c.is_ascii_digit()) {
            let after = &rest[num.len()..];
            return after.starts_with(" - ") && after.len() > 3;
        }
        return false;
    }
    if let Some(rest) = text.strip_prefix("PASS") {
        let body = rest.trim_start_matches(|c| c == ' ' || c == '\t');
        if body.len() == rest.len() {
            return false;
        }
        // 需含 .test./.spec. 且扩展名是 js/ts 系
        let stem = body.split(' ').next().unwrap_or("");
        return [".test.", ".spec."].iter().any(|m| stem.contains(m))
            && [".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs", ".mts", ".cts"]
                .iter()
                .any(|e| stem.ends_with(e));
    }
    false
}

/// pytest：`path.py ....  [ 50%]`；或 `path.py::test PASSED [ 50%]`
fn is_pytest_pass(text: &str) -> bool {
    let body = text.split(' ').next().unwrap_or("");
    if !body.ends_with(".py") && !body.contains(".py::") {
        return false;
    }
    if text.contains(" PASSED") {
        return true;
    }
    // 点进度：`path.py ....` 后跟 `[`
    if let Some(pos) = text.find(" ") {
        let dots = &text[pos + 1..];
        let dot_end = dots.find(' ').map(|p| &dots[..p]).unwrap_or(dots);
        if !dot_end.is_empty() && dot_end.bytes().all(|c| c == b'.') {
            return dots.contains('[');
        }
    }
    false
}

/// go test：`--- PASS: pkg (0.01s)`；或 `ok  pkg  0.01s` / `(cached)`
fn is_go_pass(text: &str) -> bool {
    if text.starts_with("--- PASS: ") && text.trim_end().ends_with(')') {
        return true;
    }
    if let Some(rest) = text.strip_prefix("ok") {
        let body = rest.trim_start_matches(|c| c == ' ' || c == '\t');
        if body.len() == rest.len() {
            return false;
        }
        let mut parts = body.split_whitespace();
        let _pkg = parts.next();
        match parts.next() {
            Some(t) => t.ends_with('s') || t == "(cached)",
            None => false,
        }
    } else {
        false
    }
}

/// 结构化输出（JSON/XML/CSV/diff）不压缩，对应 Kotlin `looksStructured`。
pub fn looks_structured(output: &str) -> bool {
    let first = output
        .lines()
        .find(|l| !l.trim().is_empty())
        .map(|l| l.trim_start())
        .unwrap_or("");
    // CSV 表头：至少两列，逗号分隔，列内字符受限
    if is_csv_header(first.trim()) {
        return true;
    }
    for line in output.lines() {
        let text = line.trim_start();
        if text.starts_with('{')
            || text.starts_with('<')
            || text.starts_with('"')
            || text.starts_with("diff --git ")
            || text.starts_with("+++ ")
            || text.starts_with("@@ ")
        {
            return true;
        }
        if text.starts_with('[') {
            let rest = &text[1..];
            let r = rest.trim_start();
            let c = r.as_bytes().first().copied().unwrap_or(0);
            if c == b'[' || c == b'{' || c == b'"' || c.is_ascii_digit() || c == b'-' {
                return true;
            }
            if r.starts_with("true") || r.starts_with("false") || r.starts_with("null") || r.is_empty() {
                return true;
            }
        }
        if text.starts_with("--- ")
            && !text.starts_with("--- PASS: ")
            && !text.starts_with("--- FAIL: ")
            && !text.starts_with("--- SKIP: ")
        {
            return true;
        }
    }
    false
}

fn is_csv_header(s: &str) -> bool {
    if !s.contains(',') {
        return false;
    }
    let cols: Vec<&str> = s.split(',').map(|c| c.trim()).collect();
    if cols.len() < 2 {
        return false;
    }
    cols.iter().all(|c| {
        !c.is_empty()
            && c.chars()
                .all(|ch| ch.is_alphanumeric() || matches!(ch, '_' | b'.' as char | b':' as char | b'-' as char))
    })
}

#[derive(Debug, Clone)]
pub struct Summary {
    pub output: String,
    pub omitted_lines: i32,
    pub strategy: String,
}

/// 主入口，与 Kotlin `compress()` 同语义。返回 None 表示不压缩。
pub fn compress(command: &str, output: &str) -> Option<Summary> {
    if output.chars().count() < MIN_LEN {
        return None;
    }
    let tool = identify_tool(command)?;
    let plain = strip_sgr(output);
    // 未知控制序列可能携带屏幕语义，不能当静态日志改写。
    if has_unknown_control(&plain) {
        return None;
    }
    if looks_structured(&plain) {
        return None;
    }
    // 保留原始行的换行形式。
    let parts: Vec<&str> = output.split('\n').collect();
    let last = parts.len().saturating_sub(1);
    let mut lines: Vec<String> = Vec::new();
    for (i, line) in parts.iter().enumerate() {
        if i < last {
            lines.push(format!("{}\n", line));
        } else if !line.is_empty() {
            lines.push((*line).to_string());
        }
    }
    let mut diagnostic_section = false;
    let mut foldable: Vec<bool> = Vec::with_capacity(lines.len());
    for line in &lines {
        let text = strip_sgr(line.trim_end_matches('\n').trim_end_matches('\r'));
        let known = is_progress_line(tool, &text);
        let task_identifier = tool == Tool::Gradle && known;
        if !task_identifier
            && (has_diagnostic(&text) || text.trim_start().starts_with("not ok "))
        {
            diagnostic_section = true;
        }
        foldable.push(!diagnostic_section && known);
    }
    let mut result = String::with_capacity(output.len());
    let mut omitted = 0i32;
    let mut index = 0usize;
    while index < lines.len() {
        if !foldable[index] {
            result.push_str(&lines[index]);
            index += 1;
            continue;
        }
        let mut end = index + 1;
        while end < lines.len() && foldable[end] {
            end += 1;
        }
        if end - index >= MIN_RUN {
            let count = end - index - 2;
            let newline = if lines[index].ends_with("\r\n") { "\r\n" } else { "\n" };
            result.push_str(&lines[index]);
            result.push_str(&format!("[已省略 {} 行已识别的进度或成功日志]", count));
            result.push_str(newline);
            result.push_str(&lines[end - 1]);
            omitted += count as i32;
        } else {
            for k in index..end {
                result.push_str(&lines[k]);
            }
        }
        index = end;
    }
    let saved = output.len() as i64 - result.len() as i64;
    if omitted == 0
        || saved < MIN_SAVED as i64
        || saved * 5 < output.len() as i64
    {
        return None;
    }
    Some(Summary {
        output: result,
        omitted_lines: omitted,
        strategy: tool.strategy().to_string(),
    })
}

fn has_unknown_control(s: &str) -> bool {
    let b = s.as_bytes();
    let mut i = 0usize;
    while i < b.len() {
        let c = b[i];
        if c < 0x20 && c != b'\n' && c != b'\t' {
            if c == b'\r' && i + 1 < b.len() && b[i + 1] == b'\n' {
                i += 1;
                i += 1;
                continue;
            }
            return true;
        }
        if (0x007f..=0x009f).contains(&(c as u32)) {
            return true;
        }
        i += 1;
    }
    false
}

#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_core_tools_system_terminal_NativeOutputCompress_compressJni(
    mut env: JNIEnv,
    _class: JClass,
    command: JString,
    output: JString,
) -> jstring {
    let cmd: String = match env.get_string(&command) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let out: String = match env.get_string(&output) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    match compress(&cmd, &out) {
        Some(s) => {
            // 协议：omittedLines\u{1f}strategy\u{1f}output
            let packed = format!("{}\u{1f}{}\u{1f}{}", s.omitted_lines, s.strategy, s.output);
            match env.new_string(packed) {
                Ok(v) => v.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
        None => std::ptr::null_mut(),
    }
}
