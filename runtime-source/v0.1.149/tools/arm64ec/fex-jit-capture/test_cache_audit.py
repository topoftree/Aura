#!/usr/bin/env python3
"""Execute the production blob-copy and auxv-query bodies with controlled inputs under ASan/UBSan."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', type=Path, required=True)
    p.add_argument('--report', type=Path, required=True)
    a = p.parse_args()
    disk = (a.source / 'FEXCore/Source/Interface/Core/DiskCache.cpp').read_text()
    copy = next(line.strip() for line in disk.splitlines() if 'memcpy(NewBlobRef->data()' in line)
    bridge = (ROOT / 'tools/arm64ec/fex-unix/FEXUnixLib.cpp').read_text()
    query = bridge.split('static NTSTATUS FEXUnixLib_GetCpuAuditHandler', 1)[1].split('extern "C"', 1)[0]
    code = r'''
#include <cassert>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>
#include <sys/auxv.h>
#include "FEXUnixLib.h"
#include "CacheBufferPolicy.h"
using NTSTATUS = int;
constexpr int STATUS_SUCCESS = 0;
uint64_t H1, H2; bool Missing; int Queries;
unsigned long fake_aux(unsigned long type) { if (Missing) { errno=ENOENT; return 0; } return type==AT_HWCAP?H1:H2; }
int fake_prctl(int op, ...) { assert(op==51); ++Queries; return 32; }
#define getauxval fake_aux
#define prctl fake_prctl
static NTSTATUS FEXUnixLib_GetCpuAuditHandler''' + query + r'''
int main() {
  using namespace FEX::Windows;
  unsetenv("FEX_DISKCACHEMEMORYSIZE");
  setenv("AURA_FEX_CACHE_BUFFER_BYTES", "33554432", 1);
  setenv("AURA_FEX_CACHE_BUFFER_EXE", "Game.exe", 1);
  ConfigureCacheBuffer("C:\\Games\\gAmE.ExE"); assert(Applied == "33554432");
  Applied.clear(); ConfigureCacheBuffer("Steam.exe"); assert(Applied.empty());
  DiskEnabled=false; ConfigureCacheBuffer("Game.exe"); assert(Applied.empty());
  DiskEnabled=true; setenv("FEX_DISKCACHEMEMORYSIZE", "1234", 1);
  ConfigureCacheBuffer("Game.exe"); assert(Applied.empty());
  unsetenv("FEX_DISKCACHEMEMORYSIZE"); setenv("AURA_FEX_CACHE_BUFFER_BYTES", "67108864", 1);
  ConfigureCacheBuffer("Game.exe"); assert(Applied=="67108864");
  Applied.clear(); setenv("AURA_FEX_CACHE_BUFFER_BYTES", "1073741824", 1);
  ConfigureCacheBuffer("Game.exe"); assert(Applied.empty());
  FEXUnixLib_GetCpuAudit A {};
  H1=(1ULL<<8)|(1ULL<<15)|(1ULL<<26)|(1ULL<<22)|(1ULL<<20); H2=(1ULL<<1)|(1ULL<<13);
  errno=EACCES; FEXUnixLib_GetCpuAuditHandler(&A);
  assert(A.Known==127 && A.Bits==127 && A.SveVectorBits==256 && Queries==1 && errno==EACCES);
  H1=H2=0; FEXUnixLib_GetCpuAuditHandler(&A);
  assert(A.Known==127 && A.Bits==0 && A.SveVectorBits==0 && Queries==1);
  Missing=true; FEXUnixLib_GetCpuAuditHandler(&A);
  assert(A.Known==0 && A.Bits==0 && A.SveVectorBits==0 && Queries==1);
  // Exact source allocation: the old header-skipping copy fails ASan at its tail.
  for (size_t pages=1; pages<=8; ++pages) for (size_t header: {16,32,64}) {
    std::vector<uint64_t> GuestPages(pages);
    size_t EntrySizeWithoutGuestCode=header+128, BlobOffset=pages*8+header;
    struct { std::vector<uint8_t> Blob; } HitData;
    HitData.Blob.resize(pages*8+EntrySizeWithoutGuestCode);
    for (size_t i=0;i<HitData.Blob.size();++i) HitData.Blob[i]=uint8_t(i*17+3);
    auto NewBlobRef=std::make_shared<std::vector<uint8_t>>(EntrySizeWithoutGuestCode);
    (void)BlobOffset;
    ''' + copy + r'''
    assert(std::memcmp(NewBlobRef->data(),HitData.Blob.data()+pages*8,EntrySizeWithoutGuestCode)==0);
  }
}
'''
    with tempfile.TemporaryDirectory(prefix='aura-cache-audit-') as tmp:
        tmp=Path(tmp); (tmp/'test.cpp').write_text(code)
        (tmp/'FEXCore/Config').mkdir(parents=True)
        (tmp/'FEXCore/Config/Config.h').write_text('''#pragma once
#include <string>
inline bool DiskEnabled=true;
inline std::string Applied;
#define FEX_CONFIG_OPT(name, ignored) auto name = [] { return DiskEnabled; }
namespace FEXCore::Config { constexpr int CONFIG_DISKCACHEMEMORYSIZE=1;
inline void Set(int, const char* Value) { Applied=Value; } }
''')
        flags=['g++','-std=c++20','-O1','-g','-fno-pie','-no-pie','-fsanitize=address,undefined','-fno-omit-frame-pointer',
               '-I',str(ROOT/'tools/arm64ec/fex-unix'), '-I',str(HERE), '-I',str(HERE.parent/'fex-shared-exits'), '-I',str(tmp)]
        subprocess.run([*flags,str(tmp/'test.cpp'),'-o',str(tmp/'test')],check=True)
        env={**os.environ,'ASAN_OPTIONS':'detect_leaks=0','UBSAN_OPTIONS':'halt_on_error=1'}
        subprocess.run([str(tmp/'test')],check=True,env=env)
        # A negative control proves this fixture catches the original offset defect.
        old=code.replace(copy,'memcpy(NewBlobRef->data(), HitData.Blob.data() + BlobOffset, EntrySizeWithoutGuestCode);')
        (tmp/'old.cpp').write_text(old)
        subprocess.run([*flags,str(tmp/'old.cpp'),'-o',str(tmp/'old')],check=True)
        failed=subprocess.run([str(tmp/'old')],capture_output=True,env=env)
        assert failed.returncode != 0 and b'AddressSanitizer' in failed.stderr
    # Preserve the read-only DB strategy: no null writer invocation to evict entries.
    assert 'bool StoreInMemory = !FoundInLRU && Writer != nullptr;' in disk
    result=dict(schema='FEX_CACHE_AUDIT_TEST_V1',passed=True,blobLayouts=24,cpuAuxvCases=3,bufferPolicyCases=6,
                oldCopyRejectedByAsan=True,sourceBodyExecuted=True,syntheticInputs=True,androidDeviceTested=False,
                diskSourceSha256=hashlib.sha256(disk.encode()).hexdigest(),bridgeSourceSha256=hashlib.sha256(bridge.encode()).hexdigest())
    a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))

if __name__=='__main__': main()
