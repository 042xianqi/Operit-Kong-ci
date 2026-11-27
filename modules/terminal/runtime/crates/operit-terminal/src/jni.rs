//! JNI surface: JSON in, JSON out. Only Android compiles this module.
//!
//! Kotlin class: `com.ai.assistance.operit.terminal.runtime.NativeTerminal`,
//! methods declared `external` with `@JvmStatic` (static native ⇒ `JClass` here).
//!
//! Wire format keeps the boundary dumb: Kotlin sends paths and values (`ProotSpec`),
//! Rust sends the finished `argv`/`env`/cwd or `{"error": "..."}`.

use crate::proot::{build, ProotCommand};
use crate::spec::ProotSpec;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::Serialize;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CommandJson<'a> {
    argv: &'a [String],
    /// Array of `[key, value]` pairs: a JSON object would lose order.
    environment: &'a [(String, String)],
    working_directory: &'a str,
    removed_environment_keys: &'a [String],
    version: &'static str,
}

impl<'a> CommandJson<'a> {
    fn new(command: &'a ProotCommand) -> Self {
        Self {
            argv: &command.argv,
            environment: &command.environment,
            working_directory: &command.working_directory,
            removed_environment_keys: &command.removed_environment_keys,
            version: crate::VERSION,
        }
    }
}

#[derive(Serialize)]
struct ErrorJson<'a> {
    error: &'a str,
}

fn error_json(message: &str) -> String {
    serde_json::to_string(&ErrorJson { error: message })
        .unwrap_or_else(|_| "{\"error\":\"unserializable\"}".to_string())
}

fn build_response(env: &mut JNIEnv, spec_json: &JString) -> String {
    let raw: String = match env.get_string(spec_json) {
        Ok(value) => value.into(),
        Err(error) => return error_json(&format!("cannot read spec: {error}")),
    };
    let spec: ProotSpec = match serde_json::from_str(&raw) {
        Ok(spec) => spec,
        Err(error) => return error_json(&format!("invalid spec: {error}")),
    };
    match build(&spec) {
        Ok(command) => serde_json::to_string(&CommandJson::new(&command))
            .unwrap_or_else(|error| error_json(&format!("cannot serialize command: {error}"))),
        Err(error) => error_json(&error),
    }
}

/// `NativeTerminal.buildProotCommand(specJson) -> String`
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_terminal_runtime_NativeTerminal_buildProotCommand<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    spec_json: JString<'local>,
) -> jstring {
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let json = build_response(&mut env, &spec_json);
        match env.new_string(json) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));
    match outcome {
        Ok(handle) => handle,
        // A panic must never unwind across the FFI boundary. Null makes the Kotlin
        // caller throw a NullPointerException, which is visible in logcat.
        Err(_) => std::ptr::null_mut(),
    }
}

/// `NativeTerminal.nativeVersion() -> String`
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_terminal_runtime_NativeTerminal_nativeVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    match env.new_string(crate::VERSION) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
