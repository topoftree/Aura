/* Aura 0.1.82 local modified file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
#include <jni.h>
#include <sys/stat.h>

#include "vk_context.h"
#include "vortek_serializer.h"
#include "request_handler.h"
#include "vulkan_helper.h"
#include "jni_utils.h"

#include "adrenotools/driver.h"

VulkanWrapper vulkanWrapper = {0};
bool vortekSerializerCastVkObject = true;

static void* openVulkanLibrary(JNIEnv* env, jstring nativeLibraryDir, jstring libvulkanPath) {
    void* libvulkan;
    if (libvulkanPath) {
        const char* nativeLibraryDirC = (*env)->GetStringUTFChars(env, nativeLibraryDir, NULL);
        if (!nativeLibraryDirC) return NULL;
        const char* libvulkanPathC = (*env)->GetStringUTFChars(env, libvulkanPath, NULL);
        if (!libvulkanPathC) {
            (*env)->ReleaseStringUTFChars(env, nativeLibraryDir, nativeLibraryDirC);
            return NULL;
        }

        char libvulkanDir[PATH_MAX] = {0};
        char tmpDir[PATH_MAX] = {0};
        // dirname() can modify its input; JNI UTF buffers are read-only. Keep name and directory separate.
        const char* slash = strrchr(libvulkanPathC, '/');
        size_t dirLength = slash ? (size_t)(slash - libvulkanPathC + 1) : 0;
        libvulkan = NULL;
        if (dirLength > 0 && dirLength + 4 <= sizeof(tmpDir) && slash[1] != '\0') {
            memcpy(libvulkanDir, libvulkanPathC, dirLength);
            snprintf(tmpDir, sizeof(tmpDir), "%stmp", libvulkanDir);
            mkdir(tmpDir, S_IRWXU | S_IRWXG);
            libvulkan = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM,
                tmpDir, nativeLibraryDirC, libvulkanDir, slash + 1, NULL, NULL);
        }

        (*env)->ReleaseStringUTFChars(env, nativeLibraryDir, nativeLibraryDirC);
        (*env)->ReleaseStringUTFChars(env, libvulkanPath, libvulkanPathC);
    }
    else libvulkan = dlopen(LIBVULKAN_PATH, RTLD_NOW | RTLD_LOCAL);

    if (!libvulkan) println("vortek: unable to open libvulkan: %s", dlerror());
    return libvulkan;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_createVkContext(JNIEnv *env,
                                                                                  jobject obj,
                                                                                  jint clientFd,
                                                                                  jobject options) {
    VkContext* context = createVkContext(env, obj, clientFd, options);
    return context ? (jlong)context : 0;
}

JNIEXPORT void JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_destroyVkContext(JNIEnv *env,
                                                                                   jobject obj,
                                                                                   jlong contextPtr) {
    destroyVkContext(env, (VkContext*)contextPtr);
}

JNIEXPORT jint JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_initVulkanWrapper(JNIEnv *env,
                                                                                    jobject obj,
                                                                                    jstring nativeLibraryDir,
                                                                                    jstring libvulkanPath) {
    void* libvulkan = openVulkanLibrary(env, nativeLibraryDir, libvulkanPath);
    if (!libvulkan) return 1;
    initVulkanWrapper(&vulkanWrapper, libvulkan);
    if (!vulkanWrapper.vkCreateInstance || !vulkanWrapper.vkGetInstanceProcAddr ||
        !vulkanWrapper.vkGetDeviceProcAddr || !vulkanWrapper.vkEnumerateInstanceExtensionProperties) {
        memset(&vulkanWrapper, 0, sizeof(vulkanWrapper));
        return 2;
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_handleExtraDataRequest(JNIEnv *env,
                                                                                         jobject obj,
                                                                                         jlong contextPtr,
                                                                                         int requestId,
                                                                                         int requestLength) {
    return handleExtraDataRequest((VkContext*)contextPtr, requestId, requestLength);
}
