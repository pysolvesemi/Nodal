package nodal

/** Address-unit convention for a programmer-visible register map. */
enum AddressUnit:
  case Byte, Word

/** Register-map byte ordering for multi-byte and multiword values. */
enum Endianness:
  case Little, Big

/** Software-visible field access semantics. */
enum SoftwareAccess:
  case RO
  case RW
  case WO
  case W1C
  case W1S
  case W1T
  case W0C
  case W0S
  case RC
  case RS
  case WriteOnce
  case Reserved

/** Hardware-side update capability, independent of software access. */
enum HardwareAccess:
  case None
  case Input
  case Write
  case Settable
  case Clearable
  case Increment
  case Decrement
  case Pulse

/** Priority when software and hardware update one field in the same cycle. */
enum CollisionPolicy:
  case HardwareWins
  case SoftwareWins
  case SetDominatesClear
  case ClearDominatesSet
  case ErrorOnCollision

/** Behavior for accesses that do not resolve to a legal register operation. */
enum IllegalAccessPolicy:
  case ErrorResponse
  case ReadZeroIgnoreWrite
  case ReadOnesIgnoreWrite

/** Policy for writes that enable only part of a mapped field or register. */
enum PartialWritePolicy:
  case Allow
  case Reject
  case RequireWholeField
  case RequireWholeRegister

/** Coherency policy for a register wider than its access transport. */
enum MultiwordAccess:
  case NonAtomic
  case SnapshotOnFirstRead
  case ShadowThenCommit
  case ProtocolAtomic
  case Rejected

/** Explicit field range with the most-significant bit first. */
final case class BitRange(high: Int, low: Int)

extension (high: Int)
  infix def downto(low: Int): BitRange = BitRange(high, low)

/** Constant register-map offset accepted directly from Scala integer literals. Symbolic hardware
  * expressions are deliberately excluded.
  */
type RegisterOffset = Int | Long | BigInt

/** Constant byte/word extent accepted directly from Scala integer literals. */
type RegisterSize = Int | Long | BigInt

/** Field bit selection accepted from either one bit or an explicit range. */
type FieldBits = Int | BitRange

/** Sentinel used when a field intentionally has no declared reset value. */
object FieldReset:
  case object Unspecified

/** Optional reset expression without Option or implicit-conversion ceremony. */
type FieldReset[A <: Data] = Expr[A] | FieldReset.Unspecified.type

/** Static or symbolic repetition count for a register-map array. */
type RegisterCount = Int | Expr[Integer]

/** Immutable bus-neutral programmer-visible register specification.
  *
  * This Increment 116 surface records source forms only; it does not yet build canonical Register
  * IR or generate hardware.
  */
abstract class RegisterMap(
    val name: String,
    val dataWidth: Int = 32,
    val addressUnit: AddressUnit = AddressUnit.Byte,
    val endianness: Endianness = Endianness.Little,
    val illegalAccess: IllegalAccessPolicy = IllegalAccessPolicy.ErrorResponse
):
  final class Field[A <: Data] private[nodal] (
      val register: Register,
      val name: String,
      val dataType: DataType[A],
      val bits: FieldBits,
      val software: SoftwareAccess,
      val reset: FieldReset[A],
      val hardware: HardwareAccess,
      val collision: CollisionPolicy,
      val partialWrite: PartialWritePolicy,
      val doc: String
  ):
    CandidateRuntime.statement(
      register,
      name,
      dataType,
      bits,
      software,
      reset,
      hardware,
      collision,
      partialWrite,
      doc
    )

  final class Register private[nodal] (
      val offset: RegisterOffset,
      val name: String,
      val doc: String,
      val multiword: MultiwordAccess
  ):
    CandidateRuntime.statement(offset, name, doc, multiword)

    def field[A <: Data](
        name: String,
        dataType: DataType[A],
        bits: FieldBits,
        software: SoftwareAccess,
        reset: FieldReset[A] = FieldReset.Unspecified,
        hardware: HardwareAccess = HardwareAccess.None,
        collision: CollisionPolicy = CollisionPolicy.HardwareWins,
        partialWrite: PartialWritePolicy = PartialWritePolicy.RequireWholeField,
        doc: String = ""
    ): Field[A] =
      new Field(
        this,
        name,
        dataType,
        bits,
        software,
        reset,
        hardware,
        collision,
        partialWrite,
        doc
      )

    def reserved(bits: FieldBits, doc: String = ""): Unit =
      CandidateRuntime.statement(this, bits, doc)

  final class Submap[M <: RegisterMap] private[nodal] (
      val offset: RegisterOffset,
      val map: M,
      val name: String
  ):
    CandidateRuntime.statement(offset, map, name)

  final class RegisterArray[M <: RegisterMap] private[nodal] (
      val base: RegisterOffset,
      val count: RegisterCount,
      val stride: RegisterSize,
      val element: M,
      val name: String
  ):
    CandidateRuntime.statement(base, count, stride, element, name)

  final class RegisterWindow private[nodal] (
      val offset: RegisterOffset,
      val size: RegisterSize,
      val name: String
  ):
    CandidateRuntime.statement(offset, size, name)

  final class RegisterAlias private[nodal] (
      val offset: RegisterOffset,
      val target: Register,
      val name: String,
      val software: SoftwareAccess
  ):
    CandidateRuntime.statement(offset, target, name, software)

  final class SnapshotGroup private[nodal] (
      val name: String,
      val fields: Seq[Field[? <: Data]]
  ):
    CandidateRuntime.statement(name, fields)

  final class CommitGroup private[nodal] (
      val name: String,
      val fields: Seq[Field[? <: Data]]
  ):
    CandidateRuntime.statement(name, fields)

  protected final def param[A <: Data](default: Expr[A]): Param[A] =
    new Param(default)

  protected final def register(
      offset: RegisterOffset,
      name: String,
      doc: String = "",
      multiword: MultiwordAccess = MultiwordAccess.NonAtomic
  ): Register = new Register(offset, name, doc, multiword)

  protected final def reserved(
      offset: RegisterOffset,
      size: RegisterSize,
      doc: String = ""
  ): Unit = CandidateRuntime.statement(offset, size, doc)

  protected final def submap[M <: RegisterMap](
      offset: RegisterOffset,
      map: M,
      name: String
  ): Submap[M] = new Submap(offset, map, name)

  protected final def array[M <: RegisterMap](
      base: RegisterOffset,
      count: RegisterCount,
      stride: RegisterSize,
      element: M,
      name: String
  ): RegisterArray[M] = new RegisterArray(base, count, stride, element, name)

  protected final def window(
      offset: RegisterOffset,
      size: RegisterSize,
      name: String
  ): RegisterWindow = new RegisterWindow(offset, size, name)

  protected final def alias(
      offset: RegisterOffset,
      target: Register,
      name: String,
      software: SoftwareAccess
  ): RegisterAlias = new RegisterAlias(offset, target, name, software)

  protected final def snapshot(
      name: String,
      fields: Field[? <: Data]*
  ): SnapshotGroup = new SnapshotGroup(name, fields.toSeq)

  protected final def commitGroup(
      name: String,
      fields: Field[? <: Data]*
  ): CommitGroup = new CommitGroup(name, fields.toSeq)

/** Opaque canonical register-access endpoint passed only to transport adapters. */
final class RegisterAccessPort private[nodal] ()

/** Capabilities that a bus adapter declares before attachment. */
final case class RegisterTransportCapabilities(
    dataWidth: Int,
    addressWidth: Int,
    byteEnable: Boolean,
    errorResponse: Boolean,
    protection: Boolean,
    backpressure: Boolean,
    maxOutstanding: Int = 1,
    inOrder: Boolean = true
)

/** Scala 3 type-class boundary for built-in and external access transports. */
trait RegisterTransport[B]:
  def capabilities(bus: B): RegisterTransportCapabilities
  def connect(bus: B, endpoint: RegisterAccessPort): Unit

/** Writable hardware-side binding for a map-owned field. */
final class RegisterFieldInput[A <: Data] private[nodal] (field: Any):
  CandidateRuntime.statement(field)

  infix def :=(value: Expr[A]): Unit = CandidateRuntime.statement(field, value)

/** One physical stateful realization of an immutable RegisterMap. */
final class RegisterBlock[M <: RegisterMap] private[nodal] (val map: M):
  private val endpoint = new RegisterAccessPort()

  def value[A <: Data](field: map.Field[A]): Expr[A] =
    CandidateRuntime.expr(this, field)

  def input[A <: Data](field: map.Field[A]): RegisterFieldInput[A] =
    new RegisterFieldInput(field)

  def setWhen(field: map.Field[Bool], condition: Expr[Bool]): Unit =
    CandidateRuntime.statement(field, condition)

  def clearWhen(field: map.Field[Bool], condition: Expr[Bool]): Unit =
    CandidateRuntime.statement(field, condition)

  def incrementWhen(field: map.Field[UInt], condition: Expr[Bool]): Unit =
    CandidateRuntime.statement(field, condition)

  def decrementWhen(field: map.Field[UInt], condition: Expr[Bool]): Unit =
    CandidateRuntime.statement(field, condition)

  def pulse(field: map.Field[Bool]): Pulse =
    Pulse(CandidateRuntime.expr(this, field))

  def writeEvent[A <: Data](field: map.Field[A]): Pulse =
    Pulse(CandidateRuntime.expr(this, field))

  def capture(group: map.SnapshotGroup, condition: Expr[Bool]): Unit =
    CandidateRuntime.statement(group, condition)

  def commit(group: map.CommitGroup, condition: Expr[Bool]): Unit =
    CandidateRuntime.statement(group, condition)

  def attach[B](bus: B)(using transport: RegisterTransport[B]): Unit =
    CandidateRuntime.statement(transport.capabilities(bus))
    transport.connect(bus, endpoint)

object RegisterBlock:
  def apply[M <: RegisterMap](map: M): RegisterBlock[M] = new RegisterBlock(map)
