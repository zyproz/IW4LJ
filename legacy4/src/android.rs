// SPDX-License-Identifier: GPL-3.0-only
//! Android start for Legacy 4. Added to the cod4-decomp client by the Legacy 4 build (`legacy4/patch_client.py`);
//! it is not part of cod4-decomp.
//!
//! Android loads `libcod4e_android.so` and calls `android_main`. This finds the game folder, sends everything the
//! client prints to `journal.txt`, then runs the same `main` the desktop client runs.
//!
//! Folder: `Android/media/dev.zyproz.legacy4/` (a file manager can write there):
//! * `cod4/`        the Call of Duty 4 1.7 install (`main/`, `zone/`, `localization.txt`)
//! * `journal.txt`  everything the last run printed (rewritten at each start)
//! * `crash.txt`    why the last run stopped, read by the launch screen
//! * `args.txt`     optional extra command-line options, e.g. `--no-sound` or `--listen --bots 1`

#![allow(clippy::print_stderr)]

use std::cell::Cell;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::sync::OnceLock;

use winit::event::{ElementState, MouseButton, Touch, TouchPhase, WindowEvent};
use winit::platform::android::activity::AndroidApp;

const PACKAGE: &str = "dev.zyproz.legacy4";

static APP: OnceLock<AndroidApp> = OnceLock::new();
static ARGS: OnceLock<Vec<String>> = OnceLock::new();
static MEDIA: OnceLock<PathBuf> = OnceLock::new();

/// The activity handed to `android_main`; the event loop needs it.
pub fn app() -> AndroidApp {
    APP.get().expect("android_main stores the app before main runs").clone()
}

/// The command line Android does not have: `--install <folder>` plus `args.txt`.
pub fn args() -> Option<Vec<String>> {
    ARGS.get().cloned()
}

fn media_dir() -> PathBuf {
    for p in [
        format!("/storage/emulated/0/Android/media/{PACKAGE}"),
        format!("/sdcard/Android/media/{PACKAGE}"),
    ] {
        if Path::new(&p).is_dir() {
            return PathBuf::from(p);
        }
    }
    PathBuf::from(format!("/storage/emulated/0/Android/media/{PACKAGE}"))
}

fn append(path: &Path, text: &str) {
    if let Ok(mut f) = fs::OpenOptions::new().create(true).append(true).open(path) {
        let _ = f.write_all(text.as_bytes());
    }
}

/// Points stdout and stderr (where `tracing` and every error message go) at a file: Android shows neither.
fn redirect_output(path: &Path) {
    use std::os::fd::IntoRawFd;
    if let Ok(f) = fs::File::create(path) {
        let fd = f.into_raw_fd();
        // SAFETY: plain descriptor calls on a descriptor this function owns; no other thread exists yet.
        unsafe {
            libc::dup2(fd, 1);
            libc::dup2(fd, 2);
            libc::close(fd);
        }
    }
}

fn journal_tail(media: &Path, lines: usize) -> String {
    let text = fs::read_to_string(media.join("journal.txt")).unwrap_or_default();
    let all: Vec<&str> = text.lines().collect();
    all[all.len().saturating_sub(lines)..].join("\n")
}

/// The window is gone (home button, another app): 0.1 cannot rebuild the graphics yet, so it stops cleanly.
pub fn leave() -> ! {
    if let Some(media) = MEDIA.get() {
        let _ = fs::remove_file(media.join("running.txt"));
    }
    std::process::exit(0);
}

thread_local! {
    static FINGER: Cell<Option<u64>> = const { Cell::new(None) };
}

/// One finger acts as the mouse: it moves the pointer and its touch is a left click. Other fingers are ignored.
pub fn touch_as_mouse(t: &Touch) -> Vec<WindowEvent> {
    if FINGER.get().is_some_and(|id| id != t.id) {
        return Vec::new();
    }
    let moved = WindowEvent::CursorMoved { device_id: t.device_id, position: t.location };
    let click = |state| WindowEvent::MouseInput { device_id: t.device_id, state, button: MouseButton::Left };
    match t.phase {
        TouchPhase::Started => {
            FINGER.set(Some(t.id));
            vec![moved, click(ElementState::Pressed)]
        }
        TouchPhase::Moved => vec![moved],
        TouchPhase::Ended | TouchPhase::Cancelled => {
            FINGER.set(None);
            vec![moved, click(ElementState::Released)]
        }
    }
}

#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    let media = media_dir();
    let _ = fs::create_dir_all(media.join("cod4"));
    let _ = MEDIA.set(media.clone());
    redirect_output(&media.join("journal.txt"));
    let _ = fs::write(media.join("running.txt"), "starting");

    let crash = media.join("crash.txt");
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let report = format!(
            "{info}\nthread: {}\n{}\n",
            std::thread::current().name().unwrap_or("?"),
            std::backtrace::Backtrace::force_capture()
        );
        append(&crash, &report);
        default_hook(info);
    }));

    let files = app
        .internal_data_path()
        .unwrap_or_else(|| PathBuf::from(format!("/data/data/{PACKAGE}/files")));
    let config = files.join("config");
    let _ = fs::create_dir_all(&config);
    // SAFETY: no other thread exists yet. The client keeps its profiles in `$XDG_CONFIG_HOME/cod4e`.
    unsafe {
        std::env::set_var("HOME", &files);
        std::env::set_var("XDG_CONFIG_HOME", &config);
        std::env::set_var("RUST_BACKTRACE", "1");
    }

    let mut args = vec!["--install".to_owned(), media.join("cod4").display().to_string()];
    if let Ok(text) = fs::read_to_string(media.join("args.txt")) {
        for line in text.lines().map(str::trim) {
            if !line.is_empty() && !line.starts_with('#') {
                args.extend(line.split_whitespace().map(str::to_owned));
            }
        }
    }
    eprintln!("Legacy 4 0.2: starting with {args:?}");
    let _ = APP.set(app);
    let _ = ARGS.set(args);

    let code = crate::main();
    // Compared through their text: it works whatever comparisons the toolchain gives `ExitCode`.
    if format!("{code:?}") != format!("{:?}", ExitCode::SUCCESS) {
        let tail = journal_tail(&media, 40);
        append(
            &media.join("crash.txt"),
            &format!("The game stopped with an error. Last lines of journal.txt:\n{tail}\n"),
        );
    }
    let _ = fs::remove_file(media.join("running.txt"));
}
