# Application object privacy and compatibility

The current `objects/aura-1.jar` has been independently obfuscated before the
final R8 release build. This raises the effort needed to inspect the independent
application; it does **not** make the object code impossible to decompile.

| Check | Result |
| --- | --- |
| Application class files | 1,100 |
| Classes with changed names | 1,082 |
| Original class names retained | 18; Android entry points, workers and resource classes |
| Winlator engine classes in the application object JAR | 0 |
| Source file, SMAP, line-number and local-variable metadata | Removed |
| Kotlin metadata and coroutine debug metadata | Removed |
| Object JAR size | 1,984,520 bytes |
| Object JAR SHA-256 | `96e9aacaf43c90353c0c19631fa58d60705a35a57a4e01ecdc290e60a73513c2` |

The preparation pass renames independent application classes and members. It
disables shrinking and optimization during that pass, using the engine and
dependencies only as library inputs, so it does not inline an engine
implementation into application objects. A separate pass removes debug/Kotlin
metadata and rebuilds class-file constant pools. The final Gradle release task
still enables R8 optimization, obfuscation and resource shrinking.

Android entry points, worker names, resource IDs and serialization reflection
contracts must remain usable. Runtime strings, serialized field names, resource
identifiers, interfaces and executable behavior remain inspectable. Some classes
retain their package namespaces for access compatibility. Removing metadata also
reduces diagnostic detail; the private mapping is retained outside this repository.

The Winlator-derived implementation remains editable source with its copyright
and license notices. Recipients can modify that engine, build a combined APK and
sign it with their own key without the private mapping or independent app source.
The permissions in [NOTICE.md](NOTICE.md), including reverse engineering for
debugging LGPL component modifications, remain applicable. This preparation
depends on the stated independent-application/derived-engine boundary being valid.

The earlier unobfuscated 0.1.82 application object has been replaced in the
published branch/tag history with the pre-obfuscated object. The matching
editable engine source and usable recombination materials remain supplied.
Git history cleanup does not purge GitHub server-side LFS objects or caches;
server-side removal requires separate verification. Prior downloads cannot
be recalled. The official APKs are unchanged.

See [VALIDATION.md](VALIDATION.md) for actual host checks and device-test limits.
