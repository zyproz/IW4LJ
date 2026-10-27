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
    eprintln!("Legacy 4 0.4: starting with {args:?}");
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

// ---------------------------------------------------------------------------------------------------------------
// Controller and touch, sent by GameActivity.java over JNI (Android gives a NativeActivity's controller to nobody:
// winit turns its buttons into unknown keys and drops its sticks, so the activity takes the input itself).
// In a match the pad drives the client's own gamepad binds (`pad_a`, `pad_rt`...: input/extra_binds.cfg).
// In the menus it becomes menu keys and a cursor: D-pad = arrows, A = select, B/Start = back, left stick = cursor.

use crate::ui::UiKey;
use std::sync::Mutex;
use std::time::Instant;

#[derive(Default)]
struct PadShared {
    left: (f32, f32),
    right: (f32, f32),
    lt: bool,
    rt: bool,
    hat: (i32, i32),
    /// Button edges for the match (the client's bind names).
    game: Vec<(&'static str, bool)>,
    /// Button edges for the menus.
    menu: Vec<(&'static str, bool)>,
    /// Held in the match, released when a menu opens or the pad goes away.
    held: Vec<&'static str>,
    /// Touches for the menus: (phase 0 down / 1 move / 2 up / 3 cancel, x, y in 0..1).
    touch: Vec<(i32, f32, f32)>,
    finger: Option<i32>,
    menu_open: bool,
    /// The menu cursor, in pixels; `None` until the window size is known.
    cursor: Option<(f32, f32)>,
    /// The last menu move came from the stick (A clicks) rather than the D-pad (A selects).
    stick_last: bool,
    last_poll: Option<Instant>,
    /// The touch screen's move stick, -1..1 with +Y forward.
    touch_stick: (f32, f32),
    /// Look drag from the touch screen, in pixels, not yet given to the game.
    look: (f32, f32),
    /// Degrees of turn per pixel dragged.
    look_speed: f32,
    /// Console lines from the mod menu, and the one being received.
    commands: Vec<String>,
    typing: String,
}

static PAD: Mutex<Option<PadShared>> = Mutex::new(None);

fn with_pad<R>(f: impl FnOnce(&mut PadShared) -> R) -> R {
    let mut guard = PAD.lock().unwrap_or_else(|e| e.into_inner());
    f(guard.get_or_insert_with(PadShared::default))
}

fn edge(p: &mut PadShared, name: &'static str, down: bool) {
    if p.menu_open {
        p.menu.push((name, down));
    } else {
        if down {
            p.held.push(name);
        } else {
            p.held.retain(|n| *n != name);
        }
        p.game.push((name, down));
    }
}

/// Android key codes (KeyEvent.KEYCODE_*) to the client's pad names.
fn pad_key(code: i32) -> Option<&'static str> {
    Some(match code {
        96 => "pad_a",
        97 => "pad_b",
        99 => "pad_x",
        100 => "pad_y",
        102 => "pad_lb",
        103 => "pad_rb",
        106 => "pad_ls",
        107 => "pad_rs",
        108 => "pad_start",
        109 => "pad_back",
        19 => "pad_dpad_up",
        20 => "pad_dpad_down",
        21 => "pad_dpad_left",
        22 => "pad_dpad_right",
        // The touch screen's FIRE and AIM: the triggers' binds.
        104 => "pad_lt",
        105 => "pad_rt",
        _ => return None,
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_zyproz_legacy4_GameActivity_nativePadButton(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    _device: i32,
    keycode: i32,
    down: u8,
) {
    if let Some(name) = pad_key(keycode) {
        with_pad(|p| edge(p, name, down != 0));
    }
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_zyproz_legacy4_GameActivity_nativePadAxes(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    _device: i32,
    lx: f32,
    ly: f32,
    rx: f32,
    ry: f32,
    lt: f32,
    rt: f32,
    hat_x: f32,
    hat_y: f32,
) {
    with_pad(|p| {
        // Android's sticks point down for +Y; the client (gilrs) expects up.
        p.left = (lx, -ly);
        p.right = (rx, -ry);
        // Triggers become buttons, with a little hysteresis so a half pull does not flicker.
        let mut edges = Vec::new();
        for (value, state, name) in [(lt, &mut p.lt, "pad_lt"), (rt, &mut p.rt, "pad_rt")] {
            let now = if *state { value > 0.25 } else { value > 0.5 };
            if now != *state {
                *state = now;
                edges.push((name, now));
            }
        }
        for (name, down) in edges {
            edge(p, name, down);
        }
        // Some pads give the D-pad as an axis pair instead of keys.
        let hat = (hat_x.round() as i32, hat_y.round() as i32);
        if hat != p.hat {
            let old = p.hat;
            p.hat = hat;
            for (was, is, minus, plus) in [
                (old.0, hat.0, "pad_dpad_left", "pad_dpad_right"),
                (old.1, hat.1, "pad_dpad_up", "pad_dpad_down"),
            ] {
                if was != is {
                    if was != 0 {
                        edge(p, if was < 0 { minus } else { plus }, false);
                    }
                    if is != 0 {
                        edge(p, if is < 0 { minus } else { plus }, true);
                    }
                }
            }
        }
    });
}


#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_zyproz_legacy4_GameActivity_nativePadRemoved(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    _device: i32,
) {
    with_pad(|p| {
        p.left = (0.0, 0.0);
        p.right = (0.0, 0.0);
        for name in std::mem::take(&mut p.held) {
            p.game.push((name, false));
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_zyproz_legacy4_GameActivity_nativeTouch(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    id: i32,
    phase: i32,
    x: f32,
    y: f32,
) {
    with_pad(|p| {
        if p.finger.is_some_and(|f| f != id) {
            return;
        }
        match phase {
            0 => p.finger = Some(id),
            2 | 3 => p.finger = None,
            _ => {}
        }
        p.touch.push((phase, x, y));
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_zyproz_legacy4_GameActivity_nativeBack(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
) {
    // The phone's Back: Escape in a menu, the pause menu in a match.
    with_pad(|p| {
        edge(p, "pad_start", true);
        edge(p, "pad_start", false);
    });
}

/// For the client's `Pad::poll`: stick positions and the match's button edges (nothing while a menu is open).
pub fn pad_poll() -> ((f32, f32), (f32, f32), Vec<(&'static str, bool)>) {
    with_pad(|p| {
        let edges = std::mem::take(&mut p.game);
        if p.menu_open {
            ((0.0, 0.0), (0.0, 0.0), edges)
        } else {
            // The touch stick and a controller's stick: whichever is pushed further.
            let left = if p.touch_stick.0.hypot(p.touch_stick.1) > p.left.0.hypot(p.left.1) {
                p.touch_stick
            } else {
                p.left
            };
            (left, p.right, edges)
        }
    })
}

/// What the menus get from the pad and the touch screen this frame.
pub enum MenuAct {
    Key(UiKey),
    Move(f32, f32),
    Up,
}

/// Called every frame with whether a menu has the input, and the window size in pixels.
pub fn menu_frame(open: bool, width: f32, height: f32) -> Vec<MenuAct> {
    with_pad(|p| {
        let now = Instant::now();
        let dt = p.last_poll.map_or(0.0, |t| now.duration_since(t).as_secs_f32()).min(0.1);
        p.last_poll = Some(now);
        if open && !p.menu_open {
            // A menu opened: let go of whatever the match held.
            for name in std::mem::take(&mut p.held) {
                p.game.push((name, false));
            }
        }
        p.menu_open = open;
        let mut out = Vec::new();
        if !open {
            p.menu.clear();
            p.touch.clear();
            return out;
        }
        let mut cursor = p.cursor.unwrap_or((width * 0.5, height * 0.5));
        for (phase, x, y) in std::mem::take(&mut p.touch) {
            cursor = (x * width, y * height);
            out.push(MenuAct::Move(cursor.0, cursor.1));
            match phase {
                0 => out.push(MenuAct::Key(UiKey::Mouse1)),
                2 | 3 => out.push(MenuAct::Up),
                _ => {}
            }
        }
        let (lx, ly) = p.left;
        if lx.hypot(ly) > 0.2 {
            let speed = height * 0.9 * dt;
            cursor.0 = (cursor.0 + lx * speed).clamp(0.0, width - 1.0);
            cursor.1 = (cursor.1 - ly * speed).clamp(0.0, height - 1.0);
            out.push(MenuAct::Move(cursor.0, cursor.1));
            p.stick_last = true;
        }
        p.cursor = Some(cursor);
        for (name, down) in std::mem::take(&mut p.menu) {
            if !down {
                if name == "pad_a" && p.stick_last {
                    out.push(MenuAct::Up);
                }
                continue;
            }
            let key = match name {
                "pad_dpad_up" => UiKey::Up,
                "pad_dpad_down" => UiKey::Down,
                "pad_dpad_left" | "pad_lb" => UiKey::Left,
                "pad_dpad_right" | "pad_rb" => UiKey::Right,
                "pad_a" if p.stick_last => UiKey::Mouse1,
                "pad_a" => UiKey::Enter,
                "pad_b" | "pad_start" => UiKey::Escape,
                _ => continue,
            };
            if name.starts_with("pad_dpad") {
                p.stick_last = false;
            }
            out.push(MenuAct::Key(key));
        }
        out
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_zyproz_legacy4_GameActivity_nativeStick(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    x: f32,
    y: f32,
) {
    with_pad(|p| p.touch_stick = (x.clamp(-1.0, 1.0), y.clamp(-1.0, 1.0)));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_zyproz_legacy4_GameActivity_nativeLook(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    dx: f32,
    dy: f32,
) {
    with_pad(|p| {
        if !p.menu_open {
            p.look.0 += dx;
            p.look.1 += dy;
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_zyproz_legacy4_GameActivity_nativeLookSpeed(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    degrees_per_pixel: f32,
) {
    with_pad(|p| p.look_speed = degrees_per_pixel.clamp(0.01, 2.0));
}

/// Whether one of the game's own menus has the input (the touch screen is then its mouse).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_zyproz_legacy4_GameActivity_nativeMenuOpen(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
) -> u8 {
    with_pad(|p| u8::from(p.menu_open))
}

/// A console line from the mod menu, one character at a time (no JNI string handling needed).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_zyproz_legacy4_GameActivity_nativeCommandChar(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    c: i32,
) {
    if let Some(ch) = char::from_u32(c as u32) {
        with_pad(|p| p.typing.push(ch));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_zyproz_legacy4_GameActivity_nativeCommandEnd(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
) {
    with_pad(|p| {
        let line = std::mem::take(&mut p.typing);
        if !line.trim().is_empty() {
            p.commands.push(line);
        }
    });
}

/// The mod menu's console lines, run by the client as if typed at its console.
pub fn take_commands() -> Vec<String> {
    with_pad(|p| std::mem::take(&mut p.commands))
}

/// The touch look since the last frame, as (yaw, pitch) degrees to add.
pub fn take_look() -> (f32, f32) {
    with_pad(|p| {
        let speed = if p.look_speed > 0.0 { p.look_speed } else { 0.15 };
        let (dx, dy) = std::mem::take(&mut p.look);
        (-dx * speed, dy * speed)
    })
}
