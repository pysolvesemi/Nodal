"""Focused closed-catalog and installed-header contracts for the successor."""
from __future__ import annotations
import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_native_structural_overlay as BASE
OVERLAY, LOCK, STATE = BASE.OVERLAY, BASE.LOCK, BASE.STATE
NAME = 'circt-symbolic-consumers-v1'
HEADER = 'include/circt/Dialect/Comb/CombOps.h'


class NativeSymbolicOverlayTests(unittest.TestCase):
    change_manifest = BASE.NativeStructuralOverlayTests.change_manifest
    fake_base = BASE.NativeStructuralOverlayTests.fake_base
    errors = BASE.NativeStructuralOverlayTests.errors
    fake_overlay = BASE.NativeStructuralOverlayTests.fake_overlay
    change_installed = BASE.NativeStructuralOverlayTests.change_installed
    change_receipt = BASE.NativeStructuralOverlayTests.change_receipt

    def setUp(self):
        BASE.BASE.NativeOverlayTests.setUp(self)
        old = self.root / 'toolchains/patches/circt-structural-consumers-v1'
        directory = self.root / 'toolchains/patches' / NAME
        directory.mkdir(exist_ok=True)
        value = json.loads((old / 'manifest.json').read_text())
        value['id'] = NAME
        value['components'] = [{'name': name, 'base_library_sha256': '1' * 64}
                               for name in OVERLAY.CATALOGS[1]]
        value['sources'].append({'path': HEADER, 'before': '2' * 64,
                                 'after': OVERLAY.hashlib.sha256(b'patched header').hexdigest()})
        self.manifest = directory / 'manifest.json'
        self.manifest.write_text(json.dumps(value))
        (directory / value['patch']['file']).write_bytes((old / value['patch']['file']).read_bytes())

    def profile(self):
        return OVERLAY.load_overlay(self.root, self.lock, NAME)

    def installed(self):
        self.fake_overlay()
        receipt = self.prefix / 'share/nodal/overlays' / NAME / 'build.json'
        receipt.parent.mkdir(parents=True)
        self.receipt.rename(receipt)
        self.receipt = receipt
        header = self.prefix / HEADER
        header.parent.mkdir(parents=True, exist_ok=True)
        header.write_bytes(b'patched header')
        self.change_installed(lambda value: value['output_sha256'].update({HEADER: OVERLAY.sha256(header)}))
        self.change_receipt(lambda value: value['output_sha256'].update({HEADER: OVERLAY.sha256(header)}))

    def test_exact_six_components_and_header_output(self):
        profile = self.profile()
        self.assertEqual(tuple(OVERLAY._components(profile)), OVERLAY.CATALOGS[1])
        self.assertEqual(len(OVERLAY._output_paths(profile)), 7)
        self.installed()
        self.assertEqual(self.errors(NAME), [])

    def test_partial_or_reordered_catalog_refused(self):
        original = self.manifest.read_text()
        for change in [lambda v: v['components'].pop(2),
                       lambda v: v['components'].reverse(),
                       lambda v: v['components'][3].update(name='CIRCTComb')]:
            self.manifest.write_text(original)
            self.change_manifest(change)
            with self.assertRaisesRegex(LOCK.LockError, 'catalog'):
                self.profile()

    def test_stale_builder_is_not_legacy_whitelisted(self):
        self.change_manifest(lambda value: value.update(builder_sha256='8de1744111dd436abae0fd00afaa4f4e534ef7cdc96fbd2490d0b3d5025ef5ae'))
        with self.assertRaisesRegex(LOCK.LockError, 'builder identity'):
            self.profile()

    def test_header_tamper_refused(self):
        self.installed()
        (self.prefix / HEADER).write_bytes(b'wrong')
        self.assertIn('checksum', ' '.join(self.errors(NAME)))

    def test_header_symlink_refused(self):
        self.installed()
        header = self.prefix / HEADER
        target = header.with_suffix('.original')
        header.rename(target)
        header.symlink_to(target)
        self.assertIn('checksum', ' '.join(self.errors(NAME)))

    def test_missing_header_output_refused(self):
        self.installed()
        self.change_installed(lambda value: value['output_sha256'].pop(HEADER))
        self.assertIn('output inventory', ' '.join(self.errors(NAME)))

    def test_changed_header_with_rehashed_receipt_refused(self):
        self.installed()
        (self.prefix / HEADER).write_bytes(b'wrong')
        digest = OVERLAY.sha256(self.prefix / HEADER)
        self.change_installed(lambda value: value['output_sha256'].update({HEADER: digest}))
        self.change_receipt(lambda value: value['output_sha256'].update({HEADER: digest}))
        self.assertIn('header identity', ' '.join(self.errors(NAME)))

    def test_unchanged_headers_not_installed(self):
        self.change_manifest(lambda value: value['sources'][-1].update(after='2' * 64))
        self.assertEqual(OVERLAY._installed_headers(self.profile()), {})

    def test_wrong_base_header_precedes_fetch(self):
        self.fake_base()
        profile = self.profile()
        for entry in profile['components']:
            path = self.prefix / f"lib/lib{entry['name']}.so"
            path.write_bytes(b'base')
            entry['base_library_sha256'] = OVERLAY.sha256(path)
        with self.assertRaisesRegex(LOCK.LockError, 'base headers'):
            OVERLAY.apply_overlay(self.prefix, profile, self.root, self.root / 'cache',
                                  lambda *_: self.fail('must not fetch'), 2)


if __name__ == '__main__':
    unittest.main()
