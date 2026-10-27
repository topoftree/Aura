#!/usr/bin/env python3
"""Run the real manual color observer with deterministic GPU completion, no phone claims."""
import argparse,json,os,shutil,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);a=p.parse_args();a.work.mkdir(parents=True,exist_ok=True)
here=Path(__file__).resolve().parent;root=here.parents[2]
for name in ('color_core.h','color_trace.h'):shutil.copyfile(here/name,a.work/('aura_'+name))
subprocess.run(['c++','-std=c++17','-Wall','-Wextra','-Werror','-Wno-misleading-indentation','-fsanitize=address,undefined','-fno-sanitize-recover=all',
 '-I'+str(root/'engine/src/main/cpp/vortekrenderer/include'),'-I'+str(a.work),str(here/'color_trace_test.cpp'),'-o',str(a.work/'color-test')],check=True)
r=subprocess.run([str(a.work/'color-test'),str(a.work/'color-fixture.bin')],capture_output=True,text=True,check=True,env={**os.environ,'ASAN_OPTIONS':'detect_leaks=0'})
a.work.joinpath('color-test.json').write_text(r.stdout);print(r.stdout)
