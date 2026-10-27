#!/usr/bin/env python3
"""Reproducible no-CRT x64 control probe; uses only Windows public networking APIs."""
import argparse, hashlib, json, pathlib, subprocess, tempfile
root = pathlib.Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--llvm', type=pathlib.Path, required=True)
args = p.parse_args()
source = root / 'tools/network-diagnostics/network_probe.c'
target = root / 'app/src/main/assets/network-diagnostics/aura-network-probe.exe'
imports = {
    'kernel32': ['GetStdHandle', 'WriteFile', 'GetLastError', 'GetTickCount64', 'ExitProcess'],
    'ws2_32': ['WSAStartup', 'WSACleanup', 'WSAGetLastError', 'getaddrinfo', 'freeaddrinfo',
               'socket', 'ioctlsocket', 'connect', 'select', 'getsockopt', 'closesocket'],
    'winhttp': ['WinHttpOpen', 'WinHttpSetTimeouts', 'WinHttpSetOption', 'WinHttpConnect',
                'WinHttpOpenRequest', 'WinHttpSendRequest', 'WinHttpReceiveResponse',
                'WinHttpQueryHeaders', 'WinHttpCloseHandle'],
}
with tempfile.TemporaryDirectory(prefix='aura-network-build-') as temp:
    directory = pathlib.Path(temp)
    for dll, names in imports.items():
        definition = directory / (dll + '.def')
        definition.write_text('LIBRARY ' + dll + '.dll\nEXPORTS\n' + '\n'.join(names) + '\n')
        subprocess.run([str(args.llvm/'llvm-dlltool'), '-m', 'i386:x86-64', '-d', str(definition), '-l', str(directory/(dll+'.lib'))], check=True)
    obj = directory / 'probe.obj'
    subprocess.run([str(args.llvm/'clang'), '--target=x86_64-pc-windows-msvc', '-c', str(source), '-o', str(obj),
        '-Oz', '-ffreestanding', '-fno-stack-protector', '-fno-ident', '-Wall', '-Wextra', '-Werror'], check=True)
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(args.llvm/'lld-link'), str(obj), *[str(directory/(dll+'.lib')) for dll in imports],
        '/entry:mainCRTStartup', '/subsystem:windows', '/nodefaultlib', '/machine:x64', '/Brepro',
        '/timestamp:0', '/opt:ref', '/opt:icf', '/out:'+str(target)], check=True)
record = dict(source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
              pe_sha256=hashlib.sha256(target.read_bytes()).hexdigest(), bytes=target.stat().st_size,
              imports=imports, architecture='AMD64', runtime_validation='NOT_RUN_ON_ANDROID')
(source.parent/'build.json').write_text(json.dumps(record, indent=2)+'\n')
print(json.dumps(record, indent=2))
