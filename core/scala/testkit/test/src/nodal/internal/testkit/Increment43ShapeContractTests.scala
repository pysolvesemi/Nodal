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
