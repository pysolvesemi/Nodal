#!/usr/bin/env python3
"""Produce the final compile-normalized Increment 16 construction kernel."""

from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str) -> None:
    target = ROOT / path
    content = target.read_text(encoding="utf-8")
    if new in content:
        return
    if content.count(old) != 1:
        raise RuntimeError(f"v6 anchor is not unique in {path}: {old[:120]!r}")
    target.write_text(content.replace(old, new, 1), encoding="utf-8")


def main() -> int:
    subprocess.run(
        ["python3", str(ROOT / "scripts/materialize_increment16_v5.py")],
        cwd=ROOT,
        check=True,
    )

    kernel = "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
    replace(
        kernel,
        "private val Current: ScopedValue[ConstructionSession] = ScopedValue.newInstance()",
        "private val Current: ScopedValue[ConstructionSession] =\n    ScopedValue.newInstance[ConstructionSession]()",
    )
    replace(
        kernel,
        """    ScopedValue.where(Current, session).run: () =>
      val root = top
      result = Some(session.finish(root))
""",
        """    ScopedValue.where(Current, session).run(
      new Runnable:
        override def run(): Unit =
          val root = top
          result = Some(session.finish(root))
    )
""",
    )
    replace(
        kernel,
        "value.getClass.getName.split('.').last.stripSuffix(\"$\")",
        "value.getClass.getName.split(\"\\\\.\").last.stripSuffix(\"$\")",
    )
    replace(
        kernel,
        """    case _: InterfaceMember.ValidChannel[?] | _: InterfaceMember.StreamChannel[?] => access match
        case RoleAccess.Master(_) | RoleAccess.Slave(_) | RoleAccess.Observe(_) => true
        case _ => false
""",
        """    case _: InterfaceMember.ValidChannel[?] => access match
        case RoleAccess.Master(_) | RoleAccess.Slave(_) | RoleAccess.Observe(_) => true
        case _ => false
    case _: InterfaceMember.StreamChannel[?] => access match
        case RoleAccess.Master(_) | RoleAccess.Slave(_) | RoleAccess.Observe(_) => true
        case _ => false
""",
    )

    for path in (
        ".github/workflows/increment-16-v4-final.yml",
        ".github/workflows/increment-16-v5-stable.yml",
    ):
        target = ROOT / path
        if target.exists():
            target.unlink()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
