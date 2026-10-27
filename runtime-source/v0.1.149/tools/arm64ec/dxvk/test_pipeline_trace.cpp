#include "aura_pipeline_trace.h"
#include <cassert>
#include <iostream>
#include <thread>
#include <vector>
using namespace aura_pipeline;
static const VkGraphicsPipelineCreateInfo* expected;
static unsigned calls=0;
static VkResult graphicsFn(VkDevice d,VkPipelineCache cache,uint32_t n,const VkGraphicsPipelineCreateInfo* p,const VkAllocationCallbacks* a,VkPipeline* out){
  assert(!d && !cache && n==1 && p==expected && !a && out && GetLastError()==17);
  ++calls;*out=reinterpret_cast<VkPipeline>(uintptr_t(91));SetLastError(55);return VK_ERROR_OUT_OF_DEVICE_MEMORY;
}
static VkResult shaderFn(VkDevice,const VkShaderModuleCreateInfo*,const VkAllocationCallbacks*,VkShaderModule* out){*out=reinterpret_cast<VkShaderModule>(uintptr_t(81));return VK_SUCCESS;}
static unsigned destroyed=0;
static void destroyFn(VkDevice,VkShaderModule m,const VkAllocationCallbacks*){assert(m==reinterpret_cast<VkShaderModule>(uintptr_t(81)));++destroyed;}
int main(int argc,char** argv){
 assert(argc==2);bool active=std::string(argv[1])=="active";
 VkDevice device=VK_NULL_HANDLE;
 uint32_t code[]={0x07230203,0x00010000,9,10,0,(2u<<16)|17,1,(2u<<16)|17,2};
 VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};sm.codeSize=sizeof(code);sm.pCode=code;
 VkShaderModule module{};assert(createShader(shaderFn,device,&sm,nullptr,&module)==VK_SUCCESS);
 VkPipelineShaderStageCreateInfo stage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};stage.stage=VK_SHADER_STAGE_VERTEX_BIT;stage.module=module;stage.pName="PRIVATE_ENTRY";
 VkFormat format=VK_FORMAT_R8G8B8A8_UNORM;VkPipelineRenderingCreateInfo rendering{VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO};rendering.colorAttachmentCount=1;rendering.pColorAttachmentFormats=&format;rendering.viewMask=3;
 VkGraphicsPipelineCreateInfo p{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};p.stageCount=1;p.pStages=&stage;p.pNext=&rendering;expected=&p;
 VkPipeline pipeline{};SetLastError(17);assert(createGraphics(graphicsFn,device,VK_NULL_HANDLE,1,&p,nullptr,&pipeline)==VK_ERROR_OUT_OF_DEVICE_MEMORY);assert(GetLastError()==55 && calls==1);
 Shader meta{};
 if(!active){assert(!trace().lookup(device,module,meta));std::cout<<"inactive delegation preserved\n";return 0;}
 assert(trace().lookup(device,module,meta) && meta[7]==2 && meta[8]==1 && meta[9]==2);
 auto r=graphics(1,&p,[&](VkShaderModule m,Shader& s){return trace().lookup(device,m,s);},[&](const VkShaderModuleCreateInfo* s){return trace().scan(s);});
 assert(r.used==248 && r.fields[232]==1 && r.fields[233]==3 && r.fields[238]==VK_FORMAT_R8G8B8A8_UNORM && r.fields[59]==0 && r.fields[71]==1);
 destroyShader(destroyFn,device,module,nullptr);assert(destroyed==1 && !trace().lookup(device,module,meta));
 // malformed, partial prefix, and budget exhaustion are distinguished.
 assert(summarize(&sm,false)[4]==8);code[5]=0;assert(summarize(&sm,true)[4]&4);code[5]=(2u<<16)|17;
 std::vector<uint32_t> huge(5000,1u<<16);std::copy(code,code+9,huge.begin());sm.pCode=huge.data();sm.codeSize=huge.size()*4;assert(summarize(&sm,true)[4]&2);
 // Concurrent reservations and returned records share a bounded ring.
 std::vector<std::thread> threads;
 for(int j=0;j<4;++j)threads.emplace_back([]{for(int i=0;i<200;++i){Record r{};r.kind=3;r.used=92;auto c=trace().begin(r);trace().end(c,VK_SUCCESS);}});
 for(auto& t:threads)t.join();
 Record pending{};pending.kind=3;pending.used=92;auto stale=trace().begin(pending);
 for(int i=0;i<64;++i){auto c=trace().begin(pending);trace().end(c,VK_SUCCESS);}trace().end(stale,VK_ERROR_DEVICE_LOST);
 while(trace().canBegin()){auto c=trace().begin(pending);trace().end(c,VK_SUCCESS);}assert(!trace().begin(pending));
 std::cout<<"shader metadata, exact delegation, destroy, concurrent ring, stale return and 8192 call budget passed\n";
}
