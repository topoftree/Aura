# Source availability and library recombination

## Aura 0.1.118 / version code 119

[Winlator source and relinking kit](winlator-source-and-relink/v0.1.118/README.md) · [Runtime sources and build inputs](runtime-source/v0.1.118/README.md) · [Release](https://github.com/topoftree/Aura/releases/tag/v0.1.118)

The matching materials are in versioned folders on **main**, not Release attachments. Clone with Git LFS as described in the kit; automatic GitHub source ZIPs may contain LFS pointers. Release assets are the official APK and its checksum file.

The kit supplies 968 editable Winlator engine source/resource files, with retained attribution and a file-level modification record. Independent application object code contains 1,528 classes, with 1,508 renamed; source/debug/Kotlin metadata is removed. Dependency objects, compiled resources, stable IDs, manifest, build rules and offline notices are included. Private application source/history, original application objects, mappings, signing material and user diagnostic captures are excluded. The library modification/recombination permissions are in [NOTICE.md](winlator-source-and-relink/v0.1.118/NOTICE.md).

The runtime supplement retains the pinned Wine/ntsync/GStreamer/SDL sources and adds the corresponding Wine locale, isolated clock-entry, ARM64EC breakpoint and DXVK D3D11 batching patches/build inputs, plus complete pinned FEX source and its required submodule archives for the executable-image-page changes. It does not expand the claim to every separately bundled upstream-prebuilt runtime. The coverage and outstanding issues below still apply.

[Validation](winlator-source-and-relink/v0.1.118/VALIDATION.md) covers independent baseline recombination and a modified Java/native/resource build, stable IDs, runtime hashes, JNI/Steam/navigation contracts and recipient-key signing. The official APK is unchanged by these tests. Earlier versioned kits remain available and must not be substituted for this APK.

## Aura 0.1.101 / version code 102

[Open the matching Winlator source and recombination kit](winlator-source-and-relink/v0.1.101/README.md), [runtime source/build inputs](runtime-source/v0.1.101/README.md), or the [0.1.101 release](https://github.com/topoftree/Aura/releases/tag/v0.1.101).

The new kit has this APK's editable Winlator engine source and modification record, independently obfuscated application objects, dependency objects, manifest, stable resource IDs, compiled resources and build rules. The standalone `Aura-0.1.101-relink.zip` contains the actual objects; a Git clone needs Git LFS. Every kit file has a SHA-256 entry.

The independent application has 1,197 classes, with 1,178 names transformed and source/debug/Kotlin metadata removed. The LGPL-covered engine remains editable. Original application objects, private application source/history, private R8 mappings, signing material and user diagnostic captures are not included. This follows the library-source/application-object recombination approach in LGPL 2.1 section 6(a), with the permissions in [NOTICE.md](winlator-source-and-relink/v0.1.101/NOTICE.md).

Separate runtime materials now include the pinned full Wine sources used for native ntdll and sechost, ntsync source and its locked vendor dependency, Wine wait-queue/completion and stack patches, source/build metadata, complete GStreamer/SDL source inputs and the DXVK backport/build recipe. They expand the previous release's source coverage; they are not a claim that every other upstream-prebuilt runtime has been reconstructed or its legal status certified.

See [VALIDATION.md](winlator-source-and-relink/v0.1.101/VALIDATION.md) for independent recombination, modified-library and signature checks. The Winlator source/recombination link in the application points to this version index. Earlier kits remain available below and must not be substituted for 0.1.101.

## Aura 0.1.83 / version code 84

[Open the matching 0.1.83 source and recombination kit](winlator-source-and-relink/v0.1.83/README.md). The matching APK, detailed release notes and checksums are on the [0.1.83 release page](https://github.com/topoftree/Aura/releases/tag/v0.1.83).

The public repository provides [`Aura-0.1.83-relink.zip`](https://media.githubusercontent.com/media/topoftree/Aura/main/winlator-source-and-relink/archives/Aura-0.1.83-relink.zip), containing the full versioned kit and actual object JARs. Verify the archive with the [published SHA256SUMS](https://raw.githubusercontent.com/topoftree/Aura/main/releases/v0.1.83/SHA256SUMS), then verify its individual files with the included `SHA256SUMS`. This download does not require Git LFS.

This version supplies its own pre-obfuscated application objects, manifest, stable resource IDs, compiled resources and matching dependency objects. The Winlator engine implementation is unchanged from 0.1.82; its editable source, build scripts, LGPL text and file-level provenance remain included. The independent Aura Kotlin UI/business source, private mapping, private Git history and production signing material are not published. See the kit's [notice](winlator-source-and-relink/v0.1.83/NOTICE.md), [privacy details](winlator-source-and-relink/v0.1.83/PRIVACY.md), [modification record](winlator-source-and-relink/v0.1.83/MODIFICATIONS.tsv) and [validation record](winlator-source-and-relink/v0.1.83/VALIDATION.md).

The APK now includes a visible Winlator license entry under **Settings → Version**, offline LGPL 2.1 text and a source/recombination link. This fulfills the previously pending in-app-notice change for 0.1.83; it does not alter the older 0.1.82 APK or resolve the separate runtime obligations below.

## Aura 0.1.82 / version code 83

[Open the Winlator engine source and recombination kit](winlator-source-and-relink/v0.1.82/README.md).

The public kit accompanies both documented 0.1.82 APK packagings. It contains:

- The modified Winlator Android engine's Java/Kotlin and C/C++ source, Android resources, retained copyright/license texts, and build configuration.
- A file-level comparison against pinned Winlator 11.2: 723 unchanged files, 86 modified files and 122 added files. Modified source files carry publication notices; the source manifest preserves the original production-source hashes.
- Aura's independently obfuscated application **object code**, third-party dependency objects, compiled resource inputs, stable resource IDs, and optimization rules. The independent Aura Kotlin UI/business source, private Git history, private name mapping, credentials and production signing keys are not included.
- A Gradle release recombination task, R8 optimization and resource shrinking, native rebuild, and instructions for signing a modified copy with the recipient's own key.

The source and objects are available from the same public repository as the APK. Read the kit's [NOTICE.md](winlator-source-and-relink/v0.1.82/NOTICE.md), [build instructions](winlator-source-and-relink/v0.1.82/README.md), [modification record](winlator-source-and-relink/v0.1.82/MODIFICATIONS.tsv) and [verification record](winlator-source-and-relink/v0.1.82/VALIDATION.md). LGPL 2.1 is available [in full](winlator-source-and-relink/v0.1.82/engine/LICENSE).

The existing `v0.1.82` tag and release APK are unchanged. They predate this source-delivery addition, so use the versioned kit on `main` rather than the release page's old automatically generated source archive. Each future binary release needs its own matching source/object materials; the 0.1.82 kit must not be assumed to match later binaries.

The earlier unobfuscated 0.1.82 application object has been replaced in the
published branch/tag history with the pre-obfuscated object. The matching
editable engine source and usable recombination materials remain supplied.
Git history cleanup does not purge GitHub server-side LFS objects or caches;
server-side removal requires separate verification. Prior downloads cannot
be recalled. The official APKs are unchanged.
See [PRIVACY.md](winlator-source-and-relink/v0.1.82/PRIVACY.md).

## Publication cleanup — 2026-10-19

The published branch/tag history now supplies the pre-obfuscated 0.1.82
application object in place of the retired unobfuscated object. Both versioned
kits have had their private absolute resource-build paths normalized. The 0.1.83
recombination ZIP and its checksum have been updated accordingly; official APK
payloads are unchanged. Git history cleanup alone does not purge server-retained
LFS objects or caches, and cannot retract copies already downloaded.

The engine source, its license and the recombination rights remain available.
Resource-link verification is documented in each kit's `VALIDATION.md`.

## Rights and installation

The Winlator-derived engine and Aura's modifications to it are provided under LGPL 2.1, with separately licensed embedded files retaining their original terms. The application objects are supplied with express permission to recombine with a modified LGPL engine, modify the combined work for personal use, and reverse engineer it for debugging LGPL-library modifications. These permissions are not restricted by Aura's general copyright notice.

The kit uses the original public APK for its already-distributed runtime inputs. The supplied engine source is rebuilt; merely extracting another runtime library's binary does not provide its corresponding source. Users can sign a modified APK with their own key. Android normally requires a fresh installation or separate test device/profile when a key differs from the official app. Back up private app data before any uninstall. Production signing keys are not needed and are not published.

Host build, resource and signature checks are distinct from installing and running on Android hardware. Consult the validation record for what was actually tested.

## Outstanding obligations and app changes

The Winlator kits and the versioned runtime supplements have the scope above. They do **not** declare the entire Aura APK legally compliant. In particular:

| Item | Remaining work |
| --- | --- |
| Modified Wine / Proton and statically included ntsync | 0.1.101 now supplies the pinned sources, patches, build scripts and replacement guidance for the listed modifications. Complete the correspondence review for other upstream-prebuilt Wine parts and assess LGPL-3.0 installation-information requirements for the actual distribution arrangement. |
| Wine Mono, FFmpeg/Termux, VKD3D and other runtime packages | Finish the exact shipped-component/source inventory and fulfill each applicable license's source-delivery terms. The new GStreamer/SDL sources do not substitute for separately bundled FFmpeg or other library sources. |
| Qualcomm proprietary graphics package | Establish redistribution permission or remove/replace it from distribution. |
| Independent Aura / derived Winlator boundary | The private-source arrangement depends on this boundary being legally valid. Copying or translating library-derived code into an Aura-named file would not make it independent. Technical recombination tests do not certify that legal boundary. |
| In-app notice | Included in 0.1.83: visible Winlator notice, offline LGPL text and corresponding-source/recombination link. The older 0.1.82 APK has not been replaced. |

The [third-party notices](THIRD_PARTY_NOTICES.md) and [copyright scope](COPYRIGHT.md) remain applicable. A complete legal compliance determination should review the actual distribution and component boundaries, not only this README or a successful build.
