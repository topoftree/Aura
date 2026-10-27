#!/usr/bin/env python3
"""Verify the shipped module, Android dependencies, and reproducible patch input.

Structural/source checks only: no assertion of AHB import or game performance.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
from build import digest, HERE, LOCK, ROOT
from patch_mesa import patch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--readelf', default='readelf')
    parser.add_argument('--archive', type=Path, required=True)
    args = parser.parse_args()
    metadata = json.loads((ROOT / 'engine/src/main/assets/arm64ec/shared-gpu.json').read_text())
    native = ROOT / 'engine/src/main/jniLibs/arm64-v8a'
    module = native / metadata['filename']
    assert module.stat().st_size == metadata['bytes'] and digest(module) == metadata['sha256']
    for name, sha in metadata['inputSha256'].items():
        assert digest(HERE / name) == sha, name
    assert digest(ROOT / 'engine/src/main/cpp/winlator/include/shared_gpu_protocol.h') == metadata['protocolSha256']
    for name, record in LOCK['androidLibraries'].items():
        assert digest(native / ('libaura_' + record['sha256'][:24] + '.so')) == record['sha256'], name
    def elf(flag):
        return subprocess.check_output([args.readelf, flag, '--wide', str(module)], text=True)
    headers, dynamic, symbols = elf('-l'), elf('-d'), elf('--dyn-syms')
    assert 'AArch64' in elf('-h')
    assert all(int(line.split()[-1], 16) >= 16384 for line in headers.splitlines() if line.strip().startswith('LOAD'))
    assert 'GLIBC_' not in symbols and 'RPATH' not in dynamic and 'RUNPATH' not in dynamic
    required = ['glXSwapBuffers', 'glXGetProcAddress', 'AHardwareBuffer_allocate',
                'AHardwareBuffer_sendHandleToUnixSocket', 'AHardwareBuffer_lock']
    assert all(name in symbols for name in required)
    needed = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
    assert needed.index('libandroid-sysvshm.so') < needed.index('libc.so')
    assert set(needed) <= set(LOCK['androidLibraries']) | {'libandroid.so', 'libz.so', 'libm.so', 'libdl.so', 'libc.so'}
    normal = json.loads((ROOT / 'engine/src/main/assets/arm64ec/manifest.json').read_text())['files']['usr/lib/libGL.so.1.5.0']
    cache_fix = json.loads((ROOT / 'tools/mesa-bo-cache/build.json').read_text())
    assert normal['sha256'] == cache_fix['sha256']
    assert digest(native / ('libaura_' + normal['sha256'][:24] + '.so')) == normal['sha256']
    assert digest(args.archive) == LOCK['mesa']['sha256']
    paths = ['src/gallium/drivers/zink/' + name for name in
        ['meson.build', 'zink_device_info.py', 'zink_types.h', 'zink_context.c', 'zink_screen.c']]
    paths += ['src/gallium/targets/libgl-xlib/meson.build', 'src/gallium/frontends/glx/xlib/xm_st.c']
    with tempfile.TemporaryDirectory() as temp, tarfile.open(args.archive) as archive:
        temp = Path(temp)
        for path in paths:
            target = temp / path; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.extractfile('mesa-' + LOCK['mesa']['commit'] + '/' + path).read())
        patch(temp)
        types = temp / 'src/gallium/drivers/zink/zink_types.h'
        fixed_layout = 'struct zink_context {\n   struct pipe_context base;\n   void *aura_present;\n   bool aura_present_disabled;'
        assert fixed_layout in types.read_text()
        before = {f.relative_to(temp).as_posix(): digest(f) for f in temp.rglob('*') if f.is_file()}
        patch(temp)
        after = {f.relative_to(temp).as_posix(): digest(f) for f in temp.rglob('*') if f.is_file()}
        assert before == after
        # Updating a prior work tree must repair the old layout, not duplicate fields.
        types.write_text(types.read_text().replace(fixed_layout,
            'struct zink_context {\n   void *aura_present;\n   bool aura_present_disabled;\n   struct pipe_context base;'))
        patch(temp)
        assert before == {f.relative_to(temp).as_posix(): digest(f) for f in temp.rglob('*') if f.is_file()}
    print(json.dumps(dict(moduleSha256=metadata['sha256'], bytes=metadata['bytes'],
        architecture='AArch64', minimumLoadAlignment=16384, needed=needed,
        normalMesaSha256=normal['sha256'], normalMesaRevision=cache_fix['revision'],
        inputHashesVerified=True, patchIdempotent=True, legacyLayoutMigrated=True,
        deviceImportTested=False, frameRateBenefitTested=False), indent=2))


if __name__ == '__main__':
    main()
