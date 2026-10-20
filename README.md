# Aura

Run supported Windows PC games on Android, with a library and launcher designed for touch and controller navigation.

## Download

[**Download Aura 0.1.101 APK**](https://github.com/topoftree/Aura/releases/download/v0.1.101/Aura-0.1.101-arm64-release.apk) · [Release notes and assets](https://github.com/topoftree/Aura/releases/tag/v0.1.101) · [Checksums](releases/v0.1.101/SHA256SUMS) · [All releases](https://github.com/topoftree/Aura/releases)

**Android 14+ · ARM64 · version code 102 · `com.android.aura`**

The APK is a signed Gradle Release build with R8 optimization, obfuscation and resource shrinking. It uses the existing upgrade signing certificate. Fully exit any game before installing the update; keep the existing installation and app data.

The matching [Winlator source and relinking kit](winlator-source-and-relink/v0.1.101/README.md) and [runtime source/build materials](runtime-source/v0.1.101/README.md) are available separately. Use materials matching your APK version. Aura's independent application source remains private, subject to the scope in [Source availability](SOURCE_CODE.md).

## New since the previous public release

Aura 0.1.101 brings together the changes made since public version 0.1.83:

| Area | Added or improved |
| --- | --- |
| CPU optimization | Per-game **Default**, **Thread waiting only**, **x87 calculations only**, and **Both** choices for installed Steam and Local games. Existing Mirror's Edge choices migrate. |
| Mirror's Edge compatibility | Startup-time compatibility, display-mode handling, an empty Direct3D 9 image-copy fix, and a paired Wine wait-completion fix for the reported multi-second picture pauses. |
| Display scaling | Choose aspect-preserving fit or stretch in the game menu. Scaling follows the current game viewport. |
| Controller shortcuts | Double-press the Select-equivalent button for the game menu; hold it to return to Aura. In Aura, a single press focuses the running-game entry. |
| Loading hints | Transparent hints with solid icons and labels appropriate to the connected controller. Hidden on the debug loading screen. |
| Launcher and Library | Slightly rounded covers, selected-cover glow, larger full-width swipe actions aligned with the game title, and a raised running-game cover when focused. |
| Game menu | Compact floating menu, smaller corners, stronger elevation, concise labels and navigation that wraps between the first and last items. |
| Dependency setup | Transparent loading progress. Successful setup is remembered after dependency validation and sustained output from the selected game; relevant game or runtime changes trigger checking again. |
| Updates | A Settings **Updates** page groups game updates and built-in components/drivers. Check all installed Steam games and independently enable daily checks. Library results appear beside the Update title. |
| Storage | An Aura page shows internal capacity, game files, saves, caches, Aura/runtime files, Other used space and available space. Export recognized saves to ZIP, associate a save folder, or confirm deletion of recognized saves/caches. |
| Settings and diagnostics | Global Stability mode, dedicated Mirror's Edge/Ubisoft startup capture pages and separate VC++/Unity capture/export flows are removed. General diagnostics, VC++ installation results and DLL checks remain. |
| Open-source notice | A separate Winlator license page supports controller navigation and links to corresponding source materials. |

The earlier Mirror's Edge intermittent-pause repair was confirmed by the reporting user. This does not establish performance for all games or devices. The new 0.1.101 storage, update and dependency workflows passed host checks but have not yet been tested on a phone. See [detailed release notes](releases/v0.1.101/README.md).

## Getting started

1. Download the APK from the official release page and verify its checksum.
2. Fully exit any game/Wine session before updating Aura.
3. Open the APK from Android's file manager or browser downloads and install over the existing app.
4. Sign in to Steam to browse and install available Windows game content, or import an existing Local game.
5. Select the correct executable in the game's details, then start the game and complete required dependency or client setup.

Use your own legally acquired games and any accounts they require. Some titles need the official Steam client or another launcher inside Wine. **Skip Steam client** is for compatible games; it does not bypass ownership checks or DRM.

Back up important saves before updating. Uninstalling Aura or clearing app data can remove games, containers and saves. If an upgrade fails, retain the installed app and report the exact error rather than uninstalling as the first step.

## Controller controls

“Select” means the corresponding button identified for the connected controller.

| Context | Gesture | Result |
| --- | --- | --- |
| In a game | Single press | Forwarded to the game after the approximately 0.3-second double-press window. |
| In a game | Double press | Open or close Aura's floating menu. |
| In a game | Hold approximately 0.7 seconds | Return to Aura with the session paused. |
| In Aura with a retained game | Single press | Focus the running-game entry at the bottom of Launcher; confirm to resume. |
| Floating menu | Up on the first item / down on the last | Wrap to the opposite end. |

Ordinary loading screens show the menu and return gestures. Debug loading screens omit those hints.

## Game settings

Open **Settings → Games Management** and choose a game. Settings are saved per game. Fully exit and restart to apply launch options; resuming a retained session keeps its original settings.

- **CPU optimization:** Try the four modes independently. The x87 option reduces floating-point precision; suitability and performance vary. Thread-waiting optimization is separate from ESync.
- **ESync:** Changes Wine synchronization. It is separate from VSync, which concerns frame presentation. Neither setting guarantees higher FPS.
- **Render resolution / frame limit:** Choose values appropriate to the game and device. Aura's limit does not override the game's own VSync or frame limit.
- **Fit / stretch:** Fit preserves the viewport's aspect ratio; stretch fills the host display. Letterboxing drawn by the game itself can remain.
- **Mirror's Edge startup compatibility:** A targeted per-game option. The CPU selector is available to other games independently.

After changing Mirror's Edge PhysX settings, completely exit the session and relaunch from Aura if the game's internal restart causes flickering. This release does not claim a universal fix for that internal restart path.

## Dependencies, updates and storage

Dependency download/installation progress floats over the loading screen without a panel background. Completion is remembered after validation and sustained output from the selected game; a static startup poster or installer alone does not qualify. Later launches of that version skip the full scan. Game updates, relevant executable/dependency changes or container reconstruction invalidate the record. Ordinary graphics settings and saves do not by themselves count as a game update.

Open **Settings → Updates** for **Game updates** and **Built-in components and drivers**. Run a manual batch check or enable daily game checks. Checks do not automatically download updates, and game files are not replaced while the game is running. Background checks depend on Android scheduling and an available Steam session. Imported Local games have no universal update provider. Only catalog-supported components update separately; other pinned runtime changes arrive through APK updates.

**Settings → Storage** displays Aura's view of internal storage instead of opening Android's system storage manager. Space used outside Aura is grouped as **Other**. Recognized saves can be exported to ZIP or deleted after confirmation; dedicated save folders can be associated manually. Ambiguous shared folders are not deleted for a single game. Recognized caches can be cleared, but rebuilding them may temporarily cause longer loading or stutter. Save discovery does not cover every game's cloud, registry or external save format.

## Runtime foundations

| Project | Role |
| --- | --- |
| [Winlator 11.2](https://github.com/brunodev85/winlator-app/tree/3981d86efa4f333b2a34a7da8b6521476cd8c8b9) | Android runtime, display, input, container and native integration, with Aura modifications. |
| [Wine / Proton 11.0-2 ARM64EC](https://github.com/The412Banner/proton-wine) | Windows API compatibility, including the supplied Aura Wine patches. |
| [FEXCore 2610](https://github.com/FEX-Emu/FEX) | CPU translation alongside Wine. |
| [DXVK 2.7.1 ARM64EC](https://github.com/doitsujin/dxvk) | Direct3D translation; D3D9 includes Aura's empty-rectangle backport. |
| [VKD3D-Proton 3.0.1 ARM64EC](https://github.com/HansKristian-Work/vkd3d-proton) | Direct3D 12 translation. |
| [Mesa](https://www.mesa3d.org/) / [AdrenoTools](https://github.com/bylaws/libadrenotools) | Supported graphics drivers, OpenGL-on-Vulkan and Android driver-loading integration. |
| [JavaSteam 1.8.0](https://github.com/Longi94/JavaSteam) | Steam protocol integration for accounts, libraries and content. |
| [SDL](https://github.com/libsdl-org/SDL), [GStreamer](https://gstreamer.freedesktop.org/), [Wine Mono](https://github.com/wine-mono/wine-mono) and other libraries | Input, audio, media and .NET compatibility. |

Credit belongs to the respective upstream authors and contributors. Installed updates can differ from these bundled baselines. See [Third-party notices](THIRD_PARTY_NOTICES.md), [LICENSES](LICENSES/) and [Source availability](SOURCE_CODE.md) for individual terms and delivered materials.

## Diagnostics and compatibility

Keep **Settings → Advanced → Debug mode** off for ordinary play. To investigate a problem, enable it before a fresh session, reproduce the issue and use general game diagnostic capture/export. Turn it off after troubleshooting. Mirror's Edge automatic stall capture remains a separate optional per-game setting.

A visible window does not prove that a game has reached its menu. Low total CPU/GPU utilization alone does not identify a bottleneck. Individual threads, synchronization, shader compilation, memory pressure or thermal throttling can limit performance.

Host tests do not establish compatibility with every game, controller or device. Some third-party native libraries still need 16 KiB-page compatibility review. Unsupported graphics features, anti-cheat, DRM or launcher requirements can prevent a game from working. Workshop, private beta branches and cloud saves should not be assumed to have full support.

## APK verification

SHA-256 for `Aura-0.1.101-arm64-release.apk`:

```text
18b76257c253e5f441ccc579cc646a0e505b554f736c217c2c601406977a0af5
```

Run `sha256sum Aura-0.1.101-arm64-release.apk`, or PowerShell's `Get-FileHash -Algorithm SHA256`. Match the exact filename against [the release checksums](releases/v0.1.101/SHA256SUMS). Android separately verifies the APK signature.

## Source availability

The [0.1.101 Winlator kit](winlator-source-and-relink/v0.1.101/README.md) supplies editable LGPL-covered engine source, its modification record, build scripts, independently obfuscated application objects, resources and dependency objects. Users can modify the engine, recombine it with the application and sign their build with their own key.

Independent Aura Kotlin UI/business source, private obfuscation mappings, production signing material and user diagnostic archives are not included. Obfuscation does not prevent decompilation or restrict applicable modification and debugging rights.

[Runtime materials](runtime-source/v0.1.101/README.md) cover supplied Wine/ntsync changes and additional runtime build inputs. Other components retain separate obligations. The package is not a certification of the entire APK's legal compliance; [SOURCE_CODE.md](SOURCE_CODE.md) records coverage and outstanding items.

## Feedback and copyright

Search [existing issues](https://github.com/topoftree/Aura/issues) before [opening a report](https://github.com/topoftree/Aura/issues/new). Include the Aura/device/Android versions, game, settings, reproduction steps and expected versus actual behavior. For performance, name the scene and describe whether changing resolution helps.

Issues are public. Remove account identifiers, credentials and personal information from reports. Do not attach copyrighted game files.

Copyright © 2026 Aura for its original material, subject to third-party contributors' rights. See [Copyright](COPYRIGHT.md). Third-party software, artwork, names and trademarks belong to their owners. Aura is not affiliated with or endorsed by Valve, Ubisoft, Microsoft or the other projects and publishers named here.
