#!/usr/bin/env python3
"""Materialize Increment 22 and align the older roadmap assignment safely."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


OLD_BLOCK = """- [ ] **Increment 22 — Cross-layer diagnostic mapping**
  - Include stable interface/role/inout/AMS codes for unstorable interfaces, missing roles/members, incompatible roles, monitor drive, invalid inversion, multiple ordinary drivers, illegal open-drain drive, unsupported resolution, hierarchy-pass-through failure, discipline/access mismatch, implicit bridge conversion, and interface-layout collisions.
  - Map construction, driver/latch/cycle/hierarchy, shape/rank/layout/storage/index, materialization/naming/source-span, parser, verifier, pass, backend, external-tool, signed literal/conversion/mixed-sign/width/shift, loop stage/bound/body/dependency/effect/profile, enum encoding/decode/exhaustiveness, FSM graph/transition/recursion/illegal-state, domain-binding, CDC, RDC, gate/mux, protocol/pipeline, memory/effect, analog/mixed-signal, and waiver diagnostics back to Scala locations, hierarchy/index paths, and stable codes.

- [ ] **Increment 23 — Backend framework and capability profiles**
  - Add translation registration, deterministic output handling, profile-owned shaped-value layouts, expression materialization/naming and CheckProfile configuration, transactional target verification/reparse hooks, `verilog-a`/`verilog-ams` profiles, and explicit unsupported-feature errors.
"""

NEW_BLOCK = """- [ ] **Increment 22 — CIRCT conversion strategy and legalizer skeleton**
  - Define the target-neutral Nodal-to-CIRCT conversion boundary, conversion-target legality, type conversion, exact operation patterns, deterministic strategy/profile metadata, and explicit deferred-operation policy.
  - Add registered preflight and transactional legalizer passes plus direct and textual pass-pipeline entry points. Start with a narrow exact finite-width constant conversion and retain unsupported Nodal operations explicitly without approximation or silent erasure.
  - Preserve public API v0.3, symbolic parameters, hierarchy identity, domain provenance, source locations, and verified Nodal semantics; defer module/interface/domain/FSM/memory/analog/backend lowering to later increments.

- [ ] **Increment 23 — Cross-layer diagnostics, backend framework, and capability profiles**
  - Include stable interface/role/inout/AMS codes for unstorable interfaces, missing roles/members, incompatible roles, monitor drive, invalid inversion, multiple ordinary drivers, illegal open-drain drive, unsupported resolution, hierarchy-pass-through failure, discipline/access mismatch, implicit bridge conversion, and interface-layout collisions.
  - Map construction, driver/latch/cycle/hierarchy, shape/rank/layout/storage/index, materialization/naming/source-span, parser, verifier, conversion, pass, backend, external-tool, signed literal/conversion/mixed-sign/width/shift, loop stage/bound/body/dependency/effect/profile, enum encoding/decode/exhaustiveness, FSM graph/transition/recursion/illegal-state, domain-binding, CDC, RDC, gate/mux, protocol/pipeline, memory/effect, analog/mixed-signal, and waiver diagnostics back to Scala locations, hierarchy/index paths, and stable codes.
  - Add translation registration, deterministic output handling, profile-owned shaped-value layouts, expression materialization/naming and CheckProfile configuration, transactional target verification/reparse hooks, `verilog-a`/`verilog-ams` profiles, and explicit unsupported-feature errors.
"""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    subprocess.run(
        [sys.executable, str(Path(__file__).with_name("materialize_increment22.py")), str(root)],
        check=True,
    )

    roadmap_path = root / "docs/roadmap/nodal-development-todo.md"
    roadmap = roadmap_path.read_text(encoding="utf-8")
    if NEW_BLOCK in roadmap:
        return
    if roadmap.count(OLD_BLOCK) != 1:
        requested = "- [ ] **Increment 22 — CIRCT conversion strategy and legalizer skeleton**"
        if requested in roadmap:
            return
        raise RuntimeError(
            "Increment 22 roadmap assignment is neither the retained legacy block "
            "nor the requested CIRCT legalizer item"
        )
    roadmap_path.write_text(roadmap.replace(OLD_BLOCK, NEW_BLOCK, 1), encoding="utf-8")


if __name__ == "__main__":
    main()
