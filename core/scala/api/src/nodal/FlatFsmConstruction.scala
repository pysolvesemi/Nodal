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
    val sourceOrdinal: Int,
    val lifecycle: Option[FlatFsmLifecycleRecord]
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

  // Queries run after graph capture closes. The graph owner retains the exact structural
  // priority prefixes used by normalization, including Exclusive transition selection.
  private lazy val samplingOutgoing = transitions.toVector.groupBy(_.sourceCase)
  private lazy val samplingIndices = samplingOutgoing.valuesIterator.flatMap(_.zipWithIndex)
    .map((edge, index) => edge.ordinal -> (edge.sourceCase, index)).toMap
  private lazy val samplingIncoming = transitions.toVector.groupBy(_.destinationCase)

  def controlInputs: Vector[Expr[Bool]] = lifecycle.toVector.flatMap(value =>
    Vector(value.control.restart, value.control.cancel, value.control.enable)
  )
  def privateStorage: Vector[Register[Bits]] = Vector(storage) ++ lifecycle.map(_.storage)
  private def stateAndControls: Vector[Any] = privateStorage ++ controlInputs
  lazy val futureSampleDependencies: Vector[Any] = stateAndControls ++ transitions.map(_.guard)

  def actionSampleDependencies(header: FlatFsmActionHeader): Vector[Any] =
    val selected = header.phase match
      case "transition" =>
        Vector(samplingIndices.getOrElse(
          header.transitionOrdinal.get,
          FlatFsmRuntime.fail("action has no actual transition")
        ))
      case "exit" =>
        samplingOutgoing.getOrElse(header.ownerCase.get, Vector.empty).lastOption.toVector
          .map(edge => samplingIndices(edge.ordinal))
      case "entry" =>
        samplingIncoming.getOrElse(header.ownerCase.get, Vector.empty)
          .map(edge => samplingIndices(edge.ordinal))
      case "active" => Vector.empty
      case _ => FlatFsmRuntime.fail("action has no known sampling phase")
    val prefixes = mutable.LinkedHashMap.empty[String, Int]
    selected.foreach: (source, index) =>
      prefixes.update(source, prefixes.getOrElse(source, -1).max(index))
    val guards = prefixes.iterator.flatMap((source, last) =>
      samplingOutgoing(source).iterator.take(last + 1).map(_.guard)
    ).toVector
    stateAndControls ++ guards

private[nodal] final case class FlatFsmLifecycleRecord(
    storage: Register[Bits],
    control: FsmControl,
    entered: KernelExpr[Bool],
    exited: KernelExpr[Bool]
)

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
