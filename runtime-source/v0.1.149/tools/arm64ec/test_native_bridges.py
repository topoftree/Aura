"""Host-side C ABI/IPC regressions, not Android/GPU execution tests."""
import array
import ctypes
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2]

class Bridges(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='aura-bridges-')
        cls.root = Path(cls.temp.name)
        def compile(output, source, *flags):
            subprocess.run(['cc', '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror', *flags, str(source), '-o', str(cls.root/output)], check=True)
        compile('paths.so', ROOT/'engine/src/main/cpp/winepaths/wine_paths.c', '-ldl', '-pthread')
        compile('shm.so', ROOT/'tools/arm64ec/sysvshm/android_sysvshm.c', '-pthread')
        cls.shm = ctypes.CDLL(str(cls.root/'shm.so'), use_errno=True)
        cls.shm.shmat.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_int]
        cls.shm.shmat.restype = ctypes.c_void_p
        cls.shm.shmdt.argtypes = [ctypes.c_void_p]
        cls.shm.shmget.argtypes = [ctypes.c_int, ctypes.c_size_t, ctypes.c_int]
        cls.shm.shmctl.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
        for name in ['shmget', 'shmat', 'shmdt', 'shmctl']:
            original = getattr(cls.shm, name)
            alias = getattr(cls.shm, 'libandroid_' + name)
            alias.argtypes, alias.restype = original.argtypes, original.restype
        c = cls.root/'path_test.c'
        c.write_text('#include <stdlib.h>\n#include <stdio.h>\nint main(int n, char **v) { (void)n; char *p=realpath(v[1],0); if(!p)return 9; puts(p); free(p); return 0; }\n')
        subprocess.run(['cc', str(c), '-o', str(cls.root/'paths-test')],check=True)
        cls.installed=cls.root/'apk/lib/arm64';cls.installed.mkdir(parents=True)
        cls.server=cls.installed/'libguest-test-hash.so'
        subprocess.run(['cc', '-Wall', '-Wextra', '-Werror', str(ROOT/'tools/arm64ec/tests/wineserver_resource_probe.c'),
                        '-o',str(cls.server)],check=True)
    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def test_wine_paths_are_scoped_to_exact_native_files(self):
        directory=self.root/'real';directory.mkdir(exist_ok=True)
        for n in ['wine','ntdll.so','ordinary']: (directory/n).touch()
        alias=self.root/'alias'
        if not alias.exists():alias.symlink_to(directory/'wine')
        env=dict(os.environ, LD_PRELOAD=str(self.root/'paths.so'), AURA_WINE_UNIX_DIR='/logical/wine/aarch64-unix',
                 AURA_WINE_NATIVE_LOADER=str(directory/'wine'), AURA_WINE_NATIVE_NTDLL=str(directory/'ntdll.so'))
        for path, expected in [(alias,'/logical/wine/aarch64-unix/wine'),(directory/'ntdll.so','/logical/wine/aarch64-unix/ntdll.so'),(directory/'ordinary',str(directory/'ordinary'))]:
            r=subprocess.run([str(self.root/'paths-test'),str(path)],env=env,text=True,capture_output=True,check=True)
            self.assertEqual(expected,r.stdout.strip())
        self.assertEqual(9,subprocess.run([str(self.root/'paths-test'),str(directory/'absent')],env=env).returncode)

    def test_wineserver_finds_nls_after_apk_relocation_not_only_version(self):
        wine=self.root/'runtime/opt/wine';binary=wine/'bin';binary.mkdir(parents=True)
        alias=binary/'wineserver';alias.symlink_to(self.server)
        nls=wine/'share/wine/nls/l_intl.nls';nls.parent.mkdir(parents=True);nls.write_bytes(b'NLS')
        env=dict(os.environ, LD_PRELOAD=str(self.root/'paths.so'), AURA_WINE_BIN_DIR=str(binary),
                 AURA_WINE_NATIVE_SERVER=str(self.server))
        # 0.1.24 remapped only Wine/ntdll. --version still passed, but real startup failed.
        old=dict(env);old.pop('AURA_WINE_NATIVE_SERVER')
        version=subprocess.run([str(alias),'--version'],env=old,capture_output=True,text=True)
        self.assertEqual(0,version.returncode)
        before=subprocess.run([str(alias)],env=old,capture_output=True,text=True)
        self.assertEqual(1,before.returncode)
        self.assertIn('failed to load l_intl.nls',before.stderr)
        after=subprocess.run([str(alias)],env=env,capture_output=True,text=True)
        self.assertEqual(0,after.returncode,after.stderr)
        # Match only this installed server, and require an absolute logical directory.
        for changes in [{'AURA_WINE_NATIVE_SERVER':str(self.server)+'.other'}, {'AURA_WINE_BIN_DIR':'relative/bin'}]:
            result=subprocess.run([str(alias)],env=dict(env,**changes),capture_output=True,text=True)
            self.assertEqual(1,result.returncode)
        ordinary=subprocess.run([str(self.root/'paths-test'),str(nls)],env=env,capture_output=True,text=True,check=True)
        self.assertEqual(str(nls),ordinary.stdout.strip())

    def test_bad_shared_memory_inputs_fail_without_crashing(self):
        self.assertEqual(ctypes.c_void_p(-1).value,self.shm.shmat(-1,None,0))
        self.assertEqual(-1,self.shm.shmdt(None))
        self.assertEqual(-1,self.shm.shmget(1,4096,0))
        self.assertEqual(-1,self.shm.shmget(0,0,0))
        self.assertEqual(-1,self.shm.shmget(0,2**32,0))
        self.assertEqual(-1,self.shm.shmctl(-1,0,None))

    def test_android_and_unprefixed_symbols_resolve_to_the_same_implementation(self):
        for name in ['shmget', 'shmat', 'shmdt', 'shmctl']:
            self.assertEqual(ctypes.cast(getattr(self.shm, name), ctypes.c_void_p).value,
                             ctypes.cast(getattr(self.shm, 'libandroid_' + name), ctypes.c_void_p).value)

    def test_winlator_fd_protocol_handles_split_reply_and_deferred_deletion(self):
        self.exercise_shared_memory(False)

    def test_mesa_prefixed_abi_uses_winlator_fd_protocol_and_bounded_ipc_stat(self):
        self.exercise_shared_memory(True)

    def exercise_shared_memory(self, android):
        prefix = 'libandroid_' if android else ''
        get, attach, control, detach = [getattr(self.shm, prefix + n) for n in ['shmget', 'shmat', 'shmctl', 'shmdt']]
        path=self.root/'ipc'
        server=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
        server.bind(str(path));server.listen(2);server.settimeout(3)
        os.environ['ANDROID_SYSVSHM_SERVER']=str(path)
        fd=os.memfd_create('aura-shm-test');os.ftruncate(fd,4096)
        observed=[];errors=[]
        def request(client):
            b=b''
            while len(b)<5:
                n=client.recv(5-len(b))
                if not n:raise RuntimeError('short request')
                b+=n
            v=struct.unpack('<BI',b);observed.append(v);return v
        def serve():
            try:
                with server.accept()[0] as client:
                    client.settimeout(3);request(client)
                    for b in struct.pack('<I',42):client.sendall(bytes([b]))
                    request(client)
                    client.sendmsg([b'\0'],[(socket.SOL_SOCKET,socket.SCM_RIGHTS,array.array('i',[fd]))])
                with server.accept()[0] as client:request(client)
            except Exception as e:errors.append(e)
        thread=threading.Thread(target=serve,daemon=True);thread.start()
        try:
            self.assertEqual(42,get(0,4096,0))
            mapped=attach(42,None,0)
            self.assertNotEqual(ctypes.c_void_p(-1).value,mapped)
            ctypes.memmove(mapped,b'AURA',4)
            self.assertEqual(b'AURA',os.pread(fd,4,0))
            # Linux x86_64 and Bionic AArch64 both use a 112-byte IPC_STAT
            # record, with size at 48 and attachment count at 88. The former
            # Winlator header used 136 bytes and would corrupt this canary.
            status=ctypes.create_string_buffer(b'\xA5'*144,144)
            self.assertEqual(0,control(42,2,ctypes.byref(status,16)))
            self.assertEqual(b'\xA5'*16,status.raw[:16])
            self.assertEqual(b'\xA5'*16,status.raw[128:])
            self.assertEqual(4096,struct.unpack_from('=Q',status.raw,16+48)[0])
            self.assertEqual(1,struct.unpack_from('=Q',status.raw,16+88)[0])
            self.assertEqual(0,control(42,0,None)) # IPC_RMID while still attached
            self.assertEqual(0,detach(mapped))
            thread.join(4)
            self.assertFalse(thread.is_alive());self.assertEqual([],errors)
            self.assertEqual([(0,4096),(1,42),(2,42)],observed)
        finally:
            server.close();os.close(fd);path.unlink();os.environ.pop('ANDROID_SYSVSHM_SERVER',None)

if __name__=='__main__':unittest.main()
