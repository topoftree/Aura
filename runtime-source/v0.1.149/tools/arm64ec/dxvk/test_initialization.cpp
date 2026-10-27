// Host regression of the actual patched Singleton/Rc and opt-in trace header.
// Android/Wine mutex, loader and clock boundaries are explicit test doubles.
#include <atomic>
#include <cassert>
#include <cerrno>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#define force_inline inline
#include "util_singleton.h"

struct Object {
  static inline std::atomic<unsigned> attempts {0}, destroyed {0};
  std::atomic<unsigned> refs {0};
  explicit Object(bool fail) { ++attempts; if (fail) throw std::runtime_error("injected initialization failure"); }
  void incRef() { ++refs; }
  void decRef() { if (--refs == 0) delete this; }
  ~Object() { ++destroyed; }
};

#define _WIN32 1
#define __cdecl
#include "util_aura_init.h"

static std::string setting;
static std::atomic<unsigned long long> ticks {100};
static thread_local unsigned lastError = 53;
static std::vector<std::string> outputLines;
static std::mutex outputLock;
static bool useWineOutput = true;
std::string dxvk::env::getEnvVar(const char* name) {
  assert(std::string(name) == "AURA_DXVK_INIT_TRACE");
  lastError = 91; errno = ERANGE;
  return setting;
}
ULONGLONG GetTickCount64() { return ticks.load(); }
DWORD GetCurrentProcessId() { return 100; }
DWORD GetLastError() { return lastError; }
void SetLastError(DWORD error) { lastError = error; }
HMODULE GetModuleHandleA(const char* name) {
  assert(std::string(name) == "ntdll.dll");
  lastError = 92; errno = EIO;
  return useWineOutput ? reinterpret_cast<void*>(1) : nullptr;
}
int wineOutput(const char* text) {
  std::lock_guard lock(outputLock);
  outputLines.emplace_back(text);
  lastError = 93; errno = ENOMEM;
  return 0;
}
void* GetProcAddress(HMODULE module, const char* name) {
  assert(module && std::string(name) == "__wine_dbg_output");
  return reinterpret_cast<void*>(wineOutput);
}
static void emit() {
  errno = EDOM; lastError = 53;
  dxvk::auraInitTrace(dxvk::AuraInitStage::CreateInstance, -9, 4206592);
  assert(errno == EDOM && lastError == 53);
}

int main(int argc, char** argv) {
  assert(argc == 2);
  std::string mode = argv[1];
  if (mode == "retry" || mode == "original") {
    dxvk::Singleton<Object> singleton;
    unsigned failures = 0;
    for (unsigned i = 0; i < 3; ++i) {
      try { auto object = singleton.acquire(true); }
      catch (const std::runtime_error&) { ++failures; }
    }
    auto first = singleton.acquire(false);
    if (mode == "original") {
      assert(!first && failures == 1 && Object::attempts == 1);
      std::cout << "original_retry_returns_null\n";
      return 0;
    }
    assert(first && failures == 3 && Object::attempts == 4);
    Object* identity = first.ptr();
    std::vector<std::thread> threads;
    for (unsigned n = 0; n < 8; ++n) threads.emplace_back([&] {
      for (unsigned i = 0; i < 1000; ++i) {
        auto next = singleton.acquire(false);
        assert(next.ptr() == identity);
        singleton.release();
      }
    });
    for (auto& t : threads) t.join();
    assert(Object::attempts == 4 && Object::destroyed == 0);
    singleton.release(); first = nullptr;
    assert(Object::destroyed == 1);
    auto next = singleton.acquire(false);
    assert(next && Object::attempts == 5);
    singleton.release(); next = nullptr;
    assert(Object::destroyed == 2);
    std::cout << "retry_lifetime_and_8000_concurrent_acquisitions_passed\n";
    return 0;
  }
  setting = mode == "disabled" ? "0" : mode == "invalid" ? "1extra" : "1";
  if (mode == "fallback") useWineOutput = false;
  emit();
  if (mode == "disabled" || mode == "invalid") assert(outputLines.empty());
  else if (mode == "fallback") assert(outputLines.empty()); // Runner checks stderr.
  else {
    assert(outputLines.size() == 1);
    assert(outputLines[0] == "AURA_DXVK_INIT_V1 pid=100; stage=CREATE_INSTANCE; result=-9; value=4206592\n");
    if (mode == "timeout") {
      ticks = 120099; emit(); assert(outputLines.size() == 2);
      ticks = 120100; emit(); assert(outputLines.size() == 2);
    } else if (mode == "budget") {
      std::vector<std::thread> threads;
      for (unsigned n = 0; n < 8; ++n) threads.emplace_back([] { for (int i = 0; i < 200; ++i) emit(); });
      for (auto& t : threads) t.join();
      assert(outputLines.size() == 64);
    } else assert(mode == "enabled");
  }
  std::cout << mode << "_passed\n";
}
