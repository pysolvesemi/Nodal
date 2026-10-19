package nodal

import scala.annotation.targetName
import scala.deriving.Mirror

/** Native singleton-enum metadata. Empty manual instances remain source-compatible. */
trait HwEnum[A]:
  private[nodal] def declaration: Option[EnumDeclaration[A]] = None

object HwEnum:
  inline def derived[A](using mirror: Mirror.SumOf[A]): HwEnum[A] =
    ${ DigitalEnumDerivation.derived[A]('mirror) }

  private[nodal] def fromDeclaration[A](value: EnumDeclaration[A]): HwEnum[A] =
    new HwEnum[A]:
      override private[nodal] val declaration: Option[EnumDeclaration[A]] = Some(value)

/** Explicit map specification retained unchanged from the frozen F15 surface. */
final case class EnumEncoding[A](values: Map[A, BigInt])

def enumEncoding[A](values: (A, BigInt)*)(using evidence: HwEnum[A]): EnumEncoding[A] =
  DigitalEnumContract.encoding(values.toVector, evidence)

/** The value can be a host value for source compatibility or a real hardware expression. */
final case class DecodedEnum[A](value: A, valid: Expr[Bool])

/** The frozen host-valued signature cannot perform hardware decoding. */
def decodeEnum[A](bits: Expr[Bits], default: A)(using evidence: HwEnum[A]): DecodedEnum[A] =
  val _ = evidence
  DigitalEnumContract.legacyDecode(bits, default)

object EnumType:
  def apply[A]()(using evidence: HwEnum[A]): DataType[EnumValue[A]] =
    DigitalEnumContract.dataType(evidence, "sequential")

  def oneHot[A]()(using evidence: HwEnum[A]): DataType[EnumValue[A]] =
    DigitalEnumContract.dataType(evidence, "one_hot")

  def gray[A]()(using evidence: HwEnum[A]): DataType[EnumValue[A]] =
    DigitalEnumContract.dataType(evidence, "gray")

  def custom[A](width: Int, encoding: EnumEncoding[A])(using
      evidence: HwEnum[A]
  )
      : DataType[EnumValue[A]] =
    DigitalEnumContract.dataType(evidence, "custom", Some(width -> encoding))

def enumLiteral[A](enumType: DataType[EnumValue[A]], value: A)(using
    evidence: HwEnum[A]
)
    : Expr[EnumValue[A]] = DigitalEnumContract.literal(enumType, value, evidence)

def decodeEnum[A](
    bits: Expr[Bits],
    enumType: DataType[EnumValue[A]],
    defaultCase: A
)(using evidence: HwEnum[A]): DecodedEnum[Expr[EnumValue[A]]] =
  DigitalEnumContract.decode(bits, enumType, defaultCase, evidence)

extension [A](value: Expr[EnumValue[A]])
  def asBits: Expr[Bits] = DigitalEnumContract.asBits(value)
  def isValid: Expr[Bool] = DigitalEnumContract.isValid(value)
  def isOneOf(cases: A*)(using evidence: HwEnum[A]): Expr[Bool] =
    DigitalEnumContract.isOneOf(value, cases.toVector, evidence)

  @targetName("enumEquality")
  infix def ===(other: Expr[EnumValue[A]]): Expr[Bool] =
    DigitalEnumContract.equal(value, other)

def selectEnum[A, B <: Data](selector: Expr[EnumValue[A]], invalidResult: Expr[B])(
    cases: (A, Expr[B])*
)(using evidence: HwEnum[A]): Expr[B] =
  DigitalEnumContract.select(selector, invalidResult, cases.toVector, evidence)
