/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
/* SPDX-License-Identifier: MIT */
#ifndef AURA_GEOMETRY_REPLY_H
#define AURA_GEOMETRY_REPLY_H
#include <stdint.h>
#include <string.h>

/* This X server uses the little-endian X11 wire format. Encode without aligned
 * stores so a reply appended after other output cannot make an unaligned write. */
static inline void geometry_u16(unsigned char *p, uint16_t value) {
    p[0]=(unsigned char)value; p[1]=(unsigned char)(value>>8);
}
static inline void geometry_reply(unsigned char *p, uint8_t depth, uint16_t sequence,
        uint32_t root, int16_t x, int16_t y, uint16_t width, uint16_t height, uint16_t border) {
    memset(p,0,32); p[0]=1; p[1]=depth;
    geometry_u16(p+2,sequence);
    geometry_u16(p+8,(uint16_t)root); geometry_u16(p+10,(uint16_t)(root>>16));
    geometry_u16(p+12,(uint16_t)x); geometry_u16(p+14,(uint16_t)y);
    geometry_u16(p+16,width); geometry_u16(p+18,height); geometry_u16(p+20,border);
}
#endif
