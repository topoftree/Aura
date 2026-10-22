# Publication scope

The LGPL-covered Winlator engine is supplied as editable source with its original notices and modification record. Its readable source is deliberate and necessary to this distribution approach.

The independent application object JAR contains 1,528 classes; 1,508 class names were transformed. Preparation does not shrink or inline implementation across the library boundary. Winlator is a library input during that pass. The final combined build separately enables R8 optimization and resource shrinking.

SourceFile, SourceDebugExtension, LineNumberTable, LocalVariableTable, Kotlin Metadata and coroutine DebugMetadata are removed from the independent application objects. Manifest/reflection/JNI/worker identities needed for correct operation may remain. Runtime serialization field names and product strings are not secrets and can remain visible. Compiled resource inputs contain no private cloud build-directory paths.

Independent Aura `.kt`/`.java` application source, original unprocessed application objects, private R8 mappings, signing keys/configuration, build logs and user diagnostic captures are excluded. Build inputs required for recombination are retained. None of these measures prevents decompilation or overrides the recipient rights in NOTICE.md.

The checks apply to the newly added 0.1.118 material. They do not claim to erase prior downloads, forks or server-retained historical objects.
