/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Aura contributors
 * A separate process: a broken driver must not terminate the Android host.
 * Exit zero only after the real runtime loader creates a Mesa PanVK device.
 */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc != 2) return 10;
    /* Fail closed on missing X11 dependencies, before the first game opens a window. */
    const char *libs[] = {"libxcb.so.1", "libX11-xcb.so.1", "libxcb-dri3.so.0",
        "libxcb-present.so.0", "libxcb-shm.so.0", "libxcb-sync.so.1",
        "libxcb-xfixes.so.0", "libxcb-randr.so.0", "libxshmfence.so.1"};
    for (size_t i = 0; i < sizeof(libs) / sizeof(libs[0]); ++i)
        if (!dlopen(libs[i], RTLD_NOW | RTLD_LOCAL)) return 11;
    void *loader = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!loader) return 12;
    PFN_vkGetInstanceProcAddr get = (PFN_vkGetInstanceProcAddr)dlsym(loader, "vkGetInstanceProcAddr");
    if (!get) return 13;
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)get(VK_NULL_HANDLE, "vkCreateInstance");
    if (!create) return 14;
    const char *extensions[] = {"VK_KHR_surface", "VK_KHR_xlib_surface", "VK_KHR_xcb_surface"};
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "Aura PanVK probe",
        .apiVersion = VK_API_VERSION_1_3};
    VkInstanceCreateInfo info = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app,
        .enabledExtensionCount = 3, .ppEnabledExtensionNames = extensions};
    VkInstance instance = VK_NULL_HANDLE;
    if (create(&info, NULL, &instance) != VK_SUCCESS) return 15;
    PFN_vkDestroyInstance destroy = (PFN_vkDestroyInstance)get(instance, "vkDestroyInstance");
    PFN_vkEnumeratePhysicalDevices enumerate = (PFN_vkEnumeratePhysicalDevices)get(instance, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties2 properties = (PFN_vkGetPhysicalDeviceProperties2)get(instance, "vkGetPhysicalDeviceProperties2");
    PFN_vkGetPhysicalDeviceQueueFamilyProperties queues = (PFN_vkGetPhysicalDeviceQueueFamilyProperties)get(instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    PFN_vkCreateDevice create_device = (PFN_vkCreateDevice)get(instance, "vkCreateDevice");
    PFN_vkGetDeviceProcAddr device_proc = (PFN_vkGetDeviceProcAddr)get(instance, "vkGetDeviceProcAddr");
    if (!destroy || !enumerate || !properties || !queues || !create_device || !device_proc) return 16;
    int status = 17;
    VkPhysicalDevice devices[8];
    uint32_t count = 8;
    VkResult result = enumerate(instance, &count, devices);
    if ((result != VK_SUCCESS && result != VK_INCOMPLETE) || count == 0 || count > 8) goto done;
    for (uint32_t i = 0; i < count; ++i) {
        VkPhysicalDeviceDriverProperties driver = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
        VkPhysicalDeviceProperties2 props = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &driver};
        properties(devices[i], &props);
        if (driver.driverID != VK_DRIVER_ID_MESA_PANVK || props.properties.vendorID != 0x13b5 ||
            props.properties.apiVersion < VK_API_VERSION_1_3) continue;
        uint32_t families = 0;
        queues(devices[i], &families, NULL);
        if (families == 0 || families > 64) { status = 18; continue; }
        VkQueueFamilyProperties family[64];
        queues(devices[i], &families, family);
        if (families > 64) { status = 18; continue; }
        for (uint32_t q = 0; q < families; ++q) {
            if (!family[q].queueCount || !(family[q].queueFlags & VK_QUEUE_GRAPHICS_BIT)) continue;
            float priority = 1.0f;
            VkDeviceQueueCreateInfo queue = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                .queueFamilyIndex = q, .queueCount = 1, .pQueuePriorities = &priority};
            const char *swapchain = "VK_KHR_swapchain";
            VkDeviceCreateInfo device_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                .queueCreateInfoCount = 1, .pQueueCreateInfos = &queue,
                .enabledExtensionCount = 1, .ppEnabledExtensionNames = &swapchain};
            VkDevice device = VK_NULL_HANDLE;
            if (create_device(devices[i], &device_info, NULL, &device) != VK_SUCCESS) { status = 19; break; }
            PFN_vkDestroyDevice destroy_device = (PFN_vkDestroyDevice)device_proc(device, "vkDestroyDevice");
            if (!destroy_device) { status = 20; break; }
            destroy_device(device, NULL);
            status = 0;
            goto done;
        }
    }
done:
    destroy(instance, NULL);
    /* All handles go away with this process; never unload a driver's background threads. */
    printf("AURA_PANVK_PROBE_V1 status=%d\n", status);
    return status;
}
