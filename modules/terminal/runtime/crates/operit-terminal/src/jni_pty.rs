//! JNI exports for `com.ai.assistance.operit.terminal.Pty`. Android-only.
//!
//! Symbol names are pinned by the Kotlin class as it exists today (`Pty.kt` is not being
//! rewritten), so the four `companion object` methods keep the `_00024Companion` suffix and
//! `setPtyWindowSize` is an instance method (`thiz` is the `Pty` object, not a `JClass`).
//!
//! Every export is wrapped in `catch_unwind`: a panic must never unwind into the JVM, and
//! every failure (`forkpty` error, bad argument, interior NUL) makes `createSubprocess`
//! return `null` rather than a `{pid, fd}` pair that Kotlin would treat as success.

use crate::pty::{self, SpawnRequest, Spawned};
use jni::objects::{JObject, JObjectArray, JString};
use jni::sys::{jint, jintArray};
use jni::JNIEnv;

/// Read a Kotlin `Array<String>` into `Vec<String>`, preserving order.
fn read_string_array(
    env: &mut JNIEnv,
    array: &JObjectArray,
    what: &str,
) -> Result<Vec<String>, String> {
    if array.is_null() {
        return Err(format!("{what} is null"));
    }
    let length = env
        .get_array_length(array)
        .map_err(|error| format!("cannot read {what} length: {error}"))?;
    let mut values = Vec::with_capacity(length.max(0) as usize);
    for index in 0..length {
        let element = env
            .get_object_array_element(array, index)
            .map_err(|error| format!("cannot read {what}[{index}]: {error}"))?;
        if element.is_null() {
            return Err(format!("{what}[{index}] is null"));
        }
        let element = JString::from(element);
        let value: String = env
            .get_string(&element)
            .map_err(|error| format!("cannot decode {what}[{index}]: {error}"))?
            .into();
        values.push(value);
    }
    Ok(values)
}

/// Fill the `{pid, masterFd}` array the Kotlin side indexes directly.
fn int_pair(env: &JNIEnv, first: jint, second: jint) -> Result<jintArray, String> {
    let array = env
        .new_int_array(2)
        .map_err(|error| format!("cannot allocate result array: {error}"))?;
    env.set_int_array_region(&array, 0, &[first, second])
        .map_err(|error| format!("cannot fill result array: {error}"))?;
    Ok(array.into_raw())
}

/// A child that never reaches Kotlin would leak a whole guest shell, so unwind it here.
fn discard(spawned: Spawned) {
    unsafe {
        libc::kill(spawned.pid, libc::SIGKILL);
        libc::close(spawned.master_fd);
        libc::waitpid(spawned.pid, std::ptr::null_mut(), 0);
    }
}

/// `Pty.Companion.createSubprocess(cmdArray, envArray, workingDir): IntArray?`
/// → `{pid, masterFd}`, or `null` when the PTY could not be created.
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_terminal_Pty_00024Companion_createSubprocess<'local>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    command: JObjectArray<'local>,
    environment: JObjectArray<'local>,
    working_directory: JString<'local>,
) -> jintArray {
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        let argv = match read_string_array(&mut env, &command, "command") {
            Ok(values) => values,
            Err(_) => return std::ptr::null_mut(),
        };
        // Raw `KEY=VALUE` strings, in the caller's order, passed to execve untouched.
        let envp = match read_string_array(&mut env, &environment, "environment") {
            Ok(values) => values,
            Err(_) => return std::ptr::null_mut(),
        };
        let working_directory: String = match env.get_string(&working_directory) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let request = SpawnRequest {
            argv,
            environment: envp,
            working_directory,
            ..SpawnRequest::default()
        };
        let spawned = match pty::spawn_pty(&request) {
            Ok(spawned) => spawned,
            Err(_) => return std::ptr::null_mut(),
        };
        match int_pair(&env, spawned.pid, spawned.master_fd) {
            Ok(array) => array,
            Err(_) => {
                discard(spawned);
                std::ptr::null_mut()
            }
        }
    }));
    match outcome {
        Ok(handle) => handle,
        Err(_) => std::ptr::null_mut(),
    }
}

/// `Pty.Companion.waitFor(pid): Int` — exit status, or `-1` if it did not exit normally.
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_terminal_Pty_00024Companion_waitFor<'local>(
    _env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    pid: jint,
) -> jint {
    match std::panic::catch_unwind(move || pty::wait_for(pid)) {
        Ok(status) => status,
        Err(_) => -1,
    }
}

/// `Pty.Companion.getTerminalFlags(fd): Int` — bits 1/2/4/8, `-1` when `fd` is not a tty.
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_terminal_Pty_00024Companion_getTerminalFlags<'local>(
    _env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    fd: jint,
) -> jint {
    match std::panic::catch_unwind(move || pty::terminal_flags(fd)) {
        Ok(flags) => flags,
        Err(_) => -1,
    }
}

/// `Pty.Companion.getAvailableBytes(fd): Int` — `FIONREAD`, `-1` on error.
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_terminal_Pty_00024Companion_getAvailableBytes<'local>(
    _env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    fd: jint,
) -> jint {
    match std::panic::catch_unwind(move || pty::available_bytes(fd)) {
        Ok(available) => available,
        Err(_) => -1,
    }
}

/// `Pty.setPtyWindowSize(fd, rows, cols): Int` — instance method, `TIOCSWINSZ`, `0` / `-1`.
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_terminal_Pty_setPtyWindowSize<'local>(
    _env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    fd: jint,
    rows: jint,
    cols: jint,
) -> jint {
    match std::panic::catch_unwind(move || {
        pty::set_window_size(fd, rows as u16, cols as u16)
    }) {
        Ok(result) => result,
        Err(_) => -1,
    }
}
