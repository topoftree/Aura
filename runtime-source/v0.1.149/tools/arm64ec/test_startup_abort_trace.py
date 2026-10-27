"""Exercise real host ELF interposition, lease lifetime and unchanged fatal delegation.

These are host tests, not Android/Wine/proprietary-driver execution.
"""
import errno
import os
from pathlib import Path
import signal
import struct
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]


class StartupAbortTrace(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='aura-startup-abort-')
        cls.root = Path(cls.temp.name)
        cls.library = cls.root / 'libaura_abort_trace.so'
        flags = ['cc', '-O2', '-Wall', '-Wextra', '-Werror', '-fno-omit-frame-pointer', '-fno-optimize-sibling-calls']
        subprocess.run(flags + ['-shared', '-fPIC', str(ROOT / 'engine/src/main/cpp/aura_abort_trace/abort_trace.c'),
                               '-ldl', '-o', str(cls.library)], check=True)
        cls.legacy = cls.root / 'libaura_native_trace.so'
        subprocess.run(flags + ['-shared', '-fPIC', str(ROOT / 'engine/src/main/cpp/aura_native_trace/native_fd_trace.c'),
                               '-ldl', '-o', str(cls.legacy)], check=True)
        subprocess.run(flags + ['-shared', '-fPIC', str(ROOT / 'tools/arm64ec/tests/native_assert_backend.c'),
                               '-o', str(cls.root / 'libassert_backend.so')], check=True)
        cls.probe = cls.root / 'assert-probe'
        subprocess.run(flags + ['-rdynamic', str(ROOT / 'tools/arm64ec/tests/native_assert_probe.c'),
                               '-L' + str(cls.root), '-Wl,-rpath,' + str(cls.root), '-lassert_backend',
                               '-o', str(cls.probe)], check=True)
        direct = cls.root / 'direct.c'
        direct.write_text('''#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <unistd.h>
static void handler(int s) { (void)s; dprintf(1, "HANDLER errno=%d\\n", errno); _exit(77); }
int main(int argc, char **argv) {
  struct rlimit r = {0,0}; setrlimit(RLIMIT_CORE, &r);
  if (argc == 2 && !strcmp(argv[1], "wait")) { char c; write(1,"READY\\n",6); if(read(0,&c,1) != 1) return 2; }
  if (argc == 2 && !strcmp(argv[1], "handler")) signal(SIGABRT,handler);
  errno = ENOTTY; abort();
}
''')
        cls.direct = cls.root / 'direct-probe'
        subprocess.run(flags + [str(direct), '-o', str(cls.direct)], check=True)
        module = cls.root / 'module.c'
        module.write_text('#include <errno.h>\n#include <stdlib.h>\nvoid module_abort(void) { errno = ENOTTY; abort(); }\n')
        cls.module_bytes = cls.root / 'module-fixture.so'
        subprocess.run(flags + ['-shared', '-fPIC', str(module), '-o', str(cls.module_bytes)], check=True)
        loader = cls.root / 'loader.c'
        loader.write_text('''#include <dlfcn.h>
#include <sys/resource.h>
int main(int argc, char **argv) {
  struct rlimit r = {0,0}; setrlimit(RLIMIT_CORE, &r);
  if (argc != 2) return 2;
  void *module = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  void (*entry)(void) = module ? dlsym(module, "module_abort") : 0;
  if (!entry) return 3;
  entry(); return 4;
}
''')
        cls.loader = cls.root / 'module-probe'
        subprocess.run(flags + [str(loader), '-ldl', '-o', str(cls.loader)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.directory = Path(tempfile.mkdtemp(dir=self.root))
        self.control = self.directory / '.abort-control'
        self.events = self.directory / '.abort-events'
        self.prepare()

    def prepare(self, enabled=1, deadline=None):
        until = deadline if deadline is not None else int(time.clock_gettime(time.CLOCK_BOOTTIME) * 1000) + 120000
        self.control.write_bytes(struct.pack('<8sQIII9I', b'AURAAB1\0', until, enabled, 0, 0, *([0]*9)))
        self.events.write_bytes(b'')
        self.control.chmod(0o600)
        self.events.chmod(0o600)

    def env(self, enabled='1'):
        return dict(os.environ, LD_PRELOAD=str(self.library), AURA_STARTUP_ABORT_TRACE=enabled,
                    AURA_STARTUP_ABORT_DIRECTORY=str(self.directory))

    def run_probe(self, mode='target', enabled='1', direct=False):
        return subprocess.run([str(self.direct if direct else self.probe), mode], env=self.env(enabled),
                              text=True, capture_output=True, timeout=10)

    def verify_assert(self, result):
        self.assertEqual(-signal.SIGABRT, result.returncode, result.stderr)
        self.assertEqual(1, result.stdout.count('DELEGATE '))
        self.assertIn(f'errno={errno.ENOTTY} line=11616 file=/private/build/a6xx-pack.xml.h', result.stdout)

    def test_compiler_message_only_exports_category_and_delegates_original(self):
        result = self.run_probe('compiler-message')
        self.assertEqual(-signal.SIGABRT, result.returncode)
        self.assertEqual(1, result.stdout.count('MESSAGE_DELEGATE'))
        self.assertIn('errno=25 message=LLVM shader compiler failure /private/secret abc123', result.stdout)
        data = self.events.read_text()
        self.assertIn('source=ANDROID_MESSAGE; category=COMPILER; text=NOT_EXPORTED', data)
        self.assertNotIn('/private', data)
        self.assertNotIn('abc123', data)
        self.prepare(enabled=0)
        result = self.run_probe('compiler-message')
        self.assertEqual(-signal.SIGABRT, result.returncode)
        self.assertEqual(1, result.stdout.count('MESSAGE_DELEGATE'))
        self.assertEqual('', self.events.read_text())

    def test_generic_assertion_keeps_all_arguments_errno_and_fatal_signal(self):
        for mode in ('target', 'other-function', 'other-expression'):
            self.prepare()
            result = self.run_probe(mode)
            self.verify_assert(result)
            output = self.events.read_text()
            self.assertEqual(1, output.count('NATIVE_ABORT_EVENT_V1 '))
            self.assertIn('origin=ASSERT2;', output)
            self.assertIn('kind=CALLER_RETURN;', output)
            self.assertIn('NATIVE_ABORT_END_V1', output)
            self.assertLessEqual(output.count('NATIVE_ABORT_FRAME_V1 '), 16)
            for private in ('/private/', str(self.root), 'fields.x', 'pack_', 'pc='):
                self.assertNotIn(private, output)

    def test_direct_abort_preserves_original_signal_handler_and_errno(self):
        result = self.run_probe('handler', direct=True)
        self.assertEqual(77, result.returncode)
        self.assertEqual(f'HANDLER errno={errno.ENOTTY}\n', result.stdout)
        self.assertIn('origin=ABORT;', self.events.read_text())
        self.prepare()
        self.assertEqual(-signal.SIGABRT, self.run_probe(direct=True).returncode)

    def test_known_renamed_adreno_modules_keep_caller_offsets(self):
        names = ('notadreno_utils.so', 'notgsl.so', 'notllvm-glnext.so', 'notllvm-qcom.so',
                 'notllvm-qgl.so', 'vulkan.ad0863.so', 'vulkan.adreno.so')
        for name in names:
            with self.subTest(module=name):
                self.prepare()
                module = self.root / name
                module.write_bytes(self.module_bytes.read_bytes())
                result = subprocess.run([str(self.loader), str(module)], env=self.env(),
                                        capture_output=True, timeout=10)
                self.assertEqual(-signal.SIGABRT, result.returncode)
                output = self.events.read_text()
                caller = next(line for line in output.splitlines() if 'kind=CALLER_RETURN;' in line)
                self.assertIn('module=' + name + ';', caller)
                self.assertGreater(int(caller.split('image_offset=0x')[1], 16), 0)
                self.assertNotIn(str(self.root), output)
                self.assertEqual(1, output.count('NATIVE_ABORT_EVENT_V1 '))
                self.assertLessEqual(output.count('NATIVE_ABORT_FRAME_V1 '), 16)

    def test_unknown_or_invalid_module_names_remain_redacted(self):
        for name in ('private-customer.so', 'notllvm-glnext-extra.so', 'vulkan.ad0999.so', 'libprivate customer.so'):
            with self.subTest(module=name):
                self.prepare()
                module = self.root / name
                module.write_bytes(self.module_bytes.read_bytes())
                result = subprocess.run([str(self.loader), str(module)], env=self.env(),
                                        capture_output=True, timeout=10)
                self.assertEqual(-signal.SIGABRT, result.returncode)
                output = self.events.read_text()
                caller = next(line for line in output.splitlines() if 'kind=CALLER_RETURN;' in line)
                self.assertIn('module=UNKNOWN; image_offset=0x0', caller)
                self.assertNotIn(name, output)

    def test_existing_assert_entry_still_sees_original_caller_and_delegates_once(self):
        env = dict(self.env(), LD_PRELOAD=f'{self.legacy} {self.library}', AURA_NATIVE_ASSERT_TRACE='1')
        result = subprocess.run([str(self.probe), 'target'], env=env, text=True, capture_output=True, timeout=10)
        self.verify_assert(result)
        self.assertRegex(result.stderr, r'kind=CALLER_RETURN; index=0; pc=0x[0-9a-f]+; module=assert-probe;')
        self.assertEqual(1, self.events.read_text().count('NATIVE_ABORT_EVENT_V1 '))

    def test_disabled_expired_future_and_invalid_controls_never_observe(self):
        now = int(time.clock_gettime(time.CLOCK_BOOTTIME) * 1000)
        for flag, enabled, deadline in [('0', 1, now+120000), ('true', 1, now+120000),
                                         ('1', 0, now+120000), ('1', 1, now-1), ('1', 1, now+500000)]:
            self.prepare(enabled, deadline)
            self.verify_assert(self.run_probe(enabled=flag))
            self.assertEqual('', self.events.read_text())
        self.prepare()
        self.control.write_bytes(b'bad')
        self.verify_assert(self.run_probe())
        self.assertEqual('', self.events.read_text())

    def test_revocation_after_library_load_stops_collection(self):
        process = subprocess.Popen([str(self.direct), 'wait'], env=self.env(), stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertEqual('READY\n', process.stdout.readline())
            with self.control.open('r+b') as stream:
                stream.seek(16); stream.write(struct.pack('<I', 0)); stream.flush()
            process.communicate('x', timeout=10)
            self.assertEqual(-signal.SIGABRT, process.returncode)
            self.assertEqual('', self.events.read_text())
        finally:
            if process.poll() is None:
                process.kill(); process.communicate()

    def test_four_event_session_budget_is_shared_across_processes(self):
        for _ in range(6):
            self.verify_assert(self.run_probe())
        text = self.events.read_text()
        self.assertEqual(4, text.count('NATIVE_ABORT_EVENT_V1 '))
        self.assertEqual(4, text.count('NATIVE_ABORT_END_V1 '))
        self.assertLessEqual(text.count('NATIVE_ABORT_FRAME_V1 '), 64)
        self.assertLess(len(text), 32768)
        self.assertEqual((4, 6), struct.unpack_from('<II', self.control.read_bytes(), 20))

    def test_closed_stderr_does_not_replace_original_abort_with_sigpipe(self):
        for mode in ('closed-stderr', 'broken-pipe'):
            self.prepare()
            self.verify_assert(self.run_probe(mode))
            self.assertIn('NATIVE_ABORT_EVENT_V1', self.events.read_text())

    def test_symlink_or_shared_writable_output_is_rejected(self):
        target = self.directory / 'elsewhere'
        target.write_bytes(b'unchanged')
        self.events.unlink(); self.events.symlink_to(target)
        self.verify_assert(self.run_probe())
        self.assertEqual(b'unchanged', target.read_bytes())
        self.events.unlink(); self.prepare(); self.events.chmod(0o666)
        self.verify_assert(self.run_probe())
        self.assertEqual(b'', self.events.read_bytes())


if __name__ == '__main__':
    unittest.main()
