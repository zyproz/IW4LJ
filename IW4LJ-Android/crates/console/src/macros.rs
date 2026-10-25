//! Scripts on pad buttons: a trickshotter's sequences of presses and waits.
//!
//! A script is an IW4x-style alias, one line of `scripts.cfg`:
//!
//! ```text
//! set glide "+gostand;wait 3;-gostand;weapnext"
//! ```
//!
//! It is a list of input commands (`+attack`, `-attack`, `+usereload`,
//! `weapnext`, ...) and `wait N`, where `N` counts frames of a 57 frames per
//! second game (the rate the scripts are made for), whatever the real frame
//! rate is. `vstr other` plays another
//! script in place. A script is tied to a pad button with `macrobind`, and the
//! pad button then plays it instead of its usual action.
//!
//! The scripts are a VIP tool: nothing runs unless [`set_macros_allowed`] has
//! been told the player may use them.

use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;
use std::sync::RwLock;
use std::sync::atomic::{AtomicBool, Ordering};

use bevy::input::gamepad::Gamepad;
use bevy::prelude::*;
use frame::AppScreen;

use crate::binds::{BindButton, KeyBinds, PadButton};
use crate::synthetic_input::is_bind_command;
use crate::{ConsoleCommand, ConsoleInputState, ConsoleRegistry, ConsoleSettings, ConsoleState};

/// The scripts shipped with the app; a copy next to the game's files is the
/// one the player edits.
const DEFAULT_SCRIPTS: &str = include_str!("../assets/scripts.cfg");

const SCRIPTS_FILE: &str = "scripts.cfg";
const BINDS_FILE: &str = "script-binds.txt";

const ROOTS: [&str; 2] = [
    "/sdcard/Android/media/dev.iw4j.iw4l",
    "/storage/emulated/0/Android/media/dev.iw4j.iw4l",
];

/// The frame rate the scripts' waits are counted in (FPS 57 in GRAPHICS holds
/// the game to it).
const SCRIPT_FPS: f32 = 57.0;
/// A tap lasts one frame of that frame rate.
const TAP_SECONDS: f32 = 1.0 / SCRIPT_FPS;
const MAX_CALL_DEPTH: usize = 8;
/// A hitch must not make a script jump a long way ahead.
const MAX_FRAMES_PER_UPDATE: f32 = 6.0;

static ALLOWED: AtomicBool = AtomicBool::new(false);
static SUMMARY: RwLock<String> = RwLock::new(String::new());

/// Tell the scripts whether this player may run them (a VIP or an admin).
pub fn set_macros_allowed(allowed: bool) {
    ALLOWED.store(allowed, Ordering::Relaxed);
}

/// A line for the menu: what each pad button plays and how many scripts there are.
pub fn macro_summary() -> String {
    SUMMARY
        .read()
        .map(|text| text.clone())
        .unwrap_or_default()
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Step {
    /// An input command such as `+attack`, `-attack` or `weapnext`.
    Input(String),
    /// Frames of a 57 frames per second game.
    Wait(f32),
    /// `vstr name`: another script, played in place.
    Call(String),
}

/// What a script line may contain, and what was left out.
#[derive(Default)]
pub(crate) struct Parsed {
    pub macros: BTreeMap<String, Vec<Step>>,
    /// Commands that this app cannot play (they belong to an IW4x mod).
    pub skipped: usize,
}

fn input_known(word: &str) -> bool {
    if let Some(rest) = word.strip_prefix('-') {
        is_bind_command(&format!("+{rest}"))
    } else {
        is_bind_command(word)
    }
}

/// The steps of one alias body; `skipped` counts the commands that cannot play.
pub(crate) fn parse_body(body: &str, skipped: &mut usize) -> Vec<Step> {
    let mut steps = Vec::new();
    for token in body.split(';') {
        let words: Vec<&str> = token.split_whitespace().collect();
        let Some(first) = words.first() else {
            continue;
        };
        let first = first.to_ascii_lowercase();
        if first == "wait" {
            let frames = words
                .get(1)
                .and_then(|text| text.parse::<f32>().ok())
                .unwrap_or(1.0);
            steps.push(Step::Wait(frames.max(0.0)));
        } else if first == "vstr" && words.len() >= 2 {
            steps.push(Step::Call(words[1].to_ascii_lowercase()));
        } else {
            let command = words.join(" ").to_ascii_lowercase();
            if input_known(&command) {
                steps.push(Step::Input(command));
            } else {
                *skipped += 1;
            }
        }
    }
    steps
}

/// Every `set name "..."` line of a config file that holds at least one step
/// the app can play.
pub(crate) fn parse_scripts(text: &str) -> Parsed {
    let mut parsed = Parsed::default();
    for line in text.lines() {
        let line = line.trim_start();
        let Some(rest) = line
            .strip_prefix("seta ")
            .or_else(|| line.strip_prefix("set "))
        else {
            continue;
        };
        let rest = rest.trim_start();
        let Some((name, after)) = rest.split_once(char::is_whitespace) else {
            continue;
        };
        let Some(body) = after.trim_start().strip_prefix('"') else {
            continue;
        };
        let Some(end) = body.find('"') else {
            continue;
        };
        let steps = parse_body(&body[..end], &mut parsed.skipped);
        let plays = steps.iter().any(|step| matches!(step, Step::Input(_)));
        if plays {
            parsed.macros.insert(name.to_ascii_lowercase(), steps);
        }
    }
    parsed
}

/// A script with its `vstr` calls spelled out.
fn flatten(macros: &BTreeMap<String, Vec<Step>>, name: &str, depth: usize, out: &mut Vec<Step>) {
    let Some(steps) = macros.get(name) else {
        return;
    };
    for step in steps {
        match step {
            Step::Call(other) if depth < MAX_CALL_DEPTH => flatten(macros, other, depth + 1, out),
            Step::Call(_) => {}
            other => out.push(other.clone()),
        }
    }
}

fn root() -> Option<PathBuf> {
    ROOTS
        .iter()
        .map(PathBuf::from)
        .find(|root| root.is_dir())
}

#[derive(Resource, Default)]
pub struct MacroLibrary {
    macros: BTreeMap<String, Vec<Step>>,
    skipped: usize,
    loaded: bool,
}

struct Run {
    steps: Vec<Step>,
    next: usize,
    /// Frames still to wait before the next step.
    wait: f32,
    /// `+commands` this script holds down, let go of if it is cut short.
    held: Vec<String>,
}

#[derive(Resource, Default)]
pub struct MacroRunner {
    runs: Vec<Run>,
    binds: Vec<(PadButton, String)>,
    /// What each pad button did before a script took it over.
    original: HashMap<PadButton, u32>,
}

impl MacroLibrary {
    fn load(&mut self) {
        let root = root();
        let path = root.as_ref().map(|root| root.join(SCRIPTS_FILE));
        let text = match path.as_ref().and_then(|path| std::fs::read_to_string(path).ok()) {
            Some(text) => text,
            None => {
                // First run: leave a copy the player can edit.
                if let Some(path) = &path {
                    let _ = std::fs::write(path, DEFAULT_SCRIPTS);
                }
                DEFAULT_SCRIPTS.to_owned()
            }
        };
        let parsed = parse_scripts(&text);
        self.macros = parsed.macros;
        self.skipped = parsed.skipped;
        self.loaded = true;
    }
}

fn pad_by_name(text: &str) -> Option<PadButton> {
    let text = text.trim();
    let alias = match text.to_ascii_lowercase().as_str() {
        "up" | "dpadup" | "pad_up" => Some(PadButton::DpadUp),
        "down" | "dpaddown" | "pad_down" => Some(PadButton::DpadDown),
        "left" | "dpadleft" | "pad_left" => Some(PadButton::DpadLeft),
        "right" | "dpadright" | "pad_right" => Some(PadButton::DpadRight),
        _ => None,
    };
    alias.or_else(|| {
        PadButton::ALL
            .into_iter()
            .find(|pad| pad.console_name().eq_ignore_ascii_case(text))
    })
}

impl MacroRunner {
    fn save(&self) {
        let Some(root) = root() else {
            return;
        };
        let text: String = self
            .binds
            .iter()
            .map(|(pad, name)| format!("{}={name}\n", pad.console_name()))
            .collect();
        let _ = std::fs::write(root.join(BINDS_FILE), text);
    }

    fn load_binds(&mut self, library: &MacroLibrary) {
        self.binds.clear();
        let Some(root) = root() else {
            return;
        };
        let Ok(text) = std::fs::read_to_string(root.join(BINDS_FILE)) else {
            return;
        };
        for line in text.lines() {
            if let Some((pad, name)) = line.split_once('=')
                && let Some(pad) = pad_by_name(pad)
            {
                let name = name.trim().to_ascii_lowercase();
                if library.macros.contains_key(&name) {
                    self.binds.push((pad, name));
                }
            }
        }
    }

    fn start(&mut self, library: &MacroLibrary, name: &str) -> bool {
        let mut steps = Vec::new();
        flatten(&library.macros, name, 0, &mut steps);
        if steps.is_empty() {
            return false;
        }
        // A script that ends with a button still down lets it go a moment
        // later, so no input is ever left stuck.
        let mut down: Vec<String> = Vec::new();
        for step in &steps {
            if let Step::Input(text) = step {
                if let Some(rest) = text.strip_prefix('+') {
                    down.push(rest.to_owned());
                } else if let Some(rest) = text.strip_prefix('-') {
                    down.retain(|held| held != rest);
                }
            }
        }
        if !down.is_empty() {
            steps.push(Step::Wait(2.0));
            for held in down {
                steps.push(Step::Input(format!("-{held}")));
            }
        }
        self.runs.push(Run {
            steps,
            next: 0,
            wait: 0.0,
            held: Vec::new(),
        });
        true
    }

    fn cancel_all(&mut self, inputs: &mut ConsoleInputState) {
        for run in self.runs.drain(..) {
            for held in run.held {
                inputs.release(&held);
            }
        }
    }
}

fn refresh_summary(library: &MacroLibrary, runner: &MacroRunner) {
    let slot = |pad: PadButton| {
        runner
            .binds
            .iter()
            .find(|(bound, _)| *bound == pad)
            .map_or("-", |(_, name)| name.as_str())
    };
    let mut line = format!(
        "PAD UP: {} | PAD DOWN: {} | PAD LEFT: {} | PAD RIGHT: {}",
        slot(PadButton::DpadUp),
        slot(PadButton::DpadDown),
        slot(PadButton::DpadLeft),
        slot(PadButton::DpadRight),
    );
    for (pad, name) in &runner.binds {
        if !matches!(
            pad,
            PadButton::DpadUp | PadButton::DpadDown | PadButton::DpadLeft | PadButton::DpadRight
        ) {
            line.push_str(&format!(" | {}: {name}", pad.console_name()));
        }
    }
    line.push_str(&format!(
        "  ({} scripts loaded, {} commands left out). Edit scripts.cfg in Android/media/dev.iw4j.iw4l then RELOAD SCRIPTS. Hold the game at FPS 57 in GRAPHICS: the scripts are made for it.",
        library.macros.len(),
        library.skipped
    ));
    if let Ok(mut summary) = SUMMARY.write() {
        *summary = line;
    }
}

pub(crate) fn register_macro_commands(registry: &mut ConsoleRegistry) {
    for (name, usage) in [
        (
            "macrobind",
            "macrobind <DPAD_UP|DPAD_DOWN|DPAD_LEFT|DPAD_RIGHT|BUTTON_*> <script|none> — a pad button plays a script from scripts.cfg",
        ),
        (
            "macroreload",
            "macroreload — read scripts.cfg again",
        ),
        (
            "macroclear",
            "macroclear — no pad button plays a script",
        ),
        (
            "macrorun",
            "macrorun <script> — play a script now",
        ),
        (
            "macrolist",
            "macrolist [text] — the scripts whose name holds the text",
        ),
    ] {
        if registry.resolve(name).is_none() {
            registry.register(crate::CommandSpec::new(name).usage(usage));
        }
    }
}

pub(crate) fn route_macro_commands(
    mut events: MessageReader<ConsoleCommand>,
    mut console: ResMut<ConsoleState>,
    settings: Res<ConsoleSettings>,
    mut library: ResMut<MacroLibrary>,
    mut runner: ResMut<MacroRunner>,
    mut binds: ResMut<KeyBinds>,
) {
    for command in events.read() {
        if !matches!(
            command.name.as_str(),
            "macrobind" | "macroreload" | "macroclear" | "macrorun" | "macrolist"
        ) {
            continue;
        }
        if !library.loaded {
            library.load();
            let library_ref: &MacroLibrary = &library;
            runner.load_binds(library_ref);
        }
        let capacity = settings.log_capacity;
        match command.name.as_str() {
            "macrobind" => {
                let (Some(pad), Some(name)) = (
                    command.args.first().and_then(|text| pad_by_name(text)),
                    command.args.get(1),
                ) else {
                    console.echo("usage: macrobind <DPAD_UP|...> <script|none>", capacity);
                    continue;
                };
                let name = name.to_ascii_lowercase();
                runner.binds.retain(|(bound, _)| *bound != pad);
                if name == "none" {
                    if let Some(original) = runner.original.remove(&pad) {
                        binds.set(BindButton::Pad(pad), original);
                    }
                    console.echo(format!("{}: no script", pad.console_name()), capacity);
                } else if library.macros.contains_key(&name) {
                    runner.binds.push((pad, name.clone()));
                    console.echo(format!("{}: {name}", pad.console_name()), capacity);
                } else {
                    console.echo(format!("no script called `{name}`"), capacity);
                }
                runner.save();
            }
            "macroreload" => {
                library.load();
                let library_ref: &MacroLibrary = &library;
                runner.load_binds(library_ref);
                console.echo(
                    format!("{} scripts loaded", library.macros.len()),
                    capacity,
                );
            }
            "macroclear" => {
                let pads: Vec<PadButton> = runner.binds.iter().map(|(pad, _)| *pad).collect();
                for pad in pads {
                    if let Some(original) = runner.original.remove(&pad) {
                        binds.set(BindButton::Pad(pad), original);
                    }
                }
                runner.binds.clear();
                runner.save();
                console.echo("no pad button plays a script", capacity);
            }
            "macrorun" => match command.args.first() {
                Some(name) if ALLOWED.load(Ordering::Relaxed) => {
                    let name = name.to_ascii_lowercase();
                    let library_ref: &MacroLibrary = &library;
                    if !runner.start(library_ref, &name) {
                        console.echo(format!("no script called `{name}`"), capacity);
                    }
                }
                Some(_) => console.echo("scripts are for VIPs", capacity),
                None => console.echo("usage: macrorun <script>", capacity),
            },
            _ => {
                let filter = command
                    .args
                    .first()
                    .map(|text| text.to_ascii_lowercase())
                    .unwrap_or_default();
                let names: Vec<&str> = library
                    .macros
                    .keys()
                    .filter(|name| name.contains(&filter))
                    .map(String::as_str)
                    .take(40)
                    .collect();
                console.echo(names.join(" "), capacity);
            }
        }
        let library_ref: &MacroLibrary = &library;
        refresh_summary(library_ref, &runner);
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn run_macros(
    time: Res<Time<Real>>,
    mut library: ResMut<MacroLibrary>,
    mut runner: ResMut<MacroRunner>,
    mut inputs: ResMut<ConsoleInputState>,
    mut binds: ResMut<KeyBinds>,
    gamepads: Query<&Gamepad>,
    active: Res<frame::ActivePad>,
    console: Res<ConsoleState>,
    screen: Option<Res<AppScreen>>,
    mod_menu: Res<frame::ModMenuOpen>,
    overlay: Res<frame::UiOverlayInput>,
) {
    if !library.loaded {
        library.load();
        let library_ref: &MacroLibrary = &library;
        runner.load_binds(library_ref);
        refresh_summary(library_ref, &runner);
    }
    let in_game = screen
        .as_deref()
        .is_some_and(|screen| matches!(screen, AppScreen::InGame));
    let usable = ALLOWED.load(Ordering::Relaxed)
        && in_game
        && !console.open
        && !mod_menu.0
        && !overlay.0;
    if !ALLOWED.load(Ordering::Relaxed) || !in_game {
        runner.cancel_all(&mut inputs);
    }

    // A pad button that plays a script does not also do its usual action.
    let taken: Vec<PadButton> = runner.binds.iter().map(|(pad, _)| *pad).collect();
    for pad in taken {
        if !ALLOWED.load(Ordering::Relaxed) {
            continue;
        }
        if let Some(original) = binds.get(BindButton::Pad(pad)) {
            runner.original.entry(pad).or_insert(original);
            let _ = binds.apply_script(&format!("unbind {}", pad.console_name()));
        }
    }

    if usable {
        let pad = active.0.and_then(|entity| gamepads.get(entity).ok());
        if let Some(pad) = pad {
            let pressed: Vec<String> = runner
                .binds
                .iter()
                .filter(|(button, _)| pad.just_pressed(button.gamepad_button()))
                .map(|(_, name)| name.clone())
                .collect();
            for name in pressed {
                let library_ref: &MacroLibrary = &library;
                runner.start(library_ref, &name);
            }
        }
    }

    // Play what is due: a wait counts frames of a 60 frames per second game.
    let frames = (time.delta_secs() * SCRIPT_FPS).clamp(0.0, MAX_FRAMES_PER_UPDATE);
    for run in runner.runs.iter_mut() {
        run.wait -= frames;
        while run.wait <= 0.0 && run.next < run.steps.len() {
            let step = run.steps[run.next].clone();
            run.next += 1;
            match step {
                Step::Wait(count) => run.wait += count,
                Step::Input(text) => {
                    if let Some(release) = text.strip_prefix('-') {
                        let plus = format!("+{release}");
                        inputs.release(&plus);
                        run.held.retain(|held| *held != plus);
                    } else if text.starts_with('+') {
                        // `+x;-x` with no wait between is a tap of one frame.
                        let tap = matches!(
                            run.steps.get(run.next),
                            Some(Step::Input(next)) if next.strip_prefix('-')
                                == text.strip_prefix('+')
                        );
                        if tap {
                            inputs.press(&text, TAP_SECONDS);
                            run.next += 1;
                        } else {
                            inputs.hold(&text);
                            run.held.push(text);
                        }
                    } else {
                        inputs.press(&text, TAP_SECONDS);
                    }
                }
                Step::Call(_) => {}
            }
        }
    }
    runner.runs.retain(|run| run.next < run.steps.len());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_script() {
        let mut skipped = 0;
        let steps = parse_body("+gostand;wait 3;-gostand;weapnext;wait;+tele", &mut skipped);
        assert_eq!(
            steps,
            vec![
                Step::Input("+gostand".into()),
                Step::Wait(3.0),
                Step::Input("-gostand".into()),
                Step::Input("weapnext".into()),
                Step::Wait(1.0),
            ]
        );
        assert_eq!(skipped, 1);
    }

    #[test]
    fn reads_set_lines() {
        let parsed = parse_scripts("set glide \"+gostand;wait 3;-gostand\" //note\nbind X \"vstr glide\"\n");
        assert_eq!(parsed.macros.len(), 1);
        assert!(parsed.macros.contains_key("glide"));
    }
}
