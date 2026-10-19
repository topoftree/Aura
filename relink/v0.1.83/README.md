# Recombine Aura 0.1.83 with a modified Winlator engine

This directory provides editable Winlator engine source plus independently
obfuscated application objects needed to build a modified combined APK. It does not require
access to Aura's private source repository or production signing configuration.
Read [NOTICE.md](NOTICE.md) for scope and permissions and [VALIDATION.md](VALIDATION.md)
for the actual verification results and limitations.
See [PRIVACY.md](PRIVACY.md) for the object transformation and its limits.

## Matching release

Application ID: `com.android.aura`; version name `0.1.83`; version code `84`.
Use this exact public APK as the source of already-distributed runtime assets and supporting native binaries:

| Base APK | SHA-256 |
| --- | --- |
| [Release asset](https://github.com/topoftree/Aura/releases/download/v0.1.83/Aura-0.1.83-arm64-release.apk) | `10becdd7562bdb6df3a2e9c94a501750c48a9bb60698605982dee85fe7e58190` |

This kit matches Aura 0.1.83 only. It uses that version's application objects,
merged manifest, stable resource IDs, compiled resource overlay and dependency
resource classes. The engine implementation and application dependencies are
unchanged from 0.1.82. Existing engine publication notices naming 0.1.82 describe
the same source bytes; they were retained along with the original upstream notices.
The compiled resource fallback retains unchanged dependency resources and overlays
the matching 0.1.83 merged app/value resources. No private app source is required.

The independent application objects have been renamed and stripped of debug and
Kotlin metadata. They are suitable inputs to the final combined release build;
they are not the original compiler output and do not promise bit-identical APKs.

## Build on Linux

Required: Python 3.11+, JDK 21, Android SDK platform `android-37.2`, build-tools
`37.0.0`, NDK `28.2.13676358`, CMake `3.22.1`, and the Gradle wrapper's pinned
Gradle 9.6. The build resolves Android Gradle Plugin 9.4.1 and Kotlin 2.3.21 from
their normal repositories. Allow several GiB of disk and memory for the native
build, dependencies and R8. Accept the Android SDK licenses yourself.

Clone this public repository and run these commands from its root to obtain the
object files rather than Git LFS pointers:

```sh
git lfs install --local
git lfs pull --include="relink/v0.1.83/objects/*.jar" --exclude=""
cd relink/v0.1.83
sha256sum -c SHA256SUMS
```

Git and Git LFS must be installed. Download the matching base APK separately.
Alternatively, download [`Aura-0.1.83-relink.zip`](https://github.com/topoftree/Aura/releases/download/v0.1.83/Aura-0.1.83-relink.zip)
from the same release, verify it against the release's `SHA256SUMS.txt`, extract
it, and enter `Aura-0.1.83-relink/`. That archive contains the actual object JARs,
so this route does not require Git LFS. Run `sha256sum -c SHA256SUMS` there too.

Set `JAVA_HOME` and `ANDROID_HOME` to your installed tools.
If needed, set `GRADLE_USER_HOME` to a writable dependency-cache directory.

```sh
python3 relink.py bootstrap --base-apk /absolute/path/Aura-0.1.83-arm64-release.apk

# Edit engine/src/main/java, engine/src/main/cpp, or engine/src/main/res here.
./gradlew relinkRelease \
  -PauraBaseApk=/absolute/path/Aura-0.1.83-arm64-release.apk
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
build/relink/Aura-0.1.83-relinked-unsigned.apk
build/relink/build-report.json
```

The output has no production signature. Sign it with a key you control using the
Android SDK's `apksigner`; keep passwords out of shell history and public files:

```sh
"$ANDROID_HOME/build-tools/37.0.0/apksigner" sign \
  --ks /private/path/your-key.jks \
  --out Aura-0.1.83-relinked.apk \
  build/relink/Aura-0.1.83-relinked-unsigned.apk
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose Aura-0.1.83-relinked.apk
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
