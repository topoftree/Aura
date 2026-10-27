// Host boundaries are explicit Vulkan call doubles. The three tested method
// bodies are extracted verbatim from the pinned, actually compiled source.
#include <vulkan/vulkan_core.h>
#include <array>
#include <cassert>
#include <cstring>
#include <iostream>
#include <map>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <tuple>
#include <vector>

namespace dxvk {
using DxvkError = std::runtime_error;
namespace str { template<typename... T> std::string format(T&&... t) {
  std::ostringstream out; (out << ... << t); return out.str();
} }
struct Calls {
  bool gpl = false, maintenance5 = false, fail = false;
  unsigned shaders = 0, views = 0;
  VkDevice device() { return VK_NULL_HANDLE; }
  VkResult vkCreateShaderModule(VkDevice, const VkShaderModuleCreateInfo* info,
                               const VkAllocationCallbacks*, VkShaderModule* out) {
    ++shaders;
    assert(!gpl && info->sType == VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
    assert(info->pNext == nullptr && info->codeSize == 16 && info->pCode[0] == 0x07230203);
    if (fail) return VK_ERROR_OUT_OF_HOST_MEMORY;
    *out = reinterpret_cast<VkShaderModule>(uintptr_t(41));
    return VK_SUCCESS;
  }
  VkResult vkCreateBufferView(VkDevice, const VkBufferViewCreateInfo* info,
                             const VkAllocationCallbacks*, VkBufferView* out) {
    ++views;
    assert(bool(info->pNext) == maintenance5 && info->offset == 80 && info->range == 64);
    if (info->pNext) {
      const auto* flags = static_cast<const VkBufferUsageFlags2CreateInfoKHR*>(info->pNext);
      assert(flags->sType == VK_STRUCTURE_TYPE_BUFFER_USAGE_FLAGS_2_CREATE_INFO_KHR);
      assert(flags->usage == VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT);
    }
    if (fail) return VK_ERROR_OUT_OF_HOST_MEMORY;
    *out = reinterpret_cast<VkBufferView>(uintptr_t(43));
    return VK_SUCCESS;
  }
};
struct Device {
  Calls calls;
  struct Features { struct { bool graphicsPipelineLibrary; } extGraphicsPipelineLibrary; } caps;
  const auto& features() { return caps; }
  Calls* vkd() { return &calls; }
};
struct SpirvCodeBuffer {
  std::array<uint32_t, 4> words = {0x07230203, 0, 0, 0};
  size_t size() const { return sizeof(words); }
  const uint32_t* data() const { return words.data(); }
};
struct DxvkShaderStageInfo {
  Device* m_device;
  uint32_t m_stageCount = 0;
  std::array<SpirvCodeBuffer, 4> m_codeBuffers;
  struct Module { VkShaderModuleCreateInfo moduleInfo; };
  std::array<Module, 4> m_moduleInfos;
  std::array<VkPipelineShaderStageCreateInfo, 4> m_stageInfos;
  void addStage(VkShaderStageFlagBits, SpirvCodeBuffer&&, const VkSpecializationInfo*);
};
struct DxvkBufferViewKey {
  VkBufferUsageFlags usage = VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
  VkFormat format = VK_FORMAT_R32_UINT;
  VkDeviceSize offset = 16, size = 64;
  bool operator<(const DxvkBufferViewKey& other) const {
    return std::tie(usage, format, offset, size) < std::tie(other.usage, other.format, other.offset, other.size);
  }
};
struct DxvkResourceBufferViewMap {
  Calls* m_vkd;
  VkBuffer m_buffer = VK_NULL_HANDLE;
  bool m_passBufferUsage = false;
  std::mutex m_mutex;
  std::map<DxvkBufferViewKey, VkBufferView> m_views;
  VkBufferView createBufferView(const DxvkBufferViewKey&, VkDeviceSize);
};
#include "actual_fallback.inc"
}

int main() {
  unsigned cases = 0;
  for (bool gpl : {false, true}) {
    dxvk::Device d{}; d.caps.extGraphicsPipelineLibrary.graphicsPipelineLibrary = gpl; d.calls.gpl = gpl;
    dxvk::DxvkShaderStageInfo info{&d};
    VkSpecializationInfo spec{};
    info.addStage(VK_SHADER_STAGE_VERTEX_BIT, dxvk::SpirvCodeBuffer(), &spec);
    const auto& stage = info.m_stageInfos[0];
    assert(info.m_stageCount == 1 && stage.stage == VK_SHADER_STAGE_VERTEX_BIT && stage.pSpecializationInfo == &spec);
    assert(d.calls.shaders == (gpl ? 0u : 1u));
    assert(bool(stage.module) == !gpl);
    assert(bool(stage.pNext) == gpl); // regression: old 2.6.2 attached this even with a real module
    assert(std::strcmp(stage.pName, "main") == 0);
    ++cases;
  }
  dxvk::Device failed{}; failed.calls.fail = true;
  dxvk::DxvkShaderStageInfo info{&failed};
  bool threw = false;
  try { info.addStage(VK_SHADER_STAGE_FRAGMENT_BIT, dxvk::SpirvCodeBuffer(), nullptr); }
  catch (const dxvk::DxvkError&) { threw = true; }
  assert(threw && info.m_stageCount == 0 && failed.calls.shaders == 1); ++cases;
  for (bool maintenance : {false, true}) {
    dxvk::Calls calls{}; calls.maintenance5 = maintenance;
    dxvk::DxvkResourceBufferViewMap map{&calls}; map.m_passBufferUsage = maintenance;
    dxvk::DxvkBufferViewKey key;
    auto view = map.createBufferView(key, 64);
    assert(view && calls.views == 1 && map.createBufferView(key, 64) == view && calls.views == 1); ++cases;
    dxvk::Calls failure{}; failure.fail = true; failure.maintenance5 = maintenance;
    dxvk::DxvkResourceBufferViewMap bad{&failure}; bad.m_passBufferUsage = maintenance;
    threw = false;
    try { bad.createBufferView(key, 64); } catch (const dxvk::DxvkError&) { threw = true; }
    assert(threw && bad.m_views.empty()); ++cases;
  }
  std::cout << "shader_and_buffer_fallback_cases=" << cases << '\n';
}
