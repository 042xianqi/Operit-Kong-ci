//! Structured diagnostics shared by installation (`install.rs`) and guest preparation
//! (`guest.rs`).
//!
//! The device only sees a single Rust error string, so every interesting fact has to
//! be part of that string in `key=value` form: the target path, its mode and its
//! parent's mode, `umask`, `uid` and the SELinux context. A plain "Permission denied"
//! cannot distinguish mode bits from umask or policy.
//!
//! All helpers are read-only except [`set_mode`]; none of them fail the caller. Mode
//! queries return `None` on non-Unix hosts (the unit-test host is Windows), which is
//! also why callers must not treat `None` as "missing".

use std::fs;
use std::io;
use std::path::Path;

/// Permission bits (no file-type bits) or `None` when the path is missing / not Unix.
pub(crate) fn mode_of(path: &Path) -> Option<u32> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::symlink_metadata(path)
            .ok()
            .map(|metadata| metadata.permissions().mode() & 0o777)
    }
    #[cfg(not(unix))]
    {
        let _ = path;
        None
    }
}

/// Explicit chmod. `mkdir` honours the process umask, so callers that need an exact
/// mode (0755 for `/dev`, for example) must call this afterwards.
pub(crate) fn set_mode(path: &Path, mode: u32) -> io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(mode))
    }
    #[cfg(not(unix))]
    {
        let _ = (path, mode);
        Ok(())
    }
}

/// `0755` / `missing` / `unreadable`, for log lines.
pub(crate) fn format_mode(path: &Path) -> String {
    match mode_of(path) {
        Some(mode) => format!("{mode:04o}"),
        None => {
            if fs::symlink_metadata(path).is_err() {
                "missing".to_string()
            } else {
                "unreadable".to_string()
            }
        }
    }
}

/// A `/proc/self/status` field (first value only), e.g. `Uid:` or `Umask:`.
pub(crate) fn proc_status_field(name: &str) -> Option<String> {
    fs::read_to_string("/proc/self/status")
        .ok()?
        .lines()
        .find_map(|line| {
            let (key, value) = line.split_once(':')?;
            if key != name {
                return None;
            }
            value.split_whitespace().next().map(str::to_string)
        })
}

/// SELinux context of this process, or a marker when `/proc` is unavailable.
pub(crate) fn selinux_context() -> String {
    fs::read_to_string("/proc/self/attr/current")
        .map(|text| text.trim().to_string())
        .unwrap_or_else(|_| "<unreadable>".to_string())
}

/// One-line snapshot of the runtime directories that proot and the guest need.
///
/// Emitted after every install (`install.rs`) and on every prepare failure
/// (`guest.rs`), so two consecutive boots can be compared byte for byte.
pub(crate) fn snapshot(event: &str, root: &Path) -> String {
    let mut line = format!(
        "event={event} rootfs={} rootfsMode={}",
        root.display(),
        format_mode(root)
    );
    for name in ["dev", "dev/pts", "dev/shm", "proc", "sys", "tmp", "root"] {
        line.push(' ');
        line.push_str(&name.replace('/', "_"));
        line.push('=');
        line.push_str(&format_mode(&root.join(name)));
    }
    line.push_str(&format!(
        " umask={} uid={} selinux={}",
        proc_status_field("Umask").unwrap_or_else(|| "<unknown>".to_string()),
        proc_status_field("Uid").unwrap_or_else(|| "<unknown>".to_string()),
        selinux_context(),
    ));
    line
}

/// Human-readable cause of a failed directory operation, with every relevant datum.
pub(crate) fn failure_detail(root: &Path, target: &Path, action: &str, error: &io::Error) -> String {
    let parent = target.parent().unwrap_or(root);
    format!(
        "{action} {} failed: {error}; event=prepare_failed rootfs={} rootfsMode={} \
         target={} targetExists={} targetMode={} parent={} parentMode={} umask={} uid={} selinux={}",
        target.display(),
        root.display(),
        format_mode(root),
        target.display(),
        target.is_dir(),
        format_mode(target),
        parent.display(),
        format_mode(parent),
        proc_status_field("Umask").unwrap_or_else(|| "<unknown>".to_string()),
        proc_status_field("Uid").unwrap_or_else(|| "<unknown>".to_string()),
        selinux_context(),
    )
}
