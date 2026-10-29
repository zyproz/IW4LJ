// SPDX-License-Identifier: GPL-3.0-only
//! Legacy 4 online play: the link between a phone that hosts a match and the Legacy 4 relay on the VPS
//! (`legacy4/vps/legacy4-relay.py`).
//!
//! While this phone runs a match (its listen server answers on UDP 28960), a link packet goes to the relay every
//! 2 s; the relay opens a public port for the match and lists it. What a player sends to that port comes here
//! through the link, tagged with the player's number; it goes to the match from a socket of its own for each player
//! (so the server sees one address per player, not loopback: loopback is the host), and the match's answers go back
//! the same way. No Tailscale, no router setting, works on 4G.
//!
//! The Internet list of the game asks the relay for the matches (`cl_masters`, see [`master`]). `lan.txt` in the app
//! folder turns all of this off (LAN only).

use std::collections::HashMap;
use std::io::ErrorKind;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, ToSocketAddrs, UdpSocket};
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

/// The Legacy 4 relay: its control port is both the link and the server list.
pub const RELAY: &str = "2.28.68.135:4435";
const MAGIC: &[u8; 4] = b"L4R1";
const OOB: [u8; 4] = [0xff; 4];
/// The port the phone's listen server uses (`net_port`).
const GAME_PORT: u16 = 28960;
/// A player socket with nothing from the match for this long is closed.
const PLAYER_IDLE: Duration = Duration::from_secs(90);

static MASTER: OnceLock<String> = OnceLock::new();

/// What `cl_masters` is when the player set none: the relay, or nothing with `lan.txt` (LAN only).
pub fn master() -> String {
    MASTER.get().cloned().unwrap_or_default()
}

/// Starts the link in the background, unless `lan.txt` is in `media`.
pub fn start(media: &Path) {
    if media.join("lan.txt").exists() {
        let _ = MASTER.set(String::new());
        eprintln!("legacy4 online: lan.txt found, LAN only");
        return;
    }
    let _ = MASTER.set(RELAY.to_owned());
    let _ = std::thread::Builder::new()
        .name("legacy4-online".into())
        .spawn(|| run(RELAY));
}

/// The address this phone reaches the Internet from (Wi-Fi or mobile data): players' packets come to the match from
/// it, never from loopback.
fn own_ip(relay: SocketAddr) -> IpAddr {
    UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| {
            s.connect(relay)?;
            s.local_addr()
        })
        .map(|a| a.ip())
        .ok()
        .filter(|ip| !ip.is_unspecified() && !ip.is_loopback())
        .unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST))
}

/// The match's `hostname` when this phone runs one (it answers `getinfo` on its port), else `None`.
fn hosting(probe: &UdpSocket) -> Option<String> {
    let mut packet = OOB.to_vec();
    packet.extend_from_slice(b"getinfo 4");
    probe
        .send_to(&packet, (Ipv4Addr::LOCALHOST, GAME_PORT))
        .ok()?;
    let mut buf = [0u8; 2048];
    let deadline = Instant::now() + Duration::from_millis(600);
    while Instant::now() < deadline {
        let Ok((n, from)) = probe.recv_from(&mut buf) else {
            return None;
        };
        if from.port() != GAME_PORT {
            continue;
        }
        if n < 4 || buf[..4] != OOB {
            continue;
        }
        let text = String::from_utf8_lossy(&buf[4..n]);
        let Some(info) = text.strip_prefix("infoResponse") else {
            continue;
        };
        let mut words = info.trim().trim_start_matches('\\').split('\\');
        let mut name = String::new();
        while let (Some(k), Some(v)) = (words.next(), words.next()) {
            if k.eq_ignore_ascii_case("hostname") {
                name = v.to_owned();
            }
        }
        return Some(name);
    }
    None
}

struct Link {
    tunnel: UdpSocket,
    relay: SocketAddr,
    /// Player number (given by the relay) -> the socket that player talks to the match from.
    players: Mutex<HashMap<u16, Arc<UdpSocket>>>,
    /// The address players come from (this phone's own).
    ip: Mutex<IpAddr>,
}

impl Link {
    /// A player's packet from the relay: to the match, from that player's socket (made on the first one).
    fn to_match(self: &Arc<Self>, id: u16, payload: &[u8]) {
        let sock = {
            let mut players = self.players.lock().unwrap_or_else(|e| e.into_inner());
            match players.get(&id) {
                Some(s) => s.clone(),
                None => {
                    let ip = *self.ip.lock().unwrap_or_else(|e| e.into_inner());
                    let Ok(s) = UdpSocket::bind(SocketAddr::new(ip, 0)) else {
                        return;
                    };
                    let _ = s.set_read_timeout(Some(PLAYER_IDLE));
                    let s = Arc::new(s);
                    players.insert(id, s.clone());
                    let link = self.clone();
                    let reader = s.clone();
                    let _ = std::thread::Builder::new()
                        .name(format!("legacy4-player-{id}"))
                        .spawn(move || link.from_match(id, &reader));
                    s
                }
            }
        };
        let ip = sock.local_addr().map(|a| a.ip()).unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST));
        let _ = sock.send_to(payload, SocketAddr::new(ip, GAME_PORT));
    }

    /// The match's answers to one player, back through the relay, until the player goes quiet.
    fn from_match(&self, id: u16, sock: &UdpSocket) {
        let mut buf = vec![0u8; 65536];
        let mut out = Vec::with_capacity(2048);
        loop {
            match sock.recv_from(&mut buf) {
                Ok((n, from)) if from.port() == GAME_PORT => {
                    out.clear();
                    out.extend_from_slice(MAGIC);
                    out.push(b'D');
                    out.extend_from_slice(&id.to_be_bytes());
                    out.extend_from_slice(&buf[..n]);
                    let _ = self.tunnel.send_to(&out, self.relay);
                }
                Ok(_) => {}
                Err(e) if matches!(e.kind(), ErrorKind::Interrupted) => {}
                Err(_) => break,
            }
            // Dropped from the list (the match stopped, or the relay started a new session): this socket is done.
            let players = self.players.lock().unwrap_or_else(|e| e.into_inner());
            if !players.get(&id).is_some_and(|s| std::ptr::eq(&**s, sock)) {
                return;
            }
        }
        let mut players = self.players.lock().unwrap_or_else(|e| e.into_inner());
        if players.get(&id).is_some_and(|s| std::ptr::eq(&**s, sock)) {
            players.remove(&id);
        }
    }

    fn forget_players(&self) {
        self.players.lock().unwrap_or_else(|e| e.into_inner()).clear();
    }
}

fn run(relay: &str) {
    let relay = loop {
        match relay.to_socket_addrs().ok().and_then(|mut a| a.next()) {
            Some(a) => break a,
            None => std::thread::sleep(Duration::from_secs(10)),
        }
    };
    let (tunnel, probe) = loop {
        match (UdpSocket::bind("0.0.0.0:0"), UdpSocket::bind((Ipv4Addr::LOCALHOST, 0))) {
            (Ok(t), Ok(p)) => break (t, p),
            _ => std::thread::sleep(Duration::from_secs(5)),
        }
    };
    let _ = tunnel.set_read_timeout(Some(Duration::from_secs(1)));
    let _ = probe.set_read_timeout(Some(Duration::from_millis(300)));
    let link = Arc::new(Link {
        tunnel,
        relay,
        players: Mutex::new(HashMap::new()),
        ip: Mutex::new(own_ip(relay)),
    });
    // What the relay sends: players' packets, and the port the match got.
    let reader = link.clone();
    let _ = std::thread::Builder::new()
        .name("legacy4-online-in".into())
        .spawn(move || {
            let mut buf = vec![0u8; 65536];
            let mut session = None;
            loop {
                let Ok((n, from)) = reader.tunnel.recv_from(&mut buf) else {
                    continue;
                };
                let p = &buf[..n];
                if from != reader.relay || n < 5 || &p[..4] != MAGIC {
                    continue;
                }
                match p[4] {
                    b'D' if n >= 7 => reader.to_match(u16::from_be_bytes([p[5], p[6]]), &p[7..]),
                    b'P' if n >= 11 => {
                        let port = u16::from_be_bytes([p[5], p[6]]);
                        let s = u32::from_be_bytes([p[7], p[8], p[9], p[10]]);
                        if session != Some(s) {
                            // A new match on the relay: the players of an old one are not this one's.
                            reader.forget_players();
                            session = Some(s);
                            eprintln!("legacy4 online: match listed, public port {port}");
                        }
                    }
                    _ => {}
                }
            }
        });
    let mut was_hosting = false;
    loop {
        match hosting(&probe) {
            Some(name) => {
                if !was_hosting {
                    *link.ip.lock().unwrap_or_else(|e| e.into_inner()) = own_ip(relay);
                    eprintln!("legacy4 online: hosting {name:?}, linking to the relay");
                }
                was_hosting = true;
                let mut packet = MAGIC.to_vec();
                packet.push(b'R');
                packet.extend(name.bytes().take(64));
                let _ = link.tunnel.send_to(&packet, relay);
            }
            None if was_hosting => {
                was_hosting = false;
                let mut packet = MAGIC.to_vec();
                packet.push(b'X');
                let _ = link.tunnel.send_to(&packet, relay);
                link.forget_players();
                eprintln!("legacy4 online: no match any more, link closed");
            }
            None => {}
        }
        std::thread::sleep(Duration::from_secs(2));
    }
}
