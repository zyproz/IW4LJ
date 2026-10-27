# Legacy 4: changes the cod4-decomp client needs to run on Android.
# Run from the root of a cod4-decomp checkout: python3 <path>/patch_client.py <path to legacy4 folder>
# Each change looks for an exact piece of the original code; if cod4-decomp changed it, the build stops here
# with the name of the change, instead of building something half patched.
import pathlib
import shutil
import sys

legacy4 = pathlib.Path(sys.argv[1])


def edit(path, old, new, what):
    p = pathlib.Path(path)
    s = p.read_text()
    n = s.count(old)
    if n != 1:
        sys.exit(f"patch_client: '{what}' expected once in {path}, found {n} times")
    p.write_text(s.replace(old, new))
    print(f"ok  {what}")


# 1. Cargo.toml: a shared library for Android, winit's Android support, no desktop folder dialog.
toml = "crates/client/Cargo.toml"
edit(toml,
     'install = { path = "../install", features = ["prompt"] }',
     'install = { path = "../install" }',
     "folder dialog off by default")
edit(toml,
     'winit = "0.30.13"',
     'winit = { version = "0.30.13", features = ["android-native-activity"] }',
     "winit Android activity")
with open(toml, "a") as f:
    f.write('''
# Legacy 4 (Android): the folder dialog only exists on desktop.
[target.'cfg(not(any(target_arch = "wasm32", target_os = "android")))'.dependencies]
install = { path = "../install", features = ["prompt"] }

[target.'cfg(target_os = "android")'.dependencies]
libc = "0.2"
# Legacy licence keys (Ed25519), as in Legacy Warfare; already in the lockfile through rustls.
ring = "0.17"

# Legacy 4 (Android): Android loads the client as a shared library.
[lib]
name = "cod4e_android"
path = "src/main.rs"
crate-type = ["cdylib"]
''')
print("ok  Android library target")

# 2. main.rs: the Android module, and its arguments in place of the command line.
main = "crates/client/src/main.rs"
edit(main,
     "mod wire;\n",
     "mod wire;\n#[cfg(target_os = \"android\")]\nmod android;\n",
     "android module")
edit(main,
     "    let args: Vec<String> = std::env::args().skip(1).collect();\n",
     "    #[cfg(target_os = \"android\")]\n"
     "    let args: Vec<String> = android::args().unwrap_or_default();\n"
     "    #[cfg(not(target_os = \"android\"))]\n"
     "    let args: Vec<String> = std::env::args().skip(1).collect();\n",
     "Android arguments")
shutil.copy(legacy4 / "src" / "android.rs", "crates/client/src/android.rs")
shutil.copy(legacy4 / "src" / "license_pub.hex", "crates/client/src/license_pub.hex")
shutil.copy(legacy4 / "menu" / "legacy_banner.rgba", "crates/client/src/legacy_banner.rgba")
print("ok  android.rs copied")

# 3. app.rs: the event loop gets the activity, a finger is the mouse, leaving the app stops the game.
app = "crates/client/src/app.rs"
edit(app,
     "    let el = EventLoop::<Ready>::with_user_event()\n        .build()\n",
     "    #[allow(unused_mut)]\n"
     "    let mut builder = EventLoop::<Ready>::with_user_event();\n"
     "    #[cfg(target_os = \"android\")]\n"
     "    {\n"
     "        use winit::platform::android::EventLoopBuilderExtAndroid;\n"
     "        builder.with_android_app(crate::android::app());\n"
     "    }\n"
     "    let el = builder\n        .build()\n",
     "event loop on Android")
edit(app,
     "    fn window_event(&mut self, el: &ActiveEventLoop, _: WindowId, ev: WindowEvent) {\n"
     "        let Some(st) = self.st.as_mut() else { return };\n",
     "    fn window_event(&mut self, el: &ActiveEventLoop, _id: WindowId, ev: WindowEvent) {\n"
     "        #[cfg(target_os = \"android\")]\n"
     "        if let WindowEvent::Touch(t) = &ev {\n"
     "            for e in crate::android::touch_as_mouse(t) {\n"
     "                self.window_event(el, _id, e);\n"
     "            }\n"
     "            return;\n"
     "        }\n"
     "        let Some(st) = self.st.as_mut() else { return };\n",
     "touch as mouse")
edit(app,
     "    fn user_event(&mut self, el: &ActiveEventLoop, ready: Ready) {\n",
     "    #[cfg(target_os = \"android\")]\n"
     "    fn suspended(&mut self, _: &ActiveEventLoop) {\n"
     "        if let Some(st) = self.st.as_mut() {\n"
     "            let _ = st.input.save();\n"
     "        }\n"
     "        crate::android::leave();\n"
     "    }\n\n"
     "    fn user_event(&mut self, el: &ActiveEventLoop, ready: Ready) {\n",
     "stop when the app is left")

# 4. pad.rs: on Android the controller comes from GameActivity (JNI), not from gilrs.
pad = "crates/client/src/input/pad.rs"
edit(pad,
     "    pub fn poll(&mut self) -> Vec<(&'static str, bool)> {\n",
     "    pub fn poll(&mut self) -> Vec<(&'static str, bool)> {\n"
     "        #[cfg(target_os = \"android\")]\n"
     "        {\n"
     "            let (left, right, edges) = crate::android::pad_poll();\n"
     "            self.left = left;\n"
     "            self.right = right;\n"
     "            #[allow(clippy::needless_return)]\n"
     "            return edges;\n"
     "        }\n"
     "        #[allow(unreachable_code)]\n",
     "Android controller")

# 5. app.rs: in the menus, the controller and the touch screen give menu keys and a cursor.
edit(app,
     "        st.menu_was_open = menu_open;\n",
     "        st.menu_was_open = menu_open;\n"
     "        #[cfg(target_os = \"android\")]\n"
     "        {\n"
     "            let size = st.window.inner_size();\n"
     "            let acts = crate::android::menu_frame(menu_open, size.width as f32, size.height as f32);\n"
     "            if let Some(sh) = st.shell.as_mut() {\n"
     "                // The mod menu's lines, run as if typed at the console.\n"
     "                for line in crate::android::take_commands() {\n"
     "                    sh.console_line(&mut st.input, &line);\n"
     "                }\n"
     "                for act in acts {\n"
     "                    match act {\n"
     "                        crate::android::MenuAct::Key(k) => {\n"
     "                            sh.key(&mut st.input, k);\n"
     "                        }\n"
     "                        crate::android::MenuAct::Move(x, y) => sh.mouse_move(&mut st.input, x, y),\n"
     "                        crate::android::MenuAct::Up => sh.mouse_up(),\n"
     "                    }\n"
     "                }\n"
     "            }\n"
     "        }\n",
     "controller and touch in the menus")

# 6. input/mod.rs: the touch screen's look drag turns the view like a mouse.
edit("crates/client/src/input/mod.rs",
     "        yaw += pad_yaw;\n        pitch += pad_pitch;\n",
     "        // Legacy 4: aiming down the sights slows the stick as it slows the mouse (the zoom's own scale), times\n"
     "        // the player's ADS setting (`l4_ads_sens`, mod menu CONTROLS).\n"
     "        #[cfg(target_os = \"android\")]\n"
     "        let (pad_yaw, pad_pitch) = if self.fov_sensitivity_scale < 0.999 {\n"
     "            let mine = cv.f32(\"l4_ads_sens\");\n"
     "            let k = self.fov_sensitivity_scale * if mine > 0.0 { mine } else { 1.0 };\n"
     "            (pad_yaw * k, pad_pitch * k)\n"
     "        } else {\n"
     "            (pad_yaw, pad_pitch)\n"
     "        };\n"
     "        yaw += pad_yaw;\n        pitch += pad_pitch;\n"
     "        #[cfg(target_os = \"android\")]\n"
     "        {\n"
     "            let (touch_yaw, touch_pitch) = crate::android::take_look();\n"
     "            yaw += touch_yaw;\n"
     "            pitch += touch_pitch;\n"
     "        }\n",
     "touch look")

# 7. console.rs: the mod menu's trickshot commands go to the server.
edit("crates/client/src/console.rs",
     '    "where",\n];\n',
     '    "where",\n'
     '    // Legacy 4 mod menu (server side: crates/server/src/legacy4.rs).\n'
     '    "l4save",\n    "l4load",\n    "l4noclip",\n    "l4god",\n    "l4bothere",\n    "l4botfreeze",\n    "l4botmove",\n    "l4explosive",\n    "l4unlock",\n];\n',
     "trickshot commands sent to the server")

# 8. Server: the trickshot commands themselves.
shutil.copy(legacy4 / "src" / "server_legacy4.rs", "crates/server/src/legacy4.rs")
edit("crates/server/src/lib.rs", "pub mod vote;\n", "pub mod vote;\npub mod legacy4;\n", "server legacy4 module")
edit("crates/server/src/server.rs",
     '            Some("where") => self.game.where_am_i(slot),\n',
     '            Some("where") => self.game.where_am_i(slot),\n'
     '            // Legacy 4 mod menu: save/load for everyone, the rest for the host (loopback).\n'
     '            Some(v) if v.starts_with("l4") => {\n'
     '                let text = self.game.legacy4_command(slot, v, loopback, &argv);\n'
     '                if !text.is_empty() {\n'
     '                    self.game.send(\n'
     '                        Dest::Client(slot),\n'
     '                        ServerCmd::Print {\n'
     '                            kind: PrintKind::Normal,\n'
     '                            text: format!("{text}\\n"),\n'
     '                        },\n'
     '                    );\n'
     '                }\n'
     '            }\n',
     "server trickshot commands")

# 9. input/mod.rs: a profile's config_mp.cfg starts with `unbindall` (the stock ones do), which wiped the controller
#    binds the touch buttons and the pad use. On Android they come back after the profile, unless the player bound
#    that button to something else. And a controller works whatever the window focus says.
edit("crates/client/src/input/mod.rs",
     "        self.config_path = save;\n        self.dirty = false;\n    }\n",
     "        #[cfg(target_os = \"android\")]\n"
     "        for line in include_str!(\"extra_binds.cfg\").lines().filter(|l| l.starts_with(\"bind pad_\")) {\n"
     "            let key = line.split_whitespace().nth(1).unwrap_or(\"\");\n"
     "            if !self.binds.contains_key(key) {\n"
     "                self.exec_line(line);\n"
     "            }\n"
     "        }\n"
     "        self.config_path = save;\n        self.dirty = false;\n    }\n",
     "controller binds back after the profile")
edit("crates/client/src/input/mod.rs",
     "            if self.focused && self.cvars.bool(\"in_gamepad\") {\n",
     "            if (self.focused || cfg!(target_os = \"android\")) && self.cvars.bool(\"in_gamepad\") {\n",
     "controller ignores window focus on Android")

# 10. fire.rs: explosive bullets (mod menu, VIP): a small blast where each bullet lands, the shooter spared.
edit("crates/server/src/fire.rs",
     "            self.bullet_impact_event(shooter, weapon, &h);\n            if !h.damageable {\n",
     "            self.bullet_impact_event(shooter, weapon, &h);\n"
     "            if crate::legacy4::explosive(shooter) && !self.client(shooter).is_some_and(|c| c.bot) {\n"
     "                let blast = crate::combat::Blast {\n"
     "                    origin: h.point,\n"
     "                    radius: 260.0,\n"
     "                    inner: 300.0,\n"
     "                    outer: 160.0,\n"
     "                    attacker: Some(shooter),\n"
     "                    inflictor: Some(shooter),\n"
     "                    cone: None,\n"
     "                    ignore: Some(shooter),\n"
     "                    skip_clients: false,\n"
     "                    mean: h.mean,\n"
     "                    weapon: u32::from(weapon),\n"
     "                };\n"
     "                self.blast(vm, &blast);\n"
     "            }\n"
     "            if !h.damageable {\n",
     "explosive bullets")

# 11. shell.rs: UNLOCK ALL (mod menu). The unlocks are where the game's own table says: every item of
#     mp/statstable.csv (column 1 = its stat): perks and items (150..298, bit 0 = unlocked), weapons (3000 and up:
#     bit 0 and every attachment bit), plus Create a Class and the class slots (profile::unlocked_stats) and the top
#     rank. The same stats go to the server of the game (`l4unlock`), whose own copy would undo them otherwise.
edit("crates/client/src/shell.rs",
     "                \"statset\" => {\n",
     "                \"l4unlockall\" => {\n"
     "                    let mut wanted: Vec<(usize, i32)> = crate::profile::unlocked_stats()\n"
     "                        .iter()\n"
     "                        .enumerate()\n"
     "                        .filter(|(i, v)| **v != 0 && *i != 2301)\n"
     "                        .map(|(i, v)| (i, *v))\n"
     "                        .collect();\n"
     "                    let (mut items, mut weapons, mut other) = (0, 0, Vec::new());\n"
     "                    if let Some(t) = ui.assets.table(\"mp/statstable.csv\") {\n"
     "                        let cols = t.column_count as usize;\n"
     "                        for r in 0..t.row_count as usize {\n"
     "                            let cell = |c: usize| {\n"
     "                                t.values.get(r * cols + c).and_then(|v| v.as_deref()).unwrap_or(\"\")\n"
     "                            };\n"
     "                            let Ok(stat) = cell(1).trim().parse::<usize>() else { continue };\n"
     "                            match stat {\n"
     "                                150..=199 | 250..=298 => {\n"
     "                                    wanted.push((stat, 1));\n"
     "                                    items += 1;\n"
     "                                }\n"
     "                                3000..=3999 => {\n"
     "                                    wanted.push((stat, 0x7fff));\n"
     "                                    weapons += 1;\n"
     "                                }\n"
     "                                _ => other.push(stat),\n"
     "                            }\n"
     "                        }\n"
     "                    }\n"
     "                    tracing::info!(items, weapons, ?other, \"legacy4: unlock all from mp/statstable.csv\");\n"
     "                    let mut pairs = Vec::new();\n"
     "                    for (i, v) in wanted {\n"
     "                        if let Some(s) = self.st.stats.get_mut(i) {\n"
     "                            let new = if i >= 2000 {\n"
     "                                *s | v\n"
     "                            } else if *s & 3 == 0 {\n"
     "                                (*s).max(v)\n"
     "                            } else {\n"
     "                                *s\n"
     "                            };\n"
     "                            *s = new;\n"
     "                            pairs.push((i, new));\n"
     "                        }\n"
     "                    }\n"
     "                    if let Some(xp) = self.st.stats.get_mut(2301) {\n"
     "                        *xp = (*xp).max(1_000_000);\n"
     "                        pairs.push((2301, *xp));\n"
     "                    }\n"
     "                    for chunk in pairs.chunks(20) {\n"
     "                        let list: Vec<String> = chunk.iter().map(|(i, v)| format!(\"{i} {v}\")).collect();\n"
     "                        self.st.actions.push(Action::Console(format!(\"l4unlock {}\", list.join(\" \"))));\n"
     "                    }\n"
     "                    self.st.log(&format!(\"Unlocked {items} items and {weapons} weapons, top rank.\"));\n"
     "                }\n"
     "                \"statset\" => {\n",
     "unlock all")

# 12. combat.rs: god mode (mod menu, VIP): a player in it takes no damage at all (bullets, blasts, falls).
edit("crates/server/src/combat.rs",
     "    pub fn g_damage(&mut self, vm: &mut Vm, target: u16, d: Damage) {\n        let Some(t) = self.ent(target) else { return };\n",
     "    pub fn g_damage(&mut self, vm: &mut Vm, target: u16, d: Damage) {\n        let Some(t) = self.ent(target) else { return };\n"
     "        // Never a bot: the list is by client slot, and a bot can take a slot a player had before.\n"
     "        if t.kind == EntKind::Client\n"
     "            && crate::legacy4::god(target)\n"
     "            && !self.client(target).is_some_and(|c| c.bot)\n"
     "        {\n"
     "            return;\n"
     "        }\n",
     "god mode")

# 13. console.rs: UNLOCK ALL is the client's own command; the console refused it as unknown.
edit("crates/client/src/console.rs",
     '    "actionslot",\n',
     '    "actionslot",\n    // Legacy 4 mod menu: UNLOCK ALL (shell.rs).\n    "l4unlockall",\n',
     "unlock all known to the console")

# 14. render/texture.rs: images replaced by name (Android: the Legacy 4 menu background, android.rs menu_image).
#     Also for images a zone carries inline, which no IWD can cover.
tex = "crates/render/src/texture.rs"
edit(tex,
     "use std::sync::Arc;\n",
     "use std::sync::Arc;\n\n"
     "/// Legacy 4: images replaced by name. `make` builds the replacement IWI file, given a reader of the stock images.\n"
     "pub struct ImageOverride {\n"
     "    pub names: &'static [&'static str],\n"
     "    pub make: fn(&str, &dyn Fn(&str) -> Option<Vec<u8>>) -> Option<Vec<u8>>,\n"
     "}\n\n"
     "/// Set once at start (the Android build); empty everywhere else.\n"
     "pub static IMAGE_OVERRIDE: std::sync::OnceLock<ImageOverride> = std::sync::OnceLock::new();\n\n"
     "fn overridden(name: &str) -> bool {\n"
     "    IMAGE_OVERRIDE\n"
     "        .get()\n"
     "        .is_some_and(|o| o.names.iter().any(|n| n.eq_ignore_ascii_case(name)))\n"
     "}\n",
     "image override hook")
edit(tex,
     "            Some(d) if !d.data.is_empty() => load_inline(gpu, img),\n",
     "            Some(d) if !d.data.is_empty() && !overridden(name) => load_inline(gpu, img),\n",
     "override beats inline images")
edit(tex,
     "        let image = match iwi::load_picmip(vfs, name, picmip) {\n",
     "        let replaced = if overridden(name) {\n"
     "            let read = |n: &str| vfs.read(&format!(\"images/{n}.iwi\")).ok().flatten();\n"
     "            IMAGE_OVERRIDE\n"
     "                .get()\n"
     "                .and_then(|o| (o.make)(name, &read))\n"
     "                .and_then(|b| iwi::Image::parse_picmip(&b, 0).ok())\n"
     "        } else {\n"
     "            None\n"
     "        };\n"
     "        let image = match replaced.map(Ok).unwrap_or_else(|| iwi::load_picmip(vfs, name, picmip)) {\n",
     "override in the loader")

# 15. netplay.rs: the weapon switch cancel (pressing weapnext twice). The request for a weapon was dropped as soon as
#     that weapon was in hand, which it still is while it goes down: the second press asked for it back and was
#     forgotten at once, so the switch went through. The request now lasts until the drop is over.
edit("crates/client/src/netplay.rs",
     "        if self.want_weapon == Some(snap.ps.weapon as u16) {\n            self.want_weapon = None;\n        }\n",
     "        if self.want_weapon == Some(snap.ps.weapon as u16)\n"
     "            && !matches!(\n"
     "                snap.ps.weapon_state,\n"
     "                sim::pm::weapon_state::DROPPING | sim::pm::weapon_state::DROPPING_QUICK\n"
     "            )\n"
     "        {\n            self.want_weapon = None;\n        }\n",
     "weapon switch cancel")

# 16. Diagnostics for the bot that would not die (journal.txt): every bullet that reaches a bot, and why the server
#     refused to hurt a client, if it did.
edit("crates/server/src/fire.rs",
     "            if !h.damageable {\n                continue;\n            }\n",
     "            if self.client(h.target).is_some_and(|c| c.bot) {\n"
     "                tracing::info!(\n"
     "                    shooter,\n"
     "                    bot = h.target,\n"
     "                    damage = h.damage,\n"
     "                    damageable = h.damageable,\n"
     "                    \"legacy4: a bullet reached a bot\"\n"
     "                );\n"
     "            }\n"
     "            if !h.damageable {\n                continue;\n            }\n",
     "log bullets on bots")
edit("crates/server/src/combat.rs",
     "            || c.ps.pm_type == PmType::Dead\n        {\n            return;\n        }\n",
     "            || c.ps.pm_type == PmType::Dead\n        {\n"
     "            if c.bot {\n"
     "                tracing::info!(\n"
     "                    bot = target,\n"
     "                    takes,\n"
     "                    damage = d.damage,\n"
     "                    noclip = c.noclip,\n"
     "                    ufo = c.ufo,\n"
     "                    dead = c.ps.pm_type == PmType::Dead,\n"
     "                    \"legacy4: damage to a bot refused\"\n"
     "                );\n"
     "            }\n"
     "            return;\n        }\n",
     "log refused damage to bots")

# 17. Final killcam (Search and Destroy), all in the server: legacy4.rs `legacy4_on_kill` / `legacy4_final_frame`.
edit("crates/server/src/combat.rs",
     "        c.ps.weapon_state = 0;\n        self.stats.deaths += 1;\n",
     "        c.ps.weapon_state = 0;\n        self.stats.deaths += 1;\n"
     "        self.legacy4_on_kill(n, d.attacker, d.time_offset);\n",
     "final killcam: the round's last kill")
edit("crates/server/src/server.rs",
     "        let t = Instant::now();\n        let mut gone_lines = Vec::new();\n",
     "        let span = self.net.as_ref().map_or(0, |n| n.archive_span(self.svs_time));\n"
     "        self.game.legacy4_final_frame(span);\n"
     "        let t = Instant::now();\n        let mut gone_lines = Vec::new();\n",
     "final killcam: each frame")
edit("crates/server/src/server.rs",
     "        if self.game.level.exit_requested {\n",
     "        if self.game.level.exit_requested && !crate::legacy4::final_killcam_active() {\n",
     "final killcam: the match end waits")
edit("crates/server/src/server.rs",
     "        if self.game.level.map_restart_requested {\n",
     "        if self.game.level.map_restart_requested && !crate::legacy4::final_killcam_active() {\n",
     "final killcam: the next round waits")
# The killer watches his own kill: a person may follow himself while a replay (archive time) is set.
edit("crates/server/src/netsv.rs",
     ".filter(|t| *t != slot && c.session == Session::Spectator);",
     ".filter(|t| (*t != slot || c.archive_time > 0.0) && c.session == Session::Spectator);",
     "final killcam: the killer watches himself")

# 18. netplay.rs: double weapnext at once (faster than the server answers): the first press asks for the other
#     weapon; the second comes before the drop has begun, so it is kept and turned into the cancel as soon as the
#     server starts the drop.
edit("crates/client/src/netplay.rs",
     "        let inv = PlayerWeapons::from_words(&snap.inv);\n"
     "        let cur = self.selected_weapon(&inv, ps);\n"
     "        if let Some(w) = inv.cycle_primary(&self.weapons, cur, self.latest_primary, forward, false)\n"
     "        {\n"
     "            self.select_weapon(w);\n"
     "        }\n",
     "        let held = ps.weapon as u16;\n"
     "        let dropping = matches!(\n"
     "            ps.weapon_state,\n"
     "            sim::pm::weapon_state::DROPPING | sim::pm::weapon_state::DROPPING_QUICK\n"
     "        );\n"
     "        let inv = PlayerWeapons::from_words(&snap.inv);\n"
     "        let cur = self.selected_weapon(&inv, ps);\n"
     "        if let Some(w) = inv.cycle_primary(&self.weapons, cur, self.latest_primary, forward, false)\n"
     "        {\n"
     "            if w == held && self.want_weapon.is_some_and(|x| x != held) && !dropping {\n"
     "                L4_SWITCH_CANCEL.store(true, std::sync::atomic::Ordering::Relaxed);\n"
     "            } else {\n"
     "                L4_SWITCH_CANCEL.store(false, std::sync::atomic::Ordering::Relaxed);\n"
     "                self.select_weapon(w);\n"
     "            }\n"
     "        }\n",
     "double weapnext: remember the cancel")
edit("crates/client/src/netplay.rs",
     "        // A weapon the player asked for is asked for until it is held; after that the scripts decide.\n",
     "        if L4_SWITCH_CANCEL.load(std::sync::atomic::Ordering::Relaxed) {\n"
     "            if self.want_weapon.is_none() {\n"
     "                L4_SWITCH_CANCEL.store(false, std::sync::atomic::Ordering::Relaxed);\n"
     "            } else if matches!(\n"
     "                snap.ps.weapon_state,\n"
     "                sim::pm::weapon_state::DROPPING | sim::pm::weapon_state::DROPPING_QUICK\n"
     "            ) {\n"
     "                L4_SWITCH_CANCEL.store(false, std::sync::atomic::Ordering::Relaxed);\n"
     "                self.want_weapon = Some(snap.ps.weapon as u16);\n"
     "            }\n"
     "        }\n"
     "        // A weapon the player asked for is asked for until it is held; after that the scripts decide.\n",
     "double weapnext: cancel once the drop begins")
with open("crates/client/src/netplay.rs", "a") as f:
    f.write("\n/// Legacy 4: a second weapnext came before the server began the switch; it cancels it once the drop starts.\n"
            "static L4_SWITCH_CANCEL: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);\n")
print("ok  double weapnext flag")

# 19. bullet.rs: sniper wallbangs everywhere (trickshots): sniper rifles (rifle class, large penetration) go through
#     8 times the thickness, and through some of what the table makes solid.
edit("crates/server/src/bullet.rs",
     "        let depth_of = |surface: usize| p.penetration.depth_for(p.info, surface, p.perks, p.params);\n",
     "        let sniper = p.info.penetrate_type == PenetrateType::Large\n"
     "            && p.info.weap_class == sim::weapon::WeaponClass::Rifle;\n"
     "        let depth_of = |surface: usize| {\n"
     "            let d = p.penetration.depth_for(p.info, surface, p.perks, p.params);\n"
     "            if sniper { (d * 8.0).max(48.0) } else { d }\n"
     "        };\n",
     "sniper wallbangs")
print("patch_client: all changes applied")
