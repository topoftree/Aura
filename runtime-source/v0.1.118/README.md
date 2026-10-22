# Aura 0.1.118 runtime source and build inputs

These files supplement the [Winlator Android engine/recombination kit](../../winlator-source-and-relink/v0.1.118/README.md). They are separately licensed runtime sources, patches and build tools, not Aura's independent Kotlin application source. Original component copyrights and license notices apply.

## Included source archives

| Archive | Matching use |
| --- | --- |
| `sources/Wine-proton-dcc50c55e2-source.tar.gz` | Complete pinned Proton Wine 11.0-2 source, including vendor Android build patches, used for the native ntdll and i386/ARM64X wait-queue changes. |
| `sources/Wine-sechost-256288853b-source.tar.gz` | Pinned Wine source used to rebuild ARM64X sechost with direct RPC imports. |
| `sources/ntsync-android-7ce6435-source.tar.gz` | LGPL-3.0-only backend statically included in the native Wine build. |
| `sources/ntsync-libc-vendor.tar.gz` | Locked Cargo dependency and offline build configuration for the supplied ntsync build. |
| `sources/gstreamer-1.28.0.tar.gz` | Complete upstream source for the added libav/parser plugins; local patch and build inputs are under `tools/arm64ec/media`. |
| `sources/FEX-14c92681f4d62cf84d901460e0358de09c8847a7.tar.gz` and the six pinned fmt/xxHash/unordered_dense/rpmalloc/range-v3/cpp-optparse archives | Complete FEX and required submodule sources for the executable-image page-range correction; hashes and destinations are in `tools/arm64ec/fex-image-pages/sources.json`. |
| `sources/SDL-2.32.10.tar.gz` | Complete upstream source for the modified headless Wine gamepad backend, supplied under `tools/arm64ec/sdl-gamepad`. |

Clone with Git LFS and run:

```sh
git lfs pull --include="runtime-source/v0.1.118/sources/*" --exclude=""
cd runtime-source/v0.1.118
sha256sum -c SHA256SUMS
```

The Wine archives preserve their complete upstream contents and notices. SHA-256 inputs, compiler versions and patches are pinned in the adjacent scripts and component build records. Scripts run host build tools; they do not launch user games.

## Wine wait-completion build

The base wait-completion native ntdll and i386 PE DLL form a pair: i386 `RtlWaitOnAddress` rechecks completion after crossing into the native wait path. They share a new private Unix operation; installing only one half is unsupported. ARM64X PE is unchanged by the completion patch itself; the subsequent clock-entry and breakpoint changes below produce the current ARM64X PE. The earlier wait-queue lifetime and ARM64EC initial-stack patches remain applied.

Requirements: Python, gcc/g++, autoconf/autoheader, m4, bison, flex, make, Android NDK 28.2.13676358, pinned LLVM-MinGW 20250920, Rust 1.90.0 and the Android target. Obtain the compiler archive from the URL/hash in `tools/arm64ec/build_sechost.py`. Use a new work directory:

```sh
PYTHONPATH=tools:tools/arm64ec python3 tools/arm64ec/build_wait_completion.py \
  --source sources/Wine-proton-dcc50c55e2-source.tar.gz \
  --toolchain-archive /path/to/llvm-mingw-20250920.tar.xz \
  --mingw /path/to/llvm-mingw/bin --ndk /path/to/ndk/28.2.13676358 \
  --work /path/to/new-build
PYTHONPATH=tools:tools/arm64ec python3 tools/arm64ec/link_wait_completion.py \
  --work /path/to/new-build --mingw /path/to/llvm-mingw/bin \
  --ndk /path/to/ndk/28.2.13676358 --cargo /path/to/cargo \
  --ntsync-source sources/ntsync-android-7ce6435-source.tar.gz \
  --ntsync-vendor sources/ntsync-libc-vendor.tar.gz
```

The scripts apply the source archive's Android patches, the supplied wait-queue patch, completion patch and initial-stack patch in the documented order. Output build records identify the native and PE binaries. For changed sources, adjust expected source/output hashes in the editable build/packaging metadata as appropriate; the official fingerprints are validation baselines, not signature locks.

## Additional Wine and FEX changes in 0.1.118

The current native ntdll adds the Android locale adapter on top of the wait-completion build above. Run `build_android_locale.py --base /path/to/completed-wait-build --work /path/to/new-locale-build` with `PYTHONPATH=tools:tools/arm64ec`. It first checks that the unchanged base objects reproduce the pinned native binary, applies `android-locale/wine-android-locale.patch` to a separate source copy, and records the output ABI, hashes and locale behavior. The existing i386 wait-completion DLL remains paired with the native path; the locale patch does not change PE binaries.

The ARM64X PE ntdll has two later changes, independent of that locale-only native build:

1. `build_mgs_tick_thunk.py --source sources/Wine-proton-dcc50c55e2-source.tar.gz --toolchain-archive /path/to/llvm-mingw-20250920.tar.xz --mingw /path/to/llvm-mingw/bin --work /path/to/new-tick-build` applies the inherited Wine patches and isolated clock-entry adapter. Its production helper, patch, notices and build record are included.
2. `build_arm64ec_breakpoint.py --baseline-work /path/to/completed-tick-build --mingw /path/to/llvm-mingw/bin --output /path/to/new-breakpoint-build` applies the saved-PC correction to that verified baseline. The native locale build and i386 PE are not rebuilt by this step.

For the FEX executable-image changes, supply all seven archives in `sources/` to `build_fex_image_pages.py --downloads sources --toolchain-archive /path/to/llvm-mingw-20250920.tar.xz --mingw /path/to/llvm-mingw --cmake-bin /path/to/cmake/bin --work /path/to/new-fex-build`. Its script validates every archive, places the submodule contents at the recorded paths, applies the supplied patch and runs the included image-range regression. The rebuilt ARM64EC and WOW64 translator pair must be packaged together. Use the adjacent build records to inspect the exact revision, configuration and outputs.

The clock-entry and FEX changes are gated by the selected compatibility option and actual game executable in Aura. Host reproduction does not establish successful Ground Zeroes gameplay. The breakpoint correction fixes the runtime instruction handling itself.

## DXVK D3D11 submission batching

`build_dxvk_batching.py`, `package_dxvk_batching.py`, `dxvk/submission-batching.patch`, the tracker regression and `dxvk/batching-build.json` describe the optional batching build. Start from the clean recursive DXVK checkout pinned in `build_dxvk.py` (commit `c3dd74be6baec53786d4e064a572185b70347a17` and its recorded submodules). Supply `--source`, `--toolchain`, `--glslang-bin`, `--ninja-bin` and a separate `--work` directory; use `--help` for path semantics. The build records its source/patch/toolchain inputs and only replaces the intended D3D11 payloads; retained DXGI/D3D9 payloads are checked by packaging. The D3D9 empty-rectangle backport remains included from the earlier release.

The option is off by default. The code preserves explicit Flush, Present, query and resource synchronization boundaries. The patch and host regression are provided for inspection and modification; they do not demonstrate a device FPS benefit.

## Other build inputs

`build_sechost.py` uses the matching older source archive for the separately rebuilt RPC service DLL. `build_media.py` and the retained media manifest document the GStreamer plugin build against the pinned Termux ABI. `build_sdl_gamepad.py` carries the modified SDL configuration/backend. DXVK's source commit, submodules, toolchain hashes and four-line upstream empty-rectangle backport are in `tools/arm64ec/dxvk` and `build_dxvk.py`; acquire that recursive upstream checkout at the recorded revisions.

`assemble.py`, package scripts, relocation and native-index staging tools document packaging of these runtime inputs. They expect the existing runtime assets/catalogs as build inputs. Start from the matching APK and the Winlator kit's bootstrap rather than assuming this directory is a standalone Android application project.

## Installing modified runtime components

Rebuild the chosen library, place it into the extracted runtime inputs used by the Winlator kit, and update associated archive/manifest/native-index hashes and mappings. Recombine and sign the APK with your own key. Runtime fingerprints and manifests are editable; no production-key service authorizes replacement. A fresh container/test profile can avoid a previous prefix's preserved library copies. Back up data before any destructive device action.

This supplement supplies the listed inputs and does not claim byte-identical rebuilds of every upstream-prebuilt library in the APK. The broader FFmpeg/Termux, Mono, VKD3D and proprietary-driver inventory remains a separate review, recorded in [SOURCE_CODE.md](../../SOURCE_CODE.md). A URL to an upstream binary is not its corresponding source. Source delivery and a host rebuild do not themselves certify all licensing or Android installation requirements.
