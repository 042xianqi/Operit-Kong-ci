//! JNI exports for this module. Android-only.
//!
//! Kotlin class: `com.ai.assistance.operit.terminal.runtime.NativeTerminal`,
//! `external fun prepareLocalRootfs(specJson: String): String` declared `@JvmStatic`
//! (static native ⇒ `JClass` here). JSON in / JSON out, camelCase, `{"error": "..."}`
//! on failure; no panic may cross the FFI boundary.

use std::collections::BTreeMap;

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};

use crate::guest::{self, GuestFile, PrepareSpec};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GuestFileJson {
    relative_path: String,
    contents: String,
}

fn default_true() -> bool {
    true
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PrepareRequest {
    rootfs: String,
    #[serde(default)]
    directories: Vec<String>,
    #[serde(default)]
    files: Vec<GuestFileJson>,
    #[serde(default)]
    group_ids: Vec<u32>,
    #[serde(default)]
    distro: String,
    #[serde(default = "default_true")]
    repair_legacy: bool,
}

#[derive(Serialize)]
struct PreparedJson {
    actions: Vec<String>,
    environment: BTreeMap<String, String>,
}

#[derive(Serialize)]
struct ErrorJson<'a> {
    error: &'a str,
}

fn error_json(message: &str) -> String {
    serde_json::to_string(&ErrorJson { error: message })
        .unwrap_or_else(|_| "{\"error\":\"unserializable\"}".to_string())
}

fn prepare_response(env: &mut JNIEnv, spec_json: &JString) -> String {
    let raw: String = match env.get_string(spec_json) {
        Ok(value) => value.into(),
        Err(error) => return error_json(&format!("cannot read spec: {error}")),
    };
    let request: PrepareRequest = match serde_json::from_str(&raw) {
        Ok(request) => request,
        Err(error) => return error_json(&format!("invalid spec: {error}")),
    };
    let spec = PrepareSpec {
        rootfs: request.rootfs,
        directories: request.directories,
        files: request
            .files
            .into_iter()
            .map(|file| GuestFile {
                relative_path: file.relative_path,
                contents: file.contents,
            })
            .collect(),
        group_ids: request.group_ids,
        distro: request.distro,
        repair_legacy: request.repair_legacy,
    };
    match guest::prepare(&spec) {
        Ok(outcome) => {
            let response = PreparedJson {
                actions: outcome.actions,
                environment: outcome.environment.into_iter().collect(),
            };
            serde_json::to_string(&response)
                .unwrap_or_else(|error| error_json(&format!("cannot serialize result: {error}")))
        }
        Err(error) => error_json(&error),
    }
}

/// `NativeTerminal.prepareLocalRootfs(specJson) -> String`
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_terminal_runtime_NativeTerminal_prepareLocalRootfs<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    spec_json: JString<'local>,
) -> jstring {
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let json = prepare_response(&mut env, &spec_json);
        match env.new_string(json) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));
    match outcome {
        Ok(handle) => handle,
        Err(_) => std::ptr::null_mut(),
    }
}
