// SPDX-License-Identifier: MIT
#pragma once
#include "aura_color_core.h"
#ifndef AURA_COLOR_TEST
#include "../dxvk/dxvk_context.h"
#include <windows.h>
#endif
#include <array>
namespace aura_color {
inline uint32_t load(uint32_t& p) { return uint32_t(InterlockedCompareExchange(reinterpret_cast<volatile LONG*>(&p),0,0)); }
inline bool cas(uint32_t& p,uint32_t before,uint32_t after) { return uint32_t(InterlockedCompareExchange(reinterpret_cast<volatile LONG*>(&p),after,before))==before; }
inline void store(uint32_t& p,uint32_t value) { InterlockedExchange(reinterpret_cast<volatile LONG*>(&p),value); }
inline uint64_t utc() { FILETIME t;GetSystemTimeAsFileTime(&t);return (((uint64_t)t.dwHighDateTime<<32)|t.dwLowDateTime)/10000-11644473600000ull; }
inline uint64_t qpc() { LARGE_INTEGER t={};QueryPerformanceCounter(&t);return t.QuadPart; }
#ifdef AURA_COLOR_TEST
extern Data* testMapping;
inline Data* mapping() noexcept { return testMapping; }
#else
inline Data* mapping() noexcept {
  static Data* data=[]() noexcept -> Data* {
    DWORD saved=GetLastError();Data* out=nullptr;
    wchar_t path[1024]={},exe[1024]={};
    DWORD n=GetEnvironmentVariableW(L"AURA_DXVK_COLOR_PATH",path,1024),e=GetModuleFileNameW(nullptr,exe,1024);
    const wchar_t* base=exe;
    for(DWORD i=0;i<e && i<1024;++i) if(exe[i]==L'\\' || exe[i]==L'/') base=exe+i+1;
    // This intrusive GPU read is deliberately restricted to the requested Cuphead diagnosis.
    if(n && n<1024 && e && e<1024 && !lstrcmpiW(base,L"Cuphead.exe")) {
      HANDLE file=CreateFileW(path,GENERIC_READ|GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,
          nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
      BY_HANDLE_FILE_INFORMATION info={};
      if(file!=INVALID_HANDLE_VALUE) {
        if(GetFileType(file)==FILE_TYPE_DISK && GetFileInformationByHandle(file,&info) &&
           !(info.dwFileAttributes&(FILE_ATTRIBUTE_REPARSE_POINT|FILE_ATTRIBUTE_DIRECTORY)) &&
           info.nNumberOfLinks==1 && !info.nFileSizeHigh && info.nFileSizeLow==Bytes) {
          HANDLE map=CreateFileMappingW(file,nullptr,PAGE_READWRITE,0,Bytes,nullptr);
          if(map) {
            auto d=static_cast<Data*>(MapViewOfFile(map,FILE_MAP_READ|FILE_MAP_WRITE,0,0,Bytes));
            if(valid(d) && cas(d->header.pid,0,GetCurrentProcessId())) out=d;
            else if(d) UnmapViewOfFile(d);
            CloseHandle(map);
          }
        }
        CloseHandle(file);
      }
    }
    SetLastError(saved);return out; // pinned mapping, no open handle; revoked by original inode
  }();return data;
}
#endif
class Trace {
  struct PendingRead { dxvk::Rc<dxvk::DxvkBuffer> buffer; Row row={};unsigned index=0; };
  std::array<PendingRead,Slots> pending;
  static bool active(Data* d,uint32_t epoch) {
    if(!d || !epoch || load(d->header.armed)!=1 || load(d->header.epoch)!=epoch ||
       load(d->header.nativeEpoch)!=epoch) return false;
    uint64_t now=utc(),tick=GetTickCount64();
    return now<d->header.deadlineUtc && d->header.deadlineUtc-now<=10000 &&
      tick>=d->header.startTick && tick-d->header.startTick<10000;
  }
  static void publish(Data* d,unsigned index,Row row) {
    if(index>=Slots || !active(d,row.epoch)) return;
    Row& out=d->rows[index];uint32_t seq=load(out.sequence);
    if((seq&1) || !cas(out.sequence,seq,seq+1)) return;
    if(active(d,row.epoch)) std::memcpy(reinterpret_cast<char*>(&out)+4,reinterpret_cast<char*>(&row)+4,sizeof(Row)-4);
    store(out.sequence,seq+2);
  }
public:
  // Called only by this swapchain's serialized CS thread. Reads never wait for GPU work.
  void sample(const dxvk::Rc<dxvk::DxvkDevice>& device,dxvk::DxvkContext* ctx,
      const dxvk::Rc<dxvk::DxvkImageView>& source,const dxvk::Rc<dxvk::DxvkImageView>& target,
      uint32_t colorSpace,uint64_t frame) noexcept {
    DWORD saved=GetLastError();
    try { sampleImpl(device,ctx,source,target,colorSpace,frame); } catch(...) { /* diagnostics cannot abort Present */ }
    SetLastError(saved);
  }
private:
  void sampleImpl(const dxvk::Rc<dxvk::DxvkDevice>& device,dxvk::DxvkContext* ctx,
      const dxvk::Rc<dxvk::DxvkImageView>& source,const dxvk::Rc<dxvk::DxvkImageView>& target,
      uint32_t colorSpace,uint64_t frame) {
    Data* d=mapping();if(!d) return;
    for(auto& p:pending) if(p.buffer && !p.buffer->isInUse(dxvk::DxvkAccess::Write)) {
      if(active(d,p.row.epoch) && p.row.state==Pending) {
        p.row.utcEnd=utc();p.row.qpcEnd=qpc();
        if(device->getDeviceStatus()!=VK_SUCCESS) p.row.state=DeviceLost;
        else if(p.buffer->mapPtr(0)) summarize(p.row,static_cast<const uint8_t*>(p.buffer->mapPtr(0)));
        else p.row.state=CopyFailed;
        publish(d,p.index,p.row);
      }
      p.buffer=nullptr;
    }
    if(load(d->header.armed)!=1) return;
    uint32_t epoch=load(d->header.epoch);
    if(epoch && load(d->header.nativeEpoch)!=epoch && cas(d->header.initializing,0,1)) {
      if(load(d->header.epoch)==epoch && load(d->header.armed)==1) {
        d->header.startTick=GetTickCount64();store(d->header.claims,0);store(d->header.nativeEpoch,epoch);
      }
      store(d->header.initializing,0);
    }
    if(!active(d,epoch)) return;
    auto available=std::find_if(pending.begin(),pending.end(),[](const PendingRead& p){return !p.buffer;});
    if(available==pending.end()) return; // at most three retained allocations, even after rearming
    uint32_t index=load(d->header.claims);
    // Three attempts, roughly spaced across the manual window, not every Present.
    uint64_t remaining=d->header.deadlineUtc-utc();
    if(index>=Slots || remaining>10000-uint64_t(index)*2500 || !cas(d->header.claims,index,index+1)) return;
    Row row={};row.epoch=epoch;row.state=Pending;row.frame=frame;row.utcBegin=utc();row.qpcBegin=qpc();
    LARGE_INTEGER frequency={};QueryPerformanceFrequency(&frequency);row.frequency=frequency.QuadPart;
    const auto& si=source->image()->info();
    row.width=si.extent.width;row.height=si.extent.height;row.sourceFormat=si.format;
    row.sourceViewFormat=source->info().format;row.targetFormat=target->image()->info().format;
    row.targetViewFormat=target->info().format;row.colorSpace=colorSpace;row.samples=si.sampleCount;
    if(device->getDeviceStatus()!=VK_SUCCESS) {row.state=DeviceLost;publish(d,index,row);return;}
    if(!rgba8(si.format) || si.sampleCount!=VK_SAMPLE_COUNT_1_BIT || si.type!=VK_IMAGE_TYPE_2D ||
       row.width<128 || row.height<128 || !(si.usage&VK_IMAGE_USAGE_TRANSFER_SRC_BIT) ||
       source->info().mipIndex || source->info().layerIndex || source->info().layerCount!=1) {
      row.state=Unsupported;publish(d,index,row);return;
    }
    dxvk::DxvkBufferCreateInfo bi={};bi.size=36;bi.usage=VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bi.stages=VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_HOST_BIT;
    bi.access=VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_HOST_READ_BIT;
    dxvk::Rc<dxvk::DxvkBuffer> buffer;
    try {buffer=device->createBuffer(bi,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);}
    catch(...) {row.state=AllocationFailed;publish(d,index,row);return;}
    if(!buffer->mapPtr(0) || !(buffer->memFlags()&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
      row.state=AllocationFailed;publish(d,index,row);return;
    }
    // Do not alter game pixels, add usages or force MSAA/unsupported formats into a probe.
    // DXVK owns barriers and resource tracking, using the same readback path as D3D11 Map.
    if(!active(d,epoch)) return;
    *available={buffer,row,index};
    publish(d,index,row);
    try {
      VkImageSubresourceLayers layers={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
      for(unsigned y=0;y<3;++y) for(unsigned x=0;x<3;++x) {
        VkOffset3D offset={int32_t((uint64_t(row.width)*(x+1))/4),int32_t((uint64_t(row.height)*(y+1))/4),0};
        ctx->copyImageToBuffer(buffer,4*(3*y+x),0,0,si.format,source->image(),layers,offset,{1,1,1});
      }
    } catch(...) {
      // Preserve real errors; a partially recorded readback is never published as pixels.
      available->row.state=CopyFailed;publish(d,index,available->row);
    }
  }
};
}
