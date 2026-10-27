#!/usr/bin/env python3
"""Exercise the shipped IPC child's x64 instructions with mock official API calls.

No Steam/Wine/Android runs. This checks the ABI calls, handle ownership, retry
ordering, paths and fail-closed results; phone interoperability remains untested.
"""
import hashlib, json, pathlib, struct, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'arm64ec'))
from pe_dependencies import PeImage
from unicorn import Uc, UC_ARCH_X86, UC_MODE_64, UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_RCX, UC_X86_REG_RDX, UC_X86_REG_R8, UC_X86_REG_R9, UC_X86_REG_RSP, UC_X86_REG_RAX, UC_X86_REG_RIP

root = pathlib.Path(__file__).resolve().parents[2]
data = (root / 'app/src/main/assets/steam-session/aura-steam-session.exe').read_bytes()
pe = PeImage(data)
header, = struct.unpack_from('<I', data, 60)
entry, = struct.unpack_from('<I', data, header + 24 + 16)
image_size, = struct.unpack_from('<I', data, header + 24 + 56)

def run(case, dll_after=0, factory_present=True, interface_present=True,
        pipe_after=0, user_after=0, release_ok=True, env_ok=True, incomplete_vtable=False):
    uc = Uc(UC_ARCH_X86, UC_MODE_64)
    uc.mem_map(pe.image_base, (image_size + 4095) & ~4095)
    uc.mem_write(pe.image_base, data[:pe.header_size])
    for _, rva, size, raw in pe.sections:
        if size: uc.mem_write(pe.image_base+rva, data[raw:raw+size])
    uc.mem_map(0x500000, 0x300000)
    uc.reg_write(UC_X86_REG_RSP, 0x6ff008)
    hooks = {}
    rva, size = pe.directory(1)
    for pos in range(rva, rva+size, 20):
        fields = struct.unpack_from('<IIIII', data, pe.offset(pos,20))
        if not any(fields): break
        for index, name in enumerate(pe.thunks(fields[0] or fields[4])):
            address = 0x710000 + len(hooks)*16; hooks[address] = name
            uc.mem_write(pe.image_base+fields[4]+index*8,struct.pack('<Q',address))
    official = ['factory', 'pipe', 'release_pipe', 'user', 'unused_local', 'release_user']
    targets = {name: 0x720000+i*16 for i,name in enumerate(official)}
    hooks.update({address:name for name,address in targets.items()})
    # ISteamClient's first five public slots; slot 3 must never be invoked.
    uc.mem_write(0x730000,struct.pack('<Q',0x730100))
    uc.mem_write(0x730100,struct.pack('<5Q',targets['pipe'],targets['release_pipe'],
        targets['user'],targets['unused_local'],0 if incomplete_vtable else targets['release_user']))
    client = r'C:\Steam 安装\steam.exe'
    environment = dict(AURA_STEAM_PROBE='1',AURA_STEAM_CLIENT=client,
        AURA_STEAM_STATUS=r'C:\.aura-steam-session\status.txt',AURA_STEAM_APPID='606150')
    states, calls, pipes, users = [], [], set(), set()
    sleeps, exit_code, loads = 0, None, 0
    def q(addr): return struct.unpack('<Q',uc.mem_read(addr,8))[0]
    def string(addr, width=2):
        if not addr: return None
        result = bytearray()
        for i in range(32768):
            ch = bytes(uc.mem_read(addr+i*width,width))
            if ch == bytes(width): return result.decode('utf-16-le' if width==2 else 'ascii')
            result += ch
        raise AssertionError('unterminated string')
    argc = dict(GetEnvironmentVariableW=3,SetEnvironmentVariableW=2,LoadLibraryExW=3,
        GetProcAddress=2,CreateFileW=7,WriteFile=5,FlushFileBuffers=1,CloseHandle=1,
        Sleep=1,ExitProcess=1,factory=2,pipe=1,release_pipe=2,user=2,release_user=3)
    def hook(machine,address,size,unused):
        nonlocal sleeps, exit_code, loads
        name = hooks.get(address)
        if name is None: return
        assert name in argc, ('unexpected API call', name)
        sp = uc.reg_read(UC_X86_REG_RSP)
        regs = [UC_X86_REG_RCX,UC_X86_REG_RDX,UC_X86_REG_R8,UC_X86_REG_R9]
        args = [uc.reg_read(regs[i]) if i<4 else q(sp+40+(i-4)*8) for i in range(argc[name])]
        result = 1
        if name == 'GetEnvironmentVariableW':
            value = environment.get(string(args[0]),''); result = len(value)
            assert result < args[2]
            uc.mem_write(args[1],(value+'\0').encode('utf-16-le'))
        elif name == 'SetEnvironmentVariableW':
            key,value = string(args[0]),string(args[1])
            if value is None: environment.pop(key,None)
            elif not env_ok: result = 0
            else: environment[key] = value
        elif name == 'LoadLibraryExW':
            assert string(args[0]) == r'C:\Steam 安装\steamclient64.dll'
            assert args[1:] == [0,8] and environment == dict(SteamAppId='606150',SteamGameId='606150')
            loads += 1; result = 0x730200 if sleeps >= dll_after else 0
        elif name == 'GetProcAddress':
            assert args[0] == 0x730200 and string(args[1],1) == 'CreateInterface'
            result = targets['factory'] if factory_present else 0
        elif name == 'factory':
            assert string(args[0],1) == 'SteamClient020'
            result = 0x730000 if interface_present else 0
        elif name in ('pipe','user','release_user','release_pipe'):
            assert args[0] == 0x730000
            calls.append((name,sleeps))
            if name == 'pipe':
                result = 77 if sleeps >= pipe_after else 0
                if result: assert not pipes; pipes.add(result)
            elif name == 'user':
                assert args[1] in pipes
                result = 88 if sleeps >= user_after else 0
                if result: users.add(result)
            elif name == 'release_user':
                assert args[1] in pipes and args[2] in users; users.remove(args[2])
            else:
                assert not users and args[1] in pipes; pipes.remove(args[1]); result = int(release_ok)
        elif name == 'CreateFileW':
            assert string(args[0]) == r'C:\.aura-steam-session\client-ipc-status.txt'; result = 0x400
        elif name == 'WriteFile':
            assert args[0] == 0x400
            states.append(bytes(uc.mem_read(args[1],args[2])).decode())
            uc.mem_write(args[3],struct.pack('<I',args[2]))
        elif name == 'Sleep':
            assert args[0] == 2000; sleeps += 1
            assert sleeps <= 60
        elif name == 'ExitProcess':
            exit_code = args[0]; uc.emu_stop(); return
        uc.reg_write(UC_X86_REG_RAX,result)
        uc.reg_write(UC_X86_REG_RIP,q(sp)); uc.reg_write(UC_X86_REG_RSP,sp+8)
    uc.hook_add(UC_HOOK_CODE,hook)
    uc.emu_start(pe.image_base+entry,0x7ff000,count=2000000)
    assert exit_code in (0x41555241,0x41555242) and not pipes and not users
    expected = (env_ok and factory_present and interface_present and not incomplete_vtable and
        max(dll_after,pipe_after,user_after) < 58 and release_ok)
    assert (exit_code == 0x41555241) == expected, (case, states[-1],exit_code)
    if expected:
        assert states[-1] == 'STEAM_CLIENT_IPC_V1 phase=READY'
        assert calls[-2][0] == 'release_user' and calls[-1][0] == 'release_pipe'
        assert sleeps >= max(dll_after,pipe_after,user_after)
    else: assert 'STEAM_CLIENT_IPC_V1 phase=READY' not in states
    return dict(case=case,sleeps=sleeps,loads=loads,phase=states[-1],exit=exit_code)

cases = [run('ready client'),run('DLL still updating',dll_after=2),run('IPC pipe not yet ready',pipe_after=3),
    run('account context precedes real global user',user_after=4),run('no DLL',dll_after=100),
    run('no factory export',factory_present=False),run('unsupported interface',interface_present=False),
    run('incomplete interface',incomplete_vtable=True),run('IPC never opens',pipe_after=100),
    run('global user never connects',user_after=100),run('pipe release fails',release_ok=False),
    run('game identity cannot be set',env_ok=False)]
print(json.dumps(dict(peSha256=hashlib.sha256(data).hexdigest(),count=len(cases),cases=cases,
    limits='Real helper instructions, mocked Win32 and official Steam interfaces. No real Steam connection or game compatibility test.'),indent=2))
