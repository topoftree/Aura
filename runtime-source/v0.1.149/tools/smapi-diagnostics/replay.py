#!/usr/bin/env python3
import argparse, json, os, shutil, subprocess, tempfile
from pathlib import Path
p=argparse.ArgumentParser(); p.add_argument('--toolchain',type=Path,required=True); a=p.parse_args(); t=a.toolchain
here=Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix='smapi-readback-') as tmp:
    out=Path(tmp)
    refs=list((t/'ref/net6.0').glob('*.dll'))+[t/'StardewModdingAPI.dll',t/'SMAPI.Toolkit.CoreInterfaces.dll']
    subprocess.run([str(t/'runtime/dotnet'),str(t/'compiler/tasks/netcore/bincore/csc.dll'),'-nologo','-target:exe','-langversion:12','-nullable:enable','-out:'+str(out/'replay.dll')]+['-r:'+str(r) for r in refs]+[str(here/'Probe.cs'),str(here/'Replay.cs')],check=True,env=os.environ|{'DOTNET_ROLL_FORWARD':'Major'})
    for n in ['StardewModdingAPI.dll','SMAPI.Toolkit.CoreInterfaces.dll','SMAPI.Toolkit.dll']: shutil.copy(t/n,out/n)
    (out/'replay.runtimeconfig.json').write_text(json.dumps({'runtimeOptions':{'tfm':'net8.0','framework':{'name':'Microsoft.NETCore.App','version':'8.0.21'}}}))
    subprocess.run([str(t/'runtime/dotnet'),str(out/'replay.dll')],check=True)
