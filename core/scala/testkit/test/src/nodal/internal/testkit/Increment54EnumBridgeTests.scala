package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

enum DigitalBridgeMode derives HwEnum:
  case Idle, Run, Stop

final class DigitalEnumBridgeSource extends Module:
  import DigitalBridgeMode.*
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val modeType = EnumType.custom(
    3,
    enumEncoding(Idle -> BigInt(0), Run -> BigInt(3), Stop -> BigInt(7))
  )
  val bits = in(Bits(3))
  val mode = in(modeType)
  val nested =
    in(Vec(Struct("Tagged", StructField("mode", modeType), StructField("valid", Bool)), 2))
  val initial = param(enumLiteral(modeType, Idle))
  val decoded = decodeEnum(bits, modeType, Stop)
  val returnedBits = decoded.value.asBits
  val valid = decoded.value.isValid
  val member = decoded.value.isOneOf(Idle, Run)
  val equal = decoded.value === initial
  val selected = selectEnum(decoded.value, 0.U(8))(Stop -> 9.U(8), Idle -> 1.U(8), Run -> 4.U(8))
  val selectedMode = selectEnum(mode, enumLiteral(modeType, Stop))(
    Run -> enumLiteral(modeType, Idle),
    Idle -> enumLiteral(modeType, Run),
    Stop -> initial
  )

final class DigitalWideEnumBridgeSource extends Module:
  import DigitalBridgeMode.*
  val modeType = EnumType.custom(
    70,
    enumEncoding(Idle -> BigInt(0), Run -> (BigInt(1) << 65), Stop -> (BigInt(1) << 69))
  )
  val initial = param(enumLiteral(modeType, Stop))
  val literal = enumLiteral(modeType, Run)
  val equal = literal === initial

final class DigitalSymbolicEnumBridgeSource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(3.integer, range = 3 to 3)
  val raw = in(Bits(width))
  val modeType = EnumType.custom(
    3,
    enumEncoding(
      DigitalBridgeMode.Idle -> BigInt(0),
      DigitalBridgeMode.Run -> BigInt(3),
      DigitalBridgeMode.Stop -> BigInt(7)
    )
  )
  val decoded = decodeEnum(raw, modeType, DigitalBridgeMode.Idle)

final class DigitalEscapedEnumBridgeSource extends Module:
  val first = param(enumLiteral(
    EnumType[DigitalSourceIdentity.`A.B`](),
    DigitalSourceIdentity.`A.B`.`One.Two`
  ))
  val second = param(enumLiteral(
    EnumType[DigitalSourceIdentity.A.B](),
    DigitalSourceIdentity.A.B.One
  ))
  val many = enumLiteral(EnumType.oneHot[DigitalSourceMany](), DigitalSourceMany.C69)

object Increment54EnumBridgeTests extends TestSuite:
  private def snapshot: ConstructionSnapshot =
    ConstructionKernel.inspect(new DigitalEnumBridgeSource)

  private def rejected(value: ConstructionSnapshot): String =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(value)).failed.get
    assert(error.isInstanceOf[BridgeException])
    error.asInstanceOf[BridgeException].diagnostic.code

  private def changeExpression(
      value: ConstructionSnapshot,
      operation: String
  )(change: KernelDigitalExpressionSnapshot => KernelDigitalExpressionSnapshot)
      : ConstructionSnapshot =
    val target = value.digitalExpressions.find(_.operation == operation).get.path
    value.copy(digitalExpressions =
      value.digitalExpressions.map(expression =>
        if expression.path == target then change(expression) else expression
      )
    )

  private def native(document: NodalMlirDocument, directory: Path): String =
    val compiler = sys.env.getOrElse(
      "NODAL_NODALC",
      scala.util.Failure[Nothing](new java.lang.AssertionError("configured nodalc is required")).get
    )
    NativeCompilerClient.run(
      document,
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

  val tests: Tests = Tests:
    test("copied enum selection cannot compute clock reset capability types"):
      val captured = snapshot
      val selected = captured.digitalExpressions.find(_.operation == "enum_select").get
      Vector("Clock", "Reset", "Vec(Clock;2)").foreach: dataType =>
        val altered = captured.copy(digitalExpressions =
          captured.digitalExpressions.map(value =>
            if value.path == selected.path then value.copy(dataType = dataType) else value
          )
        )
        val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(altered)).failed.get
        assert(error.isInstanceOf[BridgeException])
        assert(error.getMessage.contains("clock/reset"))

    test("source enum definitions and dynamic expressions cross the real typed boundary"):
      val captured = snapshot
      val document = ScalaToMlirBridge.fromSnapshot(captured)
      assert(document == ScalaToMlirBridge.lower(new DigitalEnumBridgeSource))
      assert(captured.enumDefinitions.size == 1)
      val operations = captured.digitalExpressions.map(_.operation).toSet
      assert(Set(
        "enum_literal",
        "enum_decode",
        "enum_is_valid",
        "enum_as_bits",
        "enum_eq",
        "enum_is_one_of",
        "enum_select"
      ).subsetOf(operations))
      val decode = captured.digitalExpressions.find(_.operation == "enum_decode").get
      val validity = captured.digitalExpressions.find(value =>
        value.operation == "enum_is_valid" && value.operands == decode.operands
      ).get
      assert(decode.operands == Vector(s"${captured.root}.bits"))
      assert(validity.dataType == "Bool")
      assert(document.text.contains("\"nodal.enum\""))
      assert(document.text.contains("\"nodal.enum_case\""))
      assert(document.text.contains("\"nodal.enum_expr\""))
      assert(document.text.contains("operator_name = \"enum_decode\""))
      assert(document.text.contains("\"nodal.port_value\""))
      assert(document.text.contains("\"nodal.const_parameter_ref\""))
      assert(document.text.contains("!nodal.shaped<\"2\", !nodal.struct<\"Tagged\""))
      assert(document.text.contains("default_value = 0 : i64"))
      assert(!document.text.contains("\"nodal.parameter_value\""))

    test("bridge independently rejects forged definitions hashes policies names and codes"):
      val captured = snapshot
      val abi = captured.enumDefinitions.head
      val malformed = Vector(
        abi.copy(hash = "0" * 64),
        abi.copy(policy = "sequential"),
        abi.copy(width = 0),
        abi.copy(cases = Vector.empty),
        abi.copy(cases = abi.cases.updated(1, abi.cases.head)),
        abi.copy(cases = abi.cases.updated(1, abi.cases(1).copy(code = -1))),
        abi.copy(cases = abi.cases.updated(1, abi.cases(1).copy(code = 8))),
        abi.copy(cases = abi.cases.updated(1, abi.cases(1).copy(id = "foreign.Run")))
      )
      malformed.foreach(value =>
        assert(rejected(captured.copy(enumDefinitions = Vector(value))) ==
          "NODAL-BRIDGE-054-ENUM")
      )
      assert(rejected(captured.copy(enumDefinitions = Vector(abi, abi))) == "NODAL-BRIDGE-054-ENUM")
      assert(rejected(captured.copy(enumDefinitions = Vector.empty)) == "NODAL-BRIDGE-054-ENUM")

    test("bridge independently rejects forged exact type default coverage and operand ownership"):
      val captured = snapshot
      val malformed = Vector(
        changeExpression(captured, "enum_decode")(_.copy(caseIds = Vector("foreign.Idle"))),
        changeExpression(captured, "enum_decode")(_.copy(caseIds = Vector.empty)),
        changeExpression(captured, "enum_decode")(_.copy(dataType = "UInt(3)")),
        changeExpression(captured, "enum_decode")(_.copy(operands = Vector("other.bits"))),
        changeExpression(captured, "enum_decode")(_.copy(enumIdentity = Some("foreign.Mode"))),
        changeExpression(captured, "enum_eq")(_.copy(dataType = "Bits(3)")),
        changeExpression(captured, "enum_select")(value =>
          value.copy(caseIds = value.caseIds.reverse)
        ),
        changeExpression(captured, "enum_select")(value =>
          value.copy(operands = value.operands.dropRight(1))
        ),
        changeExpression(captured, "enum_as_bits")(value =>
          value.copy(operands = Vector(value.path))
        ),
        changeExpression(captured, "enum_literal")(_.copy(literal = Some("0")))
      )
      malformed.foreach(value => assert(rejected(value) == "NODAL-BRIDGE-054-ENUM"))
      val extra = captured.digitalExpressions.head.copy(owner = "unknown")
      assert(rejected(captured.copy(digitalExpressions =
        extra +: captured.digitalExpressions.tail
      )) ==
        "NODAL-BRIDGE-054-ENUM")
      val duplicate = captured.copy(digitalExpressions =
        captured.digitalExpressions :+ captured.digitalExpressions.head
      )
      assert(rejected(duplicate) == "NODAL-BRIDGE-054-ENUM")

    test("enum parameter default cannot alias a dynamic decode or another type"):
      val captured = snapshot
      val decoded = captured.digitalExpressions.find(_.operation == "enum_decode").get.path
      val module = captured.modules.head
      val altered = module.declarations.map: declaration =>
        if declaration.kind == "parameter" then
          declaration.copy(attributes =
            declaration.attributes.map((key, value) =>
              key -> (if key == "default" then decoded else value)
            )
          )
        else declaration
      assert(rejected(captured.copy(modules = Vector(module.copy(declarations = altered)))) ==
        "NODAL-BRIDGE-054-ENUM")
      val abi = captured.enumDefinitions.head
      val wrongType = module.declarations.map: declaration =>
        if declaration.name == "mode" then
          declaration.copy(dataType =
            Some(s"Enum(${abi.identity},4,${abi.hash})")
          )
        else declaration
      assert(rejected(captured.copy(modules = Vector(module.copy(declarations = wrongType)))) ==
        "NODAL-BRIDGE-054-ENUM")

    test("arbitrary precision enum codes and defaults retain unsigned magnitudes"):
      val document = ScalaToMlirBridge.lower(new DigitalWideEnumBridgeSource)
      assert(document.text.contains("default_value = 590295810358705651712 : i71"))
      assert(document.text.contains("value = 36893488147419103232 : i67"))
      assert(document.text.contains("underlying_type = !nodal.uint<70>"))
      assert(!document.text.contains("\"nodal.const_literal\""))

    test("symbolic widths and escaped declaration identities retain exact facts"):
      val symbolic = ScalaToMlirBridge.lower(new DigitalSymbolicEnumBridgeSource)
      assert(symbolic.text.contains("!nodal.digital<\"bits\", \"width\">"))
      assert(symbolic.text.contains("operator_name = \"enum_decode\""))
      val escaped = ScalaToMlirBridge.lower(new DigitalEscapedEnumBridgeSource)
      assert(escaped.text.contains("sym_name = \"One%2ETwo\""))
      assert(escaped.text.contains("sym_name = \"One%252ETwo\""))
      assert(escaped.text.contains("display_name = \"One.Two\""))
      assert(escaped.text.contains("underlying_type = !nodal.uint<70>"))
      assert(escaped.text.contains("value = 590295810358705651712 : i71"))
      assert(escaped.text == ScalaToMlirBridge.lower(new DigitalEscapedEnumBridgeSource).text)

    test("graph cycles and excessive depth reject before recursive expansion"):
      val captured = snapshot
      val selected = captured.digitalExpressions.find(value =>
        value.operation == "enum_select" && value.dataType.startsWith("Enum(")
      ).get
      val cyclic = captured.copy(digitalExpressions =
        captured.digitalExpressions.map(value =>
          if value.path == selected.path then
            value.copy(operands = value.operands.updated(1, value.path))
          else value
        )
      )
      assert(rejected(cyclic) == "NODAL-BRIDGE-054-ENUM")
      val decode = captured.digitalExpressions.find(_.operation == "enum_decode").get
      val abi = captured.enumDefinitions.head
      var previous = decode.path
      val extra = Vector.tabulate(140): index =>
        val path = s"${captured.root}.deep_$index"
        val expression = if index % 2 == 0 then
          KernelDigitalExpressionSnapshot(
            path,
            captured.root,
            "enum_as_bits",
            Vector(previous),
            "Bits(3)",
            None,
            Some(abi.identity),
            Vector.empty
          )
        else decode.copy(path = path, operands = Vector(previous))
        previous = path
        expression
      assert(rejected(captured.copy(digitalExpressions = captured.digitalExpressions ++ extra)) ==
        "NODAL-BRIDGE-054-ENUM")

    test("actual source output verifies and round trips through the native enum boundary"):
      val directory = Path.of(".work", "increment54-enum-bridge").toAbsolutePath
      Files.createDirectories(directory)
      Vector[() => Module](
        () => new DigitalEnumBridgeSource,
        () => new DigitalWideEnumBridgeSource,
        () => new DigitalSymbolicEnumBridgeSource,
        () => new DigitalEscapedEnumBridgeSource
      )
        .zipWithIndex.foreach: (construct, index) =>
          val document = ScalaToMlirBridge.lower(construct())
          Files.writeString(directory.resolve(s"source-$index.mlir"), document.text)
          val normalized = native(document, directory)
          Files.writeString(directory.resolve(s"normalized-$index.mlir"), normalized)
          assert(normalized.contains("nodal.enum_expr"), normalized.contains("!nodal.enum<"))
          val repeated = native(document.copy(text = normalized), directory)
          assert(normalized == repeated)
