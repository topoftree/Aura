# Recombination verification — 2026-10-19

Verified on a Linux build host using JDK 21, AGP 9.4.1, Gradle 9.6, Android SDK
37.2 / build-tools 37.0.0, NDK 28.2.13676358 and CMake 3.22.1.

The kit was built in a separate directory using the public 0.1.82 APK, exported
object inputs, an installed SDK and a dependency cache. The recombination build
does not consume the private Aura Kotlin application source or signing setup.

## Checks completed

- The standalone Gradle `relinkRelease` task completed successfully. It compiled
  the engine Java/Kotlin and native code, ran R8 optimization/obfuscation and
  resource shrinking, and assembled the modified APK.
- A verification-only Java method was added to `AppUtils`, an exported function
  was added to `wine_paths.c`, and a new string resource was referenced by the
  Java method. All three distinct markers were found in the final APK's DEX,
  rebuilt `libaura_wine_paths.so` and resource table. These temporary changes
  are **not** in the published production engine source.
- All **7,775** original resource IDs checked against the stable-ID input were
  unchanged; the verification string received a new ID.
- The linked resource archive was reduced from **2,622,647** to **1,817,529**
  bytes by resource shrinking. Engine R fields are non-final to support the
  optimized shrinker. Stale original DEX optimization profiles were omitted.
- All **274** indexed native payloads matched their SHA-256 index entries in
  the final APK. Supporting prebuilt runtime inputs are preserved without AAR
  stripping; the modified Winlator JNI library was rebuilt from source.
- `apksigner verify --verbose` accepted a copy signed with a new, local,
  verification-only key. `zipalign -c -P 16 4` passed on that signed copy.
  The official production signing configuration/key was not read or changed.
- Android package inspection reported `com.android.aura`, version `0.1.82`,
  code `83`. The official APK and release tag were not replaced.
- Publication checks found no independent Aura `.kt`/`.java` application source,
  private repository history, signing material or credential-pattern matches in
  the selected source/object files. Build logs, test APKs and the test key are
  excluded. The LGPL text matches the pinned upstream license byte-for-byte.

Verification-only unsigned APK SHA-256:

```text
593510c4b8b31beb49f3cf098cce812561748510f6ee4b553c4b46bc096b5b66
```

Verification-only locally signed APK SHA-256:

```text
884a088a17ea7962d84d04caaf3faf3d2554ececfa33ad862fc4e2dd96d6ff7a
```

These are test artifacts, not new official downloads. ZIP timestamps, tool inputs
and the recipient's signing key can change output hashes; bit-identical APK
reproduction is not claimed. See `SHA256SUMS` for the published kit's input hashes.

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
