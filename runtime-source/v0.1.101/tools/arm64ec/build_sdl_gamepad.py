#!/usr/bin/env python3
"""Build pinned SDL2 with Aura's Wine-process joystick backend (no APK/JNI UI).

Use --host for protocol integration tests on Linux. Android builds require NDK.
Only the source archive pinned in sources.lock.json is accepted.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def build(source_archive, stage, output, ndk=None):
    lock = json.loads((HERE / 'sources.lock.json').read_text())['sources']['sdl2']
    assert hashlib.sha256(source_archive.read_bytes()).hexdigest() == lock['sha256']
    stage.mkdir(parents=True, exist_ok=False)
    with tarfile.open(source_archive) as archive:
        archive.extractall(stage, filter='data')
    source = stage / 'SDL-release-2.32.10'
    cmake_file = source / 'CMakeLists.txt'
    cmake = cmake_file.read_text()
    # Keep public joystick/haptic APIs, using only our joystick and dummy haptic
    # backend. Android Java polling/USB/HID drivers cannot work in a Wine process.
    for option in ('SDL_JOYSTICK', 'SDL_HAPTIC'):
        cmake = cmake.replace(f'if({option})', f'if({option} AND NOT AURA_WINE_GAMEPAD)')
    cmake += '\nif(ANDROID)\n  target_link_libraries(SDL2 PRIVATE log android)\nendif()\n'
    cmake_file.write_text(cmake)
    shutil.copyfile(HERE / 'sdl-gamepad/SDL_aura_joystick.c', source / 'src/joystick/dummy/SDL_sysjoystick.c')
    sdl_c = source / 'src/SDL.c'
    content = sdl_c.read_text()
    assert 'static SDL_bool SDL_MainIsReady' in content
    # Wine loads SDL through dlopen, without SDLActivity/SDL_main.
    content = content.replace('static SDL_bool SDL_MainIsReady = SDL_FALSE;',
                              'static SDL_bool SDL_MainIsReady = SDL_TRUE; /* Aura Wine dlopen */')
    sdl_c.write_text(content)
    shutil.copyfile(HERE / 'sdl-gamepad/SDL_aura_android.c', source / 'src/core/android/SDL_android.c')
    # Wine uses actual filesystem paths, never an SDLActivity asset manager.
    rwops = source / 'src/file/SDL_rwops.c'
    content = rwops.read_text().replace('#ifdef __ANDROID__', '#if 0 /* Aura: libc files */')
    content = content.replace('#if defined(__ANDROID__)', '#if 0 /* Aura: libc files */')
    rwops.write_text(content)
    args = ['cmake', '-S', str(source), '-B', str(stage / 'build'),
            '-DCMAKE_BUILD_TYPE=Release', '-DAURA_WINE_GAMEPAD=ON',
            '-DSDL_SHARED=ON', '-DSDL_STATIC=OFF', '-DSDL_TEST=OFF',
            '-DSDL_JOYSTICK=ON', '-DSDL_HAPTIC=ON', '-DSDL_EVENTS=ON',
            '-DSDL_THREADS=ON', '-DSDL_TIMERS=ON', '-DSDL_LOADSO=ON']
    for option in ('AUDIO', 'VIDEO', 'RENDER', 'POWER', 'FILESYSTEM', 'LOCALE',
                   'MISC', 'SENSOR', 'HIDAPI', 'HIDAPI_JOYSTICK',
                   'VIRTUAL_JOYSTICK', 'LINUX_JOYSTICK', 'DBUS', 'IBUS',
                   'INSTALL', 'SDL2MAIN'):
        args.append(f'-DSDL_{option}=OFF')
    if ndk:
        args += ['-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
                 '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-34',
                 '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON',
                 '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384']
    subprocess.run(args, check=True)
    subprocess.run(['cmake', '--build', str(stage / 'build'), '--target', 'SDL2', '-j2'], check=True)
    library = next((stage / 'build').glob('libSDL2*.so'))
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(library, output)
    strip = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip' if ndk else 'strip'
    subprocess.run([str(strip), '--strip-debug', str(output)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--stage', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument('--ndk', type=Path)
    target.add_argument('--host', action='store_true')
    args = parser.parse_args()
    build(args.source.resolve(), args.stage.resolve(), args.output.resolve(),
          args.ndk.resolve() if args.ndk else None)


if __name__ == '__main__':
    main()
