#!/usr/bin/env python3
"""Apply final compile-normalized Increment 16 materialization."""

from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str) -> None:
    target = ROOT / path
    content = target.read_text(encoding="utf-8")
    if new in content:
        return
    if content.count(old) != 1:
        raise RuntimeError(f"v3 anchor is not unique in {path}: {old[:100]!r}")
    target.write_text(content.replace(old, new, 1), encoding="utf-8")


def main() -> int:
    subprocess.run(
        ["python3", str(ROOT / "scripts/materialize_increment16_v2.py")],
        cwd=ROOT,
        check=True,
    )

    kernel = "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
    replace(
        kernel,
        """  def attachInstance(instance: AnyRef, childModule: Module): Unit =
    val childHandle = moduleHandle(childModule)
""",
        """  def attachInstance(instance: AnyRef, childModule: Module): Unit =
    if !moduleIds.containsKey(childModule) then
      fail(
        "NODAL-HIERARCHY-016",
        "child Module was constructed outside this construction transaction"
      )
    val childHandle = moduleHandle(childModule)
""",
    )
    replace(
        kernel,
        """  def withDomain[A](domain: ClockDomain)(body: => A): A =
    domainRef(domain)
""",
        """  def withDomain[A](domain: ClockDomain)(body: => A): A =
    if !domainIds.containsKey(domain) then
      fail("NODAL-DOMAIN-018", "lexical ClockDomain is outside this transaction")
    domainRef(domain)
""",
    )
    replace(
        kernel,
        """  private def interfaceAbi(resolved: Map[DomainRef, String]): Vector[InterfaceAbiEntry] =
    records.values.toVector.flatMap: module =>
      module.declarations.toVector.flatMap: declaration =>
        declaration.value match
          case port: InterfacePort[?, ?] =>
            endpointAbi(
              port.definition,
              port.role,
              port.name,
              port.domain,
              None,
              declaration,
              resolved
            )
          case array: InterfaceArray[?, ?] =>
            endpointAbi(
              array.definition,
              array.role,
              array.name,
              array.domain,
              Some(array.count),
              declaration,
              resolved
            )
          case _ => Vector.empty
    .sortBy(_.logicalPath)
""",
        """  private def interfaceAbi(resolved: Map[DomainRef, String]): Vector[InterfaceAbiEntry] =
    val entries = records.values.toVector.flatMap: module =>
      module.declarations.toVector.flatMap: declaration =>
        declaration.value match
          case port: InterfacePort[?, ?] =>
            endpointAbi(
              port.definition,
              port.role,
              port.name,
              port.domain,
              None,
              declaration,
              resolved
            )
          case array: InterfaceArray[?, ?] =>
            endpointAbi(
              array.definition,
              array.role,
              array.name,
              array.domain,
              Some(array.count),
              declaration,
              resolved
            )
          case _ => Vector.empty
    entries.sortBy(_.logicalPath)
""",
    )
    replace(
        kernel,
        """  private def resolvedNets(): Vector[KernelResolvedNetSnapshot] =
    records.values.toVector.flatMap: module =>
      module.declarations.collect:
        case declaration if declaration.kind == KernelSignalKind.DigitalInout =>
          val related = operations.iterator
            .filter: operation =>
              operation.values.exists:
                case reference: AnyRef => reference eq declaration.value
                case _ => false
            .map(_.kind)
            .toVector
          KernelResolvedNetSnapshot(
            declarationPath(declaration.reference),
            declaration.dataType.map(renderType(_, module.handle)).getOrElse("Bits"),
            attribute(declaration, "mode", module.handle, "unknown"),
            attribute(declaration, "placement", module.handle, "unknown"),
            attribute(declaration, "profile", module.handle, "unknown"),
            related
          )
    .sortBy(_.path)
""",
        """  private def resolvedNets(): Vector[KernelResolvedNetSnapshot] =
    val nets = records.values.toVector.flatMap: module =>
      module.declarations.collect:
        case declaration if declaration.kind == KernelSignalKind.DigitalInout =>
          val related = operations.iterator
            .filter: operation =>
              operation.values.exists:
                case reference: AnyRef => reference eq declaration.value
                case _ => false
            .map(_.kind)
            .toVector
          KernelResolvedNetSnapshot(
            declarationPath(declaration.reference),
            declaration.dataType.map(renderType(_, module.handle)).getOrElse("Bits"),
            attribute(declaration, "mode", module.handle, "unknown"),
            attribute(declaration, "placement", module.handle, "unknown"),
            attribute(declaration, "profile", module.handle, "unknown"),
            related
          )
    nets.sortBy(_.path)
""",
    )
    replace(
        kernel,
        """  private def topology(): Vector[KernelTopologyEdge] = operations.toVector.flatMap: operation =>
    if Set("node-connect", "terminal-connect", "inout-pass-through").contains(operation.kind) &&
        operation.values.size >= 2
    then
      (pathOf(operation.values(0)), pathOf(operation.values(1))) match
        case (Some(left), Some(right)) => Some(KernelTopologyEdge(operation.kind, left, right))
        case _ => None
    else None
  .sortBy(edge => (edge.kind, edge.left, edge.right))
""",
        """  private def topology(): Vector[KernelTopologyEdge] =
    val edges = operations.toVector.flatMap: operation =>
      if Set("node-connect", "terminal-connect", "inout-pass-through").contains(operation.kind) &&
          operation.values.size >= 2
      then
        (pathOf(operation.values(0)), pathOf(operation.values(1))) match
          case (Some(left), Some(right)) => Some(KernelTopologyEdge(operation.kind, left, right))
          case _ => None
      else None
    edges.sortBy(edge => (edge.kind, edge.left, edge.right))
""",
    )

    replace(
        "core/scala/testkit/test/src/nodal/ConstructionKernelTests.scala",
        "sealed trait KernelPayload extends Struct\n",
        "opaque type KernelPayload <: Struct = Bits\n",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
