//! PTY allocation and child process lifecycle. Replaces `src/main/jni/pty.c` and `libpty.so`.
//!
//! Contract (must mirror `pty.c`, which is the behaviour in production today):
//! * `spawn_pty` → `forkpty` with the same termios flags and 60x40 initial window,
//!   child `chdir(cwd)`, `execve(argv[0], argv, envp)`; on failure `_exit(1)`.
//! * `wait_for` → blocking `waitpid(pid, &status, 0)`, retried on `EINTR`, encoded as
//!   `WIFEXITED ? WEXITSTATUS : -1` (see pty.c:129-150).
//! * `terminal_flags` → bit0 ICANON, bit1 ECHO, bit2 ISIG, bit3 IEXTEN; -1 on error.
//! * `available_bytes` → `ioctl(fd, FIONREAD)`; -1 on error.
//! * `set_window_size` → `ioctl(fd, TIOCSWINSZ)`, 0 / -1.
//!
//! Everything that can be decided without a syscall (argument conversion, the control
//! characters, the default window size, the flag bit values) lives in plain host-testable
//! code; only the syscalls themselves are behind `cfg(unix)`.

/// Initial window from `pty.c` (`ws_row = 60`, `ws_col = 40`).
pub const DEFAULT_ROWS: u16 = 60;
/// Initial window from `pty.c` (`ws_col = 40`).
pub const DEFAULT_COLS: u16 = 40;

/// `getTerminalFlags` bit 0 — canonical (line buffered) input.
pub const FLAG_ICANON: i32 = 0x01;
/// `getTerminalFlags` bit 1 — input echo.
pub const FLAG_ECHO: i32 = 0x02;
/// `getTerminalFlags` bit 2 — signal-generating characters (`^C`, `^Z`, …).
pub const FLAG_ISIG: i32 = 0x04;
/// `getTerminalFlags` bit 3 — extended input processing.
pub const FLAG_IEXTEN: i32 = 0x08;

/// The control characters `pty.c` installs, kept portable so they can be asserted in
/// `cargo test` on any host. Names follow termios (`VINTR` … `VTIME`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PtyControlChars {
    /// `^C` — `VINTR`.
    pub interrupt: u8,
    /// `^\` — `VQUIT`.
    pub quit: u8,
    /// `0x7f` (DEL) — `VERASE`.
    pub erase: u8,
    /// `^U` — `VKILL`.
    pub kill: u8,
    /// `^D` — `VEOF`.
    pub eof: u8,
    /// `^S` — `VSTOP`.
    pub stop: u8,
    /// `^Z` — `VSUSP`.
    pub suspend: u8,
    /// `^Q` — `VSTART`.
    pub start: u8,
    /// `VMIN`.
    pub min: u8,
    /// `VTIME`.
    pub time: u8,
}

impl Default for PtyControlChars {
    fn default() -> Self {
        // Same table as pty.c: `'C' - '@'` == 0x03 and so on.
        Self {
            interrupt: b'C' - b'@',
            quit: b'\\' - b'@',
            erase: 0x7f,
            kill: b'U' - b'@',
            eof: b'D' - b'@',
            stop: b'S' - b'@',
            suspend: b'Z' - b'@',
            start: b'Q' - b'@',
            min: 1,
            time: 0,
        }
    }
}

/// One PTY spawn request. Values only; no shell string.
#[derive(Debug, Clone)]
pub struct SpawnRequest {
    /// `argv`; `argv[0]` is the program path handed to `execve`. Never empty.
    pub argv: Vec<String>,
    /// Raw `envp` entries (`KEY=VALUE`) in the exact order given. Passed to `execve`
    /// verbatim: no reordering, no de-duplication, no joining or splitting.
    pub environment: Vec<String>,
    /// Directory the child `chdir`s into before `execve` (pty.c `chdir(cwd)`).
    pub working_directory: String,
    pub rows: u16,
    pub cols: u16,
    pub control_chars: PtyControlChars,
}

impl Default for SpawnRequest {
    fn default() -> Self {
        Self {
            argv: Vec::new(),
            environment: Vec::new(),
            working_directory: "/".to_string(),
            rows: DEFAULT_ROWS,
            cols: DEFAULT_COLS,
            control_chars: PtyControlChars::default(),
        }
    }
}

/// Child pid + master side of the PTY.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Spawned {
    pub pid: i32,
    pub master_fd: i32,
}

/// Convert one string into a `CString`, naming the offending slot on an interior NUL.
pub fn to_cstring(value: &str, what: &str, index: usize) -> Result<std::ffi::CString, String> {
    std::ffi::CString::new(value).map_err(|_| {
        format!("{what}[{index}] contains a NUL byte and cannot be passed to execve: {value:?}")
    })
}

/// Convert `argv`/`envp` strings for `execve`, rejecting interior NULs up front.
pub fn to_cstring_list(values: &[String], what: &str) -> Result<Vec<std::ffi::CString>, String> {
    values
        .iter()
        .enumerate()
        .map(|(index, value)| to_cstring(value, what, index))
        .collect()
}

/// `forkpty` + child `chdir`/`execve`. Mirrors `Pty.kt`'s expectations: a failure is an
/// `Err`, never a bogus `pid`/`fd` pair that looks like success.
#[cfg(unix)]
pub fn spawn_pty(request: &SpawnRequest) -> Result<Spawned, String> {
    use std::os::raw::{c_char, c_int};

    if request.argv.is_empty() {
        return Err("argv is empty: execve requires a program at argv[0]".to_string());
    }
    let argv = to_cstring_list(&request.argv, "argv")?;
    let envp = to_cstring_list(&request.environment, "environment")?;
    let cwd = to_cstring(&request.working_directory, "working_directory", 0)?;

    // Build the raw pointer tables *before* forking; the child must not allocate.
    let mut argv_ptrs: Vec<*const c_char> = argv.iter().map(|value| value.as_ptr()).collect();
    argv_ptrs.push(std::ptr::null());
    let mut envp_ptrs: Vec<*const c_char> = envp.iter().map(|value| value.as_ptr()).collect();
    envp_ptrs.push(std::ptr::null());

    // Same messages pty.c writes to the (pty-backed) stderr, prepared before the fork
    // so the child never touches the allocator.
    let mut chdir_message = Vec::with_capacity(64);
    chdir_message.extend_from_slice(b"chdir to ");
    chdir_message.extend_from_slice(request.working_directory.as_bytes());
    chdir_message.extend_from_slice(b" failed");
    let mut exec_message = Vec::with_capacity(64);
    exec_message.extend_from_slice(b"execve(");
    exec_message.extend_from_slice(request.argv[0].as_bytes());
    exec_message.extend_from_slice(b") failed");

    let termios = build_termios(&request.control_chars);
    let window = libc::winsize {
        ws_row: request.rows,
        ws_col: request.cols,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };

    let mut master_fd: c_int = -1;
    let pid = unsafe { libc::forkpty(&mut master_fd, std::ptr::null_mut(), &termios, &window) };
    if pid < 0 {
        return Err(format!(
            "forkpty failed: {}",
            std::io::Error::last_os_error()
        ));
    }

    if pid == 0 {
        // Child. Only async-signal-safe calls until execve replaces the image.
        unsafe {
            if libc::chdir(cwd.as_ptr()) != 0 {
                write_child_failure(&chdir_message);
                libc::_exit(1);
            }
            libc::execve(argv_ptrs[0], argv_ptrs.as_ptr(), envp_ptrs.as_ptr());
            write_child_failure(&exec_message);
            libc::_exit(1);
        }
    }

    Ok(Spawned {
        pid: pid as i32,
        master_fd,
    })
}

/// Everything `execve` needs, checked without touching the OS: a program, and no interior
/// NUL bytes in `argv`, `envp` or the working directory.
pub fn validate(request: &SpawnRequest) -> Result<(), String> {
    if request.argv.is_empty() {
        return Err("argv is empty: execve requires a program at argv[0]".to_string());
    }
    to_cstring_list(&request.argv, "argv")?;
    to_cstring_list(&request.environment, "environment")?;
    to_cstring(&request.working_directory, "working_directory", 0)?;
    Ok(())
}

/// Non-Unix hosts can compile the crate but cannot allocate a PTY. Arguments are still
/// validated first so callers get the same diagnostics everywhere.
#[cfg(not(unix))]
pub fn spawn_pty(request: &SpawnRequest) -> Result<Spawned, String> {
    validate(request)?;
    Err("pty is only available on Unix targets".to_string())
}

/// Termios `pty.c` builds: the same input/output/control/local flags and control chars.
#[cfg(unix)]
fn build_termios(control: &PtyControlChars) -> libc::termios {
    // `termios` is plain old data; zero it and fill every field pty.c sets.
    let mut settings: libc::termios = unsafe { std::mem::zeroed() };
    settings.c_iflag = libc::ICRNL | libc::IXON | libc::IXANY;
    settings.c_oflag = libc::OPOST | libc::ONLCR;
    settings.c_lflag = libc::ISIG | libc::ICANON | libc::ECHO | libc::ECHOE | libc::ECHOK
        | libc::ECHONL
        | libc::IEXTEN;
    settings.c_cflag = libc::CS8 | libc::CREAD;
    settings.c_cc[libc::VINTR] = control.interrupt as libc::cc_t;
    settings.c_cc[libc::VQUIT] = control.quit as libc::cc_t;
    settings.c_cc[libc::VERASE] = control.erase as libc::cc_t;
    settings.c_cc[libc::VKILL] = control.kill as libc::cc_t;
    settings.c_cc[libc::VEOF] = control.eof as libc::cc_t;
    settings.c_cc[libc::VSTOP] = control.stop as libc::cc_t;
    settings.c_cc[libc::VSUSP] = control.suspend as libc::cc_t;
    settings.c_cc[libc::VSTART] = control.start as libc::cc_t;
    settings.c_cc[libc::VMIN] = control.min as libc::cc_t;
    settings.c_cc[libc::VTIME] = control.time as libc::cc_t;
    settings
}

/// Write `<message> (errno <n>)\n` to fd 2 without allocating. Used only in the forked
/// child, where malloc may be in an inconsistent state.
#[cfg(unix)]
fn write_child_failure(message: &[u8]) {
    // `last_os_error()` only stores the code (no allocation, no locks).
    let errno = std::io::Error::last_os_error()
        .raw_os_error()
        .unwrap_or_default();
    let mut buffer = [0u8; 128];
    let mut used = 0usize;
    for byte in message {
        if used == buffer.len() {
            break;
        }
        buffer[used] = *byte;
        used += 1;
    }
    // " (errno N)\n" — digits are produced backwards then reversed in place.
    let mut tail = [0u8; 24];
    let mut tail_len = 0usize;
    let mut value = errno.unsigned_abs();
    loop {
        tail[tail_len] = b'0' + (value % 10) as u8;
        tail_len += 1;
        value /= 10;
        if value == 0 || tail_len == 20 {
            break;
        }
    }
    let mut suffix = [0u8; 32];
    let mut suffix_len = 0usize;
    for byte in b" (errno " {
        suffix[suffix_len] = *byte;
        suffix_len += 1;
    }
    if errno < 0 {
        suffix[suffix_len] = b'-';
        suffix_len += 1;
    }
    while tail_len > 0 {
        tail_len -= 1;
        suffix[suffix_len] = tail[tail_len];
        suffix_len += 1;
    }
    suffix[suffix_len] = b')';
    suffix_len += 1;
    suffix[suffix_len] = b'\n';
    suffix_len += 1;

    unsafe {
        libc::write(2, buffer.as_ptr() as *const libc::c_void, used);
        libc::write(2, suffix.as_ptr() as *const libc::c_void, suffix_len);
    }
}

/// `waitpid` retried on `EINTR`; `WIFEXITED ? WEXITSTATUS : -1`, `-1` if the wait failed.
#[cfg(unix)]
pub fn wait_for(pid: i32) -> i32 {
    use std::os::raw::c_int;

    let mut status: c_int = 0;
    let result = loop {
        let result = unsafe { libc::waitpid(pid, &mut status, 0) };
        if result == -1 && std::io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
            continue;
        }
        break result;
    };
    if result != pid {
        return -1;
    }
    if libc::WIFEXITED(status) {
        libc::WEXITSTATUS(status)
    } else {
        -1
    }
}

#[cfg(not(unix))]
pub fn wait_for(_pid: i32) -> i32 {
    -1
}

/// `tcgetattr` reduced to the four bits `Pty.kt` inspects; `-1` when the fd is not a tty.
#[cfg(unix)]
pub fn terminal_flags(fd: i32) -> i32 {
    let mut settings: libc::termios = unsafe { std::mem::zeroed() };
    if unsafe { libc::tcgetattr(fd, &mut settings) } != 0 {
        return -1;
    }
    let local = settings.c_lflag;
    let mut flags: i32 = 0;
    if local & libc::ICANON != 0 {
        flags |= FLAG_ICANON;
    }
    if local & libc::ECHO != 0 {
        flags |= FLAG_ECHO;
    }
    if local & libc::ISIG != 0 {
        flags |= FLAG_ISIG;
    }
    if local & libc::IEXTEN != 0 {
        flags |= FLAG_IEXTEN;
    }
    flags
}

#[cfg(not(unix))]
pub fn terminal_flags(_fd: i32) -> i32 {
    -1
}

/// Bytes waiting in the PTY's input queue (`FIONREAD`); `-1` on error.
#[cfg(unix)]
pub fn available_bytes(fd: i32) -> i32 {
    let mut available: std::os::raw::c_int = 0;
    if unsafe { libc::ioctl(fd, libc::FIONREAD, &mut available) } != 0 {
        return -1;
    }
    available
}

#[cfg(not(unix))]
pub fn available_bytes(_fd: i32) -> i32 {
    -1
}

/// `TIOCSWINSZ`; `0` on success, `-1` otherwise.
#[cfg(unix)]
pub fn set_window_size(fd: i32, rows: u16, cols: u16) -> i32 {
    let window = libc::winsize {
        ws_row: rows,
        ws_col: cols,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    if unsafe { libc::ioctl(fd, libc::TIOCSWINSZ, &window) } != 0 {
        return -1;
    }
    0
}

#[cfg(not(unix))]
pub fn set_window_size(_fd: i32, _rows: u16, _cols: u16) -> i32 {
    -1
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_request_matches_pty_c_window() {
        let request = SpawnRequest::default();
        assert_eq!(request.rows, 60);
        assert_eq!(request.cols, 40);
        assert_eq!(request.working_directory, "/");
        assert!(request.argv.is_empty());
        assert!(request.environment.is_empty());
    }

    #[test]
    fn control_chars_match_pty_c() {
        let control = PtyControlChars::default();
        assert_eq!(control.interrupt, 0x03, "VINTR = 'C' - '@'");
        assert_eq!(control.quit, 0x1c, "VQUIT = '\\\\' - '@'");
        assert_eq!(control.erase, 0x7f, "VERASE = DEL");
        assert_eq!(control.kill, 0x15, "VKILL = 'U' - '@'");
        assert_eq!(control.eof, 0x04, "VEOF = 'D' - '@'");
        assert_eq!(control.stop, 0x13, "VSTOP = 'S' - '@'");
        assert_eq!(control.suspend, 0x1a, "VSUSP = 'Z' - '@'");
        assert_eq!(control.start, 0x11, "VSTART = 'Q' - '@'");
        assert_eq!(control.min, 1, "VMIN");
        assert_eq!(control.time, 0, "VTIME");
    }

    #[test]
    fn terminal_flag_bits_match_kotlin() {
        assert_eq!(FLAG_ICANON, 1);
        assert_eq!(FLAG_ECHO, 2);
        assert_eq!(FLAG_ISIG, 4);
        assert_eq!(FLAG_IEXTEN, 8);
    }

    #[test]
    fn env_entries_are_kept_verbatim_in_order() {
        let request = SpawnRequest {
            argv: vec!["/bin/sh".to_string(), "-i".to_string()],
            environment: vec![
                "PATH=/usr/bin".to_string(),
                "PATH=/bin".to_string(), // duplicate key: must not be de-duplicated
                "EMPTY=".to_string(),
                "ODD=a=b".to_string(),
                "BARE".to_string(), // no '=': still an envp entry
            ],
            ..SpawnRequest::default()
        };
        let envp = to_cstring_list(&request.environment, "environment").unwrap();
        let round_trip: Vec<String> = envp
            .iter()
            .map(|value| value.to_str().unwrap().to_string())
            .collect();
        assert_eq!(round_trip, request.environment);
    }

    #[test]
    fn argv_preserves_every_element() {
        let argv = vec![
            "liboperit_proot.so".to_string(),
            "-0".to_string(),
            "-w".to_string(),
            "/root".to_string(),
            "/bin/sh".to_string(),
            "-i".to_string(),
        ];
        let converted = to_cstring_list(&argv, "argv").unwrap();
        assert_eq!(converted.len(), argv.len());
        assert_eq!(converted[3].to_str().unwrap(), "/root");
    }

    #[test]
    fn interior_nul_is_rejected_with_the_slot() {
        let argv = vec!["ok".to_string(), "bad\0arg".to_string()];
        let error = to_cstring_list(&argv, "argv").unwrap_err();
        assert!(error.contains("argv[1]"), "unexpected error: {error}");
        assert!(error.contains("NUL"), "unexpected error: {error}");

        let error = to_cstring("cwd\0", "working_directory", 0).unwrap_err();
        assert!(error.contains("working_directory[0]"), "unexpected error: {error}");
    }

    #[test]
    fn empty_argv_is_rejected() {
        let request = SpawnRequest::default();
        let error = spawn_pty(&request).unwrap_err();
        assert!(error.contains("argv is empty"), "unexpected error: {error}");
    }

    #[cfg(unix)]
    #[test]
    fn termios_carries_pty_c_flags() {
        let settings = build_termios(&PtyControlChars::default());
        assert_eq!(settings.c_iflag, libc::ICRNL | libc::IXON | libc::IXANY);
        assert_eq!(settings.c_oflag, libc::OPOST | libc::ONLCR);
        assert_eq!(
            settings.c_lflag,
            libc::ISIG
                | libc::ICANON
                | libc::ECHO
                | libc::ECHOE
                | libc::ECHOK
                | libc::ECHONL
                | libc::IEXTEN
        );
        assert_eq!(settings.c_cflag, libc::CS8 | libc::CREAD);
        assert_eq!(settings.c_cc[libc::VINTR], 0x03);
        assert_eq!(settings.c_cc[libc::VERASE], 0x7f);
        assert_eq!(settings.c_cc[libc::VMIN], 1);
        assert_eq!(settings.c_cc[libc::VTIME], 0);
    }
}
