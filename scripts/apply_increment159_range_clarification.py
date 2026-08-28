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
        "**Revision:** 1.38",
        "**Revision:** 1.39",
        "roadmap revision",
    )

    text = replace_once(
        text,
        "- Preserve ordinary Scala `for` syntax through typed staged ranges: `genRange(...)` constructs structural generation and `hwRange(...)` constructs bounded hardware iteration, while ordinary Scala ranges remain elaboration-only. Never infer loop staging by inspecting the loop body.\n",
        "- Preserve ordinary Scala `for` syntax through typed staged ranges: `genRange(...)` constructs structural generation and `hwRange(...)` constructs bounded hardware iteration, while ordinary Scala ranges remain elaboration-only. Bounds may be concrete Scala `Int` values or legal target-visible integer parameters/constants. Never infer loop staging from module instances, local binders, `Reg`/`Wire` presence, or any other loop-body content.\n",
        "fixed staged-range direction",
    )

    text = replace_once(
        text,
        "- Keep ordinary Scala `for` over Scala ranges for elaboration; add typed staged range candidates `genRange(...)` and `hwRange(...)` so the same Scala `for` syntax explicitly constructs symbolic structural generation or bounded hardware iteration without body-based inference. Retain `generate(...)` and `loop(...)` as canonical explicit forms; reject runtime trip counts and unbounded `while` in the initial synthesizable contract.\n",
        "- Keep ordinary Scala `for` over Scala ranges for elaboration; add typed staged range candidates `genRange(...)` and `hwRange(...)` so the same Scala `for` syntax explicitly constructs structural generation or bounded hardware iteration without body-based inference. Each staged constructor accepts concrete Scala `Int` bounds and legal symbolic integer parameter/constant bounds. `hwRange` obtains its finite envelope from the concrete bound, the parameter's declared legal range, or an explicit enforced `maximum`. Retain `generate(...)` and `loop(...)` as canonical explicit forms; reject runtime trip counts and unbounded `while` in the initial synthesizable contract.\n",
        "public staged-range direction",
    )

    old_example = """```scala
for index <- 0 until copies do                     // Scala elaboration
for index <- genRange(0, lanes) do                 // structural target generation
for index <- hwRange(0, taps, maximum = 64) do     // bounded hardware iteration
```

`genRange` and `hwRange` are frontend wrappers over canonical `generate(...)` and `loop(...)` semantics. Their staged range type, never inspection of the loop body, selects the loop category.
"""
    new_example = """```scala
for index <- 0 until copies do                     // copies: Scala Int; elaboration
for index <- genRange(0, LANES) do                 // LANES: Int or integer Param/Const
for index <- hwRange(0, TAPS) do                   // TAPS: Int or bounded integer Param/Const
```

`genRange` and `hwRange` are frontend wrappers over canonical `generate(...)` and `loop(...)` semantics. Their staged range type, never inspection of the loop body, selects the loop category. A Scala `Int` becomes a concrete staged bound; a legal target-visible integer parameter or constant remains symbolic through IR and HDL. For `hwRange`, a declared finite parameter range supplies the required envelope, so `hwRange(0, TAPS)` needs no redundant `maximum`; when no finite envelope is otherwise available, an explicit `maximum` is required and enforced as part of the legal parameter contract rather than treated as an optimization hint.

The selected loop kind determines which body effects are legal. `genRange` may create structural declarations, instances, connections, and nested generation. `hwRange` performs repeated operations inside an enclosing combinational or sequential region and rejects module, port, instance, or other structural-object creation. A Scala local `val` is only a binder or alias unless it explicitly constructs hardware; it does not automatically become a local HDL signal or select a combinational/sequential process. Plain Scala ranges remain concretely elaborated even when they instantiate modules and are never semantically reclassified from body patterns. Generated process and block labels derive from semantic source names, caller/local binders, roles, and loop indices; generic `COMB_<id>`/`SEQ_<id>` traversal-counter labels are prohibited except for a deterministic collision suffix as the final fallback.
"""
    text = replace_once(text, old_example, new_example, "staged-range architecture example")

    start = text.find("- [ ] **Foundation Increment 159 — Typed staged Scala `for` ranges for generate and hardware loops**\n")
    end = text.find("## Foundation completion barrier\n", start)
    if start < 0 or end < 0:
        raise SystemExit("could not locate Increment 159 section")

    increment = """- [ ] **Foundation Increment 159 — Typed staged Scala `for` ranges for generate and hardware loops**
  - Add public backend-neutral staged range types and concise constructors such as `genRange(lower, upper, step = 1)` and `hwRange(lower, upper, step = 1, maximum = ...)`. Each bound accepts either a Scala `Int` or a legal target-visible integer parameter/constant; overloads or an internal static-bound abstraction must not require users to collapse symbolic parameters into Scala integers.
  - Keep `for index <- 0 until count` as Scala elaboration only. Lower `for index <- genRange(0, LANES)` exactly to existing structural `nodal.generate` semantics and `for index <- hwRange(0, TAPS)` exactly to existing bounded `nodal.hardware_loop` semantics whether `LANES` and `TAPS` are concrete `Int` values or legal symbolic integer parameters/constants. Never inspect the loop body to infer or change staging.
  - Preserve symbolic `genRange` bounds through hierarchy, target-neutral IR, and emitted HDL. For `hwRange`, derive the finite upper envelope from a concrete bound or the symbolic parameter's declared legal range; require `maximum` only when the bound contract otherwise lacks a finite upper envelope, enforce that maximum as a legality constraint, and reject dynamic hardware values or runtime trip counts.
  - Select loop kind before validating the body. `genRange` may create structural declarations, modules, ports, instances, connections, and nested legal generation. `hwRange` may perform repeated operations inside the enclosing combinational or sequential semantic region but may not create modules, ports, instances, or new structural hardware objects. A Scala local `val` remains a binder or alias unless it explicitly constructs hardware; module instances, local variables, or `Reg`/`Wire` presence never choose the loop category.
  - Keep plain Scala loops concretely elaborated even when they instantiate modules. Do not silently reconstruct parameterization or change hierarchy, process ownership, naming, or source paths from body-pattern inference. Any future static-repetition compression must be a separately selected proof-carrying optimization, not a source-language staging rule.
  - Make staged-range `for` forms and canonical `generate(...)`/`loop(...)` forms normalize to identical target-neutral IR, diagnostics, source maps, naming/provenance, optimization obligations, and Verilog-family lowering. Treat this as a frontend ergonomic layer, not a new loop kind or backend construct.
  - Derive generated combinational/sequential region and block labels from semantic source roles, caller/local binder paths, sinks/state owners, and symbolic indices. Prohibit generic `COMB_<id>`/`SEQ_<id>` or traversal-counter identities; use a deterministic collision suffix only as the final fallback.
  - Add positive and negative compile fixtures for `Int` and symbolic parameter/constant bounds, parameter-range-derived envelopes, explicit enforced maxima, ascending steps, empty/singleton ranges, nested and mixed loop categories, helper methods, separate compilation, generated names, invalid dynamic bounds, missing finite envelopes, structural creation inside `hwRange`, local alias non-materialization, plain-Scala non-inference, deterministic process labels, and explicit-form-versus-range-form IR/HDL equivalence.
  - Prerequisites: Increments 55 and 58 plus Foundation Increments 153-157. Integrate portable Verilog, open-source equivalence, and Verilog-AMS regression through Increments 65-67 and 72 without changing their target loop semantics.

"""
    text = text[:start] + increment + text[end:]

    ROADMAP.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
