# Source availability and library recombination

## Aura 0.1.82 / version code 83

[Open the Winlator engine source and recombination kit](relink/v0.1.82/README.md).

The public kit accompanies both documented 0.1.82 APK packagings. It contains:

- The modified Winlator Android engine's Java/Kotlin and C/C++ source, Android resources, retained copyright/license texts, and build configuration.
- A file-level comparison against pinned Winlator 11.2: 723 unchanged files, 86 modified files and 122 added files. Modified source files carry publication notices; the source manifest preserves the original production-source hashes.
- Aura's pre-R8 application **object code**, third-party dependency objects, compiled resource inputs, stable resource IDs, and optimization rules. The independent Aura Kotlin UI/business source, private Git history, credentials and production signing keys are not included.
- A Gradle release recombination task, R8 optimization and resource shrinking, native rebuild, and instructions for signing a modified copy with the recipient's own key.

The source and objects are available from the same public repository as the APK. Read the kit's [NOTICE.md](relink/v0.1.82/NOTICE.md), [build instructions](relink/v0.1.82/README.md), [modification record](relink/v0.1.82/MODIFICATIONS.tsv) and [verification record](relink/v0.1.82/VALIDATION.md). LGPL 2.1 is available [in full](relink/v0.1.82/engine/LICENSE).

The existing `v0.1.82` tag and release APK are unchanged. They predate this source-delivery addition, so use the versioned kit on `main` rather than the release page's old automatically generated source archive. Each future binary release needs its own matching source/object materials; the 0.1.82 kit must not be assumed to match later binaries.

## Rights and installation

The Winlator-derived engine and Aura's modifications to it are provided under LGPL 2.1, with separately licensed embedded files retaining their original terms. The application objects are supplied with express permission to recombine with a modified LGPL engine, modify the combined work for personal use, and reverse engineer it for debugging LGPL-library modifications. These permissions are not restricted by Aura's general copyright notice.

The kit uses the original public APK for its already-distributed runtime inputs. The supplied engine source is rebuilt; merely extracting another runtime library's binary does not provide its corresponding source. Users can sign a modified APK with their own key. Android normally requires a fresh installation or separate test device/profile when a key differs from the official app. Back up private app data before any uninstall. Production signing keys are not needed and are not published.

Host build, resource and signature checks are distinct from installing and running on Android hardware. Consult the validation record for what was actually tested.

## Outstanding obligations and app changes

This publication addresses the Winlator Android engine/recombination portion. It does **not** declare the entire Aura APK legally compliant. In particular:

| Item | Remaining work |
| --- | --- |
| Modified Wine / Proton and statically included ntsync | Deliver matching component source, patches and build/recombination materials; assess the LGPL-3.0 installation-information requirements for the actual distribution arrangement. |
| Wine Mono, media/audio libraries and other runtime packages | Finish the exact shipped-component/source inventory and fulfill each applicable license's source-delivery terms. |
| Qualcomm proprietary graphics package | Establish redistribution permission or remove/replace it from distribution. |
| Independent Aura / derived Winlator boundary | The private-source arrangement depends on this boundary being legally valid. Copying or translating library-derived code into an Aura-named file would not make it independent. Technical recombination tests do not certify that legal boundary. |
| In-app notice | The next application build needs the visible LGPL notice, offline license text and corresponding-source entry. These additions are implemented and compile-checked in the private app; the published 0.1.82 APK has not been replaced. |

The [third-party notices](THIRD_PARTY_NOTICES.md) and [copyright scope](COPYRIGHT.md) remain applicable. A complete legal compliance determination should review the actual distribution and component boundaries, not only this README or a successful build.
