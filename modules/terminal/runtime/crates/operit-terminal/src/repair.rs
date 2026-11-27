//! Legacy rootfs repair. Earlier Kong builds appended a profile hook to `/etc/profile`
//! that sourced `/etc/operit/terminal.sh`; that script then sourced `/etc/profile`
//! again, so `ash -l` recursed until it died before printing a prompt.
//!
//! Historical marker (verbatim, from the generated script in
//! `TerminalManager.generateStartScript()` / `common.sh`):
//!
//! ```sh
//! printf '\n. /etc/operit/terminal.sh\n' >> "$UBUNTU_PATH/etc/profile"
//! ```
//!
//! so the only line Kong ever added to `/etc/profile` was:
//!
//! ```text
//! . /etc/operit/terminal.sh
//! ```
//!
//! There was never a comment-delimited begin/end block in the shipped sources; the
//! marker *is* that line (plus the `source` spelling as a defensive variant). Older
//! builds also dropped `/etc/profile.orig` when backing the file up.
//!
//! Contract:
//! * No marker → file untouched, no actions.
//! * Marker line → only that line removed, every other byte preserved.
//! * `<rootfs>/etc/profile.orig` present AND the current profile carries the marker →
//!   restored over `/etc/profile`, then the backup removed.
//! * `<rootfs>/etc/terminal.sh` and `<rootfs>/etc/operit/terminal.sh` present → deleted.
//! * Never touch a profile that has no marker. Idempotent.

use std::fs;
use std::path::{Path, PathBuf};

/// The exact line earlier Kong builds appended to `/etc/profile`.
pub const LEGACY_PROFILE_MARKER: &str = ". /etc/operit/terminal.sh";
/// Bash spelling of the same hook (kept for rootfs images that used it).
pub const LEGACY_PROFILE_MARKER_SOURCE: &str = "source /etc/operit/terminal.sh";

/// Is this profile line the Kong-generated hook?
fn is_marker_line(line: &str) -> bool {
    let trimmed = line.trim_end_matches(['\n', '\r']).trim();
    trimmed == LEGACY_PROFILE_MARKER || trimmed == LEGACY_PROFILE_MARKER_SOURCE
}

/// Does this profile carry Kong's hook at all?
pub fn contains_legacy_marker(profile: &str) -> bool {
    profile.lines().any(is_marker_line)
}

/// Absolute path for reporting; falls back to the input when the cwd is unavailable.
fn absolute(path: &Path) -> PathBuf {
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()
            .map(|cwd| cwd.join(path))
            .unwrap_or_else(|_| path.to_path_buf())
    }
}

/// Strip every Kong-generated profile line, preserving all other bytes verbatim.
/// Returns `None` when nothing was removed, so callers can skip the write.
fn strip_marker_lines(profile: &str) -> Option<String> {
    let mut removed = false;
    let mut kept = String::with_capacity(profile.len());
    for line in profile.split_inclusive('\n') {
        if is_marker_line(line) {
            removed = true;
        } else {
            kept.push_str(line);
        }
    }
    // A file without a trailing newline still yields one final chunk above.
    if removed {
        Some(kept)
    } else {
        None
    }
}

/// Repair one rootfs. Idempotent: a clean rootfs yields no actions.
pub fn repair(rootfs: &str) -> Result<Vec<String>, String> {
    let root = Path::new(rootfs.trim());
    if !root.is_dir() {
        return Err(format!("rootfs is missing: {}", absolute(root).display()));
    }

    let mut actions = Vec::new();
    let profile_path = root.join("etc/profile");
    let backup_path = root.join("etc/profile.orig");

    let profile = match fs::read(&profile_path) {
        Ok(bytes) => Some(String::from_utf8(bytes).map_err(|_| {
            format!(
                "/etc/profile is not valid UTF-8: {}",
                absolute(&profile_path).display()
            )
        })?),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => None,
        Err(error) => {
            return Err(format!(
                "cannot read {}: {error}",
                absolute(&profile_path).display()
            ))
        }
    };

    let marked = profile
        .as_deref()
        .map(contains_legacy_marker)
        .unwrap_or(false);

    if marked {
        if backup_path.is_file() {
            // The backup is the pre-Kong profile, so it supersedes a line-level patch.
            let original = fs::read(&backup_path).map_err(|error| {
                format!(
                    "cannot read {}: {error}",
                    absolute(&backup_path).display()
                )
            })?;
            fs::write(&profile_path, &original).map_err(|error| {
                format!(
                    "cannot write {}: {error}",
                    absolute(&profile_path).display()
                )
            })?;
            fs::remove_file(&backup_path).map_err(|error| {
                format!(
                    "cannot remove {}: {error}",
                    absolute(&backup_path).display()
                )
            })?;
            actions.push(format!(
                "restore {} from etc/profile.orig",
                absolute(&profile_path).display()
            ));
        } else if let Some(stripped) = profile.as_deref().and_then(strip_marker_lines) {
            fs::write(&profile_path, stripped).map_err(|error| {
                format!(
                    "cannot write {}: {error}",
                    absolute(&profile_path).display()
                )
            })?;
            actions.push(format!(
                "remove legacy profile hook from {}",
                absolute(&profile_path).display()
            ));
        }
    }

    for relative in ["etc/terminal.sh", "etc/operit/terminal.sh"] {
        let bridge = root.join(relative);
        match fs::remove_file(&bridge) {
            Ok(()) => actions.push(format!("delete {}", absolute(&bridge).display())),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => {
                return Err(format!(
                    "cannot remove {}: {error}",
                    absolute(&bridge).display()
                ))
            }
        }
    }

    Ok(actions)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU32, Ordering};

    static COUNTER: AtomicU32 = AtomicU32::new(0);

    fn temp_rootfs(tag: &str) -> PathBuf {
        let unique = COUNTER.fetch_add(1, Ordering::SeqCst);
        let path = std::env::temp_dir().join(format!(
            "operit-repair-{tag}-{}-{unique}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&path);
        fs::create_dir_all(path.join("etc")).unwrap();
        path
    }

    fn read(path: &Path) -> String {
        fs::read_to_string(path).unwrap()
    }

    #[test]
    fn unmarked_profile_is_untouched() {
        let root = temp_rootfs("unmarked");
        let profile = "export PATH=/usr/bin\n# user line\n";
        fs::write(root.join("etc/profile"), profile).unwrap();

        let actions = repair(root.to_str().unwrap()).unwrap();

        assert!(actions.is_empty(), "{actions:?}");
        assert_eq!(profile, read(&root.join("etc/profile")));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn marker_line_is_stripped_and_surrounding_lines_preserved() {
        let root = temp_rootfs("marked");
        let profile = "before\n. /etc/operit/terminal.sh\nafter\n";
        fs::write(root.join("etc/profile"), profile).unwrap();

        let actions = repair(root.to_str().unwrap()).unwrap();

        assert_eq!(1, actions.len(), "{actions:?}");
        assert_eq!("before\nafter\n", read(&root.join("etc/profile")));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn source_spelling_is_stripped_too() {
        let root = temp_rootfs("source");
        fs::write(
            root.join("etc/profile"),
            "before\nsource /etc/operit/terminal.sh\nafter\n",
        )
        .unwrap();

        assert_eq!(1, repair(root.to_str().unwrap()).unwrap().len());
        assert_eq!("before\nafter\n", read(&root.join("etc/profile")));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn terminal_bridge_files_are_deleted() {
        let root = temp_rootfs("bridge");
        fs::write(root.join("etc/profile"), "before\n").unwrap();
        fs::create_dir_all(root.join("etc/operit")).unwrap();
        fs::write(root.join("etc/terminal.sh"), "#!/bin/sh\n").unwrap();
        fs::write(root.join("etc/operit/terminal.sh"), "#!/bin/sh\n").unwrap();

        let actions = repair(root.to_str().unwrap()).unwrap();

        assert_eq!(2, actions.len(), "{actions:?}");
        assert!(!root.join("etc/terminal.sh").exists());
        assert!(!root.join("etc/operit/terminal.sh").exists());
        assert_eq!("before\n", read(&root.join("etc/profile")));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn backup_is_restored_only_for_a_marked_profile() {
        let root = temp_rootfs("backup");
        let original = "original profile\n";
        fs::write(root.join("etc/profile.orig"), original).unwrap();

        // No marker: the backup must stay untouched and the profile must not change.
        fs::write(root.join("etc/profile"), "user profile\n").unwrap();
        assert!(repair(root.to_str().unwrap()).unwrap().is_empty());
        assert_eq!("user profile\n", read(&root.join("etc/profile")));
        assert!(root.join("etc/profile.orig").exists());

        // With the marker: restore the backup, then drop it.
        fs::write(
            root.join("etc/profile"),
            "user profile\n. /etc/operit/terminal.sh\n",
        )
        .unwrap();
        let actions = repair(root.to_str().unwrap()).unwrap();
        assert_eq!(1, actions.len(), "{actions:?}");
        assert_eq!(original, read(&root.join("etc/profile")));
        assert!(!root.join("etc/profile.orig").exists());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn second_run_reports_no_actions() {
        let root = temp_rootfs("idempotent");
        fs::write(
            root.join("etc/profile"),
            "before\n. /etc/operit/terminal.sh\nafter\n",
        )
        .unwrap();
        fs::write(root.join("etc/terminal.sh"), "").unwrap();

        assert!(!repair(root.to_str().unwrap()).unwrap().is_empty());
        assert!(repair(root.to_str().unwrap()).unwrap().is_empty());
        assert_eq!("before\nafter\n", read(&root.join("etc/profile")));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn missing_rootfs_is_an_error() {
        let error = repair("definitely/not/a/real/rootfs").unwrap_err();
        assert!(error.contains("rootfs is missing"), "{error}");
    }
}
