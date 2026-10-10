package nodal.internal.testkit

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*

import utest.*

private trait Increment43ShapeClock:
  this: Module =>
  val root: ClockDomain = ClockDomain.external(
    "root",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.Sync,
    resetPolarity = ResetPolarity.ActiveHigh,
    frequency = 100.MHz
  )

final class Increment43FixedShape extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))

final class Increment43SymbolicShape extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = out(Vec(Real, lanes, 2))

final class Increment43ZeroShape extends Module:
  val samples: Signal[Vec[Real]] = wire(Vec(Real, 0))

final class Increment43UnboundedShape extends Module:
  val lanes: Param[Integer] = param(2.integer)
  val samples: Signal[Vec[Real]] = wire(Vec(Real, lanes))

final class Increment43PossiblyEmptyShape extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 0 to 4)
  val samples: Signal[Vec[Real]] = wire(Vec(Real, lanes))

final class Increment43CompoundShape extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes + 1.integer))
  val selected: Expr[Real] = samples.at(1)

final class Increment43NonpositiveCompoundShape extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = wire(Vec(Real, lanes - 1.integer))

final class Increment43FixedShapeIndex extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val selected: Expr[Real] = samples.at(1, 2.integer)

final class Increment43SymbolicShapeIndex extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = out(Vec(Real, lanes, 2))
  val selected: Expr[Real] = samples.at(0, 1)

final class Increment43NegativeShapeIndex extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2))
  val selected: Expr[Real] = samples.at(-1)

final class Increment43EndShapeIndex extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2))
  val selected: Expr[Real] = samples.at(2)

final class Increment43RankShapeIndex extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val selected: Expr[Real] = samples.at(1)

final class Increment43SymbolicIndex extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 3)
  val samples: Signal[Vec[Real]] = in(Vec(Real, 4))
  val selected: Expr[Real] = samples.at(lanes)

final class Increment43CompoundIndex extends Module, Increment43ShapeClock:
  val lane: Param[Integer] = param(2.integer, range = 1 to 3)
  val samples: Signal[Vec[Real]] = in(Vec(Real, 3))
  val selected: Expr[Real] = samples.at(lane - 1.integer)

final class Increment43UnprovedSymbolicIndex extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = in(Vec(Real, 4))
  val selected: Expr[Real] = samples.at(lanes)

final class Increment43WireShapeIndex extends Module:
  val samples: Signal[Vec[Real]] = wire(Vec(Real, 2))
  val selected: Expr[Real] = samples.at(1)

final class Increment43FixedShapeView extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val transposedShape: Expr[Vec[Real]] = samples.reshape(3, 2)

final class Increment43MismatchedShapeView extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val invalid: Expr[Vec[Real]] = samples.reshape(2, 2)

final class Increment43EmptyShapeView extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val invalid: Expr[Vec[Real]] = samples.reshape()

final class Increment43ZeroShapeView extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val invalid: Expr[Vec[Real]] = samples.reshape(6, 0)

final class Increment43SymbolicShapeView extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, 2))
  val reshaped: Expr[Vec[Real]] = samples.reshape(2, lanes)

final class Increment43SymbolicMismatchShapeView extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, 2))
  val invalid: Expr[Vec[Real]] = samples.reshape(3, lanes)

final class Increment43RepeatedShapeView extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, lanes, 6))
  val reshaped: Expr[Vec[Real]] = samples.reshape(2, lanes, 3, lanes)

final class Increment43DroppedShapeFactor extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, lanes))
  val invalid: Expr[Vec[Real]] = samples.reshape(lanes)

final class Increment43CoincidentShapeFactors extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val rows: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, 2))
  val invalid: Expr[Vec[Real]] = samples.reshape(rows, 2)

final class Increment43OverflowShapeView extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to Int.MaxValue)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, lanes, lanes))
  val invalid: Expr[Vec[Real]] = samples.reshape(lanes, lanes, lanes)

final class Increment43CompoundShapeView extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val extent: Expr[Integer] = lanes + 1.integer
  val samples: Signal[Vec[Real]] = in(Vec(Real, extent, 2))
  val reshaped: Expr[Vec[Real]] = samples.reshape(2, extent)

final class Increment43DistinctCompoundShapeView extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val sourceExtent: Expr[Integer] = lanes + 1.integer
  val samples: Signal[Vec[Real]] = in(Vec(Real, sourceExtent, 2))
  val targetExtent: Expr[Integer] = lanes + 1.integer
  val invalid: Expr[Vec[Real]] = samples.reshape(2, targetExtent)

object Increment43ShapeContractTests extends TestSuite:
  private def failure(top: => Module): ConstructionException =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get.asInstanceOf[ConstructionException]

  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try stream.forEach(delete)
      finally stream.close()
    Files.deleteIfExists(path)

  val tests: Tests = Tests:
    test("fixed and direct symbolic shapes retain deterministic typed declarations"):
      val fixed = ConstructionKernel.inspect(new Increment43FixedShape)
      val symbolic = ConstructionKernel.inspect(new Increment43SymbolicShape)
      val compound = ConstructionKernel.inspect(new Increment43CompoundShape)
      assert(fixed.modules.head.declarations.find(_.name == "samples").flatMap(_.dataType)
        .contains("Vec(Real;2x3)"))
      assert(symbolic.modules.head.declarations.find(_.name == "samples").flatMap(_.dataType)
        .contains("Vec(Real;lanesx2)"))
      val compoundType = compound.modules.head.declarations
        .find(_.name == "samples").flatMap(_.dataType).get
      assert(compoundType.startsWith("Vec(Real;Increment43CompoundShape."))
      val dimensionPath = compoundType.stripPrefix("Vec(Real;").stripSuffix(")")
      assert(compound.parameterExpressions.exists(_.path == dimensionPath))
      val parameter = symbolic.modules.head.declarations.find(_.name == "lanes").get
      val attributes = parameter.attributes.toMap
      assert(attributes.get("classification").contains("structural"))
      assert(attributes.get("structural_effects").contains("rank,shape"))

    test("bridge emits canonical shaped port types and parameter shape envelopes"):
      val fixed = ScalaToMlirBridge.lower(new Increment43FixedShape)
      val symbolic = ScalaToMlirBridge.lower(new Increment43SymbolicShape)
      val compound = ScalaToMlirBridge.lower(new Increment43CompoundShape)
      assert(fixed == ScalaToMlirBridge.lower(new Increment43FixedShape))
      assert(fixed.text.contains("!nodal.shaped<\"2,3\", f64>"))
      assert(symbolic.text.contains("!nodal.shaped<\"lanes,2\", f64>"))
      assert(symbolic.text.contains("effects = [\"rank\", \"shape\"]"))
      assert(compound.text.contains("!nodal.shaped<\"Increment43CompoundShape."))
      assert(compound.text.contains("\"nodal.const_expr\""))
      assert(compound.text.contains("operator_name = \"add\""))

    test(
      "zero unbounded possibly-empty and nonpositive compound dimensions fail before publication"
    ):
      Vector(
        failure(new Increment43ZeroShape),
        failure(new Increment43UnboundedShape),
        failure(new Increment43PossiblyEmptyShape),
        failure(new Increment43NonpositiveCompoundShape)
      ).foreach(error => assert(error.diagnostic.code == "NODAL-SHAPE-043-001"))

    test("forged snapshot dimensions fail at the bridge"):
      val snapshot = ConstructionKernel.inspect(new Increment43SymbolicShape)
      val forged = snapshot.copy(modules = snapshot.modules.map: module =>
        module.copy(declarations = module.declarations.map: declaration =>
          if declaration.name == "samples" then
            declaration.copy(dataType = Some("Vec(Real;missingx2)"))
          else declaration))
      val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged))
        .failed.get.asInstanceOf[BridgeException]
      assert(error.diagnostic.code == "NODAL-BRIDGE-018")

    test("configured native accepts public fixed and symbolic shaped declarations"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = Files.createTempDirectory("nodal-increment43-shape-")
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            Vector(
              ScalaToMlirBridge.lower(new Increment43FixedShape),
              ScalaToMlirBridge.lower(new Increment43SymbolicShape),
              ScalaToMlirBridge.lower(new Increment43CompoundShape)
            ).foreach: document =>
              NativeCompilerClient.run(document, request) match
                case success: NativeCompilerSuccess =>
                  assert(success.normalizedMlir.contains("!nodal.shaped"))
                case failure: NativeCompilerFailure =>
                  scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")
          finally delete(directory)

    test("public static indexing retains input identity literal order and semantic result"):
      val fixed = ConstructionKernel.inspect(new Increment43FixedShapeIndex)
      val symbolic = ConstructionKernel.inspect(new Increment43SymbolicShapeIndex)
      val legacyWire = ConstructionKernel.inspect(new Increment43WireShapeIndex)
      assert(fixed.shapeIndices.size == 1)
      assert(fixed.shapeIndices.head.input.endsWith(".samples"))
      assert(fixed.shapeIndices.head.indices == Vector("1", "2"))
      assert(fixed.shapeIndices.head.path != fixed.shapeIndices.head.input)
      assert(fixed.sourceMap.exists(_.semanticPath == fixed.shapeIndices.head.path))
      assert(symbolic.shapeIndices.head.indices == Vector("0", "1"))
      assert(legacyWire.shapeIndices.isEmpty)

    test("bridge transports public static indexing through typed port SSA"):
      val fixed = ScalaToMlirBridge.lower(new Increment43FixedShapeIndex)
      val symbolic = ScalaToMlirBridge.lower(new Increment43SymbolicShapeIndex)
      val direct = ScalaToMlirBridge.lower(new Increment43SymbolicIndex)
      val compound = ScalaToMlirBridge.lower(new Increment43CompoundIndex)
      assert(fixed == ScalaToMlirBridge.lower(new Increment43FixedShapeIndex))
      assert(fixed.text.contains("\"nodal.port_value\""))
      assert(fixed.text.contains("\"nodal.shape_index\""))
      assert(fixed.text.contains("value = 1 : index"))
      assert(fixed.text.contains("value = 2 : index"))
      assert(symbolic.text.contains("!nodal.shaped<\"lanes,2\", f64>"))
      assert(direct.text.contains("\"nodal.const_parameter_ref\""))
      assert(direct.text.contains("i64) -> f64"))
      assert(compound.text.contains("\"nodal.const_expr\""))
      assert(compound.text.contains("operator_name = \"sub\""))

    test("public port indexing rejects unsafe literals rank and unproved symbolic indices"):
      Vector(
        failure(new Increment43NegativeShapeIndex),
        failure(new Increment43EndShapeIndex),
        failure(new Increment43RankShapeIndex),
        failure(new Increment43UnprovedSymbolicIndex)
      ).foreach(error => assert(error.diagnostic.code == "NODAL-SHAPE-043-002"))

    test("bridge rejects forged static-index ownership rank and bounds"):
      val snapshot = ConstructionKernel.inspect(new Increment43FixedShapeIndex)
      val compound = ConstructionKernel.inspect(new Increment43CompoundIndex)
      val compoundIndex = compound.shapeIndices.head.indices.head
      val forged = Vector(
        snapshot.copy(shapeIndices = snapshot.shapeIndices.map(_.copy(owner = "Missing"))),
        snapshot.copy(shapeIndices = snapshot.shapeIndices.map(_.copy(input = "Missing.samples"))),
        snapshot.copy(
          shapeIndices = snapshot.shapeIndices.map(
            _.copy(path = "Increment43FixedShapeIndex.missing")
          )
        ),
        snapshot.copy(shapeIndices = snapshot.shapeIndices.map(_.copy(indices = Vector("1")))),
        snapshot.copy(shapeIndices = snapshot.shapeIndices.map(_.copy(indices = Vector("2", "0")))),
        compound.copy(
          shapeIndices = compound.shapeIndices.map(_.copy(indices = Vector("Missing.index")))
        ),
        compound.copy(parameterExpressions = compound.parameterExpressions.map: expression =>
          if expression.path == compoundIndex then expression.copy(operation = "analog_add")
          else expression),
        compound.copy(modules = compound.modules.map: module =>
          module.copy(declarations = module.declarations.map: declaration =>
            if declaration.name == "lane" then
              declaration.copy(attributes = declaration.attributes.map:
                case ("classification", _) => "classification" -> "ordinary"
                case attribute => attribute)
            else declaration))
      )
      forged.foreach: candidate =>
        val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(candidate))
          .failed.get.asInstanceOf[BridgeException]
        assert(error.diagnostic.code == "NODAL-BRIDGE-044")

    test("configured native accepts public static shape-index transport"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = Files.createTempDirectory("nodal-increment43-shape-index-")
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            Vector(
              ScalaToMlirBridge.lower(new Increment43FixedShapeIndex),
              ScalaToMlirBridge.lower(new Increment43SymbolicShapeIndex),
              ScalaToMlirBridge.lower(new Increment43SymbolicIndex),
              ScalaToMlirBridge.lower(new Increment43CompoundIndex)
            ).foreach: document =>
              NativeCompilerClient.run(document, request) match
                case success: NativeCompilerSuccess =>
                  assert(success.normalizedMlir.contains("nodal.port_value"))
                  assert(success.normalizedMlir.contains("nodal.shape_index"))
                case failure: NativeCompilerFailure =>
                  scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")
          finally delete(directory)

    test("fixed reshape retains a source-correlated structural view"):
      val snapshot = ConstructionKernel.inspect(new Increment43FixedShapeView)
      assert(snapshot.shapeViews.size == 1)
      assert(snapshot.shapeViews.head.input.endsWith(".samples"))
      assert(snapshot.shapeViews.head.dimensions == Vector("3", "2"))
      assert(snapshot.sourceMap.exists(_.semanticPath == snapshot.shapeViews.head.path))
      val symbolic = ConstructionKernel.inspect(new Increment43SymbolicShapeView)
      assert(symbolic.shapeViews.size == 1)
      assert(symbolic.shapeViews.head.dimensions == Vector("2", "lanes"))
      val symbolicFailure = failure(new Increment43SymbolicMismatchShapeView)
      assert(symbolicFailure.diagnostic.code == "NODAL-SHAPE-043-003")
      Vector(
        failure(new Increment43MismatchedShapeView),
        failure(new Increment43EmptyShapeView),
        failure(new Increment43ZeroShapeView)
      ).foreach(error => assert(error.diagnostic.code == "NODAL-SHAPE-043-003"))

    test("bridge emits and independently validates fixed structural reshape"):
      val document = ScalaToMlirBridge.lower(new Increment43FixedShapeView)
      assert(document == ScalaToMlirBridge.lower(new Increment43FixedShapeView))
      assert(document.text.contains("\"nodal.shape_view\""))
      assert(document.text.contains("!nodal.shaped<\"2,3\", f64>"))
      assert(document.text.contains("!nodal.shaped<\"3,2\", f64>"))
      assert(document.text.contains("materialization = \"view\""))
      assert(document.text.contains("storage = \"structural\""))
      val symbolicDocument = ScalaToMlirBridge.lower(new Increment43SymbolicShapeView)
      assert(symbolicDocument.text.contains("!nodal.shaped<\"lanes,2\", f64>"))
      assert(symbolicDocument.text.contains("!nodal.shaped<\"2,lanes\", f64>"))

      val snapshot = ConstructionKernel.inspect(new Increment43FixedShapeView)
      Vector(
        snapshot.copy(shapeViews = snapshot.shapeViews.map(_.copy(owner = "Missing"))),
        snapshot.copy(shapeViews = snapshot.shapeViews.map(_.copy(input = "Missing.samples"))),
        snapshot.copy(shapeViews = snapshot.shapeViews.map(_.copy(dimensions = Vector("2", "2")))),
        snapshot.copy(shapeViews = snapshot.shapeViews ++ snapshot.shapeViews)
      ).foreach: forged =>
        val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged))
          .failed.get.asInstanceOf[BridgeException]
        assert(error.diagnostic.code == "NODAL-BRIDGE-045")

    test(
      "symbolic reshape preserves repeated factors and rejects coincident defaults and overflow"
    ):
      val snapshot = ConstructionKernel.inspect(new Increment43RepeatedShapeView)
      assert(snapshot.shapeViews.head.dimensions == Vector("2", "lanes", "3", "lanes"))
      assert(ScalaToMlirBridge.fromSnapshot(snapshot) ==
        ScalaToMlirBridge.lower(new Increment43RepeatedShapeView))
      Vector(
        failure(new Increment43DroppedShapeFactor),
        failure(new Increment43CoincidentShapeFactors),
        failure(new Increment43OverflowShapeView)
      ).foreach(error => assert(error.diagnostic.code == "NODAL-SHAPE-043-003"))

    test("compound reshape preserves the canonical static expression root"):
      val snapshot = ConstructionKernel.inspect(new Increment43CompoundShapeView)
      val view = snapshot.shapeViews.head
      val compoundPath = view.dimensions.last
      assert(view.dimensions.head == "2")
      assert(compoundPath.startsWith("Increment43CompoundShapeView."))
      assert(snapshot.parameterExpressions.exists(_.path == compoundPath))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document == ScalaToMlirBridge.lower(new Increment43CompoundShapeView))
      assert(document.text.contains(s"!nodal.shaped<\"$compoundPath,2\", f64>"))
      assert(document.text.contains(s"!nodal.shaped<\"2,$compoundPath\", f64>"))
      assert(document.text.contains("operator_name = \"add\""))
      val distinct = failure(new Increment43DistinctCompoundShapeView)
      assert(distinct.diagnostic.code == "NODAL-SHAPE-043-003")

    test("bridge rejects forged symbolic reshape proofs and worst-case overflow"):
      val snapshot = ConstructionKernel.inspect(new Increment43SymbolicShapeView)
      def parameterAttribute(key: String, value: String): ConstructionSnapshot =
        snapshot.copy(modules = snapshot.modules.map: module =>
          module.copy(declarations = module.declarations.map: declaration =>
            if declaration.name == "lanes" then
              declaration.copy(attributes =
                declaration.attributes.filterNot(_._1 == key) :+ (key -> value)
              )
            else declaration))
      val overflow = parameterAttribute("integer_range_upper", Int.MaxValue.toString)
      val overflowShape = overflow.copy(
        modules = overflow.modules.map(module =>
          module.copy(declarations = module.declarations.map: declaration =>
            if declaration.name == "samples" then
              declaration.copy(dataType = Some("Vec(Real;lanesxlanesxlanes)"))
            else declaration)
        ),
        shapeViews = overflow.shapeViews.map(_.copy(dimensions = Vector("lanes", "lanes", "lanes")))
      )
      Vector(
        snapshot.copy(shapeViews =
          snapshot.shapeViews.map(_.copy(dimensions = Vector("2", " lanes")))
        ),
        snapshot.copy(shapeViews =
          snapshot.shapeViews.map(_.copy(dimensions = Vector("2", "lanes", "lanes")))
        ),
        parameterAttribute("classification", "ordinary"),
        parameterAttribute("structural_effects", "topology"),
        parameterAttribute("integer_range_lower", "0"),
        parameterAttribute("integer_range_upper", "unbounded"),
        overflowShape
      ).foreach: forged =>
        val error = scala.util.Try(
          ScalaToMlirBridge.fromSnapshot(forged)
        ).failed.get.asInstanceOf[BridgeException]
        assert(error.diagnostic.code == "NODAL-BRIDGE-045")

    test("configured native accepts public fixed and symbolic reshape transport"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = Files.createTempDirectory("nodal-increment43-shape-view-")
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            Vector(
              ScalaToMlirBridge.lower(new Increment43FixedShapeView),
              ScalaToMlirBridge.lower(new Increment43SymbolicShapeView),
              ScalaToMlirBridge.lower(new Increment43RepeatedShapeView),
              ScalaToMlirBridge.lower(new Increment43CompoundShapeView)
            ).foreach: document =>
              NativeCompilerClient.run(document, request) match
                case success: NativeCompilerSuccess =>
                  assert(success.normalizedMlir.contains("nodal.shape_view"))
                  assert(success.normalizedMlir.contains("materialization = \"view\""))
                case failure: NativeCompilerFailure =>
                  scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")
          finally delete(directory)
