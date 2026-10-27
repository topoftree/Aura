// SPDX-License-Identifier: MIT
#pragma once
#include <cerrno>
#include <cstring>

namespace AuraHardwareTso {
constexpr int GetModel = 0x6d4d444c;
constexpr int SetModel = 0x4d4d444c;
constexpr int Default = 0;
constexpr int Tso = 1;
enum class Result { Off, Enabled, Unsupported, Denied, Failed };

inline Result failure(int error) {
  if (error == EINVAL || error == ENOSYS || error == EOPNOTSUPP) return Result::Unsupported;
  if (error == EPERM || error == EACCES) return Result::Denied;
  return Result::Failed;
}

// Never turn off FEX's software TSO. FEX accepts hardware support only after a
// successful kernel query, or a set followed by readback in this Wine process.
template<typename Control>
Result enable(const char* requested, Control control) {
  if (!requested || std::strcmp(requested, "1")) return Result::Off;
  int current = control(GetModel, 0);
  if (current == Tso) return Result::Enabled;
  if (current < 0) return failure(errno);
  if (current != Default) return Result::Unsupported;
  if (control(SetModel, Tso) < 0) return failure(errno);
  return control(GetModel, 0) == Tso ? Result::Enabled : Result::Failed;
}

inline const char* name(Result result) {
  switch (result) {
    case Result::Enabled: return "ENABLED";
    case Result::Unsupported: return "UNSUPPORTED";
    case Result::Denied: return "DENIED";
    case Result::Failed: return "FAILED";
    default: return "OFF";
  }
}
}
