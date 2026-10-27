/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>

#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <jni.h>
#include <string.h>
#include <stdbool.h>
#include <sys/mman.h>

#include "native_handle.h"
#include "dri3_hardware_buffer.h"
#include "winlator.h"

#define HAL_PIXEL_FORMAT_BGRA_8888 5

extern const native_handle_t* _Nullable AHardwareBuffer_getNativeHandle(const AHardwareBuffer* _Nonnull buffer);

int AHardwareBuffer_getFd(AHardwareBuffer* hardwareBuffer) {
    const native_handle_t* nativeHandle = AHardwareBuffer_getNativeHandle(hardwareBuffer);
    return nativeHandle->numFds > 0 ? nativeHandle->data[0] : -1;
}

EGLImageKHR createImageKHR(AHardwareBuffer* hardwareBuffer, int textureId) {
    const EGLint attribList[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    if (!hardwareBuffer) return EGL_NO_IMAGE_KHR;
    // GPUImage owns the allocation reference until destroyHardwareBuffer(). EGL owns
    // its own image reference; an extra acquire here leaked on success and failure.

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(hardwareBuffer);
    if (!clientBuffer) return NULL;

    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLImageKHR imageKHR = eglCreateImageKHR(eglDisplay, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attribList);
    if (!imageKHR) return NULL;

    for (int i = 0; i < 8 && glGetError() != GL_NO_ERROR; ++i) {}
    glBindTexture(GL_TEXTURE_2D, textureId);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, imageKHR);
    GLenum error = glGetError();
    glBindTexture(GL_TEXTURE_2D, 0);
    if (error != GL_NO_ERROR) {
        eglDestroyImageKHR(eglDisplay, imageKHR);
        return EGL_NO_IMAGE_KHR;
    }

    return imageKHR;
}

AHardwareBuffer* createHardwareBuffer(int width, int height, bool cpuAccess, bool useHALPixelFormatBGRA8888) {
    AHardwareBuffer_Desc buffDesc = {0};
    buffDesc.width = width;
    buffDesc.height = height;
    buffDesc.layers = 1;
    buffDesc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (cpuAccess) buffDesc.usage |= AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
    buffDesc.format = useHALPixelFormatBGRA8888 ? HAL_PIXEL_FORMAT_BGRA_8888 : AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;

    AHardwareBuffer* hardwareBuffer = NULL;
    AHardwareBuffer_allocate(&buffDesc, &hardwareBuffer);

    return hardwareBuffer;
}

static void describeHardwareBuffer(JNIEnv *env, jobject obj, AHardwareBuffer *hardwareBuffer) {
    if (hardwareBuffer) {
        jclass cls = (*env)->GetObjectClass(env, obj);

        AHardwareBuffer_Desc buffDesc = {0};
        AHardwareBuffer_describe(hardwareBuffer, &buffDesc);
        jmethodID setDescription = (*env)->GetMethodID(env, cls, "setHardwareBufferDescription", "(IIIIIJ)V");
        if (!setDescription) return;
        (*env)->CallVoidMethod(env, obj, setDescription, (jint)buffDesc.width, (jint)buffDesc.height,
            (jint)buffDesc.stride, (jint)buffDesc.format, (jint)buffDesc.layers, (jlong)buffDesc.usage);
        if ((*env)->ExceptionCheck(env)) return;

        // Stable allocation identity survives a second import of the same AHB.
        // Pointer/FD/GL texture names cannot distinguish aliases safely. Unknown
        // identity conservatively disables concurrent old-buffer reclamation.
        uint64_t bufferId = 0;
        if (AHardwareBuffer_getId(hardwareBuffer, &bufferId) != 0) bufferId = 0;
        jmethodID setBufferId = (*env)->GetMethodID(env, cls, "setHardwareBufferId", "(J)V");
        if (!setBufferId) return;
        (*env)->CallVoidMethod(env, obj, setBufferId, (jlong)bufferId);
        if ((*env)->ExceptionCheck(env)) return;

        jmethodID setStride = (*env)->GetMethodID(env, cls, "setStride", "(S)V");
        (*env)->CallVoidMethod(env, obj, setStride, (jshort)buffDesc.stride);

        int fd = AHardwareBuffer_getFd(hardwareBuffer);
        if (fd != -1) {
            jmethodID setNativeHandle = (*env)->GetMethodID(env, cls, "setNativeHandle", "(I)V");
            (*env)->CallVoidMethod(env, obj, setNativeHandle, fd);
        }
    }
}

JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_GPUImage_createHardwareBuffer(JNIEnv *env, jobject obj, jshort width,
                                                         jshort height, jboolean cpuAccess, jboolean useHALPixelFormatBGRA8888) {
    AHardwareBuffer *hardwareBuffer = createHardwareBuffer(width, height, cpuAccess, useHALPixelFormatBGRA8888);
    describeHardwareBuffer(env, obj, hardwareBuffer);
    return (jlong)hardwareBuffer;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_GPUImage_receiveHardwareBuffer(JNIEnv *env, jobject obj, jint fd, jint width, jint height) {
    AHardwareBuffer *buffer = aura_dri3_receive_hardware_buffer(fd, width, height);
    describeHardwareBuffer(env, obj, buffer);
    if ((*env)->ExceptionCheck(env) && buffer) {
        AHardwareBuffer_release(buffer);
        return 0;
    }
    return (jlong)buffer;
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_GPUImage_abortHardwareBufferImport(JNIEnv *env, jclass cls, jint fd) {
    aura_dri3_abort_hardware_buffer(fd);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_GPUImage_createImageKHR(JNIEnv *env, jclass obj,
                                                   jlong hardwareBufferPtr, jint textureId) {
    return (jlong)createImageKHR((AHardwareBuffer*)hardwareBufferPtr, textureId);
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_GPUImage_destroyHardwareBuffer(JNIEnv *env, jclass obj,
                                                          jlong hardwareBufferPtr, jboolean locked) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (hardwareBuffer) {
        if (locked) {
            AHardwareBuffer_unlock(hardwareBuffer, NULL);
            locked = false;
        }
        AHardwareBuffer_release(hardwareBuffer);
    }
}

JNIEXPORT jobject JNICALL
Java_com_winlator_renderer_GPUImage_lockHardwareBuffer(JNIEnv *env, jclass obj,
                                                       jlong hardwareBufferPtr) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    void *virtualAddr = NULL;
    if (!hardwareBuffer || AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                                                -1, NULL, &virtualAddr) || !virtualAddr) return NULL;

    AHardwareBuffer_Desc buffDesc = {0};
    AHardwareBuffer_describe(hardwareBuffer, &buffDesc);

    jlong size = buffDesc.stride * buffDesc.height * 4;
    jobject data = (*env)->NewDirectByteBuffer(env, virtualAddr, size);
    if (!data) AHardwareBuffer_unlock(hardwareBuffer, NULL);
    return data;
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_GPUImage_destroyImageKHR(JNIEnv *env, jclass obj, jlong imageKHRPtr) {
    EGLImageKHR imageKHR = (EGLImageKHR)imageKHRPtr;
    if (imageKHR) {
        EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        eglDestroyImageKHR(eglDisplay, imageKHR);
    }
}
