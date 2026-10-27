#!/usr/bin/env python3
"""Package the verified Mesa transform, preserving every other runtime file."""
import argparse, hashlib, io, json, subprocess, sys, tarfile
from pathlib import Path
import zstandard
from build import ROOT,HERE,baseline,transform
sys.path.insert(0,str(ROOT/'tools/arm64ec'))
from package_adrenotools import update_runtime_delivery
def sha(data): return hashlib.sha256(data).hexdigest()
p=argparse.ArgumentParser();p.add_argument('--binary',type=Path,required=True);args=p.parse_args()
record=json.loads((HERE/'build.json').read_text());data=args.binary.read_bytes()
assert transform(baseline(data,record))==(data,record)
assets=ROOT/'engine/src/main/assets/arm64ec';manifest=json.loads((assets/'manifest.json').read_text())
logical='usr/lib/libGL.so.1.5.0';archive=assets/'runtime.tzst'
assert manifest['files'][logical]['sha256'] in (record['originalSha256'],record['sha256'])
assert sha(archive.read_bytes())==manifest['archives']['runtime.tzst']['sha256']
replacements={logical:data}
prefix='usr/share/licenses/aura-mesa-bo-cache/'
for name in ('build.json','bo-cache.patch'):replacements[prefix+name]=(HERE/name).read_bytes()
seen=set();preserved=0;temporary=archive.with_suffix('.new')
try:
    with archive.open('rb') as raw,zstandard.ZstdDecompressor().stream_reader(raw) as reader:
        with temporary.open('wb') as dst,zstandard.ZstdCompressor(level=10).stream_writer(dst) as writer:
            with tarfile.open(fileobj=reader,mode='r|') as source,tarfile.open(fileobj=writer,mode='w|') as target:
                for entry in source:
                    name=entry.name.removeprefix('./');payload=source.extractfile(entry).read() if entry.isfile() else None
                    if payload is not None:assert manifest['files'][name]==dict(bytes=len(payload),sha256=sha(payload)),name
                    if name in replacements:
                        payload=replacements[name];entry.size=len(payload);seen.add(name)
                    elif payload is not None:preserved+=1
                    target.addfile(entry,io.BytesIO(payload) if payload is not None else None)
                assert logical in seen
                for name,payload in replacements.items():
                    if name not in seen:
                        assert name.startswith(prefix)
                        entry=tarfile.TarInfo(name);entry.size=len(payload);entry.mode=0o644
                        target.addfile(entry,io.BytesIO(payload))
            while reader.read(1024*1024):pass
    for name,payload in replacements.items():manifest['files'][name]=dict(bytes=len(payload),sha256=sha(payload))
    manifest['mesaBoCache']=record
    manifest['archives']['runtime.tzst']=dict(bytes=temporary.stat().st_size,sha256=sha(temporary.read_bytes()))
    temporary.replace(archive)
    (assets/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,sort_keys=True,indent=2)+'\n')
finally:temporary.unlink(missing_ok=True)
lockpath=ROOT/'tools/arm64ec/sources.lock.json';lock=json.loads(lockpath.read_text())
lock['builtOverrides']['mesa-bo-cache']=dict(revision=record['revision'],method=record['method'],
    build='../mesa-bo-cache/build.json',files={logical:manifest['files'][logical]})
lockpath.write_text(json.dumps(lock,indent=2)+'\n')
subprocess.run([sys.executable,str(ROOT/'tools/prepare_engine.py')],check=True)
update_runtime_delivery()
print(json.dumps(dict(revision=record['revision'],otherFilesByteIdentical=preserved,deviceTested=False)))
