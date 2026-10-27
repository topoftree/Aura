# Verification — Aura 0.1.149

Checked on 2026-10-27 against the official APK SHA-256 in README.md. Machine-readable results: [validation.json](validation.json).

| Check | Result |
| --- | --- |
| Engine provenance | 1067 tracked source/resource files compared with pinned Winlator 11.2: 697 unchanged, 109 modified, 261 added. Original notices retained; hashes and changes recorded in MODIFICATIONS.tsv. |
| Independent application objects | 1,915 classes; 1,890 renamed. No engine implementation classes or source/debug/Kotlin metadata in the application JAR. |
| JVM verification | Original and obfuscated objects each passed: 1,915 loaded classes, 45 serializer lookups and 69 controller/serialization assertions. |
| Baseline recombination | Independent engine compilation and APK recombination with full R8 optimization/resource shrinking; 274 indexed runtime payloads verified. |
| Library replacement | Temporary engine Java method, native export and new string resource reached the rebuilt APK. |
| Stable resources | 8,279 original linked IDs preserved; 742 stable-input entries absent from both outputs. New resource ID allocated without moving original IDs. |
| Final APK contracts | Both baseline and modified output passed 12 JNI fields in 8 classes, 24 Steam reflection contracts and 8 navigation themes. |
| Recipient signing | Modified APK signed with a newly generated temporary recipient key; signature and ZIP alignment verified. No production key used. |
| Restoration | Temporary Java/native probes and test resource removed; published source hashes match the pre-test bytes. |
| Publication boundary | Independent application source/history, original objects, private mappings, signing material, build outputs/logs and user captures excluded. |

The official APK was not replaced by the relinking tests. It retains its existing upgrade certificate and Release R8/resource shrinking. The official 0.1.149 build passed 217 freshly run JVM tests and six Python analyzer tests with zero failures/errors/skips, release lint with 0 errors/97 warnings, runtime reconstruction and the offline notice catalog checks.

Build tools: JDK 21, Gradle 9.6.0, AGP 9.4.1, Kotlin 2.3.21, Android SDK 37.2/build-tools 37.0.0, NDK 28.2.13676358 and CMake 3.22.1. A local offline mirror supplied the pinned dependencies. Temporary paths, logs, verification signing key and private name mappings are not distributed.

These checks establish library replacement/recombination and recipient signing on the host. They do not establish byte-identical APK reproduction, Android hardware/game compatibility, a device FPS benefit, remediation of third-party 16 KiB-page limitations, or complete legal compliance for every separately bundled component. See repository SOURCE_CODE.md for source coverage and existing outstanding items.
