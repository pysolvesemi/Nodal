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

final class Increment43FixedRowView extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val selected: Expr[Vec[Real]] = samples.row(1)

final class Increment43SymbolicRowView extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = out(Vec(Real, lanes, 2))
  val selected: Expr[Vec[Real]] = samples.row(0)

final class Increment43FixedPlaneView extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3, 4))
  val selected: Expr[Vec[Real]] = samples.plane(1)

final class Increment43ReshapedRowView extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val reshaped: Expr[Vec[Real]] = samples.reshape(3, 2)
  val selected: Expr[Vec[Real]] = reshaped.row(2)

final class Increment43ComposedPlaneRow extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3, 4))
  val plane: Expr[Vec[Real]] = samples.plane(1)
  val row: Expr[Vec[Real]] = plane.row(0)
  val selected: Expr[Real] = row.at(2)

final class Increment43SymbolicComposedPlaneRow extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(3.integer, range = 3 to 5)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, 2, lanes))
  val plane: Expr[Vec[Real]] = samples.plane(1)
  val row: Expr[Vec[Real]] = plane.row(0)
  val selected: Expr[Real] = row.at(lanes - 3.integer)

final class Increment43ComposedRowReshape extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 4))
  val row: Expr[Vec[Real]] = samples.row(1)
  val reshaped: Expr[Vec[Real]] = row.reshape(2, 2)
  val selected: Expr[Real] = reshaped.at(1, 1)

final class Increment43SymbolicComposedRowReshape extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(3.integer, range = 3 to 5)
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, lanes))
  val row: Expr[Vec[Real]] = samples.row(1)
  val reshaped: Expr[Vec[Real]] = row.reshape(1, lanes)
  val selected: Expr[Real] = reshaped.at(0, lanes - 3.integer)

final class Increment43AlternatingShapeViews extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(3.integer, range = 3 to 5)
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, lanes, 4))
  val plane: Expr[Vec[Real]] = samples.plane(1)
  val reshaped: Expr[Vec[Real]] = plane.reshape(lanes, 4)
  val row: Expr[Vec[Real]] = reshaped.row(0)
  val flattened: Expr[Vec[Real]] = row.flatten
  val expanded: Expr[Vec[Real]] = flattened.reshape(2, 2)
  val selected: Expr[Real] = expanded.at(1, 1)

final class Increment43SymbolicComposedRowFlatten extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(3.integer, range = 3 to 5)
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, lanes))
  val row: Expr[Vec[Real]] = samples.row(1)
  val flattened: Expr[Vec[Real]] = row.flatten
  val selected: Expr[Real] = flattened.at(2)

final class Increment43LongAlternatingShapes extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 1, 4))
  val repeated: Expr[Vec[Real]] = (0 until 64).foldLeft(samples: Expr[Vec[Real]]): (value, _) =>
    value.row(0).reshape(1, 4)
  val selected: Expr[Real] = repeated.row(0).at(2)

final class Increment43ScalarShapeCarrier extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val selected: Expr[Real] = samples.at(1, 2)
  val reshaped: Expr[Vec[Real]] = samples.reshape(3, 2)

final class Increment43ForgedShapedExpression(flatten: Boolean) extends Module:
  val forged: Expr[Vec[Real]] =
    val expression = new KernelExpr[Vec[Real]](
      Vector.empty,
      resultType = Some(KernelTypeDescriptor("Vec", Vector(Real, Vector(2, 3)))),
      operation = Some("shape_index")
    )
    ConstructionKernel.expression(expression)
    expression
  val selected: Expr[? <: Data] = if flatten then forged.flatten else forged.row(0)

final class Increment43ForgedViewResult extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val forged: Expr[Vec[Real]] =
    val expression = new KernelExpr[Vec[Real]](
      Vector(samples),
      resultType = Some(KernelTypeDescriptor("Vec", Vector(Integer, Vector(3, 2)))),
      operation = Some("shape_view")
    )
    ConstructionKernel.shapeView(expression, samples, Vector(3, 2))
    expression

final class Increment43ForeignShapeCarrier extends Module, Increment43ShapeClock:
  val child = new Increment43FixedRowView
  val invalid: Expr[Vec[Real]] = child.selected.reshape(1, 3)

final class Increment43InvalidRowRank extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3, 4))
  val invalid: Expr[Vec[Real]] = samples.row(0)

final class Increment43InvalidPlaneRank extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val invalid: Expr[Vec[Real]] = samples.plane(0)

final class Increment43InvalidRowBound extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val invalid: Expr[Vec[Real]] = samples.row(2)

final class Increment43FixedShapeView extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val transposedShape: Expr[Vec[Real]] = samples.reshape(3, 2)

final class Increment43ChainedShapeView extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val first: Expr[Vec[Real]] = samples.reshape(3, 2)
  val second: Expr[Vec[Real]] = first.reshape(1, 6)

final class Increment43IndexedShapeView extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val view: Expr[Vec[Real]] = samples.reshape(3, 2)
  val selected: Expr[Real] = view.at(2, 1)

final class Increment43SymbolicIndexedShapeView extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, 2))
  val view: Expr[Vec[Real]] = samples.reshape(2, lanes)
  val selected: Expr[Real] = view.at(1, 0)

final class Increment43InvalidIndexedShapeView extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val view: Expr[Vec[Real]] = samples.reshape(3, 2)
  val selected: Expr[Real] = view.at(3, 0)

final class Increment43SymbolicChainedShapeFlatten extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, 2))
  val first: Expr[Vec[Real]] = samples.reshape(2, lanes)
  val flattened: Expr[Vec[Real]] = first.flatten

final class Increment43InvalidChainedShapeView extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val first: Expr[Vec[Real]] = samples.reshape(3, 2)
  val invalid: Expr[Vec[Real]] = first.reshape(2, 2)

final class Increment43FixedShapeFlatten extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 2, 3))
  val flattened: Expr[Vec[Real]] = samples.flatten

final class Increment43SymbolicShapeFlatten extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, 2))
  val flattened: Expr[Vec[Real]] = samples.flatten

final class Increment43RepeatedSymbolicShapeFlatten extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, lanes, 6))
  val flattened: Expr[Vec[Real]] = samples.flatten

final class Increment43CompoundShapeFlatten extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val extent: Expr[Integer] = lanes + 1.integer
  val samples: Signal[Vec[Real]] = in(Vec(Real, extent, 2))
  val flattened: Expr[Vec[Real]] = samples.flatten

final class Increment43OverflowShapeFlatten extends Module, Increment43ShapeClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, Int.MaxValue, 2))
  val invalid: Expr[Vec[Real]] = samples.flatten

final class Increment43OverflowSymbolicShapeFlatten extends Module, Increment43ShapeClock:
  val lanes: Param[Integer] = param(2.integer, range = 1 to Int.MaxValue)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, lanes, lanes))
  val invalid: Expr[Vec[Real]] = samples.flatten

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
  private def occurrences(text: String, token: String): Int =
    text.sliding(token.length).count(_ == token)

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

    test("public row and plane views retain exact remaining dimensions"):
      val fixed = ConstructionKernel.inspect(new Increment43FixedRowView)
      val symbolic = ConstructionKernel.inspect(new Increment43SymbolicRowView)
      val plane = ConstructionKernel.inspect(new Increment43FixedPlaneView)
      val chained = ConstructionKernel.inspect(new Increment43ReshapedRowView)

      assert(fixed.shapeIndices.head.indices == Vector("1"))
      assert(fixed.shapeIndices.head.resultDimensions == Vector("3"))
      assert(symbolic.shapeIndices.head.resultDimensions == Vector("2"))
      assert(plane.shapeIndices.head.resultDimensions == Vector("3", "4"))
      assert(chained.shapeIndices.head.input == chained.shapeViews.head.path)
      assert(chained.shapeIndices.head.resultDimensions == Vector("2"))

      Vector(fixed, symbolic, plane, chained).foreach: snapshot =>
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        assert(document == ScalaToMlirBridge.fromSnapshot(snapshot))
        assert(document.text.contains("\"nodal.shape_index\""))
      assert(ScalaToMlirBridge.fromSnapshot(fixed).text.contains(
        "!nodal.shaped<\"3\", f64>"
      ))
      assert(ScalaToMlirBridge.fromSnapshot(plane).text.contains(
        "!nodal.shaped<\"3,4\", f64>"
      ))

    test("composed structural shapes preserve immediate deterministic SSA edges"):
      val fixed = ConstructionKernel.inspect(new Increment43ComposedPlaneRow)
      val symbolic = ConstructionKernel.inspect(new Increment43SymbolicComposedPlaneRow)
      Vector(fixed, symbolic).foreach: snapshot =>
        assert(snapshot.shapeIndices.size == 3)
        assert(snapshot.shapeIndices(1).input == snapshot.shapeIndices.head.path)
        assert(snapshot.shapeIndices(2).input == snapshot.shapeIndices(1).path)
        assert(snapshot.shapeIndices(2).resultDimensions.isEmpty)
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        assert(document == ScalaToMlirBridge.fromSnapshot(snapshot))
        assert(occurrences(document.text, "\"nodal.port_value\"") == 1)
        assert(document.text.contains("%shape_index_1 = \"nodal.shape_index\"(%shape_index_0,"))
        assert(document.text.contains("%shape_index_2 = \"nodal.shape_index\"(%shape_index_1,"))

      Vector(
        ConstructionKernel.inspect(new Increment43ComposedRowReshape),
        ConstructionKernel.inspect(new Increment43SymbolicComposedRowReshape),
        ConstructionKernel.inspect(new Increment43SymbolicComposedRowFlatten)
      ).foreach: snapshot =>
        assert(snapshot.shapeViews.head.input == snapshot.shapeIndices.head.path)
        assert(snapshot.shapeIndices(1).input == snapshot.shapeViews.head.path)
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        assert(document == ScalaToMlirBridge.fromSnapshot(snapshot))
        assert(occurrences(document.text, "\"nodal.port_value\"") == 1)
        assert(document.text.contains("%shape_view_0 = \"nodal.shape_view\"(%shape_index_0)"))
        assert(document.text.contains("%shape_index_1 = \"nodal.shape_index\"(%shape_view_0,"))
        assert(document.text.indexOf("%shape_index_0 =") < document.text.indexOf("%shape_view_0 ="))
        assert(document.text.indexOf("%shape_view_0 =") < document.text.indexOf("%shape_index_1 ="))

      val alternating = ScalaToMlirBridge.lower(new Increment43AlternatingShapeViews)
      assert(alternating == ScalaToMlirBridge.lower(new Increment43AlternatingShapeViews))
      assert(occurrences(alternating.text, "\"nodal.port_value\"") == 1)
      assert(occurrences(alternating.text, "\"nodal.shape_index\"") == 3)
      assert(occurrences(alternating.text, "\"nodal.shape_view\"") == 3)
      assert(alternating.text.contains("%shape_view_1 = \"nodal.shape_view\"(%shape_index_1)"))
      assert(alternating.text.contains("%shape_view_2 = \"nodal.shape_view\"(%shape_view_1)"))
      assert(alternating.text.contains("%shape_index_2 = \"nodal.shape_index\"(%shape_view_2,"))

    test("composed structural shapes traverse long alternating chains without expansion"):
      val snapshot = ConstructionKernel.inspect(new Increment43LongAlternatingShapes)
      assert(snapshot.shapeIndices.size == 66)
      assert(snapshot.shapeViews.size == 64)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document == ScalaToMlirBridge.fromSnapshot(snapshot))
      assert(occurrences(document.text, "\"nodal.port_value\"") == 1)
      assert(occurrences(document.text, "\"nodal.shape_index\"") == 66)
      assert(occurrences(document.text, "\"nodal.shape_view\"") == 64)
      assert(document.text.contains("%shape_index_64 = \"nodal.shape_index\"(%shape_view_63,"))
      assert(document.text.contains("%shape_index_65 = \"nodal.shape_index\"(%shape_index_64,"))

    test("composed structural shapes reject uncaptured foreign and forged typed carriers"):
      assert(failure(new Increment43ForgedShapedExpression(false)).diagnostic.code ==
        "NODAL-SHAPE-043-002")
      Vector(
        failure(new Increment43ForgedShapedExpression(true)),
        failure(new Increment43ForgedViewResult),
        failure(new Increment43ForeignShapeCarrier)
      ).foreach(error => assert(error.diagnostic.code == "NODAL-SHAPE-043-003"))

    test("composed structural shapes reject malformed dependency snapshots"):
      val indexed = ConstructionKernel.inspect(new Increment43ComposedPlaneRow)
      val mixed = ConstructionKernel.inspect(new Increment43ComposedRowReshape)
      val scalar = ConstructionKernel.inspect(new Increment43ScalarShapeCarrier)
      val indexFailures = Vector(
        indexed.copy(shapeIndices = indexed.shapeIndices.tail),
        indexed.copy(shapeIndices = indexed.shapeIndices.reverse),
        indexed.copy(shapeIndices =
          indexed.shapeIndices.updated(
            1,
            indexed.shapeIndices(1).copy(input = "Foreign.row")
          )
        ),
        indexed.copy(shapeIndices =
          indexed.shapeIndices.updated(
            0,
            indexed.shapeIndices.head.copy(input = indexed.shapeIndices(1).path)
          )
        ),
        indexed.copy(shapeIndices =
          indexed.shapeIndices.updated(
            0,
            indexed.shapeIndices.head.copy(resultDimensions = Vector("4", "3"))
          )
        ),
        indexed.copy(shapeIndices =
          indexed.shapeIndices.updated(
            1,
            indexed.shapeIndices(1).copy(resultDimensions = Vector.empty)
          )
        ),
        indexed.copy(shapeIndices =
          indexed.shapeIndices.updated(
            2,
            indexed.shapeIndices(2).copy(input = indexed.shapeIndices(2).path)
          )
        )
      )
      indexFailures.foreach: forged =>
        val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged))
          .failed.get.asInstanceOf[BridgeException]
        assert(error.diagnostic.code == "NODAL-BRIDGE-044")
      val viewFailures = Vector(
        mixed.copy(shapeIndices = mixed.shapeIndices.tail),
        mixed.copy(shapeViews = mixed.shapeViews.map(_.copy(input = "Foreign.row"))),
        mixed.copy(shapeIndices =
          mixed.shapeIndices.updated(
            0,
            mixed.shapeIndices.head.copy(input = mixed.shapeViews.head.path)
          )
        ),
        mixed.copy(shapeViews = mixed.shapeViews.map(_.copy(dimensions = Vector("3", "2")))),
        mixed.copy(shapeViews = mixed.shapeViews.map(_.copy(path = mixed.shapeIndices.head.path))),
        scalar.copy(shapeViews =
          scalar.shapeViews.map(_.copy(input = scalar.shapeIndices.head.path))
        )
      )
      viewFailures.zipWithIndex.foreach: (forged, ordinal) =>
        val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged))
          .failed.get.asInstanceOf[BridgeException]
        val expectedCode = if ordinal == 4 then "NODAL-BRIDGE-044" else "NODAL-BRIDGE-045"
        assert(error.diagnostic.code == expectedCode)

    test("configured native accepts composed structural shape edges"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = Files.createTempDirectory("nodal-increment43-shape-composition-")
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            Vector(
              ScalaToMlirBridge.lower(new Increment43ComposedPlaneRow),
              ScalaToMlirBridge.lower(new Increment43SymbolicComposedPlaneRow),
              ScalaToMlirBridge.lower(new Increment43ComposedRowReshape),
              ScalaToMlirBridge.lower(new Increment43SymbolicComposedRowReshape),
              ScalaToMlirBridge.lower(new Increment43SymbolicComposedRowFlatten),
              ScalaToMlirBridge.lower(new Increment43AlternatingShapeViews),
              ScalaToMlirBridge.lower(new Increment43LongAlternatingShapes)
            ).foreach: document =>
              NativeCompilerClient.run(document, request) match
                case success: NativeCompilerSuccess =>
                  assert(success.normalizedMlir.contains("nodal.shape_index"))
                  assert(occurrences(success.normalizedMlir, "\"nodal.port_value\"") == 1)
                case failure: NativeCompilerFailure =>
                  scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")
          finally delete(directory)

    test("public row and plane views reject wrong rank bounds and forged result shape"):
      Vector(
        failure(new Increment43InvalidRowRank),
        failure(new Increment43InvalidPlaneRank),
        failure(new Increment43InvalidRowBound)
      ).foreach(error => assert(error.diagnostic.code == "NODAL-SHAPE-043-002"))

      val snapshot = ConstructionKernel.inspect(new Increment43FixedRowView)
      Vector(
        snapshot.copy(shapeIndices =
          snapshot.shapeIndices.map(
            _.copy(resultDimensions = Vector.empty)
          )
        ),
        snapshot.copy(shapeIndices =
          snapshot.shapeIndices.map(
            _.copy(resultDimensions = Vector("2"))
          )
        ),
        snapshot.copy(shapeIndices =
          snapshot.shapeIndices.map(
            _.copy(indices = Vector("1", "0"), resultDimensions = Vector("3"))
          )
        )
      ).foreach: forged =>
        val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged))
          .failed.get.asInstanceOf[BridgeException]
        assert(error.diagnostic.code == "NODAL-BRIDGE-044")

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
              ScalaToMlirBridge.lower(new Increment43CompoundIndex),
              ScalaToMlirBridge.lower(new Increment43FixedRowView),
              ScalaToMlirBridge.lower(new Increment43SymbolicRowView),
              ScalaToMlirBridge.lower(new Increment43FixedPlaneView),
              ScalaToMlirBridge.lower(new Increment43ReshapedRowView)
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

    test("chained reshape and flatten retain structural view ownership"):
      val fixed = ConstructionKernel.inspect(new Increment43ChainedShapeView)
      assert(fixed.shapeViews.size == 2)
      assert(fixed.shapeViews.head.input.endsWith(".samples"))
      assert(fixed.shapeViews(1).input == fixed.shapeViews.head.path)
      assert(fixed.shapeViews(1).dimensions == Vector("1", "6"))
      assert(failure(new Increment43InvalidChainedShapeView).diagnostic.code ==
        "NODAL-SHAPE-043-003")

      val symbolic = ConstructionKernel.inspect(new Increment43SymbolicChainedShapeFlatten)
      assert(symbolic.shapeViews.size == 2)
      assert(symbolic.shapeViews(1).input == symbolic.shapeViews.head.path)
      assert(symbolic.shapeViews(1).dimensions.size == 1)
      assert(symbolic.parameterExpressions.exists(
        _.path == symbolic.shapeViews(1).dimensions.head
      ))

      Vector(fixed, symbolic).foreach: snapshot =>
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        assert(document == ScalaToMlirBridge.fromSnapshot(snapshot))
        assert(occurrences(document.text, "\"nodal.shape_view\"") == 2)
        assert(occurrences(document.text, "\"nodal.port_value\"") == 1)

      val reordered = fixed.copy(shapeViews = fixed.shapeViews.reverse)
      val reorderedError = scala.util.Try(ScalaToMlirBridge.fromSnapshot(reordered))
        .failed.get.asInstanceOf[BridgeException]
      assert(reorderedError.diagnostic.code == "NODAL-BRIDGE-045")

    test("indexing a structural view retains its immediate SSA predecessor"):
      val fixed = ConstructionKernel.inspect(new Increment43IndexedShapeView)
      assert(fixed.shapeViews.size == 1)
      assert(fixed.shapeIndices.size == 1)
      assert(fixed.shapeIndices.head.input == fixed.shapeViews.head.path)
      assert(fixed.shapeIndices.head.indices == Vector("2", "1"))
      assert(failure(new Increment43InvalidIndexedShapeView).diagnostic.code ==
        "NODAL-SHAPE-043-002")

      val symbolic = ConstructionKernel.inspect(new Increment43SymbolicIndexedShapeView)
      assert(symbolic.shapeIndices.head.input == symbolic.shapeViews.head.path)
      assert(symbolic.shapeIndices.head.indices == Vector("1", "0"))
      assert(symbolic.shapeViews.head.dimensions == Vector("2", "lanes"))

      Vector(fixed, symbolic).foreach: snapshot =>
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        assert(document == ScalaToMlirBridge.fromSnapshot(snapshot))
        assert(occurrences(document.text, "\"nodal.port_value\"") == 1)
        assert(occurrences(document.text, "\"nodal.shape_view\"") == 1)
        assert(occurrences(document.text, "\"nodal.shape_index\"") == 1)
        assert(document.text.contains("\"nodal.shape_index\"(%shape_view_0"))

      Vector(
        fixed.copy(shapeViews = Vector.empty),
        fixed.copy(shapeIndices = fixed.shapeIndices.map(_.copy(input = "Missing.view")))
      ).foreach: mutated =>
        val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(mutated))
          .failed.get.asInstanceOf[BridgeException]
        assert(error.diagnostic.code == "NODAL-BRIDGE-044")

    test("fixed and symbolic flatten reuse the structural shape-view proof"):
      val snapshot = ConstructionKernel.inspect(new Increment43FixedShapeFlatten)
      assert(snapshot.shapeViews.size == 1)
      assert(snapshot.shapeViews.head.input.endsWith(".samples"))
      assert(snapshot.shapeViews.head.dimensions == Vector("6"))
      assert(snapshot.sourceMap.exists(_.semanticPath == snapshot.shapeViews.head.path))
      assert(failure(new Increment43OverflowShapeFlatten).diagnostic.code == "NODAL-SHAPE-043-003")
      assert(
        failure(new Increment43OverflowSymbolicShapeFlatten).diagnostic.code ==
          "NODAL-SHAPE-043-003"
      )

      val document = ScalaToMlirBridge.lower(new Increment43FixedShapeFlatten)
      assert(document == ScalaToMlirBridge.lower(new Increment43FixedShapeFlatten))
      assert(document.text.contains("!nodal.shaped<\"2,3\", f64>"))
      assert(document.text.contains("!nodal.shaped<\"6\", f64>"))
      assert(document.text.contains("materialization = \"view\""))

      Vector(
        ConstructionKernel.inspect(new Increment43SymbolicShapeFlatten),
        ConstructionKernel.inspect(new Increment43RepeatedSymbolicShapeFlatten),
        ConstructionKernel.inspect(new Increment43CompoundShapeFlatten)
      ).foreach: symbolic =>
        assert(symbolic.shapeViews.size == 1)
        val dimension = symbolic.shapeViews.head.dimensions
        assert(dimension.size == 1)
        val product = symbolic.parameterExpressions.find(_.path == dimension.head).get
        assert(product.operation == "analog_mul")
        assert(product.operands.size == 2)
        assert(symbolic.sourceMap.exists(_.semanticPath == symbolic.shapeViews.head.path))

      val symbolicSnapshot = ConstructionKernel.inspect(new Increment43SymbolicShapeFlatten)
      val symbolicRoot = symbolicSnapshot.shapeViews.head.dimensions.head
      val symbolicDocument = ScalaToMlirBridge.fromSnapshot(symbolicSnapshot)
      assert(symbolicDocument == ScalaToMlirBridge.lower(new Increment43SymbolicShapeFlatten))
      assert(symbolicDocument.text.contains("!nodal.shaped<\"lanes,2\", f64>"))
      assert(symbolicDocument.text.contains(s"!nodal.shaped<\"$symbolicRoot\", f64>"))
      assert(symbolicDocument.text.contains("operator_name = \"mul\""))

      val forged = symbolicSnapshot.copy(
        parameterExpressions = symbolicSnapshot.parameterExpressions.map: expression =>
          if expression.path == symbolicRoot then expression.copy(operation = "analog_add")
          else expression
      )
      val forgedError = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged))
        .failed.get.asInstanceOf[BridgeException]
      assert(forgedError.diagnostic.code == "NODAL-BRIDGE-045")

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
      assert(compoundPath.contains("x"))
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
              ScalaToMlirBridge.lower(new Increment43FixedShapeFlatten),
              ScalaToMlirBridge.lower(new Increment43SymbolicShapeFlatten),
              ScalaToMlirBridge.lower(new Increment43RepeatedSymbolicShapeFlatten),
              ScalaToMlirBridge.lower(new Increment43CompoundShapeFlatten),
              ScalaToMlirBridge.lower(new Increment43SymbolicShapeView),
              ScalaToMlirBridge.lower(new Increment43RepeatedShapeView),
              ScalaToMlirBridge.lower(new Increment43CompoundShapeView),
              ScalaToMlirBridge.lower(new Increment43ChainedShapeView),
              ScalaToMlirBridge.lower(new Increment43SymbolicChainedShapeFlatten),
              ScalaToMlirBridge.lower(new Increment43IndexedShapeView),
              ScalaToMlirBridge.lower(new Increment43SymbolicIndexedShapeView)
            ).foreach: document =>
              NativeCompilerClient.run(document, request) match
                case success: NativeCompilerSuccess =>
                  assert(success.normalizedMlir.contains("nodal.shape_view"))
                  assert(success.normalizedMlir.contains("materialization = \"view\""))
                  if document.text.contains("nodal.shape_index") then
                    assert(success.normalizedMlir.contains("nodal.shape_index"))
                case failure: NativeCompilerFailure =>
                  scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")
          finally delete(directory)
