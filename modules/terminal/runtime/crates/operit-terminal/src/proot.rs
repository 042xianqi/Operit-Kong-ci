//! proot argv + envp construction. Single source of truth: no other layer may build these.
//!
//! Reference: Operit2 `hosts/android/src/runtime_common.rs:49-127`
//! (`buildAndroidProotCommandWithRootfs`). Invariants kept verbatim:
//! 1. `PROOT_LOADER` and `PROOT_TMP_DIR` are always set (missing loader = silent death).
//! 2. `PROOT_NO_SECCOMP` never reaches proot (Operit2 uses `env_remove`).
//! 3. `LD_LIBRARY_PATH` is empty so host paths cannot leak into the guest.
//! 4. Base binds carry no `!`; app-data binds do (`!` = "skip if the host path is absent").
//! 5. `-w <dir>` precedes the guest command.

use crate::spec::ProotSpec;

pub const PROOT_TMP_DIR: &str = "PROOT_TMP_DIR";
pub const PROOT_LOADER: &str = "PROOT_LOADER";
pub const PROOT_NO_SECCOMP: &str = "PROOT_NO_SECCOMP";

/// Guest start directory used by Operit2.
pub const DEFAULT_GUEST_WORKING_DIR: &str = "/root";
/// Fixed by Operit2 `terminal.rs:1436`.
pub const GUEST_LANG: &str = "C.UTF-8";
/// Guest PATH; deliberately not inherited from the host.
pub const GUEST_PATH: &str = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

/// Pseudo-filesystem binds, in Operit2 order. These host paths exist on Android,
/// so none of them gets the `!` suffix.
pub const BASE_BINDS: [&str; 7] = [
    "/proc",
    "/dev",
    "/sys",
    "/dev/pts",
    "/sdcard",
    "/storage",
    "/data/local/tmp:/data/local/tmp",
];

/// One resolved launch: exactly what is handed to `execve`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProotCommand {
    pub argv: Vec<String>,
    /// Insertion-ordered envp. `execve` needs `KEY=VALUE` pairs; order is cosmetic but
    /// stable output makes `terminal-init.log` diffable.
    pub environment: Vec<(String, String)>,
    pub working_directory: String,
    /// Keys deliberately stripped; diagnostics only.
    pub removed_environment_keys: Vec<String>,
}

impl ProotCommand {
    /// `KEY=VALUE` block for `execve`.
    pub fn envp(&self) -> Vec<String> {
        self.environment
            .iter()
            .map(|(key, value)| format!("{key}={value}"))
            .collect()
    }

    /// Single-line argv for `terminal-init.log`.
    pub fn describe_argv(&self) -> String {
        self.argv.iter().map(|arg| quote(arg)).collect::<Vec<_>>().join(" ")
    }
}

fn quote(arg: &str) -> String {
    if arg.is_empty() || arg.contains(' ') || arg.contains('\t') {
        format!("\"{}\"", arg.replace('"', "\\\""))
    } else {
        arg.to_string()
    }
}

/// Split a `source:target` bind into its pair; a bare path binds to itself.
/// Equivalent to Kotlin's `split(':', limit = 2)`.
pub fn parse_bind_pair(bind: &str) -> (&str, &str) {
    match bind.split_once(':') {
        Some((source, target)) => (source, target),
        None => (bind, bind),
    }
}

/// Render one `-b` value. `!` marks "skip this bind if the host path is missing".
pub fn bind_argument(source: &str, target: &str) -> String {
    if target.is_empty() || target == source {
        format!("{source}!")
    } else {
        format!("{source}:{target}!")
    }
}

/// Append or replace an env entry, keeping the position of the first insertion.
fn set_env(environment: &mut Vec<(String, String)>, key: &str, value: &str) {
    if let Some(slot) = environment.iter_mut().find(|(k, _)| k == key) {
        slot.1 = value.to_string();
    } else {
        environment.push((key.to_string(), value.to_string()));
    }
}

/// Build the complete argv + envp for one launch.
pub fn build(spec: &ProotSpec) -> Result<ProotCommand, String> {
    spec.validate()?;

    let mut argv: Vec<String> = Vec::with_capacity(16 + 2 * spec.binds.len() + spec.guest_command.len());
    argv.push(spec.proot.clone());
    argv.push("-0".to_string());
    // Android filesystems cannot hardlink across the board; emulate with symlinks.
    argv.push("--link2symlink".to_string());
    argv.push("-r".to_string());
    argv.push(spec.rootfs.clone());

    let mut bound: Vec<(&str, &str)> = Vec::with_capacity(BASE_BINDS.len() + spec.binds.len());
    for bind in BASE_BINDS {
        bound.push(parse_bind_pair(bind));
        argv.push("-b".to_string());
        argv.push(bind.to_string());
    }

    for bind in &spec.binds {
        let source = bind.source();
        let target = bind.target();
        if bound
            .iter()
            .any(|(seen_source, seen_target)| *seen_source == source && *seen_target == target)
        {
            continue;
        }
        bound.push((source, target));
        argv.push("-b".to_string());
        argv.push(bind_argument(&bind.source, &bind.target));
    }

    argv.push("-w".to_string());
    argv.push(spec.guest_working_directory.clone());
    argv.extend(spec.guest_command.iter().cloned());

    let mut environment: Vec<(String, String)> = Vec::with_capacity(8 + spec.guest_environment.len());
    set_env(&mut environment, PROOT_TMP_DIR, &spec.tmp_dir);
    set_env(&mut environment, PROOT_LOADER, &spec.loader);
    set_env(&mut environment, "LD_LIBRARY_PATH", "");
    set_env(&mut environment, "HOME", "/root");
    set_env(&mut environment, "LANG", GUEST_LANG);
    set_env(&mut environment, "PATH", GUEST_PATH);
    set_env(&mut environment, "TERM", "xterm-256color");
    for (key, value) in &spec.guest_environment {
        set_env(&mut environment, key, value);
    }
    // Same effect as Operit2's `Command::env_remove`: the exec'd envp simply lacks it.
    environment.retain(|(key, _)| key != PROOT_NO_SECCOMP);

    Ok(ProotCommand {
        argv,
        environment,
        working_directory: spec.process_working_directory.clone(),
        removed_environment_keys: vec![PROOT_NO_SECCOMP.to_string()],
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::spec::BindSpec;
    use std::collections::BTreeMap;

    fn base_spec() -> ProotSpec {
        ProotSpec {
            proot: "/data/app/~~x/com.ai.assistance.operit.debug-y/lib/arm64/liboperit_proot.so".to_string(),
            loader: "/data/app/~~x/com.ai.assistance.operit.debug-y/lib/arm64/liboperit_loader.so".to_string(),
            tmp_dir: "/data/user/0/com.ai.assistance.operit.debug/files/tmp".to_string(),
            rootfs:
                "/data/user/0/com.ai.assistance.operit.debug/files/usr/var/lib/proot-distro/installed-rootfs/alpine"
                    .to_string(),
            process_working_directory: "/data/user/0/com.ai.assistance.operit.debug/files".to_string(),
            binds: Vec::new(),
            guest_command: vec!["/bin/sh".to_string(), "-i".to_string()],
            guest_environment: BTreeMap::new(),
            guest_working_directory: DEFAULT_GUEST_WORKING_DIR.to_string(),
        }
    }

    fn bind(source: &str, target: &str) -> BindSpec {
        BindSpec {
            source: source.to_string(),
            target: target.to_string(),
        }
    }

    fn env_value<'a>(cmd: &'a ProotCommand, key: &str) -> Option<&'a str> {
        cmd.environment
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
    }

    #[test]
    fn argv_order_matches_operit2() {
        let spec = base_spec();
        let cmd = build(&spec).expect("build");
        let mut expected = vec![
            spec.proot.clone(),
            "-0".to_string(),
            "--link2symlink".to_string(),
            "-r".to_string(),
            spec.rootfs.clone(),
        ];
        for b in BASE_BINDS {
            expected.push("-b".to_string());
            expected.push(b.to_string());
        }
        expected.push("-w".to_string());
        expected.push(DEFAULT_GUEST_WORKING_DIR.to_string());
        expected.push("/bin/sh".to_string());
        expected.push("-i".to_string());
        assert_eq!(cmd.argv, expected);
    }

    #[test]
    fn base_binds_carry_no_skip_suffix() {
        let cmd = build(&base_spec()).expect("build");
        let mut idx = cmd.argv.iter().position(|a| a == "/proc").expect("-b /proc") - 1;
        assert_eq!(cmd.argv[idx], "-b");
        while idx + 1 < cmd.argv.len() && cmd.argv[idx] == "-b" {
            let value = &cmd.argv[idx + 1];
            assert!(!value.ends_with('!'), "base bind must not carry '!': {value}");
            idx += 2;
        }
    }

    #[test]
    fn app_binds_end_with_skip_suffix() {
        let mut spec = base_spec();
        spec.binds = vec![
            bind("/data/user/0/com.ai.assistance.operit.debug/files/tmp", ""),
            bind(
                "/data/user/0/com.ai.assistance.operit.debug/files/tmp",
                "/dev/shm",
            ),
        ];
        let cmd = build(&spec).expect("build");
        assert!(cmd
            .argv
            .contains(&"/data/user/0/com.ai.assistance.operit.debug/files/tmp!".to_string()));
        assert!(cmd.argv.contains(
            &"/data/user/0/com.ai.assistance.operit.debug/files/tmp:/dev/shm!".to_string()
        ));
    }

    #[test]
    fn duplicate_binds_are_emitted_once() {
        let mut spec = base_spec();
        spec.binds = vec![
            // Identical to the base `/data/local/tmp:/data/local/tmp` pair.
            bind("/data/local/tmp", ""),
            bind("/dev", "/dev"),
            bind("/data/user/0/com.ai.assistance.operit.debug/a", ""),
            bind("/data/user/0/com.ai.assistance.operit.debug/a", ""),
        ];
        let cmd = build(&spec).expect("build");
        let dev_binds = cmd.argv.iter().filter(|a| *a == "/dev").count();
        assert_eq!(dev_binds, 1, "duplicate /dev bind leaked into argv");
        let tmp_binds = cmd
            .argv
            .iter()
            .filter(|a| *a == "/data/local/tmp:/data/local/tmp")
            .count();
        assert_eq!(tmp_binds, 1, "app bind duplicated the base bind");
        let app_binds = cmd
            .argv
            .iter()
            .filter(|a| *a == "/data/user/0/com.ai.assistance.operit.debug/a!")
            .count();
        assert_eq!(app_binds, 1, "repeated app bind leaked into argv");
    }

    #[test]
    fn working_directory_precedes_guest_command() {
        let mut spec = base_spec();
        spec.guest_working_directory = "/home/operit".to_string();
        let cmd = build(&spec).expect("build");
        let w_index = cmd.argv.iter().position(|a| a == "-w").expect("-w");
        assert_eq!(cmd.argv[w_index + 1], "/home/operit");
        assert_eq!(&cmd.argv[w_index + 2..], &["/bin/sh".to_string(), "-i".to_string()]);
    }

    #[test]
    fn environment_has_loader_and_empty_ld_library_path() {
        let spec = base_spec();
        let cmd = build(&spec).expect("build");
        assert_eq!(env_value(&cmd, PROOT_LOADER), Some(spec.loader.as_str()));
        assert_eq!(env_value(&cmd, PROOT_TMP_DIR), Some(spec.tmp_dir.as_str()));
        assert_eq!(env_value(&cmd, "LD_LIBRARY_PATH"), Some(""));
        assert_eq!(env_value(&cmd, "LANG"), Some(GUEST_LANG));
        assert_eq!(env_value(&cmd, "PATH"), Some(GUEST_PATH));
        assert_eq!(env_value(&cmd, "TERM"), Some("xterm-256color"));
        assert_eq!(cmd.working_directory, spec.process_working_directory);
    }

    #[test]
    fn proot_no_seccomp_is_removed_and_reported() {
        let mut spec = base_spec();
        spec.guest_environment
            .insert(PROOT_NO_SECCOMP.to_string(), "1".to_string());
        let cmd = build(&spec).expect("build");
        assert!(env_value(&cmd, PROOT_NO_SECCOMP).is_none());
        assert!(!cmd.envp().iter().any(|kv| kv.starts_with("PROOT_NO_SECCOMP=")));
        assert_eq!(cmd.removed_environment_keys, vec![PROOT_NO_SECCOMP.to_string()]);
    }

    #[test]
    fn guest_environment_overrides_in_place() {
        let mut spec = base_spec();
        spec.guest_environment.insert("TERM".to_string(), "dumb".to_string());
        spec.guest_environment.insert("ENV".to_string(), "/etc/operit/prompt.sh".to_string());
        let cmd = build(&spec).expect("build");
        assert_eq!(env_value(&cmd, "TERM"), Some("dumb"));
        assert_eq!(env_value(&cmd, "ENV"), Some("/etc/operit/prompt.sh"));
        let term_index = cmd.environment.iter().position(|(k, _)| k == "TERM").expect("TERM");
        // Override must not duplicate the key.
        assert_eq!(cmd.environment.iter().filter(|(k, _)| k == "TERM").count(), 1);
        assert!(term_index < cmd.environment.iter().position(|(k, _)| k == "ENV").expect("ENV"));
    }

    #[test]
    fn envp_renders_key_value_pairs() {
        let cmd = build(&base_spec()).expect("build");
        let envp = cmd.envp();
        assert!(envp.iter().any(|kv| kv == "LD_LIBRARY_PATH="));
        assert_eq!(envp.len(), cmd.environment.len());
    }

    #[test]
    fn rejects_missing_guest_command() {
        let mut spec = base_spec();
        spec.guest_command.clear();
        let err = build(&spec).unwrap_err();
        assert!(err.contains("guestCommand"), "{err}");
    }

    #[test]
    fn rejects_relative_rootfs() {
        let mut spec = base_spec();
        spec.rootfs = "usr/alpine".to_string();
        let err = build(&spec).unwrap_err();
        assert!(err.contains("rootfs"), "{err}");
    }

    #[test]
    fn rejects_bind_with_embedded_marker() {
        let mut spec = base_spec();
        spec.binds = vec![bind("/sdcard!", "")];
        let err = build(&spec).unwrap_err();
        assert!(err.contains("binds[0].source"), "{err}");
    }

    #[test]
    fn rejects_relative_guest_executable() {
        let mut spec = base_spec();
        spec.guest_command = vec!["sh".to_string()];
        let err = build(&spec).unwrap_err();
        assert!(err.contains("guestCommand[0]"), "{err}");
    }

    #[test]
    fn guest_working_directory_defaults_to_root() {
        let json = r#"{
            "proot": "/b/proot",
            "loader": "/b/loader",
            "tmpDir": "/f/tmp",
            "rootfs": "/f/rootfs",
            "processWorkingDirectory": "/f",
            "guestCommand": ["/bin/sh", "-i"]
        }"#;
        let spec: ProotSpec = serde_json::from_str(json).expect("parse");
        assert_eq!(spec.guest_working_directory, DEFAULT_GUEST_WORKING_DIR);
        let cmd = build(&spec).expect("build");
        let len = cmd.argv.len();
        assert_eq!(cmd.argv[len - 4], "-w");
        assert_eq!(cmd.argv[len - 3], "/root");
    }

    #[test]
    fn kotlin_json_shape_round_trips() {
        // Shape Kotlin's kotlinx.serialization encoder emits for the shim.
        let json = r#"{
            "proot": "/b/proot",
            "loader": "/b/loader",
            "tmpDir": "/f/tmp",
            "rootfs": "/f/rootfs",
            "processWorkingDirectory": "/f",
            "binds": [{"source": "/f/tmp", "target": ""}, {"source": "/f/tmp", "target": "/dev/shm"}],
            "guestCommand": ["/bin/sh", "-i"],
            "guestEnvironment": {"ENV": "/etc/operit/prompt.sh", "OPERIT_AUTOMATION": "1"},
            "guestWorkingDirectory": "/root"
        }"#;
        let spec: ProotSpec = serde_json::from_str(json).expect("parse");
        let cmd = build(&spec).expect("build");
        assert!(cmd.argv.contains(&"/f/tmp!".to_string()));
        assert!(cmd.argv.contains(&"/f/tmp:/dev/shm!".to_string()));
        assert_eq!(env_value(&cmd, "ENV"), Some("/etc/operit/prompt.sh"));
    }
}
