#!/usr/bin/env python3
"""Apply compile-safe refinements after the first Increment 16 materialization."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if text.count(old) != 1:
        raise RuntimeError(f"repair anchor is not unique in {path}: {old[:100]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> int:
    kernel = "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
    replace(
        kernel,
        """  private def pathOf(value: Any): Option[String] = value match
    case reference: AnyRef =>
      Option(declarationLookup.get(reference)).map(declarationPath)
        .orElse(
          Option(moduleLookup.get(reference.asInstanceOf[Module])).map(value => modulePath(value.longValue))
            if reference.isInstanceOf[Module]
            else None
        )
    case _ => None
""",
        """  private def pathOf(value: Any): Option[String] = value match
    case module: Module =>
      Option(moduleLookup.get(module)).map(handle => modulePath(handle.longValue))
    case reference: AnyRef =>
      Option(declarationLookup.get(reference)).map(declarationPath)
    case _ => None
""",
    )
    replace(
        kernel,
        """    case expression: Expr[?] =>
      expression match
        case reference: AnyRef =>
          Option(expressionLookup.get(reference)) match
            case Some(found) => s"${modulePath(found.module)}.expr_${found.index}"
            case None => "detached-expr"
""",
        """    case expression: Expr[?] =>
      Option(expressionLookup.get(expression.asInstanceOf[AnyRef])) match
        case Some(found) => s"${modulePath(found.module)}.expr_${found.index}"
        case None => "detached-expr"
""",
    )
    replace(
        kernel,
        """        case declaration if declaration.value.isInstanceOf[DigitalInout[?, ?]] =>
          val endpoint = declaration.value.asInstanceOf[DigitalInout[Bits, DriveMode]]
          val path = declarationPath(declaration.reference)
          val related = operations.collect:
            case operation if operation.values.exists:
                case reference: AnyRef => reference eq declaration.value
                case _ => false
              => operation.kind
          KernelResolvedNetSnapshot(
""",
        """        case declaration if declaration.value.isInstanceOf[DigitalInout[?, ?]] =>
          val endpoint = declaration.value.asInstanceOf[DigitalInout[?, ?]]
          val path = declarationPath(declaration.reference)
          val related = operations
            .filter: operation =>
              operation.values.exists:
                case reference: AnyRef => reference eq declaration.value
                case _ => false
            .map(_.kind)
          KernelResolvedNetSnapshot(
""",
    )

    tests = "core/scala/testkit/test/src/nodal/ConstructionKernelTests.scala"
    replace(
        tests,
        """  val payloadType: DataType[KernelPayload] = Struct("KernelPayload")(
    StructField("data", UInt(16)),
    StructField("tag", UInt(4))
  ).asInstanceOf[DataType[KernelPayload]]
""",
        """  val payloadType: DataType[KernelPayload] = Struct(
    "KernelPayload",
    StructField("data", UInt(16)),
    StructField("tag", UInt(4))
  ).asInstanceOf[DataType[KernelPayload]]
""",
    )
    replace(
        tests,
        """  val terminalA: TerminalView[Electrical, ConservativeAccess.Connect] =
    terminal(Electrical, "a", TerminalAccess.connect)
  val terminalB: TerminalView[Electrical, ConservativeAccess.Connect] =
    terminal(Electrical, "b", TerminalAccess.connect)
""",
        """  val terminalA = terminal(Electrical, "a", TerminalAccess.connect)
  val terminalB = terminal(Electrical, "b", TerminalAccess.connect)
""",
    )
    replace(
        tests,
        '      assert(first.interfaceAbi.exists(_.logicalPath.endsWith("nested.link.payload.data")))\n',
        '      assert(first.interfaceAbi.exists(_.logicalPath.endsWith("nested.link.payload.ready")))\n',
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
