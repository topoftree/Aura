// SPDX-License-Identifier: MIT
#pragma once
#include "aura_pipeline_core.h"
#include <windows.h>
#include <atomic>
#include <mutex>

namespace aura_pipeline {
inline uint32_t load(uint32_t* p) { return uint32_t(InterlockedCompareExchange(reinterpret_cast<volatile LONG*>(p), 0, 0)); }
inline uint32_t add(uint32_t* p) { return uint32_t(InterlockedIncrement(reinterpret_cast<volatile LONG*>(p))); }
inline bool cas(uint32_t* p, uint32_t before, uint32_t after) {
  return uint32_t(InterlockedCompareExchange(reinterpret_cast<volatile LONG*>(p), LONG(after), LONG(before))) == before;
}
inline uint64_t utcMs() {
  FILETIME t; GetSystemTimeAsFileTime(&t);
  return ((uint64_t(t.dwHighDateTime) << 32) | t.dwLowDateTime) / 10000 - 11644473600000ull;
}
class Trace {
  Control* c = nullptr; Record* slots = nullptr; uint64_t start = 0;
  struct Entry { VkDevice device {}; VkShaderModule module {}; Shader meta {}; };
  std::array<Entry, 256> shaders {}; unsigned next = 0; std::mutex mutex;
  static HANDLE open(const wchar_t* directory, const wchar_t* name, DWORD bytes) {
    wchar_t path[1024]; size_t n = wcslen(directory), m = wcslen(name);
    if (n + m + 2 >= 1024) return INVALID_HANDLE_VALUE;
    memcpy(path, directory, n * sizeof(wchar_t)); path[n++] = L'\\';
    memcpy(path + n, name, (m + 1) * sizeof(wchar_t));
    HANDLE f = CreateFileW(path, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
      nullptr, OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
    BY_HANDLE_FILE_INFORMATION i {};
    if (f != INVALID_HANDLE_VALUE && GetFileType(f) == FILE_TYPE_DISK && GetFileInformationByHandle(f, &i) &&
        !(i.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) &&
        i.nNumberOfLinks == 1 && !i.nFileSizeHigh && i.nFileSizeLow == bytes) return f;
    if (f != INVALID_HANDLE_VALUE) CloseHandle(f);
    return INVALID_HANDLE_VALUE;
  }
  static void* map(HANDLE file, DWORD bytes) {
    if (file == INVALID_HANDLE_VALUE) return nullptr;
    HANDLE mapping = CreateFileMappingW(file, nullptr, PAGE_READWRITE, 0, bytes, nullptr); CloseHandle(file);
    if (!mapping) return nullptr;
    void* p = MapViewOfFile(mapping, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, bytes); CloseHandle(mapping); return p;
  }
public:
  Trace() {
    DWORD error = GetLastError();
    wchar_t dir[900]; DWORD length = GetEnvironmentVariableW(L"AURA_STARTUP_PIPELINE_DIRECTORY", dir, 900);
    if (length && length < 900 && dir[0] == L'Z' && dir[1] == L':' && dir[2] == L'\\') {
      c = static_cast<Control*>(map(open(dir, L".pipeline-control", sizeof(Control)), sizeof(Control)));
      if (c && !memcmp(c->magic, "AURAVP1", 8) && c->version == 1 && c->slots == Slots) {
        slots = static_cast<Record*>(map(open(dir, L".pipeline-slots", Slots * RecordBytes), Slots * RecordBytes));
        start = GetTickCount64(); if (active()) add(&c->ready);
      }
      if (!slots && c) { UnmapViewOfFile(c); c = nullptr; }
    }
    SetLastError(error);
  }
  bool active() {
    if (!c || !slots || load(&c->enabled) != 1) return false;
    uint64_t now = utcMs(), local = GetTickCount64();
    return now < c->deadlineUtcMs && c->deadlineUtcMs - now <= 120000 && local >= start && local - start < 120000;
  }
  bool canBegin() {
    if (!active()) return false;
    if (load(&c->calls) < MaxCalls) return true;
    cas(&c->exhausted, 0, 1); return false;
  }
  Shader scan(const VkShaderModuleCreateInfo* p) {
    if (!p) return {};
    uint32_t size = uint32_t(std::min<size_t>(p->codeSize, 16384)), old = load(&c->scanBytes);
    for (;;) {
      if (old > 4*1024*1024 || size > 4*1024*1024 - old) return summarize(p, false);
      if (cas(&c->scanBytes, old, old + size)) return summarize(p, true);
      old = load(&c->scanBytes);
    }
  }
  bool lookup(VkDevice device, VkShaderModule module, Shader& out) {
    std::unique_lock<std::mutex> lock(mutex, std::try_to_lock);
    if (!lock) return false;
    for (const auto& entry : shaders) if (entry.device == device && entry.module == module) { out = entry.meta; return true; }
    return false;
  }
  void remember(VkDevice device, VkShaderModule module, const Shader& meta) {
    if (!module || !active()) return;
    std::unique_lock<std::mutex> lock(mutex, std::try_to_lock); if (!lock) return;
    for (auto& entry : shaders) if (entry.device == device && entry.module == module) { entry.meta = meta; return; }
    shaders[next++ % shaders.size()] = {device, module, meta};
  }
  void forget(VkDevice device, VkShaderModule module) {
    std::lock_guard<std::mutex> lock(mutex);
    for (auto& entry : shaders) if (entry.device == device && entry.module == module) entry = {};
  }
  uint32_t begin(Record& r) {
    if (!active()) return 0;
    uint32_t count = load(&c->calls);
    for (;;) {
      if (count >= MaxCalls) { cas(&c->exhausted, 0, 1); return 0; }
      if (cas(&c->calls, count, count + 1)) break;
      count = load(&c->calls);
    }
    r.call = count + 1; r.pid = GetCurrentProcessId(); r.tid = GetCurrentThreadId(); r.state = 1;
    Record* target = &slots[count % Slots]; uint32_t seq = load(&target->sequence);
    if ((seq & 1) || !cas(&target->sequence, seq, seq + 1)) { add(&c->dropped); return 0; }
    memcpy(reinterpret_cast<char*>(target) + 4, reinterpret_cast<char*>(&r) + 4, sizeof(r) - 4);
    InterlockedExchange(reinterpret_cast<volatile LONG*>(&target->sequence), LONG(seq + 2));
    return r.call;
  }
  void end(uint32_t call, VkResult result) {
    if (!call || !active()) return;
    Record* r = &slots[(call - 1) % Slots]; uint32_t seq = load(&r->sequence);
    if ((seq & 1) || !cas(&r->sequence, seq, seq + 1)) { add(&c->dropped); return; }
    if (r->call == call && r->pid == GetCurrentProcessId() && r->tid == GetCurrentThreadId()) {
      r->result = uint32_t(result); r->state = 2;
    } else add(&c->overwritten);
    InterlockedExchange(reinterpret_cast<volatile LONG*>(&r->sequence), LONG(seq + 2));
  }
};
inline Trace& trace() { static Trace t; return t; }
inline VkResult createShader(PFN_vkCreateShaderModule fn, VkDevice d, const VkShaderModuleCreateInfo* p,
    const VkAllocationCallbacks* a, VkShaderModule* out) {
  DWORD before = GetLastError(); Trace& t = trace(); uint32_t call = 0; Shader meta {};
  if (t.canBegin()) { Record r {}; r.kind = 1; r.used = 32;
    if (p) { r.fields[0] = p->flags; chain(p->pNext, r.fields + 1); meta = t.scan(p);
      std::copy(meta.begin(), meta.end(), r.fields + 12); }
    call = t.begin(r);
  }
  SetLastError(before); VkResult result = fn(d,p,a,out); DWORD after = GetLastError();
  if (call) { t.end(call,result); if (result == VK_SUCCESS && out) t.remember(d,*out,meta); }
  SetLastError(after); return result;
}
inline void destroyShader(PFN_vkDestroyShaderModule fn, VkDevice d, VkShaderModule module, const VkAllocationCallbacks* a) {
  DWORD before = GetLastError(); Trace& t = trace(); if (t.active()) t.forget(d,module);
  SetLastError(before); fn(d,module,a);
}
inline VkResult createGraphics(PFN_vkCreateGraphicsPipelines fn, VkDevice d, VkPipelineCache cache, uint32_t n,
    const VkGraphicsPipelineCreateInfo* p, const VkAllocationCallbacks* a, VkPipeline* out) {
  DWORD before = GetLastError(); Trace& t = trace(); uint32_t call = 0;
  if (t.canBegin()) { auto r = graphics(n,p,[&](VkShaderModule m, Shader& s){return t.lookup(d,m,s);},
      [&](const VkShaderModuleCreateInfo* s){return t.scan(s);}); call = t.begin(r); }
  SetLastError(before); VkResult result = fn(d,cache,n,p,a,out); DWORD after = GetLastError();
  t.end(call,result); SetLastError(after); return result;
}
inline VkResult createCompute(PFN_vkCreateComputePipelines fn, VkDevice d, VkPipelineCache cache, uint32_t n,
    const VkComputePipelineCreateInfo* p, const VkAllocationCallbacks* a, VkPipeline* out) {
  DWORD before = GetLastError(); Trace& t = trace(); uint32_t call = 0;
  if (t.canBegin()) { Record r {}; r.kind = 3; r.used = 92; r.fields[0] = n;
    if (p && n) { r.fields[1] = p->flags; chain(p->pNext,r.fields+2); r.fields[13] = p->layout != VK_NULL_HANDLE;
      r.fields[57] = n > 1; stage(p->stage,r.fields+64,[&](VkShaderModule m, Shader& s){return t.lookup(d,m,s);},
        [&](const VkShaderModuleCreateInfo* s){return t.scan(s);}); }
    call = t.begin(r);
  }
  SetLastError(before); VkResult result = fn(d,cache,n,p,a,out); DWORD after = GetLastError();
  t.end(call,result); SetLastError(after); return result;
}
}
