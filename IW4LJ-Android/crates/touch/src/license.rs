//! The player's id and licence key.
//!
//! Every install makes itself an id the first time it runs. A VIP or admin
//! licence is a line signed with the owner's private key, bound to that id:
//!
//! ```text
//! <tier>.<id>.<expiry>.<signature>
//! ```
//!
//! `tier` is `vip` or `admin`, `id` the 16 hex digits, `expiry` a Unix time in
//! seconds (`0` never ends), `signature` the Ed25519 signature, in hex, of the
//! text `MW2L1|<tier>|<ID>|<expiry>`. The public key that checks it is compiled
//! in (`license_pub.hex`); the private key never leaves the owner's phone.

use std::path::{Path, PathBuf};
use std::sync::RwLock;
use std::time::{SystemTime, UNIX_EPOCH};

use ring::rand::{SecureRandom, SystemRandom};
use ring::signature::{ED25519, UnparsedPublicKey};

/// The public key that checks licences, 32 bytes in hex.
const PUBLIC_KEY_HEX: &str = include_str!("../license_pub.hex");

/// Where the app keeps its files that survive a reinstall.
const ROOTS: [&str; 2] = [
    "/sdcard/Android/media/dev.iw4j.iw4l",
    "/storage/emulated/0/Android/media/dev.iw4j.iw4l",
];

const ID_FILE: &str = "player-id.txt";
const KEY_FILE: &str = "license.key";

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub(crate) enum Tier {
    Free,
    Vip,
    Admin,
}

impl Tier {
    fn name(self) -> &'static str {
        match self {
            Tier::Free => "FREE",
            Tier::Vip => "VIP",
            Tier::Admin => "ADMIN",
        }
    }
}

struct State {
    /// 16 uppercase hex digits.
    id: String,
    tier: Tier,
    /// What happened to the last key entered.
    note: &'static str,
}

static STATE: RwLock<Option<State>> = RwLock::new(None);

fn root() -> Option<PathBuf> {
    ROOTS
        .iter()
        .map(Path::new)
        .find(|root| root.is_dir())
        .map(Path::to_path_buf)
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| elapsed.as_secs())
}

fn hex_decode(text: &str) -> Option<Vec<u8>> {
    let text = text.trim();
    if text.len() % 2 != 0 {
        return None;
    }
    (0..text.len())
        .step_by(2)
        .map(|at| u8::from_str_radix(text.get(at..at + 2)?, 16).ok())
        .collect()
}

fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02X}")).collect()
}

fn valid_id(text: &str) -> bool {
    text.len() == 16 && text.chars().all(|c| c.is_ascii_hexdigit())
}

/// The tier a key gives to `id` at time `now`, if the key is genuine.
fn verify(token: &str, id: &str, now: u64) -> Option<Tier> {
    let mut parts = token.trim().split('.');
    let tier_text = parts.next()?;
    let key_id = parts.next()?;
    let expiry: u64 = parts.next()?.parse().ok()?;
    let signature = hex_decode(parts.next()?)?;
    if parts.next().is_some() {
        return None;
    }
    if !key_id.eq_ignore_ascii_case(id) {
        return None;
    }
    let tier = match tier_text {
        "vip" => Tier::Vip,
        "admin" => Tier::Admin,
        _ => return None,
    };
    if expiry != 0 && now > expiry {
        return None;
    }
    let public_key = hex_decode(PUBLIC_KEY_HEX)?;
    if public_key.len() != 32 {
        return None;
    }
    let message = format!("MW2L1|{tier_text}|{}|{expiry}", id.to_ascii_uppercase());
    UnparsedPublicKey::new(&ED25519, &public_key)
        .verify(message.as_bytes(), &signature)
        .ok()?;
    Some(tier)
}

fn load() -> State {
    let root = root();
    let mut id = root
        .as_ref()
        .and_then(|root| std::fs::read_to_string(root.join(ID_FILE)).ok())
        .map(|text| text.trim().to_ascii_uppercase())
        .filter(|text| valid_id(text));
    if id.is_none() {
        let mut bytes = [0u8; 8];
        if SystemRandom::new().fill(&mut bytes).is_ok() {
            let fresh = hex_encode(&bytes);
            if let Some(root) = &root {
                let _ = std::fs::write(root.join(ID_FILE), format!("{fresh}\n"));
            }
            id = Some(fresh);
        }
    }
    let id = id.unwrap_or_else(|| "0000000000000000".to_owned());
    let tier = root
        .as_ref()
        .and_then(|root| std::fs::read_to_string(root.join(KEY_FILE)).ok())
        .and_then(|token| verify(&token, &id, now()))
        .unwrap_or(Tier::Free);
    State {
        id,
        tier,
        note: "",
    }
}

fn with_state<T>(read: impl FnOnce(&State) -> T) -> T {
    if let Ok(guard) = STATE.read()
        && let Some(state) = guard.as_ref()
    {
        return read(state);
    }
    let fresh = load();
    if let Ok(mut guard) = STATE.write() {
        let state = guard.get_or_insert(fresh);
        return read(state);
    }
    read(&State {
        id: "0000000000000000".to_owned(),
        tier: Tier::Free,
        note: "",
    })
}

/// What this player may use.
pub(crate) fn tier() -> Tier {
    with_state(|state| state.tier)
}

/// A line for the account tab: the id, the status and what to do next.
pub(crate) fn summary() -> String {
    with_state(|state| {
        let id = &state.id;
        let grouped = format!("{}-{}-{}-{}", &id[0..4], &id[4..8], &id[8..12], &id[12..16]);
        let next = match state.tier {
            Tier::Free => " Send your ID to ZyProz to get a key, then ENTER LICENSE KEY.",
            Tier::Vip | Tier::Admin => "",
        };
        let note = if state.note.is_empty() {
            String::new()
        } else {
            format!(" ({})", state.note)
        };
        format!("Your ID: {grouped}  Status: {}{note}.{next}", state.tier.name())
    })
}

/// A key typed or pasted by the player: kept for next time when it is genuine.
pub(crate) fn submit(text: &str) {
    let token: String = text.chars().filter(|c| !c.is_whitespace()).collect();
    let id = with_state(|state| state.id.clone());
    let outcome = verify(&token, &id, now());
    if outcome.is_some()
        && let Some(root) = root()
    {
        let _ = std::fs::write(root.join(KEY_FILE), format!("{token}\n"));
    }
    if let Ok(mut guard) = STATE.write()
        && let Some(state) = guard.as_mut()
    {
        match outcome {
            Some(tier) => {
                state.tier = tier;
                state.note = "key accepted";
            }
            None => state.note = "key refused: wrong ID, expired or not genuine",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_garbage() {
        assert_eq!(verify("", "0123456789ABCDEF", 1), None);
        assert_eq!(verify("vip.0123456789ABCDEF.0.zz", "0123456789ABCDEF", 1), None);
        assert_eq!(verify("god.0123456789ABCDEF.0.00", "0123456789ABCDEF", 1), None);
    }

    #[test]
    fn hex_round_trip() {
        assert_eq!(hex_decode(&hex_encode(&[0, 1, 254, 255])), Some(vec![0, 1, 254, 255]));
    }
}
