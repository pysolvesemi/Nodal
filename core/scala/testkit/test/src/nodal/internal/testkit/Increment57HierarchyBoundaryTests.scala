package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.ScalaToMlirBridge
import utest.*

final class HierarchyBoundaryStateLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(Bool)
  val output = out(Bool)
  required:
    val state = Reg(false.B)
    state := input
    output := state

final class HierarchyBoundaryOwnership(mode: String) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val leftModule = new HierarchyBoundaryStateLeaf
  val rightModule = new HierarchyBoundaryStateLeaf
  val left = instance(leftModule).domain(root)
  val right = instance(rightModule).domain(root)
  left(_.input) := true.B
  right(_.input) := true.B
  if mode == "requirement" then left.domain(_ => rightModule.required, root)
  if mode == "actual" then left.domain(rightModule.required)
  root:
    val state = Reg(false.B)
    state := (if mode == "raw-sibling" then rightModule.output else left(_.output))

/** The output's annotation is deliberately not evidence about its actual driver. */
final class HierarchyBoundaryOutputLeaf(mode: String) extends Module:
  val source = ClockDomain.required("source")
  val destination = ClockDomain.required("destination")
  var inputPort = Option.empty[Signal[Bool]]
  var outputPort = Option.empty[Signal[Bool]]
  source:
    val input = in(Bool)
    inputPort = Some(input)
  val value = mode match
    case "direct" => inputPort.get
    case "predicate" => select(inputPort.get, true.B, false.B)
    case _ => Cdc.sync(inputPort.get, destination, stages = 2)
  destination:
    val output = out(Bool)
    output := value
    outputPort = Some(output)

final class HierarchyBoundaryOutputSource(mode: String) extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Falling,
    ResetPolicy.Sync,
    ResetPolarity.ActiveLow,
    20.MHz
  )
  var inputValue = Option.empty[Signal[Bool]]
  source:
    val input = in(Bool)
    inputValue = Some(input)
  val child = instance(new HierarchyBoundaryOutputLeaf(mode))
    .domain(_.source, source).domain(_.destination, destination)
  child(_.inputPort.get) := inputValue.get
  destination:
    val state = Reg(false.B)
    state := child(_.outputPort.get)

final class HierarchyBoundaryChain(depth: Int) extends Module:
  val required = ClockDomain.required("required")
  val input = in(Bool)
  val output = out(Bool)
  if depth > 0 then
    val child = instance(new HierarchyBoundaryChain(depth - 1)).domain(required)
    child(_.input) := input
    output := child(_.output)
  else
    required:
      val state = Reg(false.B)
      state := input
      output := state

final class HierarchyBoundaryNested(depth: Int, unsafeReset: Boolean = false) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    if unsafeReset then ResetPolicy.Async else ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveLow,
    100.MHz
  )
  val child = instance(new HierarchyBoundaryChain(depth)).domain(root)
  child(_.input) := true.B
  root:
    val state = Reg(false.B)
    state := child(_.output)

final class HierarchyBoundarySiblings(count: Int) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val children = Vector.tabulate(count)(_ => instance(new HierarchyBoundaryStateLeaf).domain(root))
  children.foreach(child => child(_.input) := true.B)

object Increment57HierarchyBoundaryTests extends TestSuite:
  private def failure(top: => Module): ConstructionException =
    val error = scala.util.Try(ConstructionKernel.inspect(top)).failed.get
    assert(error.isInstanceOf[ConstructionException])
    error.asInstanceOf[ConstructionException]

  val tests = Tests:
    test("supported sibling selectors and explicit output crossing remain causal"):
      val ownership = ConstructionKernel.inspect(new HierarchyBoundaryOwnership("legal"))
      val crossing = ConstructionKernel.inspect(new HierarchyBoundaryOutputSource("legal"))
      assert(ownership.modules.size == 3, crossing.modules.size == 2)
      assert(crossing.digitalExpressions.flatMap(_.crossing).size == 1)
      assert(
        ScalaToMlirBridge.fromSnapshot(crossing).text.contains("nodal.instance_domain_binding")
      )
    test("named requirement selector cannot name a sibling requirement"):
      assert(failure(new HierarchyBoundaryOwnership("requirement")).diagnostic.code ==
        "NODAL-BINDING-019")
    test("actual cannot name a child requirement even when it resolves to the parent domain"):
      assert(failure(new HierarchyBoundaryOwnership("actual")).diagnostic.code ==
        "NODAL-BINDING-020")
    test("raw sibling capture cannot borrow a legal instance boundary"):
      val error = failure(new HierarchyBoundaryOwnership("raw-sibling"))
      assert(error.diagnostic.code == "NODAL-SEQUENTIAL-056-001")
      assert(error.getMessage.contains("another module"))
    test("forged output domain cannot conceal foreign driver or selector provenance"):
      Vector("direct", "predicate").foreach: mode =>
        val error = failure(new HierarchyBoundaryOutputSource(mode))
        assert(error.diagnostic.code == "NODAL-CDC-001")
    test("two-boundary reset forwarding keeps exact receiving release proof"):
      val valid = ConstructionKernel.inspect(new HierarchyBoundaryNested(1))
      assert(valid.modules.size == 3)
      assert(valid.modules.flatMap(_.instances).flatMap(_.actualBindings).forall(_.reset.nonEmpty))
      val error = failure(new HierarchyBoundaryNested(1, unsafeReset = true))
      assert(error.diagnostic.code == "NODAL-RDC-057-001")
      assert(error.getMessage.contains("release"))
    test("representative sibling and nested construction transport remain bounded"):
      Vector(
        ("siblings", () => new HierarchyBoundarySiblings(48), 49),
        ("nested", () => new HierarchyBoundaryNested(8), 10)
      ).foreach: (name, build, expected) =>
        val started = System.nanoTime()
        val snapshot = ConstructionKernel.inspect(build())
        val captured = System.nanoTime()
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        val finished = System.nanoTime()
        assert(snapshot.modules.size == expected)
        assert(snapshot.modules.flatMap(_.instances).flatMap(_.actualBindings).size == expected - 1)
        println(s"hierarchy scale $name modules=$expected snapshot_ms=${(captured - started) /
            1000000L} bridge_ms=${(finished - captured) / 1000000L} mlir_chars=${text.length}")
    test("excessive concrete nesting rejects at the declared construction bound"):
      val error = failure(new HierarchyBoundaryNested(129))
      assert(error.diagnostic.code == "NODAL-HIERARCHY-057-002")
