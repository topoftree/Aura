#!/usr/bin/env python3
"""Resolve the helper's imports against the packaged Wine ARM64X x64 export view.
This is a static ABI check, not an execution/compatibility test.
"""
import argparse, hashlib, json, pathlib, sys, tarfile, zipfile
import zstandard
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'arm64ec'))
from pe_dependencies import PeImage, resolve_module

p = argparse.ArgumentParser(); p.add_argument('apk',type=pathlib.Path)
p.add_argument('--helper', type=pathlib.Path)
p.add_argument('--build-record', type=pathlib.Path)
args = p.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
helper_path = args.helper or root/'app/src/main/assets/steam-session/aura-steam-session.exe'
helper = helper_path.read_bytes()
images, hashes = {}, {}
with zipfile.ZipFile(args.apk) as apk, apk.open('assets/arm64ec/runtime.tzst') as compressed:
    with zstandard.ZstdDecompressor().stream_reader(compressed) as stream, tarfile.open(fileobj=stream,mode='r|') as tar:
        for item in tar:
            name = item.name.removeprefix('./')
            if not item.isfile() or not name.startswith('opt/wine/lib/wine/aarch64-windows/') or not name.endswith('.dll'): continue
            data = tar.extractfile(item).read(); base=pathlib.PurePosixPath(name).name.lower()
            images[base]=PeImage(data).ec_view(); hashes[base]=hashlib.sha256(data).hexdigest()
modules={name: image.exports() for name,image in images.items()}
api_sets=images['apisetschema.dll'].api_sets()
used=set()
def resolve(name, symbol, importer, seen=()):
    target=resolve_module(name,importer,modules,api_sets)
    assert target is not None, (name,symbol,'missing module')
    assert symbol in modules[target], (target,symbol,'missing export')
    assert (target,symbol) not in seen
    used.add(target)
    forward=modules[target][symbol]
    if forward:
        name,_,symbol=forward.rpartition('.')
        return resolve(name,symbol,target,seen+((target,symbol),))
    return target+'!'+symbol
results=[]
for entry in PeImage(helper).imports():
    for symbol in entry['symbols']:
        results.append(dict(module=entry['module'],symbol=symbol,resolved=resolve(entry['module'],symbol,helper_path.name)))
expected = json.loads((args.build_record or root/'tools/steam-session/build.json').read_text())['imports']
assert {(x['module'].lower().removesuffix('.dll'), x['symbol']) for x in results} == {
    (module, symbol) for module, symbols in expected.items() for symbol in symbols}
print(json.dumps(dict(helperSha256=hashlib.sha256(helper).hexdigest(),imports=len(results),resolved=results,
    wineDllHashes={name:hashes[name] for name in sorted(used)},
    limits='Static exports/forwarders through the ARM64X x64 view; no Wine, FEX or Steam process executed.'),indent=2))
