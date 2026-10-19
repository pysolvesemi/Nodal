package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import scala.compiletime.testing.typeCheckErrors

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object DigitalDriverWitnesses:
  final case class Witness(
      name: String,
      source: () => Module,
      diagnostic: Option[String] = None,
      standalone: Boolean = false
  )

  private val overlap = Some("overlapping or unproved-disjoint drivers")
  private val cycle = Some("combinational storage-read cycle")
  private val netCycle = Some("combinational value cycle")
  val cases: Vector[Witness] = Vector(
    Witness("scalar-multiple", () => new DriverScalarSource("multiple"), overlap),
    Witness("scalar-self", () => new DriverScalarSource("self"), cycle),
    Witness("scalar-two-cycle", () => new DriverScalarSource("two-cycle"), cycle),
    Witness("scalar-alias-cycle", () => new DriverScalarSource("alias-cycle"), cycle),
    Witness("scalar-alias-positive", () => new DriverScalarSource("alias-positive")),
    Witness("scalar-acyclic", () => new DriverScalarSource("acyclic")),
    Witness(
      "scalar-undriven",
      () => new DriverScalarSource("undriven"),
      Some("consumes an undriven combinational wire")
    ),
    Witness("scalar-unused", () => new DriverScalarSource("unused")),
    Witness(
      "register-undriven-rhs",
      () => new DriverRegisterSource("undriven"),
      Some("consumes an undriven combinational wire")
    ),
    Witness("register-driven-rhs", () => new DriverRegisterSource("driven")),
    Witness("register-self-read", () => new DriverRegisterSource("self")),
    Witness("vec-driven-lane-reads", () => new DriverVecSource("driven-lane-reads")),
    Witness("vec-alias-input", () => new DriverVecSource("alias-input")),
    Witness(
      "vec-undriven-lane",
      () => new DriverVecSource("undriven-lane"),
      Some("consumes an undriven combinational wire")
    ),
    Witness("interface-feedback", () => new DriverInterfaceSource("feedback"), netCycle, true),
    Witness("interface-driven", () => new DriverInterfaceSource("driven"), standalone = true),
    Witness("interface-boundary", () => new DriverInterfaceSource("boundary"), standalone = true),
    Witness("net-feedback", () => new DriverNetSource("feedback"), netCycle, true),
    Witness(
      "net-pass-through-feedback",
      () => new DriverNetSource("pass-through-feedback"),
      netCycle,
      true
    ),
    Witness("net-two-feedback", () => new DriverNetSource("two-net-feedback"), netCycle, true),
    Witness("net-acyclic", () => new DriverNetSource("acyclic"), standalone = true),
    Witness(
      "net-multiple-drivers",
      () => new DriverNetSource("multiple-drivers"),
      standalone = true
    ),
    Witness("net-pass-through", () => new DriverNetSource("pass-through"), standalone = true),
    Witness("net-aliases-only", () => new DriverNetSource("aliases-only"), standalone = true)
  )

  def directory: Path = Path.of(sys.env.getOrElse(
    "NODAL_DRIVER_WITNESS_DIR",
    ".work/increment55-driver-witnesses"
  )).toAbsolutePath

  def document(witness: Witness): NodalMlirDocument =
    val captured = ConstructionKernel.inspect(witness.source())
    val result = ScalaToMlirBridge.fromSnapshot(captured)
    assert(result == ScalaToMlirBridge.lower(witness.source()))
    assert(!result.text.contains("nodal.placeholder"))
    if witness.standalone then assert(!result.text.contains("nodal.digital_assign"))
    result

/** Construction/serialization is distinct from native graph admission. */
object Increment55DigitalDriverSourceTests extends TestSuite:
  val tests: Tests = Tests:
    test("actual public source preserves causal graphs for native verification"):
      DigitalDriverWitnesses.cases.foreach: witness =>
        val document = DigitalDriverWitnesses.document(witness)
        val directory = DigitalDriverWitnesses.directory.resolve(witness.name)
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("source.mlir"), document.text)
        Files.writeString(directory.resolve("expected.txt"), witness.diagnostic.getOrElse("accept"))
        if witness.name.contains("alias-") then assert(document.text.contains("digital_alias"))
        if witness.name.startsWith("vec-") then assert(document.text.contains("nodal.shape_index"))
        if witness.name.startsWith("net-") && witness.name.contains("feedback") then
          assert(document.text.contains("nodal.net_read"))

    test("frozen public Vec projection does not invent a writable capability"):
      val errors = typeCheckErrors("""
        import nodal.*
        final class UnavailableLaneWrite extends Module:
          val input = in(SInt(8))
          val lanes = wire(Vec(SInt(8), 2))
          lanes.at(0) := input
      """)
      assert(errors.exists(_.message.contains("value := is not a member")))

/** Run only with the coordinated, activated native owner, never a predecessor binary. */
object Increment55DigitalDriverAdmissionTests extends TestSuite:
  val tests: Tests = Tests:
    test("native graph admission follows actual public source and exact causal diagnostics"):
      val compiler = Path.of(sys.env.getOrElse(
        "NODAL_NODALC",
        scala.util.Failure[Nothing](
          new java.lang.AssertionError("configured nodalc is required")
        ).get
      )).toAbsolutePath
      DigitalDriverWitnesses.cases.foreach: witness =>
        val document = DigitalDriverWitnesses.document(witness)
        val directory = DigitalDriverWitnesses.directory.resolve(witness.name)
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
