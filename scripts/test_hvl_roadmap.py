#!/usr/bin/env python3
"""Mutation tests for roadmap metadata, not implementations of the future HVL API."""
from __future__ import annotations

import copy
import json
import re
from pathlib import Path
import shutil
import tempfile
import unittest

from check_hvl_roadmap import (
    DOCUMENTS, PROJECTION_PLAN, ROOT, load_surface, unique_object,
    validate_projection_dependencies, validate_surface,
)


def copy_documents(root: Path) -> None:
    for path in DOCUMENTS:
        destination = root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / path, destination)


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

    def test_misplaced_umbrella_ownership(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            copy_documents(root)
            todo = root / 'docs/roadmap/nodal-development-todo.md'
            text = todo.read_text()
            note = next(line for line in text.splitlines() if line.startswith('  - Ownership: CAP-01'))
            text = text.replace(note + '\n', '', 1)
            lines = text.splitlines()
            heading = next(i for i, line in enumerate(lines) if line.startswith('- [ ] **Digital Verification Increment 7 —'))
            lines.insert(heading + 1, note)
            todo.write_text('\n'.join(lines) + '\n')
            errors = validate_surface(self.data, root)
            self.assertTrue(any('NODAL-HVL-014:' in error for error in errors), errors)

    def test_floating_ownership_after_section_separator(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            copy_documents(root)
            plan = root / 'docs/roadmap/dependent-productivity-and-verification-tracks-v0.1-plan.md'
            text = plan.read_text()
            note = next(line for line in text.splitlines() if line.startswith('  - Ownership: LIVE-08'))
            plan.write_text(text.replace(note, '---\n' + note, 1))
            errors = validate_surface(self.data, root)
            self.assertTrue(any('NODAL-HVL-014:' in error for error in errors), errors)

    def test_missing_profile_release_binding(self):
        del self.data['profileReleaseGates']['nodal.hvl.projection.uvm']
        self.rejected('006')

    def test_remapped_profile_release_binding(self):
        self.data['profileReleaseGates']['nodal.hvl.projection.uvm'] = 'release.vtb'
        self.rejected('006')

    def test_parity_vtb_excludes_uvm(self):
        self.data['dependencyNodes']['release.parity-vtb']['requires'].append('UVM-09')
        self.rejected('010')

    def test_parity_vtb_excludes_aggregate(self):
        self.data['dependencyNodes']['release.parity-vtb']['requires'].append('release.parity-aggregate')
        self.rejected('010')

    def test_every_pairwise_parity_release_is_independent(self):
        original = copy.deepcopy(self.data)
        cases = [
            ('release.parity-vtb', 'AMSP-03'),
            ('release.parity-uvm', 'VTB-07'),
            ('release.parity-open-ams', 'AMSP-04'),
            ('release.parity-verilog-ams', 'AMSP-03'),
            ('release.parity-uvm-ms', 'AMSP-02'),
        ]
        for gate, unrelated in cases:
            with self.subTest(gate=gate, unrelated=unrelated):
                self.data = copy.deepcopy(original)
                self.data['dependencyNodes'][gate]['requires'].append(unrelated)
                self.rejected('010')

    def test_legacy_projection_dependencies_checked_with_surface(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            copy_documents(root)
            self.assertEqual(validate_surface(self.data, root), [])
            plan = root / PROJECTION_PLAN
            text = plan.read_text(encoding='utf-8')
            plan.write_text(text.replace('## Completion claim',
                            '- UVM parity must wait for Verilog-TB execution.\n\n## Completion claim', 1),
                            encoding='utf-8')
            errors = validate_surface(self.data, root)
            self.assertEqual(len(errors), 1, errors)
            self.assertTrue(errors[0].startswith('NODAL-HVL-015:'), errors)

    def test_duplicate_json_keys_rejected(self):
        with self.assertRaises(ValueError):
            json.loads('{"id":1,"id":2}', object_pairs_hook=unique_object)


class ProjectionDependencyTests(unittest.TestCase):
    def setUp(self):
        self.text = (ROOT / PROJECTION_PLAN).read_text(encoding='utf-8')

    def rejected(self, text):
        errors = validate_projection_dependencies(text)
        self.assertEqual(len(errors), 1, errors)
        self.assertTrue(errors[0].startswith('NODAL-HVL-015:'), errors)

    def test_current_projection_dependencies(self):
        self.assertEqual(validate_projection_dependencies(self.text), [])

    def test_each_legacy_dependency_rejected(self):
        obsolete = [
            'Digital Verification Increment 6 depends on Digital Verification Increments 1-5 and Foundation 152.',
            'Digital Verification Increment 8 may reuse Increments 1-5 but parity closure waits for Increment 7.',
            'AMS Verification Increment 6 depends on AMS Verification Increments 1-5, Foundation 152, and the required Foundation AMS/backend work.',
            'AMS Verification Increment 7 depends on AMS Verification Increment 6 only for shared identities.',
        ]
        for dependency in obsolete:
            with self.subTest(dependency=dependency):
                # Keep every valid reference: checking link presence alone is insufficient.
                self.rejected(self.text.replace('## Completion claim',
                              '- ' + dependency + '\n\n## Completion claim', 1))

    def test_original_dependency_section_rejected(self):
        legacy = 'Foundation Increment 152 depends on the existing Foundation verification, interface, register, property, AMS, source-map, plugin, and tool-adapter contracts. The implementation-track increments remain blocked until every Foundation checkbox is complete.\n\nWithin the dependent tracks:\n\n- Digital Verification Increment 6 depends on Digital Verification Increments 1-5 and Foundation 152.\n- Digital Verification Increment 7 depends on Digital Verification Increment 6.\n- Digital Verification Increment 8 may reuse Increments 1-5 but parity closure waits for Increment 7.\n- AMS Verification Increment 6 depends on AMS Verification Increments 1-5, Foundation 152, and the required Foundation AMS/backend work.\n- AMS Verification Increment 7 depends on AMS Verification Increment 6 only for shared identities; its open harness path may be implemented independently where the same frozen Verification IR operations are available.\n- UVM/UVM-MS generation remains independent of procedural HDL execution, except for shared canonical identities and parity evidence.'
        text = re.sub(r'(?ms)(^## Implementation dependencies\n).*?(?=^## Completion claim)',
                      lambda match: match[1] + '\n' + legacy + '\n\n', self.text, count=1)
        self.rejected(text)

    def test_missing_ledger_reference(self):
        self.rejected(self.text.replace(
            '[resolved dependency and independent release ledger](nodal-hvl-simulation-v0.1-plan.md#resolved-dependency-and-independent-release-ledger)',
            'the old prerequisite list'))

    def test_wrong_surface_reference(self):
        self.rejected(self.text.replace(
            '[`dependencyNodes` and `profileReleaseGates`](nodal-hvl-simulation-v0.1-surface.json)',
            '[`dependencyNodes` and `profileReleaseGates`](obsolete-surface.json)'))

    def test_missing_section(self):
        self.rejected(self.text.replace('## Implementation dependencies', '## Retired dependencies'))

    def test_duplicate_section(self):
        self.rejected(self.text + '\n## Implementation dependencies\n\nDuplicate.\n')

    def test_extra_dependency_heading(self):
        for heading in ('## Legacy prerequisites', '### Legacy prerequisites'):
            with self.subTest(heading=heading):
                self.rejected(self.text.replace('## Completion claim',
                              heading + '\n\nUVM requires VTB.\n\n## Completion claim', 1))

    def test_line_wrapping_allowed(self):
        text = self.text.replace('Umbrella increment numbers identify ownership, not an implicit execution order.',
                                 'Umbrella increment numbers identify ownership,\nnot an implicit execution order.')
        self.assertEqual(validate_projection_dependencies(text), [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
