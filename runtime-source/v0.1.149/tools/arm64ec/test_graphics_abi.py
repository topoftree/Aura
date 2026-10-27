"""Regression against the actual packaged Mesa imports and helper exports."""
import copy
import unittest

from audit_graphics_abi import SHIM, catalogue, validate


class GraphicsAbi(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.libraries = catalogue()

    def test_actual_packaged_graphics_dependency_symbols_resolve(self):
        result = validate(self.libraries)
        self.assertIn('usr/lib/libvulkan_freedreno.so', result['consumers'])
        self.assertIn('usr/lib/libGL.so.1.5.0', result['consumers'])

    def test_old_shim_exporting_only_plain_names_is_rejected(self):
        libraries = copy.deepcopy(self.libraries)
        libraries[SHIM]['exports'] -= {s for s in libraries[SHIM]['exports'] if s.startswith('libandroid_')}
        with self.assertRaisesRegex(AssertionError, 'libandroid_shmget'):
            validate(libraries)

    def test_unrelated_shmem_library_cannot_mask_missing_dependency(self):
        libraries = copy.deepcopy(self.libraries)
        turnip = libraries['usr/lib/libvulkan_freedreno.so']
        turnip['needed'].remove('libandroid-sysvshm.so')
        # libandroid-shmem.so is present, but is NOT in Turnip's dependency tree.
        self.assertIn('libandroid_shmget', libraries['usr/lib/libandroid-shmem.so']['exports'])
        with self.assertRaisesRegex(AssertionError, 'unresolved libandroid imports'):
            validate(libraries)


if __name__ == '__main__':
    unittest.main()
