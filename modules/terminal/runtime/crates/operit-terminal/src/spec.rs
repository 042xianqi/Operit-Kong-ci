//! Launch inputs handed over from Kotlin.
//!
//! Everything here is a *path or a value*, never a proot argument string: argv and envp
//! are produced only by [`crate::proot::build`]. Kotlin cannot pre-assemble `-b` entries,
//! because `!` suffixes and deduplication live on this side.

use serde::Deserialize;
use std::collections::BTreeMap;

/// One extra bind mount. `target` empty means "same path as source".
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BindSpec {
    pub source: String,
    #[serde(default)]
    pub target: String,
}

impl BindSpec {
    /// Host path that must exist for proot to bind it.
    pub fn source(&self) -> &str {
        &self.source
    }

    /// Guest path; defaults to the source path.
    pub fn target(&self) -> &str {
        if self.target.is_empty() {
            &self.source
        } else {
            &self.target
        }
    }
}

/// Complete input for one proot launch.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProotSpec {
    /// Host proot executable (`$nativeLibDir/liboperit_proot.so`).
    pub proot: String,
    /// Host loader passed through `PROOT_LOADER`.
    pub loader: String,
    /// `PROOT_TMP_DIR`; must be writable by the app.
    pub tmp_dir: String,
    /// Guest root.
    pub rootfs: String,
    /// Host working directory of the proot process itself.
    pub process_working_directory: String,
    /// App-specific bind mounts, in order.
    #[serde(default)]
    pub binds: Vec<BindSpec>,
    /// Command executed inside the rootfs; `[0]` is the guest shell path.
    pub guest_command: Vec<String>,
    /// Guest environment overrides on top of the base environment.
    #[serde(default)]
    pub guest_environment: BTreeMap<String, String>,
    /// Guest start directory (`-w`). Defaults to `/root`, matching Operit2.
    #[serde(default = "default_guest_working_dir")]
    pub guest_working_directory: String,
}

fn default_guest_working_dir() -> String {
    crate::proot::DEFAULT_GUEST_WORKING_DIR.to_string()
}

/// Reject anything that cannot be a host path on Android. Deliberately *not*
/// `Path::is_absolute()`: that is host-platform dependent and these values are always
/// Android paths, even when the same code runs in a Windows test.
pub(crate) fn require_absolute(field: &str, value: &str) -> Result<(), String> {
    if value.is_empty() {
        return Err(format!("{field} is empty"));
    }
    if value.contains('\0') {
        return Err(format!("{field} contains a NUL byte"));
    }
    if !value.starts_with('/') {
        return Err(format!("{field} must be an absolute path, got {value:?}"));
    }
    Ok(())
}

impl ProotSpec {
    /// Structural validation, run before any argument is generated.
    pub fn validate(&self) -> Result<(), String> {
        require_absolute("proot", &self.proot)?;
        require_absolute("loader", &self.loader)?;
        require_absolute("tmpDir", &self.tmp_dir)?;
        require_absolute("rootfs", &self.rootfs)?;
        require_absolute("processWorkingDirectory", &self.process_working_directory)?;
        require_absolute("guestWorkingDirectory", &self.guest_working_directory)?;

        if self.guest_command.is_empty() {
            return Err("guestCommand must contain at least the guest executable".to_string());
        }
        for (index, arg) in self.guest_command.iter().enumerate() {
            if arg.contains('\0') {
                return Err(format!("guestCommand[{index}] contains a NUL byte"));
            }
        }
        if self.guest_command[0].is_empty() {
            return Err("guestCommand[0] is empty".to_string());
        }
        if !self.guest_command[0].starts_with('/') {
            return Err(format!(
                "guestCommand[0] must be an absolute guest path, got {:?}",
                self.guest_command[0]
            ));
        }

        for (index, bind) in self.binds.iter().enumerate() {
            require_absolute(&format!("binds[{index}].source"), &bind.source)?;
            // `:` separates source and target and `!` is our own "skip if missing"
            // marker; both are appended by this crate, never accepted from the caller.
            if bind.source.contains(':') || bind.source.contains('!') {
                return Err(format!(
                    "binds[{index}].source must not contain ':' or '!', got {:?}",
                    bind.source
                ));
            }
            if !bind.target.is_empty() {
                require_absolute(&format!("binds[{index}].target"), &bind.target)?;
                if bind.target.contains(':') || bind.target.contains('!') {
                    return Err(format!(
                        "binds[{index}].target must not contain ':' or '!', got {:?}",
                        bind.target
                    ));
                }
            }
        }

        for key in self.guest_environment.keys() {
            if key.is_empty() || key.contains('=') || key.contains('\0') {
                return Err(format!("invalid guest environment key {key:?}"));
            }
        }
        for (key, value) in &self.guest_environment {
            if value.contains('\0') {
                return Err(format!("guest environment value for {key:?} contains a NUL byte"));
            }
        }

        Ok(())
    }
}
