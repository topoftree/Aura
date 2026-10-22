#!/usr/bin/env python3
"""Assemble a pinned Android/Bionic ARM64EC runtime. No payload is executed.

Input cache must contain the audited WCPs, termux dependency extraction/source
manifest, and the pinned Winlator-Bionic graphics archives. Native code is staged
by prepare_engine.py into PackageManager's executable directory after packaging.
"""
import argparse, hashlib, io, json, os, pathlib, posixpath, re, shutil, subprocess, sys, tarfile
import zstandard
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from audit_runtime_candidate import elf_info
from runtime_paths import relocate_runtime_paths
from rebuild_sysvshm import build as build_sysvshm
from build_sdl_gamepad import build as build_sdl_gamepad
from package_sechost import stage_from_cache as stage_sechost
from package_media import stage_from_cache as stage_media
from package_dxvk import stage_from_cache as stage_dxvk, write_catalogue as write_dxvk_catalogue
from package_dxvk_batching import stage_from_cache as stage_dxvk_batching, write_catalogue as write_dxvk_batching_catalogue
from package_wait_queue import stage_from_cache as stage_wait_queue, write_catalogue as write_wait_queue_catalogue
REPO = pathlib.Path(__file__).resolve().parents[2]
SYSTEM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libaaudio.so', 'libnativewindow.so', 'libz.so', 'libGLESv2.so', 'libEGL.so', 'libmediandk.so', 'libOpenSLES.so'}
SOURCE_COMMIT = 'da12b7a0a9168261656a71a6d68f9f08f77d6304'

def digest(path):
    with open(path, 'rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()

def archive(path):
    f = path.open('rb')
    r = zstandard.ZstdDecompressor().stream_reader(f) if path.suffix in ('.wcp','.tzst') else f
    with f, r, tarfile.open(fileobj=r, mode='r|*') as t:
        for m in t:
            name = m.name.removeprefix('./')
            if '..' in pathlib.PurePosixPath(name).parts or name.startswith('/'): raise ValueError(name)
            if m.isfile():
                if m.size > 150*1024**2: raise ValueError('oversized entry '+name)
                yield name, t.extractfile(m).read(), None
            elif m.issym() or m.islnk(): yield name, None, m.linkname

def put(root, name, data):
    p=root/name; p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes(data)

def pack(root, target):
    with target.open('wb') as f, zstandard.ZstdCompressor(level=10).stream_writer(f) as z, tarfile.open(fileobj=z,mode='w|') as t:
        for p in sorted(root.rglob('*')):
            m=t.gettarinfo(str(p),arcname=p.relative_to(root).as_posix());m.uid=m.gid=m.mtime=0;m.uname=m.gname='';m.mode=0o755 if p.is_dir() or p.is_symlink() else 0o644
            if p.is_file() and not p.is_symlink():
                with p.open('rb') as f: t.addfile(m,f)
            else:t.addfile(m)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--cache',type=pathlib.Path,default=pathlib.Path('/tmp'));ap.add_argument('--stage',type=pathlib.Path,required=True);ap.add_argument('--ndk',type=pathlib.Path,required=True)
    a=ap.parse_args(); a.stage.mkdir(parents=True,exist_ok=False)
    root=a.stage/'root';prefix=a.stage/'prefix';dx=a.stage/'dx';root.mkdir();prefix.mkdir();dx.mkdir()
    evidence=json.loads((REPO/'docs/validation/runtime-migration/candidates.json').read_text())['candidates']
    lock=json.loads((REPO/'tools/arm64ec/sources.lock.json').read_text())
    for filename,expected in lock['legacyInputs'].items():assert digest(REPO/'engine/src/main/assets'/filename)==expected,filename
    sources={}; native={}; links={}
    files={'proton-arm64ec':'aura-proton-arm64ec.wcp','fexcore':'aura-fexcore-2610.wcp','dxvk-arm64ec':'aura-dxvk-arm64ec.wcp','vkd3d-arm64ec':'aura-vkd3d-arm64ec.wcp'}
    for kind,name in files.items():
        p=a.cache/name; assert digest(p)==evidence[kind]['sha256'],name;sources[kind]={k:evidence[kind][k] for k in ('url','sha256','bytes')}
        for name,data,link in archive(p):
            if kind=='proton-arm64ec':
                if name.startswith(('lib/wine/','share/wine/','share/licenses/','bin/')) and not name.endswith(('winewayland.so','winewayland.drv')):
                    dest='opt/wine/'+name
                    if link: links[dest]=link
                    elif data is not None: put(root,dest,data)
                elif name=='prefixPack.txz':
                    q=a.stage/'prefix.txz';q.write_bytes(data)
                    for n,b,l in archive(q):
                        if l: links['PREFIX/'+n]=l
                        elif b is not None:put(prefix,n,b)
            elif kind=='fexcore':
                if name.startswith('system32/') and data is not None:
                    put(root,'opt/wine/lib/wine/aarch64-windows/'+pathlib.Path(name).name,data)
                    put(prefix,'.wine/drive_c/windows/'+name,data)
                elif name.startswith('aarch64-unix/') and data is not None:put(root,'opt/wine/lib/wine/'+name,data)
            elif name.startswith(('system32/','syswow64/')) and data is not None:put(dx,name,data)
    dxvk_d3d9 = stage_dxvk(dx, a.cache)
    dxvk_d3d11 = stage_dxvk_batching(dx, a.cache)
    wine_wait_queue = stage_wait_queue(root, a.cache)
    # Winlator guest helpers and fonts retain upstream PE code, not an app fork.
    for name,data,link in archive(REPO/'engine/src/main/assets/rootfs_patches.tzst'):
        if data is not None and name.startswith('home/xuser/') and '/windows/' in name:
            put(prefix,name.removeprefix('home/xuser/'),data)
    # These PE DLLs consume Wine HID; the SDL Wine-process backend below receives
    # Winlator UDP. Copying these DLLs alone does NOT preserve the gamepad bridge.
    input_dlls={'xinput1_1.dll','xinput1_2.dll','xinput1_3.dll','xinput1_4.dll','xinput9_1_0.dll','dinput.dll','dinput8.dll'}
    for name,data,link in archive(REPO/'engine/src/main/assets/rootfs.tzst'):
        parts=name.split('/')
        if data is not None and len(parts)==6 and parts[:3]==['opt','wine','lib'] and parts[3]=='wine' and parts[-1] in input_dlls:
            if parts[4] in ('x86_64-windows','i386-windows'):put(prefix,'.wine/drive_c/windows/'+('system32' if parts[4]=='x86_64-windows' else 'syswow64')+'/'+parts[-1],data)
    deps=a.cache/'aura-bionic-deps';depindex=json.loads((deps/'source.json').read_text());sources['termux-dependencies']={k:depindex[k] for k in ('url','sha256','bytes')}
    for d in depindex['files']:
        if 'target' in d:links['usr/'+d['path']]=d['target']
        else:
            p=deps/d['path'];assert digest(p)==d['sha256'],d['path']; native['usr/'+d['path']]=p
    for asset in ['extra_libs.tzst','wrapper.tzst']:
        p=a.cache/('aura-bionic-'+asset)
        sources[asset]={'url':f'https://raw.githubusercontent.com/Pipetto-crypto/winlator/{SOURCE_COMMIT}/app/src/main/assets/graphics_driver/{asset}','sha256':digest(p),'bytes':p.stat().st_size}
        assert digest(p)==lock['sources'][asset]['sha256'],asset
        for name,data,link in archive(p):
            if data is None:continue
            if name.endswith('libvkbasalt.so') or '/implicit_layer.d/' in name or name.endswith('libutil_layer.so'):continue
            put(root,name,data)
    # Build the shared-memory client against the current Android NDK, including 16KB alignment.
    tc=a.ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin';shm=root/'usr/lib/libandroid-sysvshm.so';shm.parent.mkdir(parents=True,exist_ok=True)
    build_sysvshm(a.ndk, shm)
    build_sdl_gamepad(a.cache/'aura-sdl2-2.32.10.tar.gz', a.stage/'sdl-build',
                      root/'usr/lib/libSDL2.so', a.ndk)
    sources['sdl2'] = lock['sources']['sdl2']
    media_codecs = stage_media(root, a.cache)
    fex_src=REPO/'tools/arm64ec/fex-unix/FEXUnixLib.cpp'
    for name in ['libarm64ecfex.so','libwow64fex.so']:
        subprocess.run([str(tc/'aarch64-linux-android34-clang++'),'-shared','-fPIC','-O2','-std=c++20','-static-libstdc++','-Wl,-z,max-page-size=16384',str(fex_src),'-o',str(root/'opt/wine/lib/wine/aarch64-unix'/name)],check=True)
    # Tiny XCB helper required by the X11 Turnip WSI; cross-build its MIT source.
    subprocess.run([str(tc/'aarch64-linux-android34-clang'),'-shared','-fPIC','-O2','-Wl,-z,max-page-size=16384','-Wl,-soname,libxcb-keysyms.so','-I'+str(deps/'include'),'-I'+str(REPO/'tools/arm64ec/xcb-keysyms'),str(REPO/'tools/arm64ec/xcb-keysyms/keysyms.c'),'-L'+str(deps/'lib'),'-lxcb','-o',str(root/'usr/lib/libxcb-keysyms.so')],check=True)
    sources['xcb-keysyms']={'url':'https://deb.debian.org/debian/pool/main/x/xcb-util-keysyms/xcb-util-keysyms_0.4.1.orig.tar.xz','sha256':'7c260a5294412aed429df1da2f8afd3bd07b7cba3fec772fba15a613a6d5c638'}
    # Resolve all linked dependencies and Wine's dlopen libraries; fail on ABI mixing/missing files.
    byname={pathlib.Path(n).name:n for n in native}
    byname.update({pathlib.Path(n).name:n for n in links if n.startswith('usr/')})
    optional={'libfreetype.so','libfontconfig.so','libgnutls.so','libvulkan.so.1','libXcursor.so','libXrandr.so','libXinerama.so','libXi.so','libXrender.so','libXxf86vm.so','libc++_shared.so'}
    pending=list(optional);seen=set();required={}
    for p in root.rglob('*'):
        if p.is_file() and p.read_bytes()[:4]==b'\x7fELF':pending+=elf_info(p.read_bytes())['needed']
    # Media plugins are loaded by filename, not DT_NEEDED.
    for n in native:
        if n.startswith('usr/lib/gstreamer-1.0/'):
            put(root,n,native[n].read_bytes());pending+=elf_info(native[n].read_bytes()).get('needed',[])
    while pending:
        name=pending.pop()
        if name in SYSTEM or name in seen:continue
        seen.add(name)
        existing=next((p for p in root.rglob(name) if p.is_file()),None)
        if existing is None:
            for candidate in (root/'usr/lib').glob('*'):
                if not candidate.is_file() or candidate.read_bytes()[:4]!=b'\x7fELF':continue
                if name in elf_info(candidate.read_bytes()).get('soname',[]):
                    existing=root/'usr/lib'/name
                    existing.symlink_to(candidate.name);break
        if existing:continue
        if name not in byname:raise ValueError('Missing Bionic dependency: '+name)
        n=byname[name];visited=set()
        while n in links:
            if n in visited:raise ValueError('symlink cycle '+n)
            visited.add(n);target=links[n]
            n=posixpath.normpath(posixpath.join(posixpath.dirname(n),target))
        if n not in native:raise ValueError('Unresolved dependency '+name+' -> '+n)
        b=native[n].read_bytes();e=elf_info(b)
        if e['machine']!='0xb7' or e.get('abi') == 'glibc':raise ValueError('Wrong ABI '+n)
        put(root,'usr/lib/'+name,b);pending+=e.get('needed',[]);required[name]=n
    # Names expected by Wine and the Vulkan loader. Keep only internal archive links.
    for n,target in sorted(links.items()):
        base=prefix if n.startswith('PREFIX/') else root;n=n.removeprefix('PREFIX/')
        if n.startswith(('usr/','opt/')) and not n.startswith('opt/wine/'):continue
        dest=base/n
        if dest.exists() or dest.is_symlink():continue
        normalized=posixpath.normpath(posixpath.join(posixpath.dirname(n),target))
        if target.startswith('/') or normalized.startswith('../'):continue
        if (base/normalized).exists():dest.parent.mkdir(parents=True,exist_ok=True);dest.symlink_to(target)
    for name,target in {'usr/lib/libGL.so.1':'libGL.so.1.5.0','usr/lib/libGL.so':'libGL.so.1.5.0','usr/lib/libglapi.so.0':'libglapi.so.0.0.0','usr/tmp':'../tmp','usr/lib/libvulkan.so':'libvulkan.so.1'}.items():
        p=root/name
        if p.is_symlink():p.unlink()
        if not p.exists():p.parent.mkdir(parents=True,exist_ok=True);p.symlink_to(target)
    for n in ['tmp','home','etc','usr/share/fonts','usr/etc','usr/local/bin']: (root/n).mkdir(parents=True,exist_ok=True)
    for name in ['headers-source.json','metadata-source.json']:
        metadata=json.loads((deps/name).read_text())
        assert metadata['sha256']==lock['sources']['termux-dependencies']['sha256']
        for item in metadata['files']:
            if 'sha256' in item:assert digest(deps/item['path'])==item['sha256'],item['path']
    # Keep licences with the native payload; no app source is imported from the dependency bundle.
    for p in (deps/'share').rglob('*'):
        if p.is_file() and ('licenses' in p.parts or p.name.lower() in ('copyright', 'copying', 'license', 'notice') or p.name.lower().startswith(('license.', 'copying.'))):
            put(root,'usr/'+p.relative_to(deps).as_posix(),p.read_bytes())
    for sub in ['sysvshm','fex-unix','xcb-keysyms','sdl-gamepad']:
        for name in ['LICENSE','COPYING']:
            p=REPO/'tools/arm64ec'/sub/name
            if p.exists():put(root,'usr/share/licenses/aura-'+sub+'/'+name,p.read_bytes())
    # Fontconfig searches both Wine fonts and the device fonts, not the Termux installation.
    fonts = b'''<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd"><fontconfig>
<dir>/system/fonts</dir><dir>/data/data/com.android.aura/files/rootfs-arm64ec/opt/wine/share/wine/fonts</dir>
<dir>/data/data/com.android.aura/files/rootfs-arm64ec/home/xuser/.wine/drive_c/windows/Fonts</dir>
<cachedir>/data/data/com.android.aura/files/rootfs-arm64ec/home/xuser/.cache/fontconfig</cachedir>
</fontconfig>'''
    put(root,'usr/etc/fonts/fonts.conf',fonts)
    # Same-length relocation is deliberate: no ELF offsets or instruction addresses change.
    old=b'/data/data/com.termux/files/usr';new=b'/data/data/com.android.aura/f/r';assert len(new)<=len(old)
    new+=b'/'*(len(old)-len(new));relocated=0
    for p in root.rglob('*'):
        if not p.is_file() or p.is_symlink():continue
        b=p.read_bytes()
        mesa_prefix=b"/data/data/com.winlator.cmod/files/imagefs/usr"
        if mesa_prefix in b:b=b.replace(mesa_prefix,new+b"/"*(len(mesa_prefix)-len(new)))
        home_prefix=b"/data/data/com.termux/files/home"
        home_target=b"/data/data/com.android.aura/f/h"
        b=b.replace(home_prefix,home_target+b"/"*(len(home_prefix)-len(home_target)))
        b=relocate_runtime_paths(b)
        p.write_bytes(b)
        if old in b:b=b.replace(old,new);p.write_bytes(b);relocated+=1
        if b[:4]==b'\x7fELF':subprocess.run([str(tc/'llvm-strip'),'--strip-debug',str(p)],check=True)
    # Replace template Termux paths with font basenames. WineBuiltinFonts must copy the
    # bundled TTFs into Windows/Fonts before Wine starts: only GDI searches Wine's data
    # directory; DirectWrite resolves these registered basenames strictly in the prefix.
    # WineBuiltinFontRegistry also retires matching HKCU External Fonts ownership before
    # launch; otherwise Wine's GDI startup removes these newly local HKLM registrations.
    for p in prefix.rglob('*.reg'):
        s=p.read_text(errors='strict');s=re.sub(r'Z:\\\\data\\\\data\\\\com\.termux[^"\n]*\\\\([^\\"\n]+\.(?:ttf|fon))',r'\1',s,flags=re.I);p.write_text(s)
    protected = {p.name.lower() for folder in ['aarch64-windows','i386-windows'] for p in (root/'opt/wine/lib/wine'/folder).iterdir() if p.is_file()}
    for p in (prefix/'.wine/drive_c/windows').rglob('*'):
        if p.is_file() and not p.is_symlink() and p.name.lower() in protected and p.name.lower() not in input_dlls:
            p.unlink()
    # Wine boot regenerates DOS links; the archive never contains links into another app/container.
    shutil.rmtree(prefix/'.wine/dosdevices', ignore_errors=True)
    service_repair = stage_sechost(root, a.cache)
    dest=REPO/'engine/src/main/assets/arm64ec';dest.mkdir(exist_ok=True)
    pack(root,dest/'runtime.tzst');pack(prefix,dest/'prefix.tzst');pack(dx,dest/'directx.tzst')
    manifest={'schema':1,'id':'proton11-fex2610-arm64ec-v1','abi':'bionic-arm64ec','wine':'11.0-2','fex':'2610','dxvk':'2.7.1','vkd3d':'3.0.1','turnip':'25.2.0-devel-6defe2244c','zink':'24.3.0-devel','sources':sources,'dependencyOrigins':required,'relocatedFiles':relocated,'files':{},'archives':{}}
    manifest['wineServiceRepair'] = service_repair
    manifest['mediaCodecs'] = media_codecs
    manifest['dxvkD3D9'] = dxvk_d3d9
    manifest['dxvkD3D11'] = dxvk_d3d11
    manifest['wineWaitQueue'] = wine_wait_queue
    write_dxvk_catalogue(dest, dxvk_d3d9)
    write_dxvk_batching_catalogue(dest, dxvk_d3d11)
    write_wait_queue_catalogue(dest, wine_wait_queue)
    for p in root.rglob('*'):
        if p.is_file() and not p.is_symlink():manifest['files'][p.relative_to(root).as_posix()]={'sha256':digest(p),'bytes':p.stat().st_size}
    for p in dest.glob('*.tzst'):manifest['archives'][p.name]={'sha256':digest(p),'bytes':p.stat().st_size}
    (dest/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,sort_keys=True,indent=2)+'\n')
    print(json.dumps({'archives':manifest['archives'],'dependencies':len(required),'files':len(manifest['files'])},indent=2))
if __name__=='__main__':main()
