// Legacy Mobile: only gathers the Steam libraries the APK needs (JavaSteam and what it uses) into build/deps.
// The APK itself is built by the workflow with javac and d8, like the rest of the app.
plugins {
    java
}

repositories {
    mavenCentral()
}

val aar by configurations.creating

dependencies {
    implementation("in.dragonbra:javasteam:1.8.0")
    implementation("in.dragonbra:javasteam-depotdownloader:1.8.0")
    // Steam chunk decompression: LZMA (pure Java) and Zstandard (native, Android build below).
    implementation("org.tukaani:xz:1.11")
    // QR code drawing for the Steam sign-in.
    implementation("com.google.zxing:core:3.5.3")
    aar("com.github.luben:zstd-jni:1.5.7-6@aar")
}

tasks.register<Copy>("collectDeps") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("deps"))
}

tasks.register<Copy>("collectAar") {
    from(aar.map { zipTree(it) })
    into(layout.buildDirectory.dir("aar"))
}
