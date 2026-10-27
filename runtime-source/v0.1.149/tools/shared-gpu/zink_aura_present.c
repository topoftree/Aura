/* SPDX-License-Identifier: MIT
 * Experimental Aura / Zink AHB bridge. No pixels cross the CPU on this path.
 * One Vulkan blit, producer fence, and two slots with explicit foreign ownership.
 */
#include "zink_aura_present.h"
#include "zink_context.h"
#include "zink_screen.h"
#include "zink_resource.h"
#include "zink_clear.h"
#include "zink_batch.h"
#include "util/u_inlines.h"
#include "shared_gpu_protocol.h"
#include <android/hardware_buffer.h>
#include <stdio.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/time.h>
#include <unistd.h>
#include <errno.h>

struct aura_slot {
   AHardwareBuffer *buffer;
   VkImage image;
   VkDeviceMemory memory;
   bool used;
};
struct aura_present {
   struct aura_slot slots[2];
   int fd;
   uint32_t width, height, window, serial, next;
};

static bool
transfer(int fd, void *data, size_t size, bool write)
{
   char *p = data;
   while (size) {
      ssize_t n = write ? send(fd, p, size, MSG_NOSIGNAL) : recv(fd, p, size, 0);
      if (n < 0 && errno == EINTR) continue;
      if (n <= 0) return false;
      p += n; size -= n;
   }
   return true;
}

void
zink_aura_present_destroy(struct zink_context *ctx)
{
   struct aura_present *a = ctx->aura_present;
   if (!a) return;
   struct zink_screen *screen = zink_screen(ctx->base.screen);
   if (a->fd >= 0) close(a->fd);
   /* Called only after the synchronous producer fence, or before any submission. */
   for (unsigned i = 0; i < 2; ++i) {
      if (a->slots[i].image) VKSCR(DestroyImage)(screen->dev, a->slots[i].image, NULL);
      if (a->slots[i].memory) VKSCR(FreeMemory)(screen->dev, a->slots[i].memory, NULL);
      if (a->slots[i].buffer) AHardwareBuffer_release(a->slots[i].buffer);
   }
   free(a);
   ctx->aura_present = NULL;
}

static bool
make_slot(struct zink_screen *screen, struct aura_present *a, struct aura_slot *slot)
{
   VkPhysicalDeviceExternalImageFormatInfo external_format = {
      .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO,
      .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
   };
   VkPhysicalDeviceImageFormatInfo2 query = {
      .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2, .pNext = &external_format,
      .format = VK_FORMAT_R8G8B8A8_UNORM, .type = VK_IMAGE_TYPE_2D, .tiling = VK_IMAGE_TILING_OPTIMAL,
      .usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
   };
   VkExternalImageFormatProperties external_props = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};
   VkImageFormatProperties2 image_props = {.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2, .pNext = &external_props};
   if (VKSCR(GetPhysicalDeviceImageFormatProperties2)(screen->pdev, &query, &image_props) != VK_SUCCESS ||
       !(external_props.externalMemoryProperties.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) ||
       a->width > image_props.imageFormatProperties.maxExtent.width || a->height > image_props.imageFormatProperties.maxExtent.height)
      return false;
   AHardwareBuffer_Desc desc = {
      .width = a->width, .height = a->height, .layers = 1,
      .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
      .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
               AHARDWAREBUFFER_USAGE_CPU_READ_RARELY,
   };
   if (!AHardwareBuffer_isSupported(&desc) || AHardwareBuffer_allocate(&desc, &slot->buffer)) return false;
   // Verify exceptional X11 reads are possible before accepting this path.
   void *map = NULL;
   if (AHardwareBuffer_lock(slot->buffer, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, NULL, &map)) return false;
   if (AHardwareBuffer_unlock(slot->buffer, NULL) || !map) return false;
   VkAndroidHardwareBufferFormatPropertiesANDROID format = {
      .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID,
   };
   VkAndroidHardwareBufferPropertiesANDROID props = {
      .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID, .pNext = &format,
   };
   if (VKSCR(GetAndroidHardwareBufferPropertiesANDROID)(screen->dev, slot->buffer, &props) != VK_SUCCESS ||
       format.format != VK_FORMAT_R8G8B8A8_UNORM || !props.memoryTypeBits || !props.allocationSize ||
       !(format.formatFeatures & VK_FORMAT_FEATURE_BLIT_DST_BIT)) return false;
   VkExternalMemoryImageCreateInfo external = {
      .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
      .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
   };
   VkImageCreateInfo image = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .pNext = &external,
      .imageType = VK_IMAGE_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM,
      .extent = {a->width, a->height, 1}, .mipLevels = 1, .arrayLayers = 1,
      .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
      .usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
      .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
   };
   if (VKSCR(CreateImage)(screen->dev, &image, NULL, &slot->image) != VK_SUCCESS) return false;
   VkMemoryDedicatedAllocateInfo dedicated = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, .image = slot->image,
   };
   VkImportAndroidHardwareBufferInfoANDROID import = {
      .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
      .pNext = &dedicated, .buffer = slot->buffer,
   };
   VkMemoryAllocateInfo memory = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &import,
      .allocationSize = props.allocationSize, .memoryTypeIndex = (unsigned)__builtin_ctz(props.memoryTypeBits),
   };
   return VKSCR(AllocateMemory)(screen->dev, &memory, NULL, &slot->memory) == VK_SUCCESS &&
          VKSCR(BindImageMemory)(screen->dev, slot->image, slot->memory, 0) == VK_SUCCESS;
}

static bool
initialize(struct zink_context *ctx, struct zink_resource *res, uint32_t window)
{
   struct zink_screen *screen = zink_screen(ctx->base.screen);
   const char *endpoint = getenv("AURA_SHARED_GPU_SOCKET");
   if (!endpoint || !*endpoint || !screen->info.have_ANDROID_external_memory_android_hardware_buffer ||
       !screen->info.have_EXT_queue_family_foreign ||
       !screen->vk.GetAndroidHardwareBufferPropertiesANDROID) {
      fprintf(stderr, "AURA_SHARED_GPU_V1 state=UNSUPPORTED\n");
      return false;
   }
   struct sockaddr_un addr = {.sun_family = AF_UNIX};
   if (strlen(endpoint) >= sizeof(addr.sun_path)) return false;
   struct aura_present *a = calloc(1, sizeof(*a));
   if (!a) return false;
   ctx->aura_present = a;
   a->fd = -1;
   a->width = res->base.b.width0; a->height = res->base.b.height0; a->window = window;
   for (unsigned i = 0; i < 2; ++i) if (!make_slot(screen, a, &a->slots[i])) return false;
   a->fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
   if (a->fd < 0) return false;
   struct timeval timeout = {.tv_sec = 2};
   setsockopt(a->fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
   setsockopt(a->fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
   strcpy(addr.sun_path, endpoint);
   if (connect(a->fd, (void *)&addr, sizeof(addr))) return false;
   struct aura_gpu_hello hello = {AURA_GPU_MAGIC, window, a->width, a->height};
   if (!transfer(a->fd, &hello, sizeof(hello), true)) return false;
   for (unsigned i = 0; i < 2; ++i)
      if (AHardwareBuffer_sendHandleToUnixSocket(a->slots[i].buffer, a->fd)) return false;
   uint32_t ack = 0;
   bool ready = transfer(a->fd, &ack, sizeof(ack), false) && ack == AURA_GPU_MAGIC;
   if (ready) fprintf(stderr, "AURA_SHARED_GPU_V1 state=READY\n");
   return ready;
}

bool
zink_aura_present(struct zink_context *ctx, struct zink_resource *res, uint32_t window)
{
   if (ctx->aura_present_disabled) return false;
   struct zink_screen *screen = zink_screen(ctx->base.screen);
   if (res->base.b.target != PIPE_TEXTURE_2D || res->base.b.nr_samples > 1 ||
       !aura_gpu_dimensions(res->base.b.width0, res->base.b.height0) ||
       !(res->obj->vkusage & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) ||
       !(res->obj->vkfeats & VK_FORMAT_FEATURE_BLIT_SRC_BIT) ||
       (res->format != VK_FORMAT_R8G8B8A8_UNORM && res->format != VK_FORMAT_B8G8R8A8_UNORM)) goto fail;
   struct aura_present *a = ctx->aura_present;
   if (a && (a->window != window || a->width != res->base.b.width0 || a->height != res->base.b.height0))
      zink_aura_present_destroy(ctx);
   if (!ctx->aura_present && !initialize(ctx, res, window)) goto fail;
   a = ctx->aura_present;
   struct aura_slot *slot = &a->slots[a->next];
   zink_fb_clears_apply(ctx, &res->base.b);
   zink_batch_no_rp(ctx);
   bool no_reorder = ctx->no_reorder;
   ctx->no_reorder = true;
   screen->image_barrier(ctx, res, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                         VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
   res->obj->unordered_read = false;
   res->obj->unordered_write = false;
   zink_batch_reference_resource_rw(ctx, res, false);
   VkCommandBuffer cmd = ctx->bs->cmdbuf;
   VkImageMemoryBarrier acquire = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
      .oldLayout = slot->used ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED,
      .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
      .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT, .dstQueueFamilyIndex = screen->gfx_queue,
      .image = slot->image, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
   };
   VKCTX(CmdPipelineBarrier)(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &acquire);
   VkImageBlit blit = {
      .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, .srcOffsets = {{0,0,0},{a->width,a->height,1}},
      .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, .dstOffsets = {{0,0,0},{a->width,a->height,1}},
   };
   VKCTX(CmdBlitImage)(cmd, res->obj->image, res->layout, slot->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
   VkImageMemoryBarrier release = acquire;
   release.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; release.dstAccessMask = 0;
   release.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; release.newLayout = VK_IMAGE_LAYOUT_GENERAL;
   release.srcQueueFamilyIndex = screen->gfx_queue; release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
   VKCTX(CmdPipelineBarrier)(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, 1, &release);
   ctx->no_reorder = no_reorder;
   ctx->bs->has_work = true;
   struct pipe_fence_handle *fence = NULL;
   ctx->base.flush(&ctx->base, &fence, PIPE_FLUSH_END_OF_FRAME);
   bool complete = fence && screen->base.fence_finish(&screen->base, &ctx->base, fence, UINT64_MAX);
   screen->base.fence_reference(&screen->base, &fence, NULL);
   if (!complete) {
      /* Even a failed fence allocation must not free an image still in use. */
      simple_mtx_lock(&screen->queue_lock);
      VKSCR(QueueWaitIdle)(screen->queue);
      simple_mtx_unlock(&screen->queue_lock);
      goto fail;
   }
   slot->used = true;
   a->serial = a->serial == UINT32_MAX ? 1 : a->serial + 1;
   struct aura_gpu_present present = {AURA_GPU_MAGIC, a->next, a->serial};
   uint32_t ack = 0;
   if (!transfer(a->fd, &present, sizeof(present), true) || !transfer(a->fd, &ack, sizeof(ack), false) || ack != a->serial) goto fail;
   a->next ^= 1;
   return true;
fail:
   fprintf(stderr, "AURA_SHARED_GPU_V1 state=FALLBACK\n");
   zink_aura_present_destroy(ctx);
   ctx->aura_present_disabled = true;
   return false;
}
