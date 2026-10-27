<p align="center">
  <img src="assets/aura-icon.png" alt="Aura gaming mascot" width="240" />
</p>

# Aura

Run supported Windows PC games on Android, with a library and launcher designed for touch and controller navigation.

## Download

[**Download Aura 0.1.118 APK**](https://github.com/topoftree/Aura/releases/download/v0.1.118/Aura-0.1.118-arm64-release.apk) · [Release notes and assets](https://github.com/topoftree/Aura/releases/tag/v0.1.118) · [Checksums](releases/v0.1.118/SHA256SUMS) · [All releases](https://github.com/topoftree/Aura/releases)

**Android 14+ · ARM64 · version code 119 · `com.android.aura`**

The APK is a signed Gradle Release build with R8 optimization, obfuscation and resource shrinking. It uses the existing upgrade signing certificate. Fully exit any game before installing the update; keep the existing installation and app data.

The matching [Winlator source and relinking kit](winlator-source-and-relink/v0.1.118/README.md) and [runtime source/build materials](runtime-source/v0.1.118/README.md) are available separately. Use materials matching your APK version. Aura's independent application source remains private, subject to the scope in [Source availability](SOURCE_CODE.md).

## New since 0.1.101

[Read the complete cumulative release notes](releases/v0.1.118/README.md), covering every version from 0.1.102 through 0.1.118, including changed defaults and removed options.

| Area | Current behavior |
| --- | --- |
| Local import | Covers appear immediately in Library with progress; details provide pause/resume, retry, cancel, speed and ETA. Browse more folders while copying. Imports resume after restart, and original files remain untouched. Add queue is removed. |
| Storage and scanning | Aura's own internal/SD/USB browser, multi-game checklists, duplicate detection, clearer access errors, valid Chinese/Aura filenames and precise rejected-path explanations. Import already-extracted games. |
| Steam downloads | Stay on game details, show cover progress, and move newly queued downloads to the front of Library without continually reshuffling it. |
| Game management | Separate Steam/Local games; aspect-aware per-game resolution capped by the current Wine container; conservative first-launch defaults and grouped Optimization controls. |
| Resolution settings | Screen settings edits the current platform's actual container default. Steam and Local save separately. Explicit per-game choices take priority. |
| Patch import | Select multiple extracted files/folders, choose a destination within the game, review replacements, retain backups and undo the last batch. |
| Clients and accounts | Cached Steam metadata and verified/local evidence show required client/account marks on covers, including uninstalled games. Supported missing clients have official installer download/import flows. New games default to Skip Steam; existing choices remain. |
| Notifications | Shared top-right solid panels, ten-second concise titles, double-Select focus, detailed actions, spring motion, first-use hint and soft chime. Undecided performance suggestions remain in Messages with Ignore. |
| Controls and presentation | Mapped Library/search pills; aligned elastic Launcher actions; improved controller drawings and breathing hints; softer page/game fades; semibold clock; monochrome octopus header and installed stamp. |
| In-game input | Finger movement reveals the mouse; gamepad input hides it. Keyboard focus/retries improved. Stretch to fill removed. |
| Runtime and performance | Generic graphics diagnostics, lower-overhead observation, automatic compatible Mesa frame-copy and X11 geometry improvements; optional D3D11 submission batching, off by default. No promised native-resolution 60 FPS. |
| Settings | Wine English/Simplified Chinese choice; Audio driver label; three sound-effect volume levels; About with offline Copyright/License. Runtime performance removed; cache/thread controls are per-game and sampling controls are in Debug. |

The official APK passed **1,725 host tests**, with no failures/errors/skips; release lint has **0 errors and 77 warnings**. It is 369,681,621 bytes (about 3.06% smaller than 0.1.101). Phone performance and compatibility remain separate from host validation. The matching [relink validation](winlator-source-and-relink/v0.1.118/VALIDATION.md) documents baseline and modified-engine builds.

The Release contains only the APK and its checksum file. Source/relinking and runtime materials live in their versioned folders on **main**.

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
| In Aura with a retained game | Single press | Focus the running-game entry at the bottom of Launcher; confirm to resume. |
| Floating menu | Up on the first item / down on the last | Wrap to the opposite end. |

Ordinary loading screens show the mapped menu/return button on a controller outline for ten seconds. Debug loading screens omit that guide. Library uses mapped Y / triangle for its category or Local Add Game, and the header uses mapped X / square for search.

## Game settings

Open **Settings → Games Management** and choose a game. Settings are saved per game. Fully exit and restart to apply launch options; resuming a retained session keeps its original settings.

- **Optimization:** Thread waiting and x87 reduced precision are separate checkboxes; both default off. x87 changes precision and needs game-specific comparison. Managed shader caches, experimental translation cache, smart suggestions, background transfer pausing, low-latency DXVK queues and Vulkan overrides are also per-game. Shader compiler threads are configured per game.
- **D3D11 submission batching (experimental):** Off by default. Trial it with a full restart for eligible DXVK D3D11 games; disable it if behavior regresses. It is not a universal FPS improvement.
- **ESync:** Changes Wine synchronization; it is separate from VSync/frame presentation. Neither guarantees higher FPS.
- **Game default resolution / frame limit:** Choices use the screen aspect ratio and stay within the Wine container size. Aura's limit does not remove a game's own VSync or frame target. Screen settings changes only the current Steam/Local container's default.
- **Smart performance suggestions:** Proposed changes require the user's decision and apply on the next full launch. First-launch file/device assessment saves conservative defaults, not benchmark-derived tuning.
- **Import patch:** Import already-extracted files/folders, review matching-file replacements and backups, and choose targets only within the game's directory. Patch compatibility is not inferred automatically.
- **Startup compatibility:** Targeted Mirror's Edge and optional Ground Zeroes settings remain separate from general CPU tuning. The Ground Zeroes candidate is off by default and is not a confirmed playable-game fix.

After changing Mirror's Edge PhysX settings, completely exit the session and relaunch from Aura if the game's internal restart causes flickering. This release does not claim a universal fix for that internal restart path.

## Dependencies, updates and storage

Concise dependency download/installation progress appears at the bottom of the loading screen with a plain segmented spinner. Completion is remembered after validation and sustained output from the selected game; a static startup poster or installer alone does not qualify. Later launches of that version skip the full scan. Game updates, relevant executable/dependency changes or container reconstruction invalidate the record. Ordinary graphics settings and saves do not by themselves count as a game update.

Open **Settings → Updates** for **Game updates** and **Built-in components and drivers**. Run a manual batch check or enable daily game checks. Checks do not automatically download updates, and game files are not replaced while the game is running. Background checks depend on Android scheduling and an available Steam session. Imported Local games have no universal update provider. Only catalog-supported components update separately; other pinned runtime changes arrive through APK updates.

**Settings → Storage** displays Aura's view of internal storage instead of opening Android's system storage manager. Space used outside Aura is grouped as **Other**. Recognized saves can be exported to ZIP or deleted after confirmation; dedicated save folders can be associated manually. Ambiguous shared folders are not deleted for a single game. Recognized caches can be cleared, but rebuilding them may temporarily cause longer loading or stutter. Save discovery does not cover every game's cloud, registry or external save format.

## Runtime foundations

| Project | Role |
| --- | --- |
| [Winlator 11.2](https://github.com/brunodev85/winlator-app/tree/3981d86efa4f333b2a34a7da8b6521476cd8c8b9) | Android runtime, display, input, container and native integration, with Aura modifications. |
| [Wine / Proton 11.0-2 ARM64EC](https://github.com/The412Banner/proton-wine) | Windows API compatibility, including the supplied Aura Wine patches. |
| [FEXCore 2610](https://github.com/FEX-Emu/FEX) | CPU translation alongside Wine. |
| [DXVK 2.7.1 ARM64EC](https://github.com/doitsujin/dxvk) | Direct3D translation; D3D9 includes the empty-rectangle backport; D3D11 includes optional submission batching. |
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

SHA-256 for `Aura-0.1.118-arm64-release.apk`:

```text
cfb0e55e580d09e97c2aa2a7b09a0b74284b4b544e21036a1e3b26c3f650df44
```

Run `sha256sum Aura-0.1.118-arm64-release.apk`, or PowerShell's `Get-FileHash -Algorithm SHA256`. Match the exact filename against [the release checksums](releases/v0.1.118/SHA256SUMS). Android separately verifies the APK signature.

## Source availability

The [0.1.118 Winlator kit](winlator-source-and-relink/v0.1.118/README.md) supplies editable LGPL-covered engine source, its modification record, build scripts, independently obfuscated application objects, resources and dependency objects. Users can modify the engine, recombine it with the application and sign their build with their own key.

Independent Aura Kotlin UI/business source, private obfuscation mappings, production signing material and user diagnostic archives are not included. Obfuscation does not prevent decompilation or restrict applicable modification and debugging rights.

[Runtime materials](runtime-source/v0.1.118/README.md) cover supplied Wine/ntsync changes and additional runtime build inputs. Other components retain separate obligations. The package is not a certification of the entire APK's legal compliance; [SOURCE_CODE.md](SOURCE_CODE.md) records coverage and outstanding items.

## Feedback and copyright

Search [existing issues](https://github.com/topoftree/Aura/issues) before [opening a report](https://github.com/topoftree/Aura/issues/new). Include the Aura/device/Android versions, game, settings, reproduction steps and expected versus actual behavior. For performance, name the scene and describe whether changing resolution helps. The exported game diagnostic ZIP contains the logs; duplicating the whole log into the issue is unnecessary.

Issues are public. Remove account identifiers, credentials and personal information from reports. Do not attach copyrighted game files.

Copyright © 2026 Aura for its original material, subject to third-party contributors' rights. See [Copyright](COPYRIGHT.md). Third-party software, artwork, names and trademarks belong to their owners. Aura is not affiliated with or endorsed by Valve, Ubisoft, Microsoft or the other projects and publishers named here.
