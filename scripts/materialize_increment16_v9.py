#!/usr/bin/env python3
"""Materialize Increment 16 with uTest-compatible exception capture."""

from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    content = path.read_text(encoding="utf-8")
    if new in content:
        return
    if content.count(old) != 1:
        raise RuntimeError(f"v9 anchor is not unique: {old!r}")
    path.write_text(content.replace(old, new, 1), encoding="utf-8")


def main() -> int:
    subprocess.run(
        ["python3", str(ROOT / "scripts/materialize_increment16_v8.py")],
        cwd=ROOT,
        check=True,
    )

    fixture = ROOT / "core/scala/testkit/test/src/nodal/ConstructionKernelTests.scala"
    cases = (
        (
            """      val failure = intercept[ConstructionException]:
        ConstructionKernel.inspect(new UnboundKernelRoot)
""",
            """      val failure =
        scala.util.Try(ConstructionKernel.inspect(new UnboundKernelRoot))
          .failed
          .get
          .asInstanceOf[ConstructionException]
""",
        ),
        (
            """      val failure = intercept[ConstructionException]:
        ConstructionKernel.inspect(new AmbiguousKernelRoot)
""",
            """      val failure =
        scala.util.Try(ConstructionKernel.inspect(new AmbiguousKernelRoot))
          .failed
          .get
          .asInstanceOf[ConstructionException]
""",
        ),
        (
            """      val failure = intercept[ConstructionException]:
        ConstructionKernel.inspect(new IncompleteRoleRoot)
""",
            """      val failure =
        scala.util.Try(ConstructionKernel.inspect(new IncompleteRoleRoot))
          .failed
          .get
          .asInstanceOf[ConstructionException]
""",
        ),
    )
    for old, new in cases:
        replace_once(fixture, old, new)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
