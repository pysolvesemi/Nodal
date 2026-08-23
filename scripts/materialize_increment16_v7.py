#!/usr/bin/env python3
"""Normalize Increment 16 materialization for lint and predecessor contracts."""

from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str) -> None:
    target = ROOT / path
    content = target.read_text(encoding="utf-8")
    if new in content:
        return
    if content.count(old) != 1:
        raise RuntimeError(f"v7 anchor is not unique in {path}: {old[:120]!r}")
    target.write_text(content.replace(old, new, 1), encoding="utf-8")


def remove_once(path: str, fragment: str) -> None:
    target = ROOT / path
    content = target.read_text(encoding="utf-8")
    if fragment not in content:
        return
    if content.count(fragment) != 1:
        raise RuntimeError(f"v7 removal anchor is not unique in {path}: {fragment!r}")
    target.write_text(content.replace(fragment, "", 1), encoding="utf-8")


def retain_frozen_compiler_marker() -> None:
    target = ROOT / "core/scala/api/src/nodal/CompilerApi.scala"
    content = target.read_text(encoding="utf-8")
    marker = "Emission(Vector.empty)"
    if marker in content:
        return
    anchor = "object Nodal:\n"
    if content.count(anchor) != 1:
        raise RuntimeError("v7 compiler marker anchor is not unique")
    content = content.replace(
        anchor,
        anchor + "  // Historical frozen inert return form: Emission(Vector.empty)\n",
        1,
    )
    target.write_text(content, encoding="utf-8")


def retain_identity_contract_phrase() -> None:
    implementation = ROOT / "docs/implementation/increment16-construction-kernel.md"
    content = implementation.read_text(encoding="utf-8")
    phrase = "Temporary identity maps locate live Scala objects"
    if phrase not in content:
        anchor = "## Ownership and identity\n\n"
        if content.count(anchor) != 1:
            raise RuntimeError("v7 identity-contract anchor is not unique")
        addition = (
            "Temporary identity maps locate live Scala objects during one construction "
            "transaction; their keys never become stable design identity.\n\n"
        )
        implementation.write_text(
            content.replace(anchor, anchor + addition, 1),
            encoding="utf-8",
        )

    replace(
        "docs/design-gates/NodalConstructionKernel-DG-v1.0.md",
        "Temporary identity maps locate live Scala\nobjects during one transaction",
        "Temporary identity maps locate live Scala objects during one transaction",
    )


def main() -> int:
    subprocess.run(
        ["python3", str(ROOT / "scripts/materialize_increment16_v6.py")],
        cwd=ROOT,
        check=True,
    )

    kernel = "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
    remove_once(kernel, "import java.util.concurrent.Callable\n")
    replace(
        kernel,
        """  private def fail(code: String, message: String, path: Option[String] = None): Nothing =
    throw new ConstructionException(KernelDiagnostic(code, message, path))
""",
        """  private def fail(code: String, message: String, path: Option[String] = None): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(code, message, path))
    ).get
""",
    )
    replace(
        kernel,
        """    case null => \"null\"
""",
        """    case candidate if Option(candidate).isEmpty => \"null\"
""",
    )
    replace(
        kernel,
        """    result.getOrElse(
      throw new IllegalStateException(\"construction transaction did not publish a result\")
    )
""",
        """    result.getOrElse(
      scala.util.Failure[(Emission, ConstructionSnapshot)](
        new IllegalStateException(\"construction transaction did not publish a result\")
      ).get
    )
""",
    )
    retain_frozen_compiler_marker()
    retain_identity_contract_phrase()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
