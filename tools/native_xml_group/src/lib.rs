use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::Serialize;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MergePlan {
    ok: bool,
    error: String,
    /// For each node index: index of the `<tool>` node that owns it, or -1.
    owner_of: Vec<i32>,
    /// For each `<tool>` node index: index of its paired `<tool_result>`, or -1.
    result_of: Vec<i32>,
    /// For each `<tool_result>` node index: index of its paired `<tool>`, or -1.
    call_of: Vec<i32>,
}

fn fail(error: String, n: usize) -> MergePlan {
    MergePlan {
        ok: false,
        error,
        owner_of: vec![-1; n],
        result_of: vec![-1; n],
        call_of: vec![-1; n],
    }
}

/// Attribute value reader that tolerates single/double quotes.
fn attr(tag: &str, name: &str) -> Option<String> {
    let bytes = tag.as_bytes();
    let mut from = 0usize;
    while let Some(pos) = tag[from..].find(name) {
        let start = from + pos;
        let before_ok = start == 0 || !bytes[start - 1].is_ascii_alphanumeric();
        let after = start + name.len();
        if before_ok && after < tag.len() {
            let mut i = after;
            while i < tag.len() && bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            if i < tag.len() && bytes[i] == b'=' {
                i += 1;
                while i < tag.len() && bytes[i].is_ascii_whitespace() {
                    i += 1;
                }
                if i < tag.len() && (bytes[i] == b'"' || bytes[i] == b'\'') {
                    let quote = bytes[i];
                    let vstart = i + 1;
                    if let Some(end) = tag[vstart..].find(quote as char) {
                        return Some(tag[vstart..vstart + end].to_string());
                    }
                }
            }
        }
        from = start + name.len();
    }
    None
}

fn tool_name_of(xml: &str) -> Option<String> {
    let head_end = xml.find('>').map(|p| p + 1).unwrap_or(xml.len().min(512));
    attr(&xml[..head_end], "name")
}

fn is_tag(xml: &str, wanted: &str) -> bool {
    let t = xml.trim_start();
    let rest = match t.strip_prefix('<') {
        Some(r) => r,
        None => return false,
    };
    if !rest.starts_with(wanted) {
        return false;
    }
    match rest[wanted.len()..].chars().next() {
        Some(c) => c.is_whitespace() || c == '>' || c == '/',
        None => false,
    }
}

fn plan(nodes: &[String]) -> MergePlan {
    let n = nodes.len();
    let mut owner_of = vec![-1i32; n];
    let mut result_of = vec![-1i32; n];
    let mut call_of = vec![-1i32; n];

    let mut pending: Vec<usize> = Vec::new();
    let mut last_owner: i32 = -1;

    for (i, xml) in nodes.iter().enumerate() {
        if is_tag(xml, "tool_result") {
            let result_name = tool_name_of(xml);
            // Operit2 pairs strictly in call order: result.matchToolName == callNames[resultCount].
            let mut matched: Option<usize> = None;
            if !pending.is_empty() {
                let head = pending[0];
                let call_name = tool_name_of(&nodes[head]);
                let same = match (&call_name, &result_name) {
                    (Some(a), Some(b)) => a == b,
                    (None, None) => true,
                    _ => false,
                };
                if same {
                    matched = Some(head);
                }
            }
            match matched {
                Some(call_index) => {
                    pending.remove(0);
                    result_of[call_index] = i as i32;
                    call_of[i] = call_index as i32;
                    owner_of[i] = call_index as i32;
                    last_owner = call_index as i32;
                }
                None => {
                    owner_of[i] = last_owner;
                }
            }
        } else if is_tag(xml, "tool") {
            pending.push(i);
            owner_of[i] = i as i32;
            last_owner = i as i32;
        } else {
            owner_of[i] = last_owner;
        }
    }

    MergePlan { ok: true, error: String::new(), owner_of, result_of, call_of }
}

#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_ui_common_markdown_NativeXmlGroup_planJson(
    mut env: JNIEnv,
    _class: JClass,
    nodes: JObjectArray,
) -> jstring {
    let parsed: Result<Vec<String>, String> = (|| {
        if nodes.is_null() {
            return Ok(Vec::new());
        }
        let len = env
            .get_array_length(&nodes)
            .map_err(|e| format!("get_array_length failed: {e}"))?;
        let mut out = Vec::with_capacity(len as usize);
        for i in 0..len {
            let obj = env
                .get_object_array_element(&nodes, i)
                .map_err(|e| format!("element {i} failed: {e}"))?;
            let s: JString = obj.into();
            let rust: String = env
                .get_string(&s)
                .map_err(|e| format!("get_string {i} failed: {e}"))?
                .into();
            out.push(rust);
        }
        Ok(out)
    })();

    let response = match parsed {
        Ok(list) => plan(&list),
        Err(error) => fail(error, 0),
    };

    let json = serde_json::to_string(&response)
        .unwrap_or_else(|e| format!(r#"{{"ok":false,"error":"encode failed: {e}","ownerOf":[],"resultOf":[],"callOf":[]}}"#));
    env.new_string(json)
        .map(|v| v.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct IconResponse {
    ok: bool,
    icon: String,
}

/// Operit2 `toolDisplayIcon` (`ToolDisplayComponents.dart:292-314`): maps the
/// declared semantic icon field, not the tool name.
fn icon_for(semantic: &str) -> &'static str {
    match semantic {
        "file" => "description_outlined",
        "folder" => "folder_outlined",
        "edit" => "edit_outlined",
        "delete" => "delete_outline",
        "search" => "search",
        "terminal" => "terminal",
        "web" => "language",
        "download" => "download_outlined",
        "package" => "extension_outlined",
        "time" => "schedule",
        "notification" => "notifications_outlined",
        "clipboard" => "content_paste",
        "audio" => "volume_up_outlined",
        "info" => "info_outline",
        "screen" => "screenshot_outlined",
        "tap" => "touch_app_outlined",
        "double_tap" => "ads_click",
        "long_press" => "pan_tool_outlined",
        "swipe" => "swipe_outlined",
        "keyboard" => "keyboard_outlined",
        _ => "build_outlined",
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_ui_common_markdown_NativeXmlGroup_iconForJson(
    mut env: JNIEnv,
    _class: JClass,
    semantic: JString,
) -> jstring {
    let value: String = env
        .get_string(&semantic)
        .map(|s| s.into())
        .unwrap_or_default();
    let response = IconResponse { ok: true, icon: icon_for(value.trim()).to_string() };
    let json = serde_json::to_string(&response).unwrap_or_else(|_| {
        r#"{"ok":false,"icon":"build_outlined"}"#.to_string()
    });
    env.new_string(json)
        .map(|v| v.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// 从 XML 片段里取首标签名，与 Kotlin `ChatMarkupRegex.extractOpeningTagName` 同语义。
///
/// 流式渲染时每个节点、每一帧都会调用它（ThinkToolsXmlNodeGrouper 的
/// slice.count / when(extractXmlTagName(...))）。Kotlin 侧走 java.util.regex，
/// 每次都要分配 Matcher；这里只用一次线性扫描，不产生中间对象。
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_ui_common_markdown_NativeXmlGroup_tagNameJni(
    mut env: JNIEnv,
    _class: JClass,
    xml: JString,
) -> jstring {
    let text: String = match env.get_string(&xml) {
        Ok(v) => v.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    match opening_tag_name(&text) {
        Some(name) => match env.new_string(name) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

/// 纯函数版本，不依赖 JNI，方便单元测试。
///
/// 与 Kotlin 正则 `<([A-Za-z][A-Za-z0-9_]*)` 逐条对齐：
///   - 跳过第一个 '<' 之前的所有字符
///   - '<' 之后必须是 ASCII 字母
///   - 随后接受字母、数字、下划线
///   - 不匹配返回 None
pub fn opening_tag_name(xml: &str) -> Option<&str> {
    let bytes = xml.as_bytes();
    let mut i = 0usize;
    while i < bytes.len() && bytes[i] != b'<' {
        i += 1;
    }
    i += 1;
    if i >= bytes.len() {
        return None;
    }
    let start = i;
    if !bytes[i].is_ascii_alphabetic() {
        return None;
    }
    i += 1;
    while i < bytes.len() && (bytes[i].is_ascii_alphanumeric() || bytes[i] == b'_') {
        i += 1;
    }
    xml.get(start..i)
}

#[cfg(test)]
mod tag_name_tests {
    use super::opening_tag_name;

    #[test]
    fn simple_tool_tag() {
        assert_eq!(opening_tag_name("<tool name=\"a\">"), Some("tool"));
    }

    #[test]
    fn tool_result_tag() {
        assert_eq!(opening_tag_name("<tool_result>"), Some("tool_result"));
    }

    #[test]
    fn leading_whitespace() {
        assert_eq!(opening_tag_name("  \n  <think>"), Some("think"));
    }

    #[test]
    fn closing_only_is_none() {
        assert_eq!(opening_tag_name("</tool>"), None);
    }

    #[test]
    fn plain_text_is_none() {
        assert_eq!(opening_tag_name("hello"), None);
    }

    #[test]
    fn unterminated_streaming() {
        assert_eq!(opening_tag_name("<tool nam"), Some("tool"));
    }
}

/// 判断一段 XML 片段是否已经完整闭合。
///
/// 与 Kotlin 侧 isXmlFullyClosed 同语义：自闭合、或存在对应 </tag>。
/// 流式渲染每帧都会对每个节点调用，Rust 侧一次扫描替代两次 String 分配。
pub fn is_fully_closed(xml: &str) -> bool {
    let trimmed = xml.trim();
    if trimmed.ends_with("/>") {
        return true;
    }
    let tag = match opening_tag_name(xml) {
        Some(v) => v,
        None => return false,
    };
    let closing = format!("</{}>", tag);
    trimmed.contains(&closing)
}

/// is_fully_closed 的 JNI 入口。true 返回 1，false 返回 0。
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_ui_common_markdown_NativeXmlGroup_isFullyClosedJni(
    mut env: JNIEnv,
    _class: JClass,
    xml: JString,
) -> jboolean {
    let text: String = match env.get_string(&xml) {
        Ok(v) => v.into(),
        Err(_) => return 0,
    };
    if is_fully_closed(&text) { 1 } else { 0 }
}

#[cfg(test)]
mod closed_tests {
    use super::is_fully_closed;

    #[test]
    fn self_closing() {
        assert!(is_fully_closed("<tool name=\"a\"/>"));
    }

    #[test]
    fn closed_pair() {
        assert!(is_fully_closed("<think>x</think>"));
    }

    #[test]
    fn streaming_incomplete() {
        assert!(!is_fully_closed("<tool name=\"a\">"));
    }

    #[test]
    fn not_xml() {
        assert!(!is_fully_closed("plain text"));
    }
}
