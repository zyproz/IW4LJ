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
     '    "l4save",\n    "l4load",\n    "l4noclip",\n    "l4god",\n    "l4bothere",\n    "l4botfreeze",\n    "l4botmove",\n    "l4explosive",\n];\n',
     "trickshot commands sent to the server")

# 8. Server: the trickshot commands themselves.
shutil.copy(legacy4 / "src" / "server_legacy4.rs", "crates/server/src/legacy4.rs")
edit("crates/server/src/lib.rs", "pub mod vote;\n", "pub mod vote;\npub mod legacy4;\n", "server legacy4 module")
edit("crates/server/src/server.rs",
     '            Some("where") => self.game.where_am_i(slot),\n',
     '            Some("where") => self.game.where_am_i(slot),\n'
     '            // Legacy 4 mod menu: save/load for everyone, the rest for the host (loopback).\n'
     '            Some(v) if v.starts_with("l4") => {\n'
     '                let text = self.game.legacy4_command(slot, v, loopback);\n'
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
     "            if crate::legacy4::explosive(shooter) {\n"
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

# 11. shell.rs: UNLOCK ALL (mod menu): the same unlocks the harness profile gets (profile::unlocked_stats): every
#     weapon, perk and attachment, Create a Class, and the top rank. Stats already set are kept.
edit("crates/client/src/shell.rs",
     "                \"statset\" => {\n",
     "                \"l4unlockall\" => {\n"
     "                    let all = crate::profile::unlocked_stats();\n"
     "                    for (i, v) in all.iter().enumerate() {\n"
     "                        if let Some(s) = self.st.stats.get_mut(i)\n"
     "                            && *s == 0\n"
     "                            && *v != 0\n"
     "                        {\n"
     "                            *s = *v;\n"
     "                        }\n"
     "                    }\n"
     "                    if let Some(xp) = self.st.stats.get_mut(2301) {\n"
     "                        *xp = (*xp).max(all[2301]);\n"
     "                    }\n"
     "                    self.st.log(\"Everything unlocked (rank, weapons, perks, attachments).\");\n"
     "                }\n"
     "                \"statset\" => {\n",
     "unlock all")

# 12. combat.rs: god mode (mod menu, VIP): a player in it takes no damage at all (bullets, blasts, falls).
edit("crates/server/src/combat.rs",
     "    pub fn g_damage(&mut self, vm: &mut Vm, target: u16, d: Damage) {\n        let Some(t) = self.ent(target) else { return };\n",
     "    pub fn g_damage(&mut self, vm: &mut Vm, target: u16, d: Damage) {\n        let Some(t) = self.ent(target) else { return };\n"
     "        if t.kind == EntKind::Client && crate::legacy4::god(target) {\n"
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
print("patch_client: all changes applied")
