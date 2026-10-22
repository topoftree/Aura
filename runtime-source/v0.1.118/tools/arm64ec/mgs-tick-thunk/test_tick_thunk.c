/* Host boundary tests of the production loader helper. No game code runs.
 * SPDX-License-Identifier: LGPL-2.1-or-later */
#define _GNU_SOURCE
#include <assert.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <wchar.h>

typedef uint8_t BYTE;
typedef uint16_t WCHAR;
typedef uint32_t DWORD;
typedef int32_t LONG;
typedef int64_t LONGLONG;
typedef uintptr_t ULONG_PTR;
typedef size_t SIZE_T;
typedef int BOOL;
typedef void *FARPROC, *HMODULE;
typedef struct { unsigned short Length, MaximumLength; WCHAR *Buffer; } UNICODE_STRING;
typedef struct { UNICODE_STRING ImagePathName; } RTL_USER_PROCESS_PARAMETERS;
typedef struct { RTL_USER_PROCESS_PARAMETERS *ProcessParameters; } PEB;
typedef struct { PEB *Peb; } TEB;
typedef struct { struct { unsigned Flags, SizeOfImage; UNICODE_STRING BaseDllName; } ldr; } WINE_MODREF;
typedef struct { int ignored; } IMAGE_EXPORT_DIRECTORY;
#define FALSE 0
#define TRUE 1
#define LDR_WINE_INTERNAL 0x80000000u
#define MEM_RESERVE 0x2000
#define MEM_COMMIT 0x1000
#define MEM_RELEASE 0x8000
#define PAGE_READWRITE 4
#define PAGE_EXECUTE_READ 0x20
#define RTL_CONSTANT_STRING(s) {sizeof(s)-2, sizeof(s), (WCHAR *)(s)}
#define GetCurrentProcess() ((void *)(intptr_t)-1)
static void quiet_trace(const char *fmt, ...) { (void)fmt; }
#define TRACE_(channel) quiet_trace
static RTL_USER_PROCESS_PARAMETERS params;
static PEB peb = {&params};
static TEB teb = {&peb};
static WINE_MODREF wm;
static BYTE *module;
static const char *flag = "1";
static int allocations, protections, flushes, frees, fail_at;
static TEB *NtCurrentTeb(void) { return &teb; }
static WINE_MODREF *get_modref(HMODULE h) { return h == module ? &wm : NULL; }
static int find_name_in_exports(HMODULE h, const IMAGE_EXPORT_DIRECTORY *ex, const char *name)
{ (void)h; (void)ex; assert(!strcmp(name, "GetTickCount")); return 17; }
static int RtlQueryEnvironmentVariable_U(void *env, UNICODE_STRING *name, UNICODE_STRING *out)
{
    (void)env;
    assert(name->Length == 38 && !memcmp(name->Buffer, L"AURA_MGS_TICK_THUNK", 38));
    if (!flag || strlen(flag)*2 > out->MaximumLength) return -1;
    out->Length = strlen(flag)*2;
    for (unsigned i=0; i<strlen(flag); i++) out->Buffer[i]=flag[i];
    return 0;
}
static int NtAllocateVirtualMemory(void *process, void **base, uintptr_t zero, SIZE_T *size,
                                   DWORD allocation, DWORD protection)
{
    (void)process; (void)zero;
    assert(allocation == (MEM_RESERVE|MEM_COMMIT) && protection == PAGE_READWRITE && *size == 4096);
    allocations++;
    if (fail_at == 1) return -1;
    *base = mmap(NULL, *size, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
    return *base == MAP_FAILED ? -1 : 0;
}
static int NtProtectVirtualMemory(void *process, void **base, SIZE_T *size, DWORD protection, DWORD *old)
{
    (void)process;
    protections++;
    assert(protection == PAGE_EXECUTE_READ);
    *old = PAGE_READWRITE;
    return fail_at == 2 ? -1 : mprotect(*base, *size, PROT_READ|PROT_EXEC);
}
static int NtFlushInstructionCache(void *process, void *base, SIZE_T size)
{ (void)process; (void)base; assert(size == 4096); flushes++; return fail_at == 3 ? -1 : 0; }
static int NtFreeVirtualMemory(void *process, void **base, SIZE_T *size, DWORD type)
{ (void)process; assert(!*size && type == MEM_RELEASE); frees++; return munmap(*base, 4096); }
#include "aura_mgs_tick_thunk.h"

static void string(UNICODE_STRING *s, const wchar_t *value)
{
    unsigned n=0;
    while(value[n]) n++;
    s->Buffer=(WCHAR *)value; s->Length=n*2; s->MaximumLength=(n+1)*2;
}
static void export_stub(unsigned offset, unsigned target)
{
    const BYTE code[]={0x48,0x8b,0xc4,0x48,0x89,0x58,0x20,0x55,0x5d,0xe9};
    LONG delta = target-offset-14;
    memcpy(module+offset,code,sizeof(code));
    memcpy(module+offset+10,&delta,4);
}
static void return_stub(unsigned offset, DWORD value)
{ module[offset]=0xb8; memcpy(module+offset+1,&value,4); module[offset+5]=0xc3; }
typedef DWORD (__attribute__((ms_abi)) *tick_fn)(void);
int main(int argc,char **argv)
{
    assert(argc >= 2 && sizeof(wchar_t)==2);
    const char *test=argv[1];
    module=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(module!=MAP_FAILED);
    memset(module,0xcc,4096);
    wm.ldr.Flags=LDR_WINE_INTERNAL; wm.ldr.SizeOfImage=4096;
    string(&wm.ldr.BaseDllName,L"kernel32.dll");
    string(&params.ImagePathName,L"C:\\games\\MgsGroundZeroes.exe");
    export_stub(256,128); export_stub(272,160);
    return_stub(128,0xfffffffe); return_stub(160,0x12345678);
    BYTE original_bytes[4096];
    DWORD ordinal=17;
    if (!strcmp(test,"off")) flag="0";
    if (!strcmp(test,"missing")) flag=NULL;
    if (!strcmp(test,"long-flag")) flag="10";
    if (!strcmp(test,"other-exe")) string(&params.ImagePathName,L"C:\\games\\MgsTpp.exe");
    if (!strcmp(test,"installer")) string(&params.ImagePathName,L"C:\\MgsGroundZeroes.exe\\setup.exe");
    if (!strcmp(test,"suffix")) string(&params.ImagePathName,L"C:\\MgsGroundZeroes.exe.bak");
    if (!strcmp(test,"case")) string(&params.ImagePathName,L"D:/GAMES/MGSGROUNDZEROES.EXE");
    if (!strcmp(test,"custom")) wm.ldr.Flags=0;
    if (!strcmp(test,"other-module")) string(&wm.ldr.BaseDllName,L"kernelbase.dll");
    if (!strcmp(test,"other-export")) ordinal=18;
    if (!strcmp(test,"layout")) module[256]=0x90;
    if (!strcmp(test,"bounds")) wm.ldr.SizeOfImage=260;
    if (!strcmp(test,"bad-jump")) memset(module+266,0x7f,4);
    if (!strcmp(test,"alloc-fail")) fail_at=1;
    if (!strcmp(test,"protect-fail")) fail_at=2;
    if (!strcmp(test,"flush-fail")) fail_at=3;
    memcpy(original_bytes,module,4096);
    assert(!mprotect(module,4096,PROT_READ|PROT_EXEC));
    BYTE *entry=aura_mgs_tick_export(module,NULL,ordinal,module+256);
    BOOL success=!strcmp(test,"active") || !strcmp(test,"case");
    assert(success == (entry != module+256));
    if(success)
    {
        assert(allocations==1 && protections==1 && flushes==1 && !frees);
        assert(entry[0]==0xe9 && *(LONG *)(entry+1)==59);
        assert(entry[64]==0xff && entry[65]==0x25 && !memcmp(entry+66,"\0\0\0\0",4));
        void *target; memcpy(&target,entry+70,8); assert(target==module+256);
        assert(((tick_fn)entry)()==0xfffffffe && ((tick_fn)(entry+64))()==0xfffffffe);
        assert(((tick_fn)(module+272))()==0x12345678);
        if(argc==3) { FILE *f=fopen(argv[2],"wb"); assert(f); assert(fwrite(entry,1,78,f)==78); assert(!fclose(f)); }
        /* Model a fourteen-byte hook and invoke the saved +64 pointer. */
        assert(!mprotect(entry,4096,PROT_READ|PROT_WRITE));
        entry[0]=0xff; entry[1]=0x25; memset(entry+2,0,4);
        target=module+160; memcpy(entry+6,&target,8);
        assert(!mprotect(entry,4096,PROT_READ|PROT_EXEC));
        assert(((tick_fn)entry)()==0x12345678 && ((tick_fn)(entry+64))()==0xfffffffe);
        assert(aura_mgs_tick_export(module,NULL,17,module+256)==entry);
        assert(aura_mgs_tick_export(module,NULL,18,module+272)==module+272);
        assert(!memcmp(original_bytes,module,4096));
        /* Different clock results, including wraparound, remain forwarded. */
        assert(!mprotect(module,4096,PROT_READ|PROT_WRITE)); return_stub(128,1);
        assert(!mprotect(module,4096,PROT_READ|PROT_EXEC));
        assert(((tick_fn)(entry+64))()==1);
        munmap(entry,4096);
    }
    else
    {
        int prior=allocations;
        fail_at=0;
        assert(aura_mgs_tick_export(module,NULL,ordinal,module+256)==module+256 && allocations==prior);
        assert(!memcmp(original_bytes,module,4096));
        assert(frees==(!strcmp(test,"protect-fail") || !strcmp(test,"flush-fail")));
    }
    munmap(module,4096);
    printf("%s: passed\n",test);
    return 0;
}
