package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class DigitalCombinationalBridgeSource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(4.integer, range = 2 to 9)
  val otherWidth = param(4.integer, range = 2 to 9)
  val fixedWidth = param(4.integer, range = 4 to 4)
  val otherFixedWidth = param(4.integer, range = 4 to 4)
  val left = in(UInt(width))
  val right = in(UInt(otherWidth))
  val signed = in(SInt(4))
  val amount = in(UInt(4))
  val fixed = in(UInt(fixedWidth))
  val fixedOther = in(UInt(otherFixedWidth))
  val sum = left + right
  val difference = left - right
  val product = left * right
  val negative = -left
  val equal = left === right
  val unequal = left =/= right
  val less = left < right
  val lessEqual = left <= right
  val greater = left > right
  val greaterEqual = left >= right
  val condition = (less && !false.B) || equal
  val booleanOutput = out(Bool)
  booleanOutput := !false.B
  val selected = select(condition, left, left)
  val equivalentSelected = select(condition, sum, left + right)
  val equivalentBitwise = sum & (left + right)
  val conjunction = left & left
  val disjunction = left | left
  val exclusive = left ^ left
  val complement = ~left
  val fixedComplement = ~fixed
  val joined = left.concat(right)
  val extracted = left.extract(0, 2)
  val bitEqual = extracted === extracted
  val shifted = left << 2
  val unchanged = left << 0
  val arithmeticRight = signed >> 1
  val logicalRight = signed.logicalShiftRight(1)
  val logicalZero = (-8).S(4).logicalShiftRight(0)
  val matrix = in(Vec(SInt(4), 2, 3))
  val otherMatrix = in(Vec(SInt(4), 2, 3))
  val matrixSelected = select(condition, matrix, otherMatrix)
  val symbolicMatrix = in(Vec(UInt(width + 1.integer), 2))
  val equivalentMatrix = in(Vec(UInt(width + 1.integer), 2))
  val symbolicMatrixSelected = select(condition, symbolicMatrix, equivalentMatrix)
  val symbolicOutput = out(Vec(UInt(width + 1.integer), 2))
  val symbolicWire = wire(Vec(UInt(width + 1.integer), 2))
  symbolicOutput := equivalentMatrix
  symbolicWire := symbolicMatrix
  val modeType = EnumType[DigitalBridgeMode]()
  val modeSelected = select(
    condition,
    enumLiteral(modeType, DigitalBridgeMode.Idle),
    enumLiteral(modeType, DigitalBridgeMode.Run)
  )
  val checkedSum = (3.U(2) + 4.U(3)).resizeChecked(3)
  val checkedDifference = (15.U(4) - 14.U(4)).toUnsigned.resizeChecked(1)
  val checkedProduct = ((-8).S(4) * (-8).S(4)).toUnsigned.resizeChecked(7)
  val checkedNegative = (-(-8).S(4)).toUnsigned.resizeChecked(4)
  val checkedBits = ((5.U(4) & 3.U(4)) | (5.U(4) ^ 4.U(4))).resizeChecked(1)
  val checkedComplement = (~14.U(4)).resizeChecked(1)
  val checkedConcat = (-1).S(2).concat(1.U(2)).extract(0, 2).resizeChecked(1)
  val checkedShift = (-8).S(4).logicalShiftRight(1).toUnsigned.resizeChecked(3)
  val checkedSelect = select(
    (1.U(1) < 2.U(2)) && !(3.S(3) =/= 3.S(3)),
    1.U(8),
    255.U(8)
  ).resizeChecked(1)

object Increment55DigitalExpressionBridgeTests extends TestSuite:
  private lazy val snapshot: ConstructionSnapshot =
    ConstructionKernel.inspect(new DigitalCombinationalBridgeSource)

  private def rejected(value: ConstructionSnapshot): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(value)).failed.get
    assert(error.isInstanceOf[BridgeException])

  private def change(value: ConstructionSnapshot, operation: String)(
      update: KernelDigitalExpressionSnapshot => KernelDigitalExpressionSnapshot
  ): ConstructionSnapshot =
    val path = value.digitalExpressions.find(_.operation == operation).get.path
    changePath(value, path)(update)

  private def changePath(value: ConstructionSnapshot, path: String)(
      update: KernelDigitalExpressionSnapshot => KernelDigitalExpressionSnapshot
  ): ConstructionSnapshot =
    value.copy(digitalExpressions =
      value.digitalExpressions.map(expression =>
        if expression.path == path then update(expression) else expression
      )
    )

  val tests: Tests = Tests:
    test("one typed SSA closure preserves arithmetic Boolean aggregate and checked proof edges"):
      val captured = snapshot
      val document = ScalaToMlirBridge.fromSnapshot(captured)
      val operations = captured.digitalExpressions.map(_.operation).toSet
      assert(DigitalCombinationalContract.Operations.subsetOf(operations))
      assert(DigitalCombinationalContract.ReachableBooleanOperations.subsetOf(operations))
      val expected = captured.digitalExpressions.count(value =>
        DigitalCombinationalContract.Operations.contains(value.operation) ||
          DigitalCombinationalContract.ReachableBooleanOperations.contains(value.operation) ||
          DigitalScalarContract.Operations.contains(value.operation)
      )
      assert(document.text.linesIterator.count(_.contains("\"nodal.digital_expr\"")) == expected)
      assert(document.text.contains("operator_name = \"max\""))
      assert(document.text.contains("!nodal.shaped<\"2,3\", !nodal.sint<4>>"))
      assert(document.text.contains("spelling = \"0\""))
      assert(document.text == ScalaToMlirBridge.lower(new DigitalCombinationalBridgeSource).text)

    test("forged snapshots reject operation arity kind result and attached facts independently"):
      val captured = snapshot
      Vector(
        change(captured, "digital_add")(_.copy(operands = Vector.empty)),
        change(captured, "digital_add")(value =>
          value.copy(operands = value.operands :+ value.operands.head)
        ),
        change(captured, "digital_add")(_.copy(operation = "digital_unchecked_add")),
        change(captured, "digital_add")(_.copy(dataType = "UInt(5)")),
        change(captured, "digital_add")(value =>
          value.copy(operands = value.operands.updated(1, s"${captured.root}.signed"))
        ),
        change(captured, "digital_sub")(value =>
          value.copy(dataType = value.dataType.replace("SInt(", "UInt("))
        ),
        change(captured, "digital_mul")(_.copy(literal = Some("0"))),
        change(captured, "digital_neg")(_.copy(caseIds = Vector("forged"))),
        change(captured, "digital_lt")(_.copy(dataType = "Bits(1)")),
        change(captured, "bool_not")(_.copy(dataType = "Bits(1)")),
        change(captured, "digital_select")(value =>
          value.copy(operands = value.operands.updated(0, s"${captured.root}.amount"))
        ),
        change(captured, "digital_concat")(_.copy(dataType = "UInt(8)")),
        change(captured, "digital_add")(value =>
          value.copy(operands = value.operands.updated(0, value.path))
        )
      ).foreach(rejected)

    test(
      "symbolic width formulas and identities cannot be replaced by equal defaults or envelopes"
    ):
      val captured = snapshot
      rejected(change(captured, "digital_and")(_.copy(dataType = "UInt(otherWidth)")))
      rejected(change(captured, "digital_and")(value =>
        value.copy(operands = value.operands.updated(1, s"${captured.root}.right"))
      ))
      val fixed = captured.digitalExpressions.find(value =>
        value.operation == "digital_not" && value.dataType == "UInt(fixedWidth)"
      ).get
      rejected(changePath(captured, fixed.path)(_.copy(dataType = "UInt(otherFixedWidth)")))
      rejected(changePath(captured, fixed.path)(_.copy(dataType = "UInt(4)")))
      val maximum = captured.parameterExpressions.find(_.operation == "static_integer_max").get
      rejected(captured.copy(parameterExpressions =
        captured.parameterExpressions.map(value =>
          if value.path == maximum.path then value.copy(operation = "analog_add") else value
        )
      ))

    test("static shift and extraction require real UInt literals and universally legal ranges"):
      val captured = snapshot
      rejected(change(captured, "digital_shl")(value =>
        value.copy(operands = value.operands.updated(1, s"${captured.root}.amount"))
      ))
      val computedAmount = captured.digitalExpressions.find(value =>
        value.operation == "digital_add" && value.dataType == "UInt(4)"
      ).get
      rejected(change(captured, "digital_shl")(value =>
        value.copy(operands = value.operands.updated(1, computedAmount.path))
      ))
      val extraction = captured.digitalExpressions.find(_.operation == "digital_extract").get
      val upper = extraction.operands(2)
      rejected(changePath(captured, upper)(_.copy(literal = Some("3"))))
      rejected(change(captured, "digital_extract")(_.copy(dataType = "Bits(1)")))
      rejected(change(captured, "digital_shr")(_.copy(dataType = "SInt(3)")))

    test("constant proof magnitude remains bounded and failed translation is transactional"):
      val captured = snapshot
      val hugePath = captured.root + ".hugeLiteral"
      val differencePath = captured.root + ".hugeDifference"
      val huge = KernelDigitalExpressionSnapshot(
        hugePath,
        captured.root,
        "integer",
        Vector.empty,
        "UInt(4097)",
        Some((BigInt(1) << 4096).toString),
        None,
        Vector.empty
      )
      val difference = KernelDigitalExpressionSnapshot(
        differencePath,
        captured.root,
        "digital_sub",
        Vector(hugePath, hugePath),
        "SInt(4098)",
        None,
        None,
        Vector.empty
      )
      val checked = KernelDigitalExpressionSnapshot(
        captured.root + ".hugeChecked",
        captured.root,
        "digital_resize_checked",
        Vector(differencePath),
        "SInt(1)",
        None,
        None,
        Vector.empty
      )
      val baseline = ScalaToMlirBridge.fromSnapshot(captured).text
      rejected(captured.copy(digitalExpressions =
        captured.digitalExpressions ++ Vector(huge, difference, checked)
      ))
      assert(ScalaToMlirBridge.fromSnapshot(captured).text == baseline)

    test("ordinary Boolean aliases cannot seed a forged digital graph"):
      val captured = snapshot
      val predicate = captured.digitalExpressions.find(_.operation == "bool_not").get
      val orphan = predicate.copy(path = captured.root + ".orphanPredicate")
      rejected(captured.copy(digitalExpressions = captured.digitalExpressions :+ orphan))
      val analog = ConstructionKernel.inspect(new DigitalExpressionAnalogIsolationSource)
      assert(analog.digitalExpressions.isEmpty)

    test("signed logical shift zero preserves negativity and cannot prove unsigned conversion"):
      val captured = snapshot
      val zero = captured.digitalExpressions.find(value =>
        value.operation == "digital_logical_shr" &&
          captured.digitalExpressions.exists(input =>
            input.path == value.operands(1) && input.literal.contains("0")
          )
      ).get
      val forged = KernelDigitalExpressionSnapshot(
        captured.root + ".forgedUnsigned",
        captured.root,
        "digital_to_unsigned",
        Vector(zero.path),
        "UInt(4)",
        None,
        None,
        Vector.empty
      )
      rejected(captured.copy(digitalExpressions = captured.digitalExpressions :+ forged))

    test("aggregate selection retains complete shape and source Bool identity"):
      val captured = snapshot
      val selected = captured.digitalExpressions.find(value =>
        value.operation == "digital_select" && value.dataType.startsWith("Vec(")
      ).get
      rejected(changePath(captured, selected.path)(_.copy(dataType = "Vec(SInt(4),3,2)")))
      rejected(changePath(captured, selected.path)(_.copy(dataType = "Bits(24)")))
      rejected(changePath(captured, selected.path)(value =>
        value.copy(operands = value.operands.updated(1, s"${captured.root}.signed"))
      ))
      val condition = captured.digitalExpressions.find(_.operation == "bool_and").get
      rejected(changePath(captured, condition.path)(value =>
        value.copy(operands = value.operands.updated(1, s"${captured.root}.amount"))
      ))

    test("actual source output verifies and round trips through the native combinational boundary"):
      val compiler = sys.env.getOrElse(
        "NODAL_NODALC",
        scala.util.Failure[Nothing](
          new java.lang.AssertionError("configured nodalc is required")
        ).get
      )
      val directory = Path.of(".work", "increment55-digital-bridge").toAbsolutePath
      Files.createDirectories(directory)
      val document = ScalaToMlirBridge.lower(new DigitalCombinationalBridgeSource)
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
          scala.util.Failure[Nothing](new java.lang.AssertionError(
            s"${failure.diagnostic}\n${failure.standardError}"
          )).get
      val normalized = native(document)
      Files.writeString(directory.resolve("normalized.mlir"), normalized)
      assert(normalized.contains("nodal.digital_expr"))
      assert(native(document.copy(text = normalized)) == normalized)
