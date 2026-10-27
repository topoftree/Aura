/* SPDX-License-Identifier: MIT
 * Standalone synthetic guest, no game bytes. Build i686 Windows for WOW64/FEX.
 * A native Linux x86-64 run validates the fixture only, NOT the FEX repair.
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#ifdef _WIN32
#include <windows.h>
#else
#include <sys/mman.h>
#endif

int main(void) {
#ifdef _WIN32
  unsigned char *page = VirtualAlloc(0, 4096, MEM_RESERVE | MEM_COMMIT, PAGE_EXECUTE_READWRITE);
#else
  unsigned char *page = mmap(0, 4096, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (page == MAP_FAILED) page = 0;
#endif
  if (!page) return 2;
  /* A writer loop and its data share a writable code page. Both x86 ABIs use
   * caller-saved EAX/ECX/EDX; the 64-bit fixture receives args in RDI/ESI. */
#ifdef _WIN32
  const unsigned char loop[] = {
    0x8b,0x54,0x24,0x04, /* mov edx,[esp+4] */
    0x8b,0x4c,0x24,0x08, /* mov ecx,[esp+8] */
    0x80,0x32,0x01,      /* xor byte [edx],1 */
    0x49,               /* dec ecx */
    0x75,0xfa,          /* jnz loop */
    0x0f,0xb6,0x02,0xc3 /* movzx eax,byte [edx]; ret */
  };
#else
  const unsigned char loop[] = {
    0x89,0xf1,          /* mov ecx,esi */
    0x80,0x37,0x01,     /* xor byte [rdi],1 */
    0xff,0xc9,          /* dec ecx */
    0x75,0xf9,          /* jnz loop */
    0x0f,0xb6,0x07,0xc3 /* movzx eax,byte [rdi]; ret */
  };
#endif
  memcpy(page, loop, sizeof loop);
  int (*write_loop)(unsigned char*, unsigned) = (void*)page;
  page[128] = 0;
  if (write_loop(page + 128, 100001) != 1 || write_loop(page + 128, 100001) != 0) return 3;

  /* A compiled instruction changes an upcoming instruction's immediate, then
   * execution reaches it in the same forward block. Recompilation must observe it. */
#ifdef _WIN32
  const unsigned char inline_write[] = {0x8b,0x54,0x24,0x04,0xc6,0x02,0x2a,0xb8,0x01,0x00,0x00,0x00,0xc3};
  const unsigned target_offset = 8;
#else
  const unsigned char inline_write[] = {0xc6,0x07,0x2a,0xb8,0x01,0x00,0x00,0x00,0xc3};
  const unsigned target_offset = 4;
#endif
  memcpy(page + 256, inline_write, sizeof inline_write);
  int (*modify_next)(unsigned char*) = (void*)(page + 256);
  for (unsigned i = 0; i < 100; ++i) {
    page[256 + target_offset] = 1;
    if (modify_next(page + 256 + target_offset) != 42) return 4;
  }
  /* External writes to an already executed function must also be observed. */
  const unsigned char result[] = {0xb8,0x00,0x00,0x00,0x00,0xc3};
  memcpy(page + 512, result, sizeof result);
  int (*value)(void) = (void*)(page + 512);
  for (unsigned i = 0; i < 256; ++i) {
    page[513] = (unsigned char)i;
    if (value() != (int)i) return 5;
  }
  puts("SMC guest: shared-page loop, inline modification and external modification passed");
#ifdef _WIN32
  VirtualFree(page, 0, MEM_RELEASE);
#else
  munmap(page, 4096);
#endif
  return 0;
}
