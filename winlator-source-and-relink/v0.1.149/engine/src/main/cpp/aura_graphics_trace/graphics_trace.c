/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT
 * Shared Mesa copy optimization and optional saved-debug host call observation.
 * Only verified, non-executable GOT slots are redirected. Display copies finish
 * before returning; driver instructions, GPU waits and submission order stay intact.
 */
#define _GNU_SOURCE
#include "graphics_stats.h"
#include "frame_copy.h"
#include "frame_geometry.h"
#include "../aura_loader/adreno_compiler_loader.h"
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <link.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include "vulkan_fault_observer.h"
#include "graphics_cadence.h"

static void *(*next_dlopen)(const char *, int);
static aura_system_dlopen_fn compiler_loader;
static void *(*real_dlsym)(void *, const char *);
static _Atomic(struct graphics_stats *) stats;
static _Atomic(struct aura_cadence *) cadence_stats;
static _Atomic uint64_t coverage, conflicts;
static char directory[PATH_MAX];
static int requested;
static int copy_requested;
static int regions_requested;
static int geometry_requested;
static _Atomic(aura_next_request_fn) geometry_next;
static _Atomic(aura_events_queued_fn) geometry_events;
static _Thread_local struct aura_frame_geometry frame_geometry;
static _Atomic int copy_delegate_safe;
static pthread_once_t storage_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t install_lock = PTHREAD_MUTEX_INITIALIZER;
static _Thread_local unsigned depth[GRAPHICS_METRICS];
static _Thread_local uint32_t thread_id;
static _Thread_local int scanning;
static _Atomic uintptr_t mesa_base;

enum function_id {
    F_GL_PROC, F_GL_SWAP, F_GL_SWAP_MSC, F_GL_FINISH, F_GL_READ, F_GL_MAP, F_GL_MAP_RANGE,
    F_VK_GIPA, F_VK_GDPA, F_VK_SUBMIT, F_VK_SUBMIT2, F_VK_SUBMIT2_KHR, F_VK_PRESENT,
    F_VK_FENCES, F_VK_SEMAPHORES, F_VK_SEMAPHORES_KHR, F_VK_QUEUE_IDLE, F_VK_DEVICE_IDLE, F_VK_MAP,
    F_GEOMETRY, F_SHM, F_SYNC, F_MEMCPY, F_SYSCALL,
    F_VK_CREATE, F_VK_DESTROY, F_VK_GET_QUEUE, F_VK_GET_QUEUE2, F_VK_ACQUIRE, F_VK_ACQUIRE2,
    F_C_BeginCommandBuffer, F_C_EndCommandBuffer, F_C_ResetCommandBuffer, F_C_ResetCommandPool, F_C_DestroyCommandPool,
    F_C_FreeCommandBuffers, F_C_Draw, F_C_DrawIndexed, F_C_DrawIndirect, F_C_DrawIndexedIndirect,
    F_C_DrawIndirectCount, F_C_DrawIndexedIndirectCount, F_C_Dispatch, F_C_DispatchIndirect, F_C_CopyBuffer2,
    F_C_CopyImage2, F_C_CopyBufferToImage2, F_C_CopyImageToBuffer2, F_C_BlitImage2, F_C_ResolveImage2,
    F_C_PipelineBarrier2, F_C_SetEvent2, F_C_ClearColorImage, F_C_ClearDepthStencilImage, F_C_ClearAttachments,
    F_C_BeginRendering, F_C_EndRendering, F_C_ExecuteCommands, F_C_BindPipeline, F_C_FillBuffer,
    F_C_UpdateBuffer, F_C_CopyQueryPoolResults, F_C_AllocateCommandBuffers, FUNCTION_COUNT
};
_Static_assert(FUNCTION_COUNT<=64, "coverage bit capacity");
struct import_slot { uintptr_t rva; int function; }; /* -1: dlsym */
struct module_spec { const char *native_name, *logical_name; uint64_t header_hash; const struct import_slot *slots; unsigned count; };
struct copy_range { uintptr_t begin, end; };
#ifndef GRAPHICS_MODULE_HEADER
#define GRAPHICS_MODULE_HEADER "graphics_modules.h"
#endif
#include GRAPHICS_MODULE_HEADER
#ifndef AURA_MESA_SHM_COPY_RETURN
#define AURA_MESA_SHM_COPY_RETURN 0
#endif
static _Atomic(void *) originals[FUNCTION_COUNT];
static void mark_coverage(uint64_t bits) {
    atomic_fetch_or(&coverage, bits);
    struct graphics_stats *s = atomic_load(&stats);
    if (s) atomic_fetch_or(&s->header.coverage, bits);
}
static void conflict(void) {
    atomic_fetch_add(&conflicts, 1);
    struct graphics_stats *s = atomic_load(&stats);
    if (s) atomic_fetch_add(&s->header.conflicts, 1);
}

static uint64_t process_start(void) {
    char buffer[4096];
    int fd = open("/proc/self/stat", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, buffer, sizeof(buffer) - 1); close(fd);
    if (n <= 0) return 0;
    buffer[n] = 0;
    char *p = strrchr(buffer, ')');
    if (!p) return 0;
    p += 2;
    for (unsigned field = 3; field < 22; ++field) {
        p = strchr(p, ' ');
        if (!p) return 0;
        while (*p == ' ') ++p;
    }
    return strtoull(p, NULL, 10);
}

static void open_storage(void) {
    if (!requested) return;
    uint64_t start = process_start();
    if (!start) return;
    int dir = open(directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (dir < 0) return;
    int fd = -1;
    unsigned slot;
    for (slot = 1; slot <= 16; ++slot) {
        char name[32]; snprintf(name, sizeof(name), "graphics-%02u.bin", slot);
        fd = openat(dir, name, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (fd >= 0 || errno != EEXIST) break;
    }
    if (fd < 0) { close(dir); return; }
    if (!ftruncate(fd, sizeof(struct graphics_stats))) {
        void *memory = mmap(NULL, sizeof(struct graphics_stats), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (memory != MAP_FAILED) {
            struct graphics_stats *s = memory;
            s->header.version = 1; s->header.rows = GRAPHICS_ROWS;
            s->header.pid = getpid(); s->header.start_ticks = start;
            s->header.started_ns = graphics_clock(CLOCK_MONOTONIC);
            aura_vk_init(&s->header.vulkan_fault,s->header.started_ns);
            s->header.commands.version=4;s->header.commands.capacity=AURA_CMD_EVENTS;
            atomic_store(&s->header.coverage, atomic_load(&coverage));
            atomic_store(&s->header.conflicts, atomic_load(&conflicts));
            s->header.copy_version = copy_requested ? 2 : 0;
            s->header.regions_version = regions_requested ? AURA_FRAME_REGIONS_VERSION : 0;
            s->header.geometry_version = geometry_requested ? 1 : 0;
            memcpy(s->header.magic, "AURAGX1", 8);
            char name[32];snprintf(name,sizeof(name),"cadence-%02u.bin",slot);
            int extra=openat(dir,name,O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
            if(extra>=0) {
                if(!ftruncate(extra,sizeof(struct aura_cadence))) {
                    void *mapped=mmap(NULL,sizeof(struct aura_cadence),PROT_READ|PROT_WRITE,MAP_SHARED,extra,0);
                    if(mapped!=MAP_FAILED) {
                        struct aura_cadence *c=mapped;
                        aura_cadence_init(c,s->header.pid,start,s->header.started_ns);
                        atomic_store_explicit(&cadence_stats,c,memory_order_release);
                    }
                }
                close(extra);
            }
            atomic_store_explicit(&stats, s, memory_order_release);
        }
    }
    close(fd);
    close(dir);
}

struct measurement { struct graphics_stats *s; uint64_t wall, cpu, epoch; unsigned metric; int outer;
    struct aura_cadence_ticket cadence; };
static struct measurement begin(unsigned metric) {
    int saved = errno;
    struct measurement m = {.metric = metric, .outer = depth[metric]++ == 0};
    if (m.outer) {
        pthread_once(&storage_once, open_storage);
        m.s = atomic_load_explicit(&stats, memory_order_acquire);
        if (m.s && m.s->header.pid == (uint32_t)getpid() && !atomic_load(&m.s->header.stop)) {
            m.epoch = atomic_load(&m.s->header.activity);
            if (!(m.epoch & 1)) {
                if (!thread_id) thread_id = (uint32_t)syscall(SYS_gettid);
                m.wall = graphics_clock(CLOCK_MONOTONIC);
                m.cpu = graphics_clock(CLOCK_THREAD_CPUTIME_ID);
            }
        }
    }
    errno = saved;
    return m;
}
static void end_as(struct measurement m, unsigned metric, uint64_t bytes, int error, uint64_t copy_calls) {
    int saved = errno;
    if (m.wall) {
        uint64_t cpu = graphics_clock(CLOCK_THREAD_CPUTIME_ID), wall = graphics_clock(CLOCK_MONOTONIC);
        if (atomic_load(&m.s->header.activity) == m.epoch)
            graphics_record_copies(m.s, thread_id, metric, m.wall, wall, m.cpu, cpu, bytes, error, copy_calls);
        else atomic_fetch_add(&m.s->header.dropped, 1);
    }
    --depth[m.metric]; errno = saved;
}
static void end(struct measurement m, uint64_t bytes, int error) {
    end_as(m, m.metric, bytes, error, 0);
}

/* Row-pitched images can call memcpy once per scanline. Keep exact bytes/calls
 * in TLS, with one clock pair and one shared-row publication per copy stage.
 * This measures first copy -> XShmPutImage ENTRY, including row-loop/unmap work,
 * not just memcpy and not GPU execution. No work or synchronization is skipped.
 * A stage without SHM submission has its own metric, never a completed frame. */
static _Thread_local struct {
    struct measurement measurement;
    uint64_t bytes, calls;
    int active, started;
} copy_stage;
static void finish_copy_stage(unsigned metric) {
    if (!copy_stage.started) return;
    end_as(copy_stage.measurement, metric, copy_stage.bytes, 0, copy_stage.calls);
    copy_stage.started = 0;
    copy_stage.bytes = copy_stage.calls = 0;
}

#define ORIGINAL(id, type) ((type)atomic_load_explicit(&originals[id], memory_order_acquire))
typedef void (*gl_swap_fn)(void *, unsigned long);
static void gl_swap(void *display, unsigned long drawable) {
    if (geometry_requested) aura_geometry_enter(&frame_geometry);
    if (!requested) {
        ORIGINAL(F_GL_SWAP, gl_swap_fn)(display, drawable);
        if (geometry_requested) aura_geometry_leave(&frame_geometry);
        return;
    }
    struct measurement m = begin(GL_SWAP);
    if (m.outer) copy_stage.active = m.wall != 0;
    ORIGINAL(F_GL_SWAP, gl_swap_fn)(display, drawable);
    if (m.outer) { finish_copy_stage(MESA_COPY_TO_SWAP_END); copy_stage.active = 0; }
    end(m, 0, 0);
    if (geometry_requested) aura_geometry_leave(&frame_geometry);
}
typedef int64_t (*gl_msc_fn)(void *, unsigned long, int64_t, int64_t, int64_t);
static int64_t gl_swap_msc(void *d, unsigned long w, int64_t a, int64_t b, int64_t c) {
    if (geometry_requested) aura_geometry_enter(&frame_geometry);
    if (!requested) {
        int64_t r = ORIGINAL(F_GL_SWAP_MSC, gl_msc_fn)(d,w,a,b,c);
        if (geometry_requested) aura_geometry_leave(&frame_geometry);
        return r;
    }
    struct measurement m = begin(GL_SWAP);
    if (m.outer) copy_stage.active = m.wall != 0;
    int64_t r = ORIGINAL(F_GL_SWAP_MSC, gl_msc_fn)(d,w,a,b,c);
    if (m.outer) { finish_copy_stage(MESA_COPY_TO_SWAP_END); copy_stage.active = 0; }
    end(m,0,0);
    if (geometry_requested) aura_geometry_leave(&frame_geometry);
    return r;
}
typedef void (*gl_void_fn)(void);
static void gl_finish(void) { struct measurement m = begin(GL_FINISH); ORIGINAL(F_GL_FINISH,gl_void_fn)(); end(m,0,0); }
typedef void (*gl_read_fn)(int,int,int,int,unsigned,unsigned,void *);
static void gl_read(int x,int y,int w,int h,unsigned format,unsigned type,void *pixels) {
    struct measurement m = begin(GL_READ_PIXELS);
    ORIGINAL(F_GL_READ,gl_read_fn)(x,y,w,h,format,type,pixels); end(m,0,0);
}
typedef void *(*gl_map_fn)(unsigned,unsigned);
static void *gl_map(unsigned target,unsigned access) {
    struct measurement m = begin(GL_MAP_BUFFER); void *r = ORIGINAL(F_GL_MAP,gl_map_fn)(target,access);
    end(m,0,0); return r;
}
typedef void *(*gl_map_range_fn)(unsigned,intptr_t,intptr_t,unsigned);
static void *gl_map_range(unsigned target,intptr_t offset,intptr_t length,unsigned access) {
    struct measurement m = begin(GL_MAP_BUFFER);
    void *r = ORIGINAL(F_GL_MAP_RANGE,gl_map_range_fn)(target,offset,length,access); end(m,0,0); return r;
}
typedef int (*geometry_fn)(void *,unsigned long,unsigned long *,int *,int *,unsigned *,unsigned *,unsigned *,unsigned *);
static int geometry(void *d,unsigned long w,unsigned long *root,int *x,int *y,unsigned *width,unsigned *height,unsigned *border,unsigned *bits) {
    struct measurement m = {0};
    if (requested) m = begin(X_GEOMETRY);
    int hit = 0;
    int r = aura_geometry_query(&frame_geometry, ORIGINAL(F_GEOMETRY,geometry_fn),
        geometry_requested ? atomic_load(&geometry_next) : NULL, atomic_load(&geometry_events),
        d,w,root,x,y,width,height,border,bits,&hit);
    if (requested) {
        struct graphics_stats *s = atomic_load(&stats);
        if (s && !atomic_load(&s->header.stop)) {
            atomic_fetch_add(hit ? &s->header.geometry_hits : &s->header.geometry_queries, 1);
        }
        end(m,0,!r);
    }
    return r;
}
typedef int (*shm_fn)(void *,unsigned long,void *,void *,int,int,int,int,unsigned,unsigned,int);
static int shm_put(void *d,unsigned long w,void *gc,void *image,int sx,int sy,int dx,int dy,unsigned width,unsigned height,int event) {
    finish_copy_stage(MESA_COPY_TO_SHM);
    struct measurement m = begin(X_SHM_SUBMIT);
    int r = ORIGINAL(F_SHM,shm_fn)(d,w,gc,image,sx,sy,dx,dy,width,height,event); end(m,0,!r); return r;
}
typedef int (*sync_fn)(void *,int);
static int x_sync(void *d,int discard) {
    struct measurement m = begin(X_SYNC); int r = ORIGINAL(F_SYNC,sync_fn)(d,discard); end(m,0,0); return r;
}
typedef void *(*copy_fn)(void *,const void *,size_t);
static void *execute_rect_copy(void *dst,const void *src,size_t bytes,int regions) {
    copy_fn original = ORIGINAL(F_MEMCPY,copy_fn);
    if (!copy_requested || !atomic_load(&copy_delegate_safe) || bytes < AURA_FRAME_COPY_MIN_BYTES)
        return original(dst,src,bytes);
    struct graphics_stats *s = requested ? atomic_load(&stats) : NULL;
    uint64_t epoch = s ? atomic_load(&s->header.activity) : 0;
    struct aura_frame_copy_work work;
    unsigned helpers = regions ? aura_frame_copy_regions(dst,src,bytes,original,s ? &work : NULL) :
        aura_frame_copy_measured(dst,src,bytes,original,s ? &work : NULL);
    if (s && !(epoch & 1) && epoch == atomic_load(&s->header.activity) && !atomic_load(&s->header.stop)) {
        if (helpers) {
            atomic_store(&s->header.copy_workers,helpers);
            atomic_fetch_add(&s->header.parallel_copy_calls,1);
            atomic_fetch_add(&s->header.parallel_copy_bytes,bytes);
            atomic_fetch_add(&s->header.copy_chunks,work.chunks);
            atomic_fetch_add(&s->header.copy_caller_bytes,work.caller_bytes);
            atomic_fetch_add(&s->header.copy_helper_bytes,work.helper_bytes);
            if (regions) {
                atomic_fetch_add(&s->header.regions_calls,1);
                atomic_fetch_add(&s->header.regions_input_bytes,bytes);
                atomic_fetch_add(&s->header.regions_compared_bytes,work.compared_bytes);
                atomic_fetch_add(&s->header.regions_written_bytes,work.written_bytes);
            }
        } else atomic_fetch_add(&s->header.serial_large_copies,1);
    }
    return dst;
}
static void *observe_rect_copy(void *dst,const void *src,size_t bytes,int regions) {
    if (depth[GL_SWAP]) {
        if (copy_stage.active) {
            if (!copy_stage.started) {
                copy_stage.measurement = begin(MESA_COPY_TO_SHM);
                copy_stage.started = 1;
            }
            copy_stage.bytes += bytes;
            ++copy_stage.calls;
        }
        return execute_rect_copy(dst,src,bytes,regions);
    }
    /* Copies outside an observed Swap retain the original call-level metric. */
    struct measurement m = begin(MESA_RECT_COPY);
    void *r = execute_rect_copy(dst,src,bytes,regions); end(m,bytes,0); return r;
}
static void *rect_copy(void *dst,const void *src,size_t bytes) {
    uintptr_t caller = (uintptr_t)__builtin_extract_return_addr(__builtin_return_address(0));
    uintptr_t base = atomic_load(&mesa_base), rva = caller - base;
    /* Exact shipped Mesa rectangle callers, including per-row and tail calls.
     * No pixel contents; a rectangle copy is not necessarily a complete frame. */
    int rectangle = 0;
    if (base) for (unsigned i=0;i<sizeof(mesa_copy_ranges)/sizeof(mesa_copy_ranges[0]);++i)
        if (rva >= mesa_copy_ranges[i].begin && rva < mesa_copy_ranges[i].end) { rectangle=1;break; }
    if (!rectangle)
        return ORIGINAL(F_MEMCPY,copy_fn)(dst,src,bytes);
    int regions = 0;
#if AURA_MESA_SHM_COPY_RETURN
    regions = regions_requested && rva == AURA_MESA_SHM_COPY_RETURN;
#endif
    return requested ? observe_rect_copy(dst,src,bytes,regions) : execute_rect_copy(dst,src,bytes,regions);
}
/* AArch64's integer variadic ABI uses the same x0..x6 registers. Extra unused
 * syscall arguments are passed unchanged; no va_arg access to missing args. */
typedef long (*sys_fn)(long,long,long,long,long,long,long);
static long mesa_syscall(long nr,long a,long b,long c,long d,long e,long f) {
    int wait = nr == SYS_futex && ((b & 127) == 0 || (b & 127) == 9);
    if (!wait) return ORIGINAL(F_SYSCALL,sys_fn)(nr,a,b,c,d,e,f);
    struct measurement m = begin(MESA_FUTEX_WAIT);
    long r = ORIGINAL(F_SYSCALL,sys_fn)(nr,a,b,c,d,e,f); end(m,0,r < 0); return r;
}

#include "vulkan_command_observer.h"
#include "vulkan_command_wrappers.h"

typedef int32_t (*submit_fn)(void *,uint32_t,const void *,uint64_t);
static struct aura_vk_data fault_begin(struct measurement *measured,unsigned api,VkDevice device,VkQueue queue,struct aura_vk_link *link) {
    struct measurement m=*measured;
    int saved=errno;
    struct aura_vk_data d={0}; *link=(struct aura_vk_link){.enabled=-1};
    if (m.wall) {
        measured->cadence=aura_cadence_enter(atomic_load(&cadence_stats),m.wall,m.epoch,thread_id,api);
        d=aura_vk_enter(&m.s->header.vulkan_fault,m.wall,m.epoch,thread_id,api);
        if (d.call) {
            *link=aura_vk_link_for(device,queue);d.device=link->device;d.queue=link->queue;
            if (!d.device) d.flags|=2;
        }
    }
    errno=saved;return d;
}
static void fault_end_commands(struct measurement m,struct aura_vk_data d,struct aura_vk_link link,int32_t result,struct aura_cmd_data *commands) {
    int saved=errno;
    uint64_t now=(d.call || m.cadence.call) ? graphics_clock(CLOCK_MONOTONIC) : 0;
    if(m.cadence.call) aura_cadence_return(atomic_load(&cadence_stats),m.cadence,now,m.cpu,
        graphics_clock(CLOCK_THREAD_CPUTIME_ID),result,atomic_load(&m.s->header.activity),atomic_load(&m.s->header.stop));
    end(m,0,result<0); /* Diagnostic driver queries are outside aggregate API timing. */
    int first_lost=d.call && aura_vk_return(&m.s->header.vulkan_fault,&d,now,result,
            atomic_load(&m.s->header.activity),atomic_load(&m.s->header.stop));
    if (commands && d.returned && !atomic_load(&m.s->header.stop) && atomic_load(&m.s->header.activity)==d.activity) {
        commands->end_ns=d.end_ns;
        if(result<0) commands->flags|=AC_NEGATIVE;
        if(result==-4) commands->flags|=AC_LOST;
        aura_cmd_publish(&m.s->header.commands,*commands,first_lost);
    }
    if(first_lost) aura_vk_driver_query(&m.s->header.vulkan_fault,link,&m.s->header.stop,&m.s->header.activity,m.epoch);
    errno=saved;
}
static void fault_end(struct measurement m,struct aura_vk_data d,struct aura_vk_link link,int32_t result) {
    fault_end_commands(m,d,link,result,NULL);
}
#define SUBMIT_WRAPPER(name,id,api,modern) static int32_t name(void *q,uint32_t n,const void *info,uint64_t fence) { \
    struct measurement m=begin(VK_SUBMIT);struct aura_vk_link link; \
    struct aura_vk_data d=fault_begin(&m,api,VK_NULL_HANDLE,(VkQueue)q,&link); \
    aura_vk_submit_info(&d,n,info,modern);if (fence) d.flags|=4; \
    struct aura_cmd_data c=aura_cmd_submit(m,d,link,n,info,modern); \
    int32_t r=ORIGINAL(id,submit_fn)(q,n,info,fence);fault_end_commands(m,d,link,r,&c);return r; }
SUBMIT_WRAPPER(vk_submit,F_VK_SUBMIT,AVK_SUBMIT,0)
SUBMIT_WRAPPER(vk_submit2,F_VK_SUBMIT2,AVK_SUBMIT2,1)
SUBMIT_WRAPPER(vk_submit2_khr,F_VK_SUBMIT2_KHR,AVK_SUBMIT2_KHR,1)
typedef int32_t (*present_fn)(void *,const void *);
static int32_t vk_present(void *queue,const void *info) {
    struct measurement m=begin(VK_PRESENT);struct aura_vk_link link;
    struct aura_vk_data d=fault_begin(&m,AVK_PRESENT,VK_NULL_HANDLE,(VkQueue)queue,&link);
    if (d.call && info) {
        const VkPresentInfoKHR *p=info;d.objects=p->swapchainCount;d.waits=p->waitSemaphoreCount;
        if (p->swapchainCount && p->pImageIndices) d.image=p->pImageIndices[0];
        if (p->pNext || p->swapchainCount>1) d.flags|=1;
    }
    int32_t r=ORIGINAL(F_VK_PRESENT,present_fn)(queue,info);fault_end(m,d,link,r);return r;
}
typedef int32_t (*fences_fn)(void *,uint32_t,const uint64_t *,uint32_t,uint64_t);
static int32_t vk_fences(void *device,uint32_t n,const uint64_t *fences,uint32_t all,uint64_t timeout) {
    struct measurement m=begin(VK_FENCE_WAIT);struct aura_vk_link link;
    struct aura_vk_data d=fault_begin(&m,AVK_FENCES,(VkDevice)device,VK_NULL_HANDLE,&link);
    d.objects=n;d.waits=n;d.timeout=aura_vk_timeout(timeout);if (all) d.flags|=8;
    int32_t r=ORIGINAL(F_VK_FENCES,fences_fn)(device,n,fences,all,timeout);fault_end(m,d,link,r);return r;
}
typedef int32_t (*semaphores_fn)(void *,const void *,uint64_t);
#define SEM_WRAPPER(name,id,api) static int32_t name(void *device,const void *info,uint64_t timeout) { \
    struct measurement m=begin(VK_SEMAPHORE_WAIT);struct aura_vk_link link; \
    struct aura_vk_data d=fault_begin(&m,api,(VkDevice)device,VK_NULL_HANDLE,&link); \
    if (d.call && info) { const VkSemaphoreWaitInfo *p=info;d.objects=d.waits=p->semaphoreCount; \
        d.timeout=aura_vk_timeout(timeout);if (p->pNext) d.flags|=1;if (p->flags&VK_SEMAPHORE_WAIT_ANY_BIT) d.flags|=8; } \
    int32_t r=ORIGINAL(id,semaphores_fn)(device,info,timeout);fault_end(m,d,link,r);return r; }
SEM_WRAPPER(vk_semaphores,F_VK_SEMAPHORES,AVK_SEMAPHORES)
SEM_WRAPPER(vk_semaphores_khr,F_VK_SEMAPHORES_KHR,AVK_SEMAPHORES_KHR)
typedef int32_t (*idle_fn)(void *);
#define IDLE_WRAPPER(name,id,metric,api,isqueue) static int32_t name(void *handle) { \
    struct measurement m=begin(metric);struct aura_vk_link link; \
    struct aura_vk_data d=fault_begin(&m,api,isqueue?VK_NULL_HANDLE:(VkDevice)handle,isqueue?(VkQueue)handle:VK_NULL_HANDLE,&link); \
    int32_t r=ORIGINAL(id,idle_fn)(handle);fault_end(m,d,link,r);return r; }
IDLE_WRAPPER(vk_queue_idle,F_VK_QUEUE_IDLE,VK_QUEUE_IDLE,AVK_QUEUE_IDLE,1)
IDLE_WRAPPER(vk_device_idle,F_VK_DEVICE_IDLE,VK_DEVICE_IDLE,AVK_DEVICE_IDLE,0)
typedef int32_t (*vk_map_fn)(void *,uint64_t,uint64_t,uint64_t,uint32_t,void **);
static int32_t vk_map(void *device,uint64_t memory,uint64_t offset,uint64_t size,uint32_t flags,void **data) {
    struct measurement m=begin(VK_MAP_MEMORY);struct aura_vk_link link;
    struct aura_vk_data d=fault_begin(&m,AVK_MAP,(VkDevice)device,VK_NULL_HANDLE,&link);d.objects=1;
    int32_t r=ORIGINAL(F_VK_MAP,vk_map_fn)(device,memory,offset,size,flags,data);fault_end(m,d,link,r);return r;
}

static VkResult vk_create(VkPhysicalDevice physical,const VkDeviceCreateInfo *info,const VkAllocationCallbacks *allocator,VkDevice *device) {
    struct measurement m=begin(VK_CREATE_DEVICE);struct aura_vk_link link;
    struct aura_vk_data d=fault_begin(&m,AVK_CREATE,VK_NULL_HANDLE,VK_NULL_HANDLE,&link);
    int enabled=m.wall ? aura_vk_fault_enabled(info) : -1;
    if (d.call && info) {d.objects=info->queueCreateInfoCount;if (info->pNext) d.flags|=1;}
    VkResult r=ORIGINAL(F_VK_CREATE,PFN_vkCreateDevice)(physical,info,allocator,device);int saved=errno;
    if (m.wall && r==VK_SUCCESS && device) {
        aura_vk_register_device(*device,enabled);link=aura_vk_link_for(*device,VK_NULL_HANDLE);
        if (d.call) {d.device=link.device;if (d.device) d.flags&=~2u;}
    }
    errno=saved;fault_end(m,d,link,r);return r;
}
static void vk_destroy(VkDevice device,const VkAllocationCallbacks *allocator) {
    int saved=errno;
    struct graphics_stats *s=atomic_load(&stats);
    // A fork child must not enter a registry mutex inherited from another thread.
    if (s && s->header.pid==(uint32_t)getpid()) {aura_cmd_reset_pool(device,VK_NULL_HANDLE,1);aura_vk_forget_device(device);}
    errno=saved;
    ORIGINAL(F_VK_DESTROY,PFN_vkDestroyDevice)(device,allocator);
}
static int registry_active(void) {
    struct graphics_stats *s=atomic_load(&stats);
    return s && s->header.pid==(uint32_t)getpid() && !atomic_load(&s->header.stop);
}
static void vk_get_queue(VkDevice device,uint32_t family,uint32_t index,VkQueue *queue) {
    ORIGINAL(F_VK_GET_QUEUE,PFN_vkGetDeviceQueue)(device,family,index,queue);int saved=errno;
    if (queue && registry_active()) aura_vk_register_queue(device,*queue,family,index);
    errno=saved;
}
static void vk_get_queue2(VkDevice device,const VkDeviceQueueInfo2 *info,VkQueue *queue) {
    ORIGINAL(F_VK_GET_QUEUE2,PFN_vkGetDeviceQueue2)(device,info,queue);int saved=errno;
    if (queue && info && registry_active()) aura_vk_register_queue(device,*queue,info->queueFamilyIndex,info->queueIndex);
    errno=saved;
}
static VkResult vk_acquire(VkDevice device,VkSwapchainKHR swapchain,uint64_t timeout,VkSemaphore sem,VkFence fence,uint32_t *index) {
    struct measurement m=begin(VK_ACQUIRE_IMAGE);struct aura_vk_link link;
    struct aura_vk_data d=fault_begin(&m,AVK_ACQUIRE,device,VK_NULL_HANDLE,&link);
    d.objects=1;d.signals=sem!=VK_NULL_HANDLE;d.timeout=aura_vk_timeout(timeout);if (fence) d.flags|=4;
    VkResult r=ORIGINAL(F_VK_ACQUIRE,PFN_vkAcquireNextImageKHR)(device,swapchain,timeout,sem,fence,index);
    if (d.call && index && (r==VK_SUCCESS || r==VK_SUBOPTIMAL_KHR)) d.image=*index;
    fault_end(m,d,link,r);return r;
}
static VkResult vk_acquire2(VkDevice device,const VkAcquireNextImageInfoKHR *info,uint32_t *index) {
    struct measurement m=begin(VK_ACQUIRE_IMAGE);struct aura_vk_link link;
    struct aura_vk_data d=fault_begin(&m,AVK_ACQUIRE2,device,VK_NULL_HANDLE,&link);
    if (d.call && info) {d.objects=1;d.signals=info->semaphore!=VK_NULL_HANDLE;d.timeout=aura_vk_timeout(info->timeout);if (info->fence) d.flags|=4;if (info->pNext) d.flags|=1;}
    VkResult r=ORIGINAL(F_VK_ACQUIRE2,PFN_vkAcquireNextImage2KHR)(device,info,index);
    if (d.call && index && (r==VK_SUCCESS || r==VK_SUBOPTIMAL_KHR)) d.image=*index;
    fault_end(m,d,link,r);return r;
}

static void *wrap(const char *name,void *real);
typedef void *(*gl_proc_fn)(const unsigned char *);
static void *gl_proc(const unsigned char *name) {
    void *real=ORIGINAL(F_GL_PROC,gl_proc_fn)(name); int saved=errno;
    void *result=wrap((const char *)name,real); errno=saved; return result;
}
typedef void *(*vk_proc_fn)(void *,const char *);
static void *vk_gipa(void *handle,const char *name) {
    void *real=ORIGINAL(F_VK_GIPA,vk_proc_fn)(handle,name); int saved=errno;
    void *result=wrap(name,real); errno=saved; return result;
}
static void *vk_gdpa(void *handle,const char *name) {
    void *real=ORIGINAL(F_VK_GDPA,vk_proc_fn)(handle,name); int saved=errno;
    if (registry_active()) aura_vk_device_resolver((VkDevice)handle,ORIGINAL(F_VK_GDPA,PFN_vkGetDeviceProcAddr));
    void *result=wrap(name,real); errno=saved; return result;
}
struct function { const char *name; unsigned id; void *wrapper; };
static const struct function functions[] = {
    {"vkAllocateCommandBuffers",F_C_AllocateCommandBuffers,cmd_AllocateCommandBuffers},
    {"vkBeginCommandBuffer",F_C_BeginCommandBuffer,cmd_BeginCommandBuffer},
    {"vkEndCommandBuffer",F_C_EndCommandBuffer,cmd_EndCommandBuffer},
    {"vkResetCommandBuffer",F_C_ResetCommandBuffer,cmd_ResetCommandBuffer},
    {"vkResetCommandPool",F_C_ResetCommandPool,cmd_ResetCommandPool},
    {"vkDestroyCommandPool",F_C_DestroyCommandPool,cmd_DestroyCommandPool},
    {"vkFreeCommandBuffers",F_C_FreeCommandBuffers,cmd_FreeCommandBuffers},
    {"vkCmdDraw",F_C_Draw,cmd_Draw},
    {"vkCmdDrawIndexed",F_C_DrawIndexed,cmd_DrawIndexed},
    {"vkCmdDrawIndirect",F_C_DrawIndirect,cmd_DrawIndirect},
    {"vkCmdDrawIndexedIndirect",F_C_DrawIndexedIndirect,cmd_DrawIndexedIndirect},
    {"vkCmdDrawIndirectCount",F_C_DrawIndirectCount,cmd_DrawIndirectCount},
    {"vkCmdDrawIndexedIndirectCount",F_C_DrawIndexedIndirectCount,cmd_DrawIndexedIndirectCount},
    {"vkCmdDispatch",F_C_Dispatch,cmd_Dispatch},
    {"vkCmdDispatchIndirect",F_C_DispatchIndirect,cmd_DispatchIndirect},
    {"vkCmdCopyBuffer2",F_C_CopyBuffer2,cmd_CopyBuffer2},
    {"vkCmdCopyImage2",F_C_CopyImage2,cmd_CopyImage2},
    {"vkCmdCopyBufferToImage2",F_C_CopyBufferToImage2,cmd_CopyBufferToImage2},
    {"vkCmdCopyImageToBuffer2",F_C_CopyImageToBuffer2,cmd_CopyImageToBuffer2},
    {"vkCmdBlitImage2",F_C_BlitImage2,cmd_BlitImage2},
    {"vkCmdResolveImage2",F_C_ResolveImage2,cmd_ResolveImage2},
    {"vkCmdPipelineBarrier2",F_C_PipelineBarrier2,cmd_PipelineBarrier2},
    {"vkCmdSetEvent2",F_C_SetEvent2,cmd_SetEvent2},
    {"vkCmdClearColorImage",F_C_ClearColorImage,cmd_ClearColorImage},
    {"vkCmdClearDepthStencilImage",F_C_ClearDepthStencilImage,cmd_ClearDepthStencilImage},
    {"vkCmdClearAttachments",F_C_ClearAttachments,cmd_ClearAttachments},
    {"vkCmdBeginRendering",F_C_BeginRendering,cmd_BeginRendering},
    {"vkCmdEndRendering",F_C_EndRendering,cmd_EndRendering},
    {"vkCmdExecuteCommands",F_C_ExecuteCommands,cmd_ExecuteCommands},
    {"vkCmdBindPipeline",F_C_BindPipeline,cmd_BindPipeline},
    {"vkCmdFillBuffer",F_C_FillBuffer,cmd_FillBuffer},
    {"vkCmdUpdateBuffer",F_C_UpdateBuffer,cmd_UpdateBuffer},
    {"vkCmdCopyQueryPoolResults",F_C_CopyQueryPoolResults,cmd_CopyQueryPoolResults},

    {"glXGetProcAddressARB",F_GL_PROC,gl_proc},{"glXGetProcAddress",F_GL_PROC,gl_proc},
    {"glXSwapBuffers",F_GL_SWAP,gl_swap},{"glXSwapBuffersMscOML",F_GL_SWAP_MSC,gl_swap_msc},
    {"glFinish",F_GL_FINISH,gl_finish},{"glReadPixels",F_GL_READ,gl_read},
    {"glMapBuffer",F_GL_MAP,gl_map},{"glMapBufferRange",F_GL_MAP_RANGE,gl_map_range},
    {"vkGetInstanceProcAddr",F_VK_GIPA,vk_gipa},{"vkGetDeviceProcAddr",F_VK_GDPA,vk_gdpa},
    {"vkQueueSubmit",F_VK_SUBMIT,vk_submit},{"vkQueueSubmit2",F_VK_SUBMIT2,vk_submit2},
    {"vkQueueSubmit2KHR",F_VK_SUBMIT2_KHR,vk_submit2_khr},{"vkQueuePresentKHR",F_VK_PRESENT,vk_present},
    {"vkWaitForFences",F_VK_FENCES,vk_fences},{"vkWaitSemaphores",F_VK_SEMAPHORES,vk_semaphores},
    {"vkWaitSemaphoresKHR",F_VK_SEMAPHORES_KHR,vk_semaphores_khr},
    {"vkQueueWaitIdle",F_VK_QUEUE_IDLE,vk_queue_idle},{"vkDeviceWaitIdle",F_VK_DEVICE_IDLE,vk_device_idle},
    {"vkMapMemory",F_VK_MAP,vk_map},
    {"vkCreateDevice",F_VK_CREATE,vk_create},{"vkDestroyDevice",F_VK_DESTROY,vk_destroy},
    {"vkGetDeviceQueue",F_VK_GET_QUEUE,vk_get_queue},{"vkGetDeviceQueue2",F_VK_GET_QUEUE2,vk_get_queue2},
    {"vkAcquireNextImageKHR",F_VK_ACQUIRE,vk_acquire},{"vkAcquireNextImage2KHR",F_VK_ACQUIRE2,vk_acquire2},
    {"XGetGeometry",F_GEOMETRY,geometry},{"XShmPutImage",F_SHM,shm_put},{"XSync",F_SYNC,x_sync},
    {"memcpy",F_MEMCPY,rect_copy},{"syscall",F_SYSCALL,mesa_syscall}
};
static void *bind_function(const struct function *f,void *real) {
    if (!real || real == f->wrapper) return real;
    void *expected=NULL;
    if (!atomic_compare_exchange_strong(&originals[f->id],&expected,real) && expected != real) {
        /* A second ICD/device-specific implementation must keep its own real
         * function. Never dispatch it via a pointer belonging to another device. */
        conflict(); if(f->id>=F_C_BeginCommandBuffer) {atomic_store(&aura_cmd_conflicted,1);aura_cmd_forget();} return real;
    }
    mark_coverage(UINT64_C(1) << f->id);
    return f->wrapper;
}
static void *wrap(const char *name,void *real) {
    if ((!requested && !geometry_requested) || !name || !real) return real;
    /* The public GL/Vulkan resolvers cannot expose private Xlib/memcpy hooks. */
    for (unsigned i=0;i<sizeof(functions)/sizeof(functions[0]);++i)
        if ((functions[i].id < F_GEOMETRY || functions[i].id >= F_VK_CREATE) && !strcmp(name,functions[i].name)) {
            if (!requested) {
                if (functions[i].id != F_GL_PROC && functions[i].id != F_GL_SWAP && functions[i].id != F_GL_SWAP_MSC) return real;
                Dl_info provider;
                if (!dladdr(real,&provider) || (uintptr_t)provider.dli_fbase != atomic_load(&mesa_base)) return real;
            }
            return bind_function(&functions[i],real);
        }
    return real;
}
static void *graphics_dlsym(void *handle,const char *name) {
    void *real=real_dlsym(handle,name); int saved=errno;
    void *result=wrap(name,real); errno=saved; return result;
}

static uint64_t hash_header(const void *memory) {
    const unsigned char *p=memory; uint64_t result=UINT64_C(14695981039346656037);
    for (unsigned i=0;i<256;++i) result=(result^p[i])*UINT64_C(1099511628211);
    return result;
}
static int writable_relro(const struct dl_phdr_info *info,uintptr_t slot,uintptr_t page) {
    uintptr_t low=slot&~(page-1),high=low+page;
    int load=0,relro=0;
    for (unsigned i=0;i<info->dlpi_phnum;++i) {
        const ElfW(Phdr) *p=&info->dlpi_phdr[i];
        uintptr_t start=info->dlpi_addr+p->p_vaddr,end=start+p->p_memsz;
        if (end<start) return 0;
        if (p->p_type==PT_GNU_RELRO && slot>=start && slot+sizeof(void *)<=end) relro=1;
        if (p->p_type==PT_LOAD) {
            uintptr_t a=start&~(page-1),b=(end+page-1)&~(page-1);
            if (low<b && high>a && (p->p_flags&PF_X)) return 0;
            if (low>=a && high<=b && p->p_flags==(PF_R|PF_W)) load=1;
        }
    }
    return load && relro;
}
static int scan_module(struct dl_phdr_info *info,size_t size,void *opaque) {
    (void)size;(void)opaque;
    const char *name=strrchr(info->dlpi_name,'/'); name=name?name+1:info->dlpi_name;
    if (!name || !info->dlpi_addr) return 0;
    const struct module_spec *module=NULL;
    for (unsigned i=0;i<sizeof(modules)/sizeof(modules[0]);++i)
        if (!strcmp(name,modules[i].native_name) || !strcmp(name,modules[i].logical_name) ||
            (i==0 && (!strcmp(name,"libGL.so.1") || !strcmp(name,"libGL.so")))) { module=&modules[i];break; }
    if (!module) return 0;
#ifndef AURA_GRAPHICS_TEST
    /* Logical aliases must resolve to the packaged, hash-named library. Never
     * apply pinned relocations to a custom library sharing just the ELF header. */
    if (strcmp(name,module->native_name)) {
        char resolved[PATH_MAX];
        if (!realpath(info->dlpi_name,resolved)) { conflict();return 0; }
        const char *base=strrchr(resolved,'/');base=base?base+1:resolved;
        if (strcmp(base,module->native_name)) { conflict();return 0; }
    }
#endif
    if (hash_header((void *)info->dlpi_addr)!=module->header_hash) { conflict();return 0; }
    long page=sysconf(_SC_PAGESIZE);
    if (page!=4096 && page!=16384) { conflict();return 0; }
    if (module==modules) atomic_store(&mesa_base,info->dlpi_addr);
    void *module_scope=NULL;
    for (unsigned i=0;i<module->count;++i) {
        const struct import_slot *spec=&module->slots[i];
        /* Debug off: bounded copy and frame-local geometry only. No Vulkan
         * observers, clocks, files, GPU calls or changes to presentation order. */
        if (!requested && !(copy_requested && module==modules && spec->function==F_MEMCPY) &&
            !(geometry_requested && (spec->function == -1 || (module==modules && spec->function==F_GEOMETRY)))) continue;
        uintptr_t address=info->dlpi_addr+spec->rva;
        if (address<info->dlpi_addr || !writable_relro(info,address,page)) { conflict();continue; }
        void **slot=(void **)address;
        void *old=__atomic_load_n(slot,__ATOMIC_ACQUIRE),*replacement=graphics_dlsym;
        if (spec->function>=0) {
            const struct function *f=NULL;
            for (unsigned j=0;j<sizeof(functions)/sizeof(functions[0]);++j)
                if ((int)functions[j].id==spec->function) { f=&functions[j];break; }
            if (!f) continue;
            if (old==f->wrapper) continue;
            void *expected=real_dlsym(RTLD_DEFAULT,f->name);
            if ((!expected || old!=expected) && next_dlopen) {
                /* Wine loads Mesa and its Xlib dependencies RTLD_LOCAL. Resolve
                 * within that already-loaded module's dependency scope; never
                 * load a replacement library or trust an arbitrary slot value. */
                if (!module_scope) module_scope=next_dlopen(info->dlpi_name,RTLD_NOW|RTLD_LOCAL|RTLD_NOLOAD);
                if (module_scope) expected=real_dlsym(module_scope,f->name);
            }
            if (!expected || old!=expected) { conflict();continue; }
            if (spec->function==F_GEOMETRY && geometry_requested) {
                Dl_info provider, next_info, events_info;
                if (dladdr(old,&provider) && provider.dli_fname) {
                    void *xlib=next_dlopen(provider.dli_fname,RTLD_NOW|RTLD_LOCAL|RTLD_NOLOAD);
                    if (xlib) {
                        void *next=real_dlsym(xlib,"XNextRequest"), *events=real_dlsym(xlib,"XEventsQueued");
                        if (next && events && dladdr(next,&next_info) && dladdr(events,&events_info) &&
                            next_info.dli_fbase==provider.dli_fbase && events_info.dli_fbase==provider.dli_fbase) {
                            atomic_store(&geometry_events,(aura_events_queued_fn)events);
                            atomic_store(&geometry_next,(aura_next_request_fn)next);
                        }
                        dlclose(xlib);
                    }
                }
            }
            if (spec->function==F_MEMCPY) {
                Dl_info provider;
                if (dladdr(old,&provider) && provider.dli_fname) {
                    const char *base=strrchr(provider.dli_fname,'/');base=base?base+1:provider.dli_fname;
                    atomic_store(&copy_delegate_safe,!strcmp(base,"libc.so")
#ifdef AURA_GRAPHICS_TEST
                        || !strcmp(base,"libc.so.6")
#endif
                    );
                }
            }
            replacement=bind_function(f,old);
        } else if (old==graphics_dlsym) continue;
        else if (old!=(void *)real_dlsym) { conflict();continue; }
        if (replacement==old) continue;
        uintptr_t start=address&~((uintptr_t)page-1);
        if (mprotect((void *)start,page,PROT_READ|PROT_WRITE)) { conflict();continue; }
        __atomic_store_n(slot,replacement,__ATOMIC_RELEASE);
        if (mprotect((void *)start,page,PROT_READ)) {
            __atomic_store_n(slot,old,__ATOMIC_RELEASE);
            (void)mprotect((void *)start,page,PROT_READ); conflict();
        } else mark_coverage(UINT64_C(1) << (32+(unsigned)(module-modules)));
    }
    if (module_scope) dlclose(module_scope);
    return 0;
}
static void scan(void) {
    if ((!requested && !copy_requested && !geometry_requested) || scanning) return;
    if (pthread_mutex_trylock(&install_lock)) { conflict();return; }
    scanning=1; dl_iterate_phdr(scan_module,NULL); scanning=0;
    pthread_mutex_unlock(&install_lock);
}
__attribute__((constructor)) static void configure(void) {
    real_dlsym=dlsym(RTLD_NEXT,"dlsym"); next_dlopen=dlsym(RTLD_NEXT,"dlopen");
    compiler_loader=aura_adreno_loader_resolve();
    const char *enable=getenv("AURA_GRAPHICS_TRACE"),*path=getenv("AURA_GRAPHICS_DIRECTORY");
    const char *copy=getenv("AURA_MESA_FRAME_COPY");
    const char *geometry=getenv("AURA_MESA_FRAME_GEOMETRY");
    if (real_dlsym && next_dlopen && copy && !strcmp(copy,"1")) copy_requested=aura_frame_copy_init();
    const char *regions=getenv("AURA_MESA_COPY_REGIONS");
    regions_requested=copy_requested && (!regions || strcmp(regions,"0"));
    geometry_requested = real_dlsym && next_dlopen && geometry && !strcmp(geometry,"1");
    if (real_dlsym && next_dlopen && enable && !strcmp(enable,"1") && path && path[0]=='/' && strlen(path)<sizeof(directory)) {
        strcpy(directory,path);requested=1;
    }
}
#ifndef AURA_GRAPHICS_TEST
void *dlopen(const char *name,int flags) {
    const void *caller=__builtin_extract_return_addr(__builtin_return_address(0));
    void *result=aura_adreno_dlopen(compiler_loader,next_dlopen,name,flags,caller);int saved=errno;
    if (result && (requested || copy_requested || geometry_requested)) {
        const char *base=name?strrchr(name,'/'):NULL;base=base?base+1:name;
        if (base) for (unsigned i=0;i<sizeof(modules)/sizeof(modules[0]);++i)
            if (!strcmp(base,modules[i].native_name) || !strcmp(base,modules[i].logical_name) ||
                (i==0 && (!strcmp(base,"libGL.so.1") || !strcmp(base,"libGL.so")))) { scan();break; }
    }
    errno=saved;return result;
}
#endif
__attribute__((destructor)) static void finish_workers(void) { aura_frame_copy_shutdown(); }
