# Aura

Run supported Windows PC games on your Android device, with a library and launcher designed for touch and controller navigation.

## Download

[**Download Aura 0.1.82 APK**](https://github.com/topoftree/Aura/releases/download/v0.1.82/Aura-0.1.82-arm64-release.apk) · [GitHub Release](https://github.com/topoftree/Aura/releases/tag/v0.1.82) · [Checksums](releases/v0.1.82/SHA256SUMS) · [All releases](https://github.com/topoftree/Aura/releases)

Current version: **0.1.82**, Android version code **83**. Download `Aura-0.1.82-arm64-release.apk` from the release's **Assets** section. It is a signed production APK, not a debug build.

GitHub's automatically generated **Source code (zip/tar.gz)** downloads contain this public distribution repository, not Aura's private application source or a complete corresponding-source package for its third-party components.

## Overview

Aura brings game installation, launch settings, controller navigation and supported Windows compatibility components together in one Android app. Games run locally on your device; performance depends on the game, runtime, graphics driver and phone.

This is Aura's public repository for APK distribution, documentation, release notes and issue tracking. Aura's original application source is maintained privately. Third-party software retains its own licenses and recipient rights; see [Copyright](COPYRIGHT.md) and [Third-party notices](THIRD_PARTY_NOTICES.md).

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

Windows compatibility uses Winlator integration, Wine/Proton, FEX and graphics translation components. These projects and their contributors are credited in the [third-party notices](THIRD_PARTY_NOTICES.md).

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

1. Download the APK from the [official release page](https://github.com/topoftree/Aura/releases/tag/v0.1.82) and verify its checksum below.
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

Keep **Settings → Advance → Debug mode** off for ordinary play. In 0.1.82, this switch controls optional Aura log collection, session/performance sampling and native execution diagnostics. Essential launch checks, session cleanup and runtime safeguards continue to operate.

To collect a new report, enable Debug mode before starting a fresh game session. After reproducing the issue, use the available diagnostic export in Settings. You do not need to force a crash to create a report. Turn Debug mode off after troubleshooting; already saved reports can remain available. Android, Steam and games can create their own logs independently of this switch.

Low total CPU or GPU usage alone does not identify a fault. A busy individual thread, synchronization waits, image transfers, memory pressure or thermal throttling can limit frame rate. Compare the same scene and resolution when reporting performance, and distinguish game FPS from a counter observed on Aura's settings screen.

## Current release and limitations

Version **0.1.82** adds installed-game update checks and confirmations, one-time Steam minimization, a unified optional-diagnostics switch, a simpler startup animation and darker dark-mode glass and edge effects. It also improves display-path and thread-timing diagnostics. See the [release notes](releases/v0.1.82/README.md).

Build and automated checks do not establish device compatibility or measured FPS gains. This release does not claim to resolve Stardew Valley's reported performance issue or all Steam/ESync startup problems. Games with unsupported graphics features, anti-cheat, DRM or launcher requirements may not work. Workshop, private beta branches and cloud-save behavior should not be assumed to have full support.

Aura is under active development. Use the release notes and issue tracker for known behavior and report reproducible problems with the details below.

## APK verification

SHA-256 for the current **Release asset**, `Aura-0.1.82-arm64-release.apk`:

```text
a6890e736ddf5dab59cce02db99dfcdc1b5534b2edbc9b3849286c94d6ee8f8c
```

In a shell with `sha256sum`, run:

```sh
sha256sum Aura-0.1.82-arm64-release.apk
```

Compare the result with the value above. On Windows PowerShell, use `Get-FileHash .\Aura-0.1.82-arm64-release.apk -Algorithm SHA256`. A matching hash verifies the published bytes; obtain the APK and checksum from the official repository. Android separately checks the APK signature during installation.

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

This public repository does not contain Aura's private application source. Publishing APKs here does not make Aura's original code open source or grant an open-source license to it.

Bundled third-party components, including modified components, retain their own licenses. Any applicable source, modification, replacement, relinking or reverse-engineering rights for those components are not restricted by Aura's copyright notice. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for attributions and the current source-delivery status, and [LICENSES/](LICENSES/) for retained license texts. Required third-party source materials are distinct from Aura's private original code.

## Copyright

Copyright © 2026 Aura. All rights reserved for Aura's original material, subject to applicable law and the separate rights of third-party contributors. See [COPYRIGHT.md](COPYRIGHT.md) for the scope of this notice.

Third-party software, game artwork, names and trademarks belong to their respective owners. Aura is not affiliated with or endorsed by Valve, Ubisoft, Microsoft or the other projects and publishers mentioned here.
