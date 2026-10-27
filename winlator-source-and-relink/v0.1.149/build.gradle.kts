plugins {
    id("com.android.library") version "9.4.1" apply false
    // Keep the original build's Kotlin compiler resolution, even though the
    // proprietary application's Compose/serialization code is precompiled.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
}
tasks.register<Exec>("relinkRelease") {
    dependsOn(":engine:assembleRelease")
    val base = providers.gradleProperty("auraBaseApk")
    doFirst { require(base.isPresent) { "Pass -PauraBaseApk=/absolute/path/to/Aura-0.1.149.apk" } }
    commandLine("python3", "relink.py", "assemble", "--base-apk", base.getOrElse("MISSING"))
}
