// Real header and extracted DXGI functions; only Windows/WSI boundaries are doubles.
#include <algorithm>
#include <cassert>
#include <cerrno>
#include <iostream>
#include <limits>
#include <mutex>
#include <numeric>
#include <set>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include "dxgi/dxgi_aura_display.h"

static std::string setting = "1", executable = "BF3.EXE";
static std::atomic<ULONGLONG> ticks {100};
static thread_local DWORD lastError = 53;
static std::atomic<DWORD> nextThread {1000};
static thread_local DWORD thread = nextThread++;
static std::vector<std::string> rows;
static std::mutex outputLock;
static bool wineOutputAvailable = true, gateThrows = false;
std::string dxvk::env::getEnvVar(const char* name) {
  assert(std::string(name) == "AURA_BF3_DISPLAY_TRACE");
  lastError = 91; errno = ERANGE;
  if (gateThrows) throw std::runtime_error("unavailable");
  return setting;
}
std::string dxvk::env::getExeName() { lastError = 92; errno = EIO; return executable; }
DWORD GetLastError() { return lastError; }
void SetLastError(DWORD value) { lastError = value; }
DWORD GetCurrentProcessId() { return 100; }
DWORD GetCurrentThreadId() { return thread; }
ULONGLONG GetTickCount64() { return ticks; }
int output(const char* line) {
  std::lock_guard lock(outputLock); rows.emplace_back(line);
  lastError = 93; errno = ENOMEM;
  return 0;
}
HMODULE GetModuleHandleA(const char* name) {
  assert(std::string(name) == "ntdll.dll");
  return wineOutputAvailable ? reinterpret_cast<void*>(1) : nullptr;
}
void* GetProcAddress(HMODULE module, const char* name) {
  assert(module && std::string(name) == "__wine_dbg_output");
  return reinterpret_cast<void*>(output);
}

using namespace dxvk;
using Call = AuraDisplayCall;
using Stage = AuraDisplayStage;
static void checkErrors() { assert(lastError == 53 && errno == EDOM); }
static void request() {
  lastError = 53; errno = EDOM;
  {
    AuraDisplayTrace trace(Call::ResizeBuffers);
    checkErrors();
    trace.resize(2, 1950, 900, 28, 2);
    checkErrors();
    assert(trace.result(DXGI_ERROR_INVALID_CALL) == DXGI_ERROR_INVALID_CALL);
    checkErrors();
  }
  checkErrors();
}
static unsigned field(const std::string& row, const std::string& name) {
  auto offset = row.find(name + "="); assert(offset != std::string::npos);
  return unsigned(std::stoul(row.substr(offset + name.size() + 1)));
}
static bool has(const std::string& text) {
  return std::any_of(rows.begin(), rows.end(), [&](const auto& row) { return row.find(text) != std::string::npos; });
}

// Minimal test boundary around the actual closest-mode implementation.
static unsigned currentCalls = 0, listCalls = 0;
static std::vector<DXGI_MODE_DESC1> available;
static DXGI_MODE_DESC1 active;
namespace dxvk {
  struct DxgiOptions { UINT forceRefreshRate = 0; };
  struct Factory { DxgiOptions options; const DxgiOptions* GetOptions() { return &options; } };
  struct Logger {
    template<typename... T> static void err(T&&...) { }
    template<typename... T> static void debug(T&&...) { }
  };
  namespace str { template<typename... T> std::string format(T&&...) { return {}; } }
  namespace wsi {
    using WsiMode = DXGI_MODE_DESC1;
    bool getCurrentDisplayMode(int, WsiMode* out) { ++currentCalls; *out = active; return true; }
  }
  class DxgiOutput {
  public:
    Factory factory;
    Factory* m_factory = &factory;
    int m_monitor = 0;
    HRESULT FindClosestMatchingMode(const DXGI_MODE_DESC*, DXGI_MODE_DESC*, IUnknown*);
    HRESULT FindClosestMatchingMode1(const DXGI_MODE_DESC1*, DXGI_MODE_DESC1*, IUnknown*);
    static void FilterModesByDesc(std::vector<DXGI_MODE_DESC1>&, const DXGI_MODE_DESC1&);
    DXGI_MODE_DESC1 ConvertDisplayMode(const wsi::WsiMode& mode) { return mode; }
    HRESULT GetDisplayModeList1(DXGI_FORMAT, UINT, UINT* count, DXGI_MODE_DESC1* out) {
      ++listCalls;
      *count = available.size();
      if (out) std::copy(available.begin(), available.end(), out);
      return S_OK;
    }
  };
}
#include "display_functions.h"

static DXGI_MODE_DESC1 mode(UINT w, UINT h, UINT hz=60) {
  DXGI_MODE_DESC1 result;
  result.Width = w; result.Height = h; result.RefreshRate = {hz, 1}; result.Format = 28;
  return result;
}
static void api() {
  active = mode(1950, 900, 120);
  available = {mode(960, 540), mode(1280, 720), mode(1950, 900), active};
  DxgiOutput out;
  auto check = [&](DXGI_MODE_DESC1 request, HRESULT expected, UINT w, UINT h, UINT hz) {
    DXGI_MODE_DESC1 selected;
    currentCalls = listCalls = 0;
    auto hr = out.FindClosestMatchingMode1(&request, &selected, nullptr);
    assert(hr == expected);
    if (!FAILED(hr)) assert(selected.Width == w && selected.Height == h && selected.RefreshRate.Numerator == hz);
    std::cout << hr << ':' << selected.Width << 'x' << selected.Height << '@' << selected.RefreshRate.Numerator
              << ':' << currentCalls << '/' << listCalls << ';';
  };
  check(mode(1950,900), S_OK,1950,900,60);
  check(mode(1170,540), S_OK,960,540,60);
  check(mode(0,0,0), S_OK,1950,900,120);
  check(mode(0,900), DXGI_ERROR_INVALID_CALL,0,0,0);
  auto unknown = mode(1950,900); unknown.Format = 0;
  check(unknown,DXGI_ERROR_INVALID_CALL,0,0,0);
  out.factory.options.forceRefreshRate = 120;
  check(mode(1950,900),S_OK,1950,900,120);
  DXGI_MODE_DESC legacy = mode(1170,540), matched;
  assert(out.FindClosestMatchingMode(&legacy, &matched, nullptr) == S_OK && matched.Width == 960);
  assert(out.FindClosestMatchingMode(nullptr, &matched, nullptr) == DXGI_ERROR_INVALID_CALL);
  available.clear(); check(mode(1950,900),DXGI_ERROR_NOT_FOUND,0,0,0);
  std::cout << '\n';
}

int main(int argc, char** argv) {
  assert(argc == 2); std::string test = argv[1];
  if (test == "api-off") { setting="0"; api(); assert(rows.empty()); return 0; }
  if (test == "api-on") { api(); return 0; }
  if (test == "disabled") setting="0";
  if (test == "invalid-flag") setting="true";
  if (test == "other-exe") executable="steam.exe";
  if (test == "gate-error") gateThrows=true;
  if (test == "fallback") wineOutputAvailable=false;
  if (test == "nested") {
    {
      AuraDisplayTrace outer(Call::ResizeTarget, 7);
      auto desc=mode(1170,540); outer.mode(Stage::Request,&desc);
      { AuraDisplayTrace inner(Call::Closest1); desc=mode(960,540); inner.mode(Stage::Matched,&desc); inner.result(S_OK); }
      outer.result(S_OK);
    }
    assert(rows.size()==6 && field(rows[0],"object")==7);
    assert(field(rows[2],"parent")==field(rows[0],"call"));
    assert(field(rows[5],"call")==field(rows[0],"call"));
    assert(has("width=1170; height=540;") && has("width=960; height=540;"));
    request(); assert(field(rows[6],"parent")==0);
  } else if (test == "unwound") {
    try { AuraDisplayTrace trace(Call::CreateBase); throw std::runtime_error("synthetic"); } catch (...) { }
    assert(rows.size()==2 && has("stage=UNWOUND;") && !has("stage=RETURN;"));
  } else if (test == "budget-concurrent") {
    std::vector<std::thread> threads;
    for(unsigned i=0;i<8;++i) threads.emplace_back([] { for(unsigned j=0;j<1000;++j) request(); });
    for(auto& t:threads) t.join();
    assert(rows.size()==384 && has("budget_last=1"));
    std::set<unsigned> sequence;
    for(const auto& r:rows) { assert(field(r,"parent")==0); sequence.insert(field(r,"seq")); }
    assert(sequence.size()==384 && *sequence.begin()==1 && *sequence.rbegin()==384);
  } else if (test == "timeout") {
    request(); assert(rows.size()==3); ticks=120100; request(); assert(rows.size()==3);
  } else if (test == "maximum-fields") {
    AuraDisplayTrace trace(Call::Fullscreen, UINT32_MAX);
    DXGI_SWAP_CHAIN_DESC1 desc; desc.Width=desc.Height=desc.Format=desc.Stereo=desc.Flags=desc.BufferCount=UINT32_MAX;
    DXGI_SWAP_CHAIN_FULLSCREEN_DESC fs; fs.RefreshRate={UINT32_MAX,UINT32_MAX}; fs.ScanlineOrdering=fs.Scaling=UINT32_MAX; fs.Windowed=-1;
    trace.swap(Stage::Request,desc,&fs); trace.result(INT32_MIN);
    assert(has("width=4294967295;") && has("result=-2147483648;"));
  } else {
    request(); assert(rows.empty());
  }
  for (const auto& row:rows) {
    assert(row.size()<640 && row.back()=='\n' && row.find("budget_last=")!=std::string::npos);
    assert(row.find("pid=100;")!=std::string::npos && row.find("BF3.EXE")==std::string::npos);
  }
  std::cout << "PASS " << test << " rows=" << rows.size() << '\n';
}
