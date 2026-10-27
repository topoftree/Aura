#!/usr/bin/env python3
"""Replay actual old/new Wine mode publication and unchanged DXGI matching.

Host types/registry are stubs. No Android session, window or GPU is executed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import audit_virtual_display_modes as audit

MAIN = r'''
int main(int argc, char **argv) {
 assert(argc==6); unsetenv("WINE_CENTER_DISPLAY_MODES");
 if(atoi(argv[5])) setenv("SteamAppId","297130",1); else unsetenv("SteamAppId");
 limit_resolutions=atoi(argv[4]);
 DEVMODEW maximum={.dmSize=sizeof(DEVMODEW), .dmFields=DM_BITSPERPEL|DM_PELSWIDTH|DM_PELSHEIGHT|DM_DISPLAYFREQUENCY,
 .dmBitsPerPel=32,.dmDisplayFrequency=atoi(argv[3]),.dmPelsWidth=atoi(argv[1]),.dmPelsHeight=atoi(argv[2])};
 struct device_manager_ctx ctx={.is_primary=TRUE,.primary=maximum};
 UINT count; DEVMODEW *modes=get_virtual_modes(&maximum,&maximum,NULL,0,&count); assert(modes);
 saved_source=(struct source){.key=1,.state_flags=DISPLAY_DEVICE_ATTACHED_TO_DESKTOP};
 sources=1; registry_current=maximum; emulate_modeset=FALSE; emulate_modelist=FALSE;
 add_modes(&maximum,count,modes,&ctx);
 for(UINT i=0;i<registry_count;i++) if(registry_modes[i].dmBitsPerPel==32)
 printf("%u %u %u\n",registry_modes[i].dmPelsWidth,registry_modes[i].dmPelsHeight,registry_modes[i].dmDisplayFrequency);
 assert(registry_current.dmPelsWidth==maximum.dmPelsWidth && registry_current.dmPelsHeight==maximum.dmPelsHeight);
 free(modes); free(registry_modes); return 0;
}
'''
CPP_HEADER = r'''
#include <algorithm>
#include <numeric>
#include <limits>
#include <cstdint>
#include <cstdlib>
#include <vector>
#include <iostream>
constexpr int DXGI_MODE_SCANLINE_ORDER_UNSPECIFIED=0, DXGI_MODE_SCALING_UNSPECIFIED=0, DXGI_FORMAT_UNKNOWN=0;
struct Rate { uint32_t Numerator=0, Denominator=1; };
struct DXGI_MODE_DESC1 { uint32_t Width=0, Height=0; Rate RefreshRate; int Format=1, ScanlineOrdering=0, Scaling=0; bool Stereo=false; };
class DxgiOutput { public: static void FilterModesByDesc(std::vector<DXGI_MODE_DESC1>&,const DXGI_MODE_DESC1&); };
'''
CPP_MAIN = r'''
int main(int argc,char **argv) {
 if(argc!=4) return 1;
 std::vector<DXGI_MODE_DESC1> modes; DXGI_MODE_DESC1 mode;
 while(std::cin>>mode.Width>>mode.Height>>mode.RefreshRate.Numerator) modes.push_back(mode);
 DXGI_MODE_DESC1 target; target.Width=atoi(argv[1]); target.Height=atoi(argv[2]); target.RefreshRate.Numerator=atoi(argv[3]);
 DxgiOutput::FilterModesByDesc(modes,target); if(modes.empty()) return 1;
 std::cout<<modes[0].Width<<" "<<modes[0].Height<<" "<<modes[0].RefreshRate.Numerator<<"\n";
}
'''


def digest(data): return hashlib.sha256(data).hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('baseline','fixed','dxgi','report'): p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args()
    old,new,dxgi=[f.read_text() for f in (a.baseline,a.fixed,a.dxgi)]
    assert digest(old.encode())=='012e8af7fce66d186b5f23e1a49ec831c71bbe7a0ba8b1d4d3fe40181db8d3b5'
    assert digest(dxgi.encode())=='cd0342f370073ff1f2bf404f25fa8b030e566cf7eefd9dba8a9ea59ba8ec22e8'
    rows=[]
    with tempfile.TemporaryDirectory(prefix='aura-aspect-tests-') as directory:
        work=Path(directory)
        for tag,text in [('old',old),('new',new)]:
            helpers=text[text.index('static UINT devmode_get('):text.index('static BOOL write_source_mode(')]
            builder=text[text.index('static UINT add_screen_size('):text.index('static void add_modes(')]
            publication=text[text.index('static void add_modes('):text.index('static const struct gdi_device_manager device_manager =')]
            (work/(tag+'.c')).write_text(audit.HEADER+'\ntypedef unsigned long long ULONGLONG;\n'+helpers+builder+audit.REGISTRY_SHIMS+publication+MAIN)
            subprocess.run(['cc','-std=gnu11','-Wall','-Wextra','-Werror','-Wno-sign-compare',
                            '-fsanitize=undefined','-fno-sanitize-recover=all',str(work/(tag+'.c')),'-o',str(work/tag)],check=True)
        filter_body=dxgi[dxgi.index('  void DxgiOutput::FilterModesByDesc('):dxgi.index('  void DxgiOutput::CacheMonitorData()')]
        (work/'dxgi.cpp').write_text(CPP_HEADER+filter_body+CPP_MAIN)
        subprocess.run(['c++','-std=c++17','-Wall','-Wextra','-Werror',str(work/'dxgi.cpp'),'-o',str(work/'dxgi')],check=True)
        for w,h in [(1950,900),(1560,720),(2340,1080),(1920,1080),(2560,1080),(900,1950),(800,600),(640,480)]:
            for hz in (60,120):
                for limit,restricted in [(0,False),(5,False),(0,True)]:
                    modes={tag:[tuple(map(int,line.split())) for line in subprocess.check_output(
                        [str(work/tag),str(w),str(h),str(hz),str(limit),str(int(restricted))],text=True).splitlines()] for tag in ('old','new')}
                    added=set(modes['new'])-set(modes['old'])
                    assert set(modes['old'])<=set(modes['new']), 'existing modes lost'
                    if limit:
                        assert len({(x,y) for x,y,_ in modes['new']})<=limit
                        if len({(x,y) for x,y,_ in modes['old']})>=limit:
                            assert modes['new']==modes['old'], 'explicit list ordering changed'
                    for nw,nh,rate in added:
                        assert nw<=w and nh<=h and rate in {60,hz}
                        assert abs(nw*h-nh*w)<=max(w,h), 'aspect rounding exceeds one pixel'
                        if restricted: assert min(nw,nh)>=600 and max(nw,nh)>=800
                        assert modes['new'].count((nw,nh,rate))==1
                    row=dict(maximum=[w,h],refresh=hz,limit=limit,lowresRestricted=restricted,added=sorted(added))
                    if (w,h)==(1950,900) and not limit and not restricted:
                        row['closest']={}
                        for target in [(1170,540,60),(1560,720,60),(1950,900,hz),(960,540,60)]:
                            found={tag:tuple(map(int,subprocess.run([str(work/'dxgi'),*map(str,target)],
                                input=''.join('%d %d %d\n'%m for m in values),text=True,capture_output=True,check=True).stdout.split()))
                                for tag,values in modes.items()}
                            assert found['new']==target
                            if target==(1170,540,60): assert found['old']==(960,540,60)
                            row['closest']['x'.join(map(str,target))]=found
                    rows.append(row)
    result=dict(schema='WINE_ASPECT_MODE_REPLAY_V1',cases=len(rows),rows=rows,
                existingModesRetained=True,explicitLimitsPreserved=True,lowResolutionExclusionsPreserved=True,
                selectedCurrentModeUnchanged=True,wineSourceSha256=digest(new.encode()),
                dxgiSourceSha256=digest(dxgi.encode()),registryAndTypesStubbed=True,androidDeviceTested=False)
    a.report.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(dict(passed=len(rows),bf3Request=[1170,540,60],before=[960,540,60],after=[1170,540,60])))


if __name__=='__main__':main()
