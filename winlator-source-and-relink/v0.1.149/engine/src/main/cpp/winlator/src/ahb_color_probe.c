/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
#include <jni.h>
#include "ahb_color_probe.h"

JNIEXPORT jintArray JNICALL
Java_com_winlator_renderer_GPUImage_readHardwareBufferPixels(JNIEnv *env,jclass cls,
    jlong pointer,jint width,jint height) {
    (void)cls;
    int32_t values[AURA_AHB_PIXEL_VALUES];
    aura_ahb_color_probe((AHardwareBuffer *)(uintptr_t)pointer,width,height,values);
    jintArray result=(*env)->NewIntArray(env,AURA_AHB_PIXEL_VALUES);
    if (result) (*env)->SetIntArrayRegion(env,result,0,AURA_AHB_PIXEL_VALUES,values);
    return result;
}
