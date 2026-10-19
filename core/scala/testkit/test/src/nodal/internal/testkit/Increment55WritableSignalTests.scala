package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import scala.compiletime.testing.typeCheckErrors

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object WritableSignalWitnesses:
  final case class Witness(name: String, source: () => Module, diagnostic: Option[String] = None)
  val cases: Vector[Witness] =
    Vector("disjoint", "output", "chain").map(mode =>
      Witness("lanes-" + mode, () => new WritableSignalLanes(mode))
    ) ++
      Vector("rows", "slices", "columns", "flatten", "reshape", "composed").map(mode =>
        Witness("views-" + mode, () => new WritableSignalViews(mode))
      ) ++
      Vector(
        Witness("planes", () => new WritableSignalPlanes),
        Witness("aggregate", () => new WritableSignalAggregate(false)),
        Witness("symbolic-selector", () => new WritableSignalSymbolicCovered(false)),
        Witness("symbolic-row", () => new WritableSignalSymbolicCovered(true)),
        Witness(
          "symbolic-selector-output-gap",
          () => new WritableSignalSymbolic("selector"),
          Some("lacks proved complete physical driver coverage")
        ),
        Witness(
          "symbolic-row-output-gap",
          () => new WritableSignalSymbolic("row"),
          Some("lacks proved complete physical driver coverage")
        ),
        Witness("root-predecessor", () => new WritableSignalRootPredecessor),
        Witness(
          "overlap",
          () => new WritableSignalLanes("overlap"),
          Some("overlapping or unproved-disjoint drivers")
        ),
        Witness(
          "whole-overlap",
          () => new WritableSignalLanes("whole-overlap"),
          Some("overlapping or unproved-disjoint drivers")
        ),
        Witness(
          "cycle",
          () => new WritableSignalLanes("cycle"),
          Some("combinational storage-read cycle")
        ),
        Witness(
          "uncovered",
          () => new WritableSignalLanes("uncovered"),
          Some("consumes an undriven combinational wire")
        ),
        Witness(
          "unknown-overlap",
          () => new WritableSignalSymbolic("unknown-overlap"),
          Some("overlapping or unproved-disjoint drivers")
        )
      )
  def directory: Path = Path.of(sys.env.getOrElse(
    "NODAL_WRITABLE_WITNESS_DIR",
    ".work/increment55-writable-signal-witnesses"
  )).toAbsolutePath

/** Trust-boundary probes intentionally construct private carriers, separately from public
  * witnesses.
  */
final class ForgedWritableSignal(mode: String) extends Module, WritableSignalClock:
  val input = in(Vec(UInt(8), 2))
  val cells = wire(Vec(UInt(8), 2))
  val state = Reg.uninitialized(Vec(UInt(8), 2))
  mode match
    case "alias" => new SignalSelection(cells.named("alias")).at(0) := input.at(0)
    case "computed" => new SignalSelection(input.map(value => value)).at(0) := input.at(0)
    case "root-register" => new SignalSelection(state) := input
    case "projected-register" => new SignalSelection(state).at(0) := input.at(0)
    case "unrecorded" =>
      val fake = new KernelExpr[UInt](
        Vector(cells, 0),
        Some(KernelTypeDescriptor("UInt", Vector(8))),
        operation = Some("shape_index")
      )
      ConstructionKernel.expression(fake)
      new SignalSelection(fake) := input.at(0)
    case "wrong-edge" =>
      val fake = new KernelExpr[UInt](
        Vector(input, 0),
        Some(KernelTypeDescriptor("UInt", Vector(8))),
        operation = Some("shape_index")
      )
      ConstructionKernel.shapeIndex(fake, cells, Vector(0))
      new SignalSelection(fake) := input.at(0)
    case _ => ()

object Increment55WritableSignalTests extends TestSuite:
  private def rejected(source: => Module, code: String, cause: String): Unit =
    val error = scala.util.Try(ConstructionKernel.inspect(source)).failed.get
    assert(error.toString.contains(code))
    assert(error.toString.contains(cause))

  private def bridgeRejected(snapshot: ConstructionSnapshot, cause: String): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(error.isInstanceOf[BridgeException])
    assert(error.toString.contains(cause))

  val tests: Tests = Tests:
    test("public source preserves actual selected SSA targets"):
      WritableSignalWitnesses.cases.foreach: witness =>
        val snapshot = ConstructionKernel.inspect(witness.source())
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        assert(!document.text.contains("nodal.placeholder"))
        if witness.name != "root-predecessor" then
          assert(snapshot.digitalAssignments.exists(assignment =>
            snapshot.shapeIndices.exists(_.path == assignment.target) ||
              snapshot.shapeViews.exists(_.path == assignment.target)
          ))
        val directory = WritableSignalWitnesses.directory.resolve(witness.name)
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("source.mlir"), document.text)
        Files.writeString(directory.resolve("expected.txt"), witness.diagnostic.getOrElse("accept"))

    test("readonly computed alias and register values do not gain write authority"):
      val readonly = typeCheckErrors("""import nodal.*
        final class ReadonlyWrite extends Module:
          val input = in(UInt(8))
          val cells = wire(Vec(UInt(8), 2))
          cells.at(0) := input
      """)
      assert(readonly.exists(_.message.contains("value := is not a member")))
      val computed = typeCheckErrors("""import nodal.*
        final class ComputedWrite extends Module:
          val input = in(Vec(UInt(8), 2))
          input.named("alias").writable.at(0) := input.at(1)
      """)
      assert(computed.exists(_.message.contains("value writable is not a member")))
      val register = typeCheckErrors("""import nodal.*
        final class RegisterWrite extends Module:
          val state = Reg.uninitialized(Vec(UInt(8), 2))
          state.writable.at(0) := 1.U(8)
      """)
      assert(register.exists(_.message.contains("value writable is not a member")))

    test("source rejects input foreign and mismatched ABI targets"):
      rejected(new WritableSignalLanes("input"), "NODAL-DIGITAL-054-010", "owned wire/output")
      rejected(new WritableSignalForeign, "NODAL-DIGITAL-054-010", "foreign")
      rejected(new WritableSignalLanes("width"), "NODAL-DIGITAL-054-010", "exactly matching")
      rejected(
        new WritableSignalSymbolic("parameter-identity"),
        "NODAL-DIGITAL-054-010",
        "exactly matching"
      )

    test("selected aggregate assignment preserves nominal exact ABI"):
      rejected(new WritableSignalAggregate(true), "NODAL-DIGITAL-054-010", "exactly matching")

    test("existing shape owner retains rank bounds and view proofs"):
      rejected(new WritableSignalLanes("bounds"), "NODAL-SHAPE-043-002", "in bounds")
      rejected(new WritableSignalViews("rank"), "NODAL-SHAPE-043-002", "prefix index")
      rejected(new WritableSignalViews("slice-bounds"), "NODAL-SHAPE-043-004", "upper bound")
      rejected(
        new WritableSignalViews("reshape-size"),
        "NODAL-SHAPE-043-003",
        "equal literal products"
      )

    test("private carriers cannot manufacture source authority"):
      Vector("alias", "computed", "unrecorded").foreach(mode =>
        rejected(
          new ForgedWritableSignal(mode),
          "NODAL-DIGITAL-054-010",
          "actual storage projection"
        )
      )
      Vector("root-register", "projected-register").foreach(mode =>
        rejected(
          new ForgedWritableSignal(mode),
          "NODAL-DIGITAL-054-010",
          "projected register updates are unsupported"
        )
      )
      rejected(new ForgedWritableSignal("wrong-edge"), "NODAL-DIGITAL-054-010", "actual input edge")

    test("bridge independently resolves target root direction and lexical ownership"):
      val captured = ConstructionKernel.inspect(new WritableSignalLanes("disjoint"))
      val assignment = captured.digitalAssignments.head
      val projection = captured.shapeIndices.find(_.path == assignment.target).get
      val input = captured.modules.flatMap(_.declarations).find(_.name == "input").get
      bridgeRejected(
        captured.copy(shapeIndices =
          captured.shapeIndices.map(value =>
            if value.path == projection.path then value.copy(input = input.path) else value
          )
        ),
        "exact sink kind"
      )
      bridgeRejected(
        captured.copy(shapeIndices =
          captured.shapeIndices.map(value =>
            if value.path == projection.path then value.copy(owner = "foreign") else value
          )
        ),
        "another owner"
      )
      bridgeRejected(
        captured.copy(shapeIndices =
          captured.shapeIndices.map(value =>
            if value.path == projection.path then
              value.copy(
                generatedScope = Some(KernelGeneratedScope("forged", "forged.index"))
              )
            else value
          )
        ),
        "digital assignment projection cannot use generated storage"
      )
      val selectedExpression = captured.digitalExpressions.find(_.path == projection.path).get
      bridgeRejected(
        captured.copy(digitalExpressions =
          captured.digitalExpressions.map(value =>
            if value.path == selectedExpression.path then value.copy(region = Some("forged"))
            else value
          )
        ),
        "exact lexical scope"
      )

/** Run only with the coordinated, activated native owner, never a predecessor binary. */
object Increment55WritableSignalAdmissionTests extends TestSuite:
  val tests: Tests = Tests:
    test("native graph admission follows actual public source and exact causal diagnostics"):
      val compiler = Path.of(sys.env.getOrElse(
        "NODAL_NODALC",
        scala.util.Failure[Nothing](
          new java.lang.AssertionError("configured nodalc is required")
        ).get
      )).toAbsolutePath
      WritableSignalWitnesses.cases.foreach: witness =>
        val document = ScalaToMlirBridge.lower(witness.source())
        val directory = WritableSignalWitnesses.directory.resolve(witness.name)
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("source.mlir"), document.text)
        val request = NativeCompilerRequest(
          compiler,
          Vector.empty,
          directory,
          timeout = Duration.ofSeconds(90)
        )
        val result = NativeCompilerClient.run(document, request)
        (witness.diagnostic, result) match
          case (None, success: NativeCompilerSuccess) =>
            Files.writeString(directory.resolve("normalized.mlir"), success.normalizedMlir)
            NativeCompilerClient.run(document.copy(text = success.normalizedMlir), request) match
              case again: NativeCompilerSuccess =>
                assert(again.normalizedMlir == success.normalizedMlir)
              case failure: NativeCompilerFailure =>
                Predef.assert(false, s"${witness.name}: ${failure.standardError}")
          case (Some(expected), failure: NativeCompilerFailure) =>
            Files.writeString(directory.resolve("diagnostic.txt"), failure.standardError)
            assert(failure.standardError.contains("NODAL-DIGITAL-055-010"))
            assert(failure.standardError.contains(expected))
          case _ => Predef.assert(false, s"${witness.name}: $result")
