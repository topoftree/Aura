#!/usr/bin/env python3
"""Regression checks against the actual bundled PE files, without running them."""
import unittest
from audit_windows_dependencies import load_images, audit
from pe_dependencies import PeImage, check_dependencies, resolve_module
from assemble import archive
from package_windows_dependencies import ASSETS


class WindowsDependenciesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.images = load_images()

    def test_bundle_and_known_upstream_limitations(self):
        result = audit()
        for tree in result['trees'].values():
            self.assertEqual(1025, tree['icuForwardersResolved'])

    def test_missing_forward_target_reproduces_stardew_failure(self):
        for directory, images in self.images.items():
            incomplete = {k: v for k, v in images.items() if k != 'icuuc68.dll'}
            problems = check_dependencies(incomplete)['problems']
            self.assertTrue(any(p.get('symbol') == 'u_charsToUChars' and
                                p.get('error') == 'missing module: icuuc68.dll' for p in problems), directory)

    def test_transitive_data_dll_is_required(self):
        for directory, images in self.images.items():
            incomplete = {k: v for k, v in images.items() if k != 'icudt68.dll'}
            problems = check_dependencies(incomplete)['problems']
            self.assertTrue(any(p['importer'] == 'icuuc68.dll' and p.get('module') == 'icudt68.dll'
                                for p in problems), directory)

    def test_api_set_crt_contracts_resolve_to_bundled_runtime(self):
        for images in self.images.values():
            self.assertEqual('ucrtbase.dll', resolve_module('api-ms-win-crt-runtime-l1-1-0.dll',
                'icuuc68.dll', images, images['apisetschema.dll'].api_sets()))

    def test_arm64x_ec_export_view_resolves_crt_exception_handler(self):
        for path, data, _ in archive(ASSETS / 'runtime.tzst'):
            if path == 'opt/wine/lib/wine/aarch64-windows/vcruntime140_1.dll':
                original = PeImage(data)
                self.assertNotIn('__CxxFrameHandler4', original.exports())
                self.assertIn('__CxxFrameHandler4', original.ec_view().exports())
                self.assertEqual(data, original.data)
                return
        self.fail('Bundled CRT missing')

    def test_icu_library_architectures_are_not_interchangeable(self):
        self.assertEqual('0x8664', self.images['system32']['icuuc68.dll'].info['machine'])
        self.assertEqual('0x14c', self.images['syswow64']['icuuc68.dll'].info['machine'])

    def test_truncated_real_pe_is_rejected(self):
        image = self.images['system32']['icuuc68.dll']
        for length in [1, 64, 256, len(image.data) // 2]:
            with self.assertRaises((ValueError, IndexError)):
                PeImage(image.data[:length])


if __name__ == '__main__':
    unittest.main()
