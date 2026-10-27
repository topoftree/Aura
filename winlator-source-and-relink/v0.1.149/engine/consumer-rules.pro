# zstd-jni's Android AAR does not ship consumer rules. Its native code looks up
# fields such as srcPos/dstPos and nativePtr by name, and mutates them outside
# Java's visibility. Keeping only native method names is insufficient: R8 can
# rename/remove those fields or fold reads of values written from JNI.
# Keep this small JNI boundary intact; the rest of Aura remains optimized.
-keep class com.github.luben.zstd.** { *; }
