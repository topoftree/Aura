#!/usr/bin/env python3
"""Compile production observer unchanged against explicit Win32/Vulkan host doubles."""
import argparse,os,struct,subprocess,tempfile,json,shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
here=Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix='pipeline-tests-') as temp:
 root=Path(temp)
 for name in ('pipeline_core.h','pipeline_trace.h'):shutil.copyfile(here/name,root/('aura_'+name))
 shutil.copyfile(here/'test_pipeline_windows.h',root/'windows.h')
 exe=root/'test'
 subprocess.run(['c++','-std=c++17','-O1','-g','-Wall','-Wextra','-Werror','-Wno-missing-field-initializers','-fsanitize=address,undefined','-fno-pie','-no-pie','-pthread','-I'+str(root),'-I'+str(a.source/'include/vulkan/include'),str(here/'test_pipeline_trace.cpp'),'-o',str(exe)],check=True)
 results={}
 private=root/'wine-private';private.mkdir()
 for mode in ('active','disabled','expired','bad-header','missing','old-android-absolute'):
  folder=(root if mode=='old-android-absolute' else private)/mode;folder.mkdir();control=bytearray(64);struct.pack_into('<8s6IQ',control,0,b'AURAVP1\0',1,64,int(mode!='disabled'),0,0,0,999999 if mode=='expired' else 1120000)
  if mode=='bad-header':control[0]=0
  (folder/'.pipeline-control').write_bytes(control);(folder/'.pipeline-slots').write_bytes(bytes(65536))
  env=dict(os.environ,ASAN_OPTIONS='detect_leaks=0',TRACE_ROOT=str(private));env.pop('TRACE_DIR',None)
  if mode!='missing':env['TRACE_DIR']='Z:'+str(folder).replace('/','\\') if mode=='old-android-absolute' else 'Z:\\'+mode
  r=subprocess.run([str(exe),mode],env=env,text=True,capture_output=True,timeout=30);assert r.returncode==0,(mode,r.stdout,r.stderr)
  c=(folder/'.pipeline-control').read_bytes();records=(folder/'.pipeline-slots').read_bytes();count=struct.unpack_from('<I',c,20)[0]
  if mode=='active':
   assert count==8192 and struct.unpack_from('<I',c,40)[0]==1
   assert struct.unpack_from('<I',c,44)[0]>=1
   for i in range(64):
    seq,kind,pid,tid,call,state,result,used=struct.unpack_from('<8I',records,i*1024)
    assert seq%2==0 and call<=8192 and (call-1)%64==i and state==2 and result==0
  else:assert count==0 and struct.unpack_from('<I',c,24)[0]==0 and not any(records)
  assert b'PRIVATE_ENTRY' not in records
  results[mode]=r.stdout.strip()
 a.output.write_text(json.dumps(dict(host_doubles=True,private_z_mapping=True,device_tested=False,results=results),indent=2)+'\n')
 print(json.dumps(results,indent=2))
