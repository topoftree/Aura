// SPDX-License-Identifier: MIT
#pragma once
#include <cstdint>
#include <cstring>
#include <algorithm>
namespace aura_color {
constexpr uint32_t Bytes=1024, Slots=3;
struct alignas(8) Header {
  char magic[8]; uint32_t version, bytes, armed, epoch, pid, claims;
  uint64_t deadlineUtc, startTick;
  uint32_t nativeEpoch, initializing;
  uint8_t reserved[72];
};
struct alignas(8) Row {
  uint32_t sequence, epoch, state, flags;
  uint64_t frame, utcBegin, utcEnd, qpcBegin, qpcEnd;
  uint32_t width, height, sourceFormat, sourceViewFormat, targetFormat, targetViewFormat, colorSpace, samples;
  uint32_t sum[4], minimum[4], maximum[4];
  uint64_t frequency; uint32_t points, reserved[3];
};
struct Data { Header header; Row rows[Slots]; uint8_t reserved[416]; };
static_assert(sizeof(Header)==128 && sizeof(Row)==160 && sizeof(Data)==Bytes);
enum State : uint32_t { Pending=1, Complete, Unsupported, AllocationFailed, CopyFailed, DeviceLost };
inline bool valid(const Data* d) {
  return d && !std::memcmp(d->header.magic,"AUCOLR1",8) && d->header.version==1 && d->header.bytes==Bytes;
}
inline bool rgba8(uint32_t format) { return format==37 || format==43 || format==44 || format==50; }
// Nine raw UNORM/sRGB-encoded texels; no gamma conversion or exposure adjustment.
inline void summarize(Row& row,const uint8_t* data) {
  bool bgra=row.sourceFormat==44 || row.sourceFormat==50;
  for(unsigned c=0;c<4;++c) {
    row.minimum[c]=255;row.maximum[c]=row.sum[c]=0;
    unsigned source=bgra && c<3 ? 2-c : c;
    for(unsigned p=0;p<9;++p) {
      uint32_t v=data[4*p+source];row.sum[c]+=v;
      row.minimum[c]=std::min(row.minimum[c],v);row.maximum[c]=std::max(row.maximum[c],v);
    }
  }
  row.points=9;row.state=Complete;
}
}
