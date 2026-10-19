# Aura 0.1.82 added integration file; notice dated 2026-10-19. See NOTICE.md and MODIFICATIONS.tsv.
# zstd-jni's Android AAR does not ship consumer rules. Its native code looks up
# fields such as srcPos/dstPos and nativePtr by name, and mutates them outside
# Java's visibility. Keeping only native method names is insufficient: R8 can
# rename/remove those fields or fold reads of values written from JNI.
# Keep this small JNI boundary intact; the rest of Aura remains optimized.
-keep class com.github.luben.zstd.** { *; }
