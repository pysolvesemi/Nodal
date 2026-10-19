package nodal.internal.testkit

import scala.compiletime.testing.typeCheckErrors

import nodal.*
import utest.*

enum DigitalSourceMode derives HwEnum:
  case Idle, Run, Stop

enum DigitalSourceOther derives HwEnum:
  case Idle, Run, Stop

object DigitalSourceNested:
  enum Mode derives HwEnum:
    case Idle, Run

object DigitalSourceIdentity:
  enum `A.B` derives HwEnum:
    case `One.Two`, `One%2ETwo`
  object A:
    enum B derives HwEnum:
      case One, Two

final class DigitalSymbolicDecodeSource(lower: Int, upper: Int) extends Module:
  val width = param(2.integer, range = lower to upper)
  val raw = in(Bits(width))
  val decoded = decodeEnum(raw, EnumType[DigitalSourceMode](), DigitalSourceMode.Idle)

enum DigitalSourceSingleton derives HwEnum:
  case Only

enum DigitalSourceMany derives HwEnum:
  case C00, C01, C02, C03, C04, C05, C06, C07, C08, C09
  case C10, C11, C12, C13, C14, C15, C16, C17, C18, C19
  case C20, C21, C22, C23, C24, C25, C26, C27, C28, C29
  case C30, C31, C32, C33, C34, C35, C36, C37, C38, C39
  case C40, C41, C42, C43, C44, C45, C46, C47, C48, C49
  case C50, C51, C52, C53, C54, C55, C56, C57, C58, C59
  case C60, C61, C62, C63, C64, C65, C66, C67, C68, C69

object Increment54EnumSourceTests extends TestSuite:
  private def failure(body: => Any): String =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException].diagnostic.code

  private def generic[A: HwEnum]: DataType[EnumValue[A]] = EnumType[A]()

  private def rawBits(width: Int): Expr[Bits] =
    new KernelExpr[Bits](
      Vector.empty,
      resultType = Some(KernelTypeDescriptor("Bits", Vector(width))),
      operation = Some("fixture_bits")
    )

  private def expression[A <: Data](value: Expr[A]): KernelExpr[A] =
    value.asInstanceOf[KernelExpr[A]]

  val tests: Tests = Tests:
    test("enum selection rejects direct and nested clock reset capabilities"):
      val selector = enumLiteral(EnumType[DigitalSourceSingleton](), DigitalSourceSingleton.Only)
      Vector[DataType[?]](
        Clock,
        Reset,
        Vec(Clock, 2),
        Struct("IllegalReset", StructField("reset", Reset))
      ).foreach: dataType =>
        val value = new KernelExpr[Data](
          Vector.empty,
          resultType = Some(CandidateRuntime.typeDescriptor(dataType)),
          operation = Some("fixture_capability")
        )
        assert(failure(selectEnum(selector, value)(DigitalSourceSingleton.Only -> value)) ==
          "NODAL-ENUM-054-017")

    test("native singleton derivation carries authored identity and declaration order"):
      val declaration = summon[HwEnum[DigitalSourceMode]].declaration.get
      assert(declaration.identity == "nodal.internal.testkit.DigitalSourceMode")
      assert(declaration.cases.map(_.name) == Vector("Idle", "Run", "Stop"))
      assert(declaration.cases.map(_.value) == DigitalSourceMode.values.toVector)
      assert(declaration.source.exists(_.line > 0))
      assert(declaration.cases.forall(_.source.nonEmpty))
      val nested = DigitalEnumContract.abi(EnumType[DigitalSourceNested.Mode]())
      assert(nested.identity == "nodal.internal.testkit.DigitalSourceNested.Mode")
      assert(DigitalEnumContract.abi(generic[DigitalSourceMode]).width == 2)

    test("authored punctuation cannot collide with qualified declaration boundaries"):
      val dotted = DigitalEnumContract.abi(EnumType[DigitalSourceIdentity.`A.B`]())
      val nested = DigitalEnumContract.abi(EnumType[DigitalSourceIdentity.A.B]())
      assert(dotted.identity.endsWith("DigitalSourceIdentity.A%2EB"))
      assert(nested.identity.endsWith("DigitalSourceIdentity.A.B"))
      assert(dotted.identity != nested.identity, dotted.displayName == "A.B")
      assert(dotted.cases.map(_.name) == Vector("One.Two", "One%2ETwo"))
      assert(dotted.cases.map(_.id) ==
        Vector(dotted.identity + ".One%2ETwo", dotted.identity + ".One%252ETwo"))
      assert(DigitalEnumContract.identityComponent("%.,()`") == "%25%2E%2C%28%29%60")

    test("canonical policies retain exact widths and arbitrary precision codes"):
      val sequential = DigitalEnumContract.abi(EnumType[DigitalSourceMode]())
      val gray = DigitalEnumContract.abi(EnumType.gray[DigitalSourceMode]())
      val oneHot = DigitalEnumContract.abi(EnumType.oneHot[DigitalSourceMode]())
      assert(sequential.width == 2, sequential.cases.map(_.code) == Vector(0, 1, 2))
      assert(gray.width == 2, gray.cases.map(_.code) == Vector(0, 1, 3))
      assert(oneHot.width == 3, oneHot.cases.map(_.code) == Vector(1, 2, 4))
      val singleton = DigitalEnumContract.abi(EnumType[DigitalSourceSingleton]())
      assert(singleton.width == 1, singleton.cases.head.code == 0)
      val many = DigitalEnumContract.abi(EnumType.oneHot[DigitalSourceMany]())
      assert(many.width == 70, many.cases.size == 70)
      assert(many.cases.last.code == (BigInt(1) << 69))
      val customWide = DigitalEnumContract.abi(EnumType.custom(
        70,
        enumEncoding(
          DigitalSourceMode.Idle -> BigInt(0),
          DigitalSourceMode.Run -> (BigInt(1) << 65),
          DigitalSourceMode.Stop -> (BigInt(1) << 69)
        )
      ))
      assert(customWide.cases.last.code == (BigInt(1) << 69))
      val padded = DigitalEnumContract.abi(EnumType.custom(
        1048576,
        enumEncoding(
          DigitalSourceMode.Idle -> BigInt(0),
          DigitalSourceMode.Run -> BigInt(1),
          DigitalSourceMode.Stop -> BigInt(2)
        )
      ))
      assert(padded.width == 1048576, padded.cases.last.code == 2)

    test("sparse custom exact width and reordered maps share deterministic ABI"):
      import DigitalSourceMode.*
      val first =
        EnumType.custom(3, enumEncoding(Idle -> BigInt(0), Run -> BigInt(3), Stop -> BigInt(7)))
      val second = EnumType.custom(
        3,
        EnumEncoding(Map(Stop -> BigInt(7), Idle -> BigInt(0), Run -> BigInt(3)))
      )
      val a = DigitalEnumContract.abi(first)
      val b = DigitalEnumContract.abi(second)
      assert(a.hash == b.hash, a.cases.map(_.code) == Vector(0, 3, 7))
      val padded = DigitalEnumContract.abi(EnumType.custom(
        8,
        EnumEncoding(Map(Idle -> BigInt(0), Run -> BigInt(3), Stop -> BigInt(7)))
      ))
      assert(padded.width == 8, padded.hash != a.hash)
      assert(Vector.fill(100)(
        DigitalEnumContract.abi(EnumType[DigitalSourceMode]()).hash
      ).distinct.size == 1)
      assert(DigitalEnumContract.abiHash(
        a.identity,
        a.width,
        a.cases.reverse.map(_.copy(source = None))
      ) == a.hash)
      assert(DigitalEnumContract.abiHash(
        a.identity,
        a.width,
        a.cases.map(c => c.copy(code = c.code + 1))
      ) != a.hash)
      assert(DigitalEnumContract.abiHash("different.Mode", a.width, a.cases) != a.hash)

    test("actual magnitude budget and UTF-8 hash framing are explicit"):
      val base = BigInt(1) << 250000
      val oversized = DigitalSourceMany.values.toVector.zipWithIndex.map((value, index) =>
        value -> (base + index)
      ).toMap
      assert(failure(EnumType.custom(250001, EnumEncoding(oversized))) == "NODAL-ENUM-054-003")
      val cases = Vector(
        ResolvedEnumCase("δοκιμή.Mode.Off", "Off", BigInt(0), None),
        ResolvedEnumCase("δοκιμή.Mode.On", "On", BigInt(2), None)
      )
      assert(DigitalEnumContract.abiHash("δοκιμή.Mode", 2, cases) ==
        "28ff2507d0f84285e8c21068c934a3d2e8a63283aad0ae2f27cf294b15f2d8ca")
      val crossLanguage = Vector(
        ResolvedEnumCase("example.Unicode.𐐀", "𐐀", BigInt(0), None),
        ResolvedEnumCase("example.Unicode.Ａ", "Ａ", BigInt(1), None)
      )
      assert(DigitalEnumContract.abiHash("example.Unicode", 1, crossLanguage) ==
        "631d26f986996ce889a07da74141f1191ee257af040ca1e4a5ad22842a0a64fb")

    test("custom map rejects duplicate missing foreign negative colliding and overflowing codes"):
      import DigitalSourceMode.*
      assert(failure(enumEncoding(Idle -> BigInt(0), Idle -> BigInt(1))) == "NODAL-ENUM-054-005")
      assert(failure(enumEncoding(Idle -> BigInt(1), Run -> BigInt(1))) == "NODAL-ENUM-054-010")
      assert(failure(enumEncoding(Idle -> BigInt(-1))) == "NODAL-ENUM-054-009")
      assert(failure(EnumType.custom(3, EnumEncoding(Map(Idle -> BigInt(0))))) ==
        "NODAL-ENUM-054-008")
      val foreign =
        Map[Any, BigInt](Idle -> BigInt(0), Run -> BigInt(1), DigitalSourceOther.Idle -> BigInt(2))
          .asInstanceOf[Map[DigitalSourceMode, BigInt]]
      assert(failure(EnumType.custom(3, EnumEncoding(foreign))) == "NODAL-ENUM-054-004")
      for codes <- Vector(Vector(-1, 1, 2), Vector(0, 1, 8)) do
        assert(failure(EnumType.custom(
          3,
          EnumEncoding(DigitalSourceMode.values.toVector.zip(codes.map(BigInt(_))).toMap)
        )) == "NODAL-ENUM-054-009")
      assert(failure(EnumType.custom(
        3,
        enumEncoding(Idle -> BigInt(0), Run -> BigInt(0), Stop -> BigInt(1))
      )) == "NODAL-ENUM-054-010")
      for width <- Vector(0, -1, Int.MaxValue) do
        assert(failure(EnumType.custom(
          width,
          EnumEncoding(Map.empty[DigitalSourceMode, BigInt])
        )) == "NODAL-ENUM-054-007")

    test("literal and safe decode retain typed operation identity and original validity operand"):
      import DigitalSourceMode.*
      val dataType = EnumType[DigitalSourceMode]()
      val literal = expression(enumLiteral(dataType, Run))
      val metadata = literal.operands.last.asInstanceOf[EnumOperationMetadata]
      assert(literal.operation.contains("enum_literal"))
      assert(literal.resultType.contains(CandidateRuntime.typeDescriptor(dataType)))
      assert(metadata.caseIds == Vector("nodal.internal.testkit.DigitalSourceMode.Run"))
      assert(!literal.operands.exists(_.isInstanceOf[DigitalSourceMode]))
      val bits = rawBits(2)
      val decoded = decodeEnum(bits, dataType, Idle)
      val value = expression(decoded.value)
      val valid = expression(decoded.valid)
      assert(value.operation.contains("enum_decode"), valid.operation.contains("enum_is_valid"))
      assert(value.operands.head.asInstanceOf[AnyRef] eq bits)
      assert(valid.operands.head.asInstanceOf[AnyRef] eq bits)
      assert(expression(decoded.value.asBits).resultType.contains(KernelTypeDescriptor(
        "Bits",
        Vector(2)
      )))
      assert(expression(decoded.value.isValid).operation.contains("enum_is_valid"))
      assert(expression(decoded.value.isOneOf(
        Idle,
        Run
      )).operands.last.asInstanceOf[EnumOperationMetadata].caseIds.size == 2)
      assert(expression(decoded.value === literal).operation.contains("enum_eq"))
      assert(failure(decodeEnum(rawBits(3), dataType, Idle)) == "NODAL-ENUM-054-013")
      val unsigned = 3.U(2)
      val decodedUnsigned = decodeEnum(unsigned, dataType, Idle)
      assert(expression(decodedUnsigned.value).operands.head.asInstanceOf[AnyRef] eq unsigned)
      assert(expression(decodedUnsigned.valid).operands.head.asInstanceOf[AnyRef] eq unsigned)
      assert(failure(decodeEnum(3.U(3), dataType, Idle)) == "NODAL-ENUM-054-013")
      val foreignType =
        EnumType[DigitalSourceOther]().asInstanceOf[DataType[EnumValue[DigitalSourceMode]]]
      assert(failure(enumLiteral(foreignType, Idle)) == "NODAL-ENUM-054-004")
      assert(failure(decodeEnum(bits, foreignType, Idle)) == "NODAL-ENUM-054-004")
      assert(failure(literal === enumLiteral(EnumType.oneHot[DigitalSourceMode](), Run)) ==
        "NODAL-ENUM-054-014")

    test("decode width is proven over the entire structural parameter range"):
      val snapshot = ConstructionKernel.inspect(new DigitalSymbolicDecodeSource(2, 2))
      assert(snapshot.digitalExpressions.exists(_.operation == "enum_decode"))
      assert(snapshot.digitalExpressions.exists(_.operation == "enum_is_valid"))
      assert(failure(ConstructionKernel.inspect(new DigitalSymbolicDecodeSource(1, 3))) ==
        "NODAL-ENUM-054-013")

    test("complete selection canonicalizes entry order and requires exact result types"):
      import DigitalSourceMode.*
      val selector = enumLiteral(EnumType[DigitalSourceMode](), Idle)
      val selected =
        expression(selectEnum(selector, 0.U(8))(Stop -> 3.U(8), Idle -> 1.U(8), Run -> 2.U(8)))
      assert(selected.operation.contains("enum_select"))
      assert(selected.resultType.contains(KernelTypeDescriptor("UInt", Vector(8))))
      assert(selected.operands.slice(2, 5).map(_.asInstanceOf[KernelExpr[?]].literal.get.value) ==
        Vector("1", "2", "3"))
      assert(failure(selectEnum(selector, 0.U(8))(Idle -> 1.U(8))) == "NODAL-ENUM-054-016")
      assert(failure(selectEnum(
        selector,
        0.U(8)
      )(Idle -> 1.U(8), Run -> 2.U(8), Stop -> 3.U(8), Idle -> 4.U(8))) == "NODAL-ENUM-054-015")
      assert(failure(selectEnum(selector, 0.U(8))(Idle -> 1.U(7), Run -> 2.U(8), Stop -> 3.U(8))) ==
        "NODAL-ENUM-054-017")

    test("identical enum result ABI ignores policy and source span differences"):
      import DigitalSourceMode.*
      val sequential = EnumType[DigitalSourceMode]()
      val custom =
        EnumType.custom(2, enumEncoding(Idle -> BigInt(0), Run -> BigInt(1), Stop -> BigInt(2)))
      val selector = enumLiteral(sequential, Idle)
      val result = selectEnum(selector, enumLiteral(sequential, Stop))(
        Idle -> enumLiteral(custom, Run),
        Run -> enumLiteral(sequential, Stop),
        Stop -> enumLiteral(custom, Idle)
      )
      assert(expression(result).operation.contains("enum_select"))
      assert(DigitalEnumContract.abi(result).hash == DigitalEnumContract.abi(custom).hash)
      val left = CandidateRuntime.typeDescriptor(Vec(UInt(8), 4))
      val right = CandidateRuntime.typeDescriptor(Vec(UInt(8), 4))
      assert(DigitalEnumContract.sameType(left, right))
      assert(!DigitalEnumContract.sameType(left, CandidateRuntime.typeDescriptor(Vec(UInt(7), 4))))

    test("shared structural result types compare by distinct node pairs"):
      def tree(): DataType[Data] =
        var result: DataType[Data] = UInt(8)
        for _ <- 0 until 18 do
          result = Struct("Pair", StructField("left", result), StructField("right", result))
        result
      assert(DigitalEnumContract.sameType(
        CandidateRuntime.typeDescriptor(tree()),
        CandidateRuntime.typeDescriptor(tree())
      ))

    test("manual empty evidence and frozen case classes stay source compatible"):
      val compatible = typeCheckErrors("""
        import nodal.*
        final class Manual
        given HwEnum[Manual] = new HwEnum[Manual] {}
        val encoding = EnumEncoding(Map.empty[Manual, BigInt])
        val EnumEncoding(values) = encoding.copy()
        def legacy(bits: Expr[Bits], default: Manual): DecodedEnum[Manual] = decodeEnum(bits, default)
        def destructure(value: DecodedEnum[Manual]) = { val DecodedEnum(v, valid) = value.copy(); (v, valid) }
        val product: Product = encoding
      """)
      assert(compatible.isEmpty)
      final class Manual
      given HwEnum[Manual] = new HwEnum[Manual] {}
      assert(failure(EnumType[Manual]()) == "NODAL-ENUM-054-002")
      assert(failure(decodeEnum(rawBits(2), DigitalSourceMode.Idle)) == "NODAL-ENUM-054-012")

    test("unsupported derivations and cross-enum expressions fail at compile time"):
      val payload = typeCheckErrors("""
        import nodal.*
        enum Payload derives HwEnum { case Value(value: Int) }
      """)
      val sealedSum = typeCheckErrors("""
        import nodal.*
        sealed trait Sum derives HwEnum
        case object Value extends Sum
      """)
      val genericEnum = typeCheckErrors("""
        import nodal.*
        enum Generic[A] derives HwEnum { case Value(value: A) }
      """)
      val local = typeCheckErrors("""
        import nodal.*
        def local = { enum Local derives HwEnum { case A, B }; EnumType[Local]() }
      """)
      val pathDependent = typeCheckErrors("""
        import nodal.*
        class Holder { enum Local derives HwEnum { case A, B } }
      """)
      val cross = typeCheckErrors("""
        import nodal.*
        import nodal.internal.testkit.*
        val a = enumLiteral(EnumType[DigitalSourceMode](), DigitalSourceMode.Idle)
        val b = enumLiteral(EnumType[DigitalSourceOther](), DigitalSourceOther.Idle)
        val impossible = a === b
      """)
      val bitsImplicit = typeCheckErrors("""
        import nodal.*
        import nodal.internal.testkit.*
        def wrong(value: Expr[Bits]): Expr[EnumValue[DigitalSourceMode]] = value
      """)
      for errors <- Vector(payload, sealedSum, genericEnum, local, pathDependent) do
        assert(errors.exists(_.message.contains("NODAL-ENUM-054-001")))
      assert(cross.nonEmpty, bitsImplicit.nonEmpty)
