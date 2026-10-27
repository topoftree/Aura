"""Host interception/delegation tests. ARM64 entry saving is verified separately."""
import errno
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class NativeAssertTrace(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='aura-assert-trace-')
        cls.directory = Path(cls.temp.name)
        cls.library = cls.directory / 'libaura_native_trace.so'
        cls.backend = cls.directory / 'libassert_backend.so'
        cls.probe = cls.directory / 'native-assert-probe'
        common = ['cc', '-O2', '-Wall', '-Wextra', '-Werror', '-fno-omit-frame-pointer']
        subprocess.run(common + ['-shared', '-fPIC',
            str(ROOT / 'engine/src/main/cpp/aura_native_trace/native_fd_trace.c'),
            '-ldl', '-o', str(cls.library)], check=True)
        subprocess.run(common + ['-shared', '-fPIC',
            str(ROOT / 'tools/arm64ec/tests/native_assert_backend.c'),
            '-o', str(cls.backend)], check=True)
        subprocess.run(common + ['-rdynamic',
            str(ROOT / 'tools/arm64ec/tests/native_assert_probe.c'),
            '-L' + str(cls.directory), '-Wl,-rpath,' + str(cls.directory),
            '-lassert_backend', '-o', str(cls.probe)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_probe(self, mode='target', enabled='1'):
        env = dict(os.environ, LD_PRELOAD=str(self.library), AURA_NATIVE_FD_TRACE='0')
        env.pop('AURA_NATIVE_ASSERT_TRACE', None)
        if enabled is not None:
            env['AURA_NATIVE_ASSERT_TRACE'] = enabled
        return subprocess.run([str(self.probe), mode], env=env, text=True,
                              capture_output=True, timeout=10)

    def check_fatal(self, result):
        self.assertEqual(-signal.SIGABRT, result.returncode, result.stderr)
        self.assertEqual(1, result.stdout.count('DELEGATE '))
        self.assertIn(f'errno={errno.ENOTTY} line=11616 file=/private/build/a6xx-pack.xml.h', result.stdout)

    def test_disabled_trace_keeps_original_assertion_and_arguments(self):
        for enabled in (None, '0', 'true', '11'):
            result = self.run_probe(enabled=enabled)
            self.check_fatal(result)
            self.assertEqual('', result.stderr)
            self.assertIn('expression=(fields.x & 0xffffc000) == 0', result.stdout)

    def test_matching_fault_records_caller_without_claiming_host_registers_or_coordinates(self):
        result = self.run_probe()
        self.check_fatal(result)
        self.assertIn('state=READY', result.stderr)
        self.assertEqual(1, result.stderr.count('AURA_NATIVE_ASSERT_V1 '))
        self.assertIn('registers=UNAVAILABLE; coordinate=UNDECODED; fatal=UNCHANGED', result.stderr)
        self.assertRegex(result.stderr, r'kind=CALLER_RETURN; index=0; pc=0x[0-9a-f]+; module=native-assert-probe; offset=0x[0-9a-f]+')
        self.assertNotIn('/private/build', result.stderr)
        self.assertNotIn(str(self.directory), result.stderr)
        self.assertLessEqual(result.stderr.count('kind=UNWIND'), 16)
        self.assertLess(len(result.stderr), 6000)

    def test_other_assertions_are_delegated_without_target_capture(self):
        for mode in ('other-function', 'other-expression'):
            result = self.run_probe(mode)
            self.check_fatal(result)
            self.assertNotIn('AURA_NATIVE_ASSERT_V1 ', result.stderr)
            self.assertNotIn('AURA_NATIVE_ASSERT_FRAME_V1 ', result.stderr)

    def test_failed_logging_preserves_original_fatal_path_not_sigpipe(self):
        for mode in ('closed-stderr', 'broken-pipe'):
            result = self.run_probe(mode)
            self.check_fatal(result)


if __name__ == '__main__':
    unittest.main()
