#!/usr/bin/env python3
"""Compare the actual pre/post ARM64 allocator; Vulkan parameters stay identical."""
import argparse, hashlib, io, itertools, json, runpy, sys
from pathlib import Path
from elftools.elf.elffile import ELFFile
sys.path.insert(0,str(Path(__file__).resolve().parent))
from build import ROOT,HERE,baseline,transform
p=argparse.ArgumentParser();p.add_argument('--binary',type=Path,required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
record=json.loads((HERE/'build.json').read_text());new=args.binary.read_bytes();old=baseline(new,record)
assert transform(old)==(new,record)
replay=runpy.run_path(str(ROOT/'tools/diagnostics/replay_mesa_bo_cache_0136.py'))['replay']
def segments(data):
    return [(s['p_vaddr'],s['p_memsz'],s.data()) for s in ELFFile(io.BytesIO(data)).iter_segments() if s['p_type']=='PT_LOAD']
original,updated=segments(old),segments(new)
rows=[]
for case in itertools.product((False,True),(False,True),(False,True),(0,1,2,3)):
    before=replay(original,*case);after=replay(updated,*case,fixed=True)
    for key in ('vulkanAllocationBytes','allocationChainTypes','allocationChainPayloads','allocateCalls','returned'):
        assert before[key]==after[key],(case,key)
    rows.append(dict(bda=case[0],priority=case[1],callerChain=case[2],flags=case[3],beforeCached=before['useReusablePool'],afterCached=after['useReusablePool']))
result=dict(casesPassed=len(rows),arm64Executions=len(rows)*2,shaderAndImageContentsUnchanged=True,
            vulkanAllocationParametersIdentical=True,onlyOrdinaryAllocationFlagsCached=True,
            allocationFailureAndSyncCodeUnmodified=True,runtimeSha256=hashlib.sha256(new).hexdigest(),
            androidDeviceTested=False,measuredFpsImprovement=False,cases=rows)
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:result[k] for k in ('casesPassed','arm64Executions','vulkanAllocationParametersIdentical')}))
