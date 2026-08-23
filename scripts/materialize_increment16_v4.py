#!/usr/bin/env python3
"""Finalize Increment 16 tests using only the frozen base phantom types."""

from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

TESTS = r'''package nodal

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

import utest.*

object KernelContracts:
  val payloadType: DataType[Struct] = Struct(
    "KernelPayload",
    StructField("data", UInt(16)),
    StructField("tag", UInt(4))
  )

  val link: InterfaceType[Interface] = Interface[Interface]("KernelLink")(
    InterfaceMember.value("control", UInt(8)),
    InterfaceMember.stream("payload", payloadType)
  )

  val producer: Role[SourceRole] = Role[SourceRole]("producer")(
    RoleAccess.Out("control"),
    RoleAccess.Master("payload")
  )

  val consumer: Role[SinkRole] = Role[SinkRole]("consumer")(
    RoleAccess.In("control"),
    RoleAccess.Slave("payload")
  )

  val nested: InterfaceType[Interface] = Interface[Interface]("NestedKernelLink")(
    InterfaceMember.nested("link", link),
    InterfaceMember.value("enable", Bool)
  )

  val nestedProducer: Role[SourceRole] = Role[SourceRole]("nestedProducer")(
    RoleAccess.Nested("link", "producer"),
    RoleAccess.Out("enable")
  )

  val incomplete: Role[SourceRole] = Role[SourceRole]("incomplete")(
    RoleAccess.Out("control")
  )

final class KernelLeaf extends Module:
  val core: ClockDomain = ClockDomain.required("core")
  val interface: InterfacePort[Interface, SourceRole] =
    interfacePort(KernelContracts.link, KernelContracts.producer, "link", core)
  val nestedInterface: InterfacePort[Interface, SourceRole] =
    interfacePort(KernelContracts.nested, KernelContracts.nestedProducer, "nested", core)
  val memory: Mem[UInt] = Mem(
    UInt(16),
    depth = 64,
    readLatency = 1,
    readUnderWrite = ReadUnderWrite.ReadFirst,
    ordering = MemoryOrdering.Ordered,
    domain = core
  )

  core:
    val state = Reg(0.U(16))
    state := 1.U(16)

final class KernelTop extends Module:
  val root: ClockDomain = ClockDomain.external(
    "root",
    edge = ClockEdge.Rising,
    reset = ResetConfig.asyncAssertSyncDeassert(2),
    resetPolarity = ResetPolarity.ActiveLow,
    frequency = 400.MHz
  )
  val shaped: Signal[Vec[UInt]] = wire(Vec(UInt(8), 2, 3))
  val padOuter: DigitalInout[Bits, DriveMode.PushPull] = digitalInout(
    Bits(1),
    DriveMode.PushPull,
    InoutPlacement.TopLevel,
    ResolutionProfile.FourState,
    "padOuter"
  )
  val padInner: DigitalInout[Bits, DriveMode.PushPull] = digitalInout(
    Bits(1),
    DriveMode.PushPull,
    InoutPlacement.HierarchyPassThrough,
    ResolutionProfile.FourState,
    "padInner"
  )
  val terminalA = terminal(Electrical, "a", TerminalAccess.connect)
  val terminalB = terminal(Electrical, "b", TerminalAccess.connect)

  passThrough(padOuter, padInner)
  terminalA.connectTo(terminalB)

  root:
    val counter = Reg(0.U(8))
    counter := counter + 1.U(8)
    val leaf = instance(new KernelLeaf)
    leaf.domain(root)

final class UnboundKernelRoot extends Module:
  val missing: ClockDomain = ClockDomain.required("missing")

  missing:
    val state = Reg(0.U(1))
    state := 1.U(1)

final class AmbiguousKernelRoot extends Module:
  val fast: ClockDomain = ClockDomain.external(
    "fast",
    edge = ClockEdge.Rising,
    reset = ResetConfig.sync,
    frequency = 800.MHz
  )
  val slow: ClockDomain = ClockDomain.external(
    "slow",
    edge = ClockEdge.Rising,
    reset = ResetConfig.sync,
    frequency = 100.MHz
  )
  val state: Register[UInt] = Reg(0.U(8))

  state := 1.U(8)

final class IncompleteRoleRoot extends Module:
  val root: ClockDomain = ClockDomain.external(
    "root",
    edge = ClockEdge.Rising,
    reset = ResetConfig.sync,
    frequency = 100.MHz
  )
  val interface: InterfacePort[Interface, SourceRole] =
    interfacePort(KernelContracts.link, KernelContracts.incomplete, "broken", root)

object ConstructionKernelTests extends TestSuite:
  val tests: Tests = Tests:
    test("deterministic hierarchy, domains, shapes, interfaces and topology"):
      val first = ConstructionKernel.inspect(new KernelTop)
      val second = ConstructionKernel.inspect(new KernelTop)

      assert(first == second)
      assert(first.root == "KernelTop")
      assert(first.modules.map(_.path) == Vector("KernelTop", "KernelTop.KernelLeaf_0"))
      assert(first.interfaceAbi.size == 9)
      assert(first.interfaceAbi.exists(_.logicalPath.endsWith("link.payload.ready")))
      assert(first.interfaceAbi.exists(_.logicalPath.endsWith("nested.link.payload.ready")))
      assert(first.resolvedNets.map(_.path) == Vector("KernelTop.padInner", "KernelTop.padOuter"))
      assert(first.topology.exists(_.kind == "inout-pass-through"))
      assert(first.topology.exists(_.kind == "terminal-connect"))

      val shaped = first.modules.head.declarations.find(_.name == "wire_0").get
      assert(shaped.dataType.contains("Vec(UInt(8);2x3)"))

      val leaf = first.modules(1)
      assert(leaf.domains.exists(domain => domain.name == "core" && domain.binding.contains("KernelTop.root")))
      assert(leaf.declarations.exists(declaration => declaration.kind == "register" && declaration.domain.contains("KernelTop.root")))
      assert(leaf.declarations.exists(declaration => declaration.kind == "memory" && declaration.domain.contains("KernelTop.root")))

    test("public emit publishes construction classification and logical ABI"):
      val emission = Nodal.emit(new KernelTop)
      assert(emission.files.isEmpty)
      assert(emission.report.designKind == DesignKind.MixedSignal)
      assert(emission.report.selectedBackend == Backend.Auto)
      assert(emission.report.interfaceAbi.nonEmpty)
      assert(emission.report.sourceMap.isEmpty)
      assert(emission.report.schedules.isEmpty)

    test("unbound root requirement is rejected transactionally"):
      val failure = intercept[ConstructionException]:
        ConstructionKernel.inspect(new UnboundKernelRoot)
      assert(failure.diagnostic.code == "NODAL-ROOT-DOMAIN-016")

      val recovered = ConstructionKernel.inspect(new KernelTop)
      assert(recovered.root == "KernelTop")

    test("unqualified multi-domain state is rejected"):
      val failure = intercept[ConstructionException]:
        ConstructionKernel.inspect(new AmbiguousKernelRoot)
      assert(failure.diagnostic.code == "NODAL-MULTI-DOMAIN-016")

    test("exported interface roles must be complete"):
      val failure = intercept[ConstructionException]:
        ConstructionKernel.inspect(new IncompleteRoleRoot)
      assert(failure.diagnostic.code == "NODAL-ROLE-COMPLETE-016")

    test("parallel elaborations do not share mutable construction state"):
      val snapshots = Await.result(
        Future.sequence(Vector.fill(8)(Future(ConstructionKernel.inspect(new KernelTop)))),
        20.seconds
      )
      assert(snapshots.distinct.size == 1)
'''


def main() -> int:
    subprocess.run(
        ["python3", str(ROOT / "scripts/materialize_increment16_v3.py")],
        cwd=ROOT,
        check=True,
    )
    target = ROOT / "core/scala/testkit/test/src/nodal/ConstructionKernelTests.scala"
    target.write_text(TESTS.rstrip() + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
