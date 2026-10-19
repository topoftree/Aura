# Aura

Run supported Windows PC games on your Android device, with a library and launcher designed for touch and controller navigation.

## Download

[**Download Aura 0.1.83 APK**](https://github.com/topoftree/Aura/releases/download/v0.1.83/Aura-0.1.83-arm64-release.apk) · [GitHub Release](https://github.com/topoftree/Aura/releases/tag/v0.1.83) · [Checksums](releases/v0.1.83/SHA256SUMS) · [All releases](https://github.com/topoftree/Aura/releases)

Current version: **0.1.83**, Android version code **84**. Download `Aura-0.1.83-arm64-release.apk` from the release's **Assets** section. It is a signed production APK, not a debug build.

For the modified Winlator engine's source and the materials to recombine it with Aura 0.1.83, see [Source and recombination](SOURCE_CODE.md). Aura's independent application source remains private. Use the matching `relink/v0.1.83` directory; the older 0.1.82 kit remains available for that release. Clone with Git LFS to obtain the full object JARs.

## Overview

Aura brings game installation, launch settings, controller navigation and supported Windows compatibility components together in one Android app. Games run locally on your device; performance depends on the game, runtime, graphics driver and phone.

This is Aura's public repository for APK distribution, documentation, release notes, issue tracking and required third-party source/recombination materials. Aura's independent application source is maintained privately. Third-party software retains its own licenses and recipient rights; see [Copyright](COPYRIGHT.md) and [Third-party notices](THIRD_PARTY_NOTICES.md).

## Main features

| Area | What you can do |
| --- | --- |
| Launcher | Open installed games, return to a retained session, or close a running session. |
| Library | Browse your Steam library, install available Windows game content, manage installed games and check for updates. Local game imports are also supported. |
| Game management | Set a game's render resolution, frame limit, stability mode, ESync, shader compiler threads and Steam-client launch preference. |
| Controllers | Navigate with touch or supported controllers and configure controller mappings. |
| Updates and Messages | Review game and supported component updates, then confirm installation. Brief side notifications leave a persistent entry in Messages. |
| Runtime settings | Manage containers and the available graphics, audio and compatibility options. |
| Optional diagnostics | Enable Debug mode when troubleshooting to collect session reports and export available diagnostic files. |

## Built on open-source projects

Aura builds on **Winlator** for Android-side Windows runtime integration, **JavaSteam** for Steam protocol access, and **FEXCore** for CPU translation. Wine/Proton and the graphics components below provide the Windows and rendering compatibility layers. Credit for these foundations belongs to their upstream authors and contributors.

| Project / bundled version | Role in Aura | Upstream |
| --- | --- | --- |
| Winlator 11.2 | Runtime and container integration, X server/display, input and Android/native integration; Aura includes modifications to this engine. | [Winlator](https://github.com/brunodev85/winlator-app/tree/3981d86efa4f333b2a34a7da8b6521476cd8c8b9) |
| JavaSteam 1.8.0 | Steam authentication, account/library access and content-download protocol integration. Some games also need the official Steam client running inside Wine. | [JavaSteam](https://github.com/Longi94/JavaSteam) |
| Proton Wine 11.0-2 / ARM64EC | Windows API compatibility and the Wine environment used to start Windows programs. | [Proton Wine build project](https://github.com/The412Banner/proton-wine) · [Wine](https://www.winehq.org/) |
| FEXCore 2610 | Translates supported x86/x86-64 code for the ARM64 runtime; it works alongside Wine rather than providing Windows APIs itself. | [FEX](https://github.com/FEX-Emu/FEX) |
| DXVK 2.7.1 / ARM64EC | Direct3D 8/9/10/11 translation to Vulkan where selected and supported. | [DXVK](https://github.com/doitsujin/dxvk) |
| VKD3D-Proton 3.0.1 / ARM64EC | Direct3D 12 translation to Vulkan. | [VKD3D-Proton](https://github.com/HansKristian-Work/vkd3d-proton) |
| Mesa Turnip and Zink | Adreno Vulkan support and OpenGL-on-Vulkan rendering; see the driver inventory below. | [Mesa](https://www.mesa3d.org/) |
| AdrenoTools / linkernsbypass | Loads supported alternate Android graphics drivers through the runtime's Bionic Wrapper integration. | [AdrenoTools](https://github.com/bylaws/libadrenotools) |
| SDL 2.32.10, ALSA/PulseAudio, GStreamer 1.28.0 and FFmpeg components | Controller, audio and media support, including Aura's runtime integration changes. | [SDL](https://github.com/libsdl-org/SDL) · [ALSA](https://www.alsa-project.org/) · [PulseAudio](https://www.freedesktop.org/wiki/Software/PulseAudio/) · [GStreamer](https://gstreamer.freedesktop.org/) · [FFmpeg](https://ffmpeg.org/) |
| Wine Mono 10.4.1 | Bundled .NET compatibility support for applicable Windows software. | [Wine Mono](https://github.com/wine-mono/wine-mono) |
| Kotlin, AndroidX / Compose / WorkManager and Material | Android application, interface and background-work foundations. | [Kotlin](https://kotlinlang.org/) · [AndroidX](https://developer.android.com/jetpack/androidx) |

The current runtime uses FEXCore with ARM64EC Wine. Version labels here describe the bundled baseline; installed component updates and the selected game configuration can differ. The [third-party notices](THIRD_PARTY_NOTICES.md) include additional dependencies, copyright information and license terms. Upstream links provide attribution; matching source and recombination obligations are addressed separately in [Source availability](SOURCE_CODE.md).

## Graphics drivers and rendering layers

Direct3D translation, CPU translation and the phone's GPU driver perform different jobs. The current ARM64EC graphics path uses **Turnip or Bionic Wrapper** for Vulkan and **Zink** for OpenGL. Driver selection depends on the device and game; these components are not all used simultaneously.

| Component / option | Included baseline and purpose | Compatibility / origin |
| --- | --- | --- |
| Mesa Turnip · Bionic | `25.2.0-devel`, revision `6defe2244c`; built-in Vulkan driver for Adreno. | Requires an Adreno GPU; [Mesa](https://www.mesa3d.org/). |
| Mesa Turnip 26.0 R8 | Optional bundled community package, `26.0.0 - R8`, by KIMCHI. | Offered for Adreno 6xx/7xx on 4 KB-page devices; a development comparison option. [Community builds](https://github.com/K11MCH1/AdrenoToolsDrivers). |
| Mesa Turnip 25.3 PROFILED | Optional bundled `25.3.0 - new autotuner` package by KIMCHI. | Experimental; offered for Adreno 6xx/7xx on 4 KB-page devices, excluding Adreno 710/720. [Community builds](https://github.com/K11MCH1/AdrenoToolsDrivers). |
| Qualcomm Adreno v863.1 | Optional bundled community-extracted/adapted package credited to Hugo / StevenMX. | Offered for Adreno 6xx/7xx on 4 KB-page devices. It contains **proprietary Qualcomm components** and is not an official Qualcomm distribution. [Package origin](https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/v863.1_a7xx). |
| System Vulkan driver | Uses the driver supplied by the phone's Android/vendor firmware through Bionic Wrapper. | Its version, license and supported features depend on the device; Aura does not supply a replacement system driver through this option. |
| Mesa Zink · Bionic | `24.3.0-devel`; implements OpenGL over Vulkan. | Uses the chosen Vulkan backend; it is a rendering layer, not a separate hardware driver package. |
| Bionic Wrapper / AdrenoTools | Connects the Wine Vulkan runtime to a supported selected driver or the system driver. | Driver loading and device/vendor ABI support must succeed; selecting a name in Settings alone does not prove that the requested driver loaded. |

Mesa's open-source terms do not apply to Qualcomm's proprietary components. Redistribution permission for the bundled Qualcomm package remains an outstanding item documented in [Source availability](SOURCE_CODE.md). Driver options and build checks do not establish support for every GPU, game or 16 KB-page device.

## Android requirements

| Requirement | Current release |
| --- | --- |
| Minimum Android version | Android 14 (API 34) |
| CPU architecture | ARM64 (`arm64-v8a`) |
| Application ID | `com.android.aura` |
| Distribution build | Signed release APK with R8 optimization and resource shrinking |
| Storage | Space for Aura, its runtime, downloaded clients and your games; installations and updates need additional working space. |
| Accounts and content | Your own legally acquired games and any Steam or publisher accounts required by those games. |

Internet access is required for sign-in, downloads, update checks and games that use online services. Offline play depends on the game and its launchers.

The Android version alone does not establish compatibility. Graphics-driver support, available memory and device temperature affect which games run and how well they perform. Full compatibility with devices using 16 KB memory pages has not been verified; some bundled native components still have 4 KB alignment constraints.

## Installation and updates

1. Download the APK from the [official release page](https://github.com/topoftree/Aura/releases/tag/v0.1.83) and verify its checksum below.
2. Before updating Aura, fully exit any running game or Wine session.
3. Open the APK from your browser's downloads or Android file manager. If prompted, allow that app to install unknown apps, then return to the installer.
4. Install and open Aura. Complete any runtime or game-dependency setup requested by the app.
5. For Steam games, sign in with your own account, select a game in Library and install it. Open an installed game from Library or Launcher and complete any required client sign-in.

For an existing `com.android.aura` installation with a compatible signing certificate, install the newer APK over the existing app. Android checks the package identity, version and signature. If installation fails, retain the existing app and report the exact error rather than uninstalling as a first step.

Back up important saves and imported files before updating. Uninstalling Aura or clearing its app data can remove app-private games, containers and saves. Do not assume that a game supports cloud saves or that its latest save has synced.

## Game settings and Steam behavior

Open **Settings → Games Management** and choose a game to change its options. **Use defaults** inherits the applicable global or container setting. Fully exit and relaunch the game to apply launch-setting changes; resuming a background session keeps that session's settings.

- **Render resolution:** Lower resolutions reduce the amount of image data to render and transfer. A higher resolution may cost performance even when the total CPU or GPU reading is low.
- **Frame limit:** Sets Aura's per-game limit. **Unlimited** does not override a game's own limit, VSync, display refresh behavior or a runtime bottleneck.
- **ESync:** Changes Wine's thread and process synchronization. It is separate from the game's **vertical sync (VSync)**, which concerns frame presentation and tearing. ESync affects the whole Wine session, including Steam, and is not a guaranteed FPS boost. If enabling it prevents a required client from starting, turn it off and fully restart the session.
- **Shader compiler threads:** Controls supported shader-compilation concurrency. Increasing it is not a general fix for low game FPS.
- **Skip Steam client:** A compatibility option for games that can start without the official client. Leave it off for games that require Steam. It does not grant ownership or bypass a game's authentication or DRM.

When **Skip Steam client** is off, Aura starts or reuses Steam and waits for its client session before launching the game. Once the selected game's visible window appears, Aura attempts to minimize the Steam main window once. It keeps the client and Steamworks session running. Restoring Steam manually does not cause repeated automatic minimization. Games that require another launcher, such as Ubisoft Connect, still need that launcher.

## Game and component updates

For an installed Steam game, open its Library details and choose **Update** to check for available changes. Local imports do not use Steam's update service.

With daily update checking enabled, Aura checks installed games and the supported component catalog when a usable session is available. Background checks depend on Android scheduling and an available Steam session; they are not guaranteed while the app is stopped or signed out. Older installations without a recorded version may offer verification instead of claiming to be current.

Available updates appear in a side notification for about **three seconds** and remain in **Messages**. Open the message and confirm to start the update. Notifications do not start downloads automatically and are suppressed during a running game. Game files are not replaced while that game is running; an interrupted update can require completion before the game starts again.

Only components offered by Aura's update catalog can be updated separately. Pinned Wine, FEX, DXVK and VKD3D components are delivered through Aura releases. Steam and Ubisoft Connect manage their own client updates.

## Debug mode and troubleshooting

Keep **Settings → Advance → Debug mode** off for ordinary play. In 0.1.83, this switch controls optional Aura log collection, session/performance sampling and native execution diagnostics. Essential launch checks, session cleanup and runtime safeguards continue to operate.

To collect a new report, enable Debug mode before starting a fresh game session. After reproducing the issue, use the available diagnostic export in Settings. You do not need to force a crash to create a report. Turn Debug mode off after troubleshooting; already saved reports can remain available. Android, Steam and games can create their own logs independently of this switch.

Low total CPU or GPU usage alone does not identify a fault. A busy individual thread, synchronization waits, image transfers, memory pressure or thermal throttling can limit frame rate. Compare the same scene and resolution when reporting performance, and distinguish game FPS from a counter observed on Aura's settings screen.

## Current release and limitations

Version **0.1.83** adds an offline Winlator LGPL 2.1 license page, source/recombination links and clearer attribution in **Settings → Version**. It retains the previous game-management and diagnostic features without changing the game runtime or graphics dependencies. See the [detailed release notes](releases/v0.1.83/README.md).

Build and automated checks do not establish device compatibility or measured FPS gains. This release does not claim to resolve Stardew Valley's reported performance issue or all Steam/ESync startup problems. Games with unsupported graphics features, anti-cheat, DRM or launcher requirements may not work. Workshop, private beta branches and cloud-save behavior should not be assumed to have full support.

Aura is under active development. Use the release notes and issue tracker for known behavior and report reproducible problems with the details below.

## APK verification

SHA-256 for the current **Release asset**, `Aura-0.1.83-arm64-release.apk`:

```text
10becdd7562bdb6df3a2e9c94a501750c48a9bb60698605982dee85fe7e58190
```

In a shell with `sha256sum`, run:

```sh
sha256sum Aura-0.1.83-arm64-release.apk
```

Compare the result with the value above. On Windows PowerShell, use `Get-FileHash .\Aura-0.1.83-arm64-release.apk -Algorithm SHA256`. A matching hash verifies the published bytes; obtain the APK and checksum from the official repository. Android separately checks the APK signature during installation.

The older repository download, [`Aura-0.1.82.apk`](https://github.com/topoftree/Aura/raw/refs/heads/main/apk/Aura-0.1.82.apk), remains available. It is a separately packaged 0.1.82 build with a **different checksum**. Match the exact filename against [SHA256SUMS](releases/v0.1.82/SHA256SUMS); do not use one file's hash to verify the other.

## Bug reports and feature requests

Search [existing issues](https://github.com/topoftree/Aura/issues) before [opening a report](https://github.com/topoftree/Aura/issues/new). Include:

- Aura version, device model and Android version.
- Game name, graphics driver, resolution and relevant per-game settings.
- Steps to reproduce, expected behavior and what happened instead.
- For performance reports, FPS in a named scene and whether changing resolution changes it.
- A relevant screenshot or diagnostic export, when available.

Issues are public. Remove passwords, tokens, account identifiers and other personal information before sharing logs or screenshots. Do not attach copyrighted game files. For feature requests, describe the task and the behavior you want; a request does not imply a delivery commitment.

## Source availability and third-party software

The [versioned Winlator source and recombination kit](relink/v0.1.83/README.md) contains the modified engine's Java/Kotlin and native source, resources, build instructions and independently obfuscated Aura application objects. It allows building a combined APK with an edited engine without the private application source or production signing key. The LGPL-covered engine and modifications remain open under their applicable terms. Aura's independent application object code has the specific recombination permissions in the kit's [notice](relink/v0.1.83/NOTICE.md); its Kotlin business/UI source is not published.

Bundled third-party components, including modified components, retain their own licenses. Modification for personal use and reverse engineering for debugging LGPL-library modifications are expressly permitted; Aura's copyright notice does not restrict the libraries' own rights. See [SOURCE_CODE.md](SOURCE_CODE.md) for the scope, validation and outstanding work, [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for attributions, and [LICENSES/](LICENSES/) for retained license texts. The Winlator kit does not complete every separate runtime component's source obligations or certify the whole APK's legal compliance.

## Copyright

Copyright © 2026 Aura. All rights reserved for Aura's original material, subject to applicable law and the separate rights of third-party contributors. See [COPYRIGHT.md](COPYRIGHT.md) for the scope of this notice.

Third-party software, game artwork, names and trademarks belong to their respective owners. Aura is not affiliated with or endorsed by Valve, Ubisoft, Microsoft or the other projects and publishers mentioned here.
