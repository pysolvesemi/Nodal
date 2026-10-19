package nodal

import scala.collection.mutable

/** Mutable records live only in the owning ConstructionSession transaction. */
private[nodal] final class FlatFsmStateRecord(val caseId: String, val ordinal: Int):
  var terminal = false
  var open = true

private[nodal] final case class FlatFsmTransitionRecord(
    sourceCase: String,
    destinationCase: String,
    mode: TransitionMode,
    guard: Expr[Bool],
    ordinal: Int
)

private[nodal] final case class FlatFsmActionHeader(
    machine: ExpressionRef,
    phase: String,
    ownerCase: Option[String],
    transitionOrdinal: Option[Int],
    ordinal: Int
)

private[nodal] final class FlatFsmRecord[S](
    val reference: ExpressionRef,
    val definition: FsmDefinition[S],
    val evidence: HwEnum[S],
    val canonical: ResolvedEnumAbi,
    val local: ResolvedEnumCodeMap,
    val initialCase: String,
    val encoding: FsmEncoding,
    val resolvedEncoding: String,
    val illegalState: IllegalStatePolicy,
    val domain: ClockDomain,
    val storage: Register[Bits],
    val nextLocal: KernelExpr[Bits],
    val taken: KernelExpr[Bool],
    val sourceOrdinal: Int
):
  var open = true
  var captureCount = 0
  private var ordinal = 0
  def nextOrdinal(): Int =
    val result = ordinal
    ordinal += 1
    result
  val states = mutable.ArrayBuffer.empty[FlatFsmStateRecord]
  val transitions = mutable.ArrayBuffer.empty[FlatFsmTransitionRecord]
  val actions = mutable.ArrayBuffer.empty[DigitalValueRegionRecord]
  val views =
    mutable.HashMap.empty[(Option[ExpressionRef], Boolean), DecodedEnum[Expr[EnumValue[S]]]]

private[nodal] final case class FlatFsmCompletionMetadata(record: FlatFsmRecord[?])
private[nodal] final case class FlatFsmObservation(record: FlatFsmRecord[?], future: Boolean)

private[nodal] final case class EnumSwitchArmHeader(switch: ExpressionRef, index: Int)
private[nodal] final class EnumSwitchRecord[S](
    val reference: ExpressionRef,
    val parent: Option[DigitalValueRegionRecord],
    val selector: Expr[EnumValue[S]],
    val canonical: ResolvedEnumAbi,
    val evidence: HwEnum[S],
    val sourceOrdinal: Int,
    val root: ExpressionRef
):
  var open = true
  var captureCount = 0
  val cases = mutable.ArrayBuffer.empty[(String, () => Unit)]
  val arms = mutable.ArrayBuffer.empty[DigitalValueRegionRecord]
