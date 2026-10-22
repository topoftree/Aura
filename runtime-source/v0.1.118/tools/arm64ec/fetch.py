#!/usr/bin/env python3
"""Download pinned DATA inputs for assemble.py. Never execute downloaded payloads.

Only native libraries, build headers and licence metadata are read from the
Termux archive. No GameNative/Pluvia application source is imported.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import urllib.request

LOCK=json.loads(Path(__file__).with_name('sources.lock.json').read_text())['sources']
FILES={'proton-arm64ec':'aura-proton-arm64ec.wcp','fexcore':'aura-fexcore-2610.wcp',
       'dxvk-arm64ec':'aura-dxvk-arm64ec.wcp','vkd3d-arm64ec':'aura-vkd3d-arm64ec.wcp',
       'extra_libs.tzst':'aura-bionic-extra_libs.tzst','wrapper.tzst':'aura-bionic-wrapper.tzst',
       'wine-mono':'wine-mono-10.4.1-x86.msi', 'sdl2':'aura-sdl2-2.32.10.tar.gz',
       'icu-win64':'icu4c-68_2-Win64-MSVC2019.zip', 'icu-win32':'icu4c-68_2-Win32-MSVC2019.zip'}

def sha(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def download(cache,key,name):
    record=LOCK[key];target=cache/name
    if target.is_file() and sha(target)==record['sha256']:return
    partial=target.with_suffix(target.suffix+'.partial')
    with urllib.request.urlopen(record['url'],timeout=60) as response,partial.open('wb') as out:
        shutil.copyfileobj(response,out,1024**2)
    if sha(partial)!=record['sha256']:
        partial.unlink();raise ValueError('Source hash mismatch: '+key)
    if 'bytes' in record:assert partial.stat().st_size==record['bytes']
    partial.replace(target)
    print('Verified',name,flush=True)

class Hashed:
    def __init__(self,response):self.response=response;self.hash=hashlib.sha256();self.size=0
    def read(self,n=-1):
        b=self.response.read(n);self.hash.update(b);self.size+=len(b);return b

def dependencies(cache):
    final=cache/'aura-bionic-deps';record=LOCK['termux-dependencies']
    manifests=['source.json','headers-source.json','metadata-source.json']
    if all((final/m).is_file() for m in manifests):
        for name in manifests:
            m=json.loads((final/name).read_text());assert m['sha256']==record['sha256']
            for entry in m['files']:
                if 'sha256' in entry:assert sha(final/entry['path'])==entry['sha256'],entry['path']
        return
    staging=cache/'.aura-bionic-deps-fetch'
    if staging.exists():shutil.rmtree(staging)
    staging.mkdir();groups={n:[] for n in manifests}
    with urllib.request.urlopen(record['url'],timeout=60) as response:
        stream=Hashed(response)
        with tarfile.open(fileobj=stream,mode='r|') as tar:
            for member in tar:
                parts=PurePosixPath(member.name).parts
                if '..' in parts or 'usr' not in parts:continue
                relative=PurePosixPath(*parts[parts.index('usr')+1:]);path=str(relative)
                group=None
                if path.startswith('lib/') and '.so' in relative.name:group='source.json'
                elif path.startswith(('include/xcb/','include/X11/')):group='headers-source.json'
                elif path.startswith(('etc/fonts/','share/doc/','share/licenses/')) or path=='var/lib/dpkg/status':group='metadata-source.json'
                if not group:continue
                if member.isfile():
                    assert member.size<150*1024**2,path
                    data=tar.extractfile(member).read();assert len(data)==member.size
                    out=staging/relative;out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(data)
                    groups[group].append({'path':path,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
                elif member.issym() or member.islnk():
                    # Store descriptors only; never create or follow archive links in the cache.
                    groups[group].append({'path':path,'target':member.linkname,'hardlink':member.islnk()})
        while stream.read(1024**2):pass
        assert stream.size==record['bytes'] and stream.hash.hexdigest()==record['sha256'],'Dependency source hash mismatch'
    for name,items in groups.items():(staging/name).write_text(json.dumps(dict(record,files=items),indent=2)+'\n')
    assert not final.exists(),'Partial existing cache: move it aside before retrying'
    staging.rename(final);print('Verified native dependency archive',flush=True)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--cache',type=Path,required=True);args=ap.parse_args();args.cache.mkdir(parents=True,exist_ok=True)
    for key,name in FILES.items():download(args.cache,key,name)
    dependencies(args.cache)
if __name__=='__main__':main()
