package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class DigitalCapabilityWiringSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val clockInput = in(Clock)
  val clockOutput = out(Clock)
  val resetInput = in(Reset)
  val resetOutput = out(Reset)
  val clocks = in(Vec(Clock, 2))
  val clockVectorOutput = out(Vec(Clock, 2))
  val selectedOutput = out(Clock)
  clockOutput := clockInput
  resetOutput := resetInput
  clockVectorOutput := clocks
  selectedOutput := clocks.at(1)

object Increment54CapabilityWiringTests extends TestSuite:
  private def rejected(snapshot: ConstructionSnapshot): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(error.isInstanceOf[BridgeException])

  val tests: Tests = Tests:
    test("Clock Reset and projected capability wiring retain exact boundary SSA"):
      val captured = ConstructionKernel.inspect(new DigitalCapabilityWiringSource)
      assert(captured.digitalAssignments.size == 4)
      val document = ScalaToMlirBridge.fromSnapshot(captured)
      assert(document.text.contains("!nodal.capability<\"clock\">"))
      assert(document.text.contains("!nodal.capability<\"reset\">"))
      assert(document.text.contains("!nodal.shaped<\"2\", !nodal.capability<\"clock\">>"))
      assert(document.text.linesIterator.count(_.contains("\"nodal.digital_assign\"")) == 4)
      assert(document.text.contains("\"nodal.shape_index\""))
      val directory = Path.of(".work", "increment54-capability-wiring").toAbsolutePath
      Files.createDirectories(directory)
      Files.writeString(directory.resolve("source.mlir"), document.text)
      val compiler = sys.env.getOrElse(
        "NODAL_NODALC",
        scala.util.Failure[Nothing](
          new java.lang.AssertionError("configured nodalc is required")
        ).get
      )
      def native(value: NodalMlirDocument): String = NativeCompilerClient.run(
        value,
        NativeCompilerRequest(
          Path.of(compiler).toAbsolutePath,
          Vector.empty,
          directory,
          timeout = Duration.ofSeconds(90)
        )
      ) match
        case success: NativeCompilerSuccess => success.normalizedMlir
        case failure: NativeCompilerFailure =>
          scala.util.Failure[Nothing](new java.lang.AssertionError(
            s"${failure.diagnostic}\n${failure.standardError}"
          )).get
      val normalized = native(document)
      Files.writeString(directory.resolve("normalized.mlir"), normalized)
      assert(native(document.copy(text = normalized)) == normalized)

    test("capability wiring rejects forged kind root domain and computed producers"):
      val captured = ConstructionKernel.inspect(new DigitalCapabilityWiringSource)
      val module = captured.modules.find(_.path == captured.root).get
      val target = module.declarations.find(_.name == "clockOutput").get
      val assignment = captured.digitalAssignments.find(_.target == target.path).get
      def changedAssignment(
          change: KernelDigitalAssignmentSnapshot => KernelDigitalAssignmentSnapshot
      ): ConstructionSnapshot =
        captured.copy(digitalAssignments =
          captured.digitalAssignments.map(value =>
            if value.path == assignment.path then change(value) else value
          )
        )
      rejected(changedAssignment(_.copy(value = target.path)))
      rejected(changedAssignment(_.copy(kind = "register_next")))
      rejected(captured.copy(modules =
        captured.modules.map(value =>
          if value.path != module.path then value
          else
            value.copy(declarations =
              value.declarations.map(declaration =>
                if declaration.path == target.path then declaration.copy(dataType = Some("Reset"))
                else declaration
              )
            )
        )
      ))
      val domain = module.domains.head
      val other = domain.copy(path = module.path + ".other", name = "other", binding = None)
      rejected(captured.copy(modules =
        captured.modules.map(value =>
          if value.path != module.path then value
          else
            value.copy(
              domains = value.domains :+ other,
              declarations = value.declarations.map(declaration =>
                declaration.copy(domain = Some(if declaration.path == target.path then other.path
                else domain.path))
              )
            )
        )
      ))
      val fake = module.path + ".fakeCapability"
      rejected(changedAssignment(_.copy(value = fake)).copy(digitalExpressions =
        captured.digitalExpressions :+ KernelDigitalExpressionSnapshot(
          fake,
          module.path,
          "boolean",
          Vector.empty,
          "Clock",
          Some("true"),
          None,
          Vector.empty
        )
      ))
