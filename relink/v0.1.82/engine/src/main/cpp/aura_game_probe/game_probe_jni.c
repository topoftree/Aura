/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
/* SPDX-License-Identifier: MIT */
#include "game_probe.h"
#include <jni.h>

static jstring capture(JNIEnv *env, jint pid, jlong start, jbyteArray executable, int detailed) {
    char name[AURA_PC_TARGET], text[AURA_PC_DETAIL];
    if (!executable || start <= 0) return (*env)->NewStringUTF(env, "state=UNAVAILABLE; samples=0");
    jsize length = (*env)->GetArrayLength(env, executable);
    if (length <= 0 || length >= AURA_PC_TARGET) return (*env)->NewStringUTF(env, "state=UNAVAILABLE; samples=0");
    (*env)->GetByteArrayRegion(env, executable, 0, length, (jbyte *)name);
    if ((*env)->ExceptionCheck(env)) return NULL;
    for (jsize i = 0; i < length; i++) if (!name[i]) return (*env)->NewStringUTF(env, "state=UNAVAILABLE; samples=0");
    name[length] = 0;
    struct aura_pc_result result;
    if (detailed) aura_pc_collect_detailed(pid, (uint64_t)start, name, &result);
    else aura_pc_collect(pid, (uint64_t)start, name, &result);
    if (detailed) aura_pc_format_detail(&result, text); else aura_pc_format(&result, text);
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jstring JNICALL Java_com_winlator_core_GameNativePcProbe_captureNative(
    JNIEnv *env, jobject object, jint pid, jlong start, jbyteArray executable) {
    (void)object; return capture(env, pid, start, executable, 0);
}
JNIEXPORT jstring JNICALL Java_com_winlator_core_GameNativePcProbe_captureDetailedNative(
    JNIEnv *env, jobject object, jint pid, jlong start, jbyteArray executable) {
    (void)object; return capture(env, pid, start, executable, 1);
}
