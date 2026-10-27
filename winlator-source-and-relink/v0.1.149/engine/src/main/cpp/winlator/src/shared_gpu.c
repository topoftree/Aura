/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#define _GNU_SOURCE
#include <jni.h>
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <errno.h>
#include <poll.h>
#include <string.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include "shared_gpu_protocol.h"

#define JNI(name) Java_com_winlator_renderer_SharedGpuBridge_##name

struct aura_egl_image { EGLDisplay display; EGLImageKHR image; };

static int read_all(int fd, void *data, size_t size) {
    unsigned char *p = data;
    while (size) {
        ssize_t n = recv(fd, p, size, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        p += n; size -= n;
    }
    return 1;
}

JNIEXPORT jint JNICALL JNI(listen)(JNIEnv *env, jclass cls, jstring path) {
    const char *name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name) return -1;
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    int fd = -1;
    if (strlen(name) < sizeof(addr.sun_path)) {
        memcpy(addr.sun_path, name, strlen(name) + 1);
        fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd >= 0) {
            unlink(name);
            if (bind(fd, (void *)&addr, sizeof(addr)) || chmod(name, 0600) || listen(fd, 2)) {
                close(fd); fd = -1;
            }
        }
    }
    (*env)->ReleaseStringUTFChars(env, path, name);
    return fd;
}

JNIEXPORT jint JNICALL JNI(accept)(JNIEnv *env, jclass cls, jint server) {
    struct pollfd p = {.fd = server, .events = POLLIN};
    if (poll(&p, 1, 250) <= 0 || !(p.revents & POLLIN)) return -1;
    int fd = accept4(server, NULL, NULL, SOCK_CLOEXEC);
    if (fd < 0) return -1;
    struct ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) || cred.uid != getuid()) {
        close(fd); return -1;
    }
    struct timeval timeout = {.tv_sec = 3};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    return fd;
}

JNIEXPORT jintArray JNICALL JNI(hello)(JNIEnv *env, jclass cls, jint fd) {
    struct aura_gpu_hello h;
    if (!read_all(fd, &h, sizeof(h)) || h.magic != AURA_GPU_MAGIC || !h.window ||
        !aura_gpu_dimensions(h.width, h.height)) return NULL;
    jint values[] = {(jint)h.window, (jint)h.width, (jint)h.height};
    jintArray out = (*env)->NewIntArray(env, 3);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 3, values);
    return out;
}

JNIEXPORT jlong JNICALL JNI(receiveBuffer)(JNIEnv *env, jclass cls, jint fd, jint w, jint h) {
    AHardwareBuffer *buffer = NULL;
    if (AHardwareBuffer_recvHandleFromUnixSocket(fd, &buffer) || !buffer) return 0;
    AHardwareBuffer_Desc d;
    AHardwareBuffer_describe(buffer, &d);
    const uint64_t usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (d.width != (uint32_t)w || d.height != (uint32_t)h || d.layers != 1 || d.stride < d.width ||
        d.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM || (d.usage & usage) != usage ||
        !(d.usage & AHARDWAREBUFFER_USAGE_CPU_READ_MASK)) {
        AHardwareBuffer_release(buffer); return 0;
    }
    return (jlong)(uintptr_t)buffer;
}

JNIEXPORT jintArray JNICALL JNI(present)(JNIEnv *env, jclass cls, jint fd) {
    // An idle/paused game keeps its pair. Bound only incomplete packet reads,
    // not the interval between frames; shutdown still wakes the worker.
    struct pollfd ready = {.fd = fd, .events = POLLIN};
    int status = poll(&ready, 1, 250);
    if (status == 0 || (status < 0 && errno == EINTR)) return (*env)->NewIntArray(env, 0);
    if (status < 0 || !(ready.revents & POLLIN)) return NULL;
    struct aura_gpu_present p;
    if (!read_all(fd, &p, sizeof(p)) || p.magic != AURA_GPU_MAGIC || p.slot > 1 || p.serial == 0) return NULL;
    jint values[] = {(jint)p.slot, (jint)p.serial};
    jintArray out = (*env)->NewIntArray(env, 2);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 2, values);
    return out;
}

JNIEXPORT jboolean JNICALL JNI(reply)(JNIEnv *env, jclass cls, jint fd, jint serial) {
    uint32_t value = (uint32_t)serial;
    return send(fd, &value, sizeof(value), MSG_NOSIGNAL) == sizeof(value);
}
JNIEXPORT void JNICALL JNI(shutdown)(JNIEnv *env, jclass cls, jint fd) { if (fd >= 0) shutdown(fd, SHUT_RDWR); }
JNIEXPORT void JNICALL JNI(close)(JNIEnv *env, jclass cls, jint fd) { if (fd >= 0) close(fd); }
JNIEXPORT void JNICALL JNI(acquire)(JNIEnv *env, jclass cls, jlong ptr) { if (ptr) AHardwareBuffer_acquire((void *)(uintptr_t)ptr); }
JNIEXPORT void JNICALL JNI(release)(JNIEnv *env, jclass cls, jlong ptr) { if (ptr) AHardwareBuffer_release((void *)(uintptr_t)ptr); }
JNIEXPORT jlong JNICALL JNI(context)(JNIEnv *env, jclass cls) { return (jlong)(uintptr_t)eglGetCurrentContext(); }

JNIEXPORT jlong JNICALL JNI(image)(JNIEnv *env, jclass cls, jlong ptr, jint texture) {
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC client = (void *)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    PFNEGLCREATEIMAGEKHRPROC create = (void *)eglGetProcAddress("eglCreateImageKHR");
    PFNEGLDESTROYIMAGEKHRPROC destroy = (void *)eglGetProcAddress("eglDestroyImageKHR");
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC bind_image = (void *)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    if (!ptr || !client || !create || !destroy || !bind_image) return 0;
    EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY) return 0;
    EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLImageKHR image = create(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client((void *)(uintptr_t)ptr), attrs);
    if (image == EGL_NO_IMAGE_KHR) return 0;
    for (int i = 0; i < 8 && glGetError() != GL_NO_ERROR; ++i) {}
    glBindTexture(GL_TEXTURE_2D, texture);
    bind_image(GL_TEXTURE_2D, image);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_ONE);
    if (glGetError() != GL_NO_ERROR) { destroy(display, image); return 0; }
    struct aura_egl_image *owned = malloc(sizeof(*owned));
    if (!owned) { destroy(display, image); return 0; }
    *owned = (struct aura_egl_image){display, image};
    return (jlong)(uintptr_t)owned;
}

JNIEXPORT void JNICALL JNI(destroyImage)(JNIEnv *env, jclass cls, jlong ptr) {
    PFNEGLDESTROYIMAGEKHRPROC destroy = (void *)eglGetProcAddress("eglDestroyImageKHR");
    struct aura_egl_image *owned = (void *)(uintptr_t)ptr;
    if (owned) {
        if (destroy) destroy(owned->display, owned->image);
        free(owned);
    }
}

/* Exceptional X11 read/partial write only; the normal present loop never maps pixels. */
JNIEXPORT jboolean JNICALL JNI(snapshot)(JNIEnv *env, jclass cls, jlong ptr, jobject dst) {
    if (!ptr || !dst) return JNI_FALSE;
    AHardwareBuffer *buffer = (void *)(uintptr_t)ptr;
    AHardwareBuffer_Desc d;
    AHardwareBuffer_describe(buffer, &d);
    uint32_t *target = (*env)->GetDirectBufferAddress(env, dst);
    jlong size = (*env)->GetDirectBufferCapacity(env, dst);
    if (!target || size < (jlong)d.width * d.height * 4) return JNI_FALSE;
    void *source = NULL;
    if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, NULL, &source) || !source) return JNI_FALSE;
    for (uint32_t y = 0; y < d.height; ++y) {
        const uint32_t *row = (uint32_t *)source + (size_t)y * d.stride;
        for (uint32_t x = 0; x < d.width; ++x) {
            uint32_t v = row[x];
            target[(size_t)y * d.width + x] = (v & 0xff00ff00u) | ((v & 255u) << 16) | ((v >> 16) & 255u);
        }
    }
    return AHardwareBuffer_unlock(buffer, NULL) == 0;
}
