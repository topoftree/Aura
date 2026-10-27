// SPDX-License-Identifier: MIT -- executes the production observer with explicit GPU completion.
#define AURA_COLOR_TEST
#include <cassert>
#include <memory>
#include <vector>
#include <stdexcept>
#include <cstring>
#include <vulkan/vulkan.h>
#include <cstdint>
using DWORD=uint32_t;using LONG=int32_t;
struct FILETIME {uint32_t dwLowDateTime,dwHighDateTime;};struct LARGE_INTEGER {int64_t QuadPart;};
static uint64_t now=100000,ticks=100,queries=0;static DWORD error=42;
static DWORD GetLastError(){return error;}static void SetLastError(DWORD e){error=e;}
static uint64_t GetTickCount64(){return ticks;}
static void GetSystemTimeAsFileTime(FILETIME* t){uint64_t v=(now+11644473600000ull)*10000;t->dwLowDateTime=v;t->dwHighDateTime=v>>32;}
static void QueryPerformanceCounter(LARGE_INTEGER* t){t->QuadPart=now*1000;++queries;}
static void QueryPerformanceFrequency(LARGE_INTEGER* t){t->QuadPart=1000000;}
static LONG InterlockedCompareExchange(volatile LONG* p,LONG after,LONG before){return __sync_val_compare_and_swap(p,before,after);}
static void InterlockedExchange(volatile LONG* p,LONG v){__sync_lock_test_and_set(p,v);}
namespace dxvk {
template<class T> using Rc=std::shared_ptr<T>;
enum class DxvkAccess {Write};
struct DxvkBufferCreateInfo {uint64_t size;uint32_t usage,stages,access;};
struct DxvkBuffer {bool busy=false;uint8_t data[36]={};bool isInUse(DxvkAccess)const{return busy;}
 void* mapPtr(unsigned){return data;}uint32_t memFlags(){return VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;}};
struct Info {VkFormat format=VK_FORMAT_B8G8R8A8_UNORM;VkImageType type=VK_IMAGE_TYPE_2D;VkExtent3D extent={1560,720,1};
 VkSampleCountFlagBits sampleCount=VK_SAMPLE_COUNT_1_BIT;uint32_t usage=VK_IMAGE_USAGE_TRANSFER_SRC_BIT;};
struct ViewInfo {VkFormat format=VK_FORMAT_B8G8R8A8_UNORM;uint32_t mipIndex=0,layerIndex=0,layerCount=1;};
struct DxvkImage {Info value;const Info& info(){return value;}};
struct DxvkImageView {Rc<DxvkImage> img=std::make_shared<DxvkImage>();ViewInfo value;
 Rc<DxvkImage> image(){return img;}ViewInfo info(){return value;}};
struct DxvkDevice {VkResult status=VK_SUCCESS;bool fail=false;std::vector<Rc<DxvkBuffer>> allocated;
 VkResult getDeviceStatus(){return status;}
 Rc<DxvkBuffer> createBuffer(DxvkBufferCreateInfo bi,uint32_t mem){
   assert(bi.size==36 && (bi.access&VK_ACCESS_HOST_READ_BIT) && (mem&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
   if(fail)throw std::runtime_error("allocation");auto b=std::make_shared<DxvkBuffer>();allocated.push_back(b);return b;}};
struct DxvkContext {unsigned copies=0;bool fail=false;
 void copyImageToBuffer(Rc<DxvkBuffer> b,uint64_t at,unsigned,unsigned,VkFormat,Rc<DxvkImage> image,
 VkImageSubresourceLayers layers,VkOffset3D offset,VkExtent3D size){
   ++copies;if(fail)throw std::runtime_error("copy");assert(size.width==1 && size.height==1 && layers.layerCount==1);
   assert(offset.x==int(image->value.extent.width*((at/4)%3+1)/4));
   assert(offset.y==int(image->value.extent.height*((at/4)/3+1)/4));
   b->busy=true;b->data[at]=10;b->data[at+1]=20;b->data[at+2]=30;b->data[at+3]=255;}};
}
// The production relative core include is staged next to the header by check_color_trace.py.
#include "aura_color_trace.h"
namespace aura_color {Data* testMapping;}
int main(int argc,char** argv){
 using namespace aura_color;using namespace dxvk;
 assert(argc==2);Data data={};std::memcpy(data.header.magic,"AUCOLR1",8);data.header.version=1;data.header.bytes=Bytes;data.header.pid=123;
 testMapping=&data;auto device=std::make_shared<DxvkDevice>();auto source=std::make_shared<DxvkImageView>();
 auto target=std::make_shared<DxvkImageView>();DxvkContext context;Trace trace;
 auto arm=[&]{data.header.armed=0;data.header.epoch++;data.header.deadlineUtc=now+10000;data.header.armed=1;};
 auto sample=[&](uint64_t frame){trace.sample(device,&context,source,target,0,frame);assert(GetLastError()==42);};
 sample(1);assert(context.copies==0 && device->allocated.empty());
 arm();sample(2);assert(context.copies==9 && data.rows[0].state==Pending);
 sample(3);assert(data.rows[0].state==Pending); // pending GPU writes do not become fabricated zero pixels
 device->allocated[0]->busy=false;sample(4);
 assert(data.rows[0].state==Complete && data.rows[0].sum[0]==270 && data.rows[0].sum[1]==180 &&
 data.rows[0].sum[2]==90 && data.rows[0].sum[3]==2295 && data.rows[0].frame==2);
 for(unsigned i=1;i<3;++i){now+=2500;ticks+=2500;sample(4+i);device->allocated.back()->busy=false;sample(9+i);}
 assert(context.copies==27 && data.header.claims==3);sample(99);assert(context.copies==27);
 data.header.armed=0;FILE* f=fopen(argv[1],"wb");assert(f && fwrite(&data,1,sizeof(data),f)==sizeof(data));fclose(f);
 for(uint32_t format:{37u,43u,44u,50u}) {Row row={};row.sourceFormat=format;
 uint8_t pixels[36];for(unsigned i=0;i<9;++i){pixels[4*i]=3;pixels[4*i+1]=7;pixels[4*i+2]=11;pixels[4*i+3]=255;}
 summarize(row,pixels);assert(row.sum[0]==((format==44 || format==50)?99u:27u) && row.points==9);}
 // Unsupported formats, allocations and copy errors remain explicit with no pixels.
 arm();source->img->value.format=VK_FORMAT_R16G16B16A16_SFLOAT;sample(100);assert(data.rows[0].state==Unsupported);
 source->img->value.format=VK_FORMAT_B8G8R8A8_UNORM;arm();device->fail=true;sample(101);assert(data.rows[0].state==AllocationFailed);device->fail=false;
 arm();context.fail=true;sample(102);assert(data.rows[0].state==CopyFailed);context.fail=false;sample(103);assert(data.rows[0].state==CopyFailed);
 arm();sample(104);device->allocated.back()->busy=false;device->status=VK_ERROR_DEVICE_LOST;sample(105);
 assert(data.rows[0].state==DeviceLost && data.rows[0].points==0);device->status=VK_SUCCESS;
 arm();sample(106);data.header.armed=0;device->allocated.back()->busy=false;sample(107);assert(data.rows[0].state==Pending);
 // Late GPU completion from an earlier capture must not publish into a rearmed epoch.
 arm();sample(108);auto old=device->allocated.back();arm();old->busy=false;sample(109);
 assert(data.rows[0].epoch==data.header.epoch && data.rows[0].frame==109 && data.rows[0].state==Pending);
 // Keep all three allocations busy across a restart; cannot grow the per-chain budget.
 now+=2500;ticks+=2500;sample(110);now+=2500;ticks+=2500;sample(111);
 auto allocations=device->allocated.size();arm();sample(112);assert(device->allocated.size()==allocations);
 now+=10001;ticks+=10001;for(auto& b:device->allocated)b->busy=false;
 sample(113);assert(device->allocated.size()==allocations); // expired, no read or new GPU work
 puts("{\"productionTrace\":true,\"asyncCompletion\":true,\"bgraRgba\":true,\"threeNinePointReads\":true,\"pendingNotBlack\":true,\"errorsUnknown\":true,\"stopEpochDeadline\":true,\"allocationBound\":true,\"extraGpuWaits\":false}");
}
