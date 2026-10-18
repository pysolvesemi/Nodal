package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

import nodal.*
import nodal.sim.*

/** Public batch API replay against actual native, OpenVAF and ngspice tools. The numerical
  * references below are declared before execution and never derived from the DUT waveform, emitted
  * target or rendered deck. Every numerical row retains its expected value. Arguments: output-root
  * nodalc translator openvaf compiler-profile ngspice simulator-profile HEAD.
  */
object Increment50SourceReplay:
  private val Ground = SimulationNode.Ground
  private val Positive = SimulationNode.Node(0)
  private val CoreOrigin = 0.2e-6
  private val RelatedOrigin = CoreOrigin + 0.25 / 1.5e6
  private val AsyncOrigin = 0.35e-6
  private val ClockRamp = 0.02e-6
  private val ResetAssertion = 0.1e-6
  private val ResetRelease = 3.2e-6
  private val StimulusStop = 10e-6

  private def write(directory: Path, name: String, content: String): Unit =
    val _ = Files.writeString(
      directory.resolve(name),
      content,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE_NEW
    )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(value => f"${value & 0xff}%02x").mkString

  private def repositoryRoot(path: Path): Path =
    if Files.isRegularFile(path.resolve("build.mill")) then path
    else
      val parent = Option(path.getParent)
      require(parent.nonEmpty, "evidence must be below the repository")
      repositoryRoot(parent.get)

  private def get[A](value: Either[SimulationError, A]): A =
    value.fold(
      error =>
        scala.util.Failure[A](new IllegalArgumentException(s"${error.code}: ${error.message}")).get,
      identity
    )

  private def port(source: SimulationSource, name: String): ModelPort = get(source.port(name))

  private def rcBindings(source: SimulationSource): Vector[(ModelPort, SimulationNode)] =
    // Binding order deliberately differs from the actual target ABI.
    Vector(port(source, "positive") -> Positive, port(source, "ground") -> Ground)

  private def square(time: Double, origin: Double, frequency: Double): Double =
    val cycles = (time - origin) * frequency
    if cycles - math.floor(cycles) < 0.5 then 1.0 else 0.0

  private def awayFromEdges(time: Double, origin: Double, frequency: Double): Boolean =
    val halfCycles = (time - origin) * frequency * 2.0
    math.abs(halfCycles - math.rint(halfCycles)) / (frequency * 2.0) > ClockRamp * 1.5

  private def weightedReference(time: Double): Double =
    square(time, CoreOrigin, 1e6) + 2.0 * square(time, RelatedOrigin, 1.5e6) +
      4.0 * square(time, AsyncOrigin, 8e5) +
      8.0 * (if time >= ResetAssertion && time < ResetRelease then 0.0 else 1.0)

  private def referenceSample(time: Double): Boolean =
    awayFromEdges(time, CoreOrigin, 1e6) && awayFromEdges(time, RelatedOrigin, 1.5e6) &&
      awayFromEdges(time, AsyncOrigin, 8e5) &&
      math.abs(time - ResetAssertion) > ClockRamp * 1.5 &&
      math.abs(time - ResetRelease) > ClockRamp * 1.5

  def main(arguments: Array[String]): Unit =
    require(
      arguments.length == 8,
      "expected output-root nodalc translator openvaf compiler-profile ngspice simulator-profile HEAD"
    )
    val outputRoot = Path.of(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(outputRoot)
    val output = Files.createTempDirectory(outputRoot, "public-replay-")
    val repository = repositoryRoot(output)
    require(arguments(7).matches("[a-fA-F0-9]{40}"), "HEAD must be a full Git revision")
    val compilerTools = CompilationTools(
      Path.of(arguments(1)).toAbsolutePath.normalize(),
      Path.of(arguments(2)).toAbsolutePath.normalize(),
      Path.of(arguments(3)).toAbsolutePath.normalize(),
      arguments(4),
      output.resolve("compilations"),
      Duration.ofSeconds(60),
      None
    )
    val simulatorTools = SimulatorTools(
      Path.of(arguments(5)).toAbsolutePath.normalize(),
      arguments(6),
      output.resolve("simulations"),
      Duration.ofSeconds(60)
    )
    val failures = mutable.ArrayBuffer.empty[String]
    val checks = mutable.ArrayBuffer(
      "case\taxis\tcomponent\tactual\treference\tabsoluteTolerance\trelativeTolerance\tpassed"
    )
    val receipts = mutable.ArrayBuffer(
      "case\toutcome\tsamples\tevidence\trequestSha256\tmodelSha256\tartifactSha256\trawSha256"
    )
    val outcomes = mutable.ArrayBuffer.empty[String]

    write(
      output,
      "references.txt",
      """Nodal F50 independent references, declared before compilation and simulation
RC: source constructor R=1000 ohm, C=1e-6 F; no adapter parameter override.
DC: -1 to 1 V in 0.5 V steps; source current = -V/R A; abs=1e-10 A, rel=1e-6.
AC: unit AC voltage, 3 points/decade 10 to 10000 Hz; source current=-(1/R+j*2*pi*f*C) A; abs=1e-10 A, rel=1e-6.
Transient: current from 0 to .001 A; delay=.001 s; linear rise=.0001 s; initial V=0; C*dV/dt+V/R=I(t); step=.00001 s; stop=.01 s; abs=2e-5 V, rel=.002.
Clock/reset: output=clock+2*related+4*asynchronous+8*rawReset V; electrical levels=0,1 V; ramps=.02 us.
Core: rising, 1 MHz, active origin=.2 us, duty=.5. Related: ratio 3/2, phase=+90 degrees of child period, inherited rising edge, origin=.3666666666666667 us. Async: rising, .8 MHz, independent origin=.35 us.
Raw reset: active-low, assertion=.1 us, release=3.2 us; policy AsyncAssertSyncRelease(2), explicit Rdc.sync(3) references recorded independently. This does not execute digital reset synchronization.
Weighted numerical samples: omit points within .03 us of any reference midpoint; reference uses analytic square phases, never generated PWL; abs=1e-8 V, rel=1e-7.
Cartesian source sweep: clock in [0,1] V, related in [.25,.75] V; asynchronous DC analysis 0 to .5 V step .5 V; raw reset=1 V; output=clock+2*related+4*axis+8 V; abs=1e-8 V, rel=1e-7.
Resource negatives: a 12x12 Cartesian source product exceeds 128 runs; 21 one-probe transient runs exceed the 4000000-cell worst-case retained result budget. Both reject before simulator evidence creation.
Assertion criterion: abs(actual-reference) <= absolute + relative*abs(reference).
Repeat: exact physical axes and measured components, same request/model/artifact identities; fresh evidence locations. Concurrent: independent captured and compiled RC sessions, R=1000 and 2000 ohm, separate files.
"""
    )
    write(
      output,
      "run.txt",
      s"baseHEAD=${arguments(7)}\nsourceState=working tree; base HEAD does not certify clean or published tree\ncompilerProfile=${arguments(4)}\nsimulatorProfile=${arguments(6)}\n"
    )
    val retainedPaths = Vector(
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment50SimulationTests.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment50SourceReplay.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment49SourceReplay.scala",
      "core/scala/sim/src/nodal/sim/SimulationCompilation.scala",
      "core/scala/sim/src/nodal/sim/SimulationBench.scala",
      "core/scala/sim/src/nodal/sim/SimulationSweep.scala",
      "core/scala/sim/src/nodal/sim/DomainStimulus.scala",
      "core/scala/api/src/nodal/CandidateApi.scala",
      "core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala",
      "core/scala/api/src/nodal/ConstructionRecords.scala",
      "core/scala/api/src/nodal/ConstructionSession.scala",
      "core/scala/api/src/nodal/ElaborationConstructionKernel.scala",
      "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
      "core/integrations/src/nodal/internal/integrations/NgspiceCircuitBench.scala",
      "core/integrations/src/nodal/internal/integrations/NgspiceSimulator.scala"
    )
    val retained = retainedPaths.map: relative =>
      val bytes = Files.readAllBytes(repository.resolve(relative))
      val target = output.resolve("source-files").resolve(relative + ".bytes")
      Files.createDirectories(target.getParent)
      Files.write(target, bytes, StandardOpenOption.CREATE_NEW)
      s"$relative\t${sha256(bytes)}\t${bytes.length}"
    write(output, "source-files.tsv", "path\tsha256\tbytes\n" + retained.mkString("\n") + "\n")

    def expect(condition: Boolean, description: String): Unit =
      outcomes += s"${if condition then "PASS" else "FAIL"}\t$description"
      if !condition then failures += description

    def compile(name: String, source: SimulationSource): CompiledSimulationModel =
      val result = SimulationCompiler.compile(source, compilerTools)
      outcomes +=
        s"COMPILE\t$name\t${result.succeeded}\t${result.error}\t${result.evidence.map(_.directory)}"
      println(
        s"F50 compile $name: succeeded=${result.succeeded}; evidence=${result.evidence.map(_.directory)}"
      )
      require(result.succeeded, s"$name compilation failed: ${result.error}")
      result.model.get

    def receipt(name: String, result: SimulationResult): Unit =
      val evidence = result.evidence
      receipts += Vector(
        name,
        evidence.outcome,
        result.sampleCount.toString,
        evidence.directory.fold("")(_.toString),
        evidence.requestSha256.getOrElse(""),
        evidence.modelSha256,
        evidence.artifactSha256.getOrElse(""),
        evidence.rawSha256.getOrElse("")
      ).mkString("\t")
      expect(result.succeeded, s"$name simulation succeeds: ${result.error}")
      println(
        s"F50 simulation $name: ${evidence.outcome}; samples=${result.sampleCount}; evidence=${evidence.directory}"
      )

    def numerical[D](
        name: String,
        result: SimulationResult,
        probe: Probe[D],
        index: Int,
        expected: Quantity[D],
        tolerance: Tolerance[D],
        component: ComplexComponent = ComplexComponent.Real
    ): Unit =
      val measurement = get(result.measure(probe, SampleReduction.At(index), component))
      val checked = SimulationAssertion.check(measurement, expected, tolerance)
      checks += Vector(
        name,
        get(result.axis(index)).toString,
        component.toString,
        checked.actual.toString,
        checked.expected.toString,
        checked.absoluteTolerance.toString,
        checked.relativeTolerance.toString,
        checked.passed.toString
      ).mkString("\t")
      if !checked.passed then failures += s"$name sample $index $component: $checked"

    val rcSource = Increment50Sources.rc()
    val rc = compile("rc", rcSource)
    expect(
      rc.portsInTargetOrder.map(_.semanticPath) == Vector("Increment50Rc.a", "Increment50Rc.z"),
      "target pin order verified independently of declaration and binding order"
    )
    val target = Files.readString(rc.evidence.directory.resolve("model.va"))
    expect(
      target.contains("module Increment50Rc(a, z);"),
      "actual generated Verilog-A has a,z scalar ABI"
    )
    write(output, "rc-generated.va", target)
    val currentTolerance = Tolerance(1e-10.amps, 1e-6)
    val transientTolerance = Tolerance(2e-5.volts, 2e-3)
    val voltageTolerance = Tolerance(1e-8.volts, 1e-7)
    val voltageDrive = ElectricalDrive.voltage(0, Positive, Ground, 0.0.volts, Some(1.0.volts))
    val currentProbe = Probe.current("source-current", voltageDrive)
    val dcAnalysis = SimulationAnalysis.dc(voltageDrive, (-1.0).volts, 1.0.volts, 0.5.volts)
    val dcBench = get(SimulationBench.create(
      rc,
      rcBindings(rcSource),
      Vector(voltageDrive),
      dcAnalysis,
      Vector(currentProbe)
    ))
    val dc = SimulationSession.run(dcBench, simulatorTools)
    receipt("rc-dc", dc)
    if dc.succeeded then
      expect(dc.sampleCount == 5, "RC DC exact five-point grid")
      (0 until dc.sampleCount).foreach: index =>
        numerical(
          "rc-dc",
          dc,
          currentProbe,
          index,
          Increment49References.dcSourceCurrent(get(dc.axis(index))).amps,
          currentTolerance
        )

    if dc.succeeded then
      Vector(
        SampleReduction.First -> 0.001,
        SampleReduction.Last -> -0.001,
        SampleReduction.Minimum -> -0.001,
        SampleReduction.Maximum -> 0.001,
        SampleReduction.Mean -> 0.0
      ).foreach: (reduction, reference) =>
        expect(
          SimulationAssertion.check(
            get(dc.measure(currentProbe, reduction)),
            reference.amps,
            currentTolerance
          ).passed,
          s"finite DC measurement reduction $reduction"
        )

    val acBench = get(SimulationBench.create(
      rc,
      rcBindings(rcSource),
      Vector(voltageDrive),
      SimulationAnalysis.acDecade(3, Frequency.hertz(10), Frequency.hertz(10000)),
      Vector(currentProbe)
    ))
    val ac = SimulationSession.run(acBench, simulatorTools)
    receipt("rc-ac", ac)
    if ac.succeeded then
      expect(ac.sampleCount == 10, "RC AC exact ten-point grid")
      (0 until ac.sampleCount).foreach: index =>
        val expected = Increment49References.acSourceCurrent(get(ac.axis(index)))
        numerical("rc-ac", ac, currentProbe, index, expected.real.amps, currentTolerance)
        numerical(
          "rc-ac",
          ac,
          currentProbe,
          index,
          expected.imaginary.amps,
          currentTolerance,
          ComplexComponent.Imaginary
        )
        numerical(
          "rc-ac",
          ac,
          currentProbe,
          index,
          math.hypot(expected.real, expected.imaginary).amps,
          currentTolerance,
          ComplexComponent.Magnitude
        )

    val ramp = ElectricalDrive.current(
      1,
      Ground,
      Positive,
      0.0.amps,
      waveform = Vector(
        0.0.seconds -> 0.0.amps,
        0.001.seconds -> 0.0.amps,
        0.0011.seconds -> 0.001.amps,
        0.01.seconds -> 0.001.amps
      )
    )
    val voltageProbe = Probe.voltage("rc-voltage", Positive)
    val transientBench = get(SimulationBench.create(
      rc,
      rcBindings(rcSource),
      Vector(ramp),
      SimulationAnalysis.transient(1e-5.seconds, 0.01.seconds, true),
      Vector(voltageProbe),
      Vector(SimulationInitialCondition(Positive, 0.0.volts))
    ))
    val transient = SimulationSession.run(transientBench, simulatorTools)
    receipt("rc-transient", transient)
    if transient.succeeded then
      expect(transient.sampleCount > 1000, "RC transient retains adaptive finite samples")
      (0 until transient.sampleCount).foreach: index =>
        numerical(
          "rc-transient",
          transient,
          voltageProbe,
          index,
          Increment49References.rcVoltage(get(transient.axis(index))).volts,
          transientTolerance
        )
      expect(
        transient.measure(Probe.voltage("absent", Positive), SampleReduction.Last)
          .left.toOption.exists(_.code == "SIM-MEASURE"),
        "missing probe is a measurement failure"
      )
      expect(
        transient.measure(voltageProbe, SampleReduction.At(-1)).isLeft,
        "negative sample index is a measurement failure"
      )
      expect(
        transient.measure(voltageProbe, SampleReduction.At(transient.sampleCount)).isLeft,
        "past-end sample index is a measurement failure"
      )
      expect(transient.axis(transient.sampleCount).isLeft, "missing axis is a measurement failure")
      val failed = SimulationAssertion.check(
        get(transient.measure(voltageProbe, SampleReduction.Last)),
        2.0.volts,
        transientTolerance
      )
      expect(
        !failed.passed && transient.succeeded,
        "intentional assertion failure does not relabel successful simulation"
      )
      expect(
        scala.util.Try(SimulationAssertion.check(
          get(transient.measure(voltageProbe, SampleReduction.Last)),
          Double.MaxValue.volts,
          Tolerance(Double.MaxValue.volts, Double.MaxValue)
        )).isFailure,
        "overflowing tolerance allowance rejects"
      )

    expect(
      SimulationBench.create(
        rc,
        rcBindings(rcSource).take(1),
        Vector(voltageDrive),
        dcAnalysis,
        Vector(currentProbe)
      ).left.toOption.exists(_.code == "SIM-BENCH"),
      "missing port binding rejects"
    )
    expect(
      SimulationBench.create(
        rc,
        rcBindings(rcSource) :+ rcBindings(rcSource).head,
        Vector(voltageDrive),
        dcAnalysis,
        Vector(currentProbe)
      ).isLeft,
      "duplicate port binding rejects"
    )
    expect(
      SimulationBench.create(
        rc,
        rcBindings(Increment50Sources.rc()),
        Vector(voltageDrive),
        dcAnalysis,
        Vector(currentProbe)
      ).isLeft,
      "foreign capture ports reject even with matching semantic paths"
    )
    val foreignDrive = ElectricalDrive.voltage(2, Positive, Ground, 0.0.volts)
    expect(
      SimulationBench.create(
        rc,
        rcBindings(rcSource),
        Vector(voltageDrive),
        SimulationAnalysis.dc(foreignDrive, 0.0.volts, 1.0.volts, 0.5.volts),
        Vector(currentProbe)
      ).isLeft,
      "foreign DC source rejects"
    )
    expect(
      SimulationBench.create(
        rc,
        rcBindings(rcSource),
        Vector(voltageDrive),
        dcAnalysis,
        Vector(Probe.current("foreign-current", foreignDrive))
      ).isLeft,
      "foreign current probe rejects"
    )

    val repeated = SimulationSession.run(dcBench, simulatorTools)
    receipt("rc-dc-repeated", repeated)
    expect(
      repeated.evidence.directory != dc.evidence.directory,
      "repeated simulation has fresh evidence"
    )
    expect(
      repeated.evidence.requestSha256 == dc.evidence.requestSha256 &&
        repeated.evidence.artifactSha256 == dc.evidence.artifactSha256,
      "repeated semantic and artifact identities match"
    )
    if dc.succeeded && repeated.succeeded then
      expect(
        dc.sampleCount == repeated.sampleCount && (0 until dc.sampleCount).forall(index =>
          dc.axis(index) == repeated.axis(index) &&
            get(dc.measure(currentProbe, SampleReduction.At(index))).value.siValue ==
            get(repeated.measure(currentProbe, SampleReduction.At(index))).value.siValue
        ),
        "repeated DC axes and measurements are exactly reproducible"
      )

    val weightedSource = Increment50Sources.weighted()
    val weighted = compile("weighted-clock-reset", weightedSource)
    write(
      output,
      "weighted-generated.va",
      Files.readString(weighted.evidence.directory.resolve("model.va"))
    )
    val clockNode = SimulationNode.Node(0)
    val relatedNode = SimulationNode.Node(1)
    val asyncNode = SimulationNode.Node(2)
    val resetNode = SimulationNode.Node(3)
    val outputNode = SimulationNode.Node(4)
    val weightedBindings = Vector(
      "clock" -> clockNode,
      "related" -> relatedNode,
      "async" -> asyncNode,
      "reset" -> resetNode,
      "output" -> outputNode,
      "ground" -> Ground
    )
      .map((name, node) => port(weightedSource, name) -> node)
    val harness = Increment50Sources.harness()
    write(output, "harness-construction.json", harness.canonicalConstruction + "\n")
    val core = harness.domains(0)
    val related = harness.domains(1)
    val asynchronous = harness.domains(2)
    val levels = ElectricalLevels(0.0.volts, 1.0.volts)
    val stimulus = get(harness.stimulus(
      StimulusStop.seconds,
      Vector(
        ClockDrive(
          core,
          levels,
          ClockRamp.seconds,
          ClockRamp.seconds,
          origin = Some(CoreOrigin.seconds)
        ),
        ClockDrive(related, levels, ClockRamp.seconds, ClockRamp.seconds),
        ClockDrive(
          asynchronous,
          levels,
          ClockRamp.seconds,
          ClockRamp.seconds,
          origin = Some(AsyncOrigin.seconds)
        )
      ),
      Vector(ResetDrive(
        related,
        levels,
        ResetAssertion.seconds,
        ResetRelease.seconds,
        ClockRamp.seconds,
        ClockRamp.seconds
      ))
    ))
    write(
      output,
      "domain-descriptors.txt",
      harness.domains.map(domain =>
        s"${domain.semanticPath}\tparent=${domain.parent.map(_.semanticPath)}\tfrequencyHz=${domain.frequencyHz}\tedge=${domain.edge}\tphaseDegrees=${domain.phaseDegrees}\tresetPolicy=${domain.resetPolicy}\tresetPolarity=${domain.resetPolarity}\tresetSource=${domain.resetSource}\trawResetSource=${domain.rawResetSource}\tsynchronizationStages=${domain.synchronizationStages}"
      ).mkString("\n") + "\n"
    )
    val resetReference = stimulus.resets.head.reference
    expect(
      math.abs(resetReference.policyReleaseAt.siValue - (RelatedOrigin + 6.0 / 1.5e6)) < 1e-18 &&
        resetReference.stagedReleaseAt.exists(time =>
          math.abs(time.siValue - (RelatedOrigin + 7.0 / 1.5e6)) < 1e-18
        ),
      "policy and explicit synchronizer references independently sample raw release"
    )
    expect(
      resetReference.rawReleaseAt.siValue == ResetRelease &&
        resetReference.policyAssertAt.siValue == ResetAssertion,
      "raw reset time and asynchronous assertion are retained"
    )
    write(
      output,
      "reset-reference.txt",
      stimulus.resets.map: value =>
        val reference = value.reference
        s"source=${value.source}\trawAssertSeconds=${reference.rawAssertAt.siValue}\trawReleaseSeconds=${reference.rawReleaseAt.siValue}\tpolicyAssertSeconds=${reference.policyAssertAt.siValue}\tpolicyReleaseSeconds=${reference.policyReleaseAt.siValue}\tstagedReleaseSeconds=${reference.stagedReleaseAt.map(_.siValue)}"
      .mkString("\n") + "\n"
    )
    val waveformRows = stimulus.clocks.map(value => value.domain.semanticPath -> value.waveform) ++
      stimulus.resets.map(value => (value.domain.semanticPath + ".rawReset") -> value.waveform)
    write(
      output,
      "domain-waveforms.tsv",
      "source\tseconds\tvolts\n" + waveformRows.flatMap((name, waveform) =>
        waveform.points.map((time, voltage) => s"$name\t${time.siValue}\t${voltage.siValue}")
      ).mkString("\n") + "\n"
    )
    val clockDrives =
      stimulus.clocks.zip(Vector(clockNode, relatedNode, asyncNode)).zipWithIndex.map:
        case ((value, node), index) => ElectricalDrive.voltage(
            index,
            node,
            Ground,
            value.waveform.points.head._2,
            waveform = Some(value.waveform)
          )
    val resetDrive = ElectricalDrive.voltage(
      3,
      resetNode,
      Ground,
      stimulus.resets.head.waveform.points.head._2,
      waveform = Some(stimulus.resets.head.waveform)
    )
    val outputProbe = Probe.voltage("weighted-output", outputNode)
    val clockBench = get(SimulationBench.create(
      weighted,
      weightedBindings,
      clockDrives :+ resetDrive,
      SimulationAnalysis.transient(0.01e-6.seconds, StimulusStop.seconds, false),
      Vector(outputProbe)
    ))
    val clockResult = SimulationSession.run(clockBench, simulatorTools)
    receipt("clock-reset-electrical", clockResult)
    if clockResult.succeeded then
      val selected = (0 until clockResult.sampleCount).filter(index =>
        referenceSample(get(clockResult.axis(index)))
      )
      expect(
        selected.size > 300,
        "weighted clock/reset reference checks more than 300 plateau samples"
      )
      selected.foreach: index =>
        numerical(
          "clock-reset-electrical",
          clockResult,
          outputProbe,
          index,
          weightedReference(get(clockResult.axis(index))).volts,
          voltageTolerance
        )

    val sweepClock = ElectricalDrive.voltage(0, clockNode, Ground, 0.0.volts)
    val sweepRelated = ElectricalDrive.voltage(1, relatedNode, Ground, 0.0.volts)
    val sweepAsync = ElectricalDrive.voltage(2, asyncNode, Ground, 0.0.volts)
    val sweepReset = ElectricalDrive.voltage(3, resetNode, Ground, 1.0.volts)
    val sweepBench = get(SimulationBench.create(
      weighted,
      weightedBindings,
      Vector(sweepClock, sweepRelated, sweepAsync, sweepReset),
      SimulationAnalysis.dc(sweepAsync, 0.0.volts, 0.5.volts, 0.5.volts),
      Vector(outputProbe)
    ))
    val axes = Vector(
      SweepAxis("clock", sweepClock, Vector(0.0.volts, 1.0.volts)),
      SweepAxis("related", sweepRelated, Vector(0.25.volts, 0.75.volts))
    )
    val sweep = get(SimulationSweep.run(sweepBench, axes, simulatorTools))
    expect(
      sweep.size == 4 && sweep.map(_.identity).distinct.size == 4,
      "two-dimensional sweep has four unique deterministic point identities"
    )
    expect(
      sweep.map(_.coordinates.map(_.ordinal)) ==
        Vector(Vector(0, 0), Vector(0, 1), Vector(1, 0), Vector(1, 1)),
      "Cartesian sweep last axis varies fastest"
    )
    write(
      output,
      "sweep-coordinates.tsv",
      sweep.map(point =>
        s"${point.identity}\t${point.coordinates.mkString(";")}"
      ).mkString("\n") + "\n"
    )
    sweep.zipWithIndex.foreach: (point, ordinal) =>
      receipt(s"cartesian-$ordinal", point.result)
      if point.result.succeeded then
        (0 until point.result.sampleCount).foreach: index =>
          val expected = point.coordinates(0).value + 2 * point.coordinates(1).value +
            4 * get(point.result.axis(index)) + 8
          numerical(
            s"cartesian-$ordinal",
            point.result,
            outputProbe,
            index,
            expected.volts,
            voltageTolerance
          )
    val oversized = Vector(
      SweepAxis("clock", sweepClock, Vector.fill(12)(0.0.volts)),
      SweepAxis("related", sweepRelated, Vector.fill(12)(0.0.volts))
    )
    val rejectedSweepTools =
      simulatorTools.copy(evidenceRoot = output.resolve("rejected-sweep-must-not-exist"))
    expect(
      SimulationSweep.run(sweepBench, oversized, rejectedSweepTools)
        .left.toOption.exists(_.code == "SIM-SWEEP"),
      "144-point Cartesian expansion rejects before any execution"
    )
    expect(
      !Files.exists(rejectedSweepTools.evidenceRoot),
      "rejected sweep creates no tool evidence or processes"
    )
    val retentionRejected = SimulationSweep.run(
      transientBench,
      Vector(SweepAxis("current", ramp, Vector.fill(21)(0.001.amps))),
      rejectedSweepTools
    )
    expect(
      retentionRejected.left.toOption.exists(error =>
        error.code == "SIM-SWEEP" &&
          error.message.endsWith("sweep worst-case retained values exceed 4000000 cells")
      ),
      "aggregate retained transient results reject before allocation or execution"
    )
    expect(
      !Files.exists(rejectedSweepTools.evidenceRoot),
      "retention rejection creates no simulator evidence or processes"
    )
    expect(
      SimulationSweep.run(
        sweepBench,
        Vector(SweepAxis("foreign", foreignDrive, Vector(0.0.volts))),
        rejectedSweepTools
      ).isLeft,
      "foreign sweep source rejects"
    )
    expect(
      SimulationSweep.run(
        sweepBench,
        Vector(SweepAxis("controlled", sweepAsync, Vector(0.0.volts))),
        rejectedSweepTools
      ).isLeft,
      "sweep cannot override the analysis-controlled source"
    )

    // Two truly separate source captures and compiler/session directories run concurrently.
    val executor = Executors.newFixedThreadPool(2)
    given ExecutionContext = ExecutionContext.fromExecutorService(executor)
    try
      val jobs = Vector(1000.0, 2000.0).map: resistance =>
        Future:
          val source = Increment50Sources.rc(resistance, 1e-6)
          val compiled = SimulationCompiler.compile(source, compilerTools)
          require(compiled.succeeded, s"concurrent compilation failed: ${compiled.error}")
          val drive = ElectricalDrive.voltage(0, Positive, Ground, 0.0.volts)
          val probe = Probe.current("independent-current", drive)
          val bench = get(SimulationBench.create(
            compiled.model.get,
            rcBindings(source),
            Vector(drive),
            SimulationAnalysis.dc(drive, 0.0.volts, 1.0.volts, 0.5.volts),
            Vector(probe)
          ))
          (resistance, compiled.model.get, probe, SimulationSession.run(bench, simulatorTools))
      val independent = Await.result(Future.sequence(jobs), 3.minutes)
      independent.zipWithIndex.foreach: (value, ordinal) =>
        val (resistance, _, probe, result) = value
        receipt(s"concurrent-$ordinal", result)
        if result.succeeded then
          (0 until result.sampleCount).foreach: index =>
            numerical(
              s"concurrent-$ordinal",
              result,
              probe,
              index,
              (-get(result.axis(index)) / resistance).amps,
              currentTolerance
            )
      expect(
        independent.map(_._2.evidence.directory).distinct.size == 2 &&
          independent.map(_._4.evidence.directory).distinct.size == 2,
        "concurrent independent captures have disjoint compilation and simulation evidence"
      )
      expect(
        independent.map(_._2.evidence.modelSha256).distinct.size == 2,
        "distinct constructor parameters survive independent source compilation"
      )
    finally executor.shutdown()

    val unsupported = get(SimulationSource.capture(new Increment50DigitalDomainDut)(model =>
      Vector(PortSelection("z", model.z), PortSelection("a", model.a))
    ))
    write(output, "unsupported-dut-construction.json", unsupported.canonicalConstruction + "\n")
    val rejected = SimulationCompiler.compile(unsupported, compilerTools)
    expect(
      !rejected.succeeded && rejected.error.nonEmpty,
      "actual digital-domain DUT is rejected without stripping its unsupported domain"
    )
    outcomes +=
      s"EXPECTED-COMPILE-FAILURE\t${rejected.error}\t${rejected.evidence.map(_.directory)}"
    val missingSimulator = SimulationSession.run(
      dcBench,
      simulatorTools.copy(executable = output.resolve("missing-ngspice"))
    )
    expect(
      !missingSimulator.succeeded && missingSimulator.error.nonEmpty &&
        missingSimulator.sampleCount == 0,
      "simulator invocation failure cannot count as numerical success"
    )
    expect(
      missingSimulator.measure(currentProbe, SampleReduction.Last).isLeft,
      "failed invocation cannot produce a measurement"
    )
    outcomes +=
      s"EXPECTED-SIMULATOR-FAILURE\t${missingSimulator.evidence.outcome}\t${missingSimulator.error}"

    write(output, "numerical-checks.tsv", checks.mkString("\n") + "\n")
    write(output, "simulation-receipts.tsv", receipts.mkString("\n") + "\n")
    write(output, "outcomes.tsv", outcomes.mkString("\n") + "\n")
    write(
      output,
      "summary.txt",
      s"passed=${failures.isEmpty}\nnumericalChecks=${checks.size -
          1}\nsimulations=${receipts.size - 1}\nfailures=${failures.size}\n" +
        failures.mkString("\n") + "\n"
    )
    println(
      s"F50 public replay: numericalChecks=${checks.size - 1}; failures=${failures.size}; evidence=$output"
    )
    require(failures.isEmpty, failures.mkString("F50 public replay failed:\n", "\n", ""))
