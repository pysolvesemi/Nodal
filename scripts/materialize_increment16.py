#!/usr/bin/env python3
"""Materialize Increment 16's deterministic construction-kernel implementation."""

from __future__ import annotations

import base64
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAYLOAD_ROOT = Path(__file__).resolve().with_name("increment16_payload")

PAYLOADS = (
    ("core/scala/api/src/nodal/CandidateApi.scala", "00-CandidateApi.zlib.b64"),
    ("core/scala/api/src/nodal/CompilerApi.scala", "01-CompilerApi.zlib.b64"),
    ("core/scala/api/src/nodal/ConstructionKernel.scala", "02-ConstructionKernel.zlib.b64"),
    ("core/scala/api/src/nodal/ConstructionMacros.scala", "03-ConstructionMacros.zlib.b64"),
    ("core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala", "04-CoreSemanticsCandidateApi.zlib.b64"),
    ("core/scala/api/src/nodal/PipelineInterfaceCandidateApi.scala", "05-PipelineInterfaceCandidateApi.zlib.b64"),
    ("core/scala/testkit/test/src/nodal/construction/ConstructionKernelTests.scala", "06-ConstructionKernelTests.zlib.b64"),
    ("docs/architecture/0022-elaboration-construction-kernel.md", "07-architecture.zlib.b64"),
    ("docs/design-gates/NodalConstructionKernel-DG-v0.1.md", "08-design-gate.zlib.b64"),
    ("scripts/check_increment16.py", "09-checker.zlib.b64"),
    ("tests/api/fixtures/increment16/manifest.json", "10-manifest.zlib.b64"),
    ("tests/api/test_increment16.py", "11-test.zlib.b64"),
)

PATCHES = (
    (
        "scripts/check_increment15.py",
        '        if "**Revision:** 1.19" not in roadmap:\n'
        '            problems.append(Problem("NODAL-INC15-059", "roadmap revision is not 1.19"))',
        '        if not any(\n'
        '            revision in roadmap\n'
        '            for revision in ("**Revision:** 1.19", "**Revision:** 1.20")\n'
        '        ):\n'
        '            problems.append(\n'
        '                Problem("NODAL-INC15-059", "roadmap revision is not a supported successor")\n'
        '            )',
    ),
    (
        "scripts/check_increment15.py",
        '    increment16 = [line for line in roadmap.splitlines() if line.startswith("- [ ] **Increment 16 — ")]',
        '    increment16 = [\n'
        '        line\n'
        '        for line in roadmap.splitlines()\n'
        '        if line.startswith(("- [ ] **Increment 16 — ", "- [x] **Increment 16 — "))\n'
        '    ]',
    ),
    (
        "scripts/check_increment15.py",
        'Problem("NODAL-INC15-064", "roadmap does not leave one unchecked Increment 16")',
        'Problem("NODAL-INC15-064", "roadmap does not retain one Increment 16")',
    ),
    (
        "scripts/nodal.py",
        '        _python(root, "check_increment12.py", "--compile-negative"),\n    ]',
        '        _python(root, "check_increment12.py", "--compile-negative"),\n'
        '        _python(root, "check_increment13.py", "--compile-negative"),\n'
        '        _python(root, "check_increment14.py", "--compile-negative"),\n'
        '        _python(root, "check_increment15.py", "--compile-negative"),\n'
        '        _python(root, "check_increment16.py", "--compile"),\n'
        '    ]',
    ),
    (
        "tests/developer/test_developer_commands.py",
        '            "check_increment12.py",\n        ):',
        '            "check_increment12.py",\n'
        '            "check_increment13.py",\n'
        '            "check_increment14.py",\n'
        '            "check_increment15.py",\n'
        '            "check_increment16.py",\n'
        '        ):',
    ),
    (
        "tests/developer/test_developer_commands.py",
        '        self.assertTrue(any("check_increment12.py" in command for command in rendered))\n'
        '        self.assertTrue(any("tests/lint" in command for command in rendered))',
        '        self.assertTrue(any("check_increment12.py" in command for command in rendered))\n'
        '        self.assertTrue(any("check_increment13.py" in command for command in rendered))\n'
        '        self.assertTrue(any("check_increment14.py" in command for command in rendered))\n'
        '        self.assertTrue(any("check_increment15.py" in command for command in rendered))\n'
        '        self.assertTrue(any("check_increment16.py" in command for command in rendered))\n'
        '        self.assertTrue(any("tests/lint" in command for command in rendered))',
    ),
)

APPENDS = (
    (
        ".github/CODEOWNERS",
        "/core/scala/api/src/nodal/ConstructionKernel.scala",
        """/core/scala/api/src/nodal/ConstructionKernel.scala @pysolvesemi
/core/scala/api/src/nodal/ConstructionMacros.scala @pysolvesemi
/core/scala/testkit/test/src/nodal/construction/ @pysolvesemi
/scripts/materialize_increment16.py @pysolvesemi
/scripts/increment16_payload/ @pysolvesemi
/scripts/check_increment16.py @pysolvesemi
/docs/architecture/0022-elaboration-construction-kernel.md @pysolvesemi
/docs/design-gates/NodalConstructionKernel-DG-v0.1.md @pysolvesemi
/tests/api/fixtures/increment16/ @pysolvesemi""",
    ),
    (
        "docs/design-gates/README.md",
        "NodalConstructionKernel-DG-v0.1.md",
        """## Elaboration construction kernel

`NodalConstructionKernel-DG-v0.1.md` approves Increment 16's private,
module-owned, deterministic, and transactional construction implementation
beneath the frozen Nodal public API v0.3.""",
    ),
)


def write_payload(relative: str, payload_name: str) -> None:
    encoded = (PAYLOAD_ROOT / payload_name).read_text(encoding="ascii").strip()
    content = zlib.decompress(base64.b64decode(encoded)).decode("utf-8")
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def replace_once_or_present(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if text.count(old) != 1:
        raise RuntimeError(f"expected one replacement anchor in {path}: {old!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def append_once(path: Path, marker: str, addition: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        return
    path.write_text(text.rstrip() + "\n" + addition.strip() + "\n", encoding="utf-8")


def main() -> int:
    for relative, payload_name in PAYLOADS:
        write_payload(relative, payload_name)
    for relative, old, new in PATCHES:
        replace_once_or_present(ROOT / relative, old, new)
    for relative, marker, addition in APPENDS:
        append_once(ROOT / relative, marker, addition)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
