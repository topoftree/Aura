/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_VULKAN_FAULT_OBSERVER_H
#define AURA_VULKAN_FAULT_OBSERVER_H
#include "../vortekrenderer/include/vulkan/vulkan_core.h"
#include <pthread.h>
#include <limits.h>
#include <time.h>

/* Handles are process-private and never copied into the mapped capture. */
struct aura_vk_link {
    VkDevice handle;
    PFN_vkGetDeviceProcAddr resolver;
    uint32_t device, queue, family, index;
    int enabled; /* 1 requested+enabled, 0 not enabled, -1 unknown */
};
static pthread_mutex_t aura_vk_registry_lock=PTHREAD_MUTEX_INITIALIZER;
static struct aura_vk_link aura_vk_devices[8];
static struct { VkQueue handle; VkDevice device; uint32_t id, family, index; } aura_vk_queues[32];
static uint32_t aura_vk_next_device, aura_vk_next_queue;

static int aura_vk_fault_enabled(const VkDeviceCreateInfo *info) {
    if (!info) return -1;
    int extension=0, feature=0;
    uint32_t count=info->enabledExtensionCount;
    if (count && !info->ppEnabledExtensionNames) return -1;
    for (uint32_t i=0;i<count && i<256;++i) {
        const char *name=info->ppEnabledExtensionNames[i];
        if (name && !strncmp(name,VK_EXT_DEVICE_FAULT_EXTENSION_NAME,256)) extension=1;
    }
    const VkBaseInStructure *node=(const VkBaseInStructure *)info->pNext;
    for (unsigned i=0;node && i<32;++i,node=node->pNext)
        if (node->sType==VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT)
            feature=((const VkPhysicalDeviceFaultFeaturesEXT *)node)->deviceFault ? 1 : 0;
    if (extension && feature) return 1;
    return (node || (count>256 && !extension)) ? -1 : 0;
}
static void aura_vk_register_device(VkDevice handle, int enabled) {
    if (!handle || pthread_mutex_trylock(&aura_vk_registry_lock)) return;
    for (unsigned i=0;i<8;++i) if (aura_vk_devices[i].handle==handle) {
        pthread_mutex_unlock(&aura_vk_registry_lock); return;
    }
    for (unsigned i=0;i<8;++i) if (!aura_vk_devices[i].handle && aura_vk_next_device<65535) {
        aura_vk_devices[i]=(struct aura_vk_link){.handle=handle,.device=++aura_vk_next_device,.enabled=enabled};break;
    }
    pthread_mutex_unlock(&aura_vk_registry_lock);
}
static void aura_vk_device_resolver(VkDevice handle, PFN_vkGetDeviceProcAddr resolver) {
    if (pthread_mutex_trylock(&aura_vk_registry_lock)) return;
    for (unsigned i=0;i<8;++i) if (aura_vk_devices[i].handle==handle) aura_vk_devices[i].resolver=resolver;
    pthread_mutex_unlock(&aura_vk_registry_lock);
}
static void aura_vk_register_queue(VkDevice device, VkQueue queue, uint32_t family, uint32_t index) {
    if (!device || !queue || pthread_mutex_trylock(&aura_vk_registry_lock)) return;
    for (unsigned i=0;i<32;++i) if (aura_vk_queues[i].handle==queue) {
        pthread_mutex_unlock(&aura_vk_registry_lock); return;
    }
    for (unsigned i=0;i<32;++i) if (!aura_vk_queues[i].handle && aura_vk_next_queue<65535) {
        aura_vk_queues[i].handle=queue; aura_vk_queues[i].device=device; aura_vk_queues[i].id=++aura_vk_next_queue; aura_vk_queues[i].family=family; aura_vk_queues[i].index=index;break;
    }
    pthread_mutex_unlock(&aura_vk_registry_lock);
}
static void aura_vk_forget_device(VkDevice handle) {
    /* Destruction is externally synchronized by Vulkan callers. Invalidate the
     * registry before forwarding destruction, never retain a fault-query job. */
    pthread_mutex_lock(&aura_vk_registry_lock);
    for (unsigned i=0;i<32;++i) if (aura_vk_queues[i].device==handle) memset(&aura_vk_queues[i],0,sizeof(aura_vk_queues[i]));
    for (unsigned i=0;i<8;++i) if (aura_vk_devices[i].handle==handle) memset(&aura_vk_devices[i],0,sizeof(aura_vk_devices[i]));
    pthread_mutex_unlock(&aura_vk_registry_lock);
}
static struct aura_vk_link aura_vk_link_for(VkDevice device, VkQueue queue) {
    struct aura_vk_link link={.enabled=-1,.family=UINT32_MAX,.index=UINT32_MAX};
    if (pthread_mutex_trylock(&aura_vk_registry_lock)) return link;
    uint32_t queue_id=0,family=UINT32_MAX,index=UINT32_MAX;
    if (queue) for (unsigned i=0;i<32;++i) if (aura_vk_queues[i].handle==queue) {
        device=aura_vk_queues[i].device; queue_id=aura_vk_queues[i].id;family=aura_vk_queues[i].family;index=aura_vk_queues[i].index;break;
    }
    for (unsigned i=0;i<8;++i) if (device && aura_vk_devices[i].handle==device) { link=aura_vk_devices[i];break; }
    link.queue=queue_id;link.family=family;link.index=index;
    pthread_mutex_unlock(&aura_vk_registry_lock);
    return link;
}
static uint32_t aura_vk_category(const char *description) {
    char text[257];
    for (unsigned i=0;i<256;++i) {
        unsigned char c=(unsigned char)description[i];
        text[i]=(char)(c>='A' && c<='Z' ? c+32 : c);
    }
    text[256]=0;
    uint32_t bits=0;
    if (strstr(text,"shader") || strstr(text,"spirv") || strstr(text,"spir-v")) bits|=1;
    if (strstr(text,"page") || strstr(text,"address")) bits|=2;
    if (strstr(text,"timeout") || strstr(text,"hang")) bits|=4;
    if (strstr(text,"out of memory")) bits|=8;
    return bits ? bits : 16;
}
static int aura_vk_query_live(struct aura_vk_fault *f, _Atomic uint32_t *stop,
        _Atomic uint64_t *activity, uint64_t epoch) {
    struct timespec time;
    if (atomic_load(stop) || atomic_load(activity)!=epoch || (epoch&1) || clock_gettime(CLOCK_MONOTONIC,&time)) return 0;
    uint64_t now=(uint64_t)time.tv_sec*UINT64_C(1000000000)+(uint64_t)time.tv_nsec;
    return now>=f->started_ns && now<=atomic_load(&f->deadline_ns);
}
static void aura_vk_driver_query(struct aura_vk_fault *f, struct aura_vk_link link,
        _Atomic uint32_t *stop, _Atomic uint64_t *activity, uint64_t epoch) {
    struct aura_vk_driver_fault report={0};
    report.state=AVF_NO_DEVICE;
    report.counts_result=report.detail_result=INT32_MAX;
    atomic_store(&f->driver.seq,1);
    if (!aura_vk_query_live(f,stop,activity,epoch)) return;
    if (link.handle) {
        report.state=link.enabled<0 ? AVF_ENABLE_UNKNOWN : AVF_NOT_ENABLED;
        if (link.enabled==1) {
            report.flags|=1; report.state=AVF_NO_RESOLVER;
            if (link.resolver) {
                PFN_vkGetDeviceFaultInfoEXT query=(PFN_vkGetDeviceFaultInfoEXT)link.resolver(link.handle,"vkGetDeviceFaultInfoEXT");
                report.state=AVF_NO_ENTRY;
                if (query && aura_vk_query_live(f,stop,activity,epoch)) {
                    VkDeviceFaultCountsEXT counts={.sType=VK_STRUCTURE_TYPE_DEVICE_FAULT_COUNTS_EXT};
                    report.counts_result=query(link.handle,&counts,NULL);
                    report.state=AVF_COUNTS_FAILED;
                    if (report.counts_result==VK_SUCCESS || report.counts_result==VK_INCOMPLETE) {
                        report.address_total=counts.addressInfoCount; report.vendor_total=counts.vendorInfoCount;
                        report.vendor_binary_bytes=counts.vendorBinarySize;
                        VkDeviceFaultAddressInfoEXT addresses[16]={0};
                        VkDeviceFaultVendorInfoEXT vendors[8]={0};
                        uint32_t ac=counts.addressInfoCount<16 ? counts.addressInfoCount : 16;
                        uint32_t vc=counts.vendorInfoCount<8 ? counts.vendorInfoCount : 8;
                        counts.addressInfoCount=ac; counts.vendorInfoCount=vc; counts.vendorBinarySize=0;
                        VkDeviceFaultInfoEXT info={.sType=VK_STRUCTURE_TYPE_DEVICE_FAULT_INFO_EXT,
                            .pAddressInfos=addresses,.pVendorInfos=vendors,.pVendorBinaryData=NULL};
                        if (aura_vk_query_live(f,stop,activity,epoch)) {
                            report.detail_result=query(link.handle,&counts,&info);
                            report.state=AVF_PARTIAL;
                            if (report.detail_result==VK_SUCCESS || report.detail_result==VK_INCOMPLETE) {
                                report.address_retained=counts.addressInfoCount<ac ? counts.addressInfoCount : ac;
                                report.vendor_retained=counts.vendorInfoCount<vc ? counts.vendorInfoCount : vc;
                                report.category=aura_vk_category(info.description);
                                for (uint32_t i=0;i<report.address_retained;++i) {
                                    unsigned type=(unsigned)addresses[i].addressType;
                                    report.address_types[type<7 ? type : 7]++;
                                }
                                for (uint32_t i=0;i<report.vendor_retained;++i) report.category|=aura_vk_category(vendors[i].description);
                                if (report.detail_result==VK_SUCCESS && report.address_total<=16 && report.vendor_total<=8)
                                    report.state=AVF_COMPLETE;
                            }
                        }
                        if (report.address_total>16 || report.vendor_total>8) report.flags|=2;
                        if (report.vendor_binary_bytes) report.flags|=4; /* binary deliberately not requested */
                    }
                }
            }
        }
    }
    if (!aura_vk_query_live(f,stop,activity,epoch)) return; /* unfinished/cancelled remains explicit */
    memcpy((char *)&f->driver+8,(char *)&report+8,sizeof(report)-8);
    atomic_store_explicit(&f->driver.seq,2,memory_order_release);
}
static void aura_vk_sum(uint32_t *value, uint32_t add, struct aura_vk_data *d) {
    if (UINT32_MAX-*value<add) { *value=UINT32_MAX;d->flags|=1; } else *value+=add;
}
static uint32_t aura_vk_timeout(uint64_t value) { return !value ? 1 : value==UINT64_MAX ? 3 : 2; }
static void aura_vk_submit_info(struct aura_vk_data *d, uint32_t n, const void *info, int modern) {
    if (!d->call) return;
    d->objects=n;
    if ((n && !info) || n>16) d->flags|=1;
    if (!info) return;
    for (uint32_t i=0;i<n && i<16;++i) {
        if (modern) {
            const VkSubmitInfo2 *s=(const VkSubmitInfo2 *)info+i;
            aura_vk_sum(&d->commands,s->commandBufferInfoCount,d);
            aura_vk_sum(&d->waits,s->waitSemaphoreInfoCount,d);
            aura_vk_sum(&d->signals,s->signalSemaphoreInfoCount,d);
            if (s->pNext) d->flags|=1;
        } else {
            const VkSubmitInfo *s=(const VkSubmitInfo *)info+i;
            aura_vk_sum(&d->commands,s->commandBufferCount,d);
            aura_vk_sum(&d->waits,s->waitSemaphoreCount,d);
            aura_vk_sum(&d->signals,s->signalSemaphoreCount,d);
            if (s->pNext) d->flags|=1;
        }
    }
}
#endif
