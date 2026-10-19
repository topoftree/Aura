# Obfuscated objects and recombination verification — 2026-10-19

Verified on a Linux build host using JDK 21, AGP 9.4.1, Gradle 9.6, Android SDK
37.2 / build-tools 37.0.0, NDK 28.2.13676358 and CMake 3.22.1.

The kit was built in a separate directory using the public 0.1.82 APK, exported
object inputs, an installed SDK and a dependency cache. The recombination build
does not consume the private Aura Kotlin application source or signing setup.

## Checks completed

- Independent object preparation retained all **1,100** application classes,
  renamed **1,082**, and removed source/line/local-variable, SMAP, Kotlin and
  coroutine debug metadata. No `com.winlator` class was in the application JAR.
  The private preparation utility reproduced the same published JAR hash.
- Both original and transformed object inputs passed JVM bytecode verification
  and reflective class loading for all **1,100** classes. Reflective serializer
  lookup passed for all **21** application serializable types; **48** assertions
  covered those lookups, controller actions and JSON behavior. These are host
  object checks, not Android device execution of the final optimized APK.
- The standalone Gradle `relinkRelease` task completed successfully. It compiled
  the engine Java/Kotlin and native code, ran R8 optimization/obfuscation and
  resource shrinking, and assembled both baseline and modified-engine APKs using
  the new application objects. Final DEX checks verified **24** Steam reflection
  contracts and **12** zstd JNI fields in **8** classes.
- A verification-only Java method was added to `AppUtils`, an exported function
  was added to `wine_paths.c`, and a new string resource was referenced by the
  Java method. All three distinct markers were found in the final APK's DEX,
  rebuilt `libaura_wine_paths.so` and resource table. These temporary changes
  are **not** in the published production engine source.
- All **7,775** linked original resource IDs checked against the stable-ID input
  were unchanged; the verification string received a new ID. The same **741**
  non-linked input entries were absent in both baseline and modified link output.
- The modified linked resource archive was reduced from **2,622,731** to **1,817,612**
  bytes by resource shrinking. Engine R fields are non-final to support the
  optimized shrinker. Stale original DEX optimization profiles were omitted.
- All **274** indexed native payloads matched their SHA-256 index entries in
  the final APK. Supporting prebuilt runtime inputs are preserved without AAR
  stripping; the modified Winlator JNI library was rebuilt from source.
- `apksigner verify --verbose` accepted a copy signed with a new, local,
  verification-only key. `zipalign -c -P 16 4` passed on that signed copy.
  The production signing key was not exported, regenerated or used to sign the
  verification APK; test signing used only the separate verification key.
- Android package inspection reported `com.android.aura`, version `0.1.82`,
  code `83`. The official APK and release tag were not replaced.
- Publication checks found no independent Aura `.kt`/`.java` application source,
  private repository history, signing material or credential-pattern matches in
  the selected source/object files. Build logs, test APKs and the test key are
  excluded. The LGPL text matches the pinned upstream license byte-for-byte.

Verification-only unsigned APK SHA-256:

```text
03f8b5f90e5f2921f2b8e488e017e9109d26363adc5bbc321fd1dae17534ebdd
```

Verification-only locally signed APK SHA-256:

```text
8db084cc186f546910c67157de5ce4bcea386d7221805c1e8e4ae54390a10f01
```

These are test artifacts, not new official downloads. ZIP timestamps, tool inputs
and the recipient's signing key can change output hashes; bit-identical APK
reproduction is not claimed. See `SHA256SUMS` for the published kit's input hashes.
The baseline build before temporary engine changes had SHA-256
`f398a979c1743160ab1fb2d2d82278420f4bfafa4611248af48ccb887aa9720e`.

## Limits

No Android device installation, launcher sign-in, game startup, performance test
or 16 KB device execution was performed. ZIP alignment does not establish the
alignment/compatibility of every prebuilt ELF library. A user-signed installation
and representative game run still need device validation.

The source/private-app boundary and separate runtime licensing obligations remain
as described in [NOTICE.md](NOTICE.md) and [SOURCE_CODE.md](../../SOURCE_CODE.md).
Successful recompilation and signing do not certify legal compliance for the
whole APK. The new private-app license dialog passed release Kotlin compilation;
it is not part of the already-published 0.1.82 binary or its frozen object inputs.
Obfuscated objects remain decompilable. Published-history cleanup does not
guarantee deletion of server-side retained objects or prior downloads;
see [PRIVACY.md](PRIVACY.md).
