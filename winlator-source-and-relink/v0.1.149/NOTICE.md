# Winlator source and recombination materials — Aura 0.1.149

Winlator is copyright its original authors and contributors. The upstream base is [Winlator 11.2, commit 3981d86efa4f333b2a34a7da8b6521476cd8c8b9](https://github.com/brunodev85/winlator-app/tree/3981d86efa4f333b2a34a7da8b6521476cd8c8b9). Its original [LGPL 2.1](engine/LICENSE) is retained unchanged.

The `engine/` directory supplies Winlator-derived Java/Kotlin, native/JNI integration, Android resources and the build definitions for this version. Aura contributions to the derivative engine are supplied under LGPL 2.1. Independently licensed embedded files retain their original terms and notices.

`MODIFICATIONS.tsv` compares 1067 distributed engine source/resource records with the pinned upstream tree. `UNCHANGED` means identical bytes; `MODIFIED` and `ADDED` identify integration changes. The production-source SHA-256 column is recorded before publication notices are inserted. Modified/new Java, Kotlin, C/C++ and XML files carry a publication notice dated 2026-10-27. Original license/copyright notices are preserved. The standalone Gradle build is adapted to obtain runtime assets from the matching APK.

The engine changes include Android/ARM64EC integration; process/session, display/input/audio and dependency support; compatibility repairs; and bounded optional diagnostics. They remain editable source, not obfuscated replacements for source.

## Application objects

`objects/aura-1.jar` supplies the independent application in object-code form. Its class/member names are transformed and debug/Kotlin metadata stripped before the final combined R8 build. It contains no Winlator implementation classes. The other JARs contain dependency/resource objects under their applicable licenses. Resources, the manifest, stable IDs and optimization rules are build inputs.

To the extent of Aura's rights, recipients may copy and redistribute these recombination materials with their notices, modify the combined work for their own use, and combine/relink the application objects with modified LGPL components. Reverse engineering to debug modifications to LGPL components is expressly permitted. These permissions prevail over any inconsistent Aura copyright notice or EULA. Third-party modification/redistribution rights remain unchanged.

The application objects are not a grant of an open-source license over the independent Aura application. Retaining that source privately depends on a valid independent-application/derived-library boundary. Merely renaming or translating library code would not establish independence. Obfuscation does not prevent decompilation or cancel the permissions above.

Recipients can build and sign modified copies with their own keys; the production signing key and private name mapping are not needed or supplied. See README for Android installation limitations.

## Separate runtime components

This kit addresses the Winlator Android engine and its recombination with Aura 0.1.149. Additional Wine/ntsync and runtime source materials are provided separately. Other components' source obligations and proprietary-driver redistribution rights remain separate; see repository `SOURCE_CODE.md` and `THIRD_PARTY_NOTICES.md`. The technical checks are not a legal certification of the entire APK or the application/library boundary.
