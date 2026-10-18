package nodal.internal.testkit

import nodal.*
import nodal.sim.*
import utest.*

final class RelatedHarnessFixture extends Module:
  val sameClock = in(Clock)
  val ratioClock = in(Clock)
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveLow,
    100.MHz
  )
  val same = ClockDomain.generated("same", sameClock, root, ClockRelation.Same, root.reset)
  val ratio = ClockDomain.generated(
    "ratio",
    ratioClock,
    root,
    ClockRelation.Ratio(2, 1, 90.deg),
    Rdc.sync(root.reset, stages = 2)
  )
  val independent = ClockDomain.external(
    "independent",
    ClockEdge.Rising,
    ResetPolicy.Async,
    ResetPolarity.ActiveHigh,
    100.MHz
  )

final class FallingHarnessFixture extends Module:
  val clock = in(Clock)
  val root = ClockDomain.external(
    "root",
    ClockEdge.Falling,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val ratio = ClockDomain.generated(
    "ratio",
    clock,
    root,
    ClockRelation.Ratio(1, 2, -90.deg),
    Rdc.sync(root.reset, stages = 3)
  )

final class ResetPoliciesHarnessFixture extends Module:
  val none = ClockDomain.external(
    "none",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val sync = ClockDomain.external(
    "sync",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val async = ClockDomain.external(
    "async",
    ClockEdge.Rising,
    ResetPolicy.Async,
    ResetPolarity.ActiveLow,
    100.MHz
  )
  val staged = ClockDomain.external(
    "staged",
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(3),
    ResetPolarity.ActiveHigh,
    100.MHz
  )

final class UnsupportedHarnessFixture extends Module:
  val clock = in(Clock)
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val unknown = ClockDomain.generated(
    "unknown",
    clock,
    root,
    ClockRelation.Synchronous(phaseKnown = false),
    root.reset
  )
  val gate = ClockGate(root, true.B)
  val mux = ClockMux.glitchless(true.B, Seq(root), "mux")
  val required = ClockDomain.required("required")

final class SharedClockSourceHarnessFixture extends Module:
  val clock = in(Clock)
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val first = ClockDomain.generated("first", clock, root, ClockRelation.Same, root.reset)
  val alias = ClockDomain.generated("alias", clock, root, ClockRelation.Same, root.reset)
  val conflicting = ClockDomain.generated(
    "conflicting",
    clock,
    root,
    ClockRelation.Ratio(2, 1, 0.deg),
    root.reset
  )

  val shifted = ClockDomain.generated(
    "shifted",
    clock,
    root,
    ClockRelation.Ratio(1, 1, 90.deg),
    root.reset
  )

final class InvalidStageHarnessFixture extends Module:
  val clock = in(Clock)
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val invalid = ClockDomain.generated(
    "invalid",
    clock,
    root,
    ClockRelation.Same,
    Rdc.sync(root.reset, stages = 0)
  )
  val cascade = ClockDomain.generated(
    "cascade",
    clock,
    root,
    ClockRelation.Same,
    Rdc.sync(Rdc.sync(root.reset, stages = 2), stages = 2)
  )

final class BoundHarnessFixture extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val domain = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Falling,
    ResetPolicy.Sync,
    ResetPolarity.ActiveLow,
    100.MHz,
    "bound"
  )

final class NestedHarnessFixture extends Module:
  val child = instance(new BoundHarnessFixture)

final class AnalogHarnessFixture extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val p = inout(Electrical)
  val n = inout(Electrical)
  analog:
    I(p, n) <+ V(p, n) / 1000.Ohm

final class EffectHarnessFixture extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  solverHints(SolverHint.MaximumStep(1.0.ns))

final class AssignedClockHarnessFixture extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val domain = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  clock := clock

object DomainStimulusTests extends TestSuite:
  private val levels = ElectricalLevels(0.volts, 1.volts)
  private val ramp = 0.1e-9.seconds

  private def get[A](value: Either[SimulationError, A]): A =
    value.fold(
      error => scala.util.Failure[A](new java.lang.AssertionError(error.toString)).get,
      identity
    )

  private def drive(domain: HarnessDomain, origin: Double = 0.0): ClockDrive =
    ClockDrive(
      domain,
      levels,
      ramp,
      ramp,
      origin = Option.when(domain.parent.isEmpty)(origin.seconds)
    )

  private def pulse(domain: HarnessDomain, release: Double = 10e-9): ResetDrive =
    ResetDrive(domain, levels, 0.seconds, release.seconds, ramp, ramp)

  private def near(actual: Double, expected: Double): Unit =
    assert(math.abs(actual - expected) <= 1e-14)

  private def voltage(waveform: VoltageWaveform, at: Double): Double =
    val points = waveform.points.map((time, voltage) => time.siValue -> voltage.siValue)
    points.find(_._1 == at).map(_._2).getOrElse:
      val pair = points.sliding(2).find(values => values.head._1 < at && values.last._1 > at).get
      val (firstTime, firstValue) = pair.head
      val (lastTime, lastValue) = pair.last
      firstValue + (lastValue - firstValue) * (at - firstTime) / (lastTime - firstTime)

  val tests = Tests:
    test("actualParentsPhaseAndIndependentOrigins"):
      val harness = get(DomainHarness.capture(new RelatedHarnessFixture)(module =>
        Vector(module.root, module.same, module.ratio, module.independent)
      ))
      val Vector(root, same, ratio, independent) = harness.domains: @unchecked
      assert(same.parent.contains(root), ratio.parent.contains(root), independent.parent.isEmpty)
      assert(root.frequencyHz == 100e6, ratio.frequencyHz == 200e6)
      assert(same.relation == HarnessClockRelation.Same)
      assert(ratio.relation == HarnessClockRelation.Ratio(2, 1, 90.0))
      assert(independent.relation == HarnessClockRelation.Independent)
      assert(harness.sourceMap.exists(_.semanticPath == root.semanticPath))
      assert(ratio.synchronizationStages.contains(2), ratio.resetSource != ratio.rawResetSource)
      val result = get(harness.stimulus(
        30e-9.seconds,
        Vector(drive(root, 2e-9), drive(same), drive(ratio), drive(independent, 4e-9))
      ))
      val waveforms = result.clocks.map(_.waveform)
      assert(waveforms(0).points.map((t, v) => t.siValue -> v.siValue) ==
        waveforms(1).points.map((t, v) => t.siValue -> v.siValue))
      near(voltage(waveforms(0), 2e-9), 0.5)
      near(voltage(waveforms(2), 3.25e-9), 0.5)
      near(voltage(waveforms(3), 4e-9), 0.5)
      near(voltage(waveforms(0), 0.0), 0.0)
      near(voltage(waveforms(2), 0.0), 1.0)
      assert(harness.stimulus(
        30e-9.seconds,
        Vector(drive(root), drive(ratio).copy(origin = Some(0.seconds)))
      ).isLeft)
      assert(harness.stimulus(30e-9.seconds, Vector(drive(ratio))).isLeft)
      assert(harness.stimulus(
        30e-9.seconds,
        Vector(drive(independent).copy(origin = None))
      ).isLeft)

    test("fallingEdgesSignedPhaseAndDuty"):
      val harness = get(DomainHarness.capture(new FallingHarnessFixture)(module =>
        Vector(module.root, module.ratio)
      ))
      val Vector(root, ratio) = harness.domains: @unchecked
      val result = get(harness.stimulus(
        30e-9.seconds,
        Vector(drive(root, 2e-9).copy(dutyCycle = 0.25), drive(ratio))
      ))
      assert(ratio.edge == ClockEdge.Falling)
      near(voltage(result.clocks.head.waveform, 2e-9), 0.5)
      near(voltage(result.clocks.head.waveform, 5e-9), 0.0)
      near(voltage(result.clocks.head.waveform, 9.5e-9), 0.5)
      near(voltage(result.clocks.head.waveform, 10e-9), 1.0)
      near(voltage(result.clocks(1).waveform, 17e-9), 0.5)
      near(voltage(result.clocks(1).waveform, 0.0), 0.0)

    test("resetPolicyAndStagedReferenceAreSeparate"):
      val harness = get(DomainHarness.capture(new RelatedHarnessFixture)(module =>
        Vector(module.root, module.same, module.ratio)
      ))
      val Vector(root, _, ratio) = harness.domains: @unchecked
      val result = get(harness.stimulus(
        30e-9.seconds,
        Vector(drive(root), drive(ratio)),
        Vector(pulse(ratio, 6.25e-9))
      ))
      val reset = result.resets.head
      assert(reset.source == root.rawResetSource)
      near(reset.reference.rawReleaseAt.siValue, 6.25e-9)
      near(reset.reference.policyReleaseAt.siValue, 11.25e-9)
      near(reset.reference.stagedReleaseAt.get.siValue, 11.25e-9)
      near(reset.reference.policyAssertAt.siValue, 0.0)
      near(voltage(reset.waveform, 1e-9), 0.0)
      near(voltage(reset.waveform, 7e-9), 1.0)
      near(voltage(reset.waveform, 6.25e-9), 0.5)
      assert(harness.stimulus(
        30e-9.seconds,
        Vector(drive(root), drive(ratio)),
        Vector(pulse(root, 6.25e-9), pulse(ratio, 7e-9))
      ).isLeft)

    test("allResetPoliciesAndCoincidentInclusiveSampling"):
      val harness = get(DomainHarness.capture(new ResetPoliciesHarnessFixture)(module =>
        Vector(module.none, module.sync, module.async, module.staged)
      ))
      val Vector(none, sync, async, staged) = harness.domains: @unchecked
      val clocks = harness.domains.map(drive(_))
      val result = get(harness.stimulus(
        40e-9.seconds,
        clocks,
        Vector(pulse(sync), pulse(async), pulse(staged))
      ))
      near(result.resets(0).reference.policyAssertAt.siValue, 0.0)
      near(result.resets(0).reference.policyReleaseAt.siValue, 10e-9)
      near(result.resets(1).reference.policyReleaseAt.siValue, 10e-9)
      near(result.resets(2).reference.policyReleaseAt.siValue, 30e-9)
      assert(result.resets.forall(_.reference.stagedReleaseAt.isEmpty))
      near(voltage(result.resets(0).waveform, 1e-9), 1.0)
      near(voltage(result.resets(1).waveform, 1e-9), 0.0)
      assert(harness.stimulus(40e-9.seconds, clocks, Vector(pulse(none))).isLeft)
      val offEdge = get(harness.stimulus(
        40e-9.seconds,
        clocks,
        Vector(pulse(sync, 11e-9).copy(assertAt = 1e-9.seconds))
      ))
      near(offEdge.resets.head.reference.policyAssertAt.siValue, 10e-9)
      near(offEdge.resets.head.reference.policyReleaseAt.siValue, 20e-9)

    test("rejectInvalidTimingLevelsAndForeignCapture"):
      val harness = get(DomainHarness.capture(new ResetPoliciesHarnessFixture)(module =>
        Vector(module.sync)
      ))
      val domain = harness.domains.head
      val good = drive(domain)
      assert(harness.stimulus((-1).seconds, Vector(good)).isLeft)
      assert(harness.stimulus(0.seconds, Vector(good)).isLeft)
      assert(harness.stimulus(1e-6.seconds, Vector(good.copy(origin = Some((-1).seconds)))).isLeft)
      assert(harness.stimulus(1e-6.seconds, Vector(good.copy(riseTime = 0.seconds))).isLeft)
      assert(harness.stimulus(1e-6.seconds, Vector(good.copy(riseTime = 20e-9.seconds))).isLeft)
      assert(harness.stimulus(1e-6.seconds, Vector(good.copy(dutyCycle = 0.0))).isLeft)
      assert(harness.stimulus(1e-6.seconds, Vector(good.copy(dutyCycle = Double.NaN))).isLeft)
      assert(harness.stimulus(
        1e-6.seconds,
        Vector(good.copy(levels = ElectricalLevels(1.volts, 1.volts)))
      ).isLeft)
      assert(harness.stimulus(
        1e-6.seconds,
        Vector(good),
        Vector(pulse(domain).copy(assertAt = (-1).seconds))
      ).isLeft)
      assert(harness.stimulus(
        1e-6.seconds,
        Vector(good),
        Vector(pulse(domain).copy(releaseAt = 0.seconds))
      ).isLeft)
      val other = get(DomainHarness.capture(new ResetPoliciesHarnessFixture)(module =>
        Vector(module.sync)
      ))
      assert(other.stimulus(1e-6.seconds, Vector(good)).isLeft)
      assert(other.stimulus(
        1e-6.seconds,
        Vector(drive(other.domains.head)),
        Vector(pulse(domain))
      ).isLeft)

    test("rejectBudgetAndUnrepresentableTimingBeforeExpansion"):
      val harness = get(DomainHarness.capture(new ResetPoliciesHarnessFixture)(module =>
        Vector(module.sync, module.async)
      ))
      val first = drive(harness.domains.head)
      val second = drive(harness.domains(1))
      assert(harness.stimulus(1.seconds, Vector(first)).isLeft)
      assert(harness.stimulus(150e-6.seconds, Vector(first, second)).isLeft)
      assert(harness.stimulus(
        1e-6.seconds,
        Vector(first.copy(origin = Some(1e100.seconds)))
      ).isLeft)
      assert(harness.stimulus(
        1e-6.seconds,
        Vector(first.copy(riseTime = java.lang.Double.MIN_VALUE.seconds))
      ).isLeft)
      val bounded = get(harness.stimulus(1e-6.seconds, Vector(first, second)))
      assert(bounded.clocks.map(_.waveform.points.size).sum < 100000)
      bounded.clocks.foreach: clock =>
        assert(clock.waveform.points.head._1.siValue == 0.0)
        assert(clock.waveform.points.last._1.siValue == 1e-6)
        assert(clock.waveform.points.map(_._1.siValue).sliding(2).forall(pair =>
          pair.head < pair.last
        ))

    test("rejectUnsupportedAndForeignConstructionHandles"):
      assert(DomainHarness.capture(new UnsupportedHarnessFixture)(module =>
        Vector(module.unknown)
      ).isLeft)
      assert(DomainHarness.capture(new UnsupportedHarnessFixture)(module =>
        Vector(module.gate)
      ).isLeft)
      assert(DomainHarness.capture(new UnsupportedHarnessFixture)(module =>
        Vector(module.mux)
      ).isLeft)
      assert(DomainHarness.capture(new UnsupportedHarnessFixture)(module =>
        Vector(module.required)
      ).isLeft)
      var escaped = Option.empty[ClockDomain]
      get(DomainHarness.capture(new ResetPoliciesHarnessFixture): module =>
        escaped = Some(module.sync)
        Vector(module.sync))
      assert(DomainHarness.capture(new ResetPoliciesHarnessFixture)(_ =>
        Vector(escaped.get)
      ).isLeft)

    test("oneClockSourceHasOneElectricalWaveform"):
      val harness = get(DomainHarness.capture(new SharedClockSourceHarnessFixture)(module =>
        Vector(module.root, module.first, module.alias, module.conflicting, module.shifted)
      ))
      val Vector(root, first, alias, conflicting, shifted) = harness.domains: @unchecked
      assert(first.clockSource == alias.clockSource, first.clockSource == conflicting.clockSource)
      assert(harness.stimulus(
        30e-9.seconds,
        Vector(drive(root), drive(first), drive(alias))
      ).isRight)
      val result = harness.stimulus(
        30e-9.seconds,
        Vector(drive(root), drive(first), drive(conflicting))
      )
      assert(result.left.toOption.exists(_.message.contains("same source")))
      val short = 1e-11.seconds
      val reference = get(harness.stimulus(short, Vector(drive(root, 2e-9), drive(first))))
      val faster = get(harness.stimulus(short, Vector(drive(root, 2e-9), drive(conflicting))))
      assert(reference.clocks.last.waveform.points.map((t, v) => t.siValue -> v.siValue) ==
        faster.clocks.last.waveform.points.map((t, v) => t.siValue -> v.siValue))
      assert(harness.stimulus(
        short,
        Vector(drive(root, 2e-9), drive(first), drive(conflicting))
      ).isLeft)
      assert(harness.stimulus(
        short,
        Vector(drive(root, 2e-9), drive(first), drive(shifted))
      ).isLeft)
      assert(harness.stimulus(
        30e-9.seconds,
        Vector(drive(root), drive(first), drive(alias).copy(dutyCycle = 0.25))
      ).isLeft)

    test("rejectInvalidOrImplicitlyComposedSynchronizerStages"):
      val zero =
        DomainHarness.capture(new InvalidStageHarnessFixture)(module => Vector(module.invalid))
      val cascade =
        DomainHarness.capture(new InvalidStageHarnessFixture)(module => Vector(module.cascade))
      assert(zero.left.toOption.exists(_.message.contains("stages must be positive")))
      assert(cascade.left.toOption.exists(_.message.contains("cascaded reset synchronizers")))

    test("admitOnlyStimulusSourcesWithoutDiscardingEffects"):
      val bound =
        get(DomainHarness.capture(new BoundHarnessFixture)(module => Vector(module.domain)))
      val nested = get(DomainHarness.capture(new NestedHarnessFixture)(module =>
        Vector(module.child(_.domain))
      ))
      assert(bound.domains.head.clockSource.nonEmpty, bound.domains.head.rawResetSource.nonEmpty)
      assert(nested.domains.head.semanticPath.contains("child"))
      assert(get(nested.stimulus(20e-9.seconds, nested.domains.map(drive(_)))).clocks.nonEmpty)
      val analog = DomainHarness.capture(new AnalogHarnessFixture)(module => Vector(module.root))
      val effect = DomainHarness.capture(new EffectHarnessFixture)(module => Vector(module.root))
      val assignment =
        DomainHarness.capture(new AssignedClockHarnessFixture)(module => Vector(module.domain))
      assert(analog.left.toOption.exists(_.message.contains("executable source effects")))
      assert(effect.left.toOption.exists(_.message.contains("executable source effects")))
      assert(assignment.left.toOption.exists(_.message.contains("executable source effects")))

    test("waveformConstructorEnforcesOrdering"):
      assert(scala.util.Try(VoltageWaveform(Vector.empty)).isFailure)
      assert(scala.util.Try(VoltageWaveform(Vector((-1).seconds -> 0.volts))).isFailure)
      assert(scala.util.Try(VoltageWaveform(Vector(
        0.seconds -> 0.volts,
        0.seconds -> 1.volts
      ))).isFailure)
