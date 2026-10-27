# Recombine Aura 0.1.149 with a modified Winlator engine

This kit provides editable Winlator-derived engine source and independently obfuscated Aura application objects. It builds a combined APK without the private Aura application source, private name mapping or production signing key. See [NOTICE.md](NOTICE.md), [PRIVACY.md](PRIVACY.md) and [VALIDATION.md](VALIDATION.md).

## Matching release

Application ID: `com.android.aura`; version name **0.1.149**; version code **150**.

Use [Aura-0.1.149-arm64-release.apk](https://github.com/topoftree/Aura/releases/download/v0.1.149/Aura-0.1.149-arm64-release.apk), SHA-256:

```text
530f734c3ea7f8480b147f67f30e32472c9bdf0804b2b5a65f0fefd1c32b3e26
```

This version has its own engine source, application objects, dependency/resource classes, merged manifest, compiled resources, stable IDs and R8 rules. It does not reuse the old 0.1.118 application or engine binary. Unchanged dependency non-value resource inputs are retained, with all current merged application/value resources overlaid. Build-path metadata has been normalized.

## Obtain and build

The kit is supplied in this versioned directory on **main**, not as a Release attachment. This version uses ordinary Git objects, including JARs; its files do not require Git LFS downloads. Older kits retain their historical LFS requirements. Clone while skipping those older LFS objects:

```sh
GIT_LFS_SKIP_SMUDGE=1 git clone https://github.com/topoftree/Aura.git
cd Aura
cd winlator-source-and-relink/v0.1.149
sha256sum -c SHA256SUMS
```

The 0.1.149 directory contains real JAR bytes in both a clone and source ZIP; verify the checksums before building. Requirements: Linux, Python 3.11+, JDK 21, Android SDK `android-37.2`, build-tools `37.0.0`, NDK `28.2.13676358`, CMake `3.22.1` and Gradle 9.6. The build resolves AGP 9.4.1 and Kotlin 2.3.21 from the configured standard repositories. Accept SDK licenses and allow several GiB of working space. Set `JAVA_HOME` and `ANDROID_HOME` to your installed tools; optionally set a writable `GRADLE_USER_HOME`.

```sh
python3 relink.py bootstrap --base-apk /absolute/path/Aura-0.1.149-arm64-release.apk
# Edit engine/src/main/java, engine/src/main/cpp or engine/src/main/res.
./gradlew relinkRelease \
  -PauraBaseApk=/absolute/path/Aura-0.1.149-arm64-release.apk
```

Bootstrap verifies the original APK hash and extracts already-distributed runtime assets/supporting native libraries into ignored directories. It preserves existing files, allowing subsequent modifications. Use a new checkout for a clean baseline. It does not install anything on an Android device.

The task rebuilds engine Kotlin/Java/C/C++, regenerates resources with stable IDs, runs R8 optimization/obfuscation and resource shrinking, packages the APK and checks ZIP alignment. It never reads the private application repository. Changed/new engine resources overlay the compiled application/dependency fallback. New engine resources are supported.

Outputs:

```text
build/relink/Aura-0.1.149-relinked-unsigned.apk
build/relink/build-report.json
```

Sign with a key you control:

```sh
"$ANDROID_HOME/build-tools/37.0.0/apksigner" sign \
  --ks /private/path/your-key.jks \
  --out Aura-0.1.149-relinked.apk \
  build/relink/Aura-0.1.149-relinked-unsigned.apk
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose Aura-0.1.149-relinked.apk
```

Keep signing passwords out of public files/history. A different signature normally cannot update the official installation in place. Use a separate test device/profile or a fresh installation after backing up important data. Uninstalling can erase private games, containers and saves. The application ID stays unchanged because runtime paths use it.

## Runtime and verification scope

Winlator JNI libraries are rebuilt. Other original runtime binaries are reused unless you modify the extracted inputs. Their editable manifest/index entries must be updated when indexed payloads change; the kit does not require production-key approval for those changes. [Runtime source/build materials](../../runtime-source/v0.1.149/README.md) provide separately licensed source and build inputs for additional components. Extracting a binary alone does not provide that component's source.

Application objects have been renamed and stripped of debug/Kotlin metadata. The final build still performs full R8 optimization. A rebuild need not be byte-identical to the official APK. Modified-engine host tests and temporary-key signing are recorded in [VALIDATION.md](VALIDATION.md); Android hardware/game compatibility is a separate test.
