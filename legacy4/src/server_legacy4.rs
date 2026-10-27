// SPDX-License-Identifier: GPL-3.0-only
//! Legacy 4 trickshot commands, sent by the mod menu as client commands (`l4save`, `l4noclip`...). Added to the
//! cod4-decomp server by the Legacy 4 build (`legacy4/patch_client.py`); not part of cod4-decomp.
//!
//! Everyone may save and load a position. The rest is for the host (the player of the listen server, who reaches
//! it over loopback): noclip, god mode, and moving or freezing the bots.

use crate::client::Session;
use crate::game::Game;
use std::collections::HashMap;
use std::sync::Mutex;

type Spot = ([f32; 3], [f32; 3]);

/// Saved positions by client slot: they last for the server's life, across rounds and maps.
static SAVED: Mutex<Option<HashMap<u16, Spot>>> = Mutex::new(None);

fn saved() -> std::sync::MutexGuard<'static, Option<HashMap<u16, Spot>>> {
    SAVED.lock().unwrap_or_else(|e| e.into_inner())
}

/// Players whose bullets explode where they land.
static EXPLOSIVE: Mutex<Vec<u16>> = Mutex::new(Vec::new());

/// Whether `slot`'s bullets explode (read by `apply_bullet_hits`).
pub fn explosive(slot: u16) -> bool {
    EXPLOSIVE.lock().unwrap_or_else(|e| e.into_inner()).contains(&slot)
}

/// Players who take no damage at all: bullets, blasts, falls (read by `g_damage`). It lasts across deaths and rounds.
static GOD: Mutex<Vec<u16>> = Mutex::new(Vec::new());

/// Whether client `slot` is in god mode.
pub fn god(slot: u16) -> bool {
    GOD.lock().unwrap_or_else(|e| e.into_inner()).contains(&slot)
}

/// Adds or removes `slot` in a list; returns whether it is in now.
fn toggle(list: &Mutex<Vec<u16>>, slot: u16) -> bool {
    let mut list = list.lock().unwrap_or_else(|e| e.into_inner());
    if list.contains(&slot) {
        list.retain(|s| *s != slot);
        false
    } else {
        list.push(slot);
        true
    }
}

impl Game {
    /// Carries out one Legacy 4 command for client `slot`; returns what to tell the player.
    pub fn legacy4_command(&mut self, slot: u16, verb: &str, host: bool, args: &[String]) -> String {
        let Some(me) = self.client(slot) else {
            return String::new();
        };
        if me.session != Session::Playing {
            return "Spawn first.".to_owned();
        }
        if me.bot {
            return String::new();
        }
        let (origin, angles) = (me.ps.origin, me.ps.viewangles);
        match verb {
            "l4save" => {
                saved().get_or_insert_with(HashMap::new).insert(slot, (origin, angles));
                "Position saved".to_owned()
            }
            "l4load" => {
                let spot = saved().as_ref().and_then(|m| m.get(&slot).copied());
                match spot {
                    Some((o, a)) => {
                        self.teleport(slot, o);
                        self.set_client_view_angle(slot, a);
                        "Position loaded".to_owned()
                    }
                    None => "No saved position yet".to_owned(),
                }
            }
            _ if !host && verb != "l4unlock" => "Only the host can do this".to_owned(),
            "l4noclip" => {
                let on = match self.client_mut(slot) {
                    Some(c) => {
                        c.noclip = !c.noclip;
                        c.noclip
                    }
                    None => false,
                };
                self.set_client_contents(slot);
                format!("Noclip {}", if on { "ON" } else { "OFF" })
            }
            "l4god" => format!("God mode {}", if toggle(&GOD, slot) { "ON" } else { "OFF" }),
            "l4bothere" => {
                let bot = self
                    .connected_clients()
                    .find(|(_, c)| c.bot && c.session == Session::Playing)
                    .map(|(n, _)| n);
                match bot {
                    Some(b) => {
                        self.teleport(b, origin);
                        self.set_client_view_angle(b, [0.0, angles[1] + 180.0, 0.0]);
                        "Bot moved to you".to_owned()
                    }
                    None => "No bot in the game: add one in the BOTS tab".to_owned(),
                }
            }
            "l4unlock" => {
                // UNLOCK ALL from inside a game. The server keeps its own copy of the player's stats from map to map
                // and sends it back, so unlocking only in the client's profile was undone. The client works out
                // the stats from the game's own table (mp/statstable.csv) and sends them here as `index value`
                // pairs; each one is set like the scripts' `setStat` and sent back to the client's profile.
                let pairs: Vec<(i32, i32)> = args
                    .get(1..)
                    .unwrap_or_default()
                    .chunks_exact(2)
                    .filter_map(|p| Some((p[0].parse().ok()?, p[1].parse().ok()?)))
                    .filter(|(i, _)| (0..4000).contains(i))
                    .collect();
                let mut changed = 0;
                for (i, v) in pairs {
                    let Some(c) = self.client_mut(slot) else { break };
                    if c.stats.get(&i).copied().unwrap_or(0) != v {
                        c.stats.insert(i, v);
                        self.send(
                            crate::ui::Dest::Client(slot),
                            net::ui::ServerCmd::Stat { index: i, value: v },
                        );
                        changed += 1;
                    }
                }
                if changed == 0 {
                    String::new()
                } else {
                    format!("Unlocked ({changed} stats): it shows from the next map.")
                }
            }
            "l4explosive" => format!(
                "Explosive bullets {}",
                if toggle(&EXPLOSIVE, slot) { "ON" } else { "OFF" }
            ),
            "l4botfreeze" | "l4botmove" => {
                let freeze = verb == "l4botfreeze";
                let bots: Vec<u16> = self
                    .connected_clients()
                    .filter(|(_, c)| c.bot)
                    .map(|(n, _)| n)
                    .collect();
                for b in &bots {
                    if let Some(c) = self.client_mut(*b) {
                        c.frozen = freeze;
                    }
                }
                match (bots.is_empty(), freeze) {
                    (true, _) => "No bot in the game".to_owned(),
                    (false, true) => "Bots frozen".to_owned(),
                    (false, false) => "Bots move again".to_owned(),
                }
            }
            _ => format!("Unknown command {verb}"),
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Final killcam (Search and Destroy). The original CoD4 has none; this one is made by the server alone, with the
// history the normal killcam already replays (`archive.rs`): when a kill leaves the victim's team with nobody alive,
// every person in the game (not the bots) watches that kill again through the killer's eyes, from a few seconds
// before to a little after, and the next round (or the end of the match) waits for it.

use crate::client::Team;
use sim::pm::PmType;

/// How much of the time before the kill the replay shows, and after it.
const BEFORE_MS: i32 = 4000;
const AFTER_MS: i32 = 2000;
/// The slow motion: the replay from `SLOW_FROM` before the kill to `SLOW_TO` after it runs at `SLOW` of real speed.
const SLOW_FROM: i32 = 1000;
const SLOW_TO: i32 = 1000;
const SLOW: f32 = 0.35;

/// Where the replay is (milliseconds from the kill, negative before it) `t` ms after it began, and its full length.
fn replay_at(t: i32) -> i32 {
    let normal_a = BEFORE_MS - SLOW_FROM;
    let slow_len = ((SLOW_FROM + SLOW_TO) as f32 / SLOW) as i32;
    if t <= normal_a {
        -BEFORE_MS + t
    } else if t <= normal_a + slow_len {
        -SLOW_FROM + ((t - normal_a) as f32 * SLOW) as i32
    } else {
        (SLOW_TO + (t - normal_a - slow_len)).min(AFTER_MS)
    }
}

fn replay_length() -> i32 {
    (BEFORE_MS - SLOW_FROM) + ((SLOW_FROM + SLOW_TO) as f32 / SLOW) as i32 + (AFTER_MS - SLOW_TO)
}
/// A final killcam that has not started this long after its kill is dropped (a level that never moved on).
const GIVE_UP_MS: i32 = 12_000;

/// What a person was doing before the replay took their view, given back after it.
struct Saved {
    slot: u16,
    session: crate::client::Session,
    spectator_client: i32,
    archive_time: f32,
    kill_cam_entity: i32,
    ps_offset_time: i32,
}

struct FinalCam {
    attacker: u16,
    /// The attacker's lag offset at the kill (the bodies were judged that much in the past).
    offset_ms: i32,
    /// Level time of the kill.
    kill_time: i32,
    /// Level time the replay began; `None` while the moments after the kill still play out live.
    started: Option<i32>,
    saved: Vec<Saved>,
}

static FINAL: Mutex<Option<FinalCam>> = Mutex::new(None);

fn final_cam() -> std::sync::MutexGuard<'static, Option<FinalCam>> {
    FINAL.lock().unwrap_or_else(|e| e.into_inner())
}

/// Whether a final killcam is waiting or playing: the round restart and the end of the match wait for it.
pub fn final_killcam_active() -> bool {
    final_cam().is_some()
}

impl Game {
    /// Called by `player_die`: a Search and Destroy kill that leaves the victim's team with nobody alive is the
    /// round's last kill, and gets a final killcam.
    pub fn legacy4_on_kill(&mut self, victim: u16, attacker: Option<u16>, time_offset: i32) {
        if !self.cvars.string("g_gametype").eq_ignore_ascii_case("sd")
            || self.cvars.string("scr_game_allowkillcam") == "0"
        {
            return;
        }
        let Some(attacker) = attacker.filter(|a| *a != victim) else {
            return;
        };
        let (Some(v), Some(a)) = (self.client(victim), self.client(attacker)) else {
            return;
        };
        let team = v.team;
        if a.team == team || matches!(team, Team::Free | Team::Spectator) {
            return;
        }
        let left = self
            .connected_clients()
            .filter(|(n, c)| {
                *n != victim
                    && c.team == team
                    && c.session == Session::Playing
                    && !matches!(c.ps.pm_type, PmType::Dead | PmType::DeadLinked)
            })
            .count();
        if left > 0 {
            return;
        }
        tracing::info!(attacker, victim, "legacy4: final killcam for the round's last kill");
        *final_cam() = Some(FinalCam {
            attacker,
            offset_ms: time_offset,
            kill_time: self.level.time,
            started: None,
            saved: Vec::new(),
        });
    }

    /// Once a server frame, after the scripts and before the snapshots: starts, holds and ends the final killcam.
    /// `span_ms` is how far back the history reaches now.
    pub fn legacy4_final_frame(&mut self, span_ms: i32) {
        let mut guard = final_cam();
        let Some(f) = guard.as_mut() else { return };
        let now = self.level.time;
        // A new level (time went back), or one that never moved on: nothing to show any more.
        if now < f.kill_time || (f.started.is_none() && now > f.kill_time + GIVE_UP_MS) {
            *guard = None;
            return;
        }
        let start = match f.started {
            Some(s) => s,
            None => {
                if now < f.kill_time + 1500 {
                    return; // the moment after the kill plays out live first
                }
                f.saved = self
                    .connected_clients()
                    .filter(|(_, c)| !c.bot)
                    .map(|(slot, c)| Saved {
                        slot,
                        session: c.session,
                        spectator_client: c.spectator_client,
                        archive_time: c.archive_time,
                        kill_cam_entity: c.kill_cam_entity,
                        ps_offset_time: c.ps_offset_time,
                    })
                    .collect();
                for s in &f.saved {
                    self.send(
                        crate::ui::Dest::Client(s.slot),
                        net::ui::ServerCmd::Print {
                            kind: net::ui::PrintKind::Bold,
                            text: "FINAL KILLCAM\n".to_owned(),
                        },
                    );
                }
                f.started = Some(now);
                now
            }
        };
        if now >= start + replay_length() + 300 {
            // Over: everyone gets their own view back, and the round may move on.
            for s in std::mem::take(&mut f.saved) {
                if let Some(c) = self.client_mut(s.slot) {
                    c.session = s.session;
                    c.spectator_client = s.spectator_client;
                    c.archive_time = s.archive_time;
                    c.kill_cam_entity = s.kill_cam_entity;
                    c.ps_offset_time = s.ps_offset_time;
                }
            }
            *guard = None;
            return;
        }
        // The replay: the archived frame `archive` ago. It moves forward in step with time, slower around the kill.
        let at = f.kill_time + replay_at(now - start);
        let archive_ms = (now - at).min(span_ms - 50).max(0);
        let (attacker, offset) = (f.attacker, f.offset_ms);
        let slots: Vec<u16> = f.saved.iter().map(|s| s.slot).collect();
        drop(guard);
        for slot in slots {
            if let Some(c) = self.client_mut(slot) {
                c.session = Session::Spectator;
                c.spectator_client = i32::from(attacker);
                c.archive_time = archive_ms as f32 * 0.001;
                c.kill_cam_entity = -1;
                c.ps_offset_time = offset;
            }
        }
    }
}
