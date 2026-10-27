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
    pub fn legacy4_command(&mut self, slot: u16, verb: &str, host: bool) -> String {
        let Some(me) = self.client(slot) else {
            return String::new();
        };
        if me.session != Session::Playing {
            return "Spawn first.".to_owned();
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
            _ if !host => "Only the host can do this".to_owned(),
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
