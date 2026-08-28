#!/usr/bin/env python3

from pathlib import Path


ROADMAP = Path("docs/roadmap/nodal-development-todo.md")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one {label} anchor, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    text = ROADMAP.read_text(encoding="utf-8")

    text = replace_once(
        text,
        "**Revision:** 1.37",
        "**Revision:** 1.38",
        "roadmap revision",
    )

    fixed_anchor = (
        "- Distinguish ordinary Scala elaboration loops, symbolic structural `generate` loops, "
        "and bounded hardware-iteration loops. Dynamic or unbounded iteration must not acquire "
        "hidden latency or an inferred FSM.\n"
    )
    text = replace_once(
        text,
        fixed_anchor,
        fixed_anchor
        + "- Preserve ordinary Scala `for` syntax through typed staged ranges: `genRange(...)` "
        "constructs structural generation and `hwRange(...)` constructs bounded hardware "
        "iteration, while ordinary Scala ranges remain elaboration-only. Never infer loop "
        "staging by inspecting the loop body.\n",
        "fixed loop direction",
    )

    public_api_anchor = (
        "- Keep ordinary Scala `for` for elaboration, reserve `generate(...)` for symbolic "
        "structural replication, and freeze a separate concise bounded hardware-loop operation "
        "plus collection `map`/`reduce` candidates. Reject runtime trip counts and unbounded "
        "`while` in the initial synthesizable contract.\n"
    )
    text = replace_once(
        text,
        public_api_anchor,
        "- Keep ordinary Scala `for` over Scala ranges for elaboration; add typed staged range "
        "candidates `genRange(...)` and `hwRange(...)` so the same Scala `for` syntax explicitly "
        "constructs symbolic structural generation or bounded hardware iteration without "
        "body-based inference. Retain `generate(...)` and `loop(...)` as canonical explicit forms; "
        "reject runtime trip counts and unbounded `while` in the initial synthesizable contract.\n",
        "public loop API direction",
    )

    loop_arch_anchor = (
        "3. a distinct bounded hardware-loop candidate such as `loop(...)` describes repeated "
        "operations inside one combinational or clocked region and may lower deterministically "
        "to a procedural HDL `for` or verified unrolled operations.\n\n"
    )
    loop_arch_addition = (
        "Typed staged ranges provide the same Scala `for` surface without introducing a fourth "
        "loop category:\n\n"
        "```scala\n"
        "for index <- 0 until copies do                     // Scala elaboration\n"
        "for index <- genRange(0, lanes) do                 // structural target generation\n"
        "for index <- hwRange(0, taps, maximum = 64) do     // bounded hardware iteration\n"
        "```\n\n"
        "`genRange` and `hwRange` are frontend wrappers over canonical `generate(...)` and "
        "`loop(...)` semantics. Their staged range type, never inspection of the loop body, "
        "selects the loop category.\n\n"
    )
    text = replace_once(
        text,
        loop_arch_anchor,
        loop_arch_anchor + loop_arch_addition,
        "signed loop architecture",
    )

    increment_159 = """
- [ ] **Foundation Increment 159 — Typed staged Scala `for` ranges for generate and hardware loops**
  - Add public backend-neutral staged range types and concise constructors such as `genRange(lower, upper, step = 1)` and `hwRange(lower, upper, step = 1, maximum = ...)`; their `foreach` support preserves ordinary Scala 3 `for` syntax while selecting an explicit Nodal loop category.
  - Keep `for index <- 0 until count` as Scala elaboration only. Lower `for index <- genRange(...)` exactly to existing structural `nodal.generate` semantics and `for index <- hwRange(...)` exactly to existing bounded `nodal.hardware_loop` semantics. Never inspect the loop body to infer or change staging.
  - Accept elaboration-static and legal symbolic parameter/constant bounds for `genRange`; permit structural declarations, instances, connections, deterministic nested generation, and index-aware hierarchy naming. Accept only finite elaboration-static or symbolic-static bounds with a proven maximum envelope for `hwRange`; retain ordered effects and prohibit structural declarations, runtime trip counts, hidden multi-cycle behavior, data-dependent termination, and unbounded `while`.
  - Make staged-range `for` forms and canonical `generate(...)`/`loop(...)` forms normalize to identical target-neutral IR, diagnostics, source maps, naming/provenance, optimization obligations, and Verilog-family lowering. Treat this as a frontend ergonomic layer, not a new loop kind or backend construct.
  - Add positive and negative compile fixtures for concrete/symbolic bounds, ascending steps, empty/singleton ranges, nested and mixed loop categories, helper methods, separate compilation, generated names, invalid dynamic bounds, missing maximum envelopes, illegal bodies, and deterministic explicit-form-versus-range-form IR/HDL equivalence.
  - Prerequisites: Increments 55 and 58 plus Foundation Increments 153-157. Integrate portable Verilog, open-source equivalence, and Verilog-AMS regression through Increments 65-67 and 72 without changing their target loop semantics.

"""
    text = replace_once(
        text,
        "## Foundation completion barrier\n",
        increment_159 + "## Foundation completion barrier\n",
        "foundation completion barrier",
    )

    barrier_anchor = (
        "> **Blocked:** no FPGA Productivity, Digital Verification, or Analog/Mixed-Signal "
        "Verification implementation increment may start until every Foundation increment is "
        "complete, including architecture-only Increments 150-152 recorded in companion plans, "
        "function-local semantic-naming Increments 153-157, and any later Foundation item added "
        "before the barrier is released.\n"
    )
    text = replace_once(
        text,
        barrier_anchor,
        "> **Blocked:** no FPGA Productivity, Digital Verification, or Analog/Mixed-Signal "
        "Verification implementation increment may start until every Foundation increment is "
        "complete, including architecture-only Increments 150-152 recorded in companion plans, "
        "function-local semantic-naming Increments 153-157, typed staged-loop range Increment 159, "
        "and any later Foundation item added before the barrier is released.\n",
        "foundation barrier inventory",
    )

    ROADMAP.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
