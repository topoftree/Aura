/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT -- exact typed delegates; observed subset only. */
static VkResult cmd_AllocateCommandBuffers(VkDevice device,const VkCommandBufferAllocateInfo *info,VkCommandBuffer *buffers) { VkResult r=ORIGINAL(F_C_AllocateCommandBuffers,PFN_vkAllocateCommandBuffers)(device,info,buffers);aura_cmd_allocated(device,info,buffers,r);return r; }
static VkResult cmd_BeginCommandBuffer(VkCommandBuffer cb,const VkCommandBufferBeginInfo *info) { VkResult r=ORIGINAL(F_C_BeginCommandBuffer,PFN_vkBeginCommandBuffer)(cb,info);aura_cmd_begin(cb,r);return r; }
static VkResult cmd_EndCommandBuffer(VkCommandBuffer cb) { VkResult r=ORIGINAL(F_C_EndCommandBuffer,PFN_vkEndCommandBuffer)(cb);aura_cmd_end(cb,r);return r; }
static VkResult cmd_ResetCommandBuffer(VkCommandBuffer cb,VkCommandBufferResetFlags flags) { aura_cmd_reset_buffer(cb,0);VkResult r=ORIGINAL(F_C_ResetCommandBuffer,PFN_vkResetCommandBuffer)(cb,flags);return r; }
static VkResult cmd_ResetCommandPool(VkDevice device,VkCommandPool pool,VkCommandPoolResetFlags flags) { aura_cmd_reset_pool(device,pool,0);VkResult r=ORIGINAL(F_C_ResetCommandPool,PFN_vkResetCommandPool)(device,pool,flags);return r; }
static void cmd_DestroyCommandPool(VkDevice device,VkCommandPool pool,const VkAllocationCallbacks *allocator) { if(pool) aura_cmd_reset_pool(device,pool,1);ORIGINAL(F_C_DestroyCommandPool,PFN_vkDestroyCommandPool)(device,pool,allocator); }
static void cmd_FreeCommandBuffers(VkDevice device,VkCommandPool pool,uint32_t count,const VkCommandBuffer *buffers) { aura_cmd_free(device,pool,count,buffers);ORIGINAL(F_C_FreeCommandBuffers,PFN_vkFreeCommandBuffers)(device,pool,count,buffers); }
static void cmd_Draw(VkCommandBuffer cb,uint32_t vertexCount,uint32_t instanceCount,uint32_t firstVertex,uint32_t firstInstance) {
    ORIGINAL(F_C_Draw,PFN_vkCmdDraw)(cb,vertexCount,instanceCount,firstVertex,firstInstance);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_DRAW);aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_DrawIndexed(VkCommandBuffer cb,uint32_t indexCount,uint32_t instanceCount,uint32_t firstIndex,int32_t vertexOffset,uint32_t firstInstance) {
    ORIGINAL(F_C_DrawIndexed,PFN_vkCmdDrawIndexed)(cb,indexCount,instanceCount,firstIndex,vertexOffset,firstInstance);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_DRAW);aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_DrawIndirect(VkCommandBuffer cb,VkBuffer buffer,VkDeviceSize offset,uint32_t drawCount,uint32_t stride) {
    ORIGINAL(F_C_DrawIndirect,PFN_vkCmdDrawIndirect)(cb,buffer,offset,drawCount,stride);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_DRAW);o.buffers=1;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_DrawIndexedIndirect(VkCommandBuffer cb,VkBuffer buffer,VkDeviceSize offset,uint32_t drawCount,uint32_t stride) {
    ORIGINAL(F_C_DrawIndexedIndirect,PFN_vkCmdDrawIndexedIndirect)(cb,buffer,offset,drawCount,stride);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_DRAW);o.buffers=1;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_DrawIndirectCount(VkCommandBuffer cb,VkBuffer buffer,VkDeviceSize offset,VkBuffer countBuffer,VkDeviceSize countOffset,uint32_t maxDrawCount,uint32_t stride) {
    ORIGINAL(F_C_DrawIndirectCount,PFN_vkCmdDrawIndirectCount)(cb,buffer,offset,countBuffer,countOffset,maxDrawCount,stride);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_DRAW);o.buffers=2;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_DrawIndexedIndirectCount(VkCommandBuffer cb,VkBuffer buffer,VkDeviceSize offset,VkBuffer countBuffer,VkDeviceSize countOffset,uint32_t maxDrawCount,uint32_t stride) {
    ORIGINAL(F_C_DrawIndexedIndirectCount,PFN_vkCmdDrawIndexedIndirectCount)(cb,buffer,offset,countBuffer,countOffset,maxDrawCount,stride);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_DRAW);o.buffers=2;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_Dispatch(VkCommandBuffer cb,uint32_t x,uint32_t y,uint32_t z) {
    ORIGINAL(F_C_Dispatch,PFN_vkCmdDispatch)(cb,x,y,z);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_DISPATCH);aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_DispatchIndirect(VkCommandBuffer cb,VkBuffer buffer,VkDeviceSize offset) {
    ORIGINAL(F_C_DispatchIndirect,PFN_vkCmdDispatchIndirect)(cb,buffer,offset);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_DISPATCH);o.buffers=1;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_CopyBuffer2(VkCommandBuffer cb,const VkCopyBufferInfo2 *info) {
    ORIGINAL(F_C_CopyBuffer2,PFN_vkCmdCopyBuffer2)(cb,info);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_COPY);o.images=0;o.buffers=2;if(info && info->pNext)o.flags|=AC_TRUNCATED;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_CopyImage2(VkCommandBuffer cb,const VkCopyImageInfo2 *info) {
    ORIGINAL(F_C_CopyImage2,PFN_vkCmdCopyImage2)(cb,info);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_COPY);o.images=2;o.buffers=0;if(info && info->pNext)o.flags|=AC_TRUNCATED;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_CopyBufferToImage2(VkCommandBuffer cb,const VkCopyBufferToImageInfo2 *info) {
    ORIGINAL(F_C_CopyBufferToImage2,PFN_vkCmdCopyBufferToImage2)(cb,info);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_COPY);o.images=1;o.buffers=1;if(info && info->pNext)o.flags|=AC_TRUNCATED;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_CopyImageToBuffer2(VkCommandBuffer cb,const VkCopyImageToBufferInfo2 *info) {
    ORIGINAL(F_C_CopyImageToBuffer2,PFN_vkCmdCopyImageToBuffer2)(cb,info);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_COPY);o.images=1;o.buffers=1;if(info && info->pNext)o.flags|=AC_TRUNCATED;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_BlitImage2(VkCommandBuffer cb,const VkBlitImageInfo2 *info) {
    ORIGINAL(F_C_BlitImage2,PFN_vkCmdBlitImage2)(cb,info);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_COPY);o.images=2;o.buffers=0;if(info && info->pNext)o.flags|=AC_TRUNCATED;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_ResolveImage2(VkCommandBuffer cb,const VkResolveImageInfo2 *info) {
    ORIGINAL(F_C_ResolveImage2,PFN_vkCmdResolveImage2)(cb,info);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_COPY);o.images=2;o.buffers=0;if(info && info->pNext)o.flags|=AC_TRUNCATED;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_PipelineBarrier2(VkCommandBuffer cb,const VkDependencyInfo *info) {
    ORIGINAL(F_C_PipelineBarrier2,PFN_vkCmdPipelineBarrier2)(cb,info);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_BARRIER);o=aura_cmd_barrier(info);aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_SetEvent2(VkCommandBuffer cb,VkEvent event,const VkDependencyInfo *info) {
    ORIGINAL(F_C_SetEvent2,PFN_vkCmdSetEvent2)(cb,event,info);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_BARRIER);o=aura_cmd_barrier(info);aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_ClearColorImage(VkCommandBuffer cb,VkImage image,VkImageLayout layout,const VkClearColorValue *color,uint32_t count,const VkImageSubresourceRange *ranges) {
    ORIGINAL(F_C_ClearColorImage,PFN_vkCmdClearColorImage)(cb,image,layout,color,count,ranges);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_CLEAR);o.images=1;o.src=o.dst=layout;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_ClearDepthStencilImage(VkCommandBuffer cb,VkImage image,VkImageLayout layout,const VkClearDepthStencilValue *value,uint32_t count,const VkImageSubresourceRange *ranges) {
    ORIGINAL(F_C_ClearDepthStencilImage,PFN_vkCmdClearDepthStencilImage)(cb,image,layout,value,count,ranges);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_CLEAR);o.images=1;o.src=o.dst=layout;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_ClearAttachments(VkCommandBuffer cb,uint32_t count,const VkClearAttachment *attachments,uint32_t rectCount,const VkClearRect *rects) {
    ORIGINAL(F_C_ClearAttachments,PFN_vkCmdClearAttachments)(cb,count,attachments,rectCount,rects);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_CLEAR);aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_BeginRendering(VkCommandBuffer cb,const VkRenderingInfo *info) {
    ORIGINAL(F_C_BeginRendering,PFN_vkCmdBeginRendering)(cb,info);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_RENDER);if(info && info->pNext)o.flags|=AC_TRUNCATED;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_EndRendering(VkCommandBuffer cb) {
    ORIGINAL(F_C_EndRendering,PFN_vkCmdEndRendering)(cb);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_RENDER);aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_ExecuteCommands(VkCommandBuffer cb,uint32_t count,const VkCommandBuffer *buffers) {
    ORIGINAL(F_C_ExecuteCommands,PFN_vkCmdExecuteCommands)(cb,count,buffers);
    int saved=errno;if(aura_cmd_live()){aura_cmd_execute(cb,count,buffers);}errno=saved;
}
static void cmd_BindPipeline(VkCommandBuffer cb,VkPipelineBindPoint point,VkPipeline pipeline) {
    ORIGINAL(F_C_BindPipeline,PFN_vkCmdBindPipeline)(cb,point,pipeline);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_BIND);o.bind=point;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_FillBuffer(VkCommandBuffer cb,VkBuffer dst,VkDeviceSize offset,VkDeviceSize size,uint32_t data) {
    ORIGINAL(F_C_FillBuffer,PFN_vkCmdFillBuffer)(cb,dst,offset,size,data);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_CLEAR);o.buffers=1;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_UpdateBuffer(VkCommandBuffer cb,VkBuffer dst,VkDeviceSize offset,VkDeviceSize size,const void *data) {
    ORIGINAL(F_C_UpdateBuffer,PFN_vkCmdUpdateBuffer)(cb,dst,offset,size,data);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_COPY);o.buffers=1;aura_cmd_note(cb,o);}errno=saved;
}
static void cmd_CopyQueryPoolResults(VkCommandBuffer cb,VkQueryPool pool,uint32_t first,uint32_t count,VkBuffer dst,VkDeviceSize offset,VkDeviceSize stride,VkQueryResultFlags flags) {
    ORIGINAL(F_C_CopyQueryPoolResults,PFN_vkCmdCopyQueryPoolResults)(cb,pool,first,count,dst,offset,stride,flags);
    int saved=errno;if(aura_cmd_live()){struct aura_cmd_op o=aura_cmd_op(AC_COPY);o.buffers=1;aura_cmd_note(cb,o);}errno=saved;
}
