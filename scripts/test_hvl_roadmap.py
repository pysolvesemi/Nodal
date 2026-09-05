#!/usr/bin/env python3
"""Mutation tests for roadmap metadata, not implementations of the future HVL API."""
from __future__ import annotations

import copy
import json
import unittest

from check_hvl_roadmap import ROOT, load_surface, unique_object, validate_surface


class HvlRoadmapTests(unittest.TestCase):
    def setUp(self):
        self.data = copy.deepcopy(load_surface())

    def rejected(self, code):
        errors = validate_surface(self.data, check_docs=False)
        self.assertTrue(any(f'NODAL-HVL-{code}:' in e for e in errors), errors)

    def test_current_surface_and_documentation(self):
        self.assertEqual(validate_surface(self.data, ROOT), [])

    def test_unresolved_dependency(self):
        self.data['dependencyNodes']['CAP-02']['requires'].append('unknown.contract')
        self.rejected('006')

    def test_unknown_capability(self):
        self.data['capabilityDefinitions']['nodal.hvl.portable.ams']['requires'].append('unknown.capability')
        self.rejected('005')

    def test_dependency_cycle(self):
        self.data['dependencyNodes']['LIVE-01']['requires'].append('LIVE-08')
        self.rejected('007')

    def test_capability_cycle(self):
        self.data['capabilityDefinitions']['nodal.hvl.portable.core']['requires'] = ['nodal.hvl.portable.ams']
        self.rejected('007')

    def test_direct_vtb_uvm_dependency(self):
        self.data['dependencyNodes']['VTB-07']['requires'].append('UVM-09')
        self.rejected('010')

    def test_indirect_vtb_uvm_dependency(self):
        self.data['dependencyNodes']['VTB-04']['requires'].append('UVM-02')
        self.rejected('010')

    def test_uvm_does_not_require_vtb(self):
        self.data['dependencyNodes']['UVM-04']['requires'].append('VTB-02')
        self.rejected('010')

    def test_open_harness_cannot_wait_for_uvm_ms(self):
        self.data['dependencyNodes']['evidence.open-ams-tool']['requires'].append('AMSP-04')
        self.rejected('010')

    def test_analog_release_excludes_mixed_signal(self):
        self.data['dependencyNodes']['ANA-09']['requires'].append('MS-06')
        self.rejected('010')

    def test_live_generated_only_not_implicit(self):
        self.data['capabilityAxes']['executionClass']['live']['automaticallySupportsGeneratedOnlyExtensions'] = True
        self.rejected('002')

    def test_capture_must_preserve_extensions(self):
        self.data['capabilityAxes']['executionClass']['portable']['captureIncludesTypedProfileExtensions'] = False
        self.rejected('003')

    def test_extensions_need_roundtrip_contract(self):
        self.data['semanticLayers']['profileExtensionContract'].remove('serialization-roundtrip')
        self.rejected('003')

    def test_common_library_cannot_depend_on_profile(self):
        self.data['libraryLayers']['commonSemanticLibrary']['mayDependOnProfileLibraries'] = True
        self.rejected('004')

    def test_no_universal_target_implementation(self):
        self.data['libraryLayers']['universalTargetImplementationClassAllowed'] = True
        self.rejected('004')

    def test_no_cross_lowering(self):
        self.data['semanticLayers']['crossLoweringAllowed']['uvmToProceduralHdlTbIr'] = True
        self.rejected('004')

    def test_duplicate_item(self):
        self.data['workstreams'][0]['increments'].append('LIVE-01')
        self.rejected('008')

    def test_foundation_cannot_be_bypassed(self):
        self.data['dependencyNodes']['LIVE-01']['requires'] = []
        self.rejected('009')

    def test_source_only_uvm_is_not_qualified(self):
        self.data['dependencyNodes']['UVM-08']['requires'] = ['UVM-06']
        self.rejected('011')

    def test_missing_open_tool_evidence(self):
        self.data['dependencyNodes']['release.open-ams-harness']['requires'] = ['AMSP-03']
        self.rejected('011')

    def test_uvm_only_fixture_not_silently_live(self):
        self.data['qualificationCases'][2]['live'] = True
        self.rejected('012')

    def test_roadmap_fixture_not_an_implementation_claim(self):
        self.data['qualificationCasesStatus'] = 'implemented'
        self.rejected('012')

    def test_missing_synchronized_document(self):
        self.data['synchronizedDocuments'].pop()
        self.rejected('013')

    def test_invalid_source_anchor(self):
        self.data['dependencyNodes']['digital-live.required-slice']['anchor'] = 'NONEXISTENT-ARCHITECTURE-GATE'
        errors = validate_surface(self.data, ROOT)
        self.assertTrue(any('NODAL-HVL-013:' in e for e in errors), errors)

    def test_duplicate_json_keys_rejected(self):
        with self.assertRaises(ValueError):
            json.loads('{"id":1,"id":2}', object_pairs_hook=unique_object)


if __name__ == '__main__':
    unittest.main(verbosity=2)
