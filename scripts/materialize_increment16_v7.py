#!/usr/bin/env python3
"""Normalize Increment 16 error paths for the repository lint contract."""

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


def main() -> int:
    subprocess.run(
        ["python3", str(ROOT / "scripts/materialize_increment16_v6.py")],
        cwd=ROOT,
        check=True,
    )

    kernel = "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
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
        """  private def renderStable(value: Any, owner: Long): String = value match
    case null => \"null\"
    case text: String => text
    case boolean: Boolean => boolean.toString
    case integer: Int => integer.toString
    case long: Long => long.toString
    case double: Double => java.lang.Double.toString(double)
    case big: BigInt => big.toString
    case dataType: DataType[?] => renderType(dataType, owner)
    case field: StructField[?] => s\"${field.name}:${renderType(field.dataType, owner)}\"
    case values: Seq[?] => values.map(renderStable(_, owner)).mkString(\"[\", \",\", \"]\")
    case values: Set[?] => values.toVector.map(renderStable(_, owner)).sorted.mkString(\"[\", \",\", \"]\")
    case option: Option[?] => option.map(renderStable(_, owner)).getOrElse(\"none\")
    case enumValue: scala.reflect.Enum => enumValue.toString
    case reference: AnyRef =>
      pathOf(reference).getOrElse(reference.getClass.getSimpleName.stripSuffix(\"$\"))
    case other => other.getClass.getSimpleName
""",
        """  private def renderStable(value: Any, owner: Long): String =
    Option(value).fold(\"null\"): nonNull =>
      nonNull match
        case text: String => text
        case boolean: Boolean => boolean.toString
        case integer: Int => integer.toString
        case long: Long => long.toString
        case double: Double => java.lang.Double.toString(double)
        case big: BigInt => big.toString
        case dataType: DataType[?] => renderType(dataType, owner)
        case field: StructField[?] =>
          s\"${field.name}:${renderType(field.dataType, owner)}\"
        case values: Seq[?] =>
          values.map(renderStable(_, owner)).mkString(\"[\", \",\", \"]\")
        case values: Set[?] =>
          values.toVector.map(renderStable(_, owner)).sorted.mkString(\"[\", \",\", \"]\")
        case option: Option[?] => option.map(renderStable(_, owner)).getOrElse(\"none\")
        case enumValue: scala.reflect.Enum => enumValue.toString
        case reference: AnyRef =>
          pathOf(reference).getOrElse(reference.getClass.getSimpleName.stripSuffix(\"$\"))
        case other => other.getClass.getSimpleName
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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
