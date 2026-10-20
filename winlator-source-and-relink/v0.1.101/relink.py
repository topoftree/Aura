#!/usr/bin/env python3
# Copyright 2026 Aura. Recombination tooling: LGPL-2.1; see engine/LICENSE.
"""Rebuild the Winlator engine and recombine it with Aura 0.1.101 object code.

Never reads the private Aura project, its history, or its signing configuration.
The output is deliberately unsigned; the recipient chooses their signing key.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
KNOWN_APKS = {
    '18b76257c253e5f441ccc579cc646a0e505b554f736c217c2c601406977a0af5',
}

def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def run(args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)

def checked_apk(path):
    path = path.resolve()
    if digest(path) not in KNOWN_APKS:
        raise ValueError('Base APK must be the documented original 0.1.101 build')
    return path

def safe_relative(name):
    p = PurePosixPath(name)
    if p.is_absolute() or '..' in p.parts or '\\' in name:
        raise ValueError('Unsafe archive entry')
    return p

def bootstrap(base):
    """Extract already-distributed runtime inputs; preserve subsequent edits."""
    built = {'libomp.so'}  # Supplied by the selected NDK's OpenMP target.
    for p in (ROOT/'engine/src/main/cpp').rglob('CMakeLists.txt'):
        for name, options in re.findall(r'add_library\(([^\s)]+)([^)]*)\)', p.read_text(), re.S):
            if 'IMPORTED' not in options:
                built.add('lib'+name+'.so')
    copied = 0
    with zipfile.ZipFile(base) as z:
        for entry in z.infolist():
            if entry.is_dir(): continue
            name = entry.filename
            if name.startswith('assets/'):
                if name.startswith('assets/dexopt/'): continue
                target = ROOT/'engine/src/main/assets'/safe_relative(name[7:])
            elif name.startswith('lib/arm64-v8a/'):
                if PurePosixPath(name).name in built: continue
                target = ROOT/'engine/src/main/jniLibs/arm64-v8a'/safe_relative(name[14:])
            else: continue
            if target.exists(): continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(z.read(entry))
            copied += 1
    print(f'Prepared {copied} runtime inputs; existing edits were preserved.')

def tooling():
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
    java = Path(os.environ.get('JAVA_HOME') or '')
    if not (java/'bin/java').is_file():
        raise ValueError('Set JAVA_HOME to JDK 21')
    android = sdk/'platforms/android-37.2/android.jar'
    if not android.is_file():
        raise ValueError('Install Android platform android-37.2 and set ANDROID_HOME')
    bt = sdk/'build-tools/37.0.0'
    if not (bt/'aapt2').is_file():
        raise ValueError('Install Android build-tools 37.0.0')
    gradle = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home()/'.gradle')))
    jars = list((gradle/'caches/modules-2/files-2.1/com.android.tools.build/builder/9.4.1').glob('*/*.jar'))
    if len(jars) != 1:
        raise ValueError('Run Gradle engine:assembleRelease first to resolve AGP 9.4.1')
    return java, android, bt, jars[0]

def jar_directory(source, destination):
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted(source.rglob('*.class')):
            z.write(p, p.relative_to(source).as_posix())

def assemble(base):
    java, android, bt, r8 = tooling()
    aar = ROOT/'engine/build/outputs/aar/engine-release.aar'
    if not aar.is_file(): raise ValueError('Build engine:assembleRelease first')
    work = ROOT/'build/relink'
    work.mkdir(parents=True, exist_ok=True)
    generated = work/'generated'
    classes = work/'r-classes'
    for p in [generated, classes]:
        if p.exists(): shutil.rmtree(p)
        p.mkdir()
    # Overlay only actual library edits. Unchanged library resources must not
    # override application resources that won the original Gradle merge.
    baseline = json.loads((ROOT/'objects/engine-resources.json').read_text())
    overlay = work/'resource-edits'
    if overlay.exists(): shutil.rmtree(overlay)
    overlay.mkdir()
    changes = 0
    for source in (ROOT/'engine/src/main/res').rglob('*'):
        if not source.is_file(): continue
        name = source.relative_to(ROOT/'engine/src/main/res').as_posix()
        previous = baseline.get(name,{})
        if digest(source)==previous.get('sha256'): continue
        destination = overlay/name
        if source.parent.name.startswith('values') and source.suffix=='.xml':
            root = ET.parse(source).getroot()
            changed = ET.Element('resources', root.attrib)
            for child in root:
                key = child.tag+':'+child.attrib.get('name','')
                checksum = hashlib.sha256(ET.tostring(child)).hexdigest()
                if checksum != previous.get('entries',{}).get(key): changed.append(child)
            if not len(changed): continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            ET.ElementTree(changed).write(destination, encoding='utf-8', xml_declaration=True)
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source,destination)
        changes += 1
    resource_inputs = ['-R', ROOT/'objects/compiled-resources.zip']
    if changes:
        run([bt/'aapt2', 'compile', '--legacy', '--dir', overlay, '-o', work/'engine-res.zip'])
        resource_inputs += ['-R', work/'engine-res.zip']
    run([bt/'aapt2', 'link', '--proto-format', '--auto-add-overlay', '--non-final-ids',
         '-I', android, '--manifest', ROOT/'objects/AndroidManifest.xml',
         '--stable-ids', ROOT/'objects/stable-ids.txt',
         '--output-text-symbols', work/'R.txt',
         *resource_inputs,
         '--min-sdk-version', '34', '--target-sdk-version', '37',
         '--version-code', '102', '--version-name', '0.1.101',
         '-o', work/'resources-proto.ap_'])
    # Generate only the engine's non-transitive R fields, with final linked IDs.
    # Including every dependency's R field would unnecessarily retain resources.
    linked = {}
    for line in (work/'R.txt').read_text().splitlines():
        kind, group, name, value = line.split(maxsplit=3)
        linked[(group,name)] = (kind,value)
    fields = {}
    with zipfile.ZipFile(aar) as z:
        for line in z.read('R.txt').decode().splitlines():
            kind, group, name, _ = line.split(maxsplit=3)
            if (group,name) not in linked:
                raise ValueError('Missing linked engine resource: '+group+'/'+name)
            linked_kind,value = linked[(group,name)]
            if linked_kind != kind: raise ValueError('Resource type changed: '+name)
            fields.setdefault(group,[]).append(f'public static {kind} {name} = {value};')
    resource_java = generated/'R.java'
    resource_java.write_text('package com.winlator;\npublic final class R {\n'+
        '\n'.join('public static final class '+g+' {\n'+'\n'.join(v)+'\n}' for g,v in fields.items())+'\n}\n')
    # IDs are deliberately non-final, as required by optimized resource shrinking.
    run([java/'bin/javac', '--release', '17', '-d', classes, resource_java])
    jar_directory(classes, work/'engine-r.jar')
    with zipfile.ZipFile(aar) as z:
        (work/'engine-classes.jar').write_bytes(z.read('classes.jar'))
    args = [java/'bin/java', '-Xmx4g', '-cp', r8, 'com.android.tools.r8.R8',
            '--release', '--min-api', '34', '--lib', android,
            '--classpath', ROOT/'objects/classpath.jar',
            '--pg-conf', ROOT/'objects/r8.pro', '--pg-map-output', work/'mapping.txt',
            '--android-resources', work/'resources-proto.ap_', work/'resources-shrunk.ap_',
            '--output', work/'optimized.zip',
            ROOT/'objects/aura-1.jar', ROOT/'objects/dependencies-1.jar',
            ROOT/'objects/dependencies-2.jar', work/'engine-classes.jar', work/'engine-r.jar']
    run(args)
    run([bt/'aapt2', 'convert', '--output-format', 'binary',
         '-o', work/'resources-binary.ap_', work/'resources-shrunk.ap_'])
    package_apk(base, aar, work, bt)

def package_apk(base, aar, work, bt):
    # AGP's AAR may strip prebuilts. Indexed runtime payloads must instead retain
    # the exact recipient-editable input bytes, or Wine startup hashes will fail.
    prebuilts = {('lib/arm64-v8a/'+p.name):p for p in
                 (ROOT/'engine/src/main/jniLibs/arm64-v8a').glob('*.so')}
    unsigned = work/'Aura-0.1.101-relinked-unaligned.apk'
    with zipfile.ZipFile(work/'resources-binary.ap_') as res, \
            zipfile.ZipFile(work/'optimized.zip') as code, \
            zipfile.ZipFile(aar) as engine, zipfile.ZipFile(base) as original, \
            zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as out:
        replacements = {}
        for z in [res, code]:
            for e in z.infolist():
                if not e.is_dir(): replacements[e.filename] = (z, e)
        for e in engine.infolist():
            if e.is_dir(): continue
            if e.filename.startswith('jni/'):
                replacements['lib/'+e.filename[4:]] = (engine, e)
            elif e.filename.startswith('assets/') and not e.filename.startswith('assets/dexopt/'):
                replacements[e.filename] = (engine, e)
        for e in original.infolist():
            name = e.filename
            if e.is_dir() or name in replacements or name in prebuilts: continue
            if re.fullmatch(r'classes\d*\.dex', name) or name.startswith('res/'): continue
            if name in ['resources.arsc', 'AndroidManifest.xml']: continue
            if name.startswith('assets/dexopt/'): continue  # Original profile refers to original DEX.
            if name.startswith('META-INF/') and (name.upper().endswith(('.RSA','.DSA','.EC','.SF')) or name in ['META-INF/MANIFEST.MF','META-INF/version-control-info.textproto']): continue
            out.writestr(e, original.read(e))
        for name,(z,e) in sorted(replacements.items()):
            if name in prebuilts: continue
            # resources.arsc must be stored and aligned on Android 11+.
            compression = zipfile.ZIP_STORED if name=='resources.arsc' else zipfile.ZIP_DEFLATED
            out.writestr(name,z.read(e),compress_type=compression)
        for name,path in sorted(prebuilts.items()):
            out.write(path,name,compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(unsigned) as z:
        index = json.loads(z.read('assets/aura-native-index.json'))
        for expected, library in index['files'].items():
            actual = hashlib.sha256(z.read('lib/arm64-v8a/'+library)).hexdigest()
            if actual != expected:
                raise ValueError('Runtime payload/index mismatch: '+library)
    result = work/'Aura-0.1.101-relinked-unsigned.apk'
    run([bt/'zipalign', '-f', '-P', '16', '4', unsigned, result])
    run([bt/'zipalign', '-c', '-P', '16', '4', result])
    report = {'base_apk_sha256': digest(base), 'relinked_apk_sha256': digest(result),
              'versionName':'0.1.101', 'versionCode':102,
              'r8_optimized':True, 'resource_shrinking':True,
              'engine_aar_sha256':digest(aar), 'signed':False,
              'device_installation_tested':False,
              'indexed_native_payloads_verified':len(index['files'])}
    (work/'build-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
    print('Unsigned output:',result)

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['bootstrap','assemble'])
    parser.add_argument('--base-apk',type=Path,required=True)
    args=parser.parse_args()
    base=checked_apk(args.base_apk)
    if args.action=='bootstrap': bootstrap(base)
    else: assemble(base)
