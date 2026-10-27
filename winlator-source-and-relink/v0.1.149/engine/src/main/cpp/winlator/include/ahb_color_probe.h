/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT. Sparse read-only bytes, never a full screenshot.
 * Caller has completed the source copy and still owns the Present source. */
#ifndef AURA_AHB_COLOR_PROBE_H
#define AURA_AHB_COLOR_PROBE_H
#include <android/hardware_buffer.h>
#include <stdint.h>
#include <stddef.h>
#include <limits.h>
#include <string.h>
#include <errno.h>
#define AURA_AHB_PIXEL_VALUES 46
enum aura_ahb_pixel_state { AHP_OBSERVED, AHP_NO_BUFFER, AHP_NO_CPU_READ, AHP_FORMAT,
    AHP_DIMENSIONS, AHP_PROTECTED, AHP_LOCK_FAILED, AHP_PLANES, AHP_UNLOCK_FAILED };
static inline void aura_ahb_color_probe(AHardwareBuffer *buffer,int width,int height,int32_t out[AURA_AHB_PIXEL_VALUES]) {
    int saved=errno;
    memset(out,0,AURA_AHB_PIXEL_VALUES*sizeof(*out));
    out[0]=1;out[1]=AHP_NO_BUFFER;out[8]=out[9]=INT32_MAX;
    if (!buffer) return;
    AHardwareBuffer_Desc desc={0};AHardwareBuffer_describe(buffer,&desc);
    out[2]=(int32_t)desc.width;out[3]=(int32_t)desc.height;out[4]=(int32_t)desc.format;
    out[5]=(int32_t)(desc.usage&AHARDWAREBUFFER_USAGE_CPU_READ_MASK);
    if (desc.width<128 || desc.height<128 || desc.width>32767 || desc.height>32767 ||
        desc.width!=(uint32_t)width || desc.height!=(uint32_t)height || desc.layers!=1) {
        out[1]=AHP_DIMENSIONS;goto done;
    }
    if (desc.usage&AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT) {out[1]=AHP_PROTECTED;goto done;}
    if (out[5]!=AHARDWAREBUFFER_USAGE_CPU_READ_RARELY && out[5]!=AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN) {
        out[1]=AHP_NO_CPU_READ;goto done;
    }
    /* 5 is Android HAL_PIXEL_FORMAT_BGRA_8888, also used by the producer. */
    if (desc.format!=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM && desc.format!=5) {out[1]=AHP_FORMAT;goto done;}
    AHardwareBuffer_Planes planes={0};
    out[8]=AHardwareBuffer_lockPlanes(buffer,AHARDWAREBUFFER_USAGE_CPU_READ_RARELY,-1,NULL,&planes);
    if (out[8]) {out[1]=AHP_LOCK_FAILED;goto done;}
    out[1]=AHP_PLANES;
    if (planes.planeCount==1 && planes.planes[0].data && planes.planes[0].pixelStride==4 &&
        planes.planes[0].rowStride>=desc.width*4 && planes.planes[0].rowStride<=1048576 &&
        (uint64_t)planes.planes[0].rowStride*desc.height<=SIZE_MAX) {
        out[6]=(int32_t)planes.planes[0].rowStride;out[7]=4;
        const uint8_t *bytes=planes.planes[0].data;
        for (unsigned y=0;y<3;++y) for (unsigned x=0;x<3;++x) {
            size_t at=(size_t)((y+1)*desc.height/4)*planes.planes[0].rowStride+((x+1)*desc.width/4)*4;
            unsigned dst=10+(y*3+x)*4;
            out[dst]=bytes[at+(desc.format==5?2:0)];out[dst+1]=bytes[at+1];
            out[dst+2]=bytes[at+(desc.format==5?0:2)];out[dst+3]=bytes[at+3];
        }
        out[1]=AHP_OBSERVED;
    }
    /* NULL fence makes unlock complete here; no returned descriptor can leak. */
    out[9]=AHardwareBuffer_unlock(buffer,NULL);
    if (out[9]) out[1]=AHP_UNLOCK_FAILED;
    if (out[1]!=AHP_OBSERVED) memset(out+10,0,36*sizeof(*out));
done:
    errno=saved;
}
#endif
