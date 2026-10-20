# Aura 0.1.101

**Android 14+ · ARM64 · version code 102**

This public release includes the changes since Aura 0.1.83. The official APK is the same signed 0.1.101 artifact already delivered for testing; its certificate matches existing Aura installations. Fully close any game and install over the existing app to retain data.

## New features

- **CPU optimization for installed Steam and Local games:** Default, Thread waiting only, x87 calculations only, or Both. Choices are per game; previous Mirror's Edge choices migrate.
- **Updates centre:** Settings groups game updates and component/driver updates. Check all installed Steam games, enable a separate daily check, and see results beside Library's Update title. Downloads require confirmation.
- **Storage inside Aura:** View internal capacity and game, save, cache, Aura/runtime, Other and available space. Export recognized saves to ZIP, associate a dedicated save folder, or confirm deletion of recognized saves/caches.
- **Remembered dependency setup:** Transparent progress in the centre of loading. Validated installation plus sustained selected-game output records success for that version. Relevant game/dependency/container changes trigger checking again. Old Cancel and Show Steam windows controls are removed.
- **Controller gestures:** Double-press Select or its controller equivalent for the game menu; hold approximately 0.7 seconds to return to Aura. A single press in Aura focuses the retained game; in the game it is forwarded after the approximately 0.3-second gesture window.
- **Loading hints:** Solid controller-specific icons explain both gestures. Hidden on debug loading screens.
- **Launcher and Library:** Slightly rounded covers, selected-cover glow, a raised running-game cover on focus, larger full-width swipe actions and text aligned with the game title.
- **Compact game menu:** Smaller corners, stronger elevation, concise labels and wrapping navigation.
- **Display scaling:** Fit or stretch the viewport from the game menu.
- **Simpler settings/diagnostics:** Removed global Stability mode, dedicated Mirror's Edge/Ubisoft startup capture pages and separate VC++/Unity collection/export flows. General diagnostics, VC++ installation results, DLL validation and optional Mirror's Edge stall capture remain.
- **Winlator license page:** Separate page with controller navigation and source links.

## Compatibility fixes

Mirror's Edge retains targeted startup-time compatibility. Display-mode changes preserve the monitor's mode list while updating the viewport and pointer bounds. DXVK D3D9 2.7.1 includes the upstream empty-rectangle guard for the zero-size image-copy path encountered after changing the saved resolution.

Wine includes wait-queue lifetime and paired native/i386 wait-completion changes. The reporting user confirmed that the earlier integrated fix resolved intermittent multi-second picture pauses. This feedback is specific to the tested game/device, and does not promise the same result elsewhere.

The x87 option reduces floating-point precision. Suitability and performance of both CPU options vary by game. Fully exit and relaunch to apply launch options.

Mirror's Edge can still flicker after its own PhysX restart action. Completely exit the session to Aura and relaunch if that occurs; this release does not claim a universal fix for the internal restart path.

## Data and validation

The signing certificate matches existing Aura upgrades. This release does not reset game resolution, graphics, CPU choices, containers, saves or existing diagnostic backups. Save discovery covers known paths and manually associated folders; shared/ambiguous directories are not deleted for a single game. Cache rebuilding can temporarily cause slower loading or shader stutter.

The production APK passed the Gradle Release build with R8/obfuscation/resource shrinking, release lint, 42 final removal/navigation regression cases, runtime package/index checks, JNI/Steam reflection checks and signature verification. All 318 native libraries and 18 runtime archives match 0.1.100. Earlier feature tests separately covered storage, dependency/update behavior, CPU options and menu/controller state.

Actual 0.1.101 storage-provider export, dependency installation, Steam background checks and physical-controller UI behavior have not yet been tested on a phone. Existing third-party native 16 KiB-page limitations remain.

The public kit's independent rebuild and modification checks are recorded in [VALIDATION.md](../../winlator-source-and-relink/v0.1.101/VALIDATION.md). Verification uses a temporary recipient-owned signing key, not the production key.

## Files and source

| Download/material | Purpose |
| --- | --- |
| `Aura-0.1.101-arm64-release.apk` | Official signed APK. |
| `Aura-0.1.101-relink.zip` | Matching editable Winlator engine, obfuscated application objects, dependencies, resources and build instructions. |
| `SHA256SUMS.txt` | Download checksums. |
| [Runtime source/build materials](../../runtime-source/v0.1.101/README.md) | Wine sources/patches, ntsync source/vendor, and additional runtime inputs. |

Independent Aura application source, private R8 mappings, signing secrets and user diagnostic captures are not published. LGPL-covered engine source remains editable, with explicit library-recombination and debugging rights. See [Source availability](../../SOURCE_CODE.md) and [Third-party notices](../../THIRD_PARTY_NOTICES.md) for coverage and outstanding component obligations; these materials do not certify the entire APK's legal compliance.

APK SHA-256:

```text
18b76257c253e5f441ccc579cc646a0e505b554f736c217c2c601406977a0af5
```
