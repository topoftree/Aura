# Verification — Aura 0.1.101

Checked on 2026-10-20 against the official APK hash in README.md. The machine-readable results are in [validation.json](validation.json).

| Check | Result |
| --- | --- |
| Engine provenance | 948 tracked files compared with pinned Winlator 11.2: 721 unchanged, 88 modified and 139 added. Original notices retained and changes recorded in MODIFICATIONS.tsv. |
| Independent application objects | 1,197 classes; 1,178 renamed. No engine classes or source/debug/Kotlin metadata in the published application JAR. |
| JVM verification | Both original and obfuscated objects: 1,197 classes loaded, 25 serializer lookups and 49 controller/serialization assertions passed. |
| Baseline recombination | Engine compiled and APK independently recombined with R8 optimization and resource shrinking; 274 indexed native payloads verified. |
| Java replacement | A temporary engine method and marker reached the rebuilt APK. |
| Native replacement | A temporary engine export and marker reached the rebuilt native library. |
| Resource replacement | A newly added engine string reached resources.arsc and received a new resource ID. |
| Stable resources | 7,829 original linked resource IDs stayed unchanged. 741 additional stable-input entries were absent from both baseline and modified output. |
| Rebuilt APK contracts | 12 JNI fields in 8 classes, 24 Steam reflection contracts and 8 navigation themes passed for both baseline and modified builds. |
| Recipient signing | The modified APK was signed with a newly generated temporary key and passed signature and ZIP-alignment verification. No production key was used. |
| Publication restoration | Temporary Java/native probes and the test resource were removed. Restored source hashes were checked before archiving. |
| Publication boundary | Private application source, original application objects, private mappings, build directories, signing material and diagnostic captures excluded. |

The official release APK was not replaced by either test build. It retains the previously verified upgrade signing certificate and its Release R8/resource shrinking. The final production build passed 42 removal/navigation regression cases and Android lint with 0 errors and 24 warnings.

The host builds used JDK 21, Gradle 9.6.0, AGP 9.4.1, Kotlin 2.3.21, Android SDK 37.2/build-tools 37.0.0, NDK 28.2.13676358 and CMake 3.22.1. An offline mirror supplied the same pinned dependencies; that machine's paths and private logs are not part of the kit.

These tests establish a working library-replacement and signing path on the build host. They do not establish byte-identical APK reproduction, Android hardware/game compatibility, a remedy for existing third-party 16 KiB-page limitations, or legal compliance for every separately bundled runtime. New 0.1.101 storage export, real dependency installation, Steam background checks and physical-controller UI behavior still require phone testing.
