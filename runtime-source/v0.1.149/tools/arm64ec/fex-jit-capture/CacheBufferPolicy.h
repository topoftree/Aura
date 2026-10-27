// SPDX-License-Identifier: MIT
#pragma once
#include "SharedSmcPolicy.h"
#include <FEXCore/Config/Config.h>
#include <cstdlib>

namespace FEX::Windows {
inline void ConfigureCacheBuffer(std::string_view ExecutablePath) {
  const char* Bytes = getenv("AURA_FEX_CACHE_BUFFER_BYTES");
  if (!Bytes || getenv("FEX_DISKCACHEMEMORYSIZE") ||
      !SharedSmcExitsRequested("1", getenv("AURA_FEX_CACHE_BUFFER_EXE"), ExecutablePath)) return;
  const std::string_view Request {Bytes};
  if (Request != "33554432" && Request != "67108864") return;
  FEX_CONFIG_OPT(Enabled, DISKCACHE);
  if (Enabled()) FEXCore::Config::Set(FEXCore::Config::CONFIG_DISKCACHEMEMORYSIZE, Bytes);
}
}
