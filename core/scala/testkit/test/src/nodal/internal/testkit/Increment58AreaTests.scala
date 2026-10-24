package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

final class Increment58AreaDuplicateDriver extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Bits(8))
  val group = new Area:
    val target = wire(Bits(8))
    target := input
  group.target := input

final class Increment58AreaFailure extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  clock:
    try
      val failed = new Area:
        val stored = Reg(false.B)
        stored := true.B
        throw new IllegalArgumentException("authored Area failure")
      val _ = failed
    catch case _: IllegalArgumentException => ()

final class Increment58AreaForeign(previous: Increment58ReusableArea) extends Module:
  val borrowed = previous

final class Increment58AreaHolder extends Module:
  val input = in(Bits(8))
  val group = new Increment58ReusableArea(input)

object Increment58AreaTests extends TestSuite:
  val tests = Tests:
    test("anonymous reusable inherited helper and nested Areas retain exact hardware"):
      val snapshot = ConstructionKernel.inspect(new Increment58AreaSource)
      assert(snapshot.modules.size == 2)
      val root = snapshot.modules.find(_.path == snapshot.root).get
      assert(root.declarations.count(_.kind == "register") == 1)
      assert(snapshot.areas.size == 6)
      assert(!snapshot.areas.exists(_.path.endsWith(".local")))
      assert(snapshot.areas.forall(_.owner == snapshot.root))
      val first = snapshot.areas.find(_.path.endsWith(".first")).get
      val nested = snapshot.areas.find(_.path.endsWith(".first.nested")).get
      assert(nested.parent.contains(first.path))
      assert(first.members.exists(member =>
        member.name == "copied" && member.target.endsWith(".first_copied")
      ))
      assert(first.members.exists(member => member.name == "nested" && member.target == nested.path))
      val second = snapshot.areas.find(_.path.endsWith(".second")).get
      assert(second.members.find(_.name == "copied").get.target.endsWith(".second_copied"))
      assert(second.members.find(_.name == "copied").get.target ==
        second.members.find(_.name == "alias").get.target)
      val anonymous = snapshot.areas.find(_.path.endsWith(".anonymous")).get
      val child = snapshot.modules.find(_.path.endsWith(".anonymous_child")).get
      assert(anonymous.members.exists(member => member.name == "child" && member.target == child.path))
      val state = snapshot.areas.find(_.path.endsWith(".stateArea")).get
      val storage = root.declarations.find(_.kind == "register").get
      assert(state.domain == storage.domain)
      assert(state.declarations.contains(storage.path))
      assert(storage.path.endsWith(".stateArea_stored"))
      assert(snapshot.structuralIfs.size == 1)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.contains("nodal.structural_if"), text.contains("nodal.instance_port"))
    test("external member writes use ordinary writable ownership"):
      val snapshot = ConstructionKernel.inspect(new Increment58AreaExternalWrite)
      assert(snapshot.areas.size == 1)
      assert(snapshot.digitalAssignments.size == 2)
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
      // As for the existing F55 driver source suite, native admission owns overlap rejection.
      val duplicate = ConstructionKernel.inspect(new Increment58AreaDuplicateDriver)
      assert(duplicate.digitalAssignments.size == 2)
      assert(duplicate.digitalAssignments.map(_.target).distinct.size == 1)
      val _ = ScalaToMlirBridge.fromSnapshot(duplicate)
    test("failed Area construction poisons the shared transaction"):
      val failure = scala.util.Try(ConstructionKernel.inspect(new Increment58AreaFailure)).failed.get
      assert(failure.getMessage.contains("NODAL-CONSTRUCTOR-LIFECYCLE-021"))
      assert(ConstructionKernel.inspect(new Increment58AreaExternalWrite).areas.size == 1)
    test("Area cannot be borrowed from a separate transaction"):
      var previous = Option.empty[Increment58ReusableArea]
      ConstructionKernel.capture(new Increment58AreaHolder)(module =>
        previous = Some(module.group)
        Vector.empty
      )
      val failure = scala.util.Try(
        ConstructionKernel.inspect(new Increment58AreaForeign(previous.get))
      ).failed.get
      assert(failure.getMessage.contains("NODAL-AREA-058-OWNER"))
    test("source paths and member identities are repeatable"):
      val first = ConstructionKernel.inspect(new Increment58AreaSource)
      val second = ConstructionKernel.inspect(new Increment58AreaSource)
      assert(first.areas == second.areas, first.names == second.names)
    test("inherited default and named child Areas retain local formal domains"):
      val snapshot = ConstructionKernel.inspect(new Increment58AreaDomainSource)
      assert(snapshot.areas.size == 3)
      snapshot.areas.foreach: area =>
        val owner = snapshot.modules.find(_.path == area.owner).get
        val requirement = owner.domains.find(_.kind == "required").get
        val storage = owner.declarations.find(_.kind == "register").get
        assert(area.domain.contains(requirement.path))
        assert(area.domain == storage.domain)
        assert(!area.domain.contains(snapshot.root + ".clock"))
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
