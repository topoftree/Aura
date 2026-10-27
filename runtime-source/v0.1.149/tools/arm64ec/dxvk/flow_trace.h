#pragma once
#include <windows.h>
#include "aura_flow_core.h"

namespace aura_flow {
// The host creates a fresh session inode. It is only mapped, never created or resized here.
inline Data* mapping() noexcept {
  static Data* data = []() noexcept -> Data* {
    DWORD saved = GetLastError(); Data* result = nullptr;
    wchar_t path[1024] = {}, target[512] = {}, executable[1024] = {};
    DWORD p = GetEnvironmentVariableW(L"AURA_DXVK_FLOW_PATH", path, 1024);
    DWORD n = GetEnvironmentVariableW(L"AURA_DXVK_FLOW_TARGET", target, 512);
    DWORD e = GetModuleFileNameW(nullptr, executable, 1024);
    if (p > 0 && p < 1024 && n > 0 && n < 512 && e > 0 && e < 1024) {
      const wchar_t* name = executable;
      for (DWORD i = 0; i < e; i++) if (executable[i] == L'\\' || executable[i] == L'/') name = executable + i + 1;
      if (lstrcmpiW(name, target) == 0) {
        HANDLE file = CreateFileW(path, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
            nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (file != INVALID_HANDLE_VALUE) {
          LARGE_INTEGER size;
          if (GetFileSizeEx(file, &size) && size.QuadPart == FileBytes) {
            HANDLE map = CreateFileMappingW(file, nullptr, PAGE_READWRITE, 0, FileBytes, nullptr);
            if (map) {
              auto d = reinterpret_cast<Data*>(MapViewOfFile(map, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, FileBytes));
              if (valid(d) && claim(d->header.ownerPid, 0u, GetCurrentProcessId())) result = d;
              else if (d) UnmapViewOfFile(d);
              CloseHandle(map);
            }
          }
          CloseHandle(file);
        }
      }
    }
    SetLastError(saved);
    // One small mapping per game process; intentionally retained until process exit so late
    // destructors cannot observe an unmapped control page. No handles remain open.
    return result;
  }();
  return data;
}

inline Ticket start(uint32_t reason) noexcept {
  Data* d = mapping();
  if (!d || load(d->header.armed) != 1) return {};
  DWORD saved = GetLastError(); auto& h = d->header;
  uint32_t epoch = load(h.epoch);
  if (epoch && load(h.nativeEpoch) != epoch && claim(h.initializing, 0u, 1u)) {
    LARGE_INTEGER hz = {}, first = {}, last = {}; FILETIME utc = {};
    QueryPerformanceFrequency(&hz); QueryPerformanceCounter(&first);
    GetSystemTimeAsFileTime(&utc); QueryPerformanceCounter(&last);
    initialize(d, epoch, GetCurrentProcessId(), uint64_t(hz.QuadPart), uint64_t(first.QuadPart), uint64_t(last.QuadPart),
        (uint64_t(utc.dwHighDateTime) << 32) | utc.dwLowDateTime);
    store(h.initializing, 0);
  }
  LARGE_INTEGER tick = {}; QueryPerformanceCounter(&tick);
  auto result = begin(d, reason, GetCurrentThreadId(), uint64_t(tick.QuadPart));
  SetLastError(saved); return result;
}
class Scope {
  Ticket ticket;
public:
  explicit Scope(uint32_t reason) noexcept : ticket(start(reason)) {}
  ~Scope() noexcept {
    if (!ticket.data) return;
    DWORD saved = GetLastError(); LARGE_INTEGER now = {}; QueryPerformanceCounter(&now);
    finish(ticket, uint64_t(now.QuadPart)); SetLastError(saved);
  }
  Scope(const Scope&) = delete;
  Scope& operator=(const Scope&) = delete;
};
}
