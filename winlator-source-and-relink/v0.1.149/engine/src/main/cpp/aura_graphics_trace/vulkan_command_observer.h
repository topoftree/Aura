/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT. Included after the graphics measurement helpers.
 * A partial command classification, NOT a full dependency or resource history. */
#ifndef AURA_VULKAN_COMMAND_OBSERVER_H
#define AURA_VULKAN_COMMAND_OBSERVER_H
#define AC_SHARDS 8
#define AC_SLOTS 16
#define AC_OWNERS 64
#define AC_CHILDREN 64
struct aura_cmd_owner {
    VkCommandBuffer handle;
    VkDevice device;
    VkCommandPool pool;
    uint64_t serial, recording;
    uint32_t loss, level;
};
struct aura_cmd_child {
    VkCommandBuffer handle;
    VkDevice device;
    uint64_t recording, owner_serial, shard_serial;
    struct aura_cmd_data data;
};
static _Atomic uint64_t aura_cmd_recording=1;
struct aura_cmd_slot {
    VkCommandBuffer handle; /* All handles remain private. */
    VkDevice device;
    VkCommandPool pool;
    uint64_t serial, shard_serial, activity, mapping_serial;
    int ended;
    uint32_t loss;
    struct aura_cmd_data data;
    unsigned child_count;
    struct aura_cmd_child children[AC_CHILDREN];
};
struct aura_cmd_shard {
    pthread_mutex_t lock;
    _Atomic uint64_t serial;
    _Atomic uint64_t owner_serial;
    unsigned next, owner_next;
    struct aura_cmd_slot slots[AC_SLOTS];
    struct aura_cmd_owner owners[AC_OWNERS];
};
#define AC_SHARD_INIT {.lock=PTHREAD_MUTEX_INITIALIZER,.serial=1,.owner_serial=1}
static struct aura_cmd_shard aura_cmd_shards[AC_SHARDS]={
    AC_SHARD_INIT,AC_SHARD_INIT,AC_SHARD_INIT,AC_SHARD_INIT,
    AC_SHARD_INIT,AC_SHARD_INIT,AC_SHARD_INIT,AC_SHARD_INIT};
static _Atomic uint64_t aura_cmd_serial=1;
static _Atomic int aura_cmd_active, aura_cmd_conflicted;
static void aura_cmd_invalidate(void) { atomic_fetch_add(&aura_cmd_serial,1); }
static struct graphics_stats *aura_cmd_store(void) {
    struct graphics_stats *s=atomic_load(&stats);
    return s && s->header.pid==(uint32_t)getpid() ? s : NULL; /* no inherited mutex after fork */
}
static struct graphics_stats *aura_cmd_live(void) {
    struct graphics_stats *s=aura_cmd_store();
    if (!s) return NULL;
    if (atomic_load(&aura_cmd_conflicted) || atomic_load(&s->header.stop) || (atomic_load(&s->header.activity)&1) ||
        atomic_load(&s->header.commands.observed)>=AURA_CMD_LIMIT ||
        !aura_vk_allowed(&s->header.vulkan_fault,graphics_clock(CLOCK_MONOTONIC))) {
        if (atomic_exchange(&aura_cmd_active,0)) {
            aura_cmd_invalidate();
            /* Lazy cleanup: no pool scans or locks after the capture window. Membership
             * may change while inactive, so a later Begin cannot reuse old ancestry. */
            for (unsigned i=0;i<AC_SHARDS;++i) {
                atomic_fetch_add(&aura_cmd_shards[i].serial,1);
                atomic_fetch_add(&aura_cmd_shards[i].owner_serial,1);
            }
        }
        return NULL;
    }
    atomic_store(&aura_cmd_active,1);
    return s;
}
/* Reasons count observations (including lifecycle invalidations), not unique dropped operations. */
static void aura_cmd_reason(struct graphics_stats *s,unsigned reason) {
    if (s && !atomic_load(&s->header.stop) && !(atomic_load(&s->header.activity)&1) &&
        !atomic_load(&s->header.vulkan_fault.frozen)) atomic_fetch_add(&s->header.commands.reasons[reason],1);
}
static struct aura_cmd_shard *aura_cmd_shard(VkCommandBuffer handle) {
    uintptr_t h=(uintptr_t)handle;
    return &aura_cmd_shards[((h>>4)^(h>>12)^(h>>20))%AC_SHARDS];
}
static int aura_cmd_acquire(struct graphics_stats *s,struct aura_cmd_shard *shard) {
    if (!pthread_mutex_trylock(&shard->lock)) return 1;
    /* A missed observer operation can only poison this shard, never unrelated shards. */
    atomic_fetch_add(&shard->serial,1);
    if (s) {atomic_fetch_add(&s->header.commands.dropped,1);aura_cmd_reason(s,ACR_CONTENTION);}
    return 0;
}
/* A missed draw/submit affects recording completeness, not allocation ancestry.
 * Only missed allocate/free/destroy operations can invalidate private ownership. */
static int aura_cmd_lifecycle_acquire(struct graphics_stats *s,struct aura_cmd_shard *shard,int membership) {
    if (aura_cmd_acquire(s,shard)) return 1;
    if (membership) atomic_fetch_add(&shard->owner_serial,1);
    return 0;
}
static struct aura_cmd_owner *aura_cmd_owner_find(struct aura_cmd_shard *shard,VkCommandBuffer handle) {
    for (unsigned i=0;i<AC_OWNERS;++i) if (handle && shard->owners[i].handle==handle) return &shard->owners[i];
    return NULL;
}
static struct aura_cmd_owner *aura_cmd_owner_insert(struct graphics_stats *s,struct aura_cmd_shard *shard,VkCommandBuffer handle) {
    struct aura_cmd_owner *owner=aura_cmd_owner_find(shard,handle);
    if (owner) return owner;
    for (unsigned i=0;i<AC_OWNERS;++i) {
        struct aura_cmd_owner *candidate=&shard->owners[i];
        if (!candidate->handle || candidate->serial!=atomic_load(&shard->owner_serial) ||
            (!candidate->device && !candidate->pool)) {owner=candidate;break;}
    }
    if (!owner) {owner=&shard->owners[shard->owner_next++%AC_OWNERS];aura_cmd_reason(s,ACR_CAPACITY);}
    memset(owner,0,sizeof(*owner));owner->handle=handle;
    return owner;
}
static int aura_cmd_owner_current(struct aura_cmd_shard *shard,struct aura_cmd_owner *owner) {
    return owner && owner->serial==atomic_load(&shard->owner_serial);
}
static struct aura_cmd_slot *aura_cmd_find(struct aura_cmd_shard *shard,VkCommandBuffer handle) {
    for (unsigned i=0;i<AC_SLOTS;++i) if (handle && shard->slots[i].handle==handle) return &shard->slots[i];
    return NULL;
}
static struct aura_cmd_slot *aura_cmd_insert(struct graphics_stats *s,struct aura_cmd_shard *shard,VkCommandBuffer handle) {
    struct aura_cmd_slot *slot=aura_cmd_find(shard,handle);
    if (slot) return slot;
    for (unsigned i=0;i<AC_SLOTS;++i) if (!shard->slots[i].handle) {slot=&shard->slots[i];break;}
    if (!slot) {
        slot=&shard->slots[shard->next++%AC_SLOTS];aura_cmd_reason(s,ACR_CAPACITY);
        struct aura_cmd_owner *owner=aura_cmd_owner_find(shard,slot->handle);
        if (aura_cmd_owner_current(shard,owner)) owner->loss|=ACU_CAPACITY;
    }
    memset(slot,0,sizeof(*slot));slot->handle=handle;slot->ended=-1;
    return slot;
}
static void aura_cmd_allocated(VkDevice device,const VkCommandBufferAllocateInfo *info,const VkCommandBuffer *buffers,VkResult result) {
    int saved=errno;
    if (!aura_cmd_store() || result!=VK_SUCCESS || !info || !buffers) {errno=saved;return;}
    struct graphics_stats *s=aura_cmd_live();
    if (!s) {errno=saved;return;}
    for (unsigned i=0;i<info->commandBufferCount && i<64;++i) {
        if (!buffers[i]) continue;
        struct aura_cmd_shard *shard=aura_cmd_shard(buffers[i]);
        if (!aura_cmd_lifecycle_acquire(s,shard,1)) continue;
        uint64_t serial=atomic_load(&shard->owner_serial);
        struct aura_cmd_owner *owner=aura_cmd_owner_insert(s,shard,buffers[i]);
        *owner=(struct aura_cmd_owner){.handle=buffers[i],.device=device,.pool=info->commandPool,.serial=serial,.level=info->level};
        /* Allocation must not evict an unrelated in-flight recording. */
        struct aura_cmd_slot *slot=aura_cmd_find(shard,buffers[i]);
        if (slot) memset(slot,0,sizeof(*slot));
        pthread_mutex_unlock(&shard->lock);
    }
    if (info->commandBufferCount>64) {
        /* Unread handles may reuse an old slot: do not retain stale pool membership. */
        for (unsigned i=0;i<AC_SHARDS;++i) {
            atomic_fetch_add(&aura_cmd_shards[i].serial,1);
            atomic_fetch_add(&aura_cmd_shards[i].owner_serial,1);
        }
        aura_cmd_reason(s,ACR_CAPACITY);
    }
    errno=saved;
}
static void aura_cmd_begin(VkCommandBuffer handle,VkResult result) {
    int saved=errno;struct graphics_stats *s=aura_cmd_live();
    struct aura_cmd_shard *shard=aura_cmd_shard(handle);
    if (s && handle && aura_cmd_acquire(s,shard)) {
        uint64_t serial=atomic_load(&shard->serial);
        struct aura_cmd_slot *slot=aura_cmd_insert(s,shard,handle);
        struct aura_cmd_owner *owner=aura_cmd_owner_find(shard,handle);
        uint64_t ancestry=atomic_load(&shard->owner_serial);
        /* Ownership survives summary eviction and missed non-lifecycle observations.
         * Never recover it from an obsolete generation or a freed handle. */
        if (aura_cmd_owner_current(shard,owner) && owner->device && owner->pool) {
            slot->device=owner->device;slot->pool=owner->pool;slot->mapping_serial=ancestry;owner->loss=0;
            owner->recording=atomic_fetch_add(&aura_cmd_recording,1);
        } else if (slot->mapping_serial!=ancestry) {slot->device=VK_NULL_HANDLE;slot->pool=VK_NULL_HANDLE;}
        slot->loss=0;slot->child_count=0;
        memset(&slot->data,0,sizeof(slot->data));slot->ended=result==VK_SUCCESS?0:-1;
        slot->serial=atomic_load(&aura_cmd_serial);slot->shard_serial=serial;
        slot->activity=atomic_load(&s->header.activity);
        slot->data.src_layout=slot->data.dst_layout=slot->data.bind_point=UINT32_MAX;
        pthread_mutex_unlock(&shard->lock);
    }
    errno=saved;
}
static void aura_cmd_end(VkCommandBuffer handle,VkResult result) {
    int saved=errno;struct graphics_stats *s=aura_cmd_live();struct aura_cmd_shard *shard=aura_cmd_shard(handle);
    if (s && aura_cmd_acquire(s,shard)) {
        struct aura_cmd_slot *slot=aura_cmd_find(shard,handle);
        if (slot && slot->ended==0) slot->ended=result==VK_SUCCESS?1:-1;
        else {
            if (slot) slot->ended=-1; /* End alone cannot resurrect a reset/failed recording. */
            aura_cmd_reason(s,slot?ACR_INCOMPLETE:ACR_MISSING);
        }
        pthread_mutex_unlock(&shard->lock);
    }
    errno=saved;
}
static void aura_cmd_forget(void) {
    int saved=errno;if (aura_cmd_store()) aura_cmd_invalidate();errno=saved;
}
/* Inactive lifecycle calls lazily invalidate through live(), without locks or exported counters. */
static void aura_cmd_reset_buffer(VkCommandBuffer handle,int remove) {
    int saved=errno;
    if (!aura_cmd_store()) {errno=saved;return;}
    struct graphics_stats *s=aura_cmd_live();
    if (!s) {errno=saved;return;}
    struct aura_cmd_shard *shard=aura_cmd_shard(handle);
    if (aura_cmd_lifecycle_acquire(s,shard,remove)) {
        struct aura_cmd_slot *slot=aura_cmd_find(shard,handle);
        if (slot) {
            if(remove) memset(slot,0,sizeof(*slot)); else {slot->ended=-1;slot->loss=ACU_LIFECYCLE;}
            aura_cmd_reason(s,ACR_LIFECYCLE);
        }
        struct aura_cmd_owner *owner=aura_cmd_owner_find(shard,handle);
        if (aura_cmd_owner_current(shard,owner)) {
            owner->loss|=ACU_LIFECYCLE;
            if (remove) {owner->device=VK_NULL_HANDLE;owner->pool=VK_NULL_HANDLE;}
        }
        pthread_mutex_unlock(&shard->lock);
    }
    errno=saved;
}
static void aura_cmd_reset_pool(VkDevice device,VkCommandPool pool,int remove) {
    int saved=errno;
    if (!aura_cmd_store()) {errno=saved;return;}
    struct graphics_stats *s=aura_cmd_live();
    if (!s) {errno=saved;return;}
    for (unsigned i=0;i<AC_SHARDS;++i) {
        struct aura_cmd_shard *shard=&aura_cmd_shards[i];
        if (!aura_cmd_lifecycle_acquire(s,shard,remove)) continue;
        uint64_t serial=atomic_load(&shard->owner_serial);
        for(unsigned j=0;j<AC_SLOTS;++j) {
            struct aura_cmd_slot *slot=&shard->slots[j];
            int unknown=!slot->device || !slot->pool || slot->mapping_serial!=serial;
            if (slot->handle && (unknown || (slot->device==device && (!pool || slot->pool==pool)))) {
                if(remove || unknown) memset(slot,0,sizeof(*slot)); else {slot->ended=-1;slot->loss=ACU_LIFECYCLE;}
                aura_cmd_reason(s,ACR_LIFECYCLE);
            }
        }
        for (unsigned j=0;j<AC_OWNERS;++j) {
            struct aura_cmd_owner *owner=&shard->owners[j];
            if (!owner->handle) continue;
            if (owner->serial!=serial) {memset(owner,0,sizeof(*owner));continue;}
            if (owner->device==device && (!pool || owner->pool==pool)) {
                owner->loss|=ACU_LIFECYCLE;
                if (remove) {owner->device=VK_NULL_HANDLE;owner->pool=VK_NULL_HANDLE;}
            }
        }
        pthread_mutex_unlock(&shard->lock);
    }
    errno=saved;
}
static void aura_cmd_free(VkDevice device,VkCommandPool pool,uint32_t count,const VkCommandBuffer *buffers) {
    int saved=errno;
    if (!aura_cmd_live()) {errno=saved;return;}
    if (buffers) for (unsigned i=0;i<count && i<64;++i) aura_cmd_reset_buffer(buffers[i],1);
    if (count>64 || (count && !buffers)) aura_cmd_reset_pool(device,pool,1);
    errno=saved;
}
static int aura_cmd_valid(struct graphics_stats *s,struct aura_cmd_shard *shard,struct aura_cmd_slot *slot,uint64_t activity,int ended,uint32_t *loss) {
    unsigned reason=ACR_COUNT;
    uint32_t why=0;
    if (!slot) {reason=ACR_MISSING;why=ACU_MISSING;}
    else if (slot->serial!=atomic_load(&aura_cmd_serial) || slot->shard_serial!=atomic_load(&shard->serial) || slot->activity!=activity) {
        reason=ACR_GENERATION;why=ACU_GENERATION;
    } else if (slot->ended!=ended) {reason=ACR_INCOMPLETE;why=ACU_INCOMPLETE|slot->loss;}
    if (reason==ACR_COUNT) return 1;
    if (loss) *loss|=why;
    aura_cmd_reason(s,reason);return 0;
}
struct aura_cmd_op { uint32_t kind, images, buffers, ownership, src, dst, bind, flags; };
static struct aura_cmd_op aura_cmd_op(unsigned kind) {
    return (struct aura_cmd_op){.kind=kind,.src=UINT32_MAX,.dst=UINT32_MAX,.bind=UINT32_MAX};
}
static void aura_cmd_note(VkCommandBuffer handle,struct aura_cmd_op op) {
    int saved=errno;struct graphics_stats *s=aura_cmd_live();
    if (s) {
        uint64_t n=atomic_fetch_add(&s->header.commands.observed,1);
        if (n>=AURA_CMD_LIMIT) {aura_cmd_invalidate();errno=saved;return;}
        struct aura_cmd_shard *shard=aura_cmd_shard(handle);
        if (aura_cmd_acquire(s,shard)) {
            struct aura_cmd_slot *slot=aura_cmd_find(shard,handle);
            if (aura_cmd_valid(s,shard,slot,atomic_load(&s->header.activity),0,NULL)) {
                struct aura_cmd_data *d=&slot->data;
                ++d->operations;d->last_op=op.kind;d->flags|=op.flags;
                d->draws+=op.kind==AC_DRAW;d->dispatches+=op.kind==AC_DISPATCH;d->copies+=op.kind==AC_COPY;
                d->barriers+=op.kind==AC_BARRIER;d->clears+=op.kind==AC_CLEAR;d->rendering+=op.kind==AC_RENDER;
                d->secondary+=op.kind==AC_SECONDARY;
                if (op.kind==AC_SECONDARY) d->flags|=AC_SECONDARY_UNKNOWN;
                d->images+=op.images;d->buffer_ops+=op.buffers;d->ownership+=op.ownership;
                if (d->src_layout==UINT32_MAX && op.src!=UINT32_MAX) {d->src_layout=op.src;d->dst_layout=op.dst;}
                if (op.bind!=UINT32_MAX) d->bind_point=op.bind;
            } else atomic_fetch_add(&s->header.commands.dropped,1);
            pthread_mutex_unlock(&shard->lock);
        }
    }
    errno=saved;
}
/* Snapshot at ExecuteCommands: a child may leave the small summary cache before
 * Submit. Retain only its observed counters, and recheck the independent allocation /
 * recording identity at Submit. Resets, reuse, pause and missed observers stay unknown. */
static void aura_cmd_execute(VkCommandBuffer parent,uint32_t count,const VkCommandBuffer *buffers) {
    int saved=errno;
    struct aura_cmd_op op=aura_cmd_op(AC_SECONDARY);
    aura_cmd_note(parent,op);
    struct graphics_stats *s=aura_cmd_live();
    if (!s) {errno=saved;return;}
    uint64_t epoch=atomic_load(&s->header.activity),generation=atomic_load(&aura_cmd_serial);
    for(unsigned i=0;buffers && i<count && i<AC_CHILDREN;++i) {
        struct aura_cmd_child child={0};
        VkCommandBuffer h=buffers[i];
        struct aura_cmd_shard *shard=aura_cmd_shard(h);
        if (h && h!=parent && aura_cmd_acquire(s,shard)) {
            struct aura_cmd_slot *slot=aura_cmd_find(shard,h);
            struct aura_cmd_owner *owner=aura_cmd_owner_find(shard,h);
            if (aura_cmd_valid(s,shard,slot,epoch,1,NULL) && aura_cmd_owner_current(shard,owner) &&
                owner->device && owner->pool && owner->recording && owner->level==VK_COMMAND_BUFFER_LEVEL_SECONDARY &&
                !(owner->loss & ACU_LIFECYCLE) && !slot->data.secondary) {
                child=(struct aura_cmd_child){.handle=h,.device=owner->device,.recording=owner->recording,
                    .owner_serial=owner->serial,.shard_serial=atomic_load(&shard->serial),.data=slot->data};
            }
            pthread_mutex_unlock(&shard->lock);
        }
        shard=aura_cmd_shard(parent);
        if (!aura_cmd_acquire(s,shard)) continue;
        struct aura_cmd_slot *p=aura_cmd_find(shard,parent);
        if (generation==atomic_load(&aura_cmd_serial) && aura_cmd_valid(s,shard,p,epoch,0,NULL)) {
            if (p->child_count<AC_CHILDREN) p->children[p->child_count++]=child;
            else p->data.flags|=AC_TRUNCATED;
        }
        pthread_mutex_unlock(&shard->lock);
    }
    /* Retain missing/truncated children explicitly, including a null input list. */
    if (count>AC_CHILDREN || (count && !buffers)) {
        struct aura_cmd_shard *shard=aura_cmd_shard(parent);
        if (aura_cmd_acquire(s,shard)) {
            struct aura_cmd_slot *p=aura_cmd_find(shard,parent);
            if(aura_cmd_valid(s,shard,p,epoch,0,NULL)) p->data.flags|=AC_TRUNCATED;
            pthread_mutex_unlock(&shard->lock);
        }
    }
    errno=saved;
}
static void aura_cmd_sum(struct aura_cmd_data *out,const struct aura_cmd_data *p) {
    #define AC_SUM(field) do {if(UINT32_MAX-out->field<p->field) {out->field=UINT32_MAX;out->flags|=AC_TRUNCATED;} else out->field+=p->field;} while(0)
    AC_SUM(operations);AC_SUM(draws);AC_SUM(dispatches);AC_SUM(copies);AC_SUM(barriers);AC_SUM(clears);
    AC_SUM(rendering);AC_SUM(secondary);AC_SUM(images);AC_SUM(buffer_ops);AC_SUM(ownership);
    #undef AC_SUM
    out->flags|=p->flags;
}
static struct aura_cmd_op aura_cmd_barrier(const VkDependencyInfo *info) {
    struct aura_cmd_op op=aura_cmd_op(AC_BARRIER);
    if (!info) {op.flags|=AC_UNKNOWN;return op;}
    uint32_t images=info->imageMemoryBarrierCount,buffers=info->bufferMemoryBarrierCount;
    op.images=images<64?images:64;op.buffers=buffers<64?buffers:64;
    if (images>64 || buffers>64 || info->pNext) op.flags|=AC_TRUNCATED;
    if (images && !info->pImageMemoryBarriers) {op.flags|=AC_UNKNOWN;op.images=0;}
    if (buffers && !info->pBufferMemoryBarriers) {op.flags|=AC_UNKNOWN;op.buffers=0;}
    for (unsigned i=0;i<op.images;++i) {
        const VkImageMemoryBarrier2 *b=info->pImageMemoryBarriers+i;
        if (!i) {op.src=b->oldLayout;op.dst=b->newLayout;}
        op.ownership+=b->srcQueueFamilyIndex!=b->dstQueueFamilyIndex &&
            b->srcQueueFamilyIndex!=VK_QUEUE_FAMILY_IGNORED && b->dstQueueFamilyIndex!=VK_QUEUE_FAMILY_IGNORED;
        if (b->pNext) op.flags|=AC_TRUNCATED;
    }
    for (unsigned i=0;i<op.buffers;++i) {
        const VkBufferMemoryBarrier2 *b=info->pBufferMemoryBarriers+i;
        op.ownership+=b->srcQueueFamilyIndex!=b->dstQueueFamilyIndex &&
            b->srcQueueFamilyIndex!=VK_QUEUE_FAMILY_IGNORED && b->dstQueueFamilyIndex!=VK_QUEUE_FAMILY_IGNORED;
        if (b->pNext) op.flags|=AC_TRUNCATED;
    }
    return op;
}
static struct aura_cmd_data aura_cmd_submit(struct measurement m,struct aura_vk_data d,struct aura_vk_link link,
        uint32_t count,const void *info,int modern) {
    struct aura_cmd_data out={.call=d.call,.begin_ns=d.begin_ns,.activity=d.activity,.device=d.device,.queue=d.queue,
        .family=link.family,.index=link.index,.src_layout=UINT32_MAX,.dst_layout=UINT32_MAX,.bind_point=UINT32_MAX};
    if (!d.call) return out;
    int saved=errno;struct graphics_stats *s=m.s;
    atomic_fetch_add(&s->header.commands.submitted,1);
    if ((count && !info) || count>16) out.flags|=AC_TRUNCATED;
    if (!aura_cmd_live()) {out.flags|=AC_UNKNOWN_REASON(ACU_INACTIVE);errno=saved;return out;}
    for (unsigned i=0;info && i<count && i<16;++i) {
        const VkSubmitInfo *a=modern?NULL:(const VkSubmitInfo *)info+i;
        const VkSubmitInfo2 *b=modern?(const VkSubmitInfo2 *)info+i:NULL;
        uint32_t n=modern?b->commandBufferInfoCount:a->commandBufferCount;
        if (n>16 || (modern?b->pNext:a->pNext)) out.flags|=AC_TRUNCATED;
        out.buffers+=n<16?n:16;
        for (unsigned j=0;j<n && j<16;++j) {
            VkCommandBuffer handle=modern ? (b->pCommandBufferInfos?b->pCommandBufferInfos[j].commandBuffer:VK_NULL_HANDLE) :
                (a->pCommandBuffers?a->pCommandBuffers[j]:VK_NULL_HANDLE);
            struct aura_cmd_shard *shard=aura_cmd_shard(handle);
            if (!aura_cmd_acquire(s,shard)) {out.flags|=AC_UNKNOWN_REASON(ACU_CONTENTION);continue;}
            struct aura_cmd_slot *slot=aura_cmd_find(shard,handle);
            struct aura_cmd_data copy={0};
            struct aura_cmd_child children[AC_CHILDREN];unsigned child_count=0;VkDevice parent_device=VK_NULL_HANDLE;
            uint32_t loss=0;
            int valid=aura_cmd_valid(s,shard,slot,d.activity,1,&loss);
            if (valid) {copy=slot->data;parent_device=slot->device;child_count=slot->child_count;
                memcpy(children,slot->children,child_count*sizeof(*children));valid=aura_cmd_valid(s,shard,slot,d.activity,1,&loss);}
            if (!slot) {
                struct aura_cmd_owner *owner=aura_cmd_owner_find(shard,handle);
                if (aura_cmd_owner_current(shard,owner)) loss|=owner->loss;
                if (!aura_cmd_owner_current(shard,owner) || !owner->device || !owner->pool) loss|=ACU_ANCESTRY;
            }
            pthread_mutex_unlock(&shard->lock);
            if (!valid) {out.flags|=AC_UNKNOWN_REASON(loss);continue;}
            if (modern && b->pCommandBufferInfos[j].pNext) out.flags|=AC_TRUNCATED;
            struct aura_cmd_data *p=&copy;++out.known;out.flags|=p->flags;
            aura_cmd_sum(&out,p);
            for (unsigned k=0;k<child_count;++k) {
                struct aura_cmd_child *child=&children[k];
                if (!child->handle) continue;
                struct aura_cmd_shard *cs=aura_cmd_shard(child->handle);
                if (!aura_cmd_acquire(s,cs)) continue;
                struct aura_cmd_owner *owner=aura_cmd_owner_find(cs,child->handle);
                int current=aura_cmd_owner_current(cs,owner) && owner->recording==child->recording &&
                    owner->serial==child->owner_serial && owner->device==child->device && owner->pool &&
                    !(owner->loss & ACU_LIFECYCLE) && atomic_load(&cs->serial)==child->shard_serial &&
                    atomic_load(&s->header.activity)==d.activity && child->device==parent_device;
                pthread_mutex_unlock(&cs->lock);
                if (current) {aura_cmd_sum(&out,&child->data);out.flags|=AC_SECONDARY_FOLDED;}
            }
            /* Bit 4 deliberately remains: unwrapped operations and child lists beyond
             * the bound are not a complete Vulkan dependency history. Bit 32 means
             * at least one independently validated child's counters were included. */
            if (out.src_layout==UINT32_MAX) {out.src_layout=p->src_layout;out.dst_layout=p->dst_layout;}
            out.last_op=p->last_op;out.bind_point=p->bind_point;
        }
    }
    errno=saved;return out;
}
#endif
