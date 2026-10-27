"""Host ABI/behaviour checks using a simulated Bionic ownership API, not device tests."""
import errno
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class NativeFdTrace(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='aura-fd-trace-')
        cls.directory = Path(cls.temp.name)
        cls.library = cls.directory / 'libaura_native_trace.so'
        cls.probe = cls.directory / 'native-fd-probe'
        subprocess.run(['cc', '-shared', '-fPIC', '-O2', '-fno-omit-frame-pointer',
                        '-Wall', '-Wextra', '-Werror',
                        str(ROOT / 'engine/src/main/cpp/aura_native_trace/native_fd_trace.c'),
                        '-ldl', '-o', str(cls.library)], check=True)
        subprocess.run(['cc', '-O2', '-g', '-rdynamic', '-Wall', '-Wextra', '-Werror',
                        str(ROOT / 'tools/arm64ec/tests/native_fd_probe.c'),
                        '-pthread', '-o', str(cls.probe)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_probe(self, mode, enabled='1', history='0'):
        env = dict(os.environ, LD_PRELOAD=str(self.library))
        env['AURA_NATIVE_FD_HISTORY'] = history
        env.pop('AURA_NATIVE_FD_TRACE', None)
        if enabled is not None:
            env['AURA_NATIVE_FD_TRACE'] = enabled
        return subprocess.run([str(self.probe), mode], env=env, text=True,
                              capture_output=True, timeout=10)

    def values(self, result):
        self.assertEqual(0, result.returncode, result.stderr)
        return {key: int(value) for key, value in re.findall(r'(\w+)=(-?\d+)', result.stdout)}

    def test_requires_exact_opt_in_and_never_bypasses_backend(self):
        for value in (None, '0', 'true', '11'):
            result = self.run_probe('owned', value)
            self.assertEqual(dict(result=-1, errno=errno.EBADF, calls=1, queries=0, bad_expected=0),
                             self.values(result))
            self.assertEqual('', result.stderr)

    def test_normal_fd_closes_once_preserving_errno_without_diagnostic(self):
        result = self.run_probe('normal')
        self.assertEqual(dict(result=0, errno=errno.ENOTTY, calls=1, queries=1, bad_expected=0),
                         self.values(result))
        self.assertEqual('', result.stderr)

    def test_invalid_fd_keeps_platform_error(self):
        result = self.run_probe('invalid')
        self.assertEqual(dict(result=-1, errno=errno.EBADF, calls=1, queries=0, bad_expected=0),
                         self.values(result))
        self.assertEqual('', result.stderr)

    def test_android_eintr_is_success_without_retrying_the_close(self):
        result = self.run_probe('eintr')
        self.assertEqual(dict(result=0, errno=errno.EINTR, calls=1, queries=1, bad_expected=0),
                         self.values(result))
        self.assertEqual('', result.stderr)

    def test_owned_fd_records_caller_without_changing_error_or_exposing_path(self):
        result = self.run_probe('owned')
        self.assertEqual(dict(result=-1, errno=errno.EBADF, calls=1, queries=1, bad_expected=0),
                         self.values(result))
        self.assertIn('owner_type=2; expected=UNOWNED', result.stderr)
        self.assertRegex(result.stderr, r'kind=CALLER; index=0; pc=0x[0-9a-f]+; module=native-fd-probe; offset=0x[0-9a-f]+')
        self.assertNotIn(str(self.directory), result.stderr)
        self.assertNotIn('123456', result.stderr)  # FILE* owner pointer is not recorded.
        self.assertLessEqual(result.stderr.count('kind=UNWIND'), 16)

    def test_diagnostic_event_limit_does_not_skip_backend_closes(self):
        result = self.run_probe('limit')
        values = self.values(result)
        self.assertEqual(12, values['calls'])
        self.assertEqual(errno.EBADF, values['errno'])
        self.assertEqual(0, values['bad_expected'])
        self.assertEqual(4, result.stderr.count('AURA_NATIVE_FD_V1 '))
        self.assertLessEqual(result.stderr.count('kind=UNWIND'), 64)
        self.assertLessEqual(len(result.stderr), 24000)

    def test_fatal_backend_is_not_suppressed(self):
        result = self.run_probe('fatal')
        self.assertEqual(-signal.SIGABRT, result.returncode)
        self.assertIn('AURA_NATIVE_FD_V1 ', result.stderr)

    def test_failed_logging_does_not_replace_the_close_error_or_raise_sigpipe(self):
        for mode in ('closed-stderr', 'broken-pipe'):
            result = self.run_probe(mode)
            self.assertEqual(dict(result=-1, errno=errno.EBADF, calls=1, queries=1, bad_expected=0),
                             self.values(result))

    def test_concurrent_normal_closes_do_not_generate_false_diagnostics(self):
        result = self.run_probe('threads')
        self.assertEqual(dict(calls=8000, queries=8000, bad_expected=0), self.values(result))
        self.assertEqual('', result.stderr)

    def test_history_is_opt_in_and_cannot_enable_trace_by_itself(self):
        for history, enabled in [('0', '1'), ('true', '1'), ('1', '0')]:
            result = self.run_probe('history-reuse', enabled, history)
            self.assertEqual(errno.EBADF, self.values(result)['errno'])
            self.assertNotIn('AURA_NATIVE_FD_HISTORY', result.stderr)

    def test_history_retains_close_reuse_and_stdio_lifetime_without_names_or_owner_pointer(self):
        result = self.run_probe('history-reuse', history='1')
        values = self.values(result)
        self.assertEqual(-1, values['result'])
        self.assertEqual(errno.EBADF, values['errno'])
        self.assertEqual(0, values['bad_expected'])
        for op in ('OPEN', 'CLOSE_BEGIN', 'CLOSE_END', 'FOPEN', 'FCLOSE_BEGIN', 'FCLOSE_END'):
            self.assertIn('op='+op+';', result.stderr)
        self.assertIn('coverage=PARTIAL', result.stderr)
        self.assertIn('AURA_NATIVE_FD_HISTORY_FRAME_V1', result.stderr)
        self.assertNotIn('/dev/null', result.stderr)
        self.assertNotIn('123456', result.stderr)
        self.assertNotIn(str(self.directory), result.stderr)

    def test_duplication_and_fdopen_history_still_delegates_the_real_operations(self):
        result = self.run_probe('history-dup', history='1')
        self.assertEqual(errno.EBADF, self.values(result)['errno'])
        for op in ('OPENAT', 'DUP2', 'FDOPEN'):
            self.assertIn('op='+op+';', result.stderr)

    def test_history_is_bounded_and_reports_eviction(self):
        result = self.run_probe('history-depth', history='1')
        self.assertEqual(errno.EBADF, self.values(result)['errno'])
        headers = re.findall(r'samples=(\d+); dropped=\d+; evicted=(\d+); coverage=PARTIAL', result.stderr)
        self.assertEqual(2, len(headers))
        for count, evicted in headers:
            self.assertLessEqual(int(count), 8)
            self.assertGreater(int(evicted), 0)
        self.assertLessEqual(result.stderr.count('AURA_NATIVE_FD_LIFETIME_V1 '), 16)

    def test_history_does_not_catch_or_replace_fatal_ownership_failure(self):
        result = self.run_probe('history-fatal', history='1')
        self.assertEqual(-signal.SIGABRT, result.returncode)
        self.assertIn('op=FOPEN;', result.stderr)
        self.assertIn('AURA_NATIVE_FD_V1 ', result.stderr)

    def test_history_concurrency_is_nonblocking_and_does_not_invent_owned_closes(self):
        result = self.run_probe('threads', history='1')
        values = self.values(result)
        self.assertEqual(8000, values['calls'])
        self.assertEqual(0, values['bad_expected'])
        self.assertEqual('', result.stderr)


if __name__ == '__main__':
    unittest.main()
