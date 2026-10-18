# Aura

## Download

[**Download Aura 0.1.82 APK**](https://github.com/topoftree/Aura/raw/refs/heads/main/apk/Aura-0.1.82.apk) · [Release notes](releases/v0.1.82/README.md) · [SHA-256](releases/v0.1.82/SHA256SUMS)

Aura is an Android launcher for running supported Windows PC games. This repository is for public APK releases and user documentation.

## Requirements

- Android 14 or newer, ARM64 device.
- Sufficient storage for the app, its runtime and your games.
- Your own legally acquired games and the accounts required by their launchers.

Game and graphics-driver compatibility varies by device. A compatible Android version alone does not guarantee that a game will run well.

## Install or update

1. Download the APK from the release above.
2. End any running Aura game/Wine session before installing an update.
3. Allow APK installation for the browser or file manager you use, then install the APK. The existing signing certificate is retained for compatible in-place updates.

## Included in 0.1.82

- Installed Steam game update checks, a Library update action, three-second side notifications, and update confirmation from Messages.
- One-time Steam main-window minimization after the selected game window appears, for games that do not skip Steam.
- An Advanced Debug mode switch that controls optional Aura diagnostic collection. Keep it off for ordinary play; enable it when collecting a troubleshooting report.
- A simpler startup animation and darker glass and edge effects in dark mode.
- Additional display-path and thread timing diagnostics. These do not establish or promise an FPS improvement or an ESync compatibility fix.

Steam and games may produce their own logs independently of Aura's diagnostic switch. Aura is not affiliated with Valve or the game publishers.

## Third-party components

See [third-party notices](THIRD_PARTY_NOTICES.md) and [license texts](LICENSES/). Component licenses remain with their respective owners.
