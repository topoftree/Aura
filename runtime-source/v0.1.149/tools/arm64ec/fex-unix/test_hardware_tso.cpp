// SPDX-License-Identifier: MIT
#include "HardwareTso.h"
#include <cassert>
#include <vector>
#include <utility>

using namespace AuraHardwareTso;
static Result run(std::vector<std::pair<int,int>> replies, std::vector<int> expected) {
  size_t calls = 0;
  auto result = enable("1", [&](int op, int model) {
    assert(calls < replies.size());
    assert(op == expected[calls]);
    assert(model == (op == SetModel ? Tso : 0));
    auto [value, error] = replies[calls++]; errno = error; return value;
  });
  assert(calls == replies.size());
  return result;
}

int main() {
  for (const char* requested : std::initializer_list<const char*>{nullptr, "", "0", "true", "2"})
    assert(enable(requested, [](int, int) { assert(false); return -1; }) == Result::Off);
  assert(run({{Tso,0}}, {GetModel}) == Result::Enabled);
  assert(run({{Default,0},{0,0},{Tso,0}}, {GetModel,SetModel,GetModel}) == Result::Enabled);
  for (int error : {EINVAL, ENOSYS, EOPNOTSUPP})
    assert(run({{-1,error}}, {GetModel}) == Result::Unsupported);
  for (int error : {EACCES, EPERM})
    assert(run({{Default,0},{-1,error}}, {GetModel,SetModel}) == Result::Denied);
  assert(run({{Default,0},{0,0},{Default,0}}, {GetModel,SetModel,GetModel}) == Result::Failed);
  assert(run({{Default,0},{0,0},{-1,EIO}}, {GetModel,SetModel,GetModel}) == Result::Failed);
  assert(run({{Default,0},{-1,EIO}}, {GetModel,SetModel}) == Result::Failed);
  assert(run({{42,0}}, {GetModel}) == Result::Unsupported);
}
