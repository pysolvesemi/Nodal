#!/usr/bin/env python3
"""Bounded final wording amendments; removed after materialization."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
changes = [
    ('docs/roadmap/nodal-development-todo.md',
     'Nodal HVL is canonical. Native/open-source execution, generated procedural Verilog testbenches, and generated UVM are sibling projections of one Verification Semantic IR; no generated backend is the simulation foundation.',
     'Live Nodal HVL is the primary host-side execution path and does not require complete static capture of ordinary Scala. Capturable common semantics plus declared typed profile extensions support only their qualified modes. Verilog-TB and UVM are independent sibling profiles with separate libraries, generated IRs and release gates; neither defines or restricts otherwise-supported live execution.'),
    ('docs/architecture/0023-unified-hvl-native-sim-uvm-uvmms-architecture.md',
     'Users should be able to author one verification environment in Scala/Nodal, run it directly with open-source simulation where possible, and later generate industry-standard UVM or UVM-MS environments without rewriting the testbench.',
     'Users should be able to reuse common verification intent across qualified live and generated modes. Ordinary live Scala need not be capturable, and explicit UVM/UVM-MS-only extensions or wrappers do not imply native execution or procedural-Verilog eligibility.'),
    ('docs/architecture/0023-unified-hvl-native-sim-uvm-uvmms-architecture.md',
     'Rejected because behavior would drift and defeat the single-source verification goal.',
     'Independent copies of shared test intent are discouraged because behavior can drift. This does not prohibit explicit profile-specific wrappers, library implementations or generated-only packages; ADR 0027 requires those boundaries rather than a universal implementation class.'),
    ('docs/architecture/0025-generated-procedural-hdl-testbench-projections.md',
     'Rejected because test intent, scoreboards, coverage, and protocol behavior would drift.',
     'Independent duplicate implementations of common test intent, scoreboards, coverage and protocol rules are discouraged. Separate qualified profile extensions and implementation libraries are permitted and required where module-based and class-based semantics differ; see ADR 0027.'),
    ('docs/roadmap/generated-hdl-testbench-projections-v0.1-plan.md',
     'Generate native/open harness behavior, Verilog-AMS collateral where supported, and UVM-MS VIP from one Nodal mixed-signal VIP source.',
     'Reuse common mixed-signal VIP intent with explicit qualified live/open-harness, Verilog-AMS and/or UVM-MS packages. Profile-specific wrappers are valid; a package need not support every profile.'),
]
for path, old, new in changes:
    file = root / path
    text = file.read_text(encoding='utf-8')
    if text.count(old) == 1:
        file.write_text(text.replace(old, new, 1), encoding='utf-8')
    elif old not in text and text.count(new) == 1:
        pass
    else:
        raise SystemExit('Unexpected wording state: ' + path)
for path in ('docs/roadmap/nodal-hvl-simulation-v0.1-plan.md', 'docs/design-gates/NodalHvlCapabilityConsistency-DG-v0.1.md'):
    file = root / path
    lines = file.read_text(encoding='utf-8').splitlines()
    file.write_text('\n'.join(line.rstrip() for line in lines) + '\n', encoding='utf-8')
print('Reconciled five broad single-source statements and Markdown whitespace without changing checkboxes.')
