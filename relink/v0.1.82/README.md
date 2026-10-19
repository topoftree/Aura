# Recombine Aura 0.1.82 with a modified Winlator engine

This directory provides editable Winlator engine source plus the pre-optimization
application objects needed to build a modified combined APK. It does not require
access to Aura's private source repository or production signing configuration.
Read [NOTICE.md](NOTICE.md) for scope and permissions and [VALIDATION.md](VALIDATION.md)
for the actual verification results and limitations.

## Matching release

Application ID: `com.android.aura`; version name `0.1.82`; version code `83`.
Use one of these exact public APKs as the source of already-distributed runtime
assets and supporting native binaries:

| Base APK | SHA-256 |
| --- | --- |
| [Release asset](https://github.com/topoftree/Aura/releases/download/v0.1.82/Aura-0.1.82-arm64-release.apk) | `a6890e736ddf5dab59cce02db99dfcdc1b5534b2edbc9b3849286c94d6ee8f8c` |
| [Repository mirror](https://github.com/topoftree/Aura/raw/refs/heads/main/apk/Aura-0.1.82.apk) | `537795a198a9cf3316a49dd34e64d74add6d55bb809c826b930a23ddc1b319ba` |

The two APKs have the same application payload; their VCS metadata/signature
packaging differs. This kit is version-specific. It must not be assumed to match
a later Aura version. Original source bytes are unchanged since these builds,
apart from the documented publication notices and standalone build configuration.

## Build on Linux

Required: Python 3.11+, JDK 21, Android SDK platform `android-37.2`, build-tools
`37.0.0`, NDK `28.2.13676358`, CMake `3.22.1`, and the Gradle wrapper's pinned
Gradle 9.6. The build resolves Android Gradle Plugin 9.4.1 and Kotlin 2.3.21 from
their normal repositories. Allow several GiB of disk and memory for the native
build, dependencies and R8. Accept the Android SDK licenses yourself.

Clone this public repository, ensure its Git LFS files are downloaded, and change
into `relink/v0.1.82`. Set `JAVA_HOME` and `ANDROID_HOME` to your installed tools.
If needed, set `GRADLE_USER_HOME` to a writable dependency-cache directory.

```sh
python3 relink.py bootstrap --base-apk /absolute/path/Aura-0.1.82-arm64-release.apk

# Edit engine/src/main/java, engine/src/main/cpp, or engine/src/main/res here.
./gradlew relinkRelease \
  -PauraBaseApk=/absolute/path/Aura-0.1.82-arm64-release.apk
```

`bootstrap` verifies the original APK hash and extracts its assets and supporting
native libraries. These inputs are in ignored directories. It does not overwrite
existing files, so it preserves subsequent runtime edits. Changing the base APK
does not silently replace a modified runtime. Use a new checkout for a clean
baseline. Android resource IDs are retained for the precompiled application;
the engine's resource class is regenerated, allowing added engine resources.
The original compiled resource fallback preserves entries still referenced by
the application; changing engine resources overlays that fallback.

The release task compiles the engine's Kotlin, Java and C/C++, regenerates Android
resources, runs R8 optimization/obfuscation and resource shrinking, recombines the
APK and checks ZIP alignment. It never reads the private Aura project.

Output:

```text
build/relink/Aura-0.1.82-relinked-unsigned.apk
build/relink/build-report.json
```

The output has no production signature. Sign it with a key you control using the
Android SDK's `apksigner`; keep passwords out of shell history and public files:

```sh
"$ANDROID_HOME/build-tools/37.0.0/apksigner" sign \
  --ks /private/path/your-key.jks \
  --out Aura-0.1.82-relinked.apk \
  build/relink/Aura-0.1.82-relinked-unsigned.apk
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose Aura-0.1.82-relinked.apk
```

Android normally rejects an update over the official installation when the
signing keys differ. Use a separate test device/profile or a fresh installation.
Back up games, containers and saves before any uninstall; uninstalling can erase
private app data. This kit does not uninstall, install or erase anything on a
device. Its application ID stays `com.android.aura` because runtime paths use it.

## Runtime modifications

The Winlator JNI libraries are rebuilt from the supplied source. The original
APK's other runtime libraries are reused. Their own corresponding-source
requirements are not satisfied merely by extracting their binaries.

The extracted runtime manifests and hash indexes are editable build inputs;
there is no production-key check in this script. A change to an indexed runtime
payload must update the matching manifest/index/archive references as well.
For changes to a Java/Android/JNI Winlator library, its newly built AAR is used
directly. Do not mistake a failed runtime hash check for a signing requirement.

Compiled object code can be inspected and reverse engineered subject to the
permissions in `NOTICE.md`; this kit does not promise bytecode secrecy.
