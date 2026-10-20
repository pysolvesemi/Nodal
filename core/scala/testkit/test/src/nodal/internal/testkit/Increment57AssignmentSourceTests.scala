package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class AssignmentReadyDefaultSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  core:
    val payload = in(Bool)
    val valid = in(Bool)
    val _ = Stream(payload, valid)

object Increment57AssignmentSourceTests extends TestSuite:
  val tests = Tests:
    test("authored continuous and register updates retain exact operation source locations"):
      val snapshot = ConstructionKernel.inspect(new HierarchyDefaultResetSource("default"))
      val locations = snapshot.sourceMap.map(value => value.semanticPath -> value.source).toMap
      assert(snapshot.digitalAssignments.exists(_.kind == "register_next"))
      assert(snapshot.digitalAssignments.exists(_.kind == "continuous"))
      snapshot.digitalAssignments.foreach: assignment =>
        val location = locations(assignment.path)
        assert(location.path.endsWith("Increment57HierarchyDefaultResetTests.scala"))
        assert(location.line > 0, location.column > 0)
        val sourcePath = Iterator.iterate(Option(java.nio.file.Path.of(".").toAbsolutePath))(
          _.flatMap(path => Option(path.getParent))
        ).takeWhile(_.nonEmpty).flatten.map(_.resolve(location.path))
          .find(java.nio.file.Files.isRegularFile(_)).get
        val line = java.nio.file.Files.readAllLines(sourcePath).get(location.line - 1)
        assert(line.contains(":="))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      val assignments = text.linesIterator.filter(_.contains("\"nodal.digital_assign\"")).toVector
      assert(assignments.nonEmpty, assignments.forall(!_.contains("loc(unknown)")))
    test("independent crossing mutation preserves its actual output assignment source mapping"):
      val snapshot = ConstructionKernel.inspect(
        new InterfaceObservationSource("output-selector-independent-sources")
      )
      val changed = Increment57InterfaceObservationReplay.sharedSourceMutation(snapshot)
      val assignment = changed.digitalAssignments.head
      val source = changed.sourceMap.find(_.semanticPath == assignment.path).get.source
      assert(source.path.endsWith("Increment57InterfaceObservationSourceTests.scala"))
      val text = ScalaToMlirBridge.fromSnapshot(changed).text
      val line = text.linesIterator.find(_.contains("\"nodal.digital_assign\"")).get
      assert(!line.contains("loc(unknown)"), line.contains(source.line.toString))
    test("synthesized ready defaults use their actual owned target or value source"):
      val snapshot = ConstructionKernel.inspect(new AssignmentReadyDefaultSource)
      val assignment = snapshot.digitalAssignments.head
      val locations = snapshot.sourceMap.map(value => value.semanticPath -> value.source).toMap
      val source = locations(assignment.path)
      assert(source.path.endsWith("Increment57AssignmentSourceTests.scala"))
      assert(locations.get(assignment.target).contains(source) ||
        locations.get(assignment.value).contains(source))

    test("generated interface member projections retain their actual endpoint declaration span"):
      val snapshot = ConstructionKernel.inspect(new InterfaceObservationSource("protocol-shared"))
      val locations = snapshot.sourceMap.map(value => value.semanticPath -> value.source).toMap
      snapshot.interfaceContracts.foreach: endpoint =>
        endpoint.members.foreach: member =>
          assert(locations(member.path) == locations(endpoint.path))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      val accesses = text.linesIterator.filter(_.contains("\"nodal.member_access\"")).toVector
      assert(accesses.nonEmpty, accesses.forall(!_.contains("loc(unknown)")))
