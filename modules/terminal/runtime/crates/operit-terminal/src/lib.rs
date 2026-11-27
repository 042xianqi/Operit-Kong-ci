//! `liboperit_terminal.so` — the Kong terminal runtime.
//!
//! The crate is split so that all *policy* (argv, envp, archive layout, profile repair,
//! prompt scripts, guest file prep) is pure and host-testable (`cargo test`), while only
//! the JNI entry points and the PTY syscalls are platform-specific. No Kotlin code may
//! reconstruct any of it.
//!
//! Module ownership (one file each, never shared):
//! * `proot.rs` + `spec.rs` — proot argv/envp (done)
//! * `pty.rs`               — openpty/fork/exec/ioctl/waitpid
//! * `install.rs`           — archive → rootfs install (gzip/xz + tar + links + markers)
//! * `guest.rs`             — guest dirs/files/gids prep
//! * `repair.rs`            — legacy Kong `/etc/profile` block removal
//! * `prompt.rs`            — ash/bash prompt emission

pub mod diagnostics;
pub mod guest;
pub mod install;
pub mod proot;
pub mod prompt;
pub mod pty;
pub mod repair;
pub mod spec;

#[cfg(target_os = "android")]
mod jni;
#[cfg(target_os = "android")]
mod jni_guest;
#[cfg(target_os = "android")]
mod jni_install;
#[cfg(target_os = "android")]
mod jni_pty;

/// Crate version reported over JNI so the Kotlin shim can log what it loaded.
pub const VERSION: &str = concat!(env!("CARGO_PKG_VERSION"), "+android26-arm64");
