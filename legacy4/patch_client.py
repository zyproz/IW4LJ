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
print("patch_client: all changes applied")
