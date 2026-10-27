# Legacy Mobile: makes the Steam library jars ready for d8 and the APK.
# usage: python3 prepare_jars.py <jar folder> <aar folder> <out classes folder> <out resources folder>
# - classes: one cleaned copy of each jar (no META-INF, no module-info, no Java 9+ duplicates), for javac and d8
# - resources: the non-class files the libraries read at run time (ServiceLoader entries, okhttp data...),
#   placed at the root of the APK where Android's class loader finds them.
import pathlib
import sys
import zipfile

jars, aar, out_classes, out_res = (pathlib.Path(a) for a in sys.argv[1:5])
out_classes.mkdir(parents=True, exist_ok=True)
out_res.mkdir(parents=True, exist_ok=True)

inputs = sorted(jars.glob("*.jar"))
aar_classes = aar / "classes.jar"
if aar_classes.exists():
    inputs.append(aar_classes)

seen = set()
for jar in inputs:
    dest = out_classes / ("zstd-jni-classes.jar" if jar == aar_classes else jar.name)
    kept = 0
    with zipfile.ZipFile(jar) as src, zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            name = info.filename
            if info.is_dir():
                continue
            if name.endswith(".class"):
                if name.startswith("META-INF/") or name.endswith("module-info.class"):
                    continue
                if name in seen:
                    continue  # the same class in two jars: d8 refuses duplicates
                seen.add(name)
                dst.writestr(name, src.read(name))
                kept += 1
            else:
                if name.startswith("META-INF/") and not name.startswith("META-INF/services/"):
                    continue
                if name.endswith((".so", ".dll", ".dylib", ".jnilib")):
                    continue  # desktop native libraries; Android gets its own
                target = out_res / name
                target.parent.mkdir(parents=True, exist_ok=True)
                if name.startswith("META-INF/services/") and target.exists():
                    target.write_bytes(target.read_bytes().rstrip(b"\n") + b"\n" + src.read(name))
                else:
                    target.write_bytes(src.read(name))
    print(f"{dest.name}: {kept} classes")
