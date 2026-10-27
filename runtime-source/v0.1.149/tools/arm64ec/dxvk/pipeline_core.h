// SPDX-License-Identifier: MIT
#pragma once
#include <vulkan/vulkan_core.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>

namespace aura_pipeline {
constexpr uint32_t Slots = 64, RecordBytes = 1024, MaxCalls = 8192;
struct Control {
  char magic[8]; uint32_t version, slots, enabled, calls, ready, dropped;
  uint64_t deadlineUtcMs; uint32_t exhausted, overwritten, scanBytes, reserved[3];
};
struct Record {
  uint32_t sequence, kind, pid, tid, call, state, result, used;
  uint32_t fields[248];
};
static_assert(sizeof(Control) == 64 && sizeof(Record) == RecordBytes);
using Shader = std::array<uint32_t, 20>;

// Public enums, counts and a bounded-prefix fingerprint only. No names, handles,
// specialization contents, SPIR-V words, pointers or executable paths leave here.
inline Shader summarize(const VkShaderModuleCreateInfo* info, bool budget) {
  Shader s {};
  if (!info) return s;
  s[0] = uint32_t(std::min<size_t>(info->codeSize, UINT32_MAX));
  if (!budget) { s[4] = 8; return s; }
  if (!info->pCode || info->codeSize < 20 || info->codeSize % 4) { s[4] = 4; return s; }
  size_t words = std::min<size_t>(info->codeSize / 4, 4096);
  auto code = info->pCode;
  if (code[0] != 0x07230203) { s[4] = 4; return s; }
  s[1] = code[1]; s[2] = code[2]; s[3] = code[3];
  s[4] = info->codeSize > words * 4 ? 2 : 1;
  uint64_t hash = 14695981039346656037ull;
  for (size_t i = 0; i < words; ++i) { hash ^= code[i]; hash *= 1099511628211ull; }
  s[5] = uint32_t(hash); s[6] = uint32_t(hash >> 32);
  for (size_t i = 5; i < words;) {
    uint32_t count = code[i] >> 16, op = code[i] & 0xffff;
    if (!count || count > words - i) { s[4] |= (s[4] & 2) && count ? 2 : 4; break; }
    if (op == 17 && count >= 2) { // OpCapability, at most twelve public values.
      if (s[7] < 12) s[8 + s[7]] = code[i + 1];
      ++s[7];
    }
    i += count;
  }
  return s;
}
inline void chain(const void* p, uint32_t* out) {
  const auto* node = static_cast<const VkBaseInStructure*>(p);
  uint32_t n = 0;
  for (; node && n < 8; ++n, node = node->pNext) out[2 + n] = node->sType;
  out[0] = n; out[1] = node ? 1 : 0;
}
template<class Lookup, class Scan>
inline void stage(const VkPipelineShaderStageCreateInfo& s, uint32_t* f, Lookup lookup, Scan scan) {
  f[0] = s.stage; f[1] = s.flags;
  f[2] = s.pNext ? static_cast<const VkBaseInStructure*>(s.pNext)->sType : 0;
  f[3] = s.module != VK_NULL_HANDLE;
  f[4] = s.pSpecializationInfo ? s.pSpecializationInfo->mapEntryCount : 0;
  f[5] = s.pSpecializationInfo ? uint32_t(std::min<size_t>(s.pSpecializationInfo->dataSize, UINT32_MAX)) : 0;
  f[6] = s.pName != nullptr;
  Shader meta {};
  if (s.module) { if (lookup(s.module, meta)) f[7] = 1; }
  else {
    auto* p = static_cast<const VkBaseInStructure*>(s.pNext);
    for (unsigned i = 0; p && i < 8; ++i, p = p->pNext) {
      if (p->sType == VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO) {
        meta = scan(reinterpret_cast<const VkShaderModuleCreateInfo*>(p)); f[7] = 2; break;
      }
    }
  }
  std::copy(meta.begin(), meta.end(), f + 8);
}
template<class Lookup, class Scan>
inline Record graphics(uint32_t count, const VkGraphicsPipelineCreateInfo* info, Lookup lookup, Scan scan) {
  Record r {}; r.kind = 2; r.used = 248; auto* f = r.fields; f[0] = count;
  if (!info || !count) return r;
  const auto& p = info[0]; // Multiple create infos are explicitly partial.
  f[1] = p.flags; chain(p.pNext, f + 2); f[12] = p.stageCount;
  f[13] = p.layout != VK_NULL_HANDLE; f[14] = p.renderPass != VK_NULL_HANDLE; f[15] = p.subpass;
  if (p.pInputAssemblyState) { f[16] = p.pInputAssemblyState->topology; f[17] = p.pInputAssemblyState->primitiveRestartEnable; }
  if (p.pRasterizationState) {
    f[18] = p.pRasterizationState->polygonMode; f[19] = p.pRasterizationState->cullMode;
    f[20] = p.pRasterizationState->frontFace; f[21] = p.pRasterizationState->rasterizerDiscardEnable;
    f[55] = p.pRasterizationState->depthClampEnable; f[56] = p.pRasterizationState->depthBiasEnable;
  }
  if (p.pMultisampleState) { f[22] = p.pMultisampleState->rasterizationSamples;
    f[53] = p.pMultisampleState->sampleShadingEnable; f[54] = p.pMultisampleState->alphaToCoverageEnable; }
  if (p.pDepthStencilState) { f[23] = p.pDepthStencilState->depthTestEnable; f[24] = p.pDepthStencilState->depthWriteEnable;
    f[25] = p.pDepthStencilState->depthCompareOp; f[26] = p.pDepthStencilState->stencilTestEnable; }
  if (p.pColorBlendState) {
    f[27] = p.pColorBlendState->attachmentCount;
    if (p.pColorBlendState->pAttachments) for (unsigned i = 0; i < std::min(16u, f[27]); ++i)
      f[28] |= uint32_t(!!p.pColorBlendState->pAttachments[i].blendEnable) << i;
  }
  if (p.pDynamicState) {
    f[29] = p.pDynamicState->dynamicStateCount; f[30] = f[29] > 16;
    if (p.pDynamicState->pDynamicStates) for (unsigned i = 0; i < std::min(16u, f[29]); ++i) f[32+i] = p.pDynamicState->pDynamicStates[i];
  }
  if (p.pVertexInputState) { f[48] = p.pVertexInputState->vertexBindingDescriptionCount; f[49] = p.pVertexInputState->vertexAttributeDescriptionCount; }
  if (p.pViewportState) { f[50] = p.pViewportState->viewportCount; f[51] = p.pViewportState->scissorCount; }
  if (p.pTessellationState) f[52] = p.pTessellationState->patchControlPoints;
  f[57] = count > 1 || p.stageCount > 6; f[58] = p.pStages != nullptr;
  f[59] = (!!p.pInputAssemblyState) | (!!p.pRasterizationState << 1) |
    (!!p.pMultisampleState << 2) | (!!p.pDepthStencilState << 3) | (!!p.pColorBlendState << 4) |
    (!!p.pDynamicState << 5) | (!!p.pVertexInputState << 6) | (!!p.pViewportState << 7) |
    (!!p.pTessellationState << 8);
  auto* node = static_cast<const VkBaseInStructure*>(p.pNext);
  for (unsigned i = 0; node && i < 8; ++i, node = node->pNext) {
    if (node->sType != VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO) continue;
    const auto* v = reinterpret_cast<const VkPipelineRenderingCreateInfo*>(node);
    f[232] = 1; f[233] = v->viewMask; f[234] = v->depthAttachmentFormat; f[235] = v->stencilAttachmentFormat;
    f[236] = v->colorAttachmentCount; f[237] = v->colorAttachmentCount > 8 || (v->colorAttachmentCount && !v->pColorAttachmentFormats);
    if (v->pColorAttachmentFormats) for (unsigned j = 0; j < std::min(8u, v->colorAttachmentCount); ++j)
      f[238+j] = v->pColorAttachmentFormats[j];
    break;
  }
  if (p.pStages) for (unsigned i = 0; i < std::min(6u, p.stageCount); ++i) stage(p.pStages[i], f + 64 + i * 28, lookup, scan);
  return r;
}
}
