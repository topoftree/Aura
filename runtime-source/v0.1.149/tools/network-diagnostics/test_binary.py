#!/usr/bin/env python3
"""Execute the actual shipped x64 PE under Unicorn with mock Win32 networking.
No real network, Wine/FEX or Android is exercised.
"""
import json, pathlib, struct, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'arm64ec'))
from pe_dependencies import PeImage
from unicorn import Uc, UC_ARCH_X86, UC_MODE_64, UC_HOOK_CODE
from unicorn.x86_const import *
root = pathlib.Path(__file__).resolve().parents[2]
data = (root/'app/src/main/assets/network-diagnostics/aura-network-probe.exe').read_bytes()
pe = PeImage(data)
pe_offset, = struct.unpack_from('<I',data,60)
entry, = struct.unpack_from('<I',data,pe_offset+40)
size, = struct.unpack_from('<I',data,pe_offset+80)
argc = dict(GetStdHandle=1,WriteFile=5,GetLastError=0,GetTickCount64=0,ExitProcess=1,
    WSAStartup=2,WSACleanup=0,WSAGetLastError=0,getaddrinfo=4,freeaddrinfo=1,socket=3,
    ioctlsocket=3,connect=3,select=5,getsockopt=5,closesocket=1,WinHttpOpen=5,
    WinHttpSetTimeouts=5,WinHttpSetOption=4,WinHttpConnect=4,WinHttpOpenRequest=7,
    WinHttpSendRequest=7,WinHttpReceiveResponse=2,WinHttpQueryHeaders=6,WinHttpCloseHandle=1)

def run(fail=None):
    uc = Uc(UC_ARCH_X86,UC_MODE_64)
    base = pe.image_base
    uc.mem_map(base,(size+4095)&~4095); uc.mem_write(base,data[:pe.header_size])
    for _,rva,n,raw in pe.sections:
        if n: uc.mem_write(base+rva,data[raw:raw+n])
    uc.mem_map(0x500000,0x400000); uc.reg_write(UC_X86_REG_RSP,0x6ff008)
    imports={}; rva,n=pe.directory(1)
    for pos in range(rva,rva+n,20):
        f=struct.unpack_from('<IIIII',data,pe.offset(pos,20))
        if not any(f): break
        for i,name in enumerate(pe.thunks(f[0] or f[4])):
            target=0x710000+16*len(imports); imports[target]=name
            uc.mem_write(base+f[4]+i*8,struct.pack('<Q',target))
    def q(p): return struct.unpack('<Q',uc.mem_read(p,8))[0]
    def put(p,v): uc.mem_write(p,struct.pack('<I',v))
    calls=[]; output=[]; last_error=0; tick=0
    def api(machine,addr,size,_):
        nonlocal last_error,tick
        name=imports.get(addr)
        if not name: return
        sp=uc.reg_read(UC_X86_REG_RSP)
        regs=[UC_X86_REG_RCX,UC_X86_REG_RDX,UC_X86_REG_R8,UC_X86_REG_R9]
        a=[uc.reg_read(regs[i]) if i<4 else q(sp+40+(i-4)*8) for i in range(argc[name])]
        calls.append((name,a)); result=1
        if name=='ExitProcess': uc.emu_stop(); return
        if name=='GetStdHandle': result=7
        elif name=='GetTickCount64': tick+=1; result=tick
        elif name in ('GetLastError','WSAGetLastError'): result=last_error
        elif name=='WriteFile': output.append(bytes(uc.mem_read(a[1],a[2])).decode()); put(a[3],a[2])
        elif name=='WSAStartup': result=10093 if fail==name else 0
        elif name=='getaddrinfo':
            if fail==name: result=11001
            else:
                uc.mem_write(0x800000,struct.pack('<iiiiQQQQ',0,2,1,6,16,0,0x800100,0x800200))
                uc.mem_write(0x800200,struct.pack('<iiiiQQQQ',0,23,1,6,28,0,0x800300,0))
                uc.mem_write(0x800100,b'\x02\0\0\x50'+(b'\0'*4 if fail=='zero' else b'\xc0\0\x02\x01')+b'\0'*8)
                uc.mem_write(a[3],struct.pack('<Q',0x800000)); result=0
        elif name=='socket': result=0xffffffffffffffff if fail==name else 91; last_error=10013
        elif name=='ioctlsocket': result=-1 if fail==name else 0; last_error=10022
        elif name=='connect': result=0 if fail=='immediate' else -1; last_error=10061 if fail==name else 10035
        elif name=='select': result=0 if fail=='timeout' else (-1 if fail==name else 1); last_error=10038
        elif name=='getsockopt':
            put(a[3],10061 if fail=='so_error' else 0); result=-1 if fail==name else 0; last_error=10022
        elif name=='WinHttpOpen': assert a[1]==1; result=0 if fail==name else 101; last_error=8
        elif name=='WinHttpSetTimeouts': assert a[1:]==[2000]*4; result=0 if fail==name else 1; last_error=87
        elif name=='WinHttpConnect': result=0 if fail==name else 102; last_error=12005
        elif name=='WinHttpOpenRequest': result=0 if fail==name else 103; last_error=8
        elif name=='WinHttpSetOption':
            assert a[1] in (63,77)
            assert struct.unpack('<I',uc.mem_read(a[2],4))[0]==(3 if a[1]==63 else 2)
            result=0 if fail==name else 1; last_error=87
        elif name=='WinHttpSendRequest': assert a[1:]==[0]*6; result=0 if fail==name else 1; last_error=12007
        elif name=='WinHttpReceiveResponse': result=0 if fail==name else 1; last_error=12175
        elif name=='WinHttpQueryHeaders':
            assert a[1]==19|0x20000000
            put(a[3],200); result=0 if fail==name else 1; last_error=12150
        uc.reg_write(UC_X86_REG_RAX,result&0xffffffffffffffff)
        uc.reg_write(UC_X86_REG_RIP,q(sp)); uc.reg_write(UC_X86_REG_RSP,sp+8)
    uc.hook_add(UC_HOOK_CODE,api)
    uc.emu_start(base+entry,base+size,count=2_000_000)
    text=''.join(output)
    assert text and all(len(l)<320 for l in text.splitlines())
    assert 'steampowered' not in text and '192.0.2.1' not in text
    if fail=='WSAStartup': assert 'WSA_START code=10093' in text and not any(n=='getaddrinfo' for n,a in calls)
    else: assert 'stage=DONE' in text
    if fail=='getaddrinfo': assert 'stage=DNS code=11001 value=0' in text
    if fail=='zero': assert 'stage=DNS_ZERO code=0 value=1' in text
    if fail=='socket': assert 'stage=SOCKET code=10013' in text
    if fail=='connect': assert 'stage=CONNECT code=10061' in text and not any(n=='select' for n,a in calls)
    if fail=='timeout': assert 'stage=SELECT code=0 value=0' in text and not any(n=='getsockopt' for n,a in calls)
    if fail=='so_error': assert 'stage=SO_ERROR code=10061' in text
    if fail=='WinHttpSendRequest': assert 'stage=HTTP_SEND code=12007' in text and not any(n=='WinHttpReceiveResponse' for n,a in calls)
    if fail=='WinHttpReceiveResponse': assert 'stage=HTTP_RECEIVE code=12175' in text and not any(n=='WinHttpQueryHeaders' for n,a in calls)
    if fail is None: assert text.count('stage=HTTP_STATUS code=0 value=200')==2
    assert all(n!='WinHttpReadData' for n,a in calls)
    expected_handles = 0 if fail in ('WSAStartup','WinHttpOpen') else (2 if fail in ('WinHttpSetTimeouts','WinHttpConnect') else (4 if fail=='WinHttpOpenRequest' else 6))
    assert sum(n=='WinHttpCloseHandle' for n,a in calls)==expected_handles
    assert sum(n=='closesocket' for n,a in calls)==(0 if fail in ('WSAStartup','getaddrinfo','socket') else 4)
    return len(calls)
cases=[None,'WSAStartup','getaddrinfo','zero','socket','ioctlsocket','connect','immediate','timeout','select','so_error','getsockopt',
    'WinHttpOpen','WinHttpSetTimeouts','WinHttpConnect','WinHttpOpenRequest','WinHttpSetOption','WinHttpSendRequest','WinHttpReceiveResponse','WinHttpQueryHeaders']
print(json.dumps({'cases':len(cases),'api_calls':sum(run(c) for c in cases),'result':'PASS','scope':'Mock Win32, actual x64 PE; no device/network validation'},indent=2))
