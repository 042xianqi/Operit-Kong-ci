//! Guest-side preparation: directories, package-source files, prompt file, Android gids,
//! and legacy profile repair. Replaces `GuestRootfsPrep.kt` behaviour.
//!
//! Pure `std::fs`; no Android types. Idempotent: unchanged files produce no actions and
//! no mtime churn.

use std::fs;
use std::path::{Path, PathBuf};

use crate::diagnostics;
use crate::prompt;
use crate::repair;

/// One file to write inside the rootfs, path relative to it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GuestFile {
    pub relative_path: String,
    pub contents: String,
}

/// Everything the host knows about the rootfs it is about to enter.
#[derive(Debug, Clone)]
pub struct PrepareSpec {
    pub rootfs: String,
    /// Guest-absolute directories (`/dev/pts`) that must exist.
    pub directories: Vec<String>,
    pub files: Vec<GuestFile>,
    pub group_ids: Vec<u32>,
    /// `"alpine"`, `"debian"`, ... — decides which prompt mechanism is used.
    pub distro: String,
    /// Strip the legacy `/etc/profile` hook and `terminal.sh` bridge before starting.
    pub repair_legacy: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PrepareOutcome {
    pub actions: Vec<String>,
    /// Prompt environment to merge into the guest env (`ENV` for ash,
    /// `PROMPT_COMMAND`/`PS1` for bash).
    pub environment: Vec<(String, String)>,
}

/// Absolute path for action strings; falls back to the input if the cwd is unavailable.
fn absolute(path: &Path) -> PathBuf {
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()
            .map(|cwd| cwd.join(path))
            .unwrap_or_else(|_| path.to_path_buf())
    }
}

/// Write `contents` when the on-disk bytes differ. Returns `true` when written.
fn write_if_different(target: &Path, contents: &str) -> Result<bool, String> {
    if let Ok(existing) = fs::read(target) {
        if existing == contents.as_bytes() {
            return Ok(false);
        }
    }
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            format!("cannot create {}: {error}", absolute(parent).display())
        })?;
    }
    fs::write(target, contents).map_err(|error| {
        format!("cannot write {}: {error}", absolute(target).display())
    })?;
    Ok(true)
}

/// Mode a prepared directory must end up with.
///
/// The `/dev` subtree is bind-mounted/shared with proot and must stay traversable
/// for every uid the guest uses, so it is always 0755. Everything else only needs
/// its owner: Android app-private dirs are 0700 and that is enough for the guest.
fn directory_mode(relative: &str) -> u32 {
    if relative == "dev" || relative.starts_with("dev/") {
        0o755
    } else {
        0o700
    }
}

/// Create `relative` under `root`, repairing lost mode bits on the way.
///
/// Android's umask is 077, so `create_dir_all` alone yields 0700; a directory that
/// later lost its bits (observed: `dev` = 000) makes even the owner unable to create
/// `dev/pts`. Every existing ancestor therefore gets its owner bits back before the
/// next level is created, and the final directory is chmod'ed explicitly instead of
/// relying on the process umask.
fn ensure_directory(root: &Path, relative: &str) -> Result<Vec<String>, String> {
    let mut actions = Vec::new();
    let desired = directory_mode(relative);

    let mut current = root.to_path_buf();
    if let Some(mode) = diagnostics::mode_of(&current) {
        if mode & 0o700 != 0o700 {
            diagnostics::set_mode(&current, 0o700).map_err(|error| {
                diagnostics::failure_detail(root, &current, "cannot repair rootfs directory", &error)
            })?;
            actions.push(format!(
                "chmod {} 0700 (was {mode:04o})",
                absolute(&current).display()
            ));
        }
    }

    for part in relative.split('/') {
        if part.is_empty() {
            continue;
        }
        current.push(part);
        let exists = fs::symlink_metadata(&current)
            .map(|metadata| metadata.is_dir())
            .unwrap_or(false);
        if exists {
            if let Some(mode) = diagnostics::mode_of(&current) {
                if mode & 0o700 != 0o700 {
                    diagnostics::set_mode(&current, desired).map_err(|error| {
                        diagnostics::failure_detail(root, &current, "cannot repair directory", &error)
                    })?;
                    actions.push(format!(
                        "chmod {} {desired:04o} (was {mode:04o})",
                        absolute(&current).display()
                    ));
                }
            }
            continue;
        }
        fs::create_dir(&current).map_err(|error| {
            diagnostics::failure_detail(root, &current, "cannot create directory", &error)
        })?;
        diagnostics::set_mode(&current, desired).map_err(|error| {
            diagnostics::failure_detail(root, &current, "cannot chmod new directory", &error)
        })?;
        actions.push(format!(
            "mkdir {} mode={desired:04o}",
            absolute(&current).display()
        ));
    }
    Ok(actions)
}

/// Prepare the host-side guest directories/files/gids and the prompt policy.
pub fn prepare(spec: &PrepareSpec) -> Result<PrepareOutcome, String> {
    let root = Path::new(spec.rootfs.trim());
    if !root.is_dir() {
        return Err(format!("rootfs is missing: {}", absolute(root).display()));
    }

    let mut actions = Vec::new();

    let mut directories: Vec<&str> = Vec::new();
    for entry in &spec.directories {
        let trimmed = entry.trim_matches('/');
        if !trimmed.is_empty() && !directories.contains(&trimmed) {
            directories.push(trimmed);
        }
    }
    for relative in directories {
        actions.extend(ensure_directory(root, relative)?);
    }

    for guest_file in &spec.files {
        let relative = guest_file.relative_path.trim_matches('/');
        if relative.is_empty() {
            continue;
        }
        let target = root.join(relative);
        if write_if_different(&target, &guest_file.contents)? {
            actions.push(format!(
                "write {} ({} bytes)",
                absolute(&target).display(),
                guest_file.contents.len()
            ));
        }
    }

    if !spec.group_ids.is_empty() {
        let group_file = root.join("etc/group");
        if let Ok(existing) = fs::read_to_string(&group_file) {
            let mut seen = Vec::new();
            let mut missing = Vec::new();
            for gid in &spec.group_ids {
                if seen.contains(gid) {
                    continue;
                }
                seen.push(*gid);
                if !existing.contains(&format!(":{gid}:")) {
                    missing.push(*gid);
                }
            }
            if !missing.is_empty() {
                let mut appended = String::new();
                for gid in &missing {
                    appended.push_str(&format!("android_group_{gid}:x:{gid}:\n"));
                }
                use std::io::Write;
                let mut handle = fs::OpenOptions::new()
                    .append(true)
                    .open(&group_file)
                    .map_err(|error| {
                        format!(
                            "cannot open {}: {error}",
                            absolute(&group_file).display()
                        )
                    })?;
                handle.write_all(appended.as_bytes()).map_err(|error| {
                    format!("cannot write {}: {error}", absolute(&group_file).display())
                })?;
                let list = missing
                    .iter()
                    .map(|gid| gid.to_string())
                    .collect::<Vec<_>>()
                    .join(",");
                actions.push(format!(
                    "append gids to {}: {list}",
                    absolute(&group_file).display()
                ));
            }
        }
    }

    if spec.distro.trim().to_ascii_lowercase().starts_with("alpine") {
        let target = root.join(prompt::ASH_PROMPT_RELATIVE);
        let script = prompt::ash_prompt_script();
        if write_if_different(&target, &script)? {
            actions.push(format!(
                "write {} ({} bytes)",
                absolute(&target).display(),
                script.len()
            ));
        }
    }

    if spec.repair_legacy {
        actions.extend(repair::repair(spec.rootfs.trim())?);
    }

    Ok(PrepareOutcome {
        actions,
        environment: prompt::environment_for(&spec.distro),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    static COUNTER: AtomicU32 = AtomicU32::new(0);

    fn temp_rootfs(tag: &str) -> PathBuf {
        let unique = COUNTER.fetch_add(1, Ordering::SeqCst);
        let path = std::env::temp_dir().join(format!(
            "operit-guest-{tag}-{}-{unique}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&path);
        fs::create_dir_all(path.join("etc")).unwrap();
        path
    }

    fn spec(rootfs: &Path, distro: &str) -> PrepareSpec {
        PrepareSpec {
            rootfs: rootfs.to_str().unwrap().to_string(),
            directories: vec!["/dev/pts".to_string(), "root".to_string(), "/dev/pts".to_string()],
            files: vec![GuestFile {
                relative_path: "etc/apk/repositories".to_string(),
                contents: "https://dl-cdn.alpinelinux.org/alpine/latest-stable/main\n".to_string(),
            }],
            group_ids: vec![10_123, 30_009],
            distro: distro.to_string(),
            repair_legacy: false,
        }
    }

    #[test]
    fn creates_directories_writes_files_and_appends_gids_once() {
        let root = temp_rootfs("full");
        fs::write(root.join("etc/group"), "root:x:0:\n").unwrap();

        let first = prepare(&spec(&root, "alpine")).unwrap();
        assert!(root.join("dev/pts").is_dir());
        assert!(root.join("root").is_dir());
        assert_eq!(
            "https://dl-cdn.alpinelinux.org/alpine/latest-stable/main\n",
            fs::read_to_string(root.join("etc/apk/repositories")).unwrap()
        );
        assert_eq!(
            "root:x:0:\nandroid_group_10123:x:10123:\nandroid_group_30009:x:30009:\n",
            fs::read_to_string(root.join("etc/group")).unwrap()
        );
        assert_eq!(
            prompt::ash_prompt_script(),
            fs::read_to_string(root.join("etc/operit/prompt.sh")).unwrap()
        );
        assert!(first.actions.iter().any(|action| action.starts_with("mkdir ")));
        assert!(first.actions.iter().any(|action| action.starts_with("write ")));
        assert_eq!(
            2,
            first
                .actions
                .iter()
                .filter(|action| action.starts_with("write "))
                .count()
        );
        assert_eq!(
            1,
            first
                .actions
                .iter()
                .filter(|action| action.starts_with("append gids"))
                .count()
        );

        let second = prepare(&spec(&root, "alpine")).unwrap();
        assert!(second.actions.is_empty(), "{:?}", second.actions);
        assert_eq!(
            "root:x:0:\nandroid_group_10123:x:10123:\nandroid_group_30009:x:30009:\n",
            fs::read_to_string(root.join("etc/group")).unwrap()
        );
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn identical_file_is_not_rewritten() {
        let root = temp_rootfs("identical");
        let target = root.join("etc/apk/repositories");
        fs::create_dir_all(target.parent().unwrap()).unwrap();
        fs::write(&target, "same\n").unwrap();
        let before = fs::metadata(&target).unwrap();

        let mut request = spec(&root, "debian");
        request.files = vec![GuestFile {
            relative_path: "/etc/apk/repositories".to_string(),
            contents: "same\n".to_string(),
        }];
        request.directories.clear();
        request.group_ids.clear();
        let actions = prepare(&request).unwrap().actions;

        assert!(actions.is_empty(), "{actions:?}");
        assert_eq!(before.modified().unwrap(), fs::metadata(&target).unwrap().modified().unwrap());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn alpine_gets_env_and_others_get_bash_prompt() {
        let root = temp_rootfs("env");
        let alpine = prepare(&spec(&root, "alpine")).unwrap();
        assert_eq!(
            vec![("ENV".to_string(), prompt::ASH_PROMPT_PATH.to_string())],
            alpine.environment
        );
        assert!(root.join("etc/operit/prompt.sh").is_file());

        let debian_root = temp_rootfs("env-debian");
        let debian = prepare(&spec(&debian_root, "debian")).unwrap();
        assert_eq!(
            vec![
                ("PROMPT_COMMAND".to_string(), prompt::BASH_PROMPT_COMMAND.to_string()),
                ("PS1".to_string(), prompt::BASH_PS1.to_string()),
            ],
            debian.environment
        );
        // Non-alpine distros do not get the ash prompt file.
        assert!(!debian_root.join("etc/operit/prompt.sh").exists());
        let _ = fs::remove_dir_all(root);
        let _ = fs::remove_dir_all(debian_root);
    }

    #[test]
    fn repair_legacy_runs_and_is_idempotent() {
        let root = temp_rootfs("repair");
        fs::write(
            root.join("etc/profile"),
            "before\n. /etc/operit/terminal.sh\nafter\n",
        )
        .unwrap();
        fs::write(root.join("etc/terminal.sh"), "").unwrap();

        let mut request = spec(&root, "alpine");
        request.repair_legacy = true;
        let first = prepare(&request).unwrap();
        assert!(first.actions.iter().any(|action| action.contains("legacy profile")), "{:?}", first.actions);
        assert!(first.actions.iter().any(|action| action.starts_with("delete ")), "{:?}", first.actions);
        assert_eq!("before\nafter\n", fs::read_to_string(root.join("etc/profile")).unwrap());

        let second = prepare(&request).unwrap();
        assert!(second.actions.is_empty(), "{:?}", second.actions);
        let _ = fs::remove_dir_all(root);
    }

    /// A `dev` directory that lost its mode bits (the real-device failure) must be
    /// repaired before `dev/pts` is created, and must come back as 0755.
    #[cfg(unix)]
    #[test]
    fn repairs_dev_mode_before_creating_children() {
        use std::os::unix::fs::PermissionsExt;
        let root = temp_rootfs("repair-mode");
        fs::create_dir_all(root.join("dev")).unwrap();
        fs::set_permissions(root.join("dev"), fs::Permissions::from_mode(0o000)).unwrap();

        let outcome = prepare(&spec(&root, "alpine")).unwrap();

        let dev_mode = fs::metadata(root.join("dev")).unwrap().permissions().mode() & 0o777;
        let pts_mode = fs::metadata(root.join("dev/pts")).unwrap().permissions().mode() & 0o777;
        assert_eq!(0o755, dev_mode, "{:?}", outcome.actions);
        assert_eq!(0o755, pts_mode, "{:?}", outcome.actions);
        assert!(outcome.actions.iter().any(|action| action.starts_with("chmod ")), "{:?}", outcome.actions);
        assert!(root.join("dev/pts").is_dir());
        let _ = fs::remove_dir_all(root);
    }

    /// New `/dev` directories are chmod'ed explicitly: the process umask must not
    /// decide whether the guest can traverse them.
    #[cfg(unix)]
    #[test]
    fn dev_tree_ignores_process_umask() {
        use std::os::unix::fs::PermissionsExt;
        let root = temp_rootfs("umask");
        // SAFETY: setting and restoring the process umask; no other thread creates
        // files whose final mode is asserted here.
        let previous = unsafe { libc::umask(0o077) };
        let prepared = prepare(&spec(&root, "alpine"));
        unsafe { libc::umask(previous) };
        prepared.unwrap();

        let dev_mode = fs::metadata(root.join("dev")).unwrap().permissions().mode() & 0o777;
        let pts_mode = fs::metadata(root.join("dev/pts")).unwrap().permissions().mode() & 0o777;
        assert_eq!(0o755, dev_mode);
        assert_eq!(0o755, pts_mode);
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn missing_rootfs_is_an_error_with_its_path() {
        let error = prepare(&PrepareSpec {
            rootfs: "definitely/not/a/real/rootfs".to_string(),
            directories: Vec::new(),
            files: Vec::new(),
            group_ids: Vec::new(),
            distro: "alpine".to_string(),
            repair_legacy: false,
        })
        .unwrap_err();
        assert!(error.contains("rootfs is missing"), "{error}");
        assert!(error.contains("definitely/not/a/real/rootfs"), "{error}");
    }
}
