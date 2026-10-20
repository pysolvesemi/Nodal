package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

final class HierarchyBindingFailure(mode: String) extends Module:
  val first = ClockDomain.external(
    "first",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val second = ClockDomain.external(
    "second",
    ClockEdge.Falling,
    ResetPolicy.Sync,
    ResetPolarity.ActiveLow,
    20.MHz
  )
  var value = Option.empty[Signal[Bool]]
  first:
    val input = in(Bool)
    value = Some(input)
  val child = instance(new Increment57HierarchyLeaf)
  if mode == "missing" then ()
  else if mode == "duplicate" then child.domain(first).domain(second)
  else if mode == "duplicate-named" then child.domain(_.required, first).domain(_.required, second)
  else child.domain(first)
  if mode == "readonly" then child(_.outputPort.get) := value.get
  else
    child(_.inputPort.get) := value.get
    if mode == "two-drivers" then child(_.inputPort.get) := value.get
  if mode == "raw" then
    val output = out(Bool)
    output := child.module.outputPort.get

final class HierarchyResetlessFailure extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val child = instance(new Increment57HierarchyLeaf).domain(root)
  child(_.inputPort.get) := true.B

object Increment57HierarchySourceTests extends TestSuite:
  def capture(binding: String = "default", nested: Boolean = false): ConstructionSnapshot =
    ConstructionKernel.inspect(new Increment57HierarchySource(binding, nested))
  val tests = Tests:
    test("local requirement identity remains distinct from occurrence binding"):
      val s = capture()
      val child = s.modules.find(_.domains.exists(_.kind == "required")).get
      val requirement = child.domains.head
      assert(requirement.clockProjection.nonEmpty, requirement.resetProjection.nonEmpty)
      assert(
        requirement.edge.isEmpty,
        requirement.resetPolicy.isEmpty,
        requirement.attributes.isEmpty
      )
      val state = child.declarations.find(_.kind == "register").get
      assert(state.domain.contains(requirement.path), state.resolvedDomain == requirement.binding)
      assert(s.modules.flatMap(_.instances).flatMap(_.actualBindings).size == 1)
    test("default named and inherited actual operands"):
      Vector("default", "named", "inherited").foreach: kind =>
        val s = capture(kind)
        val b = s.modules.flatMap(_.instances).flatMap(_.actualBindings).head
        assert(b.origin == kind, b.reset.nonEmpty)
        assert(b.clock.startsWith(s.root + "."), b.actual.startsWith(s.root + "."))
    test("grandchild forwards local formal capabilities"):
      val s = capture(nested = true)
      val bindings = s.modules.flatMap(_.instances).flatMap(_.actualBindings)
      assert(bindings.size == 2)
      val forwarded = bindings.find(b =>
        s.modules.flatMap(_.domains).exists(_.clockProjection.contains(b.clock))
      ).get
      assert(forwarded.reset.nonEmpty)
      assert(s.modules.flatMap(_.domains).exists(d =>
        d.path == forwarded.actual && d.kind == "required"
      ))
    test("actual bridge projection and operand proof"):
      val text = ScalaToMlirBridge.fromSnapshot(capture(nested = true)).text
      assert(text.contains("nodal.requirement_clock"), text.contains("nodal.requirement_reset"))
      assert(text.contains("nodal.instance_domain_binding"), text.contains("nodal.instance_port"))
      assert(text.contains("kind = \"register_next\""), text.contains("nodal.root.module"))
    test("multiple requirement primitive crossings traverse both directions"):
      val s = ConstructionKernel.inspect(new Increment57HierarchyCrossingSource)
      assert(s.modules.flatMap(_.instances).flatMap(_.actualBindings).size == 4)
      assert(s.digitalExpressions.flatMap(_.crossing).size == 2)
      val text = ScalaToMlirBridge.fromSnapshot(s).text
      assert(text.contains("nodal.crossing"))
    test("independent crossing occurrences cannot reconverge"):
      val failure = scala.util.Try(
        ConstructionKernel.inspect(new Increment57HierarchyCrossingSource(true))
      ).failed.get
      assert(failure.getMessage.contains("NODAL-CDC-057-002"))
    test("missing conflicting readonly and multiple-driver boundaries reject"):
      Vector("missing", "duplicate", "duplicate-named", "readonly", "two-drivers", "raw").foreach:
        mode =>
          assert(
            scala.util.Try(ConstructionKernel.inspect(new HierarchyBindingFailure(mode))).isFailure
          )
    test("initialized child cannot bind resetless domain"):
      assert(scala.util.Try(ConstructionKernel.inspect(new HierarchyResetlessFailure)).isFailure)

    test("requirements sharing an actual retain the exact sampled local endpoint"):
      val snapshot = ConstructionKernel.inspect(new Increment57HierarchyExactSource)
      val child = snapshot.modules.find(_.className == "Increment57HierarchyExactSourceLeaf").get
      val crossing = snapshot.digitalExpressions.flatMap(_.crossing).head
      assert(crossing.sourceDomain == child.domains.find(_.name == "source").get.path)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.contains("source_domain = @\"source\""))
