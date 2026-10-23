from __future__ import annotations
import hashlib
import json
import sys
import unittest
from pathlib import Path
from unittest import mock
sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_native_overlay as BASE
OVERLAY, STATE, INSTALL, LOCK = BASE.OVERLAY, BASE.STATE, BASE.INSTALL, BASE.LOCK
NAME = 'circt-structural-consumers-v1'

class NativeStructuralOverlayTests(unittest.TestCase):
    setUp = BASE.NativeOverlayTests.setUp
    change_manifest = BASE.NativeOverlayTests.change_manifest
    fake_base = BASE.NativeOverlayTests.fake_base
    errors = BASE.NativeOverlayTests.errors

    def setUp(self):
        BASE.NativeOverlayTests.setUp(self)
        self.manifest = self.root / 'toolchains/patches' / NAME / 'manifest.json'

    def profile(self):
        return OVERLAY.load_overlay(self.root, self.lock, NAME)

    def fake_overlay(self):
        self.fake_base()
        profile = self.profile()
        outputs = {}
        for relative in OVERLAY._base_libraries(profile):
            path = self.prefix / relative
            path.write_bytes(('patched ' + relative).encode())
            outputs[relative] = OVERLAY.sha256(path)
        identity = {'tools': {key: {'path': '/fixture/' + key, 'sha256': '1' * 64, 'version': 'fixture'} for key in ['cxx', 'cmake', 'ninja', 'llvm-config']}, 'llvm_cxxflags': '-std=c++17', 'cmake_cache_sha256': '2' * 64}
        self.receipt = self.prefix / 'share/nodal/overlays' / NAME / 'build.json'
        self.receipt.parent.mkdir(parents=True)
        self.receipt.write_text(json.dumps({'identity': OVERLAY.identity(profile), 'output_sha256': outputs, 'sources': profile['sources'], 'circt_commit': profile['circt_commit'], 'base_lock_sha256': profile['base_lock_sha256'], 'builder_sha256': profile['builder_sha256'], 'build': {key: identity for key in OVERLAY._components(profile)}}, sort_keys=True))
        value = OVERLAY.identity(profile) | {'output_sha256': outputs, 'receipt_sha256': OVERLAY.sha256(self.receipt)}
        STATE.write_manifest(self.prefix, self.lock, mode='prebuilt', host=('linux', 'x86_64'), overlay=value)

    def change_installed(self, fn):
        path = self.prefix / '.nodal-toolchain.json'; value = json.loads(path.read_text()); fn(value['overlay']); path.write_text(json.dumps(value))

    def change_receipt(self, fn):
        value = json.loads(self.receipt.read_text()); fn(value); self.receipt.write_text(json.dumps(value)); self.change_installed(lambda v: v.update(receipt_sha256=OVERLAY.sha256(self.receipt)))

    def test_combined_exact_catalog_and_pin(self):
        profile = self.profile()
        self.assertEqual(OVERLAY._components(profile), ['CIRCTHW', 'CIRCTSVTransforms', 'CIRCTExportVerilog'])
        plan = STATE.plan_install(self.root, self.lock, mode='prebuilt', host=('linux','x86_64'), overlay=NAME)
        self.assertEqual(plan['overlay']['id'], NAME)
        self.assertEqual(len(profile['sources']), 39)

    def test_unknown_duplicate_missing_reordered_components(self):
        original = self.manifest.read_text()
        for change in [lambda v: v['components'][1].update(name='Arbitrary'), lambda v: v['components'][1].update(name='CIRCTHW'), lambda v: v['components'].pop(), lambda v: v['components'].reverse()]:
            self.manifest.write_text(original); self.change_manifest(change)
            with self.assertRaisesRegex(LOCK.LockError, 'catalog'): self.profile()

    def test_wrong_builder_identity(self):
        self.change_manifest(lambda v: v.update(builder_sha256='0'*64))
        with self.assertRaisesRegex(LOCK.LockError, 'builder identity'): self.profile()

    def test_wrong_revision(self):
        self.change_manifest(lambda v: v.update(circt_commit='0'*40))
        with self.assertRaisesRegex(LOCK.LockError, 'exact locked'): self.profile()

    def test_no_manifest_commands(self):
        self.change_manifest(lambda v: v['components'][0].update(command='anything'))
        with self.assertRaisesRegex(LOCK.LockError, 'catalog'): self.profile()

    def test_patch_source_and_checksum_closed(self):
        self.change_manifest(lambda v: v['sources'][0].update(path='lib/Other/Unsafe.cpp'))
        with self.assertRaisesRegex(LOCK.LockError, 'outside'): self.profile()

    def test_valid_three_output_identity(self):
        self.fake_overlay(); self.assertEqual(self.errors(NAME), [])

    def test_missing_unknown_output_rejected(self):
        for extra in [False, True]:
            with self.subTest(extra=extra):
                self.fake_overlay() if not self.prefix.exists() else None
                before=(self.prefix/'.nodal-toolchain.json').read_text()
                self.change_installed(lambda v: v['output_sha256'].update({'lib/libOther.so':'1'*64}) if extra else v['output_sha256'].pop('lib/libCIRCTSVTransforms.so'))
                self.assertIn('output inventory', ' '.join(self.errors(NAME)))
                (self.prefix/'.nodal-toolchain.json').write_text(before)

    def test_each_output_tamper(self):
        self.fake_overlay()
        for relative in OVERLAY._base_libraries(self.profile()):
            path=self.prefix/relative; before=path.read_bytes();path.write_bytes(b'wrong')
            self.assertIn('library checksum', ' '.join(self.errors(NAME)));path.write_bytes(before)

    def test_stale_receipt_identity(self):
        self.fake_overlay(); self.change_receipt(lambda v: v['identity'].update(patch_sha256='0'*64))
        self.assertIn('receipt identity', ' '.join(self.errors(NAME)))

    def test_stale_receipt_output(self):
        self.fake_overlay(); self.change_receipt(lambda v: v['output_sha256'].update({'lib/libCIRCTHW.so':'0'*64}))
        self.assertIn('receipt identity', ' '.join(self.errors(NAME)))

    def test_missing_component_build_identity(self):
        self.fake_overlay(); self.change_receipt(lambda v: v['build'].pop('CIRCTExportVerilog'))
        self.assertIn('receipt identity', ' '.join(self.errors(NAME)))

    def test_bad_tool_identity(self):
        self.fake_overlay(); self.change_receipt(lambda v: v['build']['CIRCTHW']['tools']['cxx'].update(sha256='invalid'))
        self.assertIn('tool identity', ' '.join(self.errors(NAME)))

    def test_force_cannot_replace_base(self):
        self.fake_base()
        with self.assertRaisesRegex(STATE.BootstrapError,'fresh destination'): INSTALL.install(self.root,self.lock,mode='prebuilt',prefix=self.prefix,overlay=NAME,force=True)

    def test_exact_repeat_noop(self):
        self.fake_overlay()
        with mock.patch.object(INSTALL,'download',side_effect=AssertionError('no download')):
            self.assertEqual(INSTALL.install(self.root,self.lock,mode='prebuilt',prefix=self.prefix,overlay=NAME)['status'],'already-installed')

    def test_wrong_base_any_component_precedes_fetch(self):
        self.fake_base(); profile=self.profile()
        for entry in profile['components']:
            path=self.prefix/f"lib/lib{entry['name']}.so";path.write_bytes(b'base '+entry['name'].encode());entry['base_library_sha256']=OVERLAY.sha256(path)
        for entry in profile['components']:
            old=entry['base_library_sha256'];entry['base_library_sha256']='0'*64;download=mock.Mock()
            with self.assertRaisesRegex(LOCK.LockError,'exact unmodified'): OVERLAY.apply_overlay(self.prefix,profile,self.root,self.root/'cache',download,1)
            download.assert_not_called();entry['base_library_sha256']=old

    def test_stale_builder_receipt(self):
        self.fake_overlay(); self.change_receipt(lambda value: value.update(builder_sha256='0'*64))
        self.assertIn('receipt identity', ' '.join(self.errors(NAME)))

    def test_output_map_must_be_exact_map(self):
        self.fake_overlay(); self.change_installed(lambda value: value.update(output_sha256='0'*64))
        self.assertIn('output inventory', ' '.join(self.errors(NAME)))

    def test_canonical_component_source_inventory(self):
        source = self.root / 'source'
        headers = {
            'CIRCTHW': ('lib/Dialect/HW', 'set(CIRCT_HW_Sources ConversionPatterns.cpp)'),
            'CIRCTSVTransforms': ('lib/Dialect/SV/Transforms', 'add_circt_dialect_library(CIRCTSVTransforms GeneratorCallout.cpp DEPENDS)'),
            'CIRCTExportVerilog': ('lib/Conversion/ExportVerilog', 'add_circt_translation_library(CIRCTExportVerilog ApplyLoweringOptions.cpp ADDITIONAL_HEADER_DIRS)'),
        }
        for directory, text in headers.values():
            path = source / directory / 'CMakeLists.txt'; path.parent.mkdir(parents=True); path.write_text(text)
        self.assertEqual({name: len(paths) for name, paths in OVERLAY._component_sources(self.profile(), source).items()}, {name: 1 for name in headers})
        path = source / headers['CIRCTSVTransforms'][0] / 'CMakeLists.txt'
        for text in [headers['CIRCTSVTransforms'][1].replace('add_circt_dialect_library','add_circt_library'), headers['CIRCTSVTransforms'][1].replace('GeneratorCallout.cpp','GeneratorCallout.cpp GeneratorCallout.cpp'), headers['CIRCTSVTransforms'][1].replace('GeneratorCallout.cpp','Unknown.cpp')]:
            path.write_text(text)
            with self.assertRaisesRegex(LOCK.LockError, 'inventory'): OVERLAY._component_sources(self.profile(), source)

if __name__=='__main__': unittest.main()
