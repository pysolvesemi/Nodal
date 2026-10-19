package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class DigitalConversionBridgeSource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(8.integer, range = 3 to 8)
  val otherWidth = param(8.integer, range = 3 to 8)
  val input = in(UInt(width))
  val other = in(UInt(otherWidth))
  val signedInput = in(SInt(8))
  val numeric = input.toSigned
  val unsigned = numeric.toUnsigned
  val reinterpreted = input.reinterpretSigned
  val returned = reinterpreted.reinterpretUnsigned
  val positive = 7.U(width)
  val checked = positive.resizeChecked(3)
  val extended = input.extend(8)
  val truncated = input.truncate(3)
  val saturated = input.saturate(3)
  val wrapped = input.wrap(3)
  val real = toReal(input)
  val modeType = EnumType.custom(
    3,
    enumEncoding(
      DigitalBridgeMode.Idle -> BigInt(0),
      DigitalBridgeMode.Run -> BigInt(3),
      DigitalBridgeMode.Stop -> BigInt(7)
    )
  )
  val decoded = decodeEnum(wrapped, modeType, DigitalBridgeMode.Idle)
  val selected = selectEnum(decoded.value, 0.U(3))(
    DigitalBridgeMode.Idle -> 1.U(3),
    DigitalBridgeMode.Run -> 2.U(3),
    DigitalBridgeMode.Stop -> 3.U(3)
  )
  val selectedSigned = selected.toSigned
  val matrix = in(Vec(UInt(width), 2, 2))
  val flat = matrix.flatten
  val projected = flat.at(2)
  val projectedSigned = projected.toSigned

final class DigitalParameterBridgeSource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(8.integer, range = 4 to 8)
  val positive = param(default = 7.U(width))
  val negative = param(default = (-8).S(width))
  val converted = positive.toSigned

final class DigitalInvalidParameterBridgeSource extends Module:
  val width = param(8.integer, range = 4 to 8)
  val invalid = param(default = 16.U(width))

object Increment54DigitalBridgeTests extends TestSuite:
  private def snapshot: ConstructionSnapshot =
    ConstructionKernel.inspect(new DigitalConversionBridgeSource)

  private def rejected(value: ConstructionSnapshot): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(value)).failed.get
    assert(error.isInstanceOf[BridgeException])

  private def change(value: ConstructionSnapshot, operation: String)(
      update: KernelDigitalExpressionSnapshot => KernelDigitalExpressionSnapshot
  ): ConstructionSnapshot =
    val path = value.digitalExpressions.find(_.operation == operation).get.path
    value.copy(digitalExpressions =
      value.digitalExpressions.map(expression =>
        if expression.path == path then update(expression) else expression
      )
    )

  val tests: Tests = Tests:
    test("scalar and enum values use one real typed closure without losing symbolic widths"):
      val captured = snapshot
      val document = ScalaToMlirBridge.fromSnapshot(captured)
      assert(DigitalScalarContract.Operations.subsetOf(
        captured.digitalExpressions.map(_.operation).toSet
      ))
      assert(captured.digitalExpressions.exists(_.operation == "enum_decode"))
      assert(captured.digitalExpressions.exists(_.operation == "enum_select"))
      assert(document.text.contains("\"nodal.digital_expr\""))
      assert(document.text.contains("\"nodal.enum_expr\""))
      val typedValues = document.text.linesIterator.filter(line =>
        line.contains("\"nodal.digital_expr\"") || line.contains("\"nodal.enum_expr\"")
      ).toVector
      assert(typedValues.nonEmpty)
      assert(typedValues.forall(_.contains("semantic_path =")))
      assert(document.text.contains("!nodal.digital<\"sint\""))
      assert(document.text.contains("\"nodal.const_expr\""))
      assert(captured.digitalExpressions.exists(_.operation == "shape_reference"))
      assert(document.text.contains("\"nodal.shape_view\""))
      assert(document.text.contains("\"nodal.shape_index\""))
      assert(document.text == ScalaToMlirBridge.lower(new DigitalConversionBridgeSource).text)

    test("bridge checks exact width relations and runtime proof boundaries independently"):
      val captured = snapshot
      Vector(
        change(captured, "digital_extend")(_.copy(dataType = "UInt(7)")),
        change(captured, "digital_truncate")(_.copy(dataType = "UInt(4)")),
        change(
          captured,
          "digital_resize_checked"
        )(_.copy(operands = Vector(s"${captured.root}.input"))),
        change(captured, "digital_to_unsigned")(_.copy(
          operands = Vector(s"${captured.root}.signedInput"),
          dataType = "UInt(8)"
        )),
        change(captured, "digital_reinterpret_signed")(_.copy(dataType = "SInt(otherWidth)")),
        change(captured, "digital_to_signed")(_.copy(dataType = "SInt(otherWidth)")),
        change(captured, "digital_to_real")(_.copy(dataType = "UInt(8)")),
        change(
          captured,
          "digital_wrap"
        )(_.copy(enumIdentity = captured.enumDefinitions.headOption.map(_.identity))),
        change(captured, "digital_wrap")(_.copy(operands = Vector.empty))
      ).foreach(rejected)
      val literal = captured.digitalExpressions.find(value =>
        value.literal.contains("7") &&
          value.dataType == "UInt(width)"
      ).get
      rejected(captured.copy(digitalExpressions =
        captured.digitalExpressions.map(value =>
          if value.path == literal.path then value.copy(literal = Some("8")) else value
        )
      ))

    test("projection references preserve their authoritative result and ownership"):
      val captured = snapshot
      val projection = captured.digitalExpressions.find(value =>
        value.operation == "shape_reference" && value.dataType == "UInt(width)"
      ).get
      rejected(captured.copy(digitalExpressions =
        captured.digitalExpressions.map(value =>
          if value.path == projection.path then value.copy(dataType = "UInt(otherWidth)") else value
        )
      ))
      rejected(captured.copy(shapeIndices =
        captured.shapeIndices.filterNot(_.path == projection.path)
      ))
      rejected(captured.copy(shapeIndices =
        captured.shapeIndices.map(value =>
          if value.path == projection.path then value.copy(input = projection.path) else value
        )
      ))

    test("symbolic parameter defaults fit every legal width independently of defaults"):
      val captured = ConstructionKernel.inspect(new DigitalParameterBridgeSource)
      val document = ScalaToMlirBridge.fromSnapshot(captured)
      assert(document.text.contains("default_value = 7 : i64"))
      assert(document.text.contains("default_value = -8 : i64"))
      assert(document.text.contains("!nodal.digital<\"uint\", \"width\">"))
      rejected(captured.copy(modules =
        captured.modules.map(module =>
          module.copy(declarations =
            module.declarations.map(declaration =>
              if declaration.name == "positive" then
                declaration.copy(attributes =
                  declaration.attributes.map((key, value) =>
                    key -> (if key == "default" then "16" else value)
                  )
                )
              else declaration
            )
          )
        )
      ))
      assert(scala.util.Try(
        ConstructionKernel.inspect(new DigitalInvalidParameterBridgeSource)
      ).isFailure)

    test("native verifies source scalar and enum composition and exact symbolic literals"):
      val compiler = sys.env.getOrElse(
        "NODAL_NODALC",
        scala.util.Failure[Nothing](
          new java.lang.AssertionError("configured nodalc is required")
        ).get
      )
      val directory = Path.of(".work", "increment54-digital-bridge").toAbsolutePath
      Files.createDirectories(directory)
      val document = ScalaToMlirBridge.lower(new DigitalConversionBridgeSource)
      Files.writeString(directory.resolve("source.mlir"), document.text)
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
          scala.util.Failure[Nothing](
            new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
          ).get
      val normalized = native(document)
      Files.writeString(directory.resolve("normalized.mlir"), normalized)
      assert(normalized.contains("nodal.digital_expr"), normalized.contains("nodal.enum_expr"))
      assert(normalized.contains("nodal.shape_view"), normalized.contains("nodal.shape_index"))
      assert(native(document.copy(text = normalized)) == normalized)
      val parameters = ScalaToMlirBridge.lower(new DigitalParameterBridgeSource)
      Files.writeString(directory.resolve("parameters.mlir"), parameters.text)
      val normalizedParameters = native(parameters)
      assert(native(parameters.copy(text = normalizedParameters)) == normalizedParameters)
