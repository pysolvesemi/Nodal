package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object RunSelectedChildControls:
  def main(args: Array[String]): Unit =
    val dir = Path.of(args(0))
    val _ = Files.createDirectories(dir)
    var count = 0
    def positive(name: String, build: () => Module): ConstructionSnapshot =
      val snapshot = ConstructionKernel.inspect(build())
      val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
      val _ = Files.writeString(dir.resolve(name + ".mlir"), text)
      println(s"PASS source/bridge $name")
      count += 1
      snapshot
    val original = positive("nonunit", () => new SelectedChildConservativeNonunit)
    val _ = positive("unused-leaf", () => new SelectedChildConservativeUnusedLeaf)
    val _ = positive("boundary-only", () => new SelectedChildConservativeBoundaryOnly)
    val two = positive("two-views", () => new SelectedChildConservativeTwoViews)
    assert(two.interfaceContracts.count(_.boundary.exists(_.selection.nonEmpty)) == 2)
    val bad: Vector[(String, () => Module, String)] = Vector(
      ("missing-body", () => new SelectedChildConservativeMissingBody, "authentic authored body Interface boundary"),
      ("child-parameter", () => new SelectedChildConservativeChildParameter, "constant scalar connect profile"),
      ("parameter-family", () => new SelectedChildConservativeParameterFamily, "fixed nonempty signed32 family"),
      ("empty", () => new SelectedChildConservativeEmpty, "NODAL-AREA-058-INDEX"),
      ("ordinal", () => new SelectedChildConservativeOrdinal, "NODAL-AREA-058-INDEX"),
      ("overflow", () => new SelectedChildConservativeOverflow, "NODAL-ITERATION"),
      ("foreign-field", () => new SelectedChildConservativeForeignField, "requires its exact declared field witness"),
      ("sense", () => new SelectedChildConservativeSense, "connect capability"),
      ("contribute", () => new SelectedChildConservativeContribute, "connect capability")
    )
    for (name, build, reason) <- bad do
      val result = scala.util.Try(ConstructionKernel.inspect(build()))
      assert(result.isFailure, name)
      val failure = result.failed.get
      println(s"REFUSAL source $name: ${failure.getMessage}")
      assert(failure.isInstanceOf[ConstructionException] && failure.getMessage.contains(reason), name)
      count += 1
    var previous: Option[SelectedChildConservativeNonunit] = None
    val _ = ConstructionKernel.inspect:
      val built = new SelectedChildConservativeNonunit
      previous = Some(built)
      built
    val foreign = scala.util.Try(ConstructionKernel.inspect(new SelectedChildConservativeForeignSession(previous.get.selected)))
    assert(foreign.isFailure && foreign.failed.get.getMessage.contains("conservative member requires an owned Interface"))
    println("PASS cross-session exact endpoint identity rejection")
    count += 1
    val selected = original.interfaceContracts.find(_.boundary.exists(_.selection.nonEmpty)).get
    val repeated = original.topology.filter(edge => edge.left.contains("selected") || edge.right.contains("selected"))
    val selectedDeclarations = original.modules.flatMap(_.declarations).filter(_.conservativeMember.exists(_.endpoint == selected.path)).map(_.path).toSet
    val selectedEdges = original.topology.filter(edge => selectedDeclarations(edge.left) || selectedDeclarations(edge.right))
    assert(repeated.isEmpty && selectedEdges.size == 2 && selectedEdges.map(_.path).distinct.size == 2 && selectedEdges.forall(_.path.nonEmpty))
    assert(selectedDeclarations.size == 1)
    println("PASS two real selected connect occurrences retain one canonical member alias")
    count += 1
    val boundary = selected.boundary.get
    val family = original.generatedRegions.head
    val childPath = original.modules.find(_.path == selected.owner).get.instances.head.childModule
    val body = original.interfaceContracts.find(_.boundary.exists(b => b.selection.isEmpty && b.region.nonEmpty)).get
    def endpointChange(endpoint: KernelInterfaceSnapshot): ConstructionSnapshot =
      original.copy(
        interfaceContracts = original.interfaceContracts.map(e => if e.path == selected.path then endpoint else e),
        modules = original.modules.map(m => m.copy(declarations = m.declarations.map(d =>
          if d.path == selected.path then d.copy(interfaceBoundary = endpoint.boundary) else d)))
      )
    val mutations = Vector(
      "missing-body" -> original.copy(interfaceContracts = original.interfaceContracts.filterNot(_.path == body.path)),
      "body-wrong-region" -> original.copy(interfaceContracts = original.interfaceContracts.map(e => if e.path == body.path then e.copy(boundary = e.boundary.map(_.copy(region = Some("foreign")))) else e)),
      "body-wrong-instance" -> original.copy(interfaceContracts = original.interfaceContracts.map(e => if e.path == body.path then e.copy(boundary = e.boundary.map(_.copy(instance = Some("foreign")))) else e)),
      "foreign-instance" -> endpointChange(selected.copy(boundary = Some(boundary.copy(instance = Some("foreign"))))),
      "foreign-child" -> endpointChange(selected.copy(boundary = Some(boundary.copy(endpoint = "foreign")))),
      "foreign-family" -> endpointChange(selected.copy(boundary = Some(boundary.copy(selection = boundary.selection.map(_.copy(family = "foreign")))))),
      "noncanonical-ordinal" -> endpointChange(selected.copy(boundary = Some(boundary.copy(selection = boundary.selection.map(_.copy(ordinal = "01")))))),
      "wrong-region" -> endpointChange(selected.copy(boundary = Some(boundary.copy(region = Some(family.path))))),
      "unused-leaf-role" -> endpointChange(selected.copy(members = selected.members.map(m => if m.name == "n" then m.copy(access = "sense") else m))),
      "unused-leaf-discipline" -> endpointChange(selected.copy(members = selected.members.map(m => if m.name == "n" then m.copy(analogAttributes = m.analogAttributes.map((k,v) => k -> (if k == "discipline" then "foreign" else v))) else m))),
      "child-formal-inventory" -> original.copy(modules = original.modules.map(m => if m.path == childPath then m.copy(declarations = m.declarations :+ KernelDeclarationSnapshot(childPath + ".forgedParameter", "parameter", "forgedParameter", Some("Integer"), None, Vector("default" -> "1", "classification" -> "ordinary"))) else m)),
      "count-mismatch" -> original.copy(generatedRegions = original.generatedRegions.map(_.copy(maximumTripCount = 4)))
    )
    for (name, forged) <- mutations do
      val result = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged, Backend.VerilogA))
      assert(result.isFailure, name)
      val failure = result.failed.get
      println(s"REFUSAL bridge $name: ${failure.getMessage}")
      val reasons = Map(
        "missing-body" -> "family storage must retain its exact generated owner and induction",
        "body-wrong-region" -> "family storage must retain its exact generated owner and induction",
        "body-wrong-instance" -> "family storage must retain its exact generated owner and induction",
        "foreign-instance" -> "exact immediate child instance",
        "foreign-child" -> "actual original endpoint",
        "foreign-family" -> "child family is absent",
        "noncanonical-ordinal" -> "constant scalar connect profile",
        "wrong-region" -> "constant scalar connect profile",
        "unused-leaf-role" -> "constant scalar connect profile",
        "unused-leaf-discipline" -> "Interface actual payload differs from its exact finalized original type",
        "child-formal-inventory" -> "constant scalar connect profile",
        "count-mismatch" -> "generated maximum count differs from its actual static domain"
      )
      assert(failure.isInstanceOf[BridgeException] && failure.getMessage.contains(reasons(name)), name)
      count += 1
    val _ = positive("recovery", () => new SelectedChildConservativeNonunit)
    println(s"PASS $count source/bridge expectations")
