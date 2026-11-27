//! Rootfs installation: archive → directory tree. Replaces `TarReader.kt` +
//! `TerminalRootfsInstaller.kt` extraction internals.
//!
//! Kotlin spools the APK asset to a plain file and hands over the path: Rust never
//! touches `AssetManager`. Everything after that (sha256, gzip/xz, tar, traversal
//! rejection, symlink/hardlink resolution, modes, structure validation, resolv.conf,
//! markers, staging + atomic rename) is owned here.
//!
//! Reference behaviour: `modules/terminal/src/main/java/com/ai/assistance/operit/terminal/utils/TerminalRootfsInstaller.kt`
//! (`installFromAsset`, `extractTar`, `safeMemberPath`, `safeLinkTarget`, `applyMode`,
//! `verifyRootfsStructure`, `validateArm64`, `writeResolver`).
//!
//! Deliberate differences from the Kotlin/Operit2 originals, all in the direction of
//! doing less work with the archive bytes:
//! * No external `xz` process: `.tar.xz` is decoded by `lzma-rs` into a sibling temp
//!   file (Android's `java.util.zip` has no xz support, which is why Kotlin shelled
//!   out). The tar is then parsed the same way as the gzip path.
//! * `tar::Archive::unpack` is never used; every entry goes through the rules below.
//! * Links are never created while regular files are still being written, so no
//!   archive entry can write *through* a symlink it smuggled in.

use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Cursor, Read, Write};
use std::path::{Component, Path, PathBuf};

use sha2::{Digest, Sha256};

use crate::diagnostics;

/// Written by every successful install; `TerminalManager` historically looked for it.
pub const INSTALLED_MARKER: &str = ".operit_installed_ok";
/// Records the sha256 of the archive that produced this rootfs (idempotency key).
pub const INSTALLED_SHA_MARKER: &str = ".operit_rootfs_sha256";

/// Fixed DNS, same value as `TerminalRootfsInstaller.RESOLV_CONF`.
const RESOLV_CONF: &str = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n";
/// 8 GiB expanded-size ceiling (Kotlin `MAX_ROOTFS_BYTES`).
const MAX_ROOTFS_BYTES: u64 = 8 * 1024 * 1024 * 1024;
/// 500k member ceiling (Kotlin `MAX_ROOTFS_ENTRIES`).
const MAX_ROOTFS_ENTRIES: u64 = 500_000;
/// The shell whose ELF header is validated, and the busybox that must be present.
const SHELL: &str = "bin/sh";
const BUSYBOX: &str = "bin/busybox";
/// ELF `e_machine` for AArch64.
const EM_AARCH64: u16 = 183;

/// One install request. `archive_path` is a host file (already spooled from assets).
#[derive(Debug, Clone)]
pub struct InstallSpec {
    pub archive_path: String,
    pub rootfs: String,
    pub instance_id: String,
    pub display_name: String,
    pub expected_sha256: Option<String>,
    /// Alpine layout: flat rootfs at archive root, `bin/busybox` + `bin/sh`.
    pub alpine: bool,
}

/// Install result reported back to Kotlin.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstallOutcome {
    pub installed: bool,
    pub actions: Vec<String>,
    pub sha256: String,
}

/// Install `spec.archive_path` at `spec.rootfs`.
///
/// The archive is verified (sha256), decoded (gzip/xz/plain tar) and extracted into a
/// sibling staging directory. Only after the layout, the shell ELF header and the
/// `bin/busybox` + `bin/sh` + `etc` predicate pass is the staging directory renamed
/// onto the final path, so a failure never publishes a partial rootfs.
pub fn install(spec: &InstallSpec) -> Result<InstallOutcome, String> {
    validate_spec(spec)?;

    let rootfs = Path::new(spec.rootfs.trim());
    let archive = Path::new(spec.archive_path.trim());

    let expected = spec
        .expected_sha256
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty());

    // Already installed from this exact archive: reuse it, never re-extract 170 MB.
    if let Some(expected) = expected {
        if rootfs.is_dir() && is_installed(spec.rootfs.trim()) {
            if let Some(recorded) = recorded_sha(rootfs) {
                if recorded.eq_ignore_ascii_case(expected) {
                    return Ok(InstallOutcome {
                        installed: true,
                        actions: Vec::new(),
                        sha256: expected.to_ascii_lowercase(),
                    });
                }
            }
        }
    }

    if !archive.is_file() {
        return Err(format!(
            "Rootfs archive is missing: {}",
            archive.display()
        ));
    }

    let sha256 = sha256_file(archive)?;
    if let Some(expected) = expected {
        if !sha256.eq_ignore_ascii_case(expected) {
            return Err(format!(
                "Terminal rootfs SHA-256 mismatch: expected={expected} actual={sha256}"
            ));
        }
    }

    let parent = rootfs
        .parent()
        .ok_or_else(|| format!("Rootfs path has no parent: {}", rootfs.display()))?;
    fs::create_dir_all(parent)
        .map_err(|error| format!("Cannot create {}: {error}", parent.display()))?;

    let staging = staged_path(rootfs, "staging");
    // A leftover staging directory is the product of a crash and cannot be trusted.
    if staging.symlink_metadata().is_ok() {
        remove_tree(&staging)?;
    }
    fs::create_dir_all(&staging)
        .map_err(|error| format!("Cannot create staging directory {}: {error}", staging.display()))?;

    let extracted = (|| -> Result<Vec<String>, String> {
        let mut actions = extract_archive(archive, &staging)?;
        validate_rootfs(&staging, spec.alpine)?;
        write_resolver(&staging, &mut actions)?;
        fs::create_dir_all(staging.join("root"))
            .map_err(|error| format!("Cannot create rootfs root directory: {error}"))?;
        actions.push("mkdir root".to_string());
        write_text(&staging.join(INSTALLED_MARKER), "ok\n")?;
        actions.push(format!("write {INSTALLED_MARKER}"));
        write_text(
            &staging.join(INSTALLED_SHA_MARKER),
            &format!("{sha256}\n"),
        )?;
        actions.push(format!("write {INSTALLED_SHA_MARKER} ({sha256})"));
        Ok(actions)
    })();

    let actions = match extracted {
        Ok(mut actions) => {
            actions.push(diagnostics::snapshot("install_published", rootfs));
            actions
        }
        Err(error) => {
            let _ = remove_tree(&staging);
            return Err(error);
        }
    };

    publish(&staging, rootfs, parent)?;

    Ok(InstallOutcome {
        installed: true,
        actions,
        sha256,
    })
}

/// Content predicate: `bin/busybox` file + `bin/sh` resolvable + `etc/` directory.
///
/// Never checks the execute bit: on Android 10+ a private-directory file is not
/// executable by the host, and proot never `execve`s guest files anyway.
pub fn is_installed(rootfs: &str) -> bool {
    let root = Path::new(rootfs.trim());
    let busybox = root.join(BUSYBOX);
    let shell = root.join(SHELL);
    let etc = root.join("etc");
    busybox.is_file()
        // `metadata` follows symlinks: a dangling `bin/sh` is not installed.
        && fs::metadata(&shell).is_ok()
        && etc.is_dir()
}

// ---------------------------------------------------------------- spec / markers

fn validate_spec(spec: &InstallSpec) -> Result<(), String> {
    let archive = spec.archive_path.trim();
    if archive.is_empty() {
        return Err("Rootfs archive path must not be empty".to_string());
    }
    if !Path::new(archive).is_absolute() {
        return Err(format!("Rootfs archive path must be absolute: {archive}"));
    }
    // Ported from `installFromAsset`: it becomes the instance directory name.
    // Hyphens are allowed because distribution ids are hyphenated (`debian-13`);
    // dots and slashes stay rejected so the name cannot escape its parent.
    if spec.instance_id.is_empty()
        || !spec
            .instance_id
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || ch == '_' || ch == '-')
    {
        return Err(format!(
            "Invalid terminal instance identifier: {}",
            spec.instance_id
        ));
    }
    let display_name = spec.display_name.trim();
    if display_name.is_empty() || display_name.contains('\0') {
        return Err("Terminal instance name must not be blank or contain NUL".to_string());
    }
    let rootfs = spec.rootfs.trim();
    if rootfs.is_empty() {
        return Err("Rootfs path must not be empty".to_string());
    }
    if !Path::new(rootfs).is_absolute() {
        return Err(format!("Rootfs path must be absolute: {rootfs}"));
    }
    let file_name = Path::new(rootfs)
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_default();
    if file_name.is_empty() || file_name == "." || file_name == ".." {
        return Err(format!("Invalid rootfs directory name: {rootfs}"));
    }
    Ok(())
}

fn recorded_sha(rootfs: &Path) -> Option<String> {
    fs::read_to_string(rootfs.join(INSTALLED_SHA_MARKER))
        .ok()
        .map(|text| text.trim().to_string())
        .filter(|text| !text.is_empty())
}

fn staged_path(target: &Path, kind: &str) -> PathBuf {
    let name = target
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_default();
    target.with_file_name(format!("{name}.{kind}-{}", std::process::id()))
}

fn write_text(path: &Path, contents: &str) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("Cannot create {}: {error}", parent.display()))?;
    }
    fs::write(path, contents).map_err(|error| format!("Cannot write {}: {error}", path.display()))
}

fn remove_tree(path: &Path) -> Result<(), String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_dir() => fs::remove_dir_all(path)
            .map_err(|error| format!("Cannot remove {}: {error}", path.display())),
        Ok(_) => fs::remove_file(path)
            .map_err(|error| format!("Cannot remove {}: {error}", path.display())),
        Err(_) => Ok(()),
    }
}

fn publish(staging: &Path, target: &Path, parent: &Path) -> Result<(), String> {
    fs::create_dir_all(parent)
        .map_err(|error| format!("Cannot create {}: {error}", parent.display()))?;

    let exists = target.symlink_metadata().is_ok();
    if !exists {
        if let Err(error) = fs::rename(staging, target) {
            let _ = remove_tree(staging);
            return Err(format!(
                "Cannot publish terminal instance at {}: {error}",
                target.display()
            ));
        }
        return Ok(());
    }

    // Overwrite install: move the old tree aside first, so that a crash between the
    // two renames still leaves a usable rootfs in place.
    let retired = staged_path(target, "retired");
    let _ = remove_tree(&retired);
    fs::rename(target, &retired).map_err(|error| {
        let _ = remove_tree(staging);
        format!(
            "Cannot retire previous rootfs {}: {error}",
            target.display()
        )
    })?;
    if let Err(error) = fs::rename(staging, target) {
        // Roll back: the previous rootfs must keep working.
        let _ = fs::rename(&retired, target);
        let _ = remove_tree(staging);
        return Err(format!(
            "Cannot publish terminal instance at {}: {error}",
            target.display()
        ));
    }
    let _ = remove_tree(&retired);
    Ok(())
}

// ---------------------------------------------------------------- archive

fn sha256_file(path: &Path) -> Result<String, String> {
    let file = File::open(path)
        .map_err(|error| format!("Cannot open rootfs archive {}: {error}", path.display()))?;
    let mut reader = BufReader::new(file);
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let read = reader
            .read(&mut buffer)
            .map_err(|error| format!("Cannot read rootfs archive {}: {error}", path.display()))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(to_hex(&hasher.finalize()))
}

fn to_hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(out, "{byte:02x}");
    }
    out
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Format {
    Gzip,
    Xz,
    Tar,
}

/// Magic-byte sniffing; file extensions lie (`rootfs.tar.gz.bin` in the APK).
fn detect_format(magic: &[u8]) -> Format {
    if magic.starts_with(&[0x1f, 0x8b]) {
        Format::Gzip
    } else if magic.starts_with(&[0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00]) {
        Format::Xz
    } else {
        Format::Tar
    }
}

fn extract_archive(archive: &Path, destination: &Path) -> Result<Vec<String>, String> {
    let file = File::open(archive)
        .map_err(|error| format!("Cannot open rootfs archive {}: {error}", archive.display()))?;
    let mut reader = BufReader::new(file);

    let mut magic = [0u8; 6];
    let mut filled = 0usize;
    while filled < magic.len() {
        let read = reader
            .read(&mut magic[filled..])
            .map_err(|error| format!("Cannot read rootfs archive {}: {error}", archive.display()))?;
        if read == 0 {
            break;
        }
        filled += read;
    }
    let magic = &magic[..filled];
    let format = detect_format(magic);
    let stream = Cursor::new(magic.to_vec()).chain(reader);

    match format {
        Format::Gzip => {
            let decoder = flate2::read::MultiGzDecoder::new(stream);
            extract_tar(decoder, destination)
        }
        Format::Tar => extract_tar(stream, destination),
        Format::Xz => {
            // `lzma-rs` pushes instead of pulling, so the tar lands in a sibling temp
            // file first (never inside the published tree).
            let tar_path = PathBuf::from(format!("{}.tar", destination.display()));
            let result = (|| -> Result<Vec<String>, String> {
                let file = File::create(&tar_path)
                    .map_err(|error| format!("Cannot create {}: {error}", tar_path.display()))?;
                let mut writer = BufWriter::new(file);
                lzma_rs::xz_decompress(&mut BufReader::new(stream), &mut writer)
                    .map_err(|error| format!("Cannot decompress xz rootfs archive: {error}"))?;
                writer
                    .flush()
                    .map_err(|error| format!("Cannot write {}: {error}", tar_path.display()))?;
                drop(writer);
                let tar = File::open(&tar_path)
                    .map_err(|error| format!("Cannot open {}: {error}", tar_path.display()))?;
                extract_tar(BufReader::new(tar), destination)
            })();
            let _ = fs::remove_file(&tar_path);
            result
        }
    }
}

/// Archive layout: flat (`./bin/sh`) or a single distribution prefix (`alpine/bin/sh`).
#[derive(Debug, Clone)]
struct Layout {
    strip: bool,
    top: String,
}

/// Names that a Linux rootfs has directly under `/`. An archive member whose first
/// component is one of these is a flat rootfs, never a single distribution directory.
fn is_rootfs_top_level(name: &str) -> bool {
    matches!(
        name,
        "bin"
            | "boot"
            | "data"
            | "dev"
            | "etc"
            | "home"
            | "lib"
            | "lib64"
            | "media"
            | "mnt"
            | "opt"
            | "proc"
            | "root"
            | "run"
            | "sbin"
            | "srv"
            | "sys"
            | "tmp"
            | "usr"
            | "var"
    )
}

/// Normalise an archive member name to a path relative to the rootfs root.
///
/// Returns `None` when the name escapes the root; `Some("")` means "the archive root
/// itself" (`.` / `./` entries), which callers skip. Ported from `safeMemberPath`.
fn safe_member_path(raw: &str) -> Option<String> {
    let name = raw.replace('\\', "/");
    if name.starts_with('/') {
        return None;
    }
    let mut parts: Vec<&str> = Vec::new();
    for part in name.split('/') {
        if part.is_empty() || part == "." {
            continue;
        }
        if part == ".." {
            return None;
        }
        parts.push(part);
    }
    let joined = parts.join("/");
    // Windows `Prefix`/`RootDir` components (`C:`, `\`) must never survive `join`.
    if Path::new(&joined).components().any(|component| {
        matches!(
            component,
            Component::Prefix(_) | Component::RootDir | Component::CurDir | Component::ParentDir
        )
    }) {
        return None;
    }
    Some(joined)
}

/// Apply the layout prefix rule to a member name.
fn resolve_member(raw: &str, layout: &Layout) -> Result<String, String> {
    let normalized = safe_member_path(raw)
        .ok_or_else(|| format!("Rootfs archive member escapes its instance: {raw}"))?;
    if normalized.is_empty() || !layout.strip {
        return Ok(normalized);
    }
    let (first, rest) = match normalized.split_once('/') {
        Some((first, rest)) => (first, rest),
        None => (normalized.as_str(), ""),
    };
    if first != layout.top {
        return Err(format!(
            "Rootfs archive must have one distribution directory: {raw}"
        ));
    }
    Ok(rest.to_string())
}

/// Hardlink targets are archive member names as well; tolerate archives that omit the
/// prefix the members carry (the tar tools disagree on this) while staying inside root.
fn resolve_link_member(raw: &str, layout: &Layout) -> Result<String, String> {
    let normalized = safe_member_path(raw)
        .ok_or_else(|| format!("Rootfs archive member escapes its instance: {raw}"))?;
    if normalized.is_empty() {
        return Err("Rootfs hardlink has no target".to_string());
    }
    if layout.strip {
        let prefix = format!("{}/", layout.top);
        if let Some(rest) = normalized.strip_prefix(&prefix) {
            return Ok(rest.to_string());
        }
    }
    Ok(normalized)
}

/// Resolve a symlink target lexically, relative to the link's own directory, so the
/// written link resolves both on the host and inside proot. Ported from
/// `safeLinkTarget`; `None` means the target leaves the rootfs.
fn safe_link_target(link_relative: &str, target: &str) -> Option<String> {
    let link_parts: Vec<&str> = link_relative
        .split('/')
        .filter(|part| !part.is_empty() && *part != ".")
        .collect();
    if link_parts.is_empty() {
        return None;
    }
    let parent = link_parts[..link_parts.len() - 1].to_vec();

    let normalized = target.replace('\\', "/");
    let mut resolved: Vec<String> = if normalized.starts_with('/') {
        Vec::new()
    } else {
        parent.iter().map(|part| (*part).to_string()).collect()
    };
    for part in normalized.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                // Climbing above the rootfs root leaves the instance.
                resolved.pop()?;
            }
            other => resolved.push(other.to_string()),
        }
    }
    Some(relativize(&parent, &resolved))
}

/// Turn an absolute-from-rootfs path into one relative to `base`
/// (`base = [bin]`, `resolved = [bin, busybox]` → `busybox`).
fn relativize(base: &[&str], resolved: &[String]) -> String {
    let mut common = 0;
    while common < base.len() && common < resolved.len() && base[common] == resolved[common] {
        common += 1;
    }
    let mut parts: Vec<String> = Vec::new();
    for _ in common..base.len() {
        parts.push("..".to_string());
    }
    parts.extend(resolved[common..].iter().cloned());
    if parts.is_empty() {
        // The link points at its own directory; "." is legal and harmless.
        ".".to_string()
    } else {
        parts.join("/")
    }
}

struct PendingLink {
    /// Path of the link itself, relative to the rootfs root.
    relative: String,
    /// What gets written into the link / copied from.
    resolved: String,
    /// The raw archive value, for error messages.
    raw: String,
    hard: bool,
}

fn extract_tar<R: Read>(reader: R, destination: &Path) -> Result<Vec<String>, String> {
    let mut archive = tar::Archive::new(reader);
    let entries = archive
        .entries()
        .map_err(|error| format!("Cannot read rootfs archive: {error}"))?;

    let mut count: u64 = 0;
    let mut expanded: u64 = 0;
    let mut layout: Option<Layout> = None;
    let mut links: Vec<PendingLink> = Vec::new();

    for item in entries {
        let mut entry = item.map_err(|error| format!("Cannot read rootfs archive entry: {error}"))?;
        count += 1;
        if count > MAX_ROOTFS_ENTRIES {
            return Err("Rootfs archive has too many entries".to_string());
        }

        let raw = String::from_utf8_lossy(&entry.path_bytes()).into_owned();
        let normalized = safe_member_path(&raw)
            .ok_or_else(|| format!("Rootfs archive member escapes its instance: {raw}"))?;

        // Layout is decided once, from the first member that names something.
        //
        // Two archive shapes exist in the wild:
        // * flat: `./bin/sh` (Alpine, proot-distro) or `bin/sh` without the `./`;
        // * a single distribution directory: `alpine/bin/sh`.
        //
        // The `./` spelling is unambiguous. Without it the first component decides:
        // a well-known rootfs directory (`bin`, `etc`, …) means flat, anything else
        // that is a directory (or prefixes another member) means one directory is
        // stripped. Deciding this per archive instead of per member is what keeps
        // `pipe.fifo`-style archives from having their first component eaten.
        if layout.is_none() {
            if normalized.is_empty() {
                continue;
            }
            let first_component = normalized
                .split('/')
                .next()
                .unwrap_or_default()
                .to_string();
            let dot_prefixed = raw.replace('\\', "/").starts_with("./");
            let entry_type = entry.header().entry_type();
            let looks_like_prefix = !dot_prefixed
                && !is_rootfs_top_level(&first_component)
                && (entry_type.is_dir() || normalized.contains('/'));
            layout = Some(Layout {
                strip: looks_like_prefix,
                top: first_component,
            });
        }
        let layout = layout.as_ref().expect("layout is set above");

        let relative = resolve_member(&raw, layout)?;
        // `dev/` is mounted by proot; archive copies are useless and may be devices.
        if relative.is_empty() || relative == "dev" || relative.starts_with("dev/") {
            continue;
        }

        let target = destination.join(&relative);
        let entry_type = entry.header().entry_type();
        let mode = entry.header().mode().unwrap_or(0);

        if entry_type.is_dir() {
            fs::create_dir_all(&target).map_err(|error| {
                format!("Cannot create rootfs directory {}: {error}", target.display())
            })?;
            // The archive's mode is authoritative, but `create_dir_all` is masked by
            // the process umask. Without this a 0755 directory from the archive could
            // end up 0700 (Android umask 077) or 000.
            apply_mode(&target, mode);
            continue;
        }

        if entry_type.is_file() || entry_type.is_contiguous() {
            let size = entry.size();
            expanded = expanded.saturating_add(size);
            if expanded > MAX_ROOTFS_BYTES {
                return Err("Rootfs exceeds installation size limit".to_string());
            }
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent).map_err(|error| {
                    format!("Cannot create {}: {error}", parent.display())
                })?;
            }
            let file = File::create(&target)
                .map_err(|error| format!("Cannot create {}: {error}", target.display()))?;
            let mut writer = BufWriter::new(file);
            io::copy(&mut entry, &mut writer)
                .map_err(|error| format!("Cannot extract {}: {error}", target.display()))?;
            writer
                .flush()
                .map_err(|error| format!("Cannot write {}: {error}", target.display()))?;
            drop(writer);
            apply_mode(&target, mode);
            continue;
        }

        if entry_type.is_symlink() {
            let raw_target = entry
                .link_name()
                .map_err(|error| format!("Cannot read symlink target for {raw}: {error}"))?
                .map(|name| name.to_string_lossy().into_owned())
                .unwrap_or_default();
            let resolved = safe_link_target(&relative, &raw_target)
                .ok_or_else(|| format!("Rootfs link escapes its instance: {raw_target}"))?;
            links.push(PendingLink {
                relative,
                resolved,
                raw: raw_target,
                hard: false,
            });
            continue;
        }

        if entry_type.is_hard_link() {
            let raw_target = entry
                .link_name()
                .map_err(|error| format!("Cannot read hardlink target for {raw}: {error}"))?
                .map(|name| name.to_string_lossy().into_owned())
                .unwrap_or_default();
            let resolved = resolve_link_member(&raw_target, layout)?;
            links.push(PendingLink {
                relative,
                resolved,
                raw: raw_target,
                hard: true,
            });
            continue;
        }

        return Err(format!(
            "Unsupported rootfs archive entry type {:?} for {raw}",
            entry_type
        ));
    }

    let mut actions: Vec<String> = vec![format!("extract {count} entries")];

    // `dev/` members are skipped above, so the directory would not exist at all.
    // proot needs it (and its `pts`/`shm` children); create them explicitly with
    // 0755 instead of letting the process umask decide.
    ensure_runtime_directories(destination, &mut actions)?;

    // Hardlinks first: their target must be a regular file that already exists.
    for link in links.iter().filter(|link| link.hard) {
        let source = destination.join(&link.resolved);
        if !source.is_file() {
            return Err(format!(
                "Rootfs hardlink target is not a regular file: {}",
                link.raw
            ));
        }
        let target = destination.join(&link.relative);
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)
                .map_err(|error| format!("Cannot create {}: {error}", parent.display()))?;
        }
        fs::copy(&source, &target).map_err(|error| {
            format!(
                "Cannot create rootfs hardlink {} -> {}: {error}",
                link.relative, link.raw
            )
        })?;
        actions.push(format!("hardlink {} -> {}", link.relative, link.resolved));
    }

    // Then symlinks, with the target already relative to the link's directory.
    for link in links.iter().filter(|link| !link.hard) {
        let target = destination.join(&link.relative);
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)
                .map_err(|error| format!("Cannot create {}: {error}", parent.display()))?;
        }
        create_symlink(&link.resolved, &target).map_err(|error| {
            format!(
                "Cannot create rootfs symlink {} -> {}: {error}",
                link.relative, link.resolved
            )
        })?;
        actions.push(format!("link {} -> {}", link.relative, link.resolved));
    }

    actions.push(diagnostics::snapshot("install_extracted", destination));
    Ok(actions)
}

/// Create the runtime directories that the archive cannot provide.
///
/// `dev/` members are skipped on purpose (devices in an archive are useless and may be
/// dangerous), so `dev`, `dev/pts` and `dev/shm` are created here with an explicit 0755.
fn ensure_runtime_directories(root: &Path, actions: &mut Vec<String>) -> Result<(), String> {
    for relative in ["dev", "dev/pts", "dev/shm"] {
        let path = root.join(relative);
        if !path.is_dir() {
            fs::create_dir_all(&path).map_err(|error| {
                format!("Cannot create runtime directory {}: {error}", path.display())
            })?;
            actions.push(format!("mkdir {relative} (runtime)"));
        }
        if diagnostics::mode_of(&path) != Some(0o755) {
            diagnostics::set_mode(&path, 0o755).map_err(|error| {
                format!("Cannot chmod runtime directory {}: {error}", path.display())
            })?;
            actions.push(format!("chmod {relative} 0755"));
        }
    }
    Ok(())
}

#[cfg(unix)]
fn apply_mode(path: &Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    // Only rwx; setuid/setgid/sticky are meaningless in an app-private rootfs.
    let bits = mode & 0o777;
    if bits == 0 {
        return;
    }
    // Best effort, exactly like `applyMode`: a chmod failure must not fail an install.
    let _ = fs::set_permissions(path, fs::Permissions::from_mode(bits));
}

#[cfg(not(unix))]
fn apply_mode(_path: &Path, _mode: u32) {
    // Windows hosts (where the tests run) have no POSIX mode bits to preserve.
}

#[cfg(unix)]
fn create_symlink(target: &str, link: &Path) -> io::Result<()> {
    std::os::unix::fs::symlink(target, link)
}

#[cfg(not(unix))]
fn create_symlink(_target: &str, _link: &Path) -> io::Result<()> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "symbolic links are not supported on this platform",
    ))
}

// ---------------------------------------------------------------- post-checks

/// Force the fixed resolver, as `writeResolver` does: images ship a build-host
/// `resolv.conf` (the bundled Alpine has `nameserver 223.5.5.5`) that resolves to
/// nothing inside the container. Identical content is left untouched.
fn write_resolver(root: &Path, actions: &mut Vec<String>) -> Result<(), String> {
    let resolver = root.join("etc/resolv.conf");
    if fs::read_to_string(&resolver)
        .map(|contents| contents == RESOLV_CONF)
        .unwrap_or(false)
    {
        return Ok(());
    }
    write_text(&resolver, RESOLV_CONF)?;
    actions.push("write etc/resolv.conf".to_string());
    Ok(())
}

/// Structure + architecture checks. Existence only, never the execute bit.
///
/// `busybox` is required only for the bundled Alpine image: Debian/Ubuntu/Fedora
/// proot-distro archives ship `bin/sh` but no `bin/busybox`, so requiring it would
/// reject every downloadable distribution.
fn validate_rootfs(root: &Path, alpine: bool) -> Result<(), String> {
    let busybox = root.join(BUSYBOX);
    if alpine && !busybox.is_file() {
        return Err(format!(
            "Rootfs has no bin/busybox after extraction: {}",
            busybox.display()
        ));
    }
    let shell = root.join(SHELL);
    if fs::metadata(&shell).is_err() {
        return Err(format!(
            "Rootfs bin/sh does not resolve after extraction: {}",
            shell.display()
        ));
    }
    let etc = root.join("etc");
    if !etc.is_dir() {
        return Err(format!(
            "Rootfs has no etc directory: {}",
            etc.display()
        ));
    }
    validate_arm64(&shell)
}

/// `bin/sh` must be a little-endian ELF64 AArch64 `ET_EXEC`/`ET_DYN` image.
fn validate_arm64(path: &Path) -> Result<(), String> {
    let mut header = [0u8; 20];
    let mut file = File::open(path)
        .map_err(|error| format!("Cannot open {}: {error}", path.display()))?;
    file.read_exact(&mut header)
        .map_err(|error| format!("Cannot read ELF header of {}: {error}", path.display()))?;

    let elf64_le = &header[0..4] == b"\x7fELF" && header[4] == 2 && header[5] == 1;
    if !elf64_le {
        return Err(format!(
            "Rootfs {} is not a little-endian ELF64 executable",
            path.display()
        ));
    }
    let e_type = u16::from_le_bytes([header[16], header[17]]);
    if e_type != 2 && e_type != 3 {
        return Err(format!(
            "Rootfs {} is not an ET_EXEC or ET_DYN executable (e_type={e_type})",
            path.display()
        ));
    }
    let machine = u16::from_le_bytes([header[18], header[19]]);
    if machine != EM_AARCH64 {
        return Err(format!(
            "Rootfs {} is not an ARM64 Linux executable (e_machine={machine})",
            path.display()
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::sync::atomic::{AtomicU64, Ordering};

    static UNIQUE: AtomicU64 = AtomicU64::new(0);

    struct TempDir(PathBuf);

    impl TempDir {
        fn new(tag: &str) -> Self {
            let unique = UNIQUE.fetch_add(1, Ordering::SeqCst);
            let path = std::env::temp_dir().join(format!(
                "operit-install-{tag}-{}-{unique}",
                std::process::id()
            ));
            let _ = fs::remove_dir_all(&path);
            fs::create_dir_all(&path).expect("temp dir");
            TempDir(path)
        }

        fn path(&self) -> &Path {
            &self.0
        }

        fn rootfs(&self) -> PathBuf {
            self.0.join("installed-rootfs").join("alpine")
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    /// Minimal ELF64 LE header: enough for `validate_arm64`.
    fn elf(e_type: u16, machine: u16) -> Vec<u8> {
        let mut bytes = vec![0u8; 64];
        bytes[0..4].copy_from_slice(b"\x7fELF");
        bytes[4] = 2; // ELFCLASS64
        bytes[5] = 1; // ELFDATA2LSB
        bytes[6] = 1; // EV_CURRENT
        bytes[16..18].copy_from_slice(&e_type.to_le_bytes());
        bytes[18..20].copy_from_slice(&machine.to_le_bytes());
        bytes
    }

    fn shell_elf() -> Vec<u8> {
        elf(3, EM_AARCH64)
    }

    enum Item {
        File(String, Vec<u8>, u32),
        Dir(String, u32),
        #[cfg_attr(not(unix), allow(dead_code))]
        Symlink(String, String),
        Hardlink(String, String),
    }

    /// Minimal POSIX/ustar header. Deliberately hand-written: the `tar` crate's
    /// builder strips `.` components, which is exactly the byte pattern the flat
    /// layout detection must see (`./bin/sh`).
    fn tar_header(name: &str, size: u64, mode: u32, type_flag: u8, link: Option<&str>) -> [u8; 512] {
        fn write_field(field: &mut [u8], value: &str) {
            let bytes = value.as_bytes();
            let length = bytes.len().min(field.len());
            field[..length].copy_from_slice(&bytes[..length]);
        }
        fn write_octal(field: &mut [u8], value: u64) {
            let digits = field.len() - 1;
            let text = format!("{value:0digits$o}", digits = digits);
            field[..digits].copy_from_slice(text.as_bytes());
            field[digits] = 0;
        }

        let mut header = [0u8; 512];
        write_field(&mut header[0..100], name);
        write_octal(&mut header[100..108], mode as u64);
        write_octal(&mut header[108..116], 0);
        write_octal(&mut header[116..124], 0);
        write_octal(&mut header[124..136], size);
        write_octal(&mut header[136..148], 0);
        header[156] = type_flag;
        if let Some(link) = link {
            write_field(&mut header[157..257], link);
        }
        header[257..263].copy_from_slice(b"ustar\0");
        header[263..265].copy_from_slice(b"00");
        for byte in &mut header[148..156] {
            *byte = b' ';
        }
        let checksum: u32 = header.iter().map(|byte| *byte as u32).sum();
        let text = format!("{checksum:06o}\0 ");
        header[148..156].copy_from_slice(text.as_bytes());
        header
    }

    fn tar_padding(size: usize) -> usize {
        (512 - size % 512) % 512
    }

    fn build_tar(items: &[Item]) -> Vec<u8> {
        let mut out: Vec<u8> = Vec::new();
        for item in items {
            match item {
                Item::File(name, data, mode) => {
                    out.extend_from_slice(&tar_header(name, data.len() as u64, *mode, b'0', None));
                    out.extend_from_slice(data);
                    out.resize(out.len() + tar_padding(data.len()), 0);
                }
                Item::Dir(name, mode) => {
                    out.extend_from_slice(&tar_header(name, 0, *mode, b'5', None));
                }
                Item::Symlink(name, target) => {
                    out.extend_from_slice(&tar_header(name, 0, 0o777, b'2', Some(target)));
                }
                Item::Hardlink(name, target) => {
                    out.extend_from_slice(&tar_header(name, 0, 0o755, b'1', Some(target)));
                }
            }
        }
        // Two zero blocks end an archive.
        out.extend_from_slice(&[0u8; 1024]);
        out
    }

    fn gzip(bytes: &[u8]) -> Vec<u8> {
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(bytes).expect("gzip write");
        encoder.finish().expect("gzip finish")
    }

    /// Alpine-shaped members: `prefix` is `"./"` (flat) or `"alpine/"` (prefixed).
    fn standard_items(prefix: &str) -> Vec<Item> {
        let name = |relative: &str| format!("{prefix}{relative}");
        vec![
            Item::Dir(name(""), 0o755),
            Item::Dir(name("bin"), 0o755),
            Item::File(name("bin/busybox"), shell_elf(), 0o755),
            Item::File(name("bin/sh"), shell_elf(), 0o755),
            Item::Dir(name("etc"), 0o755),
            Item::File(
                name("etc/passwd"),
                b"root:x:0:0::/root:/bin/sh\n".to_vec(),
                0o644,
            ),
            Item::Dir(name("usr"), 0o755),
            Item::Dir(name("usr/bin"), 0o755),
            Item::File(name("usr/bin/tool"), b"#!/bin/sh\necho tool\n".to_vec(), 0o755),
        ]
    }

    fn prefixed_items() -> Vec<Item> {
        standard_items("alpine/")
    }

    fn file(name: &str, data: Vec<u8>, mode: u32) -> Item {
        Item::File(name.to_string(), data, mode)
    }

    #[cfg(unix)]
    fn sym(name: &str, target: &str) -> Item {
        Item::Symlink(name.to_string(), target.to_string())
    }

    fn hard(name: &str, target: &str) -> Item {
        Item::Hardlink(name.to_string(), target.to_string())
    }

    fn write_archive(dir: &Path, name: &str, bytes: &[u8]) -> PathBuf {
        let path = dir.join(name);
        fs::write(&path, bytes).expect("write archive");
        path
    }

    fn spec_for(archive: &Path, rootfs: &Path, expected: Option<String>) -> InstallSpec {
        InstallSpec {
            archive_path: archive.display().to_string(),
            rootfs: rootfs.display().to_string(),
            instance_id: "alpine".to_string(),
            display_name: "Alpine".to_string(),
            expected_sha256: expected,
            alpine: true,
        }
    }

    fn sha_of(bytes: &[u8]) -> String {
        let mut hasher = Sha256::new();
        hasher.update(bytes);
        to_hex(&hasher.finalize())
    }

    #[test]
    fn installs_flat_dot_prefixed_layout() {
        let dir = TempDir::new("flat");
        let archive_bytes = gzip(&build_tar(&standard_items("./")));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &archive_bytes);
        let rootfs = dir.rootfs();
        let expected = sha_of(&archive_bytes);

        let outcome = install(&spec_for(&archive, &rootfs, Some(expected.clone()))).expect("install");

        assert!(outcome.installed);
        assert_eq!(outcome.sha256, expected);
        assert!(outcome.actions[0].starts_with("extract "), "{:?}", outcome.actions);
        assert!(outcome
            .actions
            .iter()
            .any(|action| action == "write etc/resolv.conf"));
        assert_eq!(
            fs::read_to_string(rootfs.join("etc/resolv.conf")).unwrap(),
            RESOLV_CONF
        );
        assert_eq!(
            fs::read(rootfs.join("bin/busybox")).unwrap(),
            shell_elf()
        );
        assert!(rootfs.join("etc/passwd").is_file());
        assert!(rootfs.join("root").is_dir());
        assert_eq!(
            fs::read_to_string(rootfs.join(INSTALLED_MARKER)).unwrap(),
            "ok\n"
        );
        assert_eq!(
            fs::read_to_string(rootfs.join(INSTALLED_SHA_MARKER)).unwrap(),
            format!("{expected}\n")
        );
        // No staging leftovers next to the published rootfs.
        let leftovers: Vec<String> = fs::read_dir(rootfs.parent().unwrap())
            .unwrap()
            .filter_map(|entry| entry.ok())
            .map(|entry| entry.file_name().to_string_lossy().into_owned())
            .filter(|name| name.contains(".staging-") || name.contains(".retired-"))
            .collect();
        assert!(leftovers.is_empty(), "{leftovers:?}");
        assert!(is_installed(&rootfs.display().to_string()));
    }

    #[test]
    fn replaces_shipped_resolver() {
        let dir = TempDir::new("resolver");
        let mut items = standard_items("./");
        items.push(file(
            "./etc/resolv.conf",
            b"nameserver 223.5.5.5\n".to_vec(),
            0o600,
        ));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        let outcome = install(&spec_for(&archive, &rootfs, None)).expect("install");

        assert_eq!(
            fs::read_to_string(rootfs.join("etc/resolv.conf")).unwrap(),
            RESOLV_CONF,
            "a build-host resolver must not survive the install"
        );
        assert!(outcome
            .actions
            .iter()
            .any(|action| action == "write etc/resolv.conf"));
    }

    #[test]
    fn strips_single_top_level_directory() {
        let dir = TempDir::new("prefixed");
        let archive = write_archive(
            dir.path(),
            "rootfs.tar.gz",
            &gzip(&build_tar(&prefixed_items())),
        );
        let rootfs = dir.rootfs();

        install(&spec_for(&archive, &rootfs, None)).expect("install");

        assert!(rootfs.join("bin/busybox").is_file());
        assert!(rootfs.join("etc/passwd").is_file());
        assert!(!rootfs.join("alpine").exists());
    }

    #[test]
    fn plain_tar_installs() {
        let dir = TempDir::new("plain");
        let archive = write_archive(dir.path(), "rootfs.bin", &build_tar(&standard_items("./")));
        let rootfs = dir.rootfs();

        let outcome = install(&spec_for(&archive, &rootfs, None)).expect("install");
        assert!(outcome.installed);
        assert_eq!(outcome.sha256, sha_of(&build_tar(&standard_items("./"))));
        assert!(is_installed(&rootfs.display().to_string()));
    }

    #[test]
    fn xz_archive_installs() {
        let dir = TempDir::new("xz");
        let tar = build_tar(&standard_items("./"));
        let mut compressed = Vec::new();
        lzma_rs::xz_compress(&mut BufReader::new(tar.as_slice()), &mut compressed)
            .expect("xz compress");
        let archive = write_archive(dir.path(), "rootfs.tar.xz", &compressed);
        let rootfs = dir.rootfs();

        install(&spec_for(&archive, &rootfs, Some(sha_of(&compressed)))).expect("install");
        assert!(rootfs.join("bin/sh").is_file());
        assert!(is_installed(&rootfs.display().to_string()));
        // The decompressed temp file must not survive.
        assert!(!PathBuf::from(format!("{}.tar", rootfs.display())).exists());
        let stray: Vec<String> = fs::read_dir(dir.path().join("installed-rootfs"))
            .unwrap()
            .filter_map(|entry| entry.ok())
            .map(|entry| entry.file_name().to_string_lossy().into_owned())
            .filter(|name| name.ends_with(".tar"))
            .collect();
        assert!(stray.is_empty(), "{stray:?}");
    }

    #[test]
    fn rejects_parent_traversal() {
        let dir = TempDir::new("dotdot");
        let mut items = standard_items("./");
        items.push(file("./../escape.txt", b"nope".to_vec(), 0o644));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        let error = install(&spec_for(&archive, &rootfs, None)).expect_err("must reject");
        assert!(error.contains("escapes its instance"), "{error}");
        assert!(!rootfs.exists());
        assert!(!dir.path().join("escape.txt").exists());
        assert_no_staging(dir.path());
    }

    #[test]
    fn rejects_absolute_member() {
        let dir = TempDir::new("absolute");
        let mut items = standard_items("./");
        items.push(file("/etc/evil", b"nope".to_vec(), 0o644));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        let error = install(&spec_for(&archive, &rootfs, None)).expect_err("must reject");
        assert!(error.contains("escapes its instance"), "{error}");
        assert!(!rootfs.exists());
        assert_no_staging(dir.path());
    }

    #[test]
    fn member_paths_are_normalised_and_bounded() {
        assert_eq!(safe_member_path("./bin/sh").as_deref(), Some("bin/sh"));
        assert_eq!(safe_member_path("./").as_deref(), Some(""));
        assert_eq!(safe_member_path(".").as_deref(), Some(""));
        assert_eq!(safe_member_path("a//b/./c").as_deref(), Some("a/b/c"));
        // Backslashes are separators in Kong's rule set, whatever the host.
        assert_eq!(safe_member_path("a\\b").as_deref(), Some("a/b"));
        assert_eq!(safe_member_path("/etc/passwd"), None);
        assert_eq!(safe_member_path("../escape"), None);
        assert_eq!(safe_member_path("a/../../escape"), None);
        assert_eq!(safe_member_path("a/../b"), None);
    }

    #[test]
    fn link_targets_are_resolved_relative_to_the_link_directory() {
        // The exact bug `safeLinkTarget` fixed: `bin/cat -> /bin/busybox` must be
        // written as `busybox`, not `bin/busybox`.
        assert_eq!(
            safe_link_target("bin/cat", "/bin/busybox").as_deref(),
            Some("busybox")
        );
        assert_eq!(
            safe_link_target("usr/bin/ash", "/bin/busybox").as_deref(),
            Some("../../bin/busybox")
        );
        assert_eq!(
            safe_link_target("etc/sh-link", "../bin/busybox").as_deref(),
            Some("../bin/busybox")
        );
        assert_eq!(
            safe_link_target("etc/ssl/certs/ca.pem", "/etc/ca.pem").as_deref(),
            Some("../../ca.pem")
        );
        // Empty target resolves to the link's own directory.
        assert_eq!(safe_link_target("etc/loop", "").as_deref(), Some("."));
        // Climbing above the rootfs root is rejected.
        assert_eq!(safe_link_target("etc/out", "../../etc/passwd"), None);
        assert_eq!(safe_link_target("out", "../x"), None);
        assert_eq!(safe_link_target("out", "/../x"), None);
    }

    #[test]
    fn layout_prefix_rules() {
        let flat = Layout {
            strip: false,
            top: "bin".to_string(),
        };
        assert_eq!(resolve_member("./bin/sh", &flat).unwrap(), "bin/sh");
        assert_eq!(resolve_member("./", &flat).unwrap(), "");

        let prefixed = Layout {
            strip: true,
            top: "alpine".to_string(),
        };
        assert_eq!(resolve_member("alpine/etc/passwd", &prefixed).unwrap(), "etc/passwd");
        assert_eq!(resolve_member("alpine", &prefixed).unwrap(), "");
        assert!(resolve_member("ubuntu/etc/passwd", &prefixed).is_err());
        // Hardlink targets tolerate archives that omit the prefix …
        assert_eq!(
            resolve_link_member("alpine/usr/bin/x", &prefixed).unwrap(),
            "usr/bin/x"
        );
        assert_eq!(resolve_link_member("usr/bin/x", &prefixed).unwrap(), "usr/bin/x");
        assert!(resolve_link_member("../x", &prefixed).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn symlink_target_is_rewritten_relative_to_link_directory() {
        let dir = TempDir::new("symlink");
        let mut items = standard_items("./");
        // Absolute target: 306 of these ship in Alpine.
        items.push(sym("./usr/bin/ash", "/bin/busybox"));
        // Relative target one level up.
        items.push(sym("./etc/sh-link", "../bin/busybox"));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        let outcome = install(&spec_for(&archive, &rootfs, None)).expect("install");

        assert_eq!(
            fs::read_link(rootfs.join("usr/bin/ash")).unwrap(),
            PathBuf::from("../../bin/busybox"),
            "an absolute target must become a relative link from usr/bin"
        );
        assert_eq!(
            fs::read_link(rootfs.join("etc/sh-link")).unwrap(),
            PathBuf::from("../bin/busybox")
        );
        assert!(outcome
            .actions
            .iter()
            .any(|action| action == "link usr/bin/ash -> ../../bin/busybox"));
        assert!(rootfs.join("usr/bin/ash").exists());
    }

    #[cfg(unix)]
    #[test]
    fn symlinked_bin_sh_satisfies_install_predicate() {
        let dir = TempDir::new("shsymlink");
        let mut items: Vec<Item> = standard_items("./")
            .into_iter()
            .filter(|item| !matches!(item, Item::File(name, _, _) if name == "./bin/sh"))
            .collect();
        items.push(sym("./bin/sh", "/bin/busybox"));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        install(&spec_for(&archive, &rootfs, None)).expect("install");
        assert_eq!(
            fs::read_link(rootfs.join("bin/sh")).unwrap(),
            PathBuf::from("busybox")
        );
        assert!(is_installed(&rootfs.display().to_string()));
    }

    #[cfg(unix)]
    #[test]
    fn rejects_symlink_escaping_rootfs() {
        let dir = TempDir::new("symlink-escape");
        let mut items = standard_items("./");
        items.push(sym("./etc/out", "../../etc/passwd"));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        let error = install(&spec_for(&archive, &rootfs, None)).expect_err("must reject");
        assert!(error.contains("escapes its instance"), "{error}");
        assert!(!rootfs.exists());
        assert_no_staging(dir.path());
    }

    #[test]
    fn hardlink_is_copied_from_target() {
        let dir = TempDir::new("hardlink");
        let mut items: Vec<Item> = standard_items("./")
            .into_iter()
            .filter(|item| !matches!(item, Item::File(name, _, _) if name == "./bin/busybox"))
            .collect();
        items.push(file("./usr/bin/busybox-source", shell_elf(), 0o755));
        items.push(hard("./bin/busybox", "./usr/bin/busybox-source"));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        let outcome = install(&spec_for(&archive, &rootfs, None)).expect("install");

        assert_eq!(
            fs::read(rootfs.join("bin/busybox")).unwrap(),
            shell_elf(),
            "hardlink content must equal its target"
        );
        assert!(rootfs.join("usr/bin/busybox-source").is_file());
        assert!(outcome
            .actions
            .iter()
            .any(|action| action == "hardlink bin/busybox -> usr/bin/busybox-source"));
    }

    #[test]
    fn rejects_hardlink_to_missing_file() {
        let dir = TempDir::new("hardlink-missing");
        let mut items = standard_items("./");
        items.push(hard("./bin/busybox", "./does/not/exist"));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        let error = install(&spec_for(&archive, &rootfs, None)).expect_err("must reject");
        assert!(error.contains("hardlink target"), "{error}");
        assert!(!rootfs.exists());
    }

    #[cfg(unix)]
    #[test]
    fn preserves_mode_bits() {
        use std::os::unix::fs::PermissionsExt;
        let dir = TempDir::new("mode");
        let mut items = standard_items("./");
        items.push(file("./usr/bin/secret", b"#".to_vec(), 0o4711));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        install(&spec_for(&archive, &rootfs, None)).expect("install");

        let mode = fs::metadata(rootfs.join("usr/bin/secret"))
            .unwrap()
            .permissions()
            .mode()
            & 0o777;
        assert_eq!(mode, 0o711, "setuid bits must be dropped, rwx kept");
        let busybox_mode = fs::metadata(rootfs.join("bin/busybox"))
            .unwrap()
            .permissions()
            .mode()
            & 0o777;
        assert_eq!(busybox_mode, 0o755);
    }

    #[test]
    fn sha_mismatch_publishes_nothing() {
        let dir = TempDir::new("sha");
        let archive = write_archive(
            dir.path(),
            "rootfs.tar.gz",
            &gzip(&build_tar(&standard_items("./"))),
        );
        let rootfs = dir.rootfs();

        let error = install(&spec_for(&archive, &rootfs, Some("0".repeat(64))))
            .expect_err("must reject");
        assert!(error.contains("SHA-256 mismatch"), "{error}");
        assert!(!rootfs.exists());
        assert_no_staging(dir.path());
    }

    #[test]
    fn expected_sha_is_case_insensitive() {
        let dir = TempDir::new("sha-case");
        let bytes = gzip(&build_tar(&standard_items("./")));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &bytes);
        let rootfs = dir.rootfs();

        let outcome =
            install(&spec_for(&archive, &rootfs, Some(sha_of(&bytes).to_uppercase()))).expect("install");
        assert_eq!(outcome.sha256, sha_of(&bytes));
    }

    #[test]
    fn second_install_is_idempotent() {
        let dir = TempDir::new("idempotent");
        let bytes = gzip(&build_tar(&standard_items("./")));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &bytes);
        let rootfs = dir.rootfs();
        let expected = sha_of(&bytes);

        let first = install(&spec_for(&archive, &rootfs, Some(expected.clone()))).expect("first");
        let stamp = fs::metadata(rootfs.join("etc/passwd")).unwrap().modified().unwrap();

        let second = install(&spec_for(&archive, &rootfs, Some(expected.clone()))).expect("second");

        assert!(second.installed);
        assert!(second.actions.is_empty(), "{:?}", second.actions);
        assert_eq!(second.sha256, expected);
        assert!(!first.actions.is_empty());
        assert_eq!(
            fs::metadata(rootfs.join("etc/passwd")).unwrap().modified().unwrap(),
            stamp,
            "a repeat install must not touch the tree"
        );
        assert_no_staging(dir.path());
    }

    #[test]
    fn changed_sha_replaces_previous_rootfs() {
        let dir = TempDir::new("replace");
        let first_bytes = gzip(&build_tar(&standard_items("./")));
        let first = write_archive(dir.path(), "one.tar.gz", &first_bytes);
        let rootfs = dir.rootfs();
        install(&spec_for(&first, &rootfs, Some(sha_of(&first_bytes)))).expect("first");
        fs::write(rootfs.join("stale-marker"), b"old").unwrap();

        let mut items = standard_items("./");
        items.push(file("./etc/new-version", b"2".to_vec(), 0o644));
        let second_bytes = gzip(&build_tar(&items));
        let second = write_archive(dir.path(), "two.tar.gz", &second_bytes);
        let outcome =
            install(&spec_for(&second, &rootfs, Some(sha_of(&second_bytes)))).expect("second");

        assert!(!outcome.actions.is_empty());
        assert!(rootfs.join("etc/new-version").is_file());
        assert!(!rootfs.join("stale-marker").exists());
        assert_no_staging(dir.path());
    }

    /// `dev/` members are skipped, but the directory itself must exist afterwards
    /// with an explicit 0755 (Android umask is 077; the original failure was 000).
    #[test]
    fn skipped_dev_still_yields_runtime_directories() {
        let dir = TempDir::new("dev-tree");
        let mut items = standard_items("./");
        items.push(Item::Dir("./dev".to_string(), 0o755));
        items.push(file("./dev/not-a-device", b"ignored".to_vec(), 0o666));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        let outcome = install(&spec_for(&archive, &rootfs, None)).expect("install");

        assert!(rootfs.join("dev").is_dir());
        assert!(rootfs.join("dev/pts").is_dir());
        assert!(rootfs.join("dev/shm").is_dir());
        assert!(!rootfs.join("dev/not-a-device").exists(), "dev members must be skipped");
        assert!(outcome
            .actions
            .iter()
            .any(|action| action.contains("event=install_extracted") && action.contains("dev=")),
            "{:?}",
            outcome.actions);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            for relative in ["dev", "dev/pts", "dev/shm"] {
                let mode = fs::metadata(rootfs.join(relative)).unwrap().permissions().mode() & 0o777;
                assert_eq!(0o755, mode, "{relative}");
            }
        }
    }

    /// Directory modes come from the archive, not from `create_dir_all`'s umask.
    #[cfg(unix)]
    #[test]
    fn archive_directory_mode_is_applied() {
        use std::os::unix::fs::PermissionsExt;
        let dir = TempDir::new("dir-mode");
        let mut items = standard_items("./");
        items.push(Item::Dir("./private".to_string(), 0o700));
        items.push(Item::Dir("./shared".to_string(), 0o755));
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        install(&spec_for(&archive, &rootfs, None)).expect("install");

        assert_eq!(0o700, fs::metadata(rootfs.join("private")).unwrap().permissions().mode() & 0o777);
        assert_eq!(0o755, fs::metadata(rootfs.join("shared")).unwrap().permissions().mode() & 0o777);
    }

    /// Debian/Ubuntu/Fedora archives have no `bin/busybox`; only Alpine requires it.
    #[test]
    fn non_alpine_archive_needs_no_busybox() {
        let dir = TempDir::new("debian-like");
        let items: Vec<Item> = standard_items("./")
            .into_iter()
            .filter(|item| !matches!(item, Item::File(name, _, _) if name == "./bin/busybox"))
            .collect();
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        let mut spec = spec_for(&archive, &rootfs, None);
        spec.alpine = false;
        spec.instance_id = "debian-13".to_string();
        spec.display_name = "Debian 13 (trixie)".to_string();
        install(&spec).expect("a distribution archive without busybox must install");

        let alpine_spec = spec_for(&archive, &dir.path().join("alpine-rootfs"), None);
        assert!(
            install(&alpine_spec).unwrap_err().contains("bin/busybox"),
            "the bundled Alpine image must still require busybox"
        );
    }

    #[test]
    fn rejects_unsupported_entry_type() {
        let dir = TempDir::new("fifo");
        let mut builder = tar::Builder::new(Vec::new());
        let mut header = tar::Header::new_gnu();
        header.set_size(0);
        header.set_mode(0o644);
        header.set_mtime(0);
        header.set_entry_type(tar::EntryType::Fifo);
        header.set_cksum();
        builder
            .append_data(&mut header, "./pipe.fifo", io::empty())
            .unwrap();
        let bytes = builder.into_inner().unwrap();
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&bytes));
        let rootfs = dir.rootfs();

        let error = install(&spec_for(&archive, &rootfs, None)).expect_err("must reject");
        assert!(error.contains("Unsupported rootfs archive entry type"), "{error}");
        assert!(!rootfs.exists());
    }

    #[test]
    fn rejects_non_arm64_shell() {
        let dir = TempDir::new("machine");
        let mut items: Vec<Item> = standard_items("./")
            .into_iter()
            .filter(|item| !matches!(item, Item::File(name, _, _) if name == "./bin/sh"))
            .collect();
        items.push(file("./bin/sh", elf(3, 62), 0o755)); // x86-64
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        let error = install(&spec_for(&archive, &rootfs, None)).expect_err("must reject");
        assert!(error.contains("e_machine=62"), "{error}");
        assert!(!rootfs.exists());
        assert_no_staging(dir.path());
    }

    #[test]
    fn rejects_missing_busybox() {
        let dir = TempDir::new("busybox");
        let items: Vec<Item> = standard_items("./")
            .into_iter()
            .filter(|item| !matches!(item, Item::File(name, _, _) if name == "./bin/busybox"))
            .collect();
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&items)));
        let rootfs = dir.rootfs();

        let error = install(&spec_for(&archive, &rootfs, None)).expect_err("must reject");
        assert!(error.contains("bin/busybox"), "{error}");
        assert!(!rootfs.exists());
    }

    #[test]
    fn rejects_bad_spec() {
        let dir = TempDir::new("spec");
        let archive = write_archive(dir.path(), "rootfs.tar.gz", &gzip(&build_tar(&standard_items("./"))));
        let rootfs = dir.rootfs();

        let mut spec = spec_for(&archive, &rootfs, None);
        spec.instance_id = "../evil".to_string();
        assert!(install(&spec).unwrap_err().contains("Invalid terminal instance"));

        let mut spec = spec_for(&archive, &rootfs, None);
        spec.display_name = "   ".to_string();
        assert!(install(&spec).unwrap_err().contains("must not be blank"));

        let mut spec = spec_for(&archive, &rootfs, None);
        spec.rootfs = "relative/rootfs".to_string();
        assert!(install(&spec).unwrap_err().contains("must be absolute"));

        let mut spec = spec_for(&archive, &rootfs, None);
        spec.archive_path = dir.path().join("missing.tar.gz").display().to_string();
        assert!(install(&spec).unwrap_err().contains("archive is missing"));
        assert!(!rootfs.exists());
    }

    #[test]
    fn is_installed_is_a_content_predicate() {
        let dir = TempDir::new("predicate");
        let root = dir.path().join("rootfs");
        let root_str = root.display().to_string();
        assert!(!is_installed(&root_str), "missing tree");

        fs::create_dir_all(root.join("bin")).unwrap();
        fs::create_dir_all(root.join("etc")).unwrap();
        fs::write(root.join(BUSYBOX), shell_elf()).unwrap();
        assert!(!is_installed(&root_str), "bin/sh missing");

        fs::write(root.join(SHELL), shell_elf()).unwrap();
        assert!(is_installed(&root_str));

        fs::remove_file(root.join(BUSYBOX)).unwrap();
        assert!(!is_installed(&root_str), "busybox missing");
    }

    #[cfg(unix)]
    #[test]
    fn dangling_shell_symlink_is_not_installed() {
        let dir = TempDir::new("dangling");
        let root = dir.path().join("rootfs");
        fs::create_dir_all(root.join("bin")).unwrap();
        fs::create_dir_all(root.join("etc")).unwrap();
        fs::write(root.join(BUSYBOX), shell_elf()).unwrap();
        std::os::unix::fs::symlink("busybox", root.join(SHELL)).unwrap();

        // Points at bin/busybox, which does exist → installed.
        assert!(is_installed(&root.display().to_string()));

        fs::remove_file(root.join(BUSYBOX)).unwrap();
        std::os::unix::fs::symlink("nowhere", root.join(SHELL)).unwrap();
        assert!(!is_installed(&root.display().to_string()));
    }

    #[test]
    fn staging_name_is_a_sibling() {
        let dir = TempDir::new("staging-name");
        let rootfs = dir.path().join("installed-rootfs").join("alpine");
        let staging = staged_path(&rootfs, "staging");
        assert_eq!(staging.parent(), rootfs.parent());
        assert_eq!(
            staging.file_name().unwrap().to_string_lossy(),
            format!("alpine.staging-{}", std::process::id())
        );
    }

    fn assert_no_staging(dir: &Path) {
        let mut stack = vec![dir.to_path_buf()];
        while let Some(current) = stack.pop() {
            let Ok(entries) = fs::read_dir(&current) else {
                continue;
            };
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().into_owned();
                assert!(
                    !name.contains(".staging-") && !name.contains(".retired-"),
                    "leftover {name} in {}",
                    current.display()
                );
                if entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false) {
                    stack.push(entry.path());
                }
            }
        }
    }

    /// Opt-in check against the real bundled Alpine archive.
    ///
    /// `OPERIT_REAL_ROOTFS=/c/p/modules/terminal/src/main/assets/alpine/arm64-v8a/rootfs.tar.gz.bin cargo test --offline real_rootfs`
    #[test]
    fn real_rootfs_when_requested() {
        let Ok(archive_path) = std::env::var("OPERIT_REAL_ROOTFS") else {
            return;
        };
        let dir = TempDir::new("real");
        let rootfs = dir.rootfs();
        let spec = InstallSpec {
            archive_path,
            rootfs: rootfs.display().to_string(),
            instance_id: "alpine".to_string(),
            display_name: "Alpine".to_string(),
            expected_sha256: std::env::var("OPERIT_REAL_ROOTFS_SHA").ok(),
            alpine: true,
        };

        install(&spec).expect("real rootfs install");
        assert!(rootfs.join("bin/sh").exists());
        assert!(rootfs.join("bin/busybox").is_file());
        assert!(is_installed(&rootfs.display().to_string()));
    }
}
