<p align="center">
  <img src="assets/aura-icon.png" alt="Aura gaming mascot" width="240" />
</p>

# Aura

Run supported Windows PC games on Android, with a library and launcher designed for touch and controller navigation.

## Download

[**Download Aura 0.1.149 APK**](https://github.com/topoftree/Aura/releases/download/v0.1.149/Aura-0.1.149-arm64-release.apk) · [Release notes and assets](https://github.com/topoftree/Aura/releases/tag/v0.1.149) · [Checksums](releases/v0.1.149/SHA256SUMS) · [All releases](https://github.com/topoftree/Aura/releases)

**Android 14+ · ARM64 · version code 150 · `com.android.aura`**

The APK is a signed Gradle Release build with R8 optimization, obfuscation and resource shrinking. It uses the existing upgrade signing certificate. Fully exit any game before installing the update; keep the existing installation and app data.

The matching [Winlator source and relinking kit](winlator-source-and-relink/v0.1.149/README.md) and [runtime source/build materials](runtime-source/v0.1.149/README.md) are available separately. Use materials matching your APK version. Aura's independent application source remains private, subject to the scope in [Source availability](SOURCE_CODE.md).

## New since 0.1.118

[Complete cumulative release notes](releases/v0.1.149/README.md) cover all **31 versions from 0.1.119 through 0.1.149**, including changed defaults, removed settings and continuing compatibility limits.

| Area | Current changes |
| --- | --- |
| Translation | Compact code generation enabled by default per game, targeted Crysis 2 validation improvements, IR bookkeeping improvements, optional cache RAM buffers and capability diagnostics. Hardware TSO remains experimental; SVE remains disabled. |
| Graphics | Selective shared-memory copies, safer buffer ownership and wait scheduling, targeted Qualcomm compatibility, full-source Turnip 26.2.5 and selected-driver Shared GPU images for supported R8/26.2.5 configurations. |
| Optimization | Driver-specific availability and explanations in both menus, conservative defaults and per-game Restore defaults. Smart performance suggestions default off. |
| Mods and patches | Separate pages, SMAPI command entry and optional after-save commands, result/timing diagnostics, patch imports/backups and supported catalogs. Cuphead UltraWide links to the original author; no mirrored third-party DLL. |
| Interface | Transparent mascot, Manrope/Noto Sans SC, Phosphor focused-fill icons, balanced Kenney controller hints, morphing-line loaders, deeper/wallpaper-dynamic colors, readable wrapping game titles and wallpaper fades. |
| In-game menu | Right-side poster/title and coordinated loading/gameplay backdrop blur; continuous Gaussian fallback fixes separated blur bands. |
| Steam and files | Managed startup/readiness, native DNS fallback, certificate FD repair, clearer runtime upgrade errors and simpler controller-friendly file navigation. |
| Diagnostics | Bounded complete performance window before PC sampling; CPU/thread, JIT/cache, Vulkan, command ancestry, color, X11 and presentation/wait observations. Unknown evidence stays unknown. |

The official APK passed **217 fresh JVM tests and six Python tests**; Release Lint reports **0 errors and 97 warnings**. The matching [relink checks](winlator-source-and-relink/v0.1.149/VALIDATION.md) cover baseline and modified-engine builds plus recipient-key signing. Phone performance remains separate: original Qualcomm-driver Cuphead dark output and Ground Zeroes loading/device-loss behavior are still being investigated.

Release assets are the APK and checksum only. The matching source/relink/runtime materials live on **main** and use ordinary Git for this version.

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
| In a game | Double press | Focus a visible right-side notice; otherwise open or close Aura's floating menu. |
| In a game | Hold approximately 0.7 seconds | Return to Aura with the session paused. |
| Launcher selected cover | Single press | Open/close that cover’s action menu; Left or Back closes it. |
| Floating menu | Up on the first item / down on the last | Wrap to the opposite end. |

Ordinary loading screens show the mapped menu/return button on a controller outline for ten seconds. Debug loading screens omit that guide. Library uses mapped Y / triangle for its category or Local Add Game, and the header uses mapped X / square for search.

## Game settings

Open **Settings → Games Management** and choose a game. Settings are saved per game. Fully exit and restart to apply launch options; resuming a retained session keeps its original settings.

- **Optimization defaults:** Compact translation and pausing Aura background downloads/scans are on. Smart suggestions and experimental cache, RAM-buffer, hardware-TSO, shared-image and D3D11 batching options are off. Existing saved choices are preserved. Every option has a focused explanation of purpose, tradeoffs, availability and restart requirements.
- **Restore defaults:** Resets only this game's Optimization settings, preserving resolution, frame limit, compatibility, patches and other games. CPU/driver changes need a full restart; resuming does not apply them.
- **Driver options:** Shared GPU images is selectable for supported R8/26.2.5 selections and still needs successful runtime buffer import/synchronization. The three Turnip 26.2.5 tuning options require that driver. Unavailable settings are disabled with a reason. Generic CPU/cache controls remain separate.
- **Cache RAM buffer:** Optional 32/64 MiB choices use additional memory to reduce disk-cache reading/processing. They require translation caching, are mutually exclusive and can be skipped for low memory or incompatible modes. They do not guarantee higher FPS.
- **Resolution / frame limit:** Screen settings changes the current Steam/Local container default; explicit per-game choices take priority. Aura's limiter does not remove a game's own frame target or VSync.
- **ESync / x87 / thread waiting:** Distinct options with different compatibility/performance tradeoffs. x87 reduced precision can affect results; hardware TSO needs real kernel support and preserves software fallback. None universally improves FPS.
- **Patch / Mod:** Import into the selected game with replacement review/backups. Supported mod commands can be sent manually or saved for after loading a save; automatic execution is opt-in. `fps 120` is a SMAPI command with a space, not proof the game can reach that rate.
- **Startup compatibility:** Game-specific behavior remains separate from generic tuning. Current OEM Ground Zeroes changes are a compatibility trial, not a confirmed playable-game fix.

After changing Mirror's Edge PhysX settings, completely exit the session and relaunch from Aura if the game's internal restart causes flickering. This release does not claim a universal fix for that internal restart path.

## Dependencies, updates and storage

Concise dependency download/installation progress appears at the bottom of the loading screen with the morphing-line loading animation. Completion is remembered after validation and sustained output from the selected game; a static startup poster or installer alone does not qualify. Later launches of that version skip the full scan. Game updates, relevant executable/dependency changes or container reconstruction invalidate the record. Ordinary graphics settings and saves do not by themselves count as a game update.

Open **Settings → Updates** for **Game updates** and **Built-in components and drivers**. Run a manual batch check or enable daily game checks. Checks do not automatically download updates, and game files are not replaced while the game is running. Background checks depend on Android scheduling and an available Steam session. Imported Local games have no universal update provider. Only catalog-supported components update separately; other pinned runtime changes arrive through APK updates.

**Settings → Storage** displays Aura's view of internal storage instead of opening Android's system storage manager. Space used outside Aura is grouped as **Other**. Recognized saves can be exported to ZIP or deleted after confirmation; dedicated save folders can be associated manually. Ambiguous shared folders are not deleted for a single game. Recognized caches can be cleared, but rebuilding them may temporarily cause longer loading or stutter. Save discovery does not cover every game's cloud, registry or external save format.

## Runtime foundations

| Project | Role |
| --- | --- |
| [Winlator 11.2](https://github.com/brunodev85/winlator-app/tree/3981d86efa4f333b2a34a7da8b6521476cd8c8b9) | Android runtime, display, input, container and native integration, with Aura modifications. |
| [Wine / Proton 11.0-2 ARM64EC](https://github.com/The412Banner/proton-wine) | Windows API compatibility, including the supplied Aura Wine patches. |
| [FEXCore 2610](https://github.com/FEX-Emu/FEX) | CPU translation alongside Wine. |
| [DXVK 2.7.1 / Qualcomm 2.6.2 ARM64EC](https://github.com/doitsujin/dxvk) | Driver-selected Direct3D translation, compatibility fixes, optional batching and bounded diagnostics. |
| [VKD3D-Proton 3.0.1 ARM64EC](https://github.com/HansKristian-Work/vkd3d-proton) | Direct3D 12 translation. |
| [Mesa](https://www.mesa3d.org/) / [AdrenoTools](https://github.com/bylaws/libadrenotools) | Supported graphics drivers, OpenGL-on-Vulkan and Android driver-loading integration. |
| [JavaSteam 1.8.0](https://github.com/Longi94/JavaSteam) | Steam protocol integration for accounts, libraries and content. |
| [SDL](https://github.com/libsdl-org/SDL), [GStreamer](https://gstreamer.freedesktop.org/), [Wine Mono](https://github.com/wine-mono/wine-mono) and other libraries | Input, audio, media and .NET compatibility. |

Credit belongs to the respective upstream authors and contributors. Installed updates can differ from these bundled baselines. See [Third-party notices](THIRD_PARTY_NOTICES.md), [LICENSES](LICENSES/) and [Source availability](SOURCE_CODE.md) for individual terms and delivered materials.

## Diagnostics and compatibility

Keep **Advance → Debug** off for ordinary play. Translation statistics and detailed runtime logs are grouped there; the old Runtime performance page is removed. To investigate a problem, enable it before a fresh session, reproduce the issue and use general game diagnostic capture/export. Turn it off after troubleshooting. Mirror's Edge automatic stall capture remains a separate optional per-game setting.

A visible window does not prove that a game has reached its menu. Low total CPU/GPU utilization alone does not identify a bottleneck. Individual threads, synchronization, shader compilation, memory pressure or thermal throttling can limit performance.

Host tests do not establish compatibility with every game, controller or device. Some third-party native libraries still need 16 KiB-page compatibility review. Unsupported graphics features, anti-cheat, DRM or launcher requirements can prevent a game from working. Workshop, private beta branches and cloud saves should not be assumed to have full support.

## APK verification

SHA-256 for `Aura-0.1.149-arm64-release.apk`:

```text
530f734c3ea7f8480b147f67f30e32472c9bdf0804b2b5a65f0fefd1c32b3e26
```

Run `sha256sum Aura-0.1.149-arm64-release.apk`, or PowerShell's `Get-FileHash -Algorithm SHA256`. Match the exact filename against [the release checksums](releases/v0.1.149/SHA256SUMS). Android separately verifies the APK signature.

## Source availability

The [0.1.149 Winlator kit](winlator-source-and-relink/v0.1.149/README.md) supplies editable LGPL-covered engine source, its modification record, build scripts, independently obfuscated application objects, resources and dependency objects. Users can modify the engine, recombine it with the application and sign their build with their own key.

Independent Aura Kotlin UI/business source, private obfuscation mappings, production signing material and user diagnostic archives are not included. Obfuscation does not prevent decompilation or restrict applicable modification and debugging rights.

[Runtime materials](runtime-source/v0.1.149/README.md) cover supplied Wine/ntsync changes and additional runtime build inputs. Other components retain separate obligations. The package is not a certification of the entire APK's legal compliance; [SOURCE_CODE.md](SOURCE_CODE.md) records coverage and outstanding items.

## Feedback and copyright

Search [existing issues](https://github.com/topoftree/Aura/issues) before [opening a report](https://github.com/topoftree/Aura/issues/new). Include the Aura/device/Android versions, game, settings, reproduction steps and expected versus actual behavior. For performance, name the scene and describe whether changing resolution helps. The exported game diagnostic ZIP contains the logs; duplicating the whole log into the issue is unnecessary.

Issues are public. Remove account identifiers, credentials and personal information from reports. Do not attach copyrighted game files.

Copyright © 2026 Aura for its original material, subject to third-party contributors' rights. See [Copyright](COPYRIGHT.md). Third-party software, artwork, names and trademarks belong to their owners. Aura is not affiliated with or endorsed by Valve, Ubisoft, Microsoft or the other projects and publishers named here.
