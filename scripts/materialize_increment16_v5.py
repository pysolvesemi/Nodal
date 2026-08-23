#!/usr/bin/env python3
"""Normalize ScopedValue execution and eliminate private-field warning risks."""

from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str) -> None:
    target = ROOT / path
    content = target.read_text(encoding="utf-8")
    if new in content:
        return
    if content.count(old) != 1:
        raise RuntimeError(f"v5 anchor is not unique in {path}: {old[:120]!r}")
    target.write_text(content.replace(old, new, 1), encoding="utf-8")


def main() -> int:
    subprocess.run(
        ["python3", str(ROOT / "scripts/materialize_increment16_v4.py")],
        cwd=ROOT,
        check=True,
    )
    kernel = "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
    replace(kernel, "import java.util.concurrent.Callable\n\n", "")
    replace(
        kernel,
        """private final class InstanceRecord(
    val ordinal: Int,
    val instance: AnyRef,
    val child: Long,
    val lexicalDomain: Option[ClockDomain]
):
""",
        """private final class InstanceRecord(
    val ordinal: Int,
    val child: Long,
    val lexicalDomain: Option[ClockDomain]
):
""",
    )
    replace(
        kernel,
        """private final class ModuleRecord(
    val handle: Long,
    val module: Module,
    val className: String,
    val parentAtConstruction: Option[Long]
):
""",
        """private final class ModuleRecord(
    val handle: Long,
    val className: String,
    val parentAtConstruction: Option[Long]
):
""",
    )
    replace(
        kernel,
        """    val record = new ModuleRecord(
      handle,
      module,
      moduleName(module),
      moduleStack.lastOption.map(_.handle)
    )
""",
        """    val record = new ModuleRecord(
      handle,
      moduleName(module),
      moduleStack.lastOption.map(_.handle)
    )
""",
    )
    replace(
        kernel,
        """    val record = new InstanceRecord(
      parent.instances.size,
      instance,
      childHandle,
      domainStack.lastOption
    )
""",
        """    val record = new InstanceRecord(
      parent.instances.size,
      childHandle,
      domainStack.lastOption
    )
""",
    )
    replace(
        kernel,
        """          val named = instance.namedBindings.collectFirst:
            case (selected, actual) if domainRef(selected) == requirement.reference => visible(actual)
""",
        """          val named = instance.namedBindings.collectFirst:
            case (selected, actual) if selected eq requirement.domain => visible(actual)
""",
    )
    replace(
        kernel,
        """  private def elaborate(top: => Module, options: EmitOptions): (Emission, ConstructionSnapshot) =
    val session = new ConstructionSession(options)
    ScopedValue.where(Current, session).call(
      new Callable[(Emission, ConstructionSnapshot)]:
        override def call(): (Emission, ConstructionSnapshot) =
          val root = top
          session.finish(root)
    )
""",
        """  private def elaborate(top: => Module, options: EmitOptions): (Emission, ConstructionSnapshot) =
    val session = new ConstructionSession(options)
    var result: Option[(Emission, ConstructionSnapshot)] = None
    ScopedValue.where(Current, session).run: () =>
      val root = top
      result = Some(session.finish(root))
    result.getOrElse(
      throw new IllegalStateException("construction transaction did not publish a result")
    )
""",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
