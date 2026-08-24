#!/usr/bin/env python3
"""Materialize the clean Increment 16 tree and align ABI evidence with v0.3 semantics."""

from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    content = path.read_text(encoding="utf-8")
    if new in content:
        return
    if content.count(old) != 1:
        raise RuntimeError(f"v10 anchor is not unique in {path}: {old!r}")
    path.write_text(content.replace(old, new, 1), encoding="utf-8")


def main() -> int:
    subprocess.run(
        ["python3", str(ROOT / "scripts/materialize_increment16_v9.py")],
        cwd=ROOT,
        check=True,
    )

    fixture = ROOT / "core/scala/testkit/test/src/nodal/ConstructionKernelTests.scala"
    replace_once(
        fixture,
        "package nodal\n\n",
        "package nodal.internal.testkit\n\nimport nodal.*\n\n",
    )
    replace_once(
        fixture,
        '      assert(first.interfaceAbi.exists(_.logicalPath.endsWith("nested.link.payload.data")))\n',
        '      assert(first.interfaceAbi.exists(_.logicalPath.endsWith("nested.link.payload.payload")))\n',
    )

    gate = ROOT / "docs/design-gates/NodalConstructionKernel-DG-v1.0.md"
    old_gate_header = (
        "**Status:** Approved by the frozen public API v0.3 architecture  \n"
        "**Scope:** construction-kernel implementation  \n"
        "**Public API:** unchanged at 0.3  \n"
        "**Governing gate:** `NodalCoreSemanticsPipelineApi-DG-v0.3.md`\n"
    )
    new_gate_header = (
        "**Status:** Approved\n"
        "**Scope:** public-api\n"
        "**API version:** 0.3\n"
        "**Decision:** Activate the private construction kernel beneath frozen public API v0.3\n"
        "**Approved by:** Repository owner instruction to continue Increment 16 on 2026-08-24\n"
        "**Public API:** unchanged at 0.3\n"
        "**Governing gate:** `NodalCoreSemanticsPipelineApi-DG-v0.3.md`\n"
    )
    replace_once(gate, old_gate_header, new_gate_header)

    implementation = ROOT / "docs/implementation/increment16-construction-kernel.md"
    replace_once(
        implementation,
        """The kernel recursively expands nested logical members and expands `Valid`/`Stream` channels into stable
logical ABI leaves. This report remains backend-neutral and does not choose a Verilog flattening policy
beyond the deterministic placeholder emitted names required by the frozen report shape.
""",
        """The kernel recursively expands nested logical members and expands `Valid`/`Stream` channels into stable
logical ABI leaves. A protocol payload remains one typed ABI leaf: `Struct` fields stay preserved in its
type descriptor and are not flattened during construction. This report remains backend-neutral and does
not choose a Verilog flattening policy beyond the deterministic placeholder emitted names required by
the frozen report shape.
""",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
