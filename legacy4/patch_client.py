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
     "mod wire;\n#[cfg(target_os = \"android\")]\nmod android;\n#[cfg(target_os = \"android\")]\nmod online;\n",
     "android module")
edit(main,
     "    let args: Vec<String> = std::env::args().skip(1).collect();\n",
     "    #[cfg(target_os = \"android\")]\n"
     "    let args: Vec<String> = android::args().unwrap_or_default();\n"
     "    #[cfg(not(target_os = \"android\"))]\n"
     "    let args: Vec<String> = std::env::args().skip(1).collect();\n",
     "Android arguments")
shutil.copy(legacy4 / "src" / "android.rs", "crates/client/src/android.rs")
shutil.copy(legacy4 / "src" / "online.rs", "crates/client/src/online.rs")
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
     "        // Paused (the app is in the background): no surface to draw on or resize.\n"
     "        #[cfg(target_os = \"android\")]\n"
     "        if crate::android::paused()\n"
     "            && matches!(ev, WindowEvent::RedrawRequested | WindowEvent::Resized(_))\n"
     "        {\n"
     "            return;\n"
     "        }\n"
     "        let Some(st) = self.st.as_mut() else { return };\n",
     "touch as mouse")
edit(app,
     "    fn user_event(&mut self, el: &ActiveEventLoop, ready: Ready) {\n",
     "    // Legacy 4: leaving the app (home button, another app) pauses the game: the window's surface goes with the\n"
     "    // window, and a new one is made from the new window when the player comes back (resumed).\n"
     "    #[cfg(target_os = \"android\")]\n"
     "    fn suspended(&mut self, _: &ActiveEventLoop) {\n"
     "        if let Some(st) = self.st.as_mut() {\n"
     "            let _ = st.input.save();\n"
     "            if let Some(r) = st.renderer.as_mut() {\n"
     "                record_gpu(&mut st.gpu_ms, r.flush_gpu_times());\n"
     "            }\n"
     "            st.surface = None;\n"
     "        }\n"
     "        crate::android::set_paused(true);\n"
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
     "                // UNLOCK ALL stays: every weapon, attachment and camo of the menus (stat 3000 + weapon number,\n"
     "                // bit 0 = unlocked), whatever a server or an old profile says.\n"
     "                // A text field of the game in use: the activity opens the phone's keyboard.\n"
     "                crate::android::set_text_field(sh.ui.l4_editing());\n"
     "                if crate::android::unlock_all() {\n"
     "                    for s in sh.st.stats.iter_mut().skip(3000).take(90) {\n"
     "                        *s |= 0x7fff;\n"
     "                    }\n"
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
     "                    // Every weapon the stock menus test (stat 3000 + weapon number, 3000..3089: bit 0 = unlocked,\n"
     "                    // bits 1..14 = attachments and camos), whatever the table gave.\n"
     "                    for i in 3000..=3089 {\n"
     "                        wanted.push((i, 0x7fff));\n"
     "                    }\n"
     "                    // From now on the client keeps them unlocked (app.rs, every frame).\n"
     "                    #[cfg(target_os = \"android\")]\n"
     "                    crate::android::set_unlock_all();\n"
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
     "            && !l4_cancel_wait\n"
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

# 18. Double weapnext / double Y (the switch cancel), client and server.
#     netplay.rs: the second press asks at once for the weapon in hand again, and that request is kept (not dropped
#     because the weapon is in hand) until the server shows the drop, then as long as the drop lasts (step 15).
edit("crates/client/src/netplay.rs",
     "        let inv = PlayerWeapons::from_words(&snap.inv);\n"
     "        let cur = self.selected_weapon(&inv, ps);\n"
     "        if let Some(w) = inv.cycle_primary(&self.weapons, cur, self.latest_primary, forward, false)\n"
     "        {\n"
     "            self.select_weapon(w);\n"
     "        }\n",
     "        let held = ps.weapon as u16;\n"
     "        let inv = PlayerWeapons::from_words(&snap.inv);\n"
     "        let cur = self.selected_weapon(&inv, ps);\n"
     "        if let Some(w) = inv.cycle_primary(&self.weapons, cur, self.latest_primary, forward, false)\n"
     "        {\n"
     "            let cancel = w == held && self.want_weapon.is_some_and(|x| x != held);\n"
     "            *L4_SWITCH_CANCEL.lock().unwrap_or_else(|e| e.into_inner()) =\n"
     "                cancel.then(std::time::Instant::now);\n"
     "            self.select_weapon(w);\n"
     "        }\n",
     "double weapnext: ask for the held weapon at once")
edit("crates/client/src/netplay.rs",
     "        // A weapon the player asked for is asked for until it is held; after that the scripts decide.\n",
     "        // Legacy 4: a switch cancel waits (at most 0.7 s) for the server to start the drop it cancels.\n"
     "        let l4_cancel_wait = {\n"
     "            let mut pending = L4_SWITCH_CANCEL.lock().unwrap_or_else(|e| e.into_inner());\n"
     "            let dropping = matches!(\n"
     "                snap.ps.weapon_state,\n"
     "                sim::pm::weapon_state::DROPPING | sim::pm::weapon_state::DROPPING_QUICK\n"
     "            );\n"
     "            match *pending {\n"
     "                Some(t) if !dropping && t.elapsed().as_millis() < 700 => true,\n"
     "                _ => {\n"
     "                    *pending = None;\n"
     "                    false\n"
     "                }\n"
     "            }\n"
     "        };\n"
     "        // A weapon the player asked for is asked for until it is held; after that the scripts decide.\n",
     "double weapnext: the cancel waits for the drop")
with open("crates/client/src/netplay.rs", "a") as f:
    f.write("\n/// Legacy 4: when the switch cancel (a second weapnext back to the weapon in hand) was asked for.\n"
            "static L4_SWITCH_CANCEL: std::sync::Mutex<Option<std::time::Instant>> = std::sync::Mutex::new(None);\n")
print("ok  double weapnext flag")
#     server client.rs: the person asks for the weapon in hand while it goes down: the switch is cancelled, as the
#     original's PM_Weapon_CheckForChangeWeapon does (the gun comes back up). The server kept the earlier request
#     (`selected`) and finished the switch anyway, then switched back.
edit("crates/server/src/client.rs",
     "            c.inv.select(want);\n        }\n        c.old_cmd = c.cmd;\n",
     "            c.inv.select(want);\n        }\n"
     "        if want != 0\n"
     "            && want == c.ps.weapon as u16\n"
     "            && c.inv.selected() != 0\n"
     "            && c.inv.selected() != want\n"
     "            && c.inv.has(want)\n"
     "            && c.ps.pm_type < PmType::Dead\n"
     "            && matches!(\n"
     "                c.ps.weapon_state,\n"
     "                pm::weapon_state::DROPPING | pm::weapon_state::DROPPING_QUICK\n"
     "            )\n"
     "        {\n"
     "            c.inv.select(want);\n"
     "        }\n"
     "        c.old_cmd = c.cmd;\n",
     "double weapnext: the server cancels the switch")

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
# 20. archive.rs / netsv.rs: killcams (and the final one, slowed down) replay the recorded frames with the bodies and
#     the view between the two frames around the moment shown, instead of one server frame after another (which
#     stood still then jumped, three times a frame when slowed).
edit("crates/server/src/archive.rs",
     "    /// The newest frame at or before `time`, else the oldest one.\n",
     "    /// Legacy 4: the frame at `time`: positions, angles and view between the two recorded frames around it.\n"
     "    pub fn at_smooth(&self, time: i32) -> Option<Frame> {\n"
     "        let i = self.frames.partition_point(|f| f.time <= time);\n"
     "        let a = self.frames.get(i.saturating_sub(1))?;\n"
     "        let b = match self.frames.get(i) {\n"
     "            Some(b) if i > 0 => b,\n"
     "            _ => return Some(a.clone()),\n"
     "        };\n"
     "        let f = ((time - a.time) as f32 / (b.time - a.time).max(1) as f32).clamp(0.0, 1.0);\n"
     "        let ang = |x: f32, y: f32| x + ((y - x + 180.0).rem_euclid(360.0) - 180.0) * f;\n"
     "        let near = |p: [f32; 3], q: [f32; 3]| (0..3).map(|k| (q[k] - p[k]).powi(2)).sum::<f32>() < 65536.0;\n"
     "        let mut out = a.clone();\n"
     "        out.time = time;\n"
     "        for e in &mut out.entities {\n"
     "            let Ok(k) = b.entities.binary_search_by_key(&e.state.number, |x| x.state.number) else {\n"
     "                continue;\n"
     "            };\n"
     "            let (s, t) = (&mut e.state, &b.entities[k].state);\n"
     "            if s.etype == t.etype && s.client == t.client && near(s.origin, t.origin) {\n"
     "                for k in 0..3 {\n"
     "                    s.origin[k] += (t.origin[k] - s.origin[k]) * f;\n"
     "                    s.angles[k] = ang(s.angles[k], t.angles[k]);\n"
     "                }\n"
     "            }\n"
     "        }\n"
     "        for (p, q) in out.players.iter_mut().zip(&b.players) {\n"
     "            if let (Some(p), Some(q)) = (p.as_mut(), q.as_ref())\n"
     "                && near(p.ps.origin, q.ps.origin)\n"
     "            {\n"
     "                for k in 0..3 {\n"
     "                    p.ps.origin[k] += (q.ps.origin[k] - p.ps.origin[k]) * f;\n"
     "                    p.ps.viewangles[k] = ang(p.ps.viewangles[k], q.ps.viewangles[k]);\n"
     "                }\n"
     "                p.ps.view_height_current += (q.ps.view_height_current - p.ps.view_height_current) * f;\n"
     "                p.ps.weapon_pos_frac += (q.ps.weapon_pos_frac - p.ps.weapon_pos_frac) * f;\n"
     "            }\n"
     "        }\n"
     "        Some(out)\n"
     "    }\n\n"
     "    /// The newest frame at or before `time`, else the oldest one.\n",
     "smooth replay frames")
edit("crates/server/src/netsv.rs",
     "            .then(|| self.archive.at(server_time - archive_ms))\n",
     "            .then(|| self.archive.at_smooth(server_time - archive_ms))\n",
     "killcams use the smooth frames")
edit("crates/server/src/netsv.rs",
     "        if let Some(f) = past {\n",
     "        if let Some(f) = &past {\n",
     "killcams use the smooth frames (borrow)")

# 21. netplay.rs: the view of a watched player (killcam, final killcam, spectating) between the two snapshots around
#     the moment the other bodies are drawn at, instead of the newest snapshot as it came: it moves at the screen's
#     rate (60 images a second and more), not the server's.
edit("crates/net/src/view.rs",
     "    /// Forgets the history (a new level restarts the server clock).\n",
     "    /// Legacy 4: the two snapshots around `time` and how far between them it is (0 to 1).\n"
     "    pub fn l4_around(&self, time: i32) -> Option<(&Snapshot, &Snapshot, f32)> {\n"
     "        let i = self.snaps.iter().position(|(_, s)| s.server_time > time)?;\n"
     "        let a = &self.snaps.get(i.checked_sub(1)?)?.1;\n"
     "        let b = &self.snaps.get(i)?.1;\n"
     "        let span = (b.server_time - a.server_time).max(1) as f32;\n"
     "        Some((a, b, ((time - a.server_time) as f32 / span).clamp(0.0, 1.0)))\n"
     "    }\n\n"
     "    /// Forgets the history (a new level restarts the server clock).\n",
     "snapshots around a time")
edit("crates/client/src/netplay.rs",
     "            let ps = snap.ps.clone();\n            self.kick.clear();\n",
     "            let ps = l4_follow_ps(&self.net.snaps, &snap, st);\n"
     "            // Legacy 4: a slowed replay (the final killcam) slows the bodies, the gun and the effects with it.\n"
     "            let dt = dt * l4_replay_rate(&snap);\n"
     "            self.kick.clear();\n",
     "smooth followed view")
with open("crates/client/src/netplay.rs", "a") as f:
    f.write(r"""
/// Legacy 4: the watched player's state at the moment the other bodies are drawn at (`INTERP_DELAY_MS` back), its
/// position, view and aim between the two snapshots around it; the newest one when they do not show the same player.
fn l4_follow_ps(
    snaps: &net::view::SnapshotBuffer,
    latest: &net::snapshot::Snapshot,
    st: i32,
) -> PlayerState {
    let Some((a, b, f)) = snaps.l4_around(st - net::view::INTERP_DELAY_MS) else {
        return latest.ps.clone();
    };
    let same = match (a.follow, b.follow) {
        (Some(x), Some(y)) => x.followed == y.followed && a.ps.client_num == b.ps.client_num,
        _ => false,
    };
    let jump: f32 = (0..3).map(|k| (b.ps.origin[k] - a.ps.origin[k]).powi(2)).sum();
    if !same || jump > 65536.0 {
        return latest.ps.clone();
    }
    let ang = |x: f32, y: f32| x + ((y - x + 180.0).rem_euclid(360.0) - 180.0) * f;
    let mut ps = b.ps.clone();
    for k in 0..3 {
        ps.origin[k] = a.ps.origin[k] + (b.ps.origin[k] - a.ps.origin[k]) * f;
        ps.viewangles[k] = ang(a.ps.viewangles[k], b.ps.viewangles[k]);
    }
    ps.view_height_current =
        a.ps.view_height_current + (b.ps.view_height_current - a.ps.view_height_current) * f;
    ps.weapon_pos_frac = a.ps.weapon_pos_frac + (b.ps.weapon_pos_frac - a.ps.weapon_pos_frac) * f;
    ps
}

/// Legacy 4: how fast the replay being watched runs (1 live and in a normal killcam, about 0.35 in the final
/// killcam's slow motion), read from how the snapshots' replay moment moves against their server time, eased.
fn l4_replay_rate(snap: &net::snapshot::Snapshot) -> f32 {
    static LAST: std::sync::Mutex<(i32, i32, f32)> = std::sync::Mutex::new((i32::MIN, 0, 1.0));
    let mut last = LAST.lock().unwrap_or_else(|e| e.into_inner());
    let archive = snap.follow.map_or(0, |f| f.archive_ms as i32);
    if archive <= 0 {
        *last = (i32::MIN, 0, 1.0);
        return 1.0;
    }
    let replay = snap.server_time - archive;
    let (t0, r0, mut rate) = *last;
    if snap.server_time == t0 {
        return rate;
    }
    if t0 != i32::MIN && snap.server_time > t0 {
        let measured = ((replay - r0) as f32 / (snap.server_time - t0) as f32).clamp(0.05, 1.0);
        rate += (measured - rate) * 0.5;
    }
    *last = (snap.server_time, replay, rate);
    rate
}
""")
print("ok  smooth followed view (function)")

# 22. ui/assets.rs: the killcam without the two red bands of the stock hud (menu "killcam": two white shaders tinted
#     dark red, top and bottom); the KILLCAM title stays.
edit("crates/client/src/ui/assets.rs",
     "            a.load_file(&path, \"mod\")?;\n        }\n        Ok(a)\n",
     "            a.load_file(&path, \"mod\")?;\n        }\n"
     "        match a.menus.get_mut(\"killcam\").map(Arc::get_mut) {\n"
     "            Some(Some(m)) => {\n"
     "                let before = m.items.len();\n"
     "                m.items.retain(|it| {\n"
     "                    let c = it.window.fore_color;\n"
     "                    !(it.window.background.is_some() && c[0] > 0.4 && c[0] < 0.6 && c[1] < 0.1 && c[2] < 0.1)\n"
     "                });\n"
     "                tracing::info!(removed = before - m.items.len(), \"legacy4: killcam red bands removed\");\n"
     "            }\n"
     "            Some(None) => tracing::warn!(\"legacy4: killcam menu shared, red bands kept\"),\n"
     "            None => {}\n"
     "        }\n"
     "        Ok(a)\n",
     "killcam without red bands")

# 23. input/mod.rs: `set <dvar> <words...>` keeps every word, as the original's Cmd_Set_f. Create a Class sets
#     `ui_primary_selected` to "Primary Weapon" and only lets a weapon be picked when it reads that; the second word
#     was dropped, so every primary weapon stayed grey.
edit("crates/client/src/input/mod.rs",
     "            (\"set\" | \"seta\" | \"sets\" | \"setu\", [n, v, ..]) => {\n"
     "                self.cvars.set(n, v, name == \"seta\");\n",
     "            (\"set\" | \"seta\" | \"sets\" | \"setu\", [n, v, rest @ ..]) => {\n"
     "                let value = std::iter::once(*v)\n"
     "                    .chain(rest.iter().copied())\n"
     "                    .collect::<Vec<&str>>()\n"
     "                    .join(\" \");\n"
     "                self.cvars.set(n, &value, name == \"seta\");\n",
     "set keeps every word")

# 24. ui/mod.rs: whether a text field is being typed in (Android: the phone's keyboard opens for it).
edit("crates/client/src/ui/mod.rs",
     "impl Ui {\n",
     "impl Ui {\n"
     "    /// Legacy 4: an edit field has the keys (a profile name, a password...).\n"
     "    pub fn l4_editing(&self) -> bool {\n"
     "        self.menus.iter().any(|m| m.items.iter().any(|it| it.editing))\n"
     "    }\n\n",
     "text field in use")

# 25. app.rs: back in the app after leaving it: a new surface from the new window, and the game goes on.
edit(app,
     "    fn resumed(&mut self, el: &ActiveEventLoop) {\n        if self.st.is_some() {\n            return;\n        }\n",
     "    fn resumed(&mut self, el: &ActiveEventLoop) {\n        if self.st.is_some() {\n"
     "            #[cfg(target_os = \"android\")]\n"
     "            if crate::android::paused()\n"
     "                && let Some(st) = self.st.as_mut()\n"
     "            {\n"
     "                match fresh_surface(st) {\n"
     "                    Ok(()) => {\n"
     "                        crate::android::set_paused(false);\n"
     "                        st.window.request_redraw();\n"
     "                        tracing::info!(\"legacy4: back in the app, new surface\");\n"
     "                    }\n"
     "                    Err(e) => tracing::error!(error = %e, \"legacy4: no surface after coming back\"),\n"
     "                }\n"
     "            }\n"
     "            return;\n        }\n",
     "back in the app")

# 26. shell.rs: online play. With no `cl_masters` set, the Internet list asks the Legacy 4 relay built into the app
#     (online.rs; nothing with lan.txt, LAN only).
edit("crates/client/src/shell.rs",
     "        let masters = self.dvar_get(\"cl_masters\");\n        if !self.st.servers.refresh(source, full, &masters) {\n",
     "        let masters = l4_masters(self.dvar_get(\"cl_masters\"));\n"
     "        tracing::info!(?source, masters = masters.as_str(), \"legacy4: server list refresh\");\n"
     "        if !self.st.servers.refresh(source, full, &masters) {\n",
     "Internet list asks the Legacy 4 relay")
edit("crates/client/src/shell.rs",
     "                    && self.dvar_get(\"cl_masters\").trim().is_empty()\n",
     "                    && l4_masters(self.dvar_get(\"cl_masters\")).trim().is_empty()\n",
     "Internet list: no master message")
with open("crates/client/src/shell.rs", "a") as f:
    f.write("""
/// Legacy 4: the masters the Internet list asks: `cl_masters`, else (Android) the Legacy 4 relay, unless `lan.txt`.
fn l4_masters(set: String) -> String {
    #[cfg(target_os = "android")]
    if set.trim().is_empty() {
        return crate::online::master();
    }
    set
}
""")
print("ok  Legacy 4 masters")

# 27. shell.rs: the join menu opens on the Internet list (the online matches); LAN is one click away (Source).
edit("crates/client/src/shell.rs",
     "    (\"ui_netSource\", \"0\"),\n",
     "    (\"ui_netSource\", \"1\"),\n",
     "join menu opens on Internet")

print("patch_client: all changes applied")
