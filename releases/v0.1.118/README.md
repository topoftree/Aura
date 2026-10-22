# Aura 0.1.118 — all changes since 0.1.101

This public release includes the cumulative changes from **0.1.102 through 0.1.118**, compared with the previous public release, **0.1.101**. The sections below describe the final behavior in 0.1.118; interim settings that were subsequently replaced are identified explicitly.

**Android 14+ · ARM64 · version code 119 · `com.android.aura`**

[Download APK](https://github.com/topoftree/Aura/releases/download/v0.1.118/Aura-0.1.118-arm64-release.apk) · [SHA-256](https://github.com/topoftree/Aura/blob/main/releases/v0.1.118/SHA256SUMS) · [Winlator source and relinking materials on main](https://github.com/topoftree/Aura/tree/main/winlator-source-and-relink/v0.1.118) · [Runtime sources and patches on main](https://github.com/topoftree/Aura/tree/main/runtime-source/v0.1.118)

Fully exit the current game/Wine session, then install over the existing Aura app and keep its data. The official APK retains the existing upgrade signing certificate. Launch-option changes require a full exit and fresh launch; resuming a retained session keeps its original configuration.

## Local game import and storage access

- **Import directly into Library.** Starting an import immediately creates the game's cover, with a thin progress bar. Opening that cover shows detailed bytes, percentage, speed and estimated remaining time, plus pause/resume, retry and cancel controls. A game becomes launchable after its private copy is ready.
- **Continue browsing while importing.** Add Game remains available for selecting games in other folders. Accepted games copy in selection order. The old **Add queue** page and Add Game's import-progress area have been removed; progress belongs to the game's Library details.
- **Resume interrupted work.** Paused imports survive an app restart. Cancelling removes that import's private copy, leaving original source files untouched. Duplicate-source checks also recognize imports made through the older Android folder picker.
- **Choose multiple discovered games.** Scanning a folder containing several games opens a Settings-style checklist, initially selecting all. Import all toggles the whole selection, including other pages. Only selected games are accepted, in scan order; an empty selection cannot start an import, and Back leaves without importing. A single discovered game follows the direct flow. Games with the same title in different folders remain distinct sources.
- **Use Aura's own file browser.** Internal shared storage and mounted SD/USB volumes are browsed inside Aura, using its normal list and focus presentation. Android's All files access settings are opened when permission is needed; folder selection no longer relies on Android's document picker. Protected Android app directories remain inaccessible.
- **Already-extracted games only.** Local ZIP, 7z, RAR and split-archive extraction has been removed. Extract games before selecting their folders. Browsing and source scanning are read-only.
- **More useful folder errors.** Browsing can show valid children even when another child has an invalid or case-conflicting name. Errors distinguish denied access, a missing folder, disconnected storage and protected paths, with a storage-access recovery shortcut. Import validation still checks the selected content strictly.
- **Fix overly broad unsafe-name failures.** Ordinary Chinese names, spaces, `aura游戏` and `Aura-Game-Debug` ZIP filenames are accepted. Scanning ignores only known installation-marker/version-receipt regular files and recreates its own installation marker after a successful copy. Other rejected entries identify the relative path and reason, including escaped invisible characters. Traversal, symbolic links, unknown reserved names and case collisions remain blocked; archives are not inspected or extracted by this flow.
- **Local game information.** Details can show release year, studio and story/content description from matched public Steam metadata, cached for offline use. Unavailable information is left unknown rather than guessed.
- **Artwork fallback and caching.** Missing Local portrait covers can fall back to the same game's landscape capsule/header, including unfocused Launcher and Library cards. Successful sources and failed requests are cached to avoid repeated reloads on focus changes. Crysis 2 matching recognizes the unambiguous Maximum Edition artwork.
- **Storage presentation.** The capacity bar appears as measurement begins, unknown values use a dash, and measured segments fade/expand with a gentle spring. Refresh retains the previous bar and smoothly adjusts its proportions.

## Steam downloads, Library and Launcher

- Starting a Steam download keeps the current game information page open. The download button becomes detailed progress with transfer speed, percentage and remaining-time estimate, accompanied by pause/resume and cancel actions.
- Downloading/installing Library covers show a thin progress bar. Games newly accepted into the download queue move to the front of Library, newest first. Progress changes, pause and completion do not continually reorder the grid; focus follows the game's new position when returning from details.
- Download titles, cover artwork, progress and status share a solid Messages card background. Cancellation dialogs follow Aura's UI, with full-width focused options and elastic text/icon feedback. Starting a download produces the shared right-side notification.
- Steam **All / Downloaded** uses a selected pill with a **filled, controller-mapped Y / triangle glyph** before the title and revised spacing. Left/right on the filter switches immediately. Glyphs follow the actual active mapping, including Nintendo layouts, and hide when no controller is available.
- Local **Add Game** has a pill and mapped Y / triangle shortcut that opens the page directly. Local no longer has Steam's All / Downloaded filters.
- Library edge navigation stays on the current game and repeats its elastic focus response, including held directions. Up from the first row no longer moves focus to the filter or Add Game. This supersedes the interim wrap/navigation behavior introduced in 0.1.106.
- Installed **Steam** games use the hollow circular stamp/check badge at the bottom left. Local covers no longer display an installed check. Client/account marks occupy their separate cover area.
- Library game information sits beside its action pane; controller left/right navigation moves between them.
- Launcher Open / Close / Delete icons and text scale smoothly and spring when focused, with smaller unfocused content. Icons align in one fixed column and label starts in another; full-width focus backgrounds and touch targets stay stable.
- A focused retained-game cover grows further. Its unfocused label uses the normal theme foreground, changing to the accent on focus.
- After a Steam-launched game exits, verified Steam-client windows no longer keep the completed session on the Wine desktop; Aura returns to Launcher.

## Game Management, resolution and settings organization

- Steam and Local Game Management lists now show their own platform's games. Settings and first-launch profiles remain separate even when a Local game uses the same artwork match as a Steam game.
- **Screen settings now edits the current platform's actual Wine container default**, independently for Steam and Local, rather than a global game-launch override. It reads changes made in the container editor when opened/resumed and identifies the platform container even if renamed. Explicit Local container settings can create that container when needed.
- Resolution choices follow the phone's aspect ratio. A recommended/container choice is saved rather than continually overriding games; Native represents the device pixel dimensions. Existing valid custom container dimensions are preserved.
- **Per-game default resolution** is prominent in Game Management. Its choices preserve the current screen aspect ratio and cannot exceed the current Wine container in either width or height. A game's explicit choice takes priority; no game configuration file is edited automatically.
- First launch records a conservative baseline from the selected executable's architecture/Direct3D imports, device memory/core count, device resolution guidance and the current container. The saved defaults are shown after assessment and are used when resetting the game. Later container/global changes do not silently rewrite an assessed game's preferences.
- New or previously unspecified **CPU optimization defaults to Off**; explicit existing choices remain. The temporary default-to-both policy from 0.1.102 is superseded. First-launch inspection does not automatically lower x87 precision or enable experimental compatibility patches.
- Optimization controls are grouped under each game's **Optimization** page, with checkbox presentation for toggles: thread-wait optimization, x87 reduced precision, managed shader cache, translation code cache, smart performance suggestions, background download/import pausing, low-latency DXVK queue behavior and per-game Vulkan overrides. Mutually exclusive driver choices remain exclusive; shader compiler thread counts remain per-game choices.
- **Translation code cache (experimental)** is now only per-game and defaults off unless explicitly selected. The global code-cache and shader-compiler-thread overrides are retired; saved explicit per-game choices remain.
- **Runtime performance is removed.** Translation statistics and detailed runtime logging are under Advance → Debug. Diagnostic preferences are preserved.
- Advance labels the audio backend setting clearly as **Audio driver**.
- **Skip startup movies** uses supported launch profiles, including Mirror's Edge and listed Source executables. Unsupported games show that status instead of receiving guessed arguments.

## Performance suggestions and all right-side notifications

- Smart suggestions use sustained observations of the selected game's drawable, focused window. Loading, installers/client windows, background/paused sessions, menus, capture intervals and insufficient samples are excluded. Low average FPS or low CPU/GPU percentage alone does not trigger a diagnosis.
- Depending on the observed conditions, a suggestion can propose a suitable frame cap, lower resolution or a trial of thread-wait optimization. It never automatically enables x87 reduced precision. The user chooses whether to apply the proposed setting on the next full launch; the current session and unrelated settings are unchanged.
- Suggestions can be disabled per game. A game's first suggestion is shown once and stored in **Messages**. Timeouts, backing out and restarting Aura do not count as consent; **Ignore** deletes it without changing the configuration. A stale suggestion cannot overwrite a later manual choice.
- Performance, component/driver update, game update and download notifications share one presentation and interaction flow. Passive notices show a concise title, sit high on the right with the right edge flush to the screen, and remain for **10 seconds**.
- Double-press the currently mapped **Select** button, or touch the notice, to focus it and reveal its full content/actions. Expansion is smooth with a gentle spring, using solid Settings-style backgrounds and elastic focused text/icons. A focused notice stops the passive countdown; entering its interactive panel pauses the game and releases held game inputs.
- The first notice also shows a bottom hint on a translucent background explaining double-Select. It appears and disappears with the passive notice. Without a notice, double-Select retains its normal game-menu action.
- Notifications use a soft, airy chime. Settings → Sound adds **three sound-effect volume levels**. Existing sound enable/mute and background behavior remain respected.

## Client and required-account detection

- Library covers, including games not installed yet, and expanded Launcher covers can display required client/account platform icons. Marks use adaptive monochrome contrast and scale/fade with Launcher cover expansion; unfocused Library covers retain them.
- Detection combines public Steam store declarations, a small verified exception/rule set and read-only local-file evidence. Shared persistent caching avoids a full built-in game list and repeated requests. Missing metadata remains unknown; a publisher name or SDK DLL alone does not prove that a desktop launcher is required.
- Required accounts are distinguished from installed clients. Recognized account brands include EA, Ubisoft, Rockstar, Battle.net, Epic, Xbox/Microsoft, PlayStation Network, 2K, Bethesda.net, Paradox, Square Enix and Wargaming. Optional/no-longer-required accounts are not treated as mandatory.
- Steam versions of titles such as Overwatch and Diablo IV may need a **Battle.net account**, so the account icon can appear; this does not install the Battle.net desktop client. Epic Online Services does not by itself imply Epic Games Launcher. Retired 2K/Bethesda launchers are not installed. Paradox/REDlauncher are not universal forced dependencies.
- Confirmed supported missing clients can be downloaded from their official sources, checksum-verified and installed in the current game's container, then checked before the original launch continues. Existing installed clients are reused. EA and Rockstar support includes their verified requirements; Rockstar preparation includes its required VC++ packages.
- If automatic download fails, **Download in browser**, **Import installer** and **Retry** remain available. Manually supplied installers must match the pinned checksum. Aura does not execute arbitrary setup/installscript commands found with a game.
- New Steam game preferences default to **Skip Steam client**. Existing explicit choices are preserved. If a game requires Steam, disable Skip Steam for that game and relaunch; detection does not silently flip the user's switch. Skipping Steam does not replace Steamworks, ownership checks, DRM or required authentication.
- Required Steam and EA clients use `-silent`; Ubisoft Connect uses `-uplay_silent`. Login, verification and important dialogs can still appear. Rockstar keeps the game's normal launcher handoff rather than receiving an unsupported generic silent flag. Background launch still runs the real client and does not eliminate all Steam/CEF memory use.
- Normal startup stays under the selected cover while Wine/Steam initialize, with **Updating Steam…** shown during bootstrap. Verified signed-in Steam main windows and ordinary popups can be minimized/closed; authentication, cloud conflicts and important dialogs are retained. Manual startup-window reveal remains available.

## Per-game patch import

- **Import patch** selects multiple already-extracted files/folders in an Aura-style browser and imports the batch together.
- The target defaults to the installed game's directory. Users can change it only within that game's directory tree; each game remembers its choice.
- Review new/matching files before importing: replace all matching files with backups, select individual replacements, or keep existing files and import only new ones.
- Undo the last batch and recover an interrupted import from the same page. Compressed patches are not extracted and installer executables are not run. Importing a patch does not establish its compatibility or guarantee ultrawide support.

## Controller, touch, keyboard and visual changes

- Fix Select single taps disappearing in games that poll controller state: after the 300 ms double-press decision window, the forwarded press lasts 100 ms. Double press still opens the game menu and holding about 700 ms returns to Aura. Focus/menu/mode changes and controller removal cancel pending input; Select also passes through keyboard/mouse modes.
- The loading guide is centered and uses redrawn Xbox, DualSense, Switch Pro and phone-attachment outlines, corrected shoulder placement and system-button details. The indicated button has a slow, gentle breathing highlight. USB phone attachments and ambiguous controller transport use the applicable fallback artwork.
- Loading hints and their matching temporary scrim remain for **10 seconds**, then fade together over 400 ms, or dismiss early through the indicated button. Text/icons choose black or white from the artwork; Debug startup omits the guide.
- Concise loading, dependency and client status moves to the bottom with a plain segmented spinner. Normal loading waits for sustained nonblank rendering from the selected game; a Wine desktop, installer or first window alone is insufficient.
- Moving a finger on the game view reveals the mouse cursor; a tap alone does not. Valid gamepad buttons/sticks hide it and cancel pending touch gestures, while stick dead zones avoid accidental mode switching.
- **Show keyboard** waits for editor/window focus and makes bounded retries based on actual keyboard visibility.
- **Stretch to fill is removed.** Rendering and pointer mapping preserve the game viewport's aspect ratio. This supersedes 0.1.101's fit/stretch choice.
- Search has a pill background and mapped **X / square** shortcut beside the magnifier. The search input is more opaque with blur.
- App pages crossfade naturally; entering/leaving a game uses slower, softer fades for Aura's components. Wallpaper scrims are lighter, the clock is semibold, and the upper-left mark is the monochrome Aura octopus.
- The Local platform icon is a monochrome pirate ship. Executable selection uses folder/document icons; the play icon remains for launching.
- Game launch uses a softer 1.2-second stereo chord with an airy tail, without delaying launch. The in-game menu's extra rectangular Compose shadow is removed while retaining blur, rounded corners and elevation.

## Wine language and startup compatibility

- Advance → **Wine language** offers English and Simplified Chinese separately from Aura's interface language. English remains the default; obsolete Portuguese/Russian indices fall back to English. Changes apply to a new runtime session.
- The runtime UI adds 300 Chinese strings. The Wine locale adapter selects Windows system/user/UI language while preserving UTF-8 Unix encoding, numeric formatting, NLS/font support and the earlier wait/stack repairs. Games/clients still need their own translations; English-only Wine tools fall back to English.
- Ground Zeroes has a **default-off Startup compatibility fix**, gated by both the setting and the actual executable. It combines a correction to FEX executable-image page ranges with an isolated Wine GetTickCount entry protecting the neighboring GetTickCount64 entry. It does not patch game files or change Windows page permissions.
- Wine ARM64EC loader breakpoint handling now advances the saved program counter rather than a floating-point context field. Known managed runtime/container files upgrade by verified hash; unknown custom replacements remain preserved. Ground Zeroes reaching playable gameplay is not confirmed by these host checks.

## General graphics diagnostics and automatic rendering optimizations

- Debug mode now provides bounded graphics-call observation for **all Steam and Local games**, not just Stardew Valley: supported GLX/OpenGL, bundled Mesa/Zink and Vulkan paths, per-process/thread host wall/CPU times, copy bytes and call counts.
- Diagnostic ZIP exports include summaries, interval history and bounded binary aggregates. Where verified identity is available, target-game activity is separated from other session processes. Unsupported or missing measurements remain unknown.
- Fix observation of XGetGeometry, XShmPutImage and XSync in locally loaded Mesa/Xlib dependencies while preserving the real function targets and results.
- Mesa copy timing is aggregated per submission stage instead of timing every scanline, substantially reducing observer clock calls. Stage-to-SHM and stage-to-Swap-end measurements preserve copy totals and replace the old per-copy timing interpretation. Debug-off SHM updates skip an unused timer read.
- XGetGeometry replies use one native buffer write instead of eleven field writes. Initial texture allocation marks uploaded content clean, avoiding a redundant next-draw upload.
- Large, verified Mesa rectangle copies automatically use the caller plus up to two reused helper threads. Completion remains synchronous and all pixels are preserved. Small, busy, unsupported or unsafe cases use the original serial path. This applies to compatible games generally and needs no Game Management or Debug toggle.
- Debug records actual parallel-copy calls/bytes. Caller-thread CPU excludes helper work, so whole-process CPU and wall time must also be considered. An advanced A/B override, `AURA_MESA_FRAME_COPY=0`, disables this copy path; removing the override restores the default.
- Ordinary X11 window geometry queries now avoid the texture-upload lock while returning live dimensions under the window lock. Bitmap/other paths retain their fallback. This optimization is automatically active, with optional low-frequency Debug timing/use records.
- Full-frame Mesa readback/copy and host texture upload still occur; direct shared GPU-buffer transport is not implemented. These changes do not lower resolution or alter a game's own frame target.
- **Automatic startup capture** can be armed once under Debug. It captures startup exception/module output before the first game window, with a 120-second / 8 MiB retained-output bound. Dependency installers do not consume the request; detailed logging lasts through that captured session. Export the dated ZIP after reproducing the problem.

## Optional D3D11 submission batching

- Each game's Optimization page adds **D3D11 submission batching (experimental)**, **Off by default**. It groups eligible DXVK D3D11 submission hints to reduce submission overhead while retaining explicit Flush, Present, query, resource and synchronization boundaries.
- The disabled path keeps the previous submission decisions. The option is API/path-specific, does not apply to every game, and requires a full restart. Compare the same scene/settings with it off/on and disable it if behavior regresses.
- Matching DXVK D3D11 payloads and version/use markers are included. Known managed DLLs update while custom replacements are preserved. Neither Battlefield 3 nor Stardew Valley is promised stable native-resolution 60 FPS.

## About, licenses, package and source delivery

- Settings' former **Version** entry becomes **About**, containing **Copyright** and **License**. Selecting a component's version opens its license/notices directly; the texts are bundled for offline viewing. The validated catalog contains 341 component entries and 237 text files.
- Stronger lossless compression of Wine, initial-container and ICU archives reduces download size while retaining the bundled offline components and decoded content. The final 0.1.118 APK is **369,681,621 bytes**, **11,687,985 bytes smaller than 0.1.101** (about 3.06%); this does not reduce expanded runtime/game size.
- The official APK remains a signed Gradle Release with R8 optimization, obfuscation and resource shrinking. It is the already-verified 0.1.118 build, not a relinking-test APK.
- Matching editable Winlator-derived engine source, modification records, licenses, build scripts, dependency/resource inputs and independently pre-obfuscated Aura application objects are on **main**. Independent application source, original application objects, private mappings/history, signing material and user diagnostic archives are excluded. See the versioned source notices for the exact scope and modification/relinking permissions.
- Relinking materials and runtime source patches are repository folders, **not Release attachments**. This Release attaches only the official APK and its checksum file. Older versioned materials remain available.

## Version-by-version coverage

| Version | Changes incorporated above |
| --- | --- |
| 0.1.102 | Lossless package compression; supported startup-movie skipping; aspect-aware resolution groundwork; loading readiness/presentation; retained-game focus. Its interim CPU default is superseded. |
| 0.1.103 | Select delivery repair; Local artwork fallback/cache; loading guide/status; Steam-window handling; menu shadow repair. |
| 0.1.104 | One-shot startup capture; lighter scrims; side-by-side Library details; loading refinements; Steam update status and initial client marks. |
| 0.1.105 | Local/executable icons; Crysis artwork matching; cover client marks; Steam game exit handling; ten-second guide; optional FEX image-page startup repair. |
| 0.1.106 | Extracted-folder import and duplicate checks; client-mark transitions; launch sound; Wine language. Interim queue and edge-wrap flows are superseded. |
| 0.1.107 | Ground Zeroes isolated clock-entry compatibility candidate and managed runtime migration. |
| 0.1.108 | Storage bar transitions; in-app shared/removable storage browser and access entry; immediate Steam filter switching. |
| 0.1.109 | Steam edge-focus feedback; removal of Local installed checks; Wine ARM64EC breakpoint correction. |
| 0.1.110 | Multi-game selection checklist and controller-outline/attachment refinements. |
| 0.1.111 | Independent Steam/Local container-default resolution; mapped Library category pills. |
| 0.1.112 | Per-game optimization organization and conservative first profiles; CPU default off; persistent suggestions/unified notices; sound levels; download UI; client/account detection/setup; Skip Steam default; audio driver label. |
| 0.1.113 | Patch import/replacement/undo; platform-separated management; container-capped game resolutions; Add Game/search pills; installed stamp; fades/header/clock/controller refinements; About and offline licenses. |
| 0.1.114 | General graphics diagnostics and supported client background launch. |
| 0.1.115 | Locally loaded Xlib observation; downloads move to Library front; Launcher action focus animation. |
| 0.1.116 | Lower-overhead copy diagnostics; X11 reply/texture improvements; touch/gamepad switching; keyboard retries; stretch removal; action-column alignment. |
| 0.1.117 | Automatic verified large-frame copy parallelism; better Local folder/access errors. |
| 0.1.118 | Library-based Local imports and metadata; precise unsafe-name handling; runtime-settings reorganization; optional D3D11 batching; automatic geometry-lock optimization. |

## Verification and performance limits

The official build passed **1,725 JVM/Robolectric tests**, with zero failures, errors or skipped tests. Full release lint reports **0 errors and 77 warnings**. Package/signature/alignment, runtime reconstruction, JNI field contracts, Steam reflection, navigation themes and offline license checks passed. Independent baseline and modified-library relinking checks are documented in the [public kit validation](https://github.com/topoftree/Aura/blob/main/winlator-source-and-relink/v0.1.118/VALIDATION.md).

Host checks are distinct from Android device/game testing. These new performance changes have not been measured on a physical Android device by the build environment. Low total CPU/GPU utilization does not exclude a limiting thread, synchronization, copy/upload or scheduling cost. Diagnostic call rates are not necessarily game FPS; nested host times overlap and are not GPU hardware execution time. Collection itself adds overhead. Existing third-party 16 KiB-page limitations remain.

For ordinary play, keep Debug off. For comparison, use the same save/scene, resolution, graphics settings and similar temperature; fully restart after changing options. If a problem remains, enable Debug for a separate fresh run and export the diagnostic ZIP. Required clients, anti-cheat/DRM, installer updates and unsupported graphics features can still prevent compatibility.

## APK checksum

`Aura-0.1.118-arm64-release.apk` — 369,681,621 bytes

```text
cfb0e55e580d09e97c2aa2a7b09a0b74284b4b544e21036a1e3b26c3f650df44
```
