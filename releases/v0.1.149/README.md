# Aura 0.1.149

**Android 14+ · ARM64 · version code 150 · `com.android.aura`**

This public release includes every change from 0.1.119 through 0.1.149 since the previous public release, **0.1.118**. It publishes the existing signed 0.1.149 APK unchanged, with the same upgrade certificate, R8 optimization and resource shrinking.

## Download and upgrade

- [Aura-0.1.149-arm64-release.apk](https://github.com/topoftree/Aura/releases/download/v0.1.149/Aura-0.1.149-arm64-release.apk) — 405,044,703 bytes.
- [SHA256SUMS.txt](https://github.com/topoftree/Aura/releases/download/v0.1.149/SHA256SUMS.txt).
- [Matching editable Winlator engine and application relinking materials](https://github.com/topoftree/Aura/tree/main/winlator-source-and-relink/v0.1.149).
- [Runtime sources, patches and build inputs](https://github.com/topoftree/Aura/tree/main/runtime-source/v0.1.149).

Fully exit the game/Wine session before installing over Aura. Keep the existing app and data; do not uninstall as an update step. Back up important saves. This is the same APK previously distributed as 0.1.149, so existing 0.1.149 users do not need another installation.

```text
530f734c3ea7f8480b147f67f30e32472c9bdf0804b2b5a65f0fefd1c32b3e26  Aura-0.1.149-arm64-release.apk
```

## Current changes and behavior

- **Translation and cache:** Crysis 2's compatible SMC validation path uses smaller bounded translation blocks and shared invalidation exits. The generic compact-translation option is per game and defaults on; it does not force the Crysis compatibility mode on unrelated games. FEX IR block-tail bookkeeping avoids repeated walks. Cache-hit/lifecycle diagnostics and optional 32/64 MiB RAM buffers support controlled comparisons. Buffers require translation caching and are skipped for insufficient memory or incompatible modes. Hardware TSO remains an opt-in experiment requiring actual kernel support; software ordering is retained on failure. SVE is not enabled.
- **Frame delivery:** More selective SHM dirty-region copies, corrected shared-memory lifetime, compatible Mesa allocation caching, destination texture fence handoff and due-buffer returns during eligible waits. Short waits now round up to the remaining millisecond deadline with bounded attempts. GPU completion and alias protection remain enforced. These changes target avoidable work and irregular buffer turnover; they do not guarantee 60/120 FPS.
- **Drivers:** Turnip 26.2.5 is bundled as the full-source `aura-ext1` build and is the default only on eligible Adreno devices. Driver selection retains device/API/page-size checks. Qualcomm System Wrapper and imported drivers use the compatible DXVK family; Turnip uses its separate profile. Loader namespace resolution, UUID handling and initialization faults have targeted fixes. Original Turnip remains a compatibility fallback.
- **Optimization:** Shared GPU images follows the selected **R8 or 26.2.5** driver and no longer silently selects R8. Unsupported driver-specific choices are disabled with an explanation in both Game Management and the in-game menu; preferences are retained when switching drivers. The three 26.2.5 tuning options require that selected driver. Universal CPU/cache options are not wrongly disabled by driver choice. Runtime AHB/EGL checks and ordinary-copy fallback remain.
- **Defaults and reset:** Compact translation and pausing Aura background downloads/scans default on. Smart performance suggestions and experimental translation-cache/RAM-buffer, shared-image, hardware-TSO and D3D11 batching controls default off. Existing saved choices take priority. Restore defaults resets only the current game's Optimization settings and preserves resolution, frame limit, compatibility, patches and other games. Runtime/driver changes require a complete restart; only supported host policies change immediately.
- **Stardew Valley / SMAPI:** Native system-information compatibility, a Mod commands window, saved commands dispatched once after SaveLoaded, command-result readback, and separate Update/Draw/host presentation timing. Automatic commands are opt-in; enter `fps 120` with a space. Loading a mod alone does not prove its target FPS took effect, and commands can alter game speed.
- **Game management:** Separate Patch and Mod pages, remote patch catalog/import and backups, SMAPI-aware startup, controller and startup compatibility grouping, aspect-ratio Wine display modes and Battlefield 3 display diagnostics. Cuphead UltraWide is listed through the author's original download links; its third-party DLL is not mirrored or represented as verified on Aura.
- **Steam, files and startup:** Managed Steam startup/readiness improvements; a Wine DNS fallback after native lookup failure and a certificate-import FD ownership fix; cached-account transitions; clearer runtime mismatch errors and bounded Copy diagnostics. File navigation separates page-back and directory-back, adds refresh, improves scrolling/wrapping and keeps transactional import boundaries. Shader caches are managed automatically; clear requests apply at launch. Wine language and Local manual Client detection controls were removed while underlying configuration/detection remains.
- **Visuals:** The new transparent Aura mascot appears in the Android icon, startup presentation, default wallpaper and repository README. Manrope plus Noto Sans SC typography, Phosphor regular/focused-fill UI icons and Kenney controller hints. Unknown controller artwork falls back to Xbox hints. Face/shoulder hints have balanced thinner strokes. Loading indicators use the morphing-line animation. Themes use deeper preset colors and wallpaper-based Dynamic color. Ordinary text shares page contrast; focused controls use their own background contrast.
- **Launcher and Library:** Launcher game titles use 20 sp Regular, shrink toward 16 sp for long names, then wrap. Titles align with the selected cover; the bottom-to-top wallpaper fade stays below the cover bottom. Library wallpaper has a lighter blur. Launcher Select opens the cover action menu; retained-session focus and notifications preserve their own routes.
- **In-game menu:** A full-height left menu, clear right-side poster/title without a title pill, shared Optimization controls, and coordinated backdrop blur for both loading and gameplay. System blur and app fallback are mutually exclusive. The gameplay fallback uses a continuous separable Gaussian kernel to remove the former strip-like ghosting; no CPU screenshot or continuously running extra refresh loop is introduced.
- **Controllers and notices:** Connection notices use a filled controller icon and actual mapped hints. Temporary connection guidance no longer leaves old hints on screen. Controller identification, raw-input testing, hold-to-exit behavior and virtual-pad layouts/touch feedback are improved. Notifications share the same right-side presentation and double-Select entry.
- **Diagnostics:** Explicit bounded startup/game captures now cover FEX/cache activity, thermal/memory and CPU features, Vulkan calls and first device loss, command-buffer ancestry including direct secondary summaries, buffer/present/wait/X11 timing, and bounded color observations. Current manual capture keeps the full ten-second performance window before PC sampling and adds thread scheduling/I/O evidence. Missing, dropped and cross-boundary observations stay unknown; exported observations are not physical display FPS or a cause diagnosis by themselves.

## Known limitations and verification

Original Qualcomm-driver Cuphead dark output and Metal Gear Solid: Ground Zeroes loading/device-loss issues are **still under investigation**. This version adds pre-present color sampling, command V4 observations and a narrowly scoped OEM rendering compatibility trial; it does not claim either phone issue is fixed. Community-driver stutter can still have multiple causes. Offline/login messages are only an observed clue; this update does not change account login to address them.

The official APK passed 217 freshly run JVM release/runtime/UI tests and six Python analyzer tests. Release Lint: 0 errors, 97 warnings. Actual APK hashes, signature, alignment, runtime/driver contracts, JNI/reflection, fonts/icons and 351 source assets were checked. Previously completed native tests were reused only after exact unchanged input/output hash verification. Host tests do not establish phone gameplay, SVE support, sustained FPS or complete 16 KiB compatibility of third-party binaries.

The public kit separately passed baseline recombination and a Java/native/resource-modified engine rebuild, stable resource IDs, JNI/Steam/navigation checks and signing with a temporary recipient key. Independent application source, original objects, private mappings, production signing and user captures remain private. This version's source/object materials use ordinary Git; older versions retain their historical LFS requirements. Release attachments contain only the APK and checksum.

## Complete cumulative chronology

The following version records cover every intermediate release since 0.1.118. Historical findings are not claims about the current build; the overview and limits above describe 0.1.149.

<details>
<summary>All changes in 0.1.119–0.1.149</summary>

## 0.1.119

This update makes the loading controller hint easier to see and delivers the
pending per-game patch manager, compatibility settings and Mesa copy scheduling
changes since 0.1.118.

- **Loading controller hint:** a soft ring expands and fades around the marked
  button over a 2.8-second cycle. The button keeps a brighter minimum highlight
  and a subtle fill. Xbox, PlayStation, Switch Pro, handhelds and custom mappings
  use their actual button bounds. The controller and button stay still. Inactive
  pages and disabled system animations use a static marker and ring. Existing
  ten-second expiry, input dismissal, double-press and hold behavior are retained.
- **Game Management → Compatibility:** group stability mode, ESync, Skip Steam,
  startup movies and client detection separately from performance options.
  General Wine/DXVK base fixes remain integrated. CPU, caches and D3D11 batching
  remain under Optimization.
- **Game Management → Patch:** choose Import patch or Download patch. The
  download page reads the signed public topoftree/auragamepatch catalogue and
  matches the current executable, including Local games. Package size, hash,
  contents and supported operations are verified before installation. Replaced
  files are backed up on the device. Removing an installed patch restores its
  originals; changed files, damaged backups or overlapping newer patches block
  unsafe restoration. Interrupted changes block launching a half-patched game.
- **Game-specific fixes:** Battlefield 3 single-player compatibility is confined
  to bf3.exe; Mirror's Edge timing and South Park low-address edits come from
  version-matched remote packages. Ground Zeroes startup support is enabled by
  a game-specific downloaded profile and still depends on Aura's Wine/FEX
  runtime. Previously applied fixes and their backups are retained with a
  legacy restore path. Resetting launch preferences does not erase patch backups.
- **Widescreen packages:** MGSVFix is installable for Ground Zeroes with a
  per-game winmm override. The Mirror's Edge package is an interactive Windows
  .NET 10 tool: downloading it does not apply the widescreen fix, and removing
  the downloaded tool cannot undo edits subsequently made by that tool.
- **Steam Library details:** show the full available release date and studio
  information, retaining the existing description.
- **Automatic Mesa frame copy:** large verified copies use 256 KiB chunks shared
  by the calling thread and up to two reusable helpers, replacing fixed thirds.
  This reduces dependence on the slowest fixed segment while preserving every
  pixel and waiting for completion. It is enabled automatically, independently
  of CPU optimization and Debug. It does not lower resolution, change the game
  clock or remove full-frame readback. Debug can report actual chunk counts and
  caller/helper bytes through MESA_FRAME_COPY_SCHEDULE_V2.

The separately published **StardewFPS** package is available through the patch
catalogue. It is not bundled or automatically enabled by this APK. It requires
Windows SMAPI and manual console configuration; raising the cap changes animation
and enemy speeds, and its author does not recommend it for normal play. It is
not a fix for performance below 60 FPS.

Fully exit the game and install as an update, keeping app data. This version
includes the new Mesa scheduling code; no CPU optimization switch or SMAPI
installation is needed to use that optimization. Compare the same scene,
resolution, settings and temperature when testing it. Physical Android FPS,
power consumption, patch compatibility and animation smoothness remain untested.
There is no claim of stable 60 FPS in Stardew Valley.

Signed Release with R8 and resource shrinking. Full JVM/Robolectric suite:
1735 tests, zero failures/errors/skipped. Release lint: 0 errors,
77 warnings. Package, signature, alignment, JNI, Steam reflection,
navigation themes, runtime reconstruction and offline license checks pass.
Native dynamic-copy guard-page, fallback, cancellation and fork checks are
recorded separately. Existing runtime 16 KB limitations are unchanged.

Android 14+ / ARM64 · version code 120 · 369,737,745 bytes.
Upgrade certificate matches 0.1.118.
SHA-256: 339958cd98934ed786a6829f4a445da08a96bf2b4c93c3ef1ce770bc1f33214e.

---

## 0.1.120

This update delivers controller tools, first-launch compatibility setup, per-game
performance advice, the left-side game menu, and additional startup diagnostics.
It includes the pending changes since 0.1.119.

- **First-launch compatibility:** Compatibility switches start off for a new
  game. The first real launch examines the selected executable and available
  client evidence, saves the applicable configuration, and uses the same result
  for dependency preparation and game launch. Explicit manual choices, including
  Off, remain authoritative. Steam and Local settings remain separate.
  Static detection is bounded and does not guarantee compatibility. Skip Steam
  is automatic only for the existing verified game-ID/executable combinations.
- **Startup movies:** keep the option on the main Game Management page, off by
  default. It is excluded from automatic Compatibility selection. Previously
  saved manual selections are preserved.
- **CPU / GPU adjustment advice:** add a per-game assessment, off by default,
  with a system-settings shortcut and a saved recommendation. Sampling waits
  for observed active game content, considers the busiest game thread, readable
  GPU load and thermal state, and stops after producing a recommendation.
  Unavailable data is not treated as zero. This build does not set CPU/GPU MHz,
  alter thermal protection, or claim that a recommendation changed frequencies.
- **Settings cleanup:** remove game-update options from Local while retaining
  component/driver updates. Retire Mirror's Edge Stall capture, including old
  settings that could start automatic sampling; the general manual Debug
  capture remains available.
- **Controller identification:** show the connected model in controller
  settings. Recognize Backbone and GameSir X5 Lite, with a new X5 Lite drawing.
  Other models currently use Backbone artwork without changing their actual
  device name or button mapping. Artwork and navigation presets are independent.
- **Controller test:** show the current controller and animate the buttons that
  Android actually reports. Include both sticks, their X/Y values and clicks,
  triggers and simultaneous buttons. The page owns controller input and exits
  only through its on-screen back control. Disconnecting or losing focus clears
  held states; leaving restores ordinary app controls. System-intercepted keys
  are not represented as successfully tested.
- **Connection notification:** show one temporary right-side model notification
  for each new connection, using the shared sound and ten-second timeout. It is
  not retained in Messages. The main interface no longer keeps the model name
  permanently visible outside controller settings and its test page.
- **In-game menu:** slide a full-height panel in from the left, with a moving
  translucent background, display-inset handling and scrollable contents. Play
  the shared soft notification cue once per opening, respecting app sound and
  volume preferences. Recomposition and background/foreground changes do not
  replay the cue.
- **Generic startup diagnostics:** manual Debug ZIPs now record bounded X11
  window coordinates, sizes, desktop/viewport relationship and the Wine display
  configuration result separately from the truncated launch journal. If native
  PC sampling meets a signal-delivery stop, record its signal type and execution
  position separately before forwarding the original signal. Caps, identity
  checks, deadlines and stopped-process protections remain in place. These
  changes apply to Steam and Local games and do not move windows or change GPU
  identity, game configuration, rendering or input behavior.

**Crysis 2 remains under investigation.** The two-minute report shows no sustained
new visual content while the main thread consumes approximately one core. DXVK
has created a D3D11 device and available memory remains around 3 GiB, so the old
video-card warning alone does not establish unsupported hardware. The window
position after a desktop-size change is a separate candidate issue. Previous
captures lost the main thread's PC at signal stops. This APK adds the missing
observations; it is not a confirmed fix for the cropped image or startup stall.

Fully exit the game and install as an update, keeping app data. To continue the
Crysis 2 investigation, enable Debug mode before a fresh launch, reproduce the
stuck screen, then collect and export a new game diagnostic ZIP. No CPU
optimization switch is needed for the additional diagnostics. Raw diagnostic
archives are not uploaded with the source.

Signed Release with R8 and resource shrinking. Full JVM/Robolectric suite:
1786 tests, zero failures/errors/skipped. Release lint: 0 errors,
79 warnings. Signature, alignment, JNI, Steam reflection,
navigation themes, runtime reconstruction and offline licenses were checked.
The native observer passed 24 Linux child-process/UBSan groups and Android ARM64
cross-compilation. These are host checks; no physical Android device was tested.
Controller behavior, animation smoothness and Crysis 2 compatibility remain
subject to device testing. Existing runtime 16 KB limitations are unchanged.

Android 14+ / ARM64 · version code 121 · 369,822,553 bytes.
Upgrade certificate matches 0.1.119.
SHA-256: ecea37f873d76be82557d384d9b57987c6260505d7ca9dd9e61f0cacab0dcfc4.

---

## 0.1.121

Android 14+ / ARM64. Install over the existing app after fully exiting games; keep application data.

Changes since 0.1.120:

- Add **Optimization** to the in-game left drawer. Checkboxes save settings for the current game. Smart performance suggestions and pausing Aura background downloads/scans apply immediately; translation/precision, caches, DXVK options and Vulkan changes clearly require a full game restart. Driver selection retains hardware eligibility checks. Returning to a paused game does not apply restart-only settings.
- Remove **Wine language** from Settings. Existing guest-language preferences are preserved and old navigation returns to Advance.
- Show shared right-side notices when **Startup capture actually starts and ends**, including time/size limits, game exit and disabling Debug. The notices use the existing sound, 10-second lifetime and Select focus. Start/end events queue separately across Activity transitions, with explicit save-failure wording and export guidance.
- Add **Optimize**, **Diagnose** and **Patch** to Library game details. Explicit optimization launches the game and collects bounded observations, then saves supported adjustments for the next full launch. Diagnosis rescans supported executable/dependency evidence and preserves manual Compatibility choices. Patch is moved out of Game Management.
- Manage installed patches below Download patch. Enable/disable, uninstall while keeping an offline package, or delete Aura-owned patch data; restore backed-up game files/settings with conflict and ownership checks. Source imports remain read-only.
- Import ZIP packages or extracted patch files using a two-column browser: choose files on the left and Auto or a game-contained destination on the right. Controller left/right switches columns. Auto relocates only recognized package layouts; manual destinations stay inside the selected game.
- Add pinned official Windows **SMAPI** installation and an explicit loader launch policy for supported Stardew patches. Installing/selecting a loader is not proof that a mod loaded or that higher FPS works.
- Controller testing can exit by holding the current Select/menu binding for 1.5 seconds, with progress and time remaining, or by touch. Controller connection notices include a controller icon and a bottom shortcut hint; double Select opens controller settings. Correct the hollow View-button artwork.
- Improve Steam startup presentation: first-login launches remain visible; returning sessions may use `-silent`. After a verified updater phase, an unresponsive silent presentation gets a bounded foreground request and a shared notice/manual recovery action. Authentication still requires the official client; no credentials or IPC readiness checks are bypassed.
- Fix Wine GPU-memory DWORD saturation for older applications such as Crysis 2, preserving the full QWORD report. This addresses a confirmed memory-report defect; the cropped image and startup stall still require device retesting.
- Reduce redundant per-Swap Mesa geometry queries and add bounded reusable host frame-copy workers for the common presentation path. These are built in and do not require CPU optimization or SMAPI. Diagnostic counters distinguish actual use from configuration; stable 60 FPS in Stardew Valley is not guaranteed.
- Extend opt-in native descriptor-lifetime diagnostics for the unresolved Steam client failure. Existing fatal fdsan behavior is preserved; collecting extra evidence is not a claim that the underlying FILE ownership fault is fixed.

Validation and limits are recorded alongside the source. No physical Android device, live Steam login, controller or game session was tested in this cloud environment. GPU time/power/frequency measurements remain limited by platform access; Aura does not directly set MHz. Existing 16 KB limitations of some bundled native libraries are unchanged.

Build: **370,036,909 bytes**, version code 122. Upgrade signing certificate matches 0.1.120.
1873 JVM/Robolectric tests passed, including the pinned official SMAPI fixture;
release lint: 0 errors, 80 warnings.
R8 renamed 1239 host classes; mapping is retained privately outside Git.
SHA-256: `555407c9a19274e871ea8f2518d4052baf604aebf76bb22454d2ed28a9e2dea6`.

Validation (internal build record).

---

## 0.1.122

This signed update delivers the local changes made since 0.1.121. Install over the existing app and keep its data. Fully close games before updating.

### Crysis 2

- Adds the optional **Crysis 2 (1.9, 32-bit) · DX9 renderer** profile in Game Management → Patch → Download patch. Download and enable it, then fully restart the game.
- The verified game build filters `r_Driver` from CFG files, so the earlier one-line diagnostic CFG does not select DX9 even when correctly imported. This profile uses the game's native `-dx9` branch instead.
- Requires the exact supported executable size and SHA-256; checks during installation, re-enabling, and new launches. No executable, CFG, resolution, movies, or saves are rewritten. Disabling restores the original launch arguments. Uninstall retains the local profile; delete removes it. It is never automatically installed.
- Actual DX9 renderer loading, progress past startup, and cropped-screen improvement on Android remain unverified. DX9 can change available DX11 visual features. Close or uninstall the earlier diagnostic CFG through its existing managed entry; do not repeatedly move it or reinstall the game because of that CFG alone.

### Rendering and Steam startup

- Built-in, game-independent SHM optimization detects unchanged outer rows of eligible full-frame updates, copies/uploads only the enclosing changed rows, and avoids redundant transfers for identical content. It preserves resolution and rendering semantics. Unsupported buffers keep the existing path. Stardew Valley needs no SMAPI or extra switch to use this runtime optimization; stable 60 FPS on a phone is not claimed.
- Managed-client loading no longer ends permanently just because a Steam client/store window appears, including in Debug. Login presentation requires an attributable login/Guard screen; the game-ready gate stays independent from Steam IPC readiness. Manual client viewing remains available.
- Adds bounded Steam login wait reasons and preserves account files. A foreground request is not treated as successful login; live account/network recovery still needs device verification.

### Controllers and Launcher

- Adds semi-transparent Virtual pad with Xbox, DualSense and Switch Pro styles, plus opacity levels. It appears in game without a physical controller when enabled, and immediately hides/releases inputs when a controller connects or the menu, keyboard, focus or session changes.
- Select toggles rectangular actions beside the focused Launcher cover. Down focuses the running game when present, otherwise gives a small cover feedback animation. Down no longer opens the old action rows; Select no longer jumps to a background game.
- Groups presets, Custom and Auto configuration in Controller settings → Configuration. The test page has two crosshair stick displays, simpler hold-to-exit text and a title-free progress bar, no mascot behind readouts, no redundant Released suffixes, and D-pad sound feedback.
- Connection notices show View/Select, confirm, back and shoulder navigation hints using current bindings. Model notices remain temporary and stay out of Messages.
- Fixes the hollow overlapping View-button rectangles. Search-button hints move left of the magnifier and disappear when no controller is connected; Library filters align with the corresponding header control. Local Choose an executable sits below Start game.

### Themes and game content

- Theme now separates Color theme from Theme mode. Primary/secondary colors can be selected independently, including red, blue, black, purple and orange; Dynamic follows the successfully launched game's artwork. App backgrounds and startup UI share the selected palette; game rendering is unchanged.
- Moves Patch back into Game Management and adds a separate Mod page with multi-selection import. Both support lifecycle controls and scoped backups. Mod Auto recognizes supported SMAPI/BepInEx/Unreal/existing Mods layouts; ambiguous files require a destination within the current game.
- Introduces the signed, classified v2 patch catalogue and exact-version automatic-repair infrastructure. Only explicitly opted-in repairs qualify; manually disabled/uninstalled/deleted patches stay suppressed. Existing optional packages, including Crysis 2 DX9 and FPS mods, remain manual. SMAPI dependencies retain their separate explicit-mod policy.

### Validation and limits

Host tests, release signing, R8/resource shrinking and APK verification are recorded with this build. No commercial game file or raw user log is redistributed. No physical Android, controller, live Steam login or game performance test was performed; changes and diagnostics do not prove that all reported game issues are solved.

Build: **370,170,785 bytes**, version code 123. Upgrade signing certificate matches 0.1.121.
1936 JVM/Robolectric tests passed with no failures/errors/skips, including the official SMAPI fixture and the private, read-only Crysis 2 input.
Release lint: 0 errors, 84 warnings. R8 renamed 1287 host classes; mapping stays outside Git.
SHA-256: `b8970b797dbdda87c59cb29faaab97b23399106c3e67b0af407f958516c25354`.

Validation (internal build record).

---

## 0.1.123

This update improves manual game diagnostics and makes per-game shader cache management automatic.

### Manual diagnostics

- Manual capture and worker validation now inspect up to 256 thread entries. The periodic monitor retains its existing 64-entry budget.
- Reports distinguish the enumeration limit, unreadable threads and directory failures. Incomplete scans do not produce a misleading full CPU total or a hottest-thread ranking.
- Signal-delivery stops may include a validated nearest FEX guest-code mapping. Original signals are forwarded unchanged; these observations remain separate from ordinary PC samples and do not imply a fatal crash.
- The capture summary counts interrupted batches and missing workers separately. The existing six-batch sampling budget, identity checks and timeout cleanup remain in place.

### Automatic shader cache management

- New game sessions automatically isolate and reuse managed shader caches by game, container, runtime and driver. Existing custom settings, storage limits and fallback policies remain respected.
- The manual managed-cache toggle is retired. Game Management and the in-game Optimization menu offer a cancellable request to clear this game's managed cache before its next full launch.
- Running sessions, game files, saves and other games' caches are not cleared. Automatic optimization no longer reports enabling this built-in policy as a new adjustment.

### Crysis 2 follow-up

User logs confirm that the existing optional DX9 profile is active and the reported picture is now complete. The remaining static startup-logo problem is **not yet resolved**. Previous diagnostic captures lacked regular thread PCs; this update addresses that evidence gap, rather than claiming a startup fix.

Keep the existing DX9 profile enabled. Fully exit the game before updating Aura, then collect a new in-game diagnostic bundle while the logo is stuck. There is no need to reinstall the game or import the old CFG again. A current game-root `Game.log`, if available, complements the bundle; older LogBackups must be matched by launch time.

### Installation and scope

Android 14 or later, ARM64. Install over the existing Aura app and keep its data. Signed Gradle Release with R8 optimization/obfuscation and resource shrinking; existing upgrade certificate retained.


Build: **370,181,521 bytes**, version code 124. Upgrade certificate matches 0.1.122.
1949 JVM/Robolectric tests passed with zero failures/errors/skips, including the pinned official SMAPI and private read-only Crysis 2 fixtures.
Release Lint: 0 errors, 84 warnings. R8 renamed 1290 host classes; mappings remain outside Git.
SHA-256: `f1acaac1dcb7b1396a553cf4e4fb1f8d484cefcb1f5751e456988eee0b0f28e1`.

Validation (internal build record).

---

## 0.1.124

Changes since 0.1.123:

- Shared-memory frames with separated changes now copy and upload up to four changed regions instead of including all unchanged rows between them. Bounded detection, full-image fallback, pixel contents and synchronization are preserved. This is a general default optimization used by Stardew Valley's observed path, with no new switch. GPU readback is unchanged; stable 60 FPS has not been demonstrated on a phone.
- Virtual pad feedback recomposes only when the visual state changes and suppresses duplicate unchanged input packets. Real stick motion, press/release, cancellation and controller hot-plug remain immediate. One UI's overlay FPS is not a measurement of game-produced frames.
- App file browsers separate page return (top) from parent-folder return (below). Up from the first available entry focuses parent-folder return, then page return. B/system Back continues to navigate the directory. Patch/Mod pane boundaries and import transaction safeguards are preserved.
- Local Compatibility no longer shows manual Client detection; launch dependency checks and Library diagnosis remain available. Steam keeps its manual entry.
- Native diagnostics now retain bounded executable code/reference context at signal-delivery stops, addressing missing context on the busy Crysis 2 leader. Signal observations stay separate from normal samples; original signals are forwarded unchanged. No raw stack dump or additional attachment/retry is introduced.

Crysis 2: the new capture confirms complete 67-thread scans and working-thread observations in Wine reply/yield paths, with one code match to FEX's shared compilation lock. This does not establish a deadlock or a fix. The static startup-logo stall is **still unresolved**. Keep the existing DX9 profile enabled; no game or patch reinstall is required for this update. If it still stalls, export an in-game diagnostic while stuck to include the new signal context. The supplied latest LogBackups file predates that run; a current root Game.log must be matched by its internal start time.

No new game-specific patch, CPU/GPU frequency override, game-file change or forced configuration was introduced.

Build: **370,197,081 bytes**, version code 125. Upgrade certificate matches 0.1.123.
1963 JVM/Robolectric tests passed with zero failures/errors/skips, including pinned SMAPI and private read-only Crysis 2 profile fixtures. The game EXE was not executed or published.
25 native diagnostic groups were rerun against disposable Linux children with UBSan; package/source hashes checked.
Release Lint: 0 errors, 84 warnings. R8 renamed 1301 host classes; mappings remain outside Git.
SHA-256: `d3a12b9b053b25726272f81e4c5280976e2dd90ffae5534b4cbfea572ed244a1`.

Validation (internal build record).

---

## 0.1.125

Changes since 0.1.124:

- The verified Mesa SHM display-target copy now compares exact 4 KiB blocks and omits identical destination writes. It reuses the bounded synchronous copy pool and returns to full chunk copying after eight consecutive changed blocks. Other copy callers keep their previous behavior. Enabled automatically; GPU readback and synchronization remain unchanged. No game patch, SMAPI or CPU-mode change is required. New native counters distinguish configuration from actual use. Stable 60 FPS is not yet verified on a phone.
- File browsers use a compact horizontal header: the icon-only page-return button sits outside the narrower rectangular selection frame on the left; directory return and entries remain inside. The visible “Back to page” label is removed while its accessibility name remains. B focuses directory return without navigating; Left/Right selects a return button and A confirms it. File lists wrap from first to last on Up and last to first on Down, skipping disabled rows. Patch/Mod content retains independent pane switching.
- Successful file exports no longer leave a permanent “Saved … Find it in the location you selected” line or empty header space. Progress, errors, cancellation and skipped-file warnings remain available.
- Aura-downloaded Steam games are registered in the official client library before a new client starts, using managed manifests and links to the existing game directory. This addresses the missing installation registration without copying the game or overwriting an existing Steam installation. Actual login/IPC readiness remains required; current-session connection diagnostics are kept separate. Moonlighter launch recovery remains unverified on a device.
- The Android system splash logo is scaled into its safe area, preserving the octopus outline and cutouts.
- Native diagnostics recognize validated WOW64 FEX entry pairs as well as ARM64EC, preserving strict boundaries and signal delivery. This improves evidence for Crysis 2; its static-logo stall is not confirmed fixed. Keep the existing DX9 profile; reinstalling it is not required for these changes.

Build: **370,220,241 bytes**, version code 126. Upgrade certificate matches 0.1.124.
1991 JVM/Robolectric tests passed with zero failures/errors/skips, including pinned SMAPI and private read-only Crysis 2 profile fixtures. No commercial game EXE was executed or published.
Graphics native checks were rerun with ASan/UBSan and protected pages, including the real ELF import, exact regions, helper failures, fork and opt-out cases. The 26 earlier native diagnostic test groups still match the build's source hashes; they were not rerun during packaging.
Release Lint: 0 errors, 84 warnings. R8 renamed 1310 host classes; mappings remain outside Git.
SHA-256: `1a23be2707d2c3dc73d51e84082cf07d47598b7990f867f61c38111460de22ce`.

Validation (internal build record).

---

## 0.1.126

Changes since 0.1.125:

- Startup capture and manual Game Debug exports now include bounded Android/Wine DNS, TCP and HTTP(S) control probes. Steam connection logs additionally report session-only, sanitized HTTP/CM/WebSocket failure counters and recent event order. These controls distinguish network stages; they do not claim that Steam authentication or Moonlighter startup is fixed.
- Add the previously prepared per-game experimental hardware TSO request with kernel readback, software-ordering fallback and persistent translation-cache isolation. Unsupported hardware is reported explicitly; this is disabled by default.
- Fix shared-memory mapping ownership and drawable lifetime cleanup. Diagnostic mapped bytes are not presented as physical memory savings.
- Refine generic Mesa SHM copies with exact 64-byte region writes. Stardew Valley reaching stable 60 FPS still requires device testing.
- Add bounded Wine code-protection observations, FEX interval counters and current-session game engine logs for investigating Crysis 2. Its static logo remains unresolved.
- Compact file-browser headers: Refresh is a header button, ZIP export is the first row, thicker/taller frame, import destination in the header and higher Patch files / Import location titles. Preserve page/path focus behavior and remove the Switch side hint.

Release uses the existing signing identity, R8 and resource shrinking. Install over the previous version to retain data. Enable Debug and Startup capture, fully restart the failing game, then export the new ZIP. Network probes stop on pause/cancellation and run for at most 20 seconds; a normal gameplay session does not continuously probe the network.

---

## 0.1.127

Changes since 0.1.126, for Android 14+ / ARM64. Signed Release with R8 and resource shrinking.

### Shared GPU images (experimental)

- Adds a per-game switch in Game Management and the in-game Optimization menu, disabled by default and applied after a complete game restart. It is excluded from automatic optimization and installers.
- When enabled on an eligible Adreno / 4 KiB device, this launch automatically selects Turnip 26 R8. The saved driver and container remain unchanged; disabling the experiment restores the saved driver on the next launch. Unsupported devices or failed preparation retain their original driver.
- An optional Zink module attempts double-buffered Android HardwareBuffer transport, a GPU blit and host EGL import, with producer/consumer synchronization and on-demand X11 readback. CPU fallback remains available; disabling the experiment uses the original Mesa module.
- Diagnostics separate the request, import, host acceptance, readback and failure counts. These counters are not game FPS. The extra image buffers and conservative synchronization can cost memory and time. Real-device image correctness, driver support and stable Stardew Valley 60 FPS remain unverified.
- To compare on Stardew: leave hardware TSO off, enable Shared GPU images, fully stop and restart the game, then capture the same scene while moving. Hardware TSO was unsupported in the supplied S24 Ultra capture. A setting alone is not evidence that shared GPU images were used.

### Crysis 2

- Includes the exact-build inline SMC experiment in the FEX runtime, without a downloadable game patch. It checks the complete known Crysis 2 1.9 x86 executable hash and additionally checks process identity in WOW64.
- Uses full instruction-content validation in place of synthetic page write protection for that verified process, while retaining actual access faults and invalidation after mapping/protection changes. Persistent translation caching and the Mono special case are disabled for that process without overwriting user preferences. Other games and installers retain their existing behavior.
- The existing verified DX9 profile remains independent. Reinstalling or moving configuration patches is not required for this runtime update. Passing the static logo on a phone has not been confirmed.

### Steam network diagnostics

- Installs the verified Wine network probe atomically inside the current prefix and launches it with a fixed DOS path. Restores filtered IPv4/IPv6 records and captures bounded lifecycle, exit-code and output-size information.
- Missing DONE records, abnormal exits and incomplete output are reported as incomplete comparisons. Android control-request success is not Steam login success. Account, proxy, DNS, certificate-verification and save settings are unchanged.

### File browsers, themes and menus

- Refresh is immediately beside the directory-return icon, with controller left/right navigation between them. Directory and import-destination labels follow the button group.
- File selection panels use an opaque secondary theme color, contrasting content/focus colors and a subtle raised shadow. Page return remains outside the panel.
- Primary and secondary colors are selected from two rows of rectangular swatches, with spring focus feedback and selected borders. Secondary presets use distinct shades.
- Wallpapers and the mascot retain their original colors. Ten independently generated dark/light background images cover the red, blue, black, purple and orange presets without color filters.
- Focused menu rows lift slightly with a soft shadow. Top navigation highlights are rectangular, attach to the upper screen edge and have small bottom corner radii.

### Delivery and validation

Release verification: 46 distinct related regression cases, Release Lint (0 errors, 85 warnings), 326 asset comparisons, 12 JNI fields, 24 Steam reflection contracts and signature/alignment checks passed. R8 renamed 1,337 host classes; mappings and signing material remain outside publication.

---

## 0.1.128

Changes since 0.1.127, for Android 14+ / ARM64. Signed Release with R8 and resource shrinking.

### Startup and network diagnostics

- Adds helper-local native DNS comparisons to existing Startup capture and manual Debug exports. Wine's original libc lookup is preserved, then compared with a lookup through the same Android Network used by the host control. Reports bounded raw error codes, address-family counts, network-binding relationships and network changes without collecting addresses, account details or response bodies.
- Requires Android, Wine and native completion records before calling a comparison complete. Missing modules retain the existing controls; cancellation and the 20-second diagnostic budget remain in force. This does not change Steam's DNS, proxy, account or certificate settings.
- Captures bounded candidate native modules and file offsets for early Wine access faults, before a game window or game-thread sample exists. It reads only session-owned process mappings, labels uncertain ownership and omits full paths and stacks.
- Preserves complete launch-configuration snapshots in both startup and manual exports, including the Crysis 2 inline SMC mode.

### File browsers, focus and themes

- File selection panels retain their border and wallpaper without an opaque secondary-color background. Focused files and folders use the primary theme color and its contrasting foreground.
- Settings and menu focus stays flat, with a small scale/lift effect and a soft fading shadow. Removes the previous raised-edge styling.
- Deepens and saturates theme presets. Color swatches have rounded corners and no visible color names, while retaining accessibility labels. Selection borders are spaced away from the fill: white in dark mode, black in light mode.
- Aligns Local Library's Add Game button with the search icon, with or without controller hints.

### Known device findings and test guidance

The supplied Stardew Valley capture fails during Wine X11 initialization when R8 is selected. The user also reports startup failure with shared GPU buffers disabled and R8 selected alone. The exact faulty native library is not identified yet; this release adds the evidence needed to investigate it and is not a confirmed R8 startup fix. Temporarily disable shared GPU images and select ordinary Turnip for normal play. To investigate R8, disable sharing, select R8, enable the next Startup capture and fully restart the game once.

The supplied Moonlighter controls show Android connectivity but Wine DNS failures. Use the existing Debug startup or manual capture on this version to obtain the native comparison. Steam login recovery, stable Stardew 60 FPS and Crysis 2 reaching its menu are not claimed as verified outcomes. Device installation and gameplay testing remain pending.

---

## 0.1.129

Changes since 0.1.128, for Android 14+ / ARM64. Signed Release with R8 and resource shrinking.

### App entry and file navigation

- Shows Aura's loading screen when opening the app, before the Steam / Local picker. Runtime errors retain retry and diagnostic actions. Selecting or switching platforms no longer replays app initialization; Steam account restoration uses the existing cancellable sign-in flow, and Local does not wait for Steam connectivity.
- Hides the circularly clipped monochrome icon in Android's system splash and removes that splash as soon as the first frame is ready. The launcher icon and Aura's own full-color loading artwork are retained. Android may still briefly display its neutral starting background.
- Extends Settings lists closer to the bottom when no controller hint is visible, retaining safe insets and space for actual hints.
- Removes the full file-list border. Only the navigation header is framed; the current location and details appear immediately after Up and Refresh. The page-back arrow remains outside the header, and controller navigation is unchanged.

### Crysis 2 compatibility experiment

- Adds a per-game **Fast instruction checks** option under Game Management > Compatibility. It is off by default and requires a full game restart.
- For the verified Crysis 2 executable and supported native process only, the option requests complete byte comparison for short instructions instead of CRC, while retaining full instruction validation and invalidation. Longer instructions retain CRC. Disabling it restores the original full-CRC checks, not an unsafe omission of checks.
- It is not enabled by automatic compatibility/optimization and does not create or install a separate game patch. Reaching the menu and performance improvement remain unverified on a phone.

### Network diagnostic cross-controls

- Extends existing Startup capture and manual Debug exports with native resolver comparisons inside Aura and raw A/AAAA queries in the Wine diagnostic helper, using the same captured Android Network.
- Separates transport/API errors, DNS response codes and record counts, without logging IP addresses, DNS packets, network handles, account details or response bodies. Missing completion records remain partial.
- Keeps the 20-second report window, bounded helper queries, independent host JNI library, and single-worker-group gate. Host synchronous resolver calls cannot safely be forcibly cancelled; late output is discarded and no new query is started after cancellation.
- Does not change Steam DNS, proxy, VPN, certificate or account settings. This adds evidence for the Moonlighter connection issue; it is not a confirmed connectivity fix.

### Delivery and verification

The signed Release passed 89 targeted JVM tests with no remaining skips, Release Lint with 0 errors and 85 existing warnings, all 326 source-asset comparisons, runtime reconstruction, JNI/reflection contracts after shrinking, signature and ZIP alignment checks. The three fixture-dependent Crysis 2 cases skipped in the initial run were rerun with the private supported EXE; that file was only copied/hashed, never executed or committed.

Android installation, Samsung startup appearance, Crysis 2 progress, Moonlighter connectivity and stable Stardew Valley 60 FPS require device testing; build and host checks do not establish these outcomes.

---

## 0.1.130

Fixes the **Check failed.** startup error in 0.1.129. That APK contained the newer FEX translator, but its initializer still required the previous revision. The initializer now matches the bundled runtime while retaining file hashes and the existing upgrade process. Existing game files, saves, containers and Steam configuration do not need to be reset.

Builds now reject mismatched FEX initializer, manifest, build and delivery revisions before preparing assets. Release verification also checks the actual APK's DEX constants. Startup failures retain a bounded, local **Copy diagnostics** action even when Debug is off; collection and upload are not enabled automatically. A runtime revision mismatch now has a specific, localized message.

All 31 targeted regression tests passed, including real bundled translator extraction, repeat initialization, data preservation, container migration and app entry. The original failure was reproduced in three existing upgrade tests before the fix. Android device startup still requires confirmation.

Android 14+ / ARM64. Delivery details and signed Release checks are recorded in validation (internal build record).

---

## 0.1.131

Repairs a confirmed double-close in Wine's certificate importer and adds a Wine-only DNS fallback for the native resolver failures observed in the latest Moonlighter samples. Normal successful lookups remain unchanged; fallback answers are validated and use Android's current network routing. Certificate verification and Android fdsan remain enabled. This targets two observed faults; real Steam login and Moonlighter startup still require phone testing.

This release also includes the changes made since 0.1.130:

- Saved Steam accounts transition directly from the Steam platform choice to the cached avatar and name. Local fades out, the identity moves and shrinks upward, and a larger theme-colored breathing, bouncing and deforming glow appears at the bottom. Authentication starts immediately; cancel and interactive authentication errors remain available.
- Local Library hides the Add Game controller hint when disconnected. Search and Add Game hints use matching sizes and aligned positions when a controller is connected.
- File-browser Back first focuses the directory-back button; Back again returns to the previous page. Down from that button enters the file list.
- Game Management is first in Settings, and Language sits immediately above Advance. Secondary-color overlays are removed from wallpapers and panels; the primary-colored startup glow remains.
- Manual game diagnostics improve verified FEX guest-instruction mapping and bounded Wine thread-request observations. Samples remain observations, not a complete trace or proof that Crysis 2 passes its logo.

Android 14+ / ARM64. Signed Release with R8 optimization and resource shrinking, using the existing upgrade certificate. No game reinstallation or account/save reset is required by these changes. Build, package and delivery evidence is recorded in validation (internal build record).

Release validation: 91 app tests, 19 native DNS tests and both nine-case certificate lifetime variants pass. Release Lint: 0 errors, 85 warnings. Actual packaged runtime, FEX contracts, JNI/Steam reflection, signature, ZIP CRC and alignment checks pass.

---

## 0.1.132

Centers the game-loading status text itself on the screen's horizontal axis. The animation remains on its left, balanced by equal empty space on the right; asymmetric landscape safe areas use symmetric margins. Steam account now appears first in Settings, followed by Game Management. Local mode without an account still starts with Game Management.

This release also includes the changes completed since 0.1.131:

- The approved single hollow, deforming ring replaces the Steam returning-account glow and the game's segmented loader. Steam uses the theme color; game, client and dependency loading use a monochrome ring. Visibility, lifecycle, reduced-motion and cancellation behavior remain intact.
- Launcher and Library game-wallpaper overlays are lighter while keeping text contrast and the original artwork.
- Manual Debug capture adds Wine request V2 observations: fixed outgoing headers, strictly validated preserved WOW64 headers during reply reads, and verified native names. The diagnostic ZIP records accepted sources and specific rejection reasons. Sampling remains bounded and does not change Wine/FEX binaries, synchronization or game execution.
- Includes the latest Crysis 2 and Moonlighter analysis and source verification records. Neither this diagnostic extension nor host-side tests establishes that the games now pass their observed startup failures.

Android 14+ / ARM64. Signed Release with R8 optimization and resource shrinking, retaining the existing upgrade certificate. Build, package and delivery evidence is recorded in validation (internal build record).

Release validation: 54 app tests pass; prior Wine V2 native evidence matches the current sources. Release Lint: 0 errors, 85 warnings. Actual APK runtime, FEX, JNI/Steam reflection, signature, ZIP CRC and alignment checks pass.

---

## 0.1.133

This version reduces repeated linked-list traversal while FEX builds translated code. A gameplay capture from Crysis 2 identified two samples in the block-tail search loop. The change keeps instruction validation, invalidation exits, block ordering and cache policy intact, and applies to both FEX frontends. Differential host tests produce identical synthetic IR; Android FPS and hitch reduction still require device testing.

Also included since 0.1.132:

- Touch feedback for accepted virtual controls; L3/R3 positions exchanged in all three pad styles while retaining their original button functions.
- A smoother game-loading fade, a translucent loading pill, and lighter hint typography. Controller double-press hints now stay inside their notification card.
- Aura game wallpapers display without full-screen overlays. Game titles select black or white from nearby image brightness. Loading controller-guide overlays are retained.
- Steam windows yield to the game after startup; ordinary loading messages are shorter. Initial Steam client preferences enable low bandwidth/performance modes, disable automatic community content and select Library as the startup location, while preserving existing choices.

For a meaningful Crysis 2 comparison, fully restart the game and keep the same settings and cooling conditions. Persistent translation caching remains disabled for this game's FULL-validation compatibility path. If frame pacing remains uneven, separately try a 30 FPS cap or two DXVK compiler threads; neither is a guaranteed FPS improvement.

Validation: 114 targeted application tests with no failures or skips, Release Lint with zero errors and 89 warnings, and actual packaged runtime/signature/reflection checks passed. The existing 16 KB native-library review list is unchanged; this does not claim phone or Android RAR validation. See release evidence (internal build record).

---

## 0.1.134

- Fix the common Turnip R8 loader conflict with Aura's graphics and diagnostic
  `dlopen` wrappers. Resolve and validate system namespace interfaces through a
  libdl handle, remove instruction scanning, and rebuild both Wine-side loader
  libraries. Existing containers, saves and driver preferences are preserved.
- Add fixed startup categories when loader interface resolution fails. A READY
  category confirms interface resolution only; it does not certify GPU startup.
- Include bounded manual FEX JIT/cache diagnostics for intermittent gameplay
  stalls, with repeated-compilation, cache capacity/lifecycle and invalidation
  observations. Add host presentation-gap timing and capture-overlap labels.
  These diagnostics do not themselves fix or prove the cause of Crysis 2 stalls.

The signed arm64 Release uses R8 optimization, obfuscation and resource shrinking.
Host/native checks and Android builds are distinct from device validation. Turnip
R8 startup, Android 17 driver compatibility and diagnostic overhead still require
phone testing. Fully exit an existing game session before comparing the new loader.

---

## 0.1.135

- Bound translation units to 512 instructions for the verified Crysis 2 WOW64 full-validation profile, preserving smaller user limits and single stepping. This aims to reduce compilation bursts; device performance gains remain unverified. Full instruction checks and code-cache capacity are unchanged.
- Fix the JIT diagnostic generation field overwriting other counters. Reject invalid timestamp records and distinguish open Present intervals from completed frames.
- Receive and acknowledge Wrapper DRI3 AHardwareBuffer socket transfers, addressing the R8 path where game audio starts while the loading poster remains. Import validation, resource ownership and game-ready checks remain enforced; device confirmation is still needed.
- Restore a subtle 10% neutral overlay on Aura game wallpapers while retaining local black/white title contrast.

Release is signed with the existing upgrade certificate and uses R8 code optimization, obfuscation and resource shrinking. No user diagnostic archives or game files are published.

---

## 0.1.136

This build expands manual diagnostics for sustained Crysis 2 slowdowns. It does not claim that the underlying stall is resolved.

- Preserve the six existing PC-sampling batches and fill the remaining ten-second JIT/Present window with passive observations. Freeze performance snapshots before anonymous network-control traffic starts.
- Add bounded D3D9 wait/flush/query/submission/pipeline timing by Wine thread, plus correlated thermal, reported CPU frequency, available-memory, VM/PSI and target-process fault observations. Unknown and incomplete data remain explicit. CPU wall waits are not GPU execution time.
- Rebuild x86 and ARM64EC D3D9 while retaining the empty StretchRect fix. Upgrade only recognized built-in DLLs; preserve custom replacements. The observer is inactive outside explicit manual capture.

Changes accumulated since the previous delivered APK:

- Use the game view's reported display refresh rate for host Present pacing and RandR while preserving queued release order and buffer ownership.
- Correct the shared-image Zink context layout and retain the ordinary image path and startup-readiness checks.
- Preserve provider namespaces and reject recursive lookup in the Qualcomm UUID helper. Make DXGI singleton initialization exception-safe and add bounded startup initialization evidence. These fixes do not establish that every vendor driver now starts every game.
- Correct Wine `GetNativeSystemInfo` for ARM64EC using the upstream fix relevant to SMAPI. Preserve native ARM64/WOW64 behavior and custom DLLs. Successful compilation and API replay are not a phone SMAPI/FPS-mod validation.
- Support the explicit Mirror's Edge 19.5:9 resource patch with version/hash/backup checks; add bounded BF3 profile and host-display evidence without forcing stretch.
- Restore A/B/shoulder guidance during controller-connection notices while keeping View in the notice; remove floating-title shadows and use local wallpaper contrast for titles and navigation hints. Move the Steam sign-in coil clear of the smaller bottom guidance.

The release APK uses the existing upgrade signature, R8 optimization/obfuscation and resource shrinking. Validation records are under `docs/validation/aura-0.1.136/` and `docs/validation/manual-stall-v2/`. Android performance, driver compatibility, SMAPI and widescreen results still require device testing.

---

## 0.1.137

This release restores ordinary Zink buffer reuse and adds an explicit SMAPI command window under **Settings → Game management → your game → Mod → Mod commands**. It also includes the changes completed since 0.1.136 listed below.

- Ordinary Mesa allocations no longer lose cache eligibility merely because the driver adds its own Vulkan allocation metadata. Special allocation flags and caller-provided chains remain excluded. Existing allocation parameters, synchronization, cache budgets and failure handling are preserved. The bundled library uses an exact-hash, four-instruction ARM64 transformation with a reproducible recipe and source-equivalent patch; it is not a full Mesa source rebuild. This may reduce allocation/mapping churn in the normal image readback path. It does not enable shared GPU images, lower resolution or change game timing.
- SMAPI commands are entered as a single line and sent explicitly to the current game's verified SMAPI process. Commands are not startup scripts and are not replayed automatically. A complete console-input write is reported as delivered, not as successful mod execution. Output remains in the SMAPI console. Start the game through SMAPI first; background execution must be allowed. `fps 120` is an example requiring a loaded save and the relevant mod; it is not a remedy for performance below the current frame target and may affect update speed.
- Crysis 2's FEX WOW64 full instruction checks now share identical invalidation exits within one compilation attempt, reducing duplicated translation code while retaining the original checks and saved state. Existing fallback controls remain available.
- BF3 startup capture records bounded DXGI display requests and results. Qualcomm/OEM graphics startup diagnostics now report the specific adapter capability rejection. These diagnostics do not establish that Qualcomm game startup or BF3 pillarboxing is fixed.
- Wallpaper text contrast updates propagate directly to the shared UI state, and controller navigation restores focus after startup, returning from games or touch input. Titles remain free of text shadows.
- Mirror's Edge widescreen investigation and the retirement of the old interactive repair-tool catalogue entry are documented. The replacement direct-install widescreen patch remains available; installed old tools and backups are preserved.

Validation includes actual ARM64 pre/post buffer-allocation replay, native graphics regression checks, actual PE instruction replay with Win32 API doubles, Compose dialog/controller and session tests, required FEX/native-upgrade tests, and verification of the final signed, shrunk release APK. These are host checks, not Android/Wine/gameplay measurements. On-device performance, SMAPI command consumption, controller/keyboard usability and the reported game issues still need verification on the phone.

Android 14+ / ARM64. Version code 138. The existing upgrade signing certificate is retained. Private source, recipes, checksums and validation records accompany this release; no signing secrets or R8 mapping are published.

---

## 0.1.138

This release adds saved automatic SMAPI commands and synchronizes navigation and game-title text colors. It includes all local changes completed since 0.1.137.

- **Saved Mod commands:** enter one command in Game management → your game → Mod → Mod commands, enable automatic execution, and save. The next fresh SMAPI launch waits for the current save to load, then attempts delivery once. Automation is off by default. Manual input, changed settings, replaced sessions, and stale logs cannot trigger the pending command; paused games wait. Partial or unknown delivery is not retried. Send now remains available. Console delivery is not proof of command success; `fps 120` requires the relevant mod and may alter game update speed.
- **Unified title colors:** unselected navigation labels and the selected game title share black or white, considering the actual wallpaper behind both areas. Wallpaper changes update them together. No text shadow is added, and the selected navigation tab retains contrast against its theme-colored background.
- **Per-game compact translation:** the optimization proven useful by the user's Crysis 2 feedback is now available for each game and enabled by default. It shares equivalent invalidation exits within an existing full-check translation, preserving instruction validation and saved machine state. It does not enable Crysis-specific compatibility checks in other games. A full game restart applies changes; benefits in other games are not yet measured on-device.
- **Ultrawide virtual display modes:** Wine now offers lower resolutions with the initial virtual desktop's aspect ratio alongside existing modes. This addresses the observed BF3 fallback from 1170×540 to 960×540 without stretching the picture or rewriting game settings. The final BF3 image still needs phone verification.
- **Qualcomm compatibility:** the community v863.1 driver and recognized Adreno factory drivers use a separate matching DXVK 2.6.2 qcom1 set with real optional-extension fallbacks. Turnip retains DXVK 2.7.1. No capability is fabricated; unknown custom DLL sets are preserved. This addresses the captured extension rejections, with phone game startup still to be checked. D3D12 is outside this change.
- **Turnip 26.2.5:** adds an independent, pinned Android KGSL community package corresponding to Mesa's stable version. Existing defaults and saved choices stay intact. Disable Shared GPU image experimentation when testing this driver, since that experiment still selects R8 for its own launch path. A newer version does not guarantee a higher frame rate.

 Host tests, native replay, compilation and APK inspection are not Android/Wine/gameplay measurements. The automatic command, wallpaper appearance, Qualcomm startup and BF3 display changes still need verification on the phone.

Android 14+ / ARM64. Version code 139. The existing upgrade signing certificate is retained. R8 optimization, obfuscation and resource shrinking remain enabled. Signing secrets and R8 mappings are not published.

---

## 0.1.139

This release delivers the completed runtime diagnostics, first-round cache work and interface changes since 0.1.138.

- **Translation cache:** fixes a RAM-cache entry copy that skipped its header and could read past the source. Each game's Optimization page now offers mutually exclusive 32 MiB and 64 MiB cache buffers, both off by default. They require an eligible disk cache and a full game restart; low or unknown available memory, custom configuration, Crysis FULL compatibility and hardware-TSO restrictions retain their existing fallback behavior. Enabling a buffer does not itself enable disk caching.
- **Cache and CPU diagnostics:** bounded manual captures distinguish resident-buffer, pending-write and storage hits, rejection reasons and actual translated-code loading. Android hardware capabilities, Wine's reported capabilities and FEX's effective choices can now be compared. Existing CPU workarounds remain. SVE stays disabled; no new instruction-selection policy or measured phone FPS improvement is claimed.
- **Stardew diagnostics:** adds session-bound SMAPI command execution readback, game Update/Draw timing and final presentation timing to distinguish an unaccepted command, game-side pacing and presentation waits. Unknown or unsupported measurements remain unknown. Diagnostics do not raise the game's target update rate or resend a command.
- **Startup crash capture:** adds bounded native abort/assert observations and graphics failure categories for post-initialization crashes. This improves evidence for the remaining Qualcomm startup failures; it does not declare those driver failures resolved.
- **Turnip 26.2.5:** replaces the restricted community build with the pinned Aura ext1 rebuild that exposes its supported maintenance5 extension to private app loading. Adds three optional per-game tuning switches, off by default and applied only on a fresh launch using this driver. Shared GPU image's temporary R8 path and other drivers do not inherit those options. Source, license and package checks accompany the payload; actual device behavior still needs testing.
- **Dynamic color:** primary and secondary Dynamic choices now follow the currently displayed game wallpaper while browsing. Loading another wallpaper keeps the current palette until its image is ready. Manual choices remain independent, and extraction reuses decoded artwork without additional image requests.
- **In-game menu:** the right side shows the current game's portrait poster and name, including on the Optimization subpage. The name has a borderless translucent pill background. The attached artwork does not take controller focus or touch input and disappears with the menu.
- **Controller hints:** removes the obsolete lower-left hint triggered by first input after connecting a controller. The current centered navigation hints and inline notification shortcut remain.

Android 14+ / ARM64. Version code 140. The existing upgrade signing certificate is retained. R8 optimization, obfuscation and resource shrinking remain enabled. Signing secrets and R8 mappings are not published.

Host tests, native replay and APK inspection are recorded separately from phone testing. On-device cache comparisons, SVE capability confirmation, SMAPI timing, Qualcomm crashes and actual UI/window-compositor behavior remain to be checked.

---

## 0.1.140

This release adds the missing Qualcomm startup evidence, clearer optimization
explanations, and Xbox controller artwork as the default for unsupported pads.

- Qualcomm DXVK 2.6.2 qcom2 records bounded shader-module, graphics-pipeline and
  compute-pipeline call state and actual return codes during Startup capture.
  It includes stage/format/state summaries and bounded shader capability
  fingerprints. It does not export shader source or arbitrary error messages.
- Native abort reports recognize known renamed Adreno libraries and classify
  observed Android abort-message prefixes without changing fatal behavior.
  Closed, expired and replaced captures revoke their own private recording lease.
- Both x86 and ARM64EC matching DXVK cohorts are rebuilt. Previous qcom1 files
  remain eligible for safe upgrades; unknown custom DLLs are preserved. Normal
  Turnip DXVK and the Vulkan driver packages are unchanged.
- Every Optimization focus description explains the purpose, trade-off and
  restart requirement in simpler English and Chinese, including the optional
  32/64 MiB translation-cache RAM buffer.
- Unadapted controllers use Xbox artwork and a View menu-key icon. GameSir X5
  Lite and Backbone keep dedicated artwork; recognized Sony and Switch pads use
  their available artwork. Actual bindings, model names and saved overrides
  are preserved, including reconnect and USB detection.
- SVE remains disabled. The submitted SM-S9280 sample exposes neither SVE nor
  SVE2 to applications. Published examples on some Dimensity/Exynos phones do
  not replace checking actual OS capabilities or validating Wine/FEX contexts.

After installing, use a new Startup capture for each Qualcomm driver and
reproduce the crash. This is a diagnostic update, not a claim that the crash
has been fixed. No Android phone gameplay, performance or SVE enablement was
validated in the build environment. Release validation and delivery links are
recorded in the validation directory (internal build record).

---

## 0.1.141

Optimization now defaults to compact translation and pausing Aura background downloads/scans. Existing saved choices are retained. Restore defaults is available in Game Management and the in-game Optimization menu; it resets only that page for the current game, preserving resolution, frame limit, compatibility and patches. In-game host policies update immediately; translation and driver changes require a complete game restart.

This release also includes the changes prepared since 0.1.140:

- Library wallpaper and the in-game menu background use the approved blur treatment. Launcher titles use the smaller, left-aligned regular style over the vertical wallpaper gradient. The deeper Proposed palette and consistent page text colors retain independent contrast for controls with backgrounds.
- Indeterminate loading indicators share the morphing-line animation. Smart performance suggestions remain off by default. Eligible Adreno devices default to Turnip 26.2.5, preserving explicit driver choices.
- Adreno deferred compiler loads retain their original caller through Aura's graphics/code/wake wrappers. Startup Vulkan capture now uses the actual private Wine Z mapping and distinguishes host configuration from guest readiness.

The loader correction is a targeted attempt, not a confirmed fix on the user's phone. Community and OEM drivers still require device retesting. SVE remains disabled. Validation, release signature, archive checksums and delivery records accompany this version; no new performance claim is made from host tests.

---

## 0.1.142

Launcher game titles are larger: 22 sp, Regular (400), with a 28 sp line height. They align to the cover's leading edge, with the title reservation and compact-screen cover budget updated together. Page text keeps its existing adaptive black/white color.

The game-menu background now uses Aura's own GLES post-process across the whole game surface, including the exposed right side. The earlier system blur-behind request could leave only dimming when cross-window blur was unavailable. The cover, title and menu controls remain sharp. The optional left-panel system frosting stays independent.

The GPU-only blur downsamples into two bounded targets (at most 2 MiB), reuses them while needed, and adds no continuous refresh or CPU image readback. Closing the menu disables processing and releases resources on the next renderer draw; normal gameplay has no blur GL calls. Resource/context and initialization-failure handling are covered by host tests.

Library game wallpaper blur is slightly lighter, reduced from 12 dp to 10 dp, retaining the vertical neutral gradient and clear foreground covers.

The signed release, runtime contracts, source/asset checks and archive readback are recorded in the accompanying validation files. Host pixel checks use the actual shaders but do not validate phone appearance or GPU performance. No new driver, translation or game-performance change is included in this version.

---

## 0.1.143

Aura now uses Phosphor Regular interface icons, Manrope text with bundled Noto Sans
SC Chinese fallback, and Kenney controller prompts. Fonts and icons work offline.
Controller hints continue to follow the selected device and actual key bindings,
including remapped keys. Brand logos and device illustrations retain their identities.

This version also includes the changes prepared since 0.1.142:

- Launcher titles use 20 sp Regular, shrink to 16 sp when needed, then wrap without
  ellipsizing. Text measurement uses the new font family.
- The game menu owns one background blur window covering both startup artwork and
  gameplay. The previous game-only and duplicate panel blur paths are removed.
  If Android disables cross-window blur, the menu retains its dimmed fallback.
- The ordinary Wrapper copy/display path now hands destination textures back using
  EGL completion fences. Existing source-copy completion and Idle ordering remain.
  Improvement of OEM-driver darkness/flicker or community-driver frame drops still
  requires phone testing; this is not a gamma or HDR adjustment.
- Translation-cache RAM buffering resolves the actual game executable instead of
  the Ubisoft client field. Existing compatibility and memory limits remain.
- Manual PC capture handles multiple matching game processes within the existing
  six-batch/eighteen-stop budget, recording identity and incomplete evidence explicitly.

The release retains Android 14 minimum support, the existing upgrade certificate,
R8 optimization/obfuscation and resource shrinking. See `docs/validation/aura-0.1.143`
for historical host validation records.
Device installation, gameplay compatibility and final phone rendering are not claimed
by the host checks.

---

## 0.1.144

Controller hints now use lighter Kenney outlines with matching visible heights for
face and shoulder buttons. Inner face letters are also lighter, closer to the
adjacent Manrope Regular text. Actual controller bindings and accessibility labels
are preserved; all vector adaptation is offline.

Launcher wallpaper shading now occupies only the area below the selected cover:
it fades upward from the bottom of the screen to transparent at the cover's lower
edge. Text contrast uses the same boundary. Library keeps its existing blur and
full-height gradient.

The game menu's dedicated full-screen backdrop now requests one window-manager
blur-behind effect, with explicit hardware acceleration and a translucent format.
It no longer also requests drawable background blur. The backdrop covers both the
startup poster and gameplay while the menu, cover and title remain sharp. Closing
the menu removes the backdrop. Android can disable cross-window blur; the dimmed
fallback remains in that case. Host window tests do not prove phone composition.

Ordinary manual game Debug adds visual-state.txt with bounded source/destination
AHardwareBuffer descriptors, GL texture/framebuffer encoding, selected alpha branch
and blend factors, EGL surface colorspace, color-effect settings and UI/menu/loading
layer state. It keeps the existing ten-second/six-batch budget and stops on pause,
Debug off, exit or session replacement. Missing data remains unknown; no pixels or
shader contents are captured, and surface metadata is not proof of panel HDR.
This supplies evidence for the OEM-driver dark-image issue, not a claimed color fix.

This release also includes the previously prepared Present Idle queue correction:
already completed, due buffers are returned before a subsequent copy can postpone
the timer. Existing source-copy completion, destination read fences, frame limiting
and refresh-rate behavior are retained. Device pacing improvements need retesting.

Android 14 remains the minimum. The existing upgrade certificate, R8 obfuscation
and resource shrinking are retained. Build and delivery records are under
`docs/validation/aura-0.1.144`; phone installation and final rendering are not claimed
by host verification.

---

## 0.1.145

When Android disables cross-window blur, opening the game menu previously dimmed the background without blurring it. This release adds an application-side fallback: the loading artwork uses Android RenderEffect, and the game Surface uses a small GPU blur pass. The menu owns the selection and lifetime; system blur and the fallback are mutually exclusive, while the foreground poster, text and controls stay sharp. Closing the menu or leaving the foreground clears the fallback. It does not capture screenshots or continuously read back game pixels.

Ordinary Debug captures now include frame intervals below 50 ms and bounded records linking host X11 Present requests, copy stages, buffer return, drawing and available Android display timestamps. The ten-second capture and six PC-probe batches are unchanged. Unsupported display timestamps and unobserved swap duration remain unknown, rather than being inferred from host draw counts. This improves diagnosis of community-driver judder; it is not a confirmed performance fix.

The release also includes the previously local first Vulkan device-loss diagnostics: bounded native return history, separate first negative error and first observed device loss, and optional fault information only when the application already enabled the required driver feature. No synchronization checks, driver results or device capabilities are bypassed.

Android 14 or later, arm64. Release builds use the existing signing certificate, R8 and resource shrinking. Phone rendering, performance and crash outcomes still require device verification.

---

## 0.1.146

Version code: 147. Signed ARM64 release, using the existing upgrade certificate.

### Changes since 0.1.145

- Fix striped contours in the application-side in-game menu blur. Continuous
  Gaussian sampling replaces widely spaced taps; both axes run on the same
  bounded intermediate image before upscaling. Loading-screen blur and sharp
  foreground posters, titles and controls retain their existing behavior.
- Return already completed, due Present buffers while waiting for another
  source copy to finish. AHB allocation identity protects aliases and unknown
  buffers; current-source completion and display-read synchronization remain
  required. Bounded fence waits fall back conservatively on failure.
- Extend ordinary Game Debug with completion-wait records: wait strategy,
  fallback reason, due-buffer delivery cost and timer boundaries. These are host
  timings, not physical scanout or GPU execution times.
- Add bounded manual color probes for investigating the OEM driver's dark
  image: at most three nine-point groups compare the imported image, copy,
  drawing and host output. Reports contain aggregate channel values, not
  screenshots. Synchronizing readback overhead is explicitly reported.
- Add bounded Vulkan command categories and real queue-family/index context to
  the first observed device-loss report. Preserve native returns, errno,
  synchronization and unknown/incomplete command history.
- Correct the window texture sampler uniform name. The previous default unit
  was already zero, so this correction alone is not evidence that OEM darkness
  is resolved.

### Validation and device follow-up

The source fixes have host regression coverage, including production GLSL
thin-line tests that reproduce the previous striped blur. Release validation
checks the actual signed package, native libraries, runtime contracts, JNI,
font/icon assets and upgrade certificate. Final results and checksums are in
the delivery record (internal build record).

Phone appearance and frame-time improvements still require device verification.
The additional diagnostics do not claim to fix vendor-driver darkness or
device loss. For dark OEM rendering or community-driver stutter, capture
ordinary Game Debug while the problem is visible. For the fixed-transition
black screen/device loss, use Startup Debug from a fresh launch, followed by
ordinary Debug if needed. Screen recording is not required.

---

## 0.1.147

Version code: 148. ARM64 release using the existing upgrade certificate.

- Improve ordinary imported-AHB completion waits when a driver returns short
  timeouts early. Requests round up to a millisecond while preserving an eight-call
  guard and adding a 50 ms elapsed budget for segmented waiting. Already-completed,
  due, known-distinct buffers may be returned; current-source completion, target
  read synchronization and alias protection remain required.
- Add bounded per-wait Game Debug records: requested timeout, actual host call
  bounds/result, selected older reservation's capture-local Present ID, eligibility
  counts and fallback. Missing/partial data stays unknown; no native handles or
  AHB allocation IDs are exported. These are not GPU or physical display timings.
- Fix missing draw/output color diagnostic associations when new Presents arrive
  quickly. COPY and DRAW have independent bounded budgets, and output is matched
  to the same host draw. Preserve readback overhead and unknown color boundaries.
- Improve Vulkan command diagnostics with precise allocation/pool/device lifecycle
  tracking, local contention invalidation and explicit reasons for unknown command
  history. No driver results, capabilities or synchronization are overridden.
- Use Phosphor Fill for focused/selected controls and the controller connection
  notification; ordinary controls retain Regular and Kenney prompts are unchanged.

Host regressions and signed-package verification are recorded in
delivery validation (internal build record). Phone performance,
OEM darkness and device-loss behavior still need device verification; additional
diagnostics are not claims that these driver faults are fixed.

For community-driver stutter, use ordinary Game Debug during the issue after
allowing the phone to cool, with the same route and settings. For fixed-transition
OEM black screens, use Startup Debug from a fresh launch. Recording is not required.

---

## 0.1.148

Version 0.1.148 (149). This release adds diagnostic coverage for Qualcomm-driver rendering and pacing problems. Device performance and game compatibility remain subject to phone validation.

- Preserve Vulkan command allocation ancestry when bounded recording summaries are evicted. V3 command diagnostics identify why a failed submission's command history is unknown, without changing Vulkan results, resource ownership or synchronization.
- Compare nine read-only AHB byte samples with GL-imported colors during a manual game capture, only when the buffer already permits CPU reads and its plane layout is supported. Retain read/unlock failures and capture cost; do not alter brightness or claim HDR/gamma is the cause of Cuphead's dark output.
- Retain a continuous manual Vulkan entry/return timeline for up to four verified game processes, at most 8192 calls each over ten seconds. Host wall time, calling-thread CPU time, real results and unobserved returns remain distinct.
- Add bounded host X11 read/decoded-dispatch timing and local Present associations to separate upstream frame gaps from host processing. These are not kernel wakeup, GPU execution or physical display timestamps. Pausing, closing Debug or ending the capture revokes observation; stale records cannot cross capture generations.
- Include the new timelines in ordinary diagnostic ZIPs with checksums and a source decoder. Existing rendering synchronization and the original graphics snapshot ABI are preserved.

For intermittent MGS community-driver stalls, capture an ordinary game bundle while reproducing the issue. For OEM black screens at a fixed startup transition, use Startup Debug and an ordinary capture if the process remains alive. Capture overhead must be separated from unprofiled performance comparisons.

Cuphead UltraWide 1.1.0 was researched as a third-party widescreen candidate. Its author describes BepInEx-based aspect detection from 16:9 to 2.39:1; Aura loading and actual gameplay have not been verified. The APK does not bundle an unlicensed copy of the third-party mod or a modified commercial game DLL.

Install over the previous Aura release using the existing upgrade signing certificate. Detailed build, package, source and delivery evidence is recorded in `docs/validation/aura-0.1.148/`.

---

## 0.1.149

Version 0.1.149 (150). This release adds driver-aware Optimization controls, shared-image integration for Turnip 26.2.5, and further diagnostics for Qualcomm rendering and stutter reports. Host validation does not establish phone compatibility or performance gains.

- Enable Shared GPU images only for the integrated Turnip 26.0 R8 and 26.2.5 drivers on supported hardware. Enabling it keeps the selected driver instead of temporarily switching to R8. Sharing remains an optional OpenGL/Zink experiment with runtime import checks and an ordinary-presentation fallback; it does not imply that DX11 games benefit.
- Enable the three Turnip-specific tuning switches only when 26.2.5 is selected. Both Optimization menus explain disabled options, keep saved choices dormant on incompatible drivers, and distinguish restart-only settings from live host controls. Universal CPU/cache/background options remain available independently of Vulkan driver choice.
- Add bounded Cuphead pre-present back-buffer color observations to ordinary manual captures. At most three nine-point samples use existing GPU completion tracking; unsupported, unfinished and failed samples remain unknown. The samples are not a frame-matched proof of HDR/gamma or a brightness fix.
- Include a scoped OEM MGS Ground Zeroes rendering trial when its existing startup compatibility option is enabled and the actual driver is System/Adreno. It requests `dxvk.tilerMode=False`, preserves the original path when disabled, and leaves community/Turnip drivers unchanged. This is a compatibility trial, not a confirmed phone fix.
- Extend Vulkan V4 command summaries with bounded direct-secondary-buffer evidence, preserving unknown/partial history and original submission results and synchronization.
- Complete the ten-second manual performance window before intrusive PC sampling. Add bounded process/thread CPU, run-queue, context-switch, fault and I/O observations on the same host monotonic clock. Later PC samples are not assigned to earlier long frames. These observations do not measure GPU execution or physical FPS and do not attempt to fix game login.

For community-driver MGS stutters, capture an ordinary game bundle while reproducing the issue. For the OEM loading-spinner freeze, fully restart with the desired compatibility setting and capture after the freeze is visible. For Cuphead's dark output, use an ordinary capture in a stable title/menu or gameplay scene. Compare performance with detailed capture disabled as well.

The selected transparent mascot now replaces the shared launcher-icon, startup-screen and default-wallpaper asset, retaining the green emblem on the handheld.

</details>
