// Only platform/capability data are doubled. decisions.h contains real DXVK functions.
#include <array>
#include <cassert>
#include <cerrno>
#include <iostream>
#include <optional>
#include <sstream>
#include <string>
#include <vector>
#define _WIN32 1
#define __cdecl
#define VK_API_VERSION_MAJOR(v) ((v) >> 22)
#define VK_API_VERSION_MINOR(v) (((v) >> 12) & 1023)
#include "util_aura_init.h"
static bool enabled;
static unsigned lastError = 51;
static std::vector<std::string> lines;
std::string dxvk::env::getEnvVar(const char* name) {
  assert(std::string(name) == "AURA_DXVK_INIT_TRACE");
  errno = ERANGE; lastError = 92; return enabled ? "1" : "0";
}
DWORD GetLastError() { return lastError; }
void SetLastError(DWORD value) { lastError = value; }
DWORD GetCurrentProcessId() { return 123; }
ULONGLONG GetTickCount64() { return 100; }
HMODULE GetModuleHandleA(const char*) { return reinterpret_cast<void*>(1); }
int output(const char* line) { lines.emplace_back(line); errno = EIO; lastError = 93; return 0; }
void* GetProcAddress(HMODULE, const char*) { return reinterpret_cast<void*>(output); }
namespace dxvk {
constexpr uint32_t DxvkVulkanApiVersion = 4206592, VK_QUEUE_FAMILY_IGNORED = ~0u;
constexpr uint32_t MaxTotalPushDataSize = 256, VK_PHYSICAL_DEVICE_TYPE_CPU = 4;
namespace str { template<typename... T> std::string format(const T&... args) {
  std::ostringstream out; (out << ... << args); return out.str();
} }
struct Logger { static void info(const std::string&) {} };
struct Extension { uint32_t specVersion = 1; const char* extensionName = "VK_EXT_test"; };
struct Properties {
  struct { struct { uint32_t apiVersion = DxvkVulkanApiVersion;
    struct { uint32_t maxPushConstantsSize = MaxTotalPushDataSize; } limits;
    const char* deviceName = "GPU"; uint32_t deviceType = 2;
  } properties; } core;
  struct { const char* driverName = "Driver"; } vk12;
  struct { std::array<uint8_t, 16> deviceUUID {}; } vk11;
  struct { std::string toString() const { return "1"; } } driverVersion;
};
struct DxvkDeviceCapabilities {
  Properties m_properties;
  struct { struct { uint32_t family = 0; } graphics; } m_queueMapping;
  struct FeatureEntry { Extension* extensionSupported; Extension* extensionEnabled;
    uint32_t* featureSupported; uint32_t* featureEnabled; bool featureRequired; const char* readableName; };
  uint32_t supported[3] = {1, 0, 1}, active[3] = {1, 0, 1};
  Extension extSupported, extEnabled;
  std::array<FeatureEntry, 3> getFeatureList() {
    return {{{nullptr, nullptr, &supported[0], &active[0], true, "coreFeature"},
      {nullptr, nullptr, &supported[1], &active[1], false, "optionalFeature"},
      {&extSupported, &extEnabled, &supported[2], &active[2], true, "extFeature"}}};
  }
  std::optional<std::string> checkDeviceCompatibility();
};
enum class DxvkDeviceFilterFlag { MatchDeviceName = 1, MatchDeviceUUID = 2, SkipCpuDevices = 4 };
struct Flags { unsigned bits = 0; bool test(DxvkDeviceFilterFlag f) const { return bits & unsigned(f); } };
struct DxvkAdapter {
  Properties props; bool compatible = true; unsigned checks = 0;
  const Properties& deviceProperties() { return props; }
  bool isCompatible(std::string& error) { ++checks; error = "incompatible"; return compatible; }
};
std::string convertUUID(const std::array<uint8_t, 16>&) { return "00112233"; }
struct DxvkDeviceFilter {
  Flags m_flags; std::string m_matchDeviceName, m_matchDeviceUUID;
  bool testAdapter(DxvkAdapter& adapter) const;
};
}
#include "decisions.h"
int main(int argc, char** argv) {
  assert(argc == 3); enabled = std::string(argv[1]) == "on";
  bool traced = enabled && std::string(argv[2]) == "patched";
  auto check = [&](dxvk::DxvkDeviceCapabilities& caps, const std::string& error, const std::string& stage) {
    lines.clear(); errno = EDOM; lastError = 51;
    auto result = caps.checkDeviceCompatibility();
    assert(result.value_or("") == error && errno == EDOM && lastError == 51);
    assert(lines.size() == (traced && !stage.empty() ? 1u : 0u));
    if (!lines.empty()) assert(lines[0] == "AURA_DXVK_INIT_V1 pid=123; stage=" + stage + "\n");
  };
  dxvk::DxvkDeviceCapabilities c;
  check(c, "", ""); // Missing optional feature remains permitted.
  c.m_properties.core.properties.apiVersion = 4202496;
  check(c, "Device does not support Vulkan 1.3", "REJECT_API; result=4206592; value=4202496");
  c.m_properties.core.properties.apiVersion = dxvk::DxvkVulkanApiVersion;
  c.m_queueMapping.graphics.family = dxvk::VK_QUEUE_FAMILY_IGNORED;
  check(c, "Device does not have a graphics queue", "REJECT_GRAPHICS_QUEUE; result=0; value=0");
  c.m_queueMapping.graphics.family = 0;
  c.active[0] = c.supported[0] = 0;
  check(c, "Device does not support required feature 'coreFeature'", "REJECT_FEATURE; result=0; value=1");
  c.supported[0] = 1;
  check(c, "Device does not support required feature 'coreFeature'", "REJECT_FEATURE; result=1; value=1");
  c.active[0] = 1; c.active[2] = c.supported[2] = 0;
  c.extSupported.specVersion = c.extEnabled.specVersion = 0;
  check(c, "Device does not support required feature 'extFeature' (extension: VK_EXT_test)", "REJECT_FEATURE; result=2; value=3");
  c.supported[2] = c.extSupported.specVersion = 1;
  check(c, "Device does not support required feature 'extFeature' (extension: VK_EXT_test)", "REJECT_FEATURE; result=7; value=3");
  c.extEnabled.specVersion = 1;
  check(c, "Device does not support required feature 'extFeature' (extension: VK_EXT_test)", "REJECT_FEATURE; result=15; value=3");
  c.active[2] = 1; c.m_properties.core.properties.limits.maxPushConstantsSize = 128;
  check(c, "Device does not support 256 of push data", "REJECT_PUSH_CONSTANTS; result=256; value=128");
  auto filter = [&](unsigned flags, bool compatible, const std::string& name, const std::string& uuid,
                    uint32_t type, bool expected, const std::string& stage) {
    dxvk::DxvkDeviceFilter f {{flags}, name, uuid}; dxvk::DxvkAdapter a;
    a.compatible = compatible; a.props.core.properties.deviceType = type; lines.clear();
    errno = EDOM; lastError = 51;
    assert(f.testAdapter(a) == expected && a.checks == 1 && errno == EDOM && lastError == 51);
    assert(lines.size() == (traced && !stage.empty() ? 1u : 0u));
    if (!lines.empty()) assert(lines[0] == "AURA_DXVK_INIT_V1 pid=123; stage=" + stage + "; result=0; value=0\n");
  };
  filter(0, true, "", "", 2, true, "");
  filter(0, false, "", "", 2, false, "");
  filter(1, true, "other", "", 2, false, "REJECT_NAME_FILTER");
  filter(2, true, "", "other", 2, false, "REJECT_UUID_FILTER");
  filter(4, true, "", "", 4, false, "REJECT_SOFTWARE_DEVICE");
  filter(3, true, "GP", "1122", 2, true, "");
  std::cout << "15_scenarios_same_decisions_errors_and_single_compatibility_query\n";
}
