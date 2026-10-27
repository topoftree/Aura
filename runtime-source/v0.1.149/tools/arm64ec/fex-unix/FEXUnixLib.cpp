// SPDX-License-Identifier: MIT
#include <cstdint>
#include <type_traits>
#include <fcntl.h>
#include <string>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <unistd.h>
#include <cstdlib>
#include <cstdio>
#include <cerrno>
#include <atomic>
#include <sys/auxv.h>
#include "HardwareTso.h"

// Android has no POSIX shm_open. Keep optional FEX statistics in Aura's private
// TMPDIR, with the same per-process filename for growth and cleanup.
static int aura_shm_open(const char *name, int flags, mode_t mode) {
  const char *dir = getenv("TMPDIR");
  if (!dir || dir[0] != '/') { errno = EINVAL; return -1; }
  std::string path = std::string(dir) + "/" + name;
  return open(path.c_str(), flags | O_CLOEXEC | O_NOFOLLOW, mode & 0600);
}
static int aura_shm_unlink(const char *name) {
  const char *dir = getenv("TMPDIR");
  if (!dir || dir[0] != '/') { errno = EINVAL; return -1; }
  return unlink((std::string(dir) + "/" + name).c_str());
}

#include "FEXUnixLib.h"

// Equivalent to C++23's std::to_underlying.
template<typename Enum>
[[nodiscard]]
constexpr std::underlying_type_t<Enum> ToUnderlying(Enum e) noexcept {
  return static_cast<std::underlying_type_t<Enum>>(e);
}

using NTSTATUS = int32_t;
using unixlib_entry_t = NTSTATUS (*)(void*);

constexpr NTSTATUS STATUS_SUCCESS = 0;
constexpr NTSTATUS STATUS_NOT_SUPPORTED = 0xC00000BB;
constexpr NTSTATUS STATUS_INTERNAL_ERROR = 0xC00000E5;

#ifndef PR_GET_MEM_MODEL
#define PR_GET_MEM_MODEL 0x6d4d444c
#endif
#ifndef PR_SET_MEM_MODEL
#define PR_SET_MEM_MODEL 0x4d4d444c
#endif
#ifndef PR_SET_MEM_MODEL_DEFAULT
#define PR_SET_MEM_MODEL_DEFAULT 0
#endif
#ifndef PR_SET_MEM_MODEL_TSO
#define PR_SET_MEM_MODEL_TSO 1
#endif
#ifndef PR_ARM64_SET_UNALIGN_ATOMIC
#define PR_ARM64_SET_UNALIGN_ATOMIC 0x46455849
#endif
#ifndef PR_SET_VMA
#define PR_SET_VMA 0x53564d41
#endif
#ifndef PR_SET_VMA_ANON_NAME
#define PR_SET_VMA_ANON_NAME 0
#endif

static NTSTATUS FEXUnixLib_SetHardwareTSOControlHandler(void* _Args) {
  auto Args = reinterpret_cast<const FEXUnixLib_SetHardwareTSOControlArgs*>(_Args);
  if (Args->Enable) {
    const auto result = AuraHardwareTso::enable(getenv("AURA_FEX_HARDWARE_TSO"), [](int op, int model) {
      return prctl(op, static_cast<unsigned long>(model), 0UL, 0UL, 0UL);
    });
    if (result != AuraHardwareTso::Result::Off) {
      // One small fixed result per process, including forked children. It also
      // works without Wine debug logging; no paths, registers or user data.
      static std::atomic<pid_t> reported {0};
      if (reported.exchange(getpid()) != getpid()) {
        fprintf(stderr, "AURA_FEX_HW_TSO_V1 state=%s; scope=FEX_PROCESS\n", AuraHardwareTso::name(result));
      }
    }
    return result == AuraHardwareTso::Result::Enabled ? STATUS_SUCCESS : STATUS_NOT_SUPPORTED;
  }

  prctl(PR_SET_MEM_MODEL, PR_SET_MEM_MODEL_DEFAULT, 0, 0, 0);
  return STATUS_SUCCESS;
}

static NTSTATUS FEXUnixLib_SetKernelUnalignedAtomicControlHandler(void* _Args) {
  auto Args = reinterpret_cast<const FEXUnixLib_SetKernelUnalignedAtomicControl*>(_Args);
  return prctl(PR_ARM64_SET_UNALIGN_ATOMIC, Args->Flags, 0, 0, 0) == 0 ? STATUS_SUCCESS : STATUS_NOT_SUPPORTED;
}

static NTSTATUS FEXUnixLib_MadviseHandler(void* _Args) {
  auto Args = reinterpret_cast<const FEXUnixLib_Madvise*>(_Args);
  return madvise(const_cast<void*>(Args->Addr), Args->Size, Args->Advise) == 0 ? STATUS_SUCCESS : STATUS_INTERNAL_ERROR;
}

static NTSTATUS FEXUnixLib_SetVMANameHandler(void* _Args) {
  auto Args = reinterpret_cast<const FEXUnixLib_SetVMAName*>(_Args);
  return prctl(PR_SET_VMA, PR_SET_VMA_ANON_NAME, reinterpret_cast<unsigned long>(Args->Addr), Args->Size, Args->Name) == 0 ?
           STATUS_SUCCESS :
           STATUS_INTERNAL_ERROR;
}

constexpr static int USER_PERMS = S_IRWXU | S_IRWXG | S_IRWXO;
static inline void* InitializeSHM(uint32_t MapSize, uint32_t MaxSize) {
  void* Base {};
  const std::string Name = "fex-" + std::to_string(getpid()) + "-stats";
  int fd = aura_shm_open(Name.c_str(), O_CREAT | O_TRUNC | O_RDWR, USER_PERMS);

  if (fd == -1) {
    return nullptr;
  }

  if (ftruncate(fd, MapSize) == -1) {
    goto err;
  }

  // Reserve a region of MAX_STATS_SIZE so we can grow the allocation buffer.
  // Number of thread slots when ThreadStatsHeader == 64bytes and ThreadStats == 40bytes:
  // 1 page: 99 slots
  // 1 MB: 26211 slots
  // 128 MB: 3355440 slots
  Base = mmap(nullptr, MaxSize, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
  if (Base == MAP_FAILED) {
    Base = nullptr;
    goto err;
  }

  prctl(PR_SET_VMA, PR_SET_VMA_ANON_NAME, reinterpret_cast<void*>(Base), MaxSize, "FEXMem_Misc");

  // Allocate a small working shared space for now, grow as necessary.
  {
    auto SharedBase = mmap(Base, MapSize, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, fd, 0);
    if (SharedBase == MAP_FAILED) {
      ::munmap(Base, MaxSize);
      Base = nullptr;
      goto err;
    }
  }

err:
  close(fd);

  return Base;
}

static inline bool AllocateMoreSlots(void* SHMBase, uint32_t NewSize) {
  bool Result {};
  const std::string Name = "fex-" + std::to_string(getpid()) + "-stats";

  // When allocating more slots, open the fd without O_TRUNC | O_CREAT.
  int fd = aura_shm_open(Name.c_str(), O_RDWR, USER_PERMS);
  if (fd == -1) {
    return false;
  }

  if (ftruncate(fd, NewSize) == -1) {
    goto err;
  }

  {
    auto SharedBase = mmap(SHMBase, NewSize, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, fd, 0);
    if (SharedBase == MAP_FAILED) {
      goto err;
    }
  }

  Result = true;

err:
  close(fd);
  return Result;
}

static NTSTATUS FEXUnixLib_GetSHMStatsVMAHandler(void* _Args) {
  auto Args = reinterpret_cast<FEXUnixLib_GetSHMStatsVMA*>(_Args);
  if (Args->SHMBase == nullptr) {
    Args->SHMBase = InitializeSHM(Args->MapSize, Args->MaxSize);
    return Args->SHMBase ? STATUS_SUCCESS : STATUS_INTERNAL_ERROR;
  }

  return AllocateMoreSlots(Args->SHMBase, Args->MapSize) ? STATUS_SUCCESS : STATUS_INTERNAL_ERROR;
}

static NTSTATUS FEXUnixLib_DeleteSHMStatsFileHandler(void* _Args) {
  const std::string Name = "fex-" + std::to_string(getpid()) + "-stats";
  aura_shm_unlink(Name.c_str());
  return STATUS_SUCCESS;
}

static NTSTATUS FEXUnixLib_MapFileHandler(void* _Args) {
  auto Args = reinterpret_cast<FEXUnixLib_MapFile*>(_Args);
  // could find a way to dedupe with DiskCacheFilemapper in FEXInterpreter but it's barely any code
  // fd was already duped by Wine handle translation
  Args->Result = mmap(nullptr, Args->MapSize, PROT_READ, MAP_SHARED | MAP_NORESERVE, Args->FD, 0);
  close(Args->FD);
  if (Args->Result == (void*)-1) {
    Args->Result = nullptr;
    return STATUS_INTERNAL_ERROR;
  }
  return STATUS_SUCCESS;
}

static NTSTATUS FEXUnixLib_GetPIDHandler(void* _Args) {
  auto Args = reinterpret_cast<FEXUnixLib_GetPID*>(_Args);
  Args->Result = ::getpid();
  return STATUS_SUCCESS;
}
static NTSTATUS FEXUnixLib_GetCpuAuditHandler(void* _Args) {
  auto Args = reinterpret_cast<FEXUnixLib_GetCpuAudit*>(_Args);
  *Args = {};
  const int SavedErrno = errno;
  errno = 0;
  const auto H1 = getauxval(AT_HWCAP);
  if (!errno) {
    Args->Known |= 1 | 2 | 4 | 16 | 64;
    Args->Bits |= ((H1 >> 8) & 1) | (((H1 >> 15) & 1) << 1) | (((H1 >> 26) & 1) << 2) |
                  (((H1 >> 22) & 1) << 4) | (((H1 >> 20) & 1) << 6);
    if (H1 & (1UL << 22)) {
      // PR_SVE_GET_VL: query only. Never enables SVE or changes a thread's vector length.
      const int VL = prctl(51, 0UL, 0UL, 0UL, 0UL);
      if (VL >= 0) Args->SveVectorBits = (VL & 0xffff) * 8;
    }
  }
  errno = 0;
  const auto H2 = getauxval(AT_HWCAP2);
  if (!errno) {
    Args->Known |= 8 | 32;
    Args->Bits |= (((H2 >> 1) & 1) << 3) | (((H2 >> 13) & 1) << 5);
  }
  errno = SavedErrno;
  return STATUS_SUCCESS;
}
extern "C" const unixlib_entry_t __wine_unix_call_funcs[] = {
  FEXUnixLib_SetHardwareTSOControlHandler,
  FEXUnixLib_SetKernelUnalignedAtomicControlHandler,
  FEXUnixLib_MadviseHandler,
  FEXUnixLib_SetVMANameHandler,
  FEXUnixLib_GetSHMStatsVMAHandler,
  FEXUnixLib_DeleteSHMStatsFileHandler,
  FEXUnixLib_MapFileHandler,
  FEXUnixLib_GetPIDHandler,
  FEXUnixLib_GetCpuAuditHandler,
};

static_assert(sizeof(__wine_unix_call_funcs) / sizeof(__wine_unix_call_funcs[0]) == ToUnderlying(FEXUnixLibFunctions::MAX));
