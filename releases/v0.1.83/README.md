# Aura 0.1.83 — Winlator License and Source Availability

Aura 0.1.83 (Android version code **84**) supports ARM64 devices running **Android 14 or later**. The application ID is `com.android.aura`.

## What's new

- Added **Winlator open-source license** under **Settings → Version**, with the complete GNU LGPL 2.1 text available offline.
- Added a link to the corresponding engine source and recombination instructions, together with the permissions to modify the combined work for personal use and reverse engineer it to debug modifications to LGPL components.
- Corrected the version-page attribution to identify Aura's **modified Winlator engine**, and distinguish it from the independent Aura application and other third-party components.
- Expanded the public README with the projects, runtime components and graphics drivers used by Aura, including their sources and device limitations.

This release does not upgrade runtime dependencies or change game execution, ESync, presentation or performance settings. It does not claim measured FPS improvements, fixes for all Steam/ESync startup issues, or newly verified game compatibility.

## Installation and upgrade

1. Download `Aura-0.1.83-arm64-release.apk` from this release and verify its SHA-256 below.
2. Exit running games and Wine sessions before updating. Back up important saves.
3. The APK retains the existing signing identity and can update an older Aura installation with a matching certificate. If installation fails, keep the existing app and record the error. Uninstalling or clearing app data may remove private games, containers and saves.
4. After installation, open **Settings → Version → Winlator open-source license** to read the offline license and open the source/recombination information.

## Open-source foundations and graphics components

Aura uses **Winlator 11.2** for Android runtime integration and **JavaSteam 1.8.0** for Steam protocol access. The Windows runtime combines **Proton Wine 11.0-2 / ARM64EC**, **FEXCore 2610**, **DXVK 2.7.1** and **VKD3D-Proton 3.0.1**.

The current graphics path uses Turnip or Bionic Wrapper for Vulkan, with Zink for OpenGL. The bundled baseline includes Mesa Turnip `25.2.0-devel (6defe2244c)`, Zink `24.3.0-devel`, and optional community Turnip 26.0 R8, Turnip 25.3 PROFILED and Qualcomm Adreno v863.1 packages. The system Vulkan option uses the driver provided by the device's firmware.

These components serve different roles and are not all active simultaneously. Optional community drivers have GPU and memory-page-size restrictions; the PROFILED package excludes Adreno 710/720. The Qualcomm package contains proprietary components and is not an open-source Mesa driver. See the [README](https://github.com/topoftree/Aura/blob/main/README.md) and [third-party notices](https://github.com/topoftree/Aura/blob/main/THIRD_PARTY_NOTICES.md) for the component inventory, attribution and limitations.

## How the Winlator portion is delivered under LGPL 2.1

For the Winlator Android engine, Aura uses the corresponding-source and application-object recombination approach described in **LGPL 2.1, section 6(a)**. Keeping independent Aura application source private depends on a valid boundary between independent application code and LGPL-derived code. Renaming packages, translating code or obfuscating an implementation does not make library-derived code independently proprietary.

The matching materials are available in the [versioned kit](https://github.com/topoftree/Aura/tree/v0.1.83/relink/v0.1.83) and in this release's `Aura-0.1.83-relink.zip`. The ZIP contains the actual object JARs and does not require Git LFS.

The current repository index is [Winlator source and relinking kits](https://github.com/topoftree/Aura/tree/main/winlator-source-and-relink). The folder on the current branch has been renamed from `relink/` to `winlator-source-and-relink/`; the pinned release links and ZIP download above retain their original paths and contents.

| Requirement or right | Materials and permissions supplied |
| --- | --- |
| Modified Winlator engine | Editable Java/Kotlin, C/C++, Android resources and build definitions, including Aura's modifications to the derived engine. Upstream attribution, copyright notices, the LGPL text and other original file-level licenses are retained. |
| Modification record | A pinned upstream revision and a file-level record identifying unchanged, modified and added files. |
| Independent Aura application | Pre-obfuscated object code, dependency objects, compiled resources and other build inputs needed to recombine the application with a modified engine. Independent Aura Kotlin UI/business source, private Git history, private name mappings and production signing keys are not published. |
| Modification and recombination | Standalone instructions to edit the LGPL engine, build a combined APK and sign it with the recipient's own key, without access to Aura's private repository or private mapping. |
| Debugging modified libraries | Express permission to modify the combined program for personal use and reverse engineer it to debug modifications to LGPL components. Aura's general copyright notice or EULA cannot override these permissions. |
| In-app notices | A visible Winlator license entry, the complete offline LGPL text and a source/recombination link are included in this APK. |
| Version correspondence | The 0.1.83 kit uses its own matching application objects and resources. The existing 0.1.82 kit remains available for that version and is not substituted for the new materials. |

The LGPL-covered implementation remains editable source. Obfuscating independent application objects only increases the effort needed to inspect them; it does not prevent decompilation or remove the rights above. Previously published or downloaded 0.1.82 objects are not erased by this release.

Recipients can sign modified APKs with their own keys. Android generally prevents an APK signed with a different key from updating the official installation. Use a separate test device/profile or back up data before installing a differently signed version. The production signing key is not required for building or signing a modified copy.

### Separate obligations that remain outstanding

The delivery above covers the **Winlator Android engine and its recombination with independent Aura objects**. It is not a declaration that the entire APK is fully compliant. Wine/Proton, statically included ntsync, Wine Mono, media/audio libraries and other runtime components have separate corresponding-source, build, recombination and potentially installation-information obligations. Redistribution permission for the proprietary Qualcomm package also remains to be established. Upstream links and license copies do not replace those requirements.

See [SOURCE_CODE.md](https://github.com/topoftree/Aura/blob/main/SOURCE_CODE.md), the versioned kit's [NOTICE.md](https://github.com/topoftree/Aura/blob/v0.1.83/relink/v0.1.83/NOTICE.md), and the [third-party notices](https://github.com/topoftree/Aura/blob/main/THIRD_PARTY_NOTICES.md) for the scope and remaining work.

## Build and validation

The archived production-build records for 0.1.83 confirm:

- A successful Gradle release build with R8 optimization, obfuscation and resource shrinking; package-version, signature and ZIP-alignment checks passed.
- **33 targeted regression tests across 3 suites** passed. Lint reported no errors or newly introduced issues.
- The APK's offline Winlator LGPL text matches the engine license byte-for-byte; the new notices and links are included.
- Runtime assets and native libraries are unchanged from 0.1.82. All **274 indexed native payload hashes** passed verification; eight matching native symbol sets remain privately archived.

Additional checks on the 0.1.83 public recombination kit passed:

- All **1,103 application classes** were retained, with **1,085 renamed**. Source-file, line-number, local-variable, SMAP, Kotlin and coroutine debug metadata were removed. The application object JAR contains no Winlator engine classes.
- Both original and transformed objects passed verification/loading for all 1,103 classes, **21 reflective serializer lookups** and **48 behavior assertions**.
- Baseline and modified-engine Gradle `relinkRelease` builds succeeded. Temporary Java, native-code and resource changes appeared in the combined test APK. Those temporary changes are absent from the published engine source.
- **7,781 original resource IDs** remained unchanged. Checks passed for **274 native payload hashes**, **24 Steam reflection contracts**, and **12 JNI fields across 8 classes**.
- A modified test APK signed with an independent, temporary recipient key passed signature and ZIP-alignment checks. The production key was not used for these tests or included in public materials.
- The kit includes **956 individual SHA-256 checksums**. See [VALIDATION.md](https://github.com/topoftree/Aura/blob/v0.1.83/relink/v0.1.83/VALIDATION.md) for the results and limits.

No Android device upgrade/install test, license-dialog interaction, real Steam login or game execution was performed for this delivery. Host build success does not establish compatibility with every GPU, game or 16 KB-page device.

## Assets and checksums

| Asset | Purpose |
| --- | --- |
| `Aura-0.1.83-arm64-release.apk` | Officially signed ARM64 application package. |
| `Aura-0.1.83-relink.zip` | Matching Winlator engine source, pre-obfuscated application objects, dependencies and build instructions. It excludes independent Aura Kotlin UI/business source, private mappings and signing keys. |
| `SHA256SUMS.txt` | SHA-256 checksums for the APK and recombination ZIP. |

Download all three files into the same directory and run:

```sh
sha256sum -c SHA256SUMS.txt
```

After extracting the ZIP, enter `Aura-0.1.83-relink/` and run `sha256sum -c SHA256SUMS` to verify the kit's individual files. Follow its README to modify, rebuild and sign the combined application.

Official APK: `Aura-0.1.83-arm64-release.apk`

Size: **374,793,571 bytes**

SHA-256:

```text
10becdd7562bdb6df3a2e9c94a501750c48a9bb60698605982dee85fe7e58190
```

Project names and trademarks belong to their respective owners. Aura is not affiliated with or officially endorsed by Valve, Qualcomm or the upstream projects named here.
