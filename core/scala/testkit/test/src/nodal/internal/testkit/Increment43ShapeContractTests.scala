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

final class Increment43CompoundShape extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)
  val samples: Signal[Vec[Real]] = wire(Vec(Real, lanes + 1.integer))

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
      assert(fixed.modules.head.declarations.find(_.name == "samples").flatMap(_.dataType)
        .contains("Vec(Real;2x3)"))
      assert(symbolic.modules.head.declarations.find(_.name == "samples").flatMap(_.dataType)
        .contains("Vec(Real;lanesx2)"))
      val parameter = symbolic.modules.head.declarations.find(_.name == "lanes").get
      val attributes = parameter.attributes.toMap
      assert(attributes.get("classification").contains("structural"))
      assert(attributes.get("structural_effects").contains("rank,shape"))

    test("bridge emits canonical shaped port types and parameter shape envelopes"):
      val fixed = ScalaToMlirBridge.lower(new Increment43FixedShape)
      val symbolic = ScalaToMlirBridge.lower(new Increment43SymbolicShape)
      assert(fixed == ScalaToMlirBridge.lower(new Increment43FixedShape))
      assert(fixed.text.contains("!nodal.shaped<\"2,3\", f64>"))
      assert(symbolic.text.contains("!nodal.shaped<\"lanes,2\", f64>"))
      assert(symbolic.text.contains("effects = [\"rank\", \"shape\"]"))

    test("zero unbounded possibly-empty and compound dimensions fail before publication"):
      Vector(
        failure(new Increment43ZeroShape),
        failure(new Increment43UnboundedShape),
        failure(new Increment43PossiblyEmptyShape),
        failure(new Increment43CompoundShape)
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
              ScalaToMlirBridge.lower(new Increment43SymbolicShape)
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
