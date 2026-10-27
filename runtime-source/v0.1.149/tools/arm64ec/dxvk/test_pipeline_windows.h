// Host-only Win32 file/mapping double. Actual production observer is compiled unchanged.
#pragma once
#include <cstdint>
#include <cwchar>
#include <cstring>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
using DWORD = uint32_t; using LONG = int32_t; using HANDLE = intptr_t;
constexpr HANDLE INVALID_HANDLE_VALUE = -1;
constexpr DWORD GENERIC_READ=1,GENERIC_WRITE=2,FILE_SHARE_READ=1,FILE_SHARE_WRITE=2,OPEN_EXISTING=3;
constexpr DWORD FILE_FLAG_OPEN_REPARSE_POINT=4,FILE_TYPE_DISK=1,FILE_ATTRIBUTE_DIRECTORY=16,FILE_ATTRIBUTE_REPARSE_POINT=32;
constexpr DWORD PAGE_READWRITE=4,FILE_MAP_READ=1,FILE_MAP_WRITE=2;
struct FILETIME { DWORD dwLowDateTime,dwHighDateTime; };
struct BY_HANDLE_FILE_INFORMATION { DWORD dwFileAttributes,nNumberOfLinks,nFileSizeHigh,nFileSizeLow; };
static thread_local DWORD lastError=0; static uint64_t nowMs=1000000, ticks=100;
inline DWORD GetLastError(){return lastError;} inline void SetLastError(DWORD x){lastError=x;}
inline LONG InterlockedCompareExchange(volatile LONG* p,LONG value,LONG expected){__atomic_compare_exchange_n(p,&expected,value,false,__ATOMIC_SEQ_CST,__ATOMIC_SEQ_CST);return expected;}
inline LONG InterlockedIncrement(volatile LONG* p){return __atomic_add_fetch(p,1,__ATOMIC_SEQ_CST);}
inline LONG InterlockedExchange(volatile LONG* p,LONG v){return __atomic_exchange_n(p,v,__ATOMIC_SEQ_CST);}
inline uint64_t GetTickCount64(){return ticks;}
inline void GetSystemTimeAsFileTime(FILETIME* p){uint64_t n=(nowMs+11644473600000ull)*10000;p->dwLowDateTime=n;p->dwHighDateTime=n>>32;}
inline DWORD GetCurrentProcessId(){return 42;}
inline DWORD GetCurrentThreadId(){static thread_local DWORD id=0;static uint32_t next=0;if(!id)id=__atomic_add_fetch(&next,1,__ATOMIC_SEQ_CST);return id;}
// TRACE_DIR is the exact DOS path. Z maps to the private runtime root, never /.
inline DWORD GetEnvironmentVariableW(const wchar_t*,wchar_t* out,DWORD size){const char* path=getenv("TRACE_DIR");if(!path)return 0;std::wstring w;for(char c:std::string(path))w+=c;if(w.size()+1>size)return w.size()+1;wcscpy(out,w.c_str());return w.size();}
inline HANDLE CreateFileW(const wchar_t* path,DWORD,DWORD,void*,DWORD,DWORD,void*){
  const char* root=getenv("TRACE_ROOT");
  if(!root || wcsncmp(path,L"Z:\\",3))return INVALID_HANDLE_VALUE;
  std::string relative;for(const wchar_t* s=path+3;*s;++s)relative+=*s=='\\'?'/':char(*s);
  if(relative.empty() || relative[0]=='/' || relative.find("..")!=std::string::npos)return INVALID_HANDLE_VALUE;
  return open((std::string(root)+"/"+relative).c_str(),O_RDWR|O_NOFOLLOW);
}
inline DWORD GetFileType(HANDLE){return FILE_TYPE_DISK;}
inline bool GetFileInformationByHandle(HANDLE fd,BY_HANDLE_FILE_INFORMATION* out){struct stat s{};if(fstat(fd,&s))return false;out->dwFileAttributes=S_ISDIR(s.st_mode)?FILE_ATTRIBUTE_DIRECTORY:0;out->nNumberOfLinks=s.st_nlink;out->nFileSizeHigh=uint64_t(s.st_size)>>32;out->nFileSizeLow=s.st_size;return true;}
inline void CloseHandle(HANDLE fd){close(fd);}
inline HANDLE CreateFileMappingW(HANDLE fd,void*,DWORD,DWORD,DWORD,void*){return dup(fd);}
inline void* MapViewOfFile(HANDLE fd,DWORD,DWORD,DWORD,DWORD bytes){void* p=mmap(nullptr,bytes,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);return p==MAP_FAILED?nullptr:p;}
inline void UnmapViewOfFile(void* p){munmap(p,64);}
