/* Aura SMAPI console input relay. MIT. No shell, clipboard or foreground input. */
#include <stdint.h>
#include <stddef.h>
typedef uint16_t WCHAR;
typedef uint32_t DWORD;
typedef int32_t BOOL;
typedef void *HANDLE;
typedef struct { DWORD low, high; } FILETIME;
typedef struct { DWORD size, usage, pid; uintptr_t heap; DWORD module, threads, parent; int32_t priority; DWORD flags; WCHAR name[260]; } PROCESSENTRY;
typedef struct { uint16_t type, pad; BOOL down; uint16_t repeat, vkey, scan, unicode; DWORD control; } INPUT;
_Static_assert(sizeof(INPUT) == 20, "Windows INPUT_RECORD ABI");
_Static_assert(sizeof(PROCESSENTRY) == 568, "Windows PROCESSENTRY32W ABI");
#ifdef AURA_TEST
#define API
#else
#define API __declspec(dllimport)
#endif
API DWORD GetModuleFileNameW(HANDLE,WCHAR*,DWORD);
API HANDLE CreateFileW(const WCHAR*,DWORD,DWORD,void*,DWORD,DWORD,HANDLE);
API BOOL ReadFile(HANDLE,void*,DWORD,DWORD*,void*);
API BOOL WriteFile(HANDLE,const void*,DWORD,DWORD*,void*);
API BOOL CloseHandle(HANDLE);
API HANDLE CreateToolhelp32Snapshot(DWORD,DWORD);
API BOOL Process32FirstW(HANDLE,PROCESSENTRY*);
API BOOL Process32NextW(HANDLE,PROCESSENTRY*);
API HANDLE OpenProcess(DWORD,BOOL,DWORD);
API BOOL QueryFullProcessImageNameW(HANDLE,DWORD,WCHAR*,DWORD*);
API BOOL GetProcessTimes(HANDLE,FILETIME*,FILETIME*,FILETIME*,FILETIME*);
API DWORD WaitForSingleObject(HANDLE,DWORD);
API BOOL AttachConsole(DWORD);
API BOOL FreeConsole(void);
API DWORD GetConsoleProcessList(DWORD*,DWORD);
API BOOL WriteConsoleInputW(HANDLE,const INPUT*,DWORD,DWORD*);
API void GetSystemTimeAsFileTime(FILETIME*);
API void ExitProcess(DWORD);
#define INVALID ((HANDLE)(intptr_t)-1)
static WCHAR filename[4096], expected[2049], image[2049];
static _Alignas(8) uint8_t request[4640];
static INPUT input[514];
static unsigned prefix;
static uint64_t milliseconds(FILETIME t) { return (((uint64_t)t.high<<32)|t.low)/10000-11644473600000ULL; }
static uint64_t now(void) { FILETIME t; GetSystemTimeAsFileTime(&t); return milliseconds(t); }
static WCHAR lower(WCHAR c) { return c>='A'&&c<='Z'?c+32:c; }
static BOOL same(const WCHAR *a,const WCHAR *b) { for(unsigned i=0;i<2049;i++) { if(lower(a[i])!=lower(b[i]))return 0; if(!a[i])return 1; }return 0; }
static void name(const char *s) { unsigned i=0; while(s[i]) {filename[prefix+i]=(WCHAR)s[i];i++;}filename[prefix+i]=0; }
static BOOL still_requested(void) {
    name("request.bin"); HANDLE f=CreateFileW(filename,0x80000000,7,0,3,0,0);
    if(f==INVALID)return 0; CloseHandle(f); return 1;
}
static DWORD relay(void) {
    DWORD n=GetModuleFileNameW(0,filename,4096); if(!n||n>=4000)return 1;
    while(n&&filename[n-1]!='\\')n--;if(!n)return 1;prefix=n;
    name("request.bin"); HANDLE f=CreateFileW(filename,0x80000000,7,0,3,0,0);if(f==INVALID)return 1;
    DWORD count=0;BOOL ok=ReadFile(f,request,sizeof(request),&count,0);CloseHandle(f);
    if(!ok||count<32)return 1;
    const uint32_t *words=(const uint32_t*)request;
    const uint64_t *times=(const uint64_t*)(request+8);
    unsigned plen=words[6],len=words[7];
    if(words[0]!=0x31434d41||words[1]!=1||plen<4||plen>2048||!len||len>256||count!=32+2*(plen+len))return 1;
    uint64_t since=times[0],deadline=times[1];
    if(now()>deadline||deadline<since)return 1;
    const WCHAR *text=(const WCHAR*)(request+32);
    for(unsigned i=0;i<plen;i++) { if(text[i]<32)return 1;expected[i]=text[i]; } expected[plen]=0;
    const WCHAR suffix[]={ '\\','S','t','a','r','d','e','w','M','o','d','d','i','n','g','A','P','I','.','e','x','e',0 };
    if(plen<22||!same(expected+plen-22,suffix))return 1;
    for(unsigned i=0;i<len;i++) {
        WCHAR c=text[plen+i]; if(c<32||(c>=127&&c<=159)||c==0x2028||c==0x2029)return 1;
        input[2*i]=(INPUT){1,0,1,1,0,0,c,0};input[2*i+1]=(INPUT){1,0,0,1,0,0,c,0};
    }
    input[2*len]=(INPUT){1,0,1,1,13,0,13,0};input[2*len+1]=(INPUT){1,0,0,1,13,0,13,0};
    HANDLE snapshot=CreateToolhelp32Snapshot(2,0);if(snapshot==INVALID)return 1;
    PROCESSENTRY entry;entry.size=sizeof(entry);
    HANDLE target=0;DWORD pid=0,matches=0,scanned=0;BOOL incomplete=0;
    for(BOOL next=Process32FirstW(snapshot,&entry);next;next=Process32NextW(snapshot,&entry)) {
        if(scanned++>=512||now()>deadline){incomplete=1;break;}
        if(!same(entry.name,suffix+1))continue;
        HANDLE process=OpenProcess(0x1000|0x100000,0,entry.pid);if(!process)continue;
        DWORD size=2048;FILETIME created,exit,kernel,user;
        if(QueryFullProcessImageNameW(process,0,image,&size)&&size<=2048) {
            image[size]=0;
            if(same(image,expected)&&GetProcessTimes(process,&created,&exit,&kernel,&user)&&
               milliseconds(created)>=since&&WaitForSingleObject(process,0)==258) {
                matches++; if(!target){target=process;pid=entry.pid;continue;}
            }
        }
        CloseHandle(process);
    }
    CloseHandle(snapshot);
    if(incomplete){if(target)CloseHandle(target);return 1;}
    if(!target)return 2;
    if(matches!=1){CloseHandle(target);return 3;}
    DWORD result=4;
    FreeConsole();
    if(AttachConsole(pid)) {
        DWORD members[32];DWORD members_n=GetConsoleProcessList(members,32);BOOL member=0;
        if(members_n<=32)for(DWORD i=0;i<members_n;i++)if(members[i]==pid)member=1;
        static const WCHAR conin[]={'C','O','N','I','N','$',0};
        HANDLE console=CreateFileW(conin,0xc0000000,3,0,3,0,0);
        if(console!=INVALID) {
            // Holding the process handle prevents PID reuse; recheck exit and request expiry immediately before input.
            if(member&&WaitForSingleObject(target,0)==258&&now()<=deadline&&still_requested()) {
                DWORD written=0;BOOL sent=WriteConsoleInputW(console,input,2*(len+1),&written);
                result=sent&&written==2*(len+1)?0:5; // Partial input is never retried or called success.
            }
            CloseHandle(console);
        }
        FreeConsole();
    }
    CloseHandle(target);return result;
}
void mainCRTStartup(void) {
    DWORD result=relay();
    if(prefix) {
        name("status.bin");HANDLE out=CreateFileW(filename,0x40000000,7,0,2,0,0);
        if(out!=INVALID){DWORD written;WriteFile(out,&result,4,&written,0);CloseHandle(out);}
    }
    ExitProcess(result);
}
