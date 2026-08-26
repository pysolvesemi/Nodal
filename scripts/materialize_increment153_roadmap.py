#!/usr/bin/env python3
"""Materialize the function-local semantic naming roadmap extension."""

from __future__ import annotations

import json
from pathlib import Path

ROADMAP_PATH = Path("docs/roadmap/nodal-development-todo.md")
GATE_PATH = Path("docs/roadmap/dependent-track-gate-v0.1.json")

FOUNDATION_BLOCK = r"""- [ ] **Foundation Increment 153 — Function-local lexical naming and alias contract**
  - Freeze a target-neutral structured name-path contract with the priority `explicit user name > caller prefix plus function-local binder > lexical binder > outer/member alias > semantic role or sink affinity > generated fallback` while keeping public API v0.3 unchanged.
  - Retain raw Scala binders and aliases for hardware-producing `val`, `var`, and `lazy val` declarations inside ordinary methods, local methods, lambdas, nested blocks, loop bodies, and match branches, together with lexical owner, definition span, invocation context, and provenance.
  - Keep raw Scala spelling separate from target-HDL sanitization and collision resolution; preserve both inner function-local names and outer call-site aliases rather than replacing one with the other.
  - Separate naming from materialization: an expression keeps source-level naming metadata when safely inlined, while `keep`, observability, readable/debug policy, sharing, typing, or target legality independently decides whether an HDL object is emitted.

- [ ] **Foundation Increment 154 — Scala 3 compiler-derived binder and naming-scope capture**
  - Replace runtime stack/source-line inspection as the naming authority with Scala 3 compile-time typed-tree capture of binders, owners, expansion positions, and call-site naming scopes; retain runtime inspection only as a diagnostic fallback.
  - Propagate an outer binding such as `pixelResult` into helper construction so a local `widenedSum` carries the structured path `pixelResult / widenedSum` without requiring user annotations or source changes.
  - Cover multiline right-hand sides, backticked identifiers, private/nested helpers, lambdas, separately compiled libraries or JARs, unavailable source files at elaboration time, and deterministic diagnostics when no trustworthy binder exists.
  - Add compile-positive, compile-negative, macro-expansion, separate-compilation, and source-unavailable fixtures proving that naming does not depend on `StackWalker`, filesystem layout, or runtime source parsing.

- [ ] **Foundation Increment 155 — Helper invocation identity, return aliases, and collision semantics**
  - Define deterministic caller-qualified emission such as `pixelResult_widenedSum`, `leftResult_widenedSum`, and `rightResult_widenedSum` for repeated helper invocations while retaining the original local binder as a source alias.
  - When a helper return and outer binding denote the same final value, prefer the outer call-site name such as `pixelResult` for the emitted object and retain `pixelResult_clippedSum` as an alias; emit a separate local object only when materialization policy requires one.
  - Define nested-helper, local-function, recursion-policy, loop, symbolic-generate, unrolling, cloning, parameterized invocation, and hierarchy qualification rules using definition origin and invocation origin as distinct identities.
  - Resolve collisions with semantic caller paths and stable source/call-site digests only as the final qualifier; never use pass traversal order or an opaque counter as semantic identity.

- [ ] **Foundation Increment 156 — Function-local naming metadata in the Scala bridge and Nodal MLIR**
  - Extend the construction snapshot, bridge protocol, and Nodal MLIR with target-neutral equivalents of `nodal.source_name`, `nodal.source_aliases`, `nodal.name_provenance`, `nodal.lexical_scope`, `nodal.definition_loc`, `nodal.invocation_path`, and `nodal.materialization_boundary`.
  - Preserve raw names, aliases, definition and invocation locations, source maps, and structured paths through parser/printer round trips, normalized textual IR, bridge versioning, cache keys, and deterministic fingerprints.
  - Keep aliases on safely inlined expressions, assign distinct invocation identities to clones or unrolled instances without losing their original binders, and defer target keyword escaping/sanitization to the selected backend.
  - Add byte-stability, bridge round-trip, clone/unroll, source-map, and mutation tests that fail when any source-bound value loses its lexical naming metadata.

- [ ] **Foundation Increment 157 — Name preservation, generated namespaces, and verifier closure**
  - Define mandatory behavior for inlining, materialization, common-subexpression elimination, dead-code elimination, cloning/unrolling, retiming/automatic pipelining, dialect conversion, target lowering, and optimization plugins: source-bound names and aliases survive every semantics-preserving transformation.
  - Add a mandatory verifier that rejects a materialized source-bound value whose lexical name path was lost or replaced by an anonymous fallback, while allowing a safely inlined value to remain metadata-only.
  - Reserve deterministic Nodal-owned generated namespaces such as `_net_<operation>_<stable-index>`, `_reg_<role>_<stable-index>`, `_mem_<role>_<stable-index>`, `_inst_<type>_<stable-index>`, and `_gen_<role>_<stable-index>`; prefer operation, sink, protocol, domain, or structural-role names before the generic fallback.
  - Allocate fallback suffixes only after normalized IR ordering so unrelated pass changes, working directories, JVM identity, or source traversal do not renumber accepted output. Prohibit Nodal-owned `_zz*`, `_T*`, `_GEN*`, `expr_<number>`, and `tmp_<number>` identifiers in accepted HDL.
  - Add pass-by-pass mutation tests, declaration-order permutations, repeated-build goldens, and source-bound-versus-genuinely-unnamed inventories proving that `_net_*` is used only when no meaningful source, caller, sink, or role name exists.
"""

INC65_NAMING = r"""  - Treat Foundation Increments 153-157 as mandatory naming prerequisites. Lower structured caller/local paths into deterministic portable-Verilog identifiers and preserve raw binders, aliases, provenance, definition/invocation locations, and materialization reasons in manifests and source maps.
  - Preserve safe expression inlining: source such as `val widenedName = a + b; out := widenedName` may emit `assign out = a + b;` when exact semantics permit, while `widenedName` remains traceable in IR/source maps and no wire is materialized solely to expose the name.
  - When materialization is required, emit caller-prefixed helper-local names such as `pixelResult_widenedSum`; repeated calls must produce semantic paths such as `leftResult_widenedSum` and `rightResult_widenedSum`. A returned value may emit as `pixelResult` while retaining `pixelResult_clippedSum` as an alias.
  - Use `_net_<operation>_<stable-index>` only for genuinely unnamed Nodal-owned combinational objects, with corresponding `_reg_*`, `_mem_*`, `_inst_*`, and `_gen_*` namespaces for other generated objects. Never emit `_zz*`, `_T*`, `_GEN*`, traversal-counter-only, `expr_<number>`, or `tmp_<number>` names for accepted Nodal-owned HDL.
  - Add exact goldens for ordinary/local/nested methods, lambdas, multiline expressions, separately compiled libraries, repeated calls, loops/generate/unrolling, sharing/CSE, backticked or reserved identifiers, collision sanitization, safe-inline/readable/debug materialization profiles, different working directories, and repeated builds. Record the selected name, original binder, aliases, provenance, materialization reason, sanitization, and collision qualification.
"""

INC72_NAMING = r"""  - Treat Foundation Increments 153-157 as mandatory naming prerequisites and preserve the same structured source binder, caller aliases, provenance, and definition/invocation identity used by portable Verilog.
  - Apply function-local naming parity to analog procedural locals, user-defined analog-function locals, intermediate quantities, event expressions, branch/access calculations, mixed-signal bridge/conversion temporaries, and digital logic inside Verilog-AMS. Safely inline only where analog, event, scheduling, and contribution semantics remain exact; otherwise materialize the retained semantic name.
  - Apply target-specific keyword, scope, escaping, and collision rules without losing the original Scala binder. Prefer semantic operation/branch/event/sink names for generated objects and prohibit Nodal-owned `_zz*` or traversal-counter fallbacks.
  - Add Verilog-A/Verilog-AMS parity goldens and manifests covering helper calls, repeated invocations, analog functions, events, contributions, conversion paths, readable/debug materialization, source maps, target reparse, and deterministic cross-backend name correlation.
"""


def replace_once(text: str, old: str, new: str, subject: str) -> str:
    if text.count(old) != 1:
        raise RuntimeError(f"{subject} anchor is not unique")
    return text.replace(old, new, 1)


def main() -> None:
    roadmap = ROADMAP_PATH.read_text(encoding="utf-8")

    if "**Revision:** 1.28" not in roadmap:
        roadmap = replace_once(
            roadmap,
            "**Revision:** 1.27",
            "**Revision:** 1.28",
            "roadmap revision",
        )

    naming_anchor = (
        "- Keep pure combinational expressions in typed DAG form and inline "
        "compiler-generated single-use expressions whenever exact width/sign/four-state "
        "semantics permit. Materialize only for a declared reason and give every required "
        "net/state a deterministic semantic name."
    )
    naming_direction = (
        "- Preserve Scala lexical binders through helper-function calls as structured "
        "caller/local name paths. A materialized helper-local value uses a readable name "
        "such as `pixelResult_widenedSum`; a safely inlined single-use expression retains "
        "its binder and aliases in IR/source maps without forcing an unnecessary HDL wire. "
        "Reserve `_net_*` for genuinely unnamed Nodal-owned combinational values, prefer "
        "semantic operation or sink-derived names, and prohibit `_zz*` and traversal-counter "
        "identities in accepted generated HDL."
    )
    if naming_direction not in roadmap:
        roadmap = replace_once(
            roadmap,
            naming_anchor,
            naming_anchor + "\n" + naming_direction,
            "pure-combinational naming",
        )

    if "**Foundation Increment 153 — Function-local lexical naming and alias contract**" not in roadmap:
        barrier = "\n## Foundation completion barrier\n"
        roadmap = replace_once(
            roadmap,
            barrier,
            "\n" + FOUNDATION_BLOCK.rstrip() + "\n" + barrier,
            "Foundation completion barrier",
        )

    inc65_heading = (
        "- [ ] **Increment 65 — Digital-only classification, Backend.Auto, and portable "
        "Verilog backend**\n"
    )
    if "Treat Foundation Increments 153-157 as mandatory naming prerequisites" not in roadmap:
        roadmap = replace_once(
            roadmap,
            inc65_heading,
            inc65_heading + INC65_NAMING,
            "Increment 65 heading",
        )

    inc72_heading = "- [ ] **Increment 72 — Complete Verilog-AMS backend skeleton**\n"
    if "Apply function-local naming parity to analog procedural locals" not in roadmap:
        roadmap = replace_once(
            roadmap,
            inc72_heading,
            inc72_heading + INC72_NAMING,
            "Increment 72 heading",
        )

    old_barrier = (
        "> **Blocked:** no FPGA Productivity, Digital Verification, or Analog/Mixed-Signal "
        "Verification implementation increment may start until every Foundation increment is "
        "complete, including architecture-only Increments 150-152 recorded in companion plans "
        "and any later Foundation item added before the barrier is released."
    )
    new_barrier = (
        "> **Blocked:** no FPGA Productivity, Digital Verification, or Analog/Mixed-Signal "
        "Verification implementation increment may start until every Foundation increment is "
        "complete, including architecture-only Increments 150-152 recorded in companion plans, "
        "function-local semantic-naming Increments 153-157, and any later Foundation item added "
        "before the barrier is released."
    )
    if new_barrier not in roadmap:
        roadmap = replace_once(
            roadmap,
            old_barrier,
            new_barrier,
            "Foundation barrier text",
        )

    chisel_naming_reference = (
        "- Chisel naming and helper-function prefixes: "
        "<https://www.chisel-lang.org/docs/explanations/naming>"
    )
    if chisel_naming_reference not in roadmap:
        reference_anchor = (
            "- Chisel modules and implicit clock/reset: "
            "<https://www.chisel-lang.org/docs/explanations/modules>"
        )
        roadmap = replace_once(
            roadmap,
            reference_anchor,
            chisel_naming_reference + "\n" + reference_anchor,
            "Chisel reference",
        )

    for number in range(153, 158):
        marker = f"**Foundation Increment {number} —"
        if roadmap.count(marker) != 1:
            raise RuntimeError(f"expected exactly one {marker}")

    for fragment in (
        "pixelResult_widenedSum",
        "leftResult_widenedSum",
        "rightResult_widenedSum",
        "assign out = a + b;",
        "_net_<operation>_<stable-index>",
        "`_zz*`",
        "nodal.source_aliases",
        "Apply function-local naming parity to analog procedural locals",
    ):
        if fragment not in roadmap:
            raise RuntimeError(f"roadmap lacks required naming fragment: {fragment}")

    ROADMAP_PATH.write_text(roadmap, encoding="utf-8")

    gate = json.loads(GATE_PATH.read_text(encoding="utf-8"))
    gate["roadmap_revision"] = "1.28"
    foundation = gate["foundation_track"]
    foundation["latest_foundation_increment"] = 157
    foundation["function_local_semantic_naming_closure_required"] = True
    foundation["foundation_increments_153_through_157_own_naming_closure"] = True
    GATE_PATH.write_text(json.dumps(gate, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
