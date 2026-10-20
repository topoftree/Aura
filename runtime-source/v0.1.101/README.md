# Aura 0.1.101 runtime source and build inputs

These files supplement the [Winlator Android engine/recombination kit](../../winlator-source-and-relink/v0.1.101/README.md). They are separately licensed runtime sources, patches and build tools, not Aura's independent Kotlin application source. Original component copyrights and license notices apply.

## Included source archives

| Archive | Matching use |
| --- | --- |
| `sources/Wine-proton-dcc50c55e2-source.tar.gz` | Complete pinned Proton Wine 11.0-2 source, including vendor Android build patches, used for the native ntdll and i386/ARM64X wait-queue changes. |
| `sources/Wine-sechost-256288853b-source.tar.gz` | Pinned Wine source used to rebuild ARM64X sechost with direct RPC imports. |
| `sources/ntsync-android-7ce6435-source.tar.gz` | LGPL-3.0-only backend statically included in the native Wine build. |
| `sources/ntsync-libc-vendor.tar.gz` | Locked Cargo dependency and offline build configuration for the supplied ntsync build. |
| `sources/gstreamer-1.28.0.tar.gz` | Complete upstream source for the added libav/parser plugins; local patch and build inputs are under `tools/arm64ec/media`. |
| `sources/SDL-2.32.10.tar.gz` | Complete upstream source for the modified headless Wine gamepad backend, supplied under `tools/arm64ec/sdl-gamepad`. |

Clone with Git LFS and run:

```sh
git lfs pull --include="runtime-source/v0.1.101/sources/*" --exclude=""
cd runtime-source/v0.1.101
sha256sum -c SHA256SUMS
```

The Wine archives preserve their complete upstream contents and notices. SHA-256 inputs, compiler versions and patches are pinned in the adjacent scripts and component build records. Scripts run host build tools; they do not launch user games.

## Wine wait-completion build

The supplied native ntdll and i386 PE DLL are a pair: i386 `RtlWaitOnAddress` rechecks completion after crossing into the native wait path. They share a new private Unix operation; installing only one half is unsupported. ARM64X PE remains unchanged by that final completion patch. The earlier wait-queue lifetime and ARM64EC initial-stack patches remain applied.

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

## Other build inputs

`build_sechost.py` uses the matching older source archive for the separately rebuilt RPC service DLL. `build_media.py` and the retained media manifest document the GStreamer plugin build against the pinned Termux ABI. `build_sdl_gamepad.py` carries the modified SDL configuration/backend. DXVK's source commit, submodules, toolchain hashes and four-line upstream empty-rectangle backport are in `tools/arm64ec/dxvk` and `build_dxvk.py`; acquire that recursive upstream checkout at the recorded revisions.

`assemble.py`, package scripts, relocation and native-index staging tools document packaging of these runtime inputs. They expect the existing runtime assets/catalogs as build inputs. Start from the matching APK and the Winlator kit's bootstrap rather than assuming this directory is a standalone Android application project.

## Installing modified runtime components

Rebuild the chosen library, place it into the extracted runtime inputs used by the Winlator kit, and update associated archive/manifest/native-index hashes and mappings. Recombine and sign the APK with your own key. Runtime fingerprints and manifests are editable; no production-key service authorizes replacement. A fresh container/test profile can avoid a previous prefix's preserved library copies. Back up data before any destructive device action.

This supplement supplies the listed inputs and does not claim byte-identical rebuilds of every upstream-prebuilt library in the APK. The broader FFmpeg/Termux, Mono, VKD3D and proprietary-driver inventory remains a separate review, recorded in [SOURCE_CODE.md](../../SOURCE_CODE.md). A URL to an upstream binary is not its corresponding source. Source delivery and a host rebuild do not themselves certify all licensing or Android installation requirements.
