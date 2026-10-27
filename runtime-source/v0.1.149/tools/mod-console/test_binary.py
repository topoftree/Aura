#!/usr/bin/env python3
"""Execute the actual x64 PE with deterministic Win32 API doubles; no Wine/device."""
import argparse, json, struct, sys
from pathlib import Path
from unicorn import Uc, UC_ARCH_X86, UC_MODE_64, UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_RCX, UC_X86_REG_RDX, UC_X86_REG_R8, UC_X86_REG_R9, UC_X86_REG_RSP, UC_X86_REG_RAX, UC_X86_REG_RIP
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/arm64ec'))
from pe_dependencies import PeImage
data=(ROOT/'app/src/main/assets/mod-console/aura-mod-console.exe').read_bytes(); pe=PeImage(data)
header=struct.unpack_from('<I',data,60)[0];entry=struct.unpack_from('<I',data,header+40)[0]
image_size=struct.unpack_from('<I',data,header+80)[0]
def run(case,command='fps 120',match=True,duplicate=False,expired=False,old=False,attach=True,member=True,partial=False,cancel=False,dead=False,too_many=False):
    uc=Uc(UC_ARCH_X86,UC_MODE_64);uc.mem_map(pe.image_base,(image_size+4095)&~4095)
    uc.mem_write(pe.image_base,data[:pe.header_size])
    for _,rva,size,raw in pe.sections:
        if size:uc.mem_write(pe.image_base+rva,data[raw:raw+size])
    uc.mem_map(0x500000,0x300000);uc.reg_write(UC_X86_REG_RSP,0x6ff008)
    hooks={};rva,size=pe.directory(1)
    for pos in range(rva,rva+size,20):
        fields=struct.unpack_from('<IIIII',data,pe.offset(pos,20))
        if not any(fields):break
        for i,name in enumerate(pe.thunks(fields[0] or fields[4])):
            address=0x710000+len(hooks)*16;hooks[address]=name
            uc.mem_write(pe.image_base+fields[4]+i*8,struct.pack('<Q',address))
    target=r'D:\游戏\StardewModdingAPI.exe';text=command.encode('utf-16-le')
    payload=struct.pack('<IIQQII',0x31434d41,1,8000,9000 if expired else 17000,len(target),len(text)//2)+target.encode('utf-16-le')+text
    prefix='C:\\aura-mod-console\\abc\\'; module=prefix+'relay.exe'
    opened=set();inputs=[];status=None;exit_code=None;enumerated=0;request_opens=0;free_console=0
    def put(p,fmt,*v):uc.mem_write(p,struct.pack('<'+fmt,*v))
    def q(p):return struct.unpack('<Q',uc.mem_read(p,8))[0]
    def string(p):
        out=bytearray()
        for i in range(4096):
            b=bytes(uc.mem_read(p+2*i,2))
            if b==b'\0\0':return out.decode('utf-16-le')
            out+=b
        raise AssertionError('unbounded string')
    def wide(p,s):uc.mem_write(p,(s+'\0').encode('utf-16-le'))
    def process(p,pid):
        assert struct.unpack('<I',uc.mem_read(p,4))[0]==568
        put(p+8,'I',pid);wide(p+44,'StardewModdingAPI.exe')
    def hook(_,pc,size,user):
        nonlocal status,exit_code,enumerated,request_opens,free_console
        if pc not in hooks:return
        name=hooks[pc];sp=uc.reg_read(UC_X86_REG_RSP)
        a=[uc.reg_read(r) for r in (UC_X86_REG_RCX,UC_X86_REG_RDX,UC_X86_REG_R8,UC_X86_REG_R9)]
        a += [q(sp+40),q(sp+48),q(sp+56)]
        result=1
        if name=='GetModuleFileNameW':wide(a[1],module);result=len(module)
        elif name=='CreateFileW':
            path=string(a[0])
            if path==prefix+'request.bin':
                request_opens+=1;result=(2**64-1) if cancel and request_opens>1 else 101
            elif path==prefix+'status.bin':result=104
            elif path=='CONIN$':result=103
            else:raise AssertionError(path)
            if result!=2**64-1:opened.add(result)
        elif name=='ReadFile':
            assert a[0]==101; chunk=payload[:a[2]];uc.mem_write(a[1],chunk);put(a[3],'I',len(chunk))
        elif name=='WriteFile':
            assert a[0]==104 and a[2]==4;status=struct.unpack('<I',uc.mem_read(a[1],4))[0];put(a[3],'I',4)
        elif name=='CloseHandle':assert a[0] in opened;opened.remove(a[0])
        elif name=='CreateToolhelp32Snapshot':result=102;opened.add(result)
        elif name=='Process32FirstW':process(a[1],42);enumerated=1
        elif name=='Process32NextW':
            if too_many and enumerated<514:process(a[1],42+enumerated);enumerated+=1
            elif duplicate and enumerated==1:process(a[1],43);enumerated+=1
            else:result=0
        elif name=='OpenProcess':result=200+a[2];opened.add(result)
        elif name=='QueryFullProcessImageNameW':
            value=target if match else r'D:\other\StardewModdingAPI.exe';wide(a[2],value);put(a[3],'I',len(value))
        elif name=='GetProcessTimes':put(a[1],'Q',(11644473600000+(7000 if old else 9000))*10000)
        elif name=='GetSystemTimeAsFileTime':put(a[0],'Q',(11644473600000+10000)*10000)
        elif name=='WaitForSingleObject':assert a[1]==0;result=0 if dead else 258
        elif name=='FreeConsole':free_console+=1
        elif name=='AttachConsole':assert a[0]==42;result=int(attach)
        elif name=='GetConsoleProcessList':put(a[0],'I',42 if member else 99);result=1
        elif name=='WriteConsoleInputW':
            assert a[0]==103 and not inputs
            raw=bytes(uc.mem_read(a[1],a[2]*20));characters=[]
            for i in range(a[2]):
                typ,pad,down,repeat,vkey,scan,c,control=struct.unpack_from('<HHiHHHHI',raw,i*20)
                assert (typ,pad,repeat,scan,control)==(1,0,1,0,0) and down==int(i%2==0)
                if down:characters.append(c)
            actual=struct.pack('<'+'H'*len(characters),*characters).decode('utf-16-le')
            assert actual==command+'\r';inputs.append(actual)
            put(a[3],'I',a[2]-1 if partial else a[2]);result=int(not partial)
        elif name=='ExitProcess':exit_code=a[0];uc.emu_stop();return
        else:raise AssertionError(name)
        uc.reg_write(UC_X86_REG_RAX,result);uc.reg_write(UC_X86_REG_RIP,q(sp));uc.reg_write(UC_X86_REG_RSP,sp+8)
    uc.hook_add(UC_HOOK_CODE,hook);uc.emu_start(pe.image_base+entry,0,count=2000000)
    assert exit_code is not None and exit_code==status and not opened,(case,exit_code,status,opened)
    valid=0<len(text)//2<=256 and not any(ord(c)<32 or 127<=ord(c)<=159 or c in '\u2028\u2029' for c in command)
    expected=1 if expired or not valid or too_many else 2 if not match or old or dead else 3 if duplicate else 4 if not attach or not member or cancel else 5 if partial else 0
    assert exit_code==expected,(case,exit_code,expected)
    assert len(inputs)==int(expected in (0,5))
    return dict(case=case,result=exit_code,inputCalls=len(inputs),allHandlesClosed=True)
cases=[run('normal'),run('unicode and metacharacters remain input',command='help "你好" & echo %PATH%'),
       run('maximum line',command='x'*256),run('empty',command=''),run('too long',command='x'*257),
       run('newline',command='help\nexit'),run('carriage return',command='help\r'),run('escape',command='help\x1b'),
       run('wrong game path',match=False),run('ambiguous process',duplicate=True),run('expired request',expired=True),
       run('previous launch',old=True),run('no console',attach=False),run('wrong console member',member=False),
       run('partial input is not retried',partial=True),run('request cancelled',cancel=True),run('process exited',dead=True),
       run('incomplete process scan is rejected',too_many=True)]
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);args=p.parse_args()
result=dict(casesPassed=len(cases),actualPeInstructions=True,apiDoubles=True,wineTested=False,androidDeviceTested=False,cases=cases)
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(dict(casesPassed=len(cases))))
