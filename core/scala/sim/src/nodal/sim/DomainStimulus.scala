package nodal.sim

import scala.collection.mutable
import scala.util.control.NonFatal

import nodal.*
import nodal.internal.bridge.ReproducibilityContract

/** Explicit electrical interpretation of a digital clock or reset. */
final case class ElectricalLevels(
    low: Quantity[VoltageDimension],
    high: Quantity[VoltageDimension]
)

/** Finite, strictly ordered electrical PWL points, suitable for a typed voltage source. */
final case class VoltageWaveform(
    points: Vector[(Quantity[TimeDimension], Quantity[VoltageDimension])]
):
  require(points.nonEmpty && points.size <= 100000, "waveform requires one to 100000 points")
  require(points.forall(_._1.siValue >= 0.0), "waveform times must be nonnegative")
  require(
    points.iterator.map(_._1.siValue).sliding(2).forall(values =>
      values.size < 2 || values.head < values.last
    ),
    "waveform times must be strictly increasing"
  )

/** Declared timing relation, retained independently of the resulting numeric frequency. */
enum HarnessClockRelation:
  case Independent, Same
  case Ratio(multiply: Int, divide: Int, phaseDegrees: Double)

/** Capture-owned immutable domain facts; generated domains retain their actual parent. Missing
  * generated edge/reset polarity/reset policy metadata inherits from that parent for this
  * electrical reference projection only. The clock source expression is not executed as hardware.
  */
final class HarnessDomain private[sim] (
    val semanticPath: String,
    val parent: Option[HarnessDomain],
    val relation: HarnessClockRelation,
    val frequencyHz: Double,
    val edge: ClockEdge,
    val resetPolicy: ResetPolicy,
    val resetPolarity: ResetPolarity,
    val phaseDegrees: Double,
    val clockSource: Option[String],
    val resetSource: String,
    val rawResetSource: String,
    val synchronizationStages: Option[Int],
    private[sim] val owner: Object
)

/** Origin is a reference active-edge midpoint, with periodic prehistory through time zero. Related
  * domains obtain their origin from their actual parent and must omit it here.
  */
final case class ClockDrive(
    domain: HarnessDomain,
    levels: ElectricalLevels,
    riseTime: Quantity[TimeDimension],
    fallTime: Quantity[TimeDimension],
    dutyCycle: Double = 0.5,
    origin: Option[Quantity[TimeDimension]] = None
)

/** One raw boundary reset pulse. Times name transition midpoints, independent of synchronizers. */
final case class ResetDrive(
    domain: HarnessDomain,
    levels: ElectricalLevels,
    assertAt: Quantity[TimeDimension],
    releaseAt: Quantity[TimeDimension],
    riseTime: Quantity[TimeDimension],
    fallTime: Quantity[TimeDimension]
)

final case class ClockStimulus(domain: HarnessDomain, waveform: VoltageWaveform)

/** Reference observations only: no digital reset implementation is executed by these calculations.
  * Policy and Rdc.sync observations are independently derived from the raw boundary pulse. They are
  * deliberately neither added together nor substituted for the raw voltage waveform.
  */
final case class ResetReference(
    rawAssertAt: Quantity[TimeDimension],
    rawReleaseAt: Quantity[TimeDimension],
    policyAssertAt: Quantity[TimeDimension],
    policyReleaseAt: Quantity[TimeDimension],
    stagedReleaseAt: Option[Quantity[TimeDimension]]
)

final case class ResetStimulus(
    domain: HarnessDomain,
    source: String,
    waveform: VoltageWaveform,
    reference: ResetReference
)

final case class DomainStimulus(clocks: Vector[ClockStimulus], resets: Vector[ResetStimulus])

/** A stimulus-only capture, separate from the analog DUT capture. */
final class DomainHarness private[sim] (
    val domains: Vector[HarnessDomain],
    private val owner: Object,
    private[sim] val snapshot: ConstructionSnapshot
):
  val sourceMap: Vector[SourceMapEntry] = snapshot.sourceMap
  val canonicalConstruction: String = ReproducibilityContract.canonicalSnapshot(snapshot)

  /** Every requested clock and any parents needed as phase anchors must have explicit drives.
    * Equal-frequency independent domains still require separate origins. The inclusive sampling
    * convention observes a reset midpoint exactly on a clock edge at that edge. This is a testbench
    * reference convention, not a guarantee about HDL scheduling or metastability.
    */
  def stimulus(
      until: Quantity[TimeDimension],
      clocks: Seq[ClockDrive],
      resets: Seq[ResetDrive] = Seq.empty
  ): Either[SimulationError, DomainStimulus] = DomainHarness.checked("SIM-DOMAIN-STIMULUS"):
    DomainWaveforms.generate(
      owner,
      until.siValue,
      clocks.iterator.take(33).toVector,
      resets.iterator.take(33).toVector
    )

object DomainHarness:
  private[sim] def reject(message: String): Nothing =
    scala.util.Failure[Nothing](new IllegalArgumentException(message)).get

  private[sim] def checked[A](code: String)(body: => A): Either[SimulationError, A] =
    try Right(body)
    catch
      case NonFatal(error) =>
        Left(SimulationError(code, Option(error.getMessage).getOrElse(error.getClass.getName)))

  private def requireStimulusOnly(captured: KernelSelectedCapture): Unit =
    val snapshot = captured.snapshot
    val semantics = snapshot.analogSemantics
    require(
      captured.operationKinds.forall(_ == "metadata"),
      "stimulus harness cannot contain executable source effects"
    )
    require(
      snapshot.analogRegions.isEmpty && snapshot.continuousOperators.isEmpty &&
        snapshot.waveformOperators.isEmpty && snapshot.noiseOperators.isEmpty &&
        snapshot.transferOperators.isEmpty && snapshot.analogFunctions.isEmpty &&
        snapshot.analogProcedural.isEmpty && semantics.equations.isEmpty &&
        semantics.contributions.isEmpty && semantics.components.isEmpty &&
        semantics.states.isEmpty && semantics.parameters.isEmpty && semantics.unsupported.isEmpty,
      "stimulus harness cannot contain analog equations, operators, functions, or effects"
    )
    require(
      snapshot.interfaceAbi.isEmpty && snapshot.resolvedNets.isEmpty &&
        snapshot.topology.isEmpty && snapshot.generatedRegions.isEmpty &&
        snapshot.shapeIndices.isEmpty && snapshot.shapeViews.isEmpty &&
        snapshot.conservativeArrayBindings.isEmpty && snapshot.instanceArrayRoots.isEmpty &&
        snapshot.waivers.isEmpty && snapshot.rootParameterBindings.isEmpty &&
        snapshot.parameterExpressions.isEmpty,
      "stimulus harness cannot contain topology, generated logic, interfaces, or parameter bindings"
    )
    val selectedPaths = captured.references.map(_.path).toSet
    require(
      snapshot.modules.flatMap(_.declarations).forall(declaration =>
        declaration.kind == "input" &&
          declaration.dataType.exists(Set("Clock", "Reset").contains) &&
          selectedPaths.contains(declaration.path) && declaration.conservativeDimensions.isEmpty &&
          declaration.attributes.isEmpty
      ),
      "stimulus harness admits only Clock/Reset input declarations used by the selected domain closure"
    )
    require(
      snapshot.modules.flatMap(_.instances).forall(_.parameterBindings.isEmpty),
      "stimulus harness cannot contain instance parameter bindings"
    )

  /** Select domains inside one construction transaction. No source handle or callback survives. */
  def capture[M <: Module](top: => M)(
      select: M => Seq[ClockDomain]
  ): Either[SimulationError, DomainHarness] = checked("SIM-DOMAIN-CAPTURE"):
    var selectedCount = 0
    val captured = ConstructionKernel.capture(top): module =>
      val selected = select(module).iterator.take(33).toVector
      require(selected.nonEmpty && selected.size <= 32, "select between one and 32 domains")
      require(selected.distinct.size == selected.size, "duplicate selected domain")
      selectedCount = selected.size
      val closure = mutable.LinkedHashSet.empty[ClockDomain]
      def visit(domain: ClockDomain): Unit =
        if !closure.contains(domain) then
          require(closure.size < 32, "domain parent closure exceeds 32 domains")
          closure += domain
          domain.parent match
            case Some(parent) =>
              domain.relation match
                case Some(ClockRelation.Same) => ()
                case Some(ClockRelation.Ratio(multiply, divide, _)) =>
                  require(multiply > 0 && divide > 0, "clock ratio must be positive")
                case _ =>
                  require(false, "unsupported generated clock relation, gate, or mux")
              visit(parent)
            case None =>
              require(
                domain.frequency.nonEmpty && domain.edge.nonEmpty &&
                  domain.resetPolicy.nonEmpty && domain.resetPolarity.nonEmpty,
                "required, unbound, or unknown clock domains are unsupported"
              )
      selected.foreach(visit)
      val expressions = mutable.LinkedHashSet.empty[AnyRef]
      def expression(value: AnyRef): Unit =
        if !expressions.contains(value) then
          require(expressions.size < 256, "domain source closure exceeds 256 references")
          expressions += value
          value match
            case node: KernelExpr[?] =>
              node.operands.foreach:
                case operand: Expr[?] => expression(operand)
                case _ => ()
            case _ => ()
      closure.foreach: domain =>
        expression(domain.reset)
        domain.clock.foreach: clock =>
          require(
            !clock.isInstanceOf[KernelExpr[?]],
            "computed clock sources require an explicit model"
          )
          expression(clock)
      selected.map(value => value: AnyRef) ++ closure.toVector.map(value => value: AnyRef) ++
        expressions.toVector
    requireStimulusOnly(captured)
    val references = captured.references.map(value => value.path -> value).toMap
    val snapshots =
      captured.snapshot.modules.flatMap(_.domains).map(value => value.path -> value).toMap
    val declarations = captured.snapshot.modules.flatMap(_.declarations).map(_.path).toSet
    val owner = new Object
    val built = mutable.Map.empty[String, HarnessDomain]
    val building = mutable.Set.empty[String]
    def resetFacts(path: String, domain: String): (String, Option[Int]) =
      val reference = references.getOrElse(
        path,
        DomainHarness.reject("reset source metadata is unavailable")
      )
      if declarations.contains(path) then (path, None)
      else
        reference.operation match
          case Some("external_reset") => (path, None)
          case Some("rdc_sync") =>
            val (source, stages) = reference.operands match
              case Vector(
                    KernelSelectedOperand.Reference(source),
                    KernelSelectedOperand.Literal("integer", stages)
                  ) => (source, stages.toInt)
              case Vector(
                    KernelSelectedOperand.Reference(source),
                    KernelSelectedOperand.Reference(destination),
                    KernelSelectedOperand.Literal("integer", stages)
                  ) =>
                require(destination == domain, "Rdc.sync destination differs from receiving domain")
                (source, stages.toInt)
              case _ => DomainHarness.reject("unsupported reset synchronization source")
            require(stages >= 1, "reset synchronization stages must be positive")
            val raw = resetFacts(source, domain)
            require(
              raw._2.isEmpty,
              "cascaded reset synchronizers require an explicit digital model"
            )
            (raw._1, Some(stages))
          case _ => DomainHarness.reject("unsupported reset source")
    def descriptor(path: String): HarnessDomain = built.getOrElseUpdate(
      path, {
        require(!building.contains(path), "cyclic clock parent relation")
        building += path
        val snapshot = snapshots.getOrElse(
          path,
          DomainHarness.reject("selected domain does not belong to this capture")
        )
        val attributes = snapshot.attributes.toMap
        val parent = attributes.get("clock_parent").map(descriptor)
        val relation = attributes.get("clock_relation") match
          case Some("alias") => HarnessClockRelation.Same
          case Some("ratio") => HarnessClockRelation.Ratio(
              attributes("clock_multiply").toInt,
              attributes("clock_divide").toInt,
              attributes("phase_degrees").toDouble
            )
          case None if parent.isEmpty => HarnessClockRelation.Independent
          case _ => DomainHarness.reject("unsupported clock timing relation")
        val frequency = parent match
          case Some(value) => attributes.get("clock_relation") match
              case Some("alias") => value.frequencyHz
              case Some("ratio") =>
                value.frequencyHz *
                  (attributes("clock_multiply").toDouble /
                    attributes("clock_divide").toDouble)
              case _ => DomainHarness.reject("unsupported related-clock timing")
          case None => attributes("frequency_hz").toDouble
        DomainWaveforms.positive(frequency, "clock frequency")
        val edge = snapshot.edge.map:
          case "rising" => ClockEdge.Rising
          case "falling" => ClockEdge.Falling
          case _ => DomainHarness.reject("unknown clock edge")
        val policy = snapshot.resetPolicy.map:
          case "none" => ResetPolicy.None
          case "sync" => ResetPolicy.Sync
          case "async" => ResetPolicy.Async
          case "async_assert_sync_release" =>
            val stages = attributes("reset_stages").toInt
            require(stages >= 1, "reset policy stages must be positive")
            ResetPolicy.AsyncAssertSyncRelease(stages)
          case _ => DomainHarness.reject("unknown reset policy")
        val polarity = attributes.get("reset_polarity").map:
          case "active_high" => ResetPolarity.ActiveHigh
          case "active_low" => ResetPolarity.ActiveLow
          case _ => DomainHarness.reject("unknown reset polarity")
        val clockSource = attributes.get("clock_source")
        clockSource.foreach: source =>
          require(declarations.contains(source), "computed clock sources require an explicit model")
        val resetSource = attributes("reset_source")
        val (rawResetSource, synchronizationStages) = resetFacts(resetSource, path)
        val result = new HarnessDomain(
          path,
          parent,
          relation,
          frequency,
          edge.orElse(parent.map(_.edge)).get,
          policy.orElse(parent.map(_.resetPolicy)).get,
          polarity.orElse(parent.map(_.resetPolarity)).get,
          attributes.get("phase_degrees").map(_.toDouble).getOrElse(0.0),
          clockSource,
          resetSource,
          rawResetSource,
          synchronizationStages,
          owner
        )
        building -= path
        result
      }
    )
    val domains =
      captured.references.take(selectedCount).map(reference => descriptor(reference.path))
    new DomainHarness(domains, owner, captured.snapshot)

private object DomainWaveforms:
  private val PointLimit = 100000L
  private val ExactOrdinalLimit = 4503599627370496.0

  def positive(value: Double, label: String): Unit =
    require(java.lang.Double.isFinite(value) && value > 0.0, s"$label must be positive and finite")

  private def finite(value: Double, label: String): Double =
    require(java.lang.Double.isFinite(value), s"$label is not representable")
    value

  private def levels(value: ElectricalLevels): Unit =
    require(value.low.siValue < value.high.siValue, "electrical low must be less than high")
    finite(value.high.siValue - value.low.siValue, "electrical level span")
    ()

  private final case class Timing(drive: ClockDrive, period: Double, origin: Double):
    def edge(ordinal: Long): Double =
      finite(Math.fma(ordinal.toDouble, period, origin), "clock edge")
    def ordinal(value: Double): Long =
      val result = finite((value - origin) / period, "clock ordinal")
      require(
        math.abs(result) < ExactOrdinalLimit - 4.0,
        "clock ordinal is not exactly representable"
      )
      math.floor(result).toLong
    def sampled(time: Double, stages: Int): Double =
      var index = ordinal(time)
      if edge(index) < time then index += 1
      else if edge(index - 1) >= time then index -= 1
      val first = edge(index)
      require(first >= time && edge(index - 1) < time, "clock sampling edge is not distinguishable")
      val lastIndex = index + stages.toLong - 1L
      require(math.abs(lastIndex.toDouble) < ExactOrdinalLimit, "reset sample ordinal is too large")
      val last = edge(lastIndex)
      if stages > 1 then
        require(
          last > first && last > edge(lastIndex - 1),
          "reset sample edges are not distinguishable"
        )
      last

  private final case class Ramp(center: Double, width: Double, before: Double, after: Double):
    val middle: Double = finite(before + (after - before) / 2.0, "transition midpoint voltage")
    require(
      middle != before && middle != after,
      "transition voltage midpoint is not distinguishable"
    )
    val start: Double = finite(center - width / 2.0, "transition start")
    val end: Double = finite(center + width / 2.0, "transition end")
    require(start < center && center < end, "transition endpoints are not distinguishable")
    def at(time: Double): Double =
      if time <= start then before
      else if time >= end then after
      else if time <= center then
        finite(Math.fma((time - start) / (center - start), middle - before, before), "voltage")
      else finite(Math.fma((time - center) / (end - center), after - middle, middle), "voltage")

  private def waveform(until: Double, ramps: Vector[Ramp], initial: Double): VoltageWaveform =
    val ordered = ramps.sortBy(_.start)
    ordered.sliding(2).foreach: pair =>
      if pair.size == 2 then
        require(
          pair.head.end < pair.last.start,
          "clock/reset transitions overlap or cannot be distinguished"
        )
    def at(time: Double): Double =
      ordered.find(
        _.end >= time
      ).map(_.at(time)).getOrElse(ordered.lastOption.map(_.after).getOrElse(initial))
    val points = Vector(0.0 -> at(0.0)) ++ ordered.flatMap: ramp =>
      Vector(ramp.start -> ramp.before, ramp.center -> ramp.middle, ramp.end -> ramp.after)
        .filter((time, _) => time > 0.0 && time < until)
    val completed = points :+ (until -> at(until))
    require(completed.size <= PointLimit, "generated waveform exceeds point budget")
    VoltageWaveform(completed.map((time, voltage) => (time.seconds, voltage.volts)))

  def generate(
      owner: Object,
      until: Double,
      clocks: Vector[ClockDrive],
      resets: Vector[ResetDrive]
  ): DomainStimulus =
    positive(until, "stimulus duration")
    require(
      clocks.nonEmpty && clocks.size <= 32 && resets.size <= 32,
      "stimulus requires one to 32 clocks and at most 32 resets"
    )
    require(clocks.map(_.domain).distinct.size == clocks.size, "duplicate clock drive")
    require(resets.map(_.domain).distinct.size == resets.size, "duplicate reset drive")
    require(
      (clocks.map(_.domain) ++ resets.map(_.domain)).forall(_.owner eq owner),
      "domain belongs to a different capture"
    )
    resets.groupBy(_.domain.rawResetSource).values.foreach: group =>
      val pulses = group.map(value =>
        (
          value.domain.resetPolarity,
          value.levels.low.siValue,
          value.levels.high.siValue,
          value.assertAt.siValue,
          value.releaseAt.siValue,
          value.riseTime.siValue,
          value.fallTime.siValue
        )
      )
      require(pulses.distinct.size == 1, "conflicting reset pulses name the same raw source")
    val byDomain = clocks.map(value => value.domain -> value).toMap
    val timings = mutable.Map.empty[HarnessDomain, Timing]
    def timing(domain: HarnessDomain): Timing = timings.getOrElseUpdate(
      domain, {
        val drive = byDomain.getOrElse(
          domain,
          DomainHarness.reject("clock drive missing for domain or related parent")
        )
        levels(drive.levels)
        positive(drive.riseTime.siValue, "clock rise time")
        positive(drive.fallTime.siValue, "clock fall time")
        require(
          java.lang.Double.isFinite(drive.dutyCycle) && drive.dutyCycle > 0.0 &&
            drive.dutyCycle < 1.0,
          "clock duty cycle must be between zero and one"
        )
        val period = 1.0 / domain.frequencyHz
        positive(period, "clock period")
        val gap = (drive.riseTime.siValue + drive.fallTime.siValue) / 2.0
        require(
          gap < period * math.min(drive.dutyCycle, 1.0 - drive.dutyCycle),
          "clock transitions overlap the duty-cycle intervals"
        )
        val origin = domain.parent match
          case Some(parent) =>
            require(drive.origin.isEmpty, "related clock origin comes from its parent")
            finite(
              timing(parent).origin + domain.phaseDegrees / 360.0 * period,
              "related clock origin"
            )
          case None =>
            val value = drive.origin.getOrElse(
              DomainHarness.reject("independent clocks require an explicit origin")
            ).siValue
            require(value >= 0.0, "independent clock origin must be nonnegative")
            value
        Timing(drive, period, origin)
      }
    )
    val requested = clocks.map(value => timing(value.domain))
    // Arithmetic preflight bounds every candidate transition before constructing point vectors.
    val ranges = requested.map: value =>
      val first = value.ordinal(0.0) - 1L
      val last = value.ordinal(until) + 1L
      val cycles = last - first + 1L
      require(cycles > 0L && cycles <= PointLimit / 6L, "clock waveform exceeds point budget")
      (value, first, last, cycles * 6L + 2L)
    val budget = ranges.map(_._4).sum + resets.size.toLong * 8L
    require(budget <= PointLimit, "aggregate generated waveform exceeds 100000-point budget")
    val clockResults = ranges.map: (value, first, last, _) =>
      val drive = value.drive
      val activeRising = drive.domain.edge == ClockEdge.Rising
      val offset = value.period * (if activeRising then drive.dutyCycle else 1.0 - drive.dutyCycle)
      val low = drive.levels.low.siValue
      val high = drive.levels.high.siValue
      val ramps = (first to last).toVector.flatMap: ordinal =>
        val active = value.edge(ordinal)
        val opposite = finite(active + offset, "opposite clock edge")
        require(
          active < opposite && opposite < value.edge(ordinal + 1L),
          "clock edges are not distinguishable"
        )
        val rising = if activeRising then active else opposite
        val falling = if activeRising then opposite else active
        Vector(
          Ramp(rising, drive.riseTime.siValue, low, high),
          Ramp(falling, drive.fallTime.siValue, high, low)
        )
      ClockStimulus(drive.domain, waveform(until, ramps, if activeRising then low else high))
    clockResults.flatMap(value => value.domain.clockSource.map(_ -> value.waveform))
      .groupBy(_._1).values.foreach: group =>
        val waveforms =
          group.map(_._2.points.map((time, voltage) => time.siValue -> voltage.siValue))
        require(
          waveforms.distinct.size == 1,
          "conflicting electrical clock waveforms name the same source"
        )
    requested.flatMap(value => value.drive.domain.clockSource.map(_ -> value))
      .groupBy(_._1).values.foreach: group =>
        val shapes = group.map: (_, value) =>
          val drive = value.drive
          val risingOrigin = if drive.domain.edge == ClockEdge.Rising then value.origin
          else finite(value.origin + value.period * (1.0 - drive.dutyCycle), "rising phase origin")
          val remainder = risingOrigin % value.period
          val phase = if remainder < 0.0 then remainder + value.period else remainder
          require(
            phase >= 0.0 && phase < value.period,
            "shared clock phase is not representable modulo its period"
          )
          (
            drive.domain.frequencyHz,
            value.period,
            drive.dutyCycle,
            drive.riseTime.siValue,
            drive.fallTime.siValue,
            drive.levels.low.siValue,
            drive.levels.high.siValue,
            phase
          )
        require(
          shapes.distinct.size == 1,
          "conflicting clock timing or levels name the same source"
        )
    val resetResults = resets.map: drive =>
      val clock = timing(drive.domain)
      require(
        drive.domain.resetPolicy != ResetPolicy.None,
        "reset policy None has no reset stimulus"
      )
      levels(drive.levels)
      positive(drive.riseTime.siValue, "reset rise time")
      positive(drive.fallTime.siValue, "reset fall time")
      val assertion = drive.assertAt.siValue
      val release = drive.releaseAt.siValue
      require(assertion >= 0.0 && release > assertion, "reset requires 0 <= assertion < release")
      val high = drive.levels.high.siValue
      val low = drive.levels.low.siValue
      val activeHigh = drive.domain.resetPolarity == ResetPolarity.ActiveHigh
      val asserted = if activeHigh then high else low
      val deasserted = if activeHigh then low else high
      val assertionWidth = if activeHigh then drive.riseTime.siValue else drive.fallTime.siValue
      val releaseWidth = if activeHigh then drive.fallTime.siValue else drive.riseTime.siValue
      val ramps = Vector(
        Ramp(assertion, assertionWidth, deasserted, asserted),
        Ramp(release, releaseWidth, asserted, deasserted)
      )
      val (policyAssertion, policyRelease) = drive.domain.resetPolicy match
        case ResetPolicy.Sync => (clock.sampled(assertion, 1), clock.sampled(release, 1))
        case ResetPolicy.Async => (assertion, release)
        case ResetPolicy.AsyncAssertSyncRelease(stages) =>
          (assertion, clock.sampled(release, stages))
        case ResetPolicy.None => DomainHarness.reject("reset policy None")
      val reference = ResetReference(
        assertion.seconds,
        release.seconds,
        policyAssertion.seconds,
        policyRelease.seconds,
        drive.domain.synchronizationStages.map(stages => clock.sampled(release, stages).seconds)
      )
      ResetStimulus(
        drive.domain,
        drive.domain.rawResetSource,
        waveform(until, ramps, deasserted),
        reference
      )
    DomainStimulus(clockResults, resetResults)
