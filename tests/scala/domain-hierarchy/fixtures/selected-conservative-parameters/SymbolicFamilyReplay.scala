package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}

object ReplaySymbolicFamily:
  def main(args: Array[String]): Unit =
    val directory = Path.of(args(0))
    def emit(name: String, snapshot: ConstructionSnapshot): String =
      val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
      assert(text == ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text)
      val _ = Files.writeString(directory.resolve(name + ".mlir"), text)
      text
    val snapshot = ConstructionKernel.inspect(new SelectedSymbolicFamily)
    val owner = snapshot.modules.find(_.className == "SelectedSymbolicFamily").get
    val lanes = owner.declarations.find(_.name == "LANES").get
    val family = snapshot.generatedRegions.find(_.owner == owner.path).get
    assert(family.lower == "0" && family.step == "1" && family.upperExclusive == lanes.path)
    assert(family.maximumTripCount == 5)
    assert(lanes.attributes.toMap.apply("structural_effects").split(",").contains("shape"))
    assert(owner.instances.size == 2)
    assert(owner.instances.map(_.path).distinct.size == 2)
    assert(snapshot.interfaceContracts.count(_.boundary.exists(_.selection.nonEmpty)) == 2)
    val original = emit("symbolic-3", snapshot)
    val _ = emit("symbolic-4", ConstructionKernel.inspect(new SelectedSymbolicFamily(initial = 4)))
    val unused = ConstructionKernel.inspect(new SelectedSymbolicFamily(unused = true))
    assert(unused.modules.find(_.className == "SelectedSymbolicFamily").get.declarations
      .find(_.name == "LANES").get.attributes.toMap.apply("structural_effects").split(",").contains("shape"))
    val _ = emit("symbolic-unused", unused)
    val _ = emit("literal-nonunit", ConstructionKernel.inspect(new SelectedTwoLiteralParameters))
    val _ = emit("symbolic-node-array", ConstructionKernel.inspect(new SymbolicNodeArrayPredecessor))

    Vector(
      "minimum-one" -> (() => new SelectedSymbolicFamily(minimum = 1, catchFailure = true)),
      "minimum-zero" -> (() => new SelectedSymbolicFamily(minimum = 0, ordinal = 0, catchFailure = true)),
      "nonzero-lower" -> (() => new SelectedSymbolicFamily(lower = 1, ordinal = 0, catchFailure = true)),
      "symbolic-step" -> (() => new SelectedSymbolicFamily(symbolicStep = true, catchFailure = true))
    ).foreach: (name, construct) =>
      val rejected = ConstructionKernel.inspect(construct())
      val parameter = rejected.modules.find(_.className == "SelectedSymbolicFamily").get
        .declarations.find(_.name == "LANES").get
      assert(!parameter.attributes.toMap.get("structural_effects").exists(_.split(",").contains("shape")), name)
      assert(!rejected.interfaceContracts.exists(_.boundary.exists(_.selection.nonEmpty)), name)
      println("PASS source refusal without shape-effect leak: " + name)

    def declaration(change: KernelDeclarationSnapshot => KernelDeclarationSnapshot): ConstructionSnapshot =
      snapshot.copy(modules = snapshot.modules.map(module =>
        if module.path == owner.path then module.copy(declarations = module.declarations.map(value =>
          if value.path == lanes.path then change(value) else value)) else module))
    def attribute(key: String, value: String): ConstructionSnapshot =
      declaration(parameter => parameter.copy(attributes = parameter.attributes.filterNot(_._1 == key) :+ (key -> value)))
    val controls = Vector(
      "missing-shape" -> attribute("structural_effects", "topology"),
      "ordinary-transport" -> attribute("classification", "ordinary"),
      "minimum-one-transport" -> attribute("integer_range_lower", "1"),
      "forged-count" -> snapshot.copy(generatedRegions = snapshot.generatedRegions.map(value =>
        if value.path == family.path then value.copy(maximumTripCount = 3) else value)),
      "forged-maximum" -> snapshot.copy(generatedRegions = snapshot.generatedRegions.map(value =>
        if value.path == family.path then value.copy(maximum = Some(3)) else value)),
      "foreign-owner" -> snapshot.copy(modules = snapshot.modules.map(module =>
        if module.path == owner.path then module.copy(declarations = module.declarations.filterNot(_.path == lanes.path))
        else if module.path == owner.instances.head.childModule then
          module.copy(declarations = module.declarations :+ lanes)
        else module))
    )
    controls.foreach: (name, forged) =>
      val attempt = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged, Backend.VerilogA))
      assert(attempt.isFailure, name)
      val diagnostic = attempt.failed.get.toString
      val categories = name match
        case "forged-count" | "forged-maximum" => Vector("NODAL-BRIDGE-043")
        case "ordinary-transport" | "foreign-owner" | "minimum-one-transport" =>
          Vector("NODAL-BRIDGE-043", "NODAL-BRIDGE-054-INTERFACE")
        case _ => Vector("NODAL-BRIDGE-054-INTERFACE")
      assert(categories.exists(diagnostic.contains) && !diagnostic.contains("budget") &&
        !diagnostic.contains("excessive"), name + ": " + diagnostic)
      println("PASS bridge refusal: " + name + ": " + attempt.failed.get)
    assert(ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text == original)
    println("PASS symbolic family source, transport and predecessor controls")
