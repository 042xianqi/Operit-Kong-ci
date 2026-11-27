//! JNI exports for rootfs installation. Android-only.
//!
//! Kotlin class: `com.ai.assistance.operit.terminal.runtime.NativeTerminal`, methods
//! declared `external` with `@JvmStatic` (static native ⇒ `JClass` here).
//!
//! JSON in / JSON out, except `rootfsInstalled`, which is a plain boolean predicate.
//! Every export is wrapped in `catch_unwind`: a panic must never unwind into the JVM.

use crate::install::{self, InstallSpec};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};

/// `installRootfs` request, as sent by `TerminalRootfsInstaller` (camelCase).
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstallSpecJson {
    archive_path: String,
    rootfs: String,
    instance_id: String,
    display_name: String,
    #[serde(default)]
    expected_sha256: Option<String>,
    #[serde(default)]
    alpine: bool,
}

impl InstallSpecJson {
    fn into_spec(self) -> InstallSpec {
        InstallSpec {
            archive_path: self.archive_path,
            rootfs: self.rootfs,
            instance_id: self.instance_id,
            display_name: self.display_name,
            expected_sha256: self.expected_sha256,
            alpine: self.alpine,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct InstallJson {
    installed: bool,
    sha256: String,
    actions: Vec<String>,
}

#[derive(Serialize)]
struct ErrorJson<'a> {
    error: &'a str,
}

fn error_json(message: &str) -> String {
    serde_json::to_string(&ErrorJson { error: message })
        .unwrap_or_else(|_| "{\"error\":\"unserializable\"}".to_string())
}

fn install_response(env: &mut JNIEnv, spec_json: &JString) -> String {
    let raw: String = match env.get_string(spec_json) {
        Ok(value) => value.into(),
        Err(error) => return error_json(&format!("cannot read spec: {error}")),
    };
    let spec: InstallSpecJson = match serde_json::from_str(&raw) {
        Ok(spec) => spec,
        Err(error) => return error_json(&format!("invalid spec: {error}")),
    };
    match install::install(&spec.into_spec()) {
        Ok(outcome) => {
            let json = InstallJson {
                installed: outcome.installed,
                sha256: outcome.sha256,
                actions: outcome.actions,
            };
            serde_json::to_string(&json)
                .unwrap_or_else(|error| error_json(&format!("cannot serialize result: {error}")))
        }
        Err(error) => error_json(&error),
    }
}

/// `NativeTerminal.installRootfs(specJson) -> String`
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_terminal_runtime_NativeTerminal_installRootfs<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    spec_json: JString<'local>,
) -> jstring {
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let json = install_response(&mut env, &spec_json);
        match env.new_string(json) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));
    match outcome {
        Ok(handle) => handle,
        // Null becomes an exception on the Kotlin side, visible in logcat.
        Err(_) => std::ptr::null_mut(),
    }
}

/// `NativeTerminal.rootfsInstalled(rootfsPath) -> Boolean`
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_terminal_runtime_NativeTerminal_rootfsInstalled<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rootfs: JString<'local>,
) -> jboolean {
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let path: String = match env.get_string(&rootfs) {
            Ok(value) => value.into(),
            Err(_) => return JNI_FALSE,
        };
        if install::is_installed(&path) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }));
    match outcome {
        Ok(flag) => flag,
        Err(_) => JNI_FALSE,
    }
}
