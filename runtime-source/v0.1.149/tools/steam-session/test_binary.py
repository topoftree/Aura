#!/usr/bin/env python3
"""Run the shipped supervisor's x64 instructions with mocked Win32 APIs.

Requires unicorn==2.1.4. No Wine/Steam/Android/network is executed. This verifies
ordering, live-process validation, exact Unicode arguments and failure paths.
"""
import hashlib, json, pathlib, struct, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'arm64ec'))
from pe_dependencies import PeImage
from unicorn import Uc, UC_ARCH_X86, UC_MODE_64, UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_RCX, UC_X86_REG_RDX, UC_X86_REG_R8, UC_X86_REG_R9, UC_X86_REG_RSP, UC_X86_REG_RAX, UC_X86_REG_RIP

root = pathlib.Path(__file__).resolve().parents[2]
data = (root / 'app/src/main/assets/steam-session/aura-steam-session.exe').read_bytes()
pe = PeImage(data)
assert pe.bits == 64
pe_offset, = struct.unpack_from('<I', data, 60)
entry, = struct.unpack_from('<I', data, pe_offset + 24 + 16)
image_size, = struct.unpack_from('<I', data, pe_offset + 24 + 56)
client = r'C:\Program Files (x86)\Steam\steam.exe'
game = r'D:\606150\测试 folder\Moonlighter.exe'
game_args = '-logFile "C:\\test folder\\Player.log" -option "%USERNAME% & echo literal"'
probe_exe = r'C:\.aura-steam-session\aura-steam-session.exe'
ipc_ready = 0x41555241
internet_key = r'Software\Microsoft\Windows\CurrentVersion\Internet Settings'
connections_key = internet_key + r'\Connections'
connection_value = 'DefaultConnectionSettings'
def blob(flags=1, proxy=b'', bypass=b'', pac=b''):
    return struct.pack('<III', 0x46, 17, flags) + b''.join(struct.pack('<I',len(x))+x for x in (proxy,bypass,pac))

def run(case, users=(0, 0, 42, 42), live=True, correct_image=True, fail_at=None, bad_type=False, view=0x100, invalid=False, cancelled=False, drive_root=False,
        proxy=None, proxy_failure=None, legacy_proxy=False, api_failure=False, api_auto=False, expect_proxy_failure=False,
        probe_code=ipc_ready, probe_delay=1, client_restarts=False, logout_during_probe=False, game_env_fails=False,
        extra_clients=(), without_steam=False, local=False, existing_client=None, invalid_extra=False, presentation_commands=None, foreground_failure=False):
    uc = Uc(UC_ARCH_X86, UC_MODE_64)
    base = pe.image_base
    uc.mem_map(base, (image_size + 4095) & ~4095)
    uc.mem_write(base, data[:pe.header_size])
    for _, rva, size, raw in pe.sections:
        if size: uc.mem_write(base+rva, data[raw:raw+size])
    uc.mem_map(0x500000, 0x300000)
    uc.reg_write(UC_X86_REG_RSP, 0x6ff008)
    imports = {}
    rva, size = pe.directory(1)
    for pos in range(rva, rva+size, 20):
        fields = struct.unpack_from('<IIIII', data, pe.offset(pos,20))
        if not any(fields): break
        for index, name in enumerate(pe.thunks(fields[0] or fields[4])):
            target = 0x710000 + len(imports) * 16
            imports[target] = name
            uc.mem_write(base + fields[4] + index * 8, struct.pack('<Q', target))
    def q(addr): return struct.unpack('<Q',uc.mem_read(addr,8))[0]
    def d(addr, value): uc.mem_write(addr, struct.pack('<I',value))
    def wide(addr):
        if not addr: return None
        parts = bytearray()
        for i in range(32768):
            pair = bytes(uc.mem_read(addr+i*2,2))
            if pair == b'\0\0': return parts.decode('utf-16-le')
            parts.extend(pair)
        raise AssertionError('Unterminated string')
    def put_wide(addr, value): uc.mem_write(addr,(value+'\0').encode('utf-16-le'))
    selected_game = r'D:\Moonlighter.exe' if drive_root else game
    environment = dict(AURA_STEAM_CLIENT='' if without_steam else client, AURA_STEAM_ARGS='-silent -forcemanifestsuffix win64 -allosarches',
        AURA_STEAM_GAME=selected_game, AURA_STEAM_GAME_ARGS=game_args, AURA_STEAM_APPID='606150',
        AURA_STEAM_STATUS=r'C:\.aura-steam-session\status.txt', SteamAppId='old', SteamGameId='old')
    extra_paths = dict(EA=r'C:\Program Files\Electronic Arts\EA Desktop\EADesktop.exe',
                      UBISOFT=r'C:\Client & 测试 % literal\UbisoftConnect.exe')
    extra_args = dict(EA='-silent', UBISOFT='-uplay_silent')
    for key in extra_clients: environment['AURA_CLIENT_'+key] = extra_paths[key]
    if local: environment['AURA_STEAM_APPID'] = ''
    if invalid_extra: environment['AURA_CLIENT_EA'] = 'bad"path'
    if invalid: environment['AURA_STEAM_GAME'] = 'bad"path'
    calls, states, reports, sleeps, exited = [], [], [], 0, None
    ipc_reports, killed, waited, closed = [], [], [], []
    presentation_reports, gate_reports = [], []
    probe_started = None
    registry, keys, files, freed, written = {}, {}, {}, [], set()
    for v in (0x100, 0x200):
        values = registry[v, connections_key] = {}
        original = (proxy or {}).get(v, (3, blob()))
        if original is not None: values[connection_value] = original
        registry[v, internet_key] = {'ProxyEnable': (4, struct.pack('<I',1))} if legacy_proxy else {}
    argc = dict(GetEnvironmentVariableW=3,SetEnvironmentVariableW=2,CreateProcessW=10,OpenProcess=3,
        GetExitCodeProcess=2,QueryFullProcessImageNameW=4,CloseHandle=1,CreateFileW=7,WriteFile=5,ReadFile=5,
        FlushFileBuffers=1,Sleep=1,ExitProcess=1,RegOpenKeyExW=5,RegQueryValueExW=6,RegCloseKey=1,
        CreateToolhelp32Snapshot=2,Process32FirstW=2,Process32NextW=2,RegCreateKeyExW=9,RegSetValueExW=6,
        WinHttpGetIEProxyConfigForCurrentUser=1,GlobalFree=1)
    argc.update(GetModuleFileNameW=3,TerminateProcess=2,WaitForSingleObject=2,LoadLibraryExW=3,GetProcAddress=2)
    def external(machine, address, size, _):
        nonlocal sleeps, exited, probe_started
        name = imports.get(address)
        if name is None: return
        sp = uc.reg_read(UC_X86_REG_RSP)
        regs = [UC_X86_REG_RCX,UC_X86_REG_RDX,UC_X86_REG_R8,UC_X86_REG_R9]
        a = [uc.reg_read(regs[i]) if i<4 else q(sp+40+(i-4)*8) for i in range(argc[name])]
        result = 1
        if name == 'GetEnvironmentVariableW':
            value = environment.get(wide(a[0]), '')
            result = len(value.encode('utf-16-le'))//2
            if result < a[2]: put_wide(a[1],value)
            else: result += 1
        elif name == 'SetEnvironmentVariableW':
            key, value = wide(a[0]),wide(a[1])
            if game_env_fails and key == 'SteamAppId' and value is not None: result = 0
            elif value is None: environment.pop(key,None)
            else: environment[key] = value
        elif name == 'CreateProcessW':
            if not without_steam: assert reports and reports[-1].endswith('result=VERIFIED')
            # No hidden/minimized Win32 startup flag: account prompts remain interactive.
            assert struct.unpack('<I',uc.mem_read(a[8]+60,4))[0] == 0
            assert a[4] & 0xffffffff == 0 and a[5] & 0xffffffff == 0
            calls.append(dict(exe=wide(a[0]),args=wide(a[1]),cwd=wide(a[7]),env=environment.copy(),tick=sleeps))
            result = len(calls) != fail_at and not (foreground_failure and wide(a[1]).endswith(' steam://open/main'))
            if result:
                is_probe = wide(a[0]) == probe_exe
                if is_probe: probe_started = sleeps
                uc.mem_write(a[9], struct.pack('<QQII',0x3100 if is_probe else 0x100,0x101,16,20))
        elif name == 'Sleep':
            sleeps += 1
            if sleeps > 75: uc.emu_stop(); return
        elif name == 'GetModuleFileNameW': put_wide(a[1], probe_exe); result = len(probe_exe)
        elif name == 'TerminateProcess':
            assert a[0] == 0x3100; killed.append(a[0])
        elif name == 'WaitForSingleObject':
            assert a[0] == 0x3100 and a[1] == 5000; waited.append(a[0]); result = 0
        elif name in ('RegOpenKeyExW','RegCreateKeyExW'):
            assert a[0] & 0xffffffff == 0x80000001
            key = wide(a[1])
            access = a[3] if name == 'RegOpenKeyExW' else a[5]
            output = a[4] if name == 'RegOpenKeyExW' else a[7]
            if key == r'Software\Valve\Steam\ActiveProcess':
                result = 0 if access == 1 | view else 2
                uc.mem_write(output,struct.pack('<Q',0x200))
            else:
                v = access & 0x300; assert v in (0x100,0x200)
                identity = (v,key)
                if proxy_failure == 'open' or (proxy_failure == 'backup' and 'SteamProxyBackup' in key): result = 5
                elif name == 'RegOpenKeyExW' and identity not in registry: result = 2
                else:
                    registry.setdefault(identity,{})
                    handle = 0x800+len(keys); keys[handle] = identity
                    uc.mem_write(output,struct.pack('<Q',handle)); result = 0
        elif name == 'RegQueryValueExW':
            key = wide(a[1])
            if a[0] == 0x200:
                assert key in ('pid','ActiveUser')
                value = (32 if client_restarts and sleeps >= 4 else 28) if key == 'pid' else users[min(sleeps-1,len(users)-1)]
                if key == 'ActiveUser' and logout_during_probe and probe_started is not None: value = 0
                d(a[3],1 if bad_type else 4); d(a[5],4); d(a[4],value); result = 0
            else:
                identity = keys[a[0]]
                item = registry[identity].get(key)
                if item is None: result = 2
                else:
                    kind, value = item
                    if proxy_failure == 'readback' and identity in written and key == connection_value: value = bytes(len(value))
                    capacity = struct.unpack('<I',uc.mem_read(a[5],4))[0]
                    d(a[3],kind); d(a[5],len(value))
                    result = 234 if a[4] and capacity < len(value) else 0
                    if a[4] and result == 0: uc.mem_write(a[4],value)
        elif name == 'RegSetValueExW':
            identity = keys[a[0]]
            if proxy_failure == 'write' and identity[1] == connections_key: result = 5
            else:
                registry[identity][wide(a[1])] = (a[3],bytes(uc.mem_read(a[4],a[5])))
                written.add(identity); result = 0
        elif name == 'WinHttpGetIEProxyConfigForCurrentUser':
            current = registry[0x100,connections_key].get(connection_value)
            auto = api_auto or current is None or current[0] != 3 or len(current[1]) < 12 or current[1][:4] != b'F\0\0\0' or bool(current[1][8]&8)
            d(a[0],int(auto))
            # Exercise ownership of API-returned strings without revealing their values.
            for i in range(3): uc.mem_write(a[0]+8*(i+1),struct.pack('<Q',0x790000+i*128))
            result = not api_failure
        elif name == 'GlobalFree': freed.append(a[0]); result = 0
        elif name == 'RegCloseKey': result = 0
        elif name == 'OpenProcess': result = (0x301 if a[2] == 99 else 0x300) if live else 0
        elif name == 'GetExitCodeProcess':
            value = probe_code if a[0] == 0x3100 and sleeps - probe_started >= probe_delay else 259
            d(a[1], value)
        elif name == 'QueryFullProcessImageNameW':
            put_wide(a[2], existing_client if a[0] == 0x301 else client.upper() if correct_image else r'C:\other\steam.exe')
        elif name == 'CreateFileW':
            path = wide(a[0])
            names = ['status.txt', 'proxy-status.txt', 'client-ipc-status.txt', 'client-command.txt', 'client-presentation.txt', 'client-gate-status.txt']
            assert path.rsplit('\\', 1)[0] == r'C:\.aura-steam-session'
            result = 0x400 + names.index(path.rsplit('\\', 1)[1])
            files[result] = path
            if result == 0x403: assert a[1] == 0x80000000 and a[4] == 3  # Read existing command only.
        elif name == 'ReadFile':
            assert a[0] == 0x403 and a[2] == 8
            commands = [value for tick, value in (presentation_commands or []) if sleeps >= tick]
            value = (commands[-1] if commands else b'')[:a[2]]
            if value: uc.mem_write(a[1], value)
            d(a[3], len(value))
        elif name == 'CreateToolhelp32Snapshot': result = 0x500
        elif name == 'Process32FirstW':
            assert struct.unpack('<I',uc.mem_read(a[1],4))[0] == 568
            d(a[1]+8,99 if existing_client else 28)
            put_wide(a[1]+44,existing_client.rsplit('\\',1)[-1] if existing_client else 'winhandler.exe' if cancelled else 'steam.exe')
        elif name == 'Process32NextW': result = 0
        elif name == 'WriteFile':
            text = bytes(uc.mem_read(a[1],a[2])).decode()
            (states if a[0] == 0x400 else ipc_reports if a[0] == 0x402 else presentation_reports if a[0] == 0x404 else gate_reports if a[0] == 0x405 else reports).append(text); d(a[3],a[2])
        elif name == 'CloseHandle': closed.append(a[0])
        elif name == 'ExitProcess': exited = a[0]; uc.emu_stop(); return
        uc.reg_write(UC_X86_REG_RAX, int(result))
        uc.reg_write(UC_X86_REG_RIP, q(sp))
        uc.reg_write(UC_X86_REG_RSP, sp+8)
    uc.hook_add(UC_HOOK_CODE,external)
    uc.emu_start(base+entry, 0x7ff000, count=1000000)
    # Foreground handoffs must target the existing installation, carry no identity,
    # and never count as game creation or authenticated IPC. Keep original assertions
    # for the normal launch chain after checking these additional calls separately.
    foreground = [call for call in calls if call['args'].endswith(' steam://open/main')]
    for call in foreground:
        assert call['exe'] == client and call['env'] == {}
    calls = [call for call in calls if call not in foreground]
    if presentation_commands:
        assert len(foreground) <= 3
        if not live or not correct_image or cancelled:
            assert not foreground
        if not any(users): assert 'GAME_STARTED' not in states and not ipc_reports
    if invalid or invalid_extra or without_steam and not extra_clients:
        assert not calls and states[-1] == 'INVALID_CONFIGURATION' and exited == 1
    elif expect_proxy_failure:
        assert not calls and states[-1] == 'PROXY_SETUP_FAILED' and exited == 1
        assert reports[-1].endswith('result=FAILED')
    elif extra_clients or without_steam:
        extras = [key for key in extra_clients if extra_paths[key].lower() != (existing_client or '').lower()]
        if not without_steam and not any(users):
            assert len(calls) == 1 and calls[0]['exe'] == client
            assert states[-1] == 'WAITING_FOR_CLIENT_LOGIN' and exited is None
        else:
            order = ([] if without_steam else [client, probe_exe]) + [extra_paths[key] for key in extras] + [selected_game]
            assert [call['exe'] for call in calls] == (order[:fail_at] if fail_at else order), case
            for call in calls:
                if call['exe'] == probe_exe: continue
                assert not any(k.startswith('AURA_') for k in call['env'])
                if call['exe'] != selected_game:
                    assert 'SteamAppId' not in call['env'] and 'SteamGameId' not in call['env']
                for key in extras:
                    if call['exe'] == extra_paths[key]:
                        assert call['args'] == '"'+extra_paths[key]+'" '+extra_args[key]
                        assert call['cwd'] == extra_paths[key].rsplit('\\',1)[0]
                        if not without_steam: assert call['tick'] >= probe_started + probe_delay
            if fail_at:
                assert exited == 1
                assert states[-1] == ('GAME_START_FAILED' if fail_at == len(order) else 'GAME_CLIENT_START_FAILED')
            else:
                assert calls[-1]['env'] == ({} if local else dict(SteamAppId='606150',SteamGameId='606150'))
                assert calls[-1]['args'] == '"'+selected_game+'" '+game_args
                assert states[-1] == 'GAME_STARTED' and exited is None
        if without_steam:
            assert not reports and not ipc_reports and not freed and not written
    else:
        assert calls[0]['exe'] == client
        assert 'SteamAppId' not in calls[0]['env'] and not any(k.startswith('AURA_') for k in calls[0]['env'])
        if fail_at == 1: assert len(calls) == 1 and states[-1] == 'CLIENT_START_FAILED' and exited == 1
        elif cancelled or not correct_image: assert len(calls) == 1 and states[-1] == 'CLIENT_EXITED' and exited == 1
        elif live and correct_image and not bad_type and any(users):
            assert calls[1]['exe'] == probe_exe and calls[1]['env'] == dict(
                AURA_STEAM_PROBE='1', AURA_STEAM_CLIENT=client, AURA_STEAM_STATUS=r'C:\.aura-steam-session\status.txt', AURA_STEAM_APPID='606150')
            assert calls[1]['tick'] >= 2
            if logout_during_probe:
                assert len(calls) == 2 and states[-1] == 'WAITING_FOR_CLIENT_LOGIN' and killed == waited == [0x3100]
            elif fail_at == 2:
                assert len(calls) == 2 and states[-1] == 'CLIENT_PROBE_START_FAILED' and exited == 1
            elif probe_code != ipc_ready:
                assert len(calls) == 2 and states[-1] == ('CLIENT_IPC_TIMEOUT' if probe_code == 259 else 'CLIENT_IPC_FAILED') and exited == 1
                assert (0x3100 in killed) == (probe_code == 259)
            elif game_env_fails:
                assert len(calls) == 2 and states[-1] == 'GAME_START_FAILED' and exited == 1
            else:
                assert len(calls) == (4 if client_restarts else 3) and calls[-1]['exe'] == selected_game
                assert calls[-1]['args'] == '"'+selected_game+'" '+game_args
                assert calls[-1]['cwd'] == ('D:\\' if drive_root else game.rsplit('\\',1)[0])
                assert calls[-1]['env'] == dict(SteamAppId='606150', SteamGameId='606150')
                assert calls[-1]['tick'] >= probe_started + probe_delay and 'CLIENT_IPC_READY' in states
                assert states[-1] == ('GAME_START_FAILED' if fail_at == 3 else 'GAME_STARTED')
                assert exited == (1 if fail_at == 3 else None)
                if client_restarts: assert killed == waited == [0x3100] and ipc_reports.count('STEAM_CLIENT_IPC_V1 phase=CLIENT_CHANGED') == 1
            if fail_at != 2: assert 0x3100 in closed
        else: assert len(calls) == 1 and states[-1] == 'WAITING_FOR_CLIENT_LOGIN' and exited is None
    if not invalid and not invalid_extra and not without_steam:
        assert len(freed) == 3
        assert 'proxy.invalid' not in reports[-1] and 'config.pac' not in reports[-1]
    if not invalid and not invalid_extra and not without_steam and not expect_proxy_failure:
        for v in (0x100,0x200):
            original = (proxy or {}).get(v, (3, blob()))
            actual = registry[v,connections_key][connection_value]
            if original is None:
                assert actual == (3, struct.pack('<6I',0x46,0,1,0,0,0))
            else:
                expected = bytearray(original[1]); expected[8] &= ~8
                if original[1][8] & 8 and not expected[8]: expected[8] = 1
                assert actual == (3,bytes(expected)), (case, v)
            if original is None or original[1][8] & 8:
                backup = registry[0x100,r'Software\Aura\SteamProxyBackupV2\View'+('64' if v==0x100 else '32')]
                assert backup['OriginalPresent'] == (4,struct.pack('<I',int(original is not None)))
                if original is not None: assert backup[connection_value] == original
    return dict(case=case, processStarts=len(calls)+len(foreground),states=states,proxy=reports,ipc=ipc_reports,exit=exited,presentation=presentation_reports,foreground=len(foreground),gate=gate_reports)

cases = [run('wait then login'),run('32-bit registry view',view=0x200),run('not logged in',users=(0,)),
         run('stale pid',live=False),run('wrong Steam prefix',correct_image=False),run('invalid registry type',bad_type=True),
         run('client launch fails',fail_at=1),run('game launch fails',fail_at=3),run('invalid path',invalid=True),
         run('cancelled updater',users=(0,),cancelled=True),run('game at drive root',drive_root=True)]
cases += [run('both live views missing',proxy={0x100:None,0x200:None}),
          run('different live views',proxy={0x100:(3,blob(9)),0x200:(3,blob(15,b'proxy.invalid:8080',b'<local>',b'https://proxy.invalid/config.pac')+b'\x08\x07\x06')}),
          run('manual and PAC unchanged',proxy={0x100:(3,blob(7,b'proxy.invalid:8080',b'<local>',b'https://proxy.invalid/config.pac'))}),
          run('auto only becomes direct',proxy={0x200:(3,blob(8))}),
          run('legacy explicit proxy not overwritten',proxy={0x100:None},legacy_proxy=True,expect_proxy_failure=True),
          run('WinHTTP still sees autodetect',api_auto=True,expect_proxy_failure=True),
          run('WinHTTP query fails',api_failure=True,expect_proxy_failure=True)]
for label, invalid_blob in [('wrong type',(1,blob())),('wrong magic',(3,bytes(24))),
        ('truncated lengths',(3,blob()[:20])),('oversized string',(3,blob()[:12]+b'\xff'*4+blob()[16:])),
        ('unknown flags',(3,blob(32))),('oversized blob',(3,blob()+bytes(16384)))]:
    cases.append(run(label,proxy={0x200:invalid_blob},expect_proxy_failure=True))
for failure in ('open','write','backup','readback'):
    cases.append(run('proxy '+failure+' fails',proxy={0x100:(3,blob(9))},proxy_failure=failure,expect_proxy_failure=True))
cases += [run('live registry before real IPC', users=(42,), probe_delay=5),
          run('probe launch fails',fail_at=2), run('probe reports unavailable',probe_code=0x41555242),
          run('probe crashes',probe_code=0xc0000005), run('ordinary exit is not IPC success',probe_code=0),
          run('blocked probe times out',probe_code=259), run('client restarts during probe',users=(42,),probe_delay=3,client_restarts=True),
          run('client logs out during probe',users=(42,),logout_during_probe=True),
          run('game identity cannot be set',game_env_fails=True)]
cases += [run('Steam then silent EA and Ubisoft then original game',extra_clients=('EA','UBISOFT')),
          run('required clients cannot bypass Steam login',extra_clients=('EA','UBISOFT'),users=(0,)),
          run('EA without Steam',extra_clients=('EA',),without_steam=True),
          run('Ubisoft without Steam',extra_clients=('UBISOFT',),without_steam=True),
          run('Local clients have no Steam identity or proxy changes',extra_clients=('EA','UBISOFT'),without_steam=True,local=True),
          run('EA prelaunch failure',extra_clients=('EA','UBISOFT'),fail_at=3),
          run('Ubisoft prelaunch failure',extra_clients=('EA','UBISOFT'),fail_at=4),
          run('client only failure',extra_clients=('UBISOFT',),without_steam=True,fail_at=1),
          run('client only game failure',extra_clients=('EA',),without_steam=True,fail_at=2),
          run('matching installed running client is reused',extra_clients=('EA','UBISOFT'),without_steam=True,
              existing_client=r'C:\Program Files\Electronic Arts\EA Desktop\EADesktop.exe'),
          run('same-name executable elsewhere is not reused',extra_clients=('EA',),without_steam=True,
              existing_client=r'D:\Wrong directory\EADesktop.exe'),
          run('invalid additional client path launches nothing',extra_clients=('EA',),invalid_extra=True),
          run('empty client plan launches nothing',without_steam=True)]
for name, commands, extra, expected, reports in [
    ('foreground handoff remains bounded and cannot bypass login', [(1,b'SHOW1\n'),(2,b'SHOW1\n'),(3,b'SHOW2\n'),(4,b'SHOW1\n'),(5,b'SHOW3\n'),(6,b'SHOW4\n')], {}, 3, ['FOREGROUND_REQUESTED']*3),
    ('foreground launch failure is reported without game start', [(1,b'SHOW1\n')], {'foreground_failure':True}, 1, ['FOREGROUND_FAILED']),
    ('missing live client cannot be restarted by a UI command', [(1,b'SHOW1\n')], {'live':False}, 0, ['NO_LIVE_CLIENT']),
    ('wrong prefix cannot receive foreground request', [(1,b'SHOW1\n')], {'correct_image':False}, 0, ['NO_LIVE_CLIENT']),
    ('invalid commands and trailing data never execute', [(1,b'SHOW0\n'),(2,b'SHOW4\n'),(3,b'SHOW1\nX'),(4,b'cmd.exe'),(5,b'SHOW1'),(6,b'show1\n')], {}, 0, []),
]:
    result = run(name, users=(0,), presentation_commands=commands, **extra)
    assert result['foreground'] == expected and result['presentation'] == reports, result
    cases.append(result)
result = run('commands arriving after real login cannot spawn a UI', users=(42,), presentation_commands=[(4,b'SHOW1\n')])
assert result['foreground'] == 0 and result['states'][-1] == 'GAME_STARTED'
cases.append(result)
by_name = {case['case']: case for case in cases}
for label, reason in [('not logged in', 'NO_ACTIVE_USER'), ('stale pid', 'PROCESS_UNAVAILABLE'),
                      ('wrong Steam prefix', 'PATH_MISMATCH'), ('invalid registry type', 'PID_UNAVAILABLE')]:
    reports = by_name[label]['gate']
    expected = 'STEAM_CLIENT_GATE_V1 view64=' + reason + '; view32=REGISTRY_UNAVAILABLE; authentication=UNCONFIRMED'
    assert reports[-1] == expected and reports.count(expected) == 1, (label, reports)
    assert all(before != after for before, after in zip(reports, reports[1:])), (label, reports)
assert by_name['32-bit registry view']['gate'][-1] == 'STEAM_CLIENT_GATE_V1 view64=REGISTRY_UNAVAILABLE; view32=CONTEXT_AVAILABLE; authentication=UNCONFIRMED'
assert len(by_name['wait then login']['gate']) == 2
assert by_name['wait then login']['gate'][-1] == 'STEAM_CLIENT_GATE_V1 view64=CONTEXT_AVAILABLE; view32=NOT_CHECKED; authentication=UNCONFIRMED'
print(json.dumps(dict(peSha256=hashlib.sha256(data).hexdigest(),count=len(cases),cases=cases,
    limits='Win32 APIs mocked; executes actual x64 PE instructions, not Wine, Steam, FEX, Android or network.'),indent=2))
