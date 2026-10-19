package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.time.Duration
import scala.collection.mutable

import nodal.*
import nodal.sim.*

/** Bounded actual F53 replay. Every fixture is compiled once, then its unchanged OSDI is consumed
  * by both tools. F52 source fixtures and independent equations remain authoritative. This is a
  * finite portability witness, not automatic qualification of the entire F52 corpus.
  */
object Increment53CrossToolReplay:
  private def get[A](value: Either[SimulationError, A]): A =
    value.fold(
      error =>
        scala.util.Failure[A](new IllegalArgumentException(s"${error.code}: ${error.message}")).get,
      identity
    )
  private def hash(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 255}%02x").mkString
  private val Ground = SimulationNode.Ground
  private val Input = SimulationNode.Node(0)
  private val Output = SimulationNode.Node(1)
  private val Origin = WaveformProvenance("Unchanged independent F52/F49 analytical equations")

  def main(args: Array[String]): Unit =
    require(
      args.length == 11,
      "output-root nodalc translator openvaf compiler-profile ngspice ngspice-profile vacask vacask-profile runtime-library-directory base-HEAD"
    )
    require(args(10).matches("[a-fA-F0-9]{40}"))
    val root = Path.of(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val out = Files.createTempDirectory(root, "cross-tool-")
    def path(i: Int): Path = Path.of(args(i)).toAbsolutePath.normalize()
    def write(name: String, text: String): Unit =
      val _ = Files.writeString(
        out.resolve(name),
        text,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW
      )
    val compiler = CompilationTools(
      path(1),
      path(2),
      path(3),
      args(4),
      out.resolve("compilations"),
      Duration.ofSeconds(60),
      None
    )
    val ng = SimulatorTools(path(5), args(6), out.resolve("ngspice"), Duration.ofSeconds(60))
    val va = SimulatorTools(
      path(7),
      args(8),
      out.resolve("vacask"),
      Duration.ofSeconds(60),
      SimulatorBackend.Vacask,
      Vector(path(9))
    )
    val failures = mutable.ArrayBuffer.empty[String]
    val checks = mutable.ArrayBuffer("case\tpassed\tdetail")
    val receipts = mutable.ArrayBuffer(
      "case\toutcome\trequestSha256\tmodelSha256\tartifactSha256\texecutableSha256\tversion\trawSha256\tdirectory"
    )
    val comparisons = mutable.ArrayBuffer("case\tcomponent\talignment\tresult")
    def cell(value: Any): String =
      value.toString.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
    def check(name: String, condition: Boolean, detail: Any): Unit =
      val text = detail.toString.replace('\n', ' ').replace('\t', ' ')
      checks += s"$name\t$condition\t$text"
      if !condition then failures += s"$name: $text"
      println(s"F53 $name: $condition; $text")
    def receipt(name: String, e: SimulationEvidence): Unit =
      receipts += Vector(
        name,
        e.outcome,
        e.requestSha256,
        e.modelSha256,
        e.artifactSha256,
        e.simulatorSha256,
        e.simulatorVersion,
        e.rawSha256,
        e.directory
      ).map(cell).mkString("\t")
      if e.outcome == "Simulated" then
        check(
          s"$name-retained-raw",
          e.rawPath.exists(p => e.rawSha256.contains(hash(Files.readAllBytes(p)))),
          e.rawSha256
        )
    def compile(name: String, source: SimulationSource): CompiledSimulationModel =
      write(s"$name-construction.json", source.canonicalConstruction)
      val result = SimulationCompiler.compile(source, compiler)
      check(s"$name-compile", result.succeeded, result.error)
      val model = result.model.get
      write(s"$name-generated.va", Files.readString(model.evidence.directory.resolve("model.va")))
      write(
        s"$name-compilation.txt",
        s"sourceSha256=${hash(source.canonicalConstruction.getBytes(StandardCharsets.UTF_8))}\nevidence=${model.evidence}\n"
      )
      model
    def pins(
        model: CompiledSimulationModel,
        values: (String, SimulationNode)*
    ): Vector[(ModelPort, SimulationNode)] =
      values.toVector.map((name, node) => get(model.source.port(name)) -> node)
    def exercise[A, D](
        name: String,
        bench: SimulationBench,
        axis: WaveformAxis[A],
        probe: Probe[D],
        unit: WaveformUnit[D],
        coordinate: Double => A,
        equation: Double => ComplexQuantity[D],
        tolerance: Tolerance[D],
        alignment: WaveformAlignment = WaveformAlignment.Exact,
        components: Vector[ComplexComponent] = Vector(ComplexComponent.Real)
    ): Unit =
      // Each backend independently meets the predeclared physical equations at its actual axis.
      Vector("ngspice" -> ng, "vacask" -> va).foreach: (backend, tools) =>
        val result = SimulationSession.run(bench, tools)
        receipt(s"$name-$backend", result.evidence)
        check(s"$name-$backend-run", result.succeeded, result.error)
        if result.succeeded then
          val expected = get(Waveform.reference(
            axis,
            unit,
            Vector.tabulate(result.sampleCount)(i =>
              val x = get(result.axis(i))
              WaveformSample(coordinate(x), equation(x))
            ),
            Origin
          ))
          val actual = get(result.waveform(axis, probe))
          components.foreach: component =>
            val comparison = get(actual.compare(expected, tolerance, component))
            comparisons += s"$name-$backend-independent\t$component\tExact\t$comparison"
            check(s"$name-$backend-independent-$component", comparison.passed, comparison)
      components.foreach: component =>
        val result = SimulationSession.compareTools(
          bench,
          ng,
          va,
          axis,
          probe,
          tolerance,
          component,
          alignment
        )
        result.referenceEvidence.foreach(receipt(s"$name-cross-reference-$component", _))
        result.candidateEvidence.foreach(receipt(s"$name-cross-candidate-$component", _))
        comparisons += s"$name-cross\t$component\t$alignment\t${cell(result)}"
        check(
          s"$name-cross-$component",
          result.passed,
          s"${result.outcome}: ${result.error}; ${result.comparison}"
        )

    write(
      "references.txt",
      Increment52References.declaration +
        "\nRC transient: unchanged F49 rcVoltage equation and tolerance 2e-5 V + 0.002*abs(reference). Current source points (0,0),(0.001,0),(0.0011,0.001),(0.01,0.001), oriented ground to input. Named coarse10us baseline step 1e-5 s is expected to disagree; named refined1us witness changes only both tools common step to 1e-6 s and must pass the unchanged acceptance. Cross-tool Linear is explicit and cannot extrapolate.\n" +
        "DC binary-exact grids preserve strict Exact alignment. RLC linear AC: 5 points, 10.0.10010 Hz. Controlled output=clock+2*related+4*async+8*reset. Deliberate analytical offset 0.1 V must fail unchanged tolerance.\n"
    )
    write(
      "run.txt",
      s"baseHEAD=${args(10)}\nsourceState=working tree, not asserted clean or published\ncompiler=${args(4)}\nreference=${args(6)}\ncandidate=${args(8)}\n"
    )
    write(
      "tool-hashes.tsv",
      Vector(
        1, 2, 3, 5, 7
      ).map(i => s"${path(i)}\t${hash(Files.readAllBytes(path(i)))}").mkString("\n") + "\n"
    )
    val retainedSources = Vector(
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment53CrossToolReplay.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment53CrossToolTests.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment52AnalogFixtures.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment52References.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment50SimulationTests.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment49SourceReplay.scala"
    )
    val repository =
      Iterator.iterate(out)(_.getParent).find(p => Files.isRegularFile(p.resolve("build.mill"))).get
    val sourceHashes = retainedSources.map: relative =>
      val bytes = Files.readAllBytes(repository.resolve(relative))
      val target = out.resolve("source-files").resolve(relative)
      Files.createDirectories(target.getParent)
      Files.write(target, bytes, StandardOpenOption.CREATE_NEW)
      s"$relative\t${hash(bytes)}"
    write("source-hashes.tsv", sourceHashes.mkString("\n") + "\n")
    try
      val diode = compile("diode", Increment52Sources.diode())
      val voltage = ElectricalDrive.voltage(0, Input, Ground, 0.0.volts, Some(1.0.volts))
      val currentProbe = Probe.current("source-current", voltage)
      val diodeBench = get(SimulationBench.create(
        diode,
        pins(diode, "positive" -> Input, "ground" -> Ground),
        Vector(voltage),
        SimulationAnalysis.dc(voltage, (-0.125).volts, 0.5.volts, 0.125.volts),
        Vector(currentProbe),
        solverTolerances = SolverTolerances(currentAbsolute = 1e-18.amps)
      ))
      val currentTolerance = Tolerance(
        Increment52References.DiodeTolerance.absolute.amps,
        Increment52References.DiodeTolerance.relative
      )
      exercise(
        "diode-dc",
        diodeBench,
        WaveformAxis.VoltageSweep,
        currentProbe,
        WaveformUnit.Current,
        _.volts,
        x => ComplexQuantity.real(Increment52References.diodeSourceCurrent(x).amps),
        currentTolerance
      )

      val amplifier = compile("amplifier", Increment52Sources.amplifier(false))
      val outputProbe = Probe.voltage("output", Output)
      val ampBench = get(SimulationBench.create(
        amplifier,
        pins(amplifier, "input" -> Input, "output" -> Output, "ground" -> Ground),
        Vector(voltage),
        SimulationAnalysis.dc(voltage, (-1.0).volts, 1.0.volts, 0.25.volts),
        Vector(outputProbe)
      ))
      val voltageTolerance = Tolerance(
        Increment52References.VoltageTolerance.absolute.volts,
        Increment52References.VoltageTolerance.relative
      )
      exercise(
        "amplifier-dc",
        ampBench,
        WaveformAxis.VoltageSweep,
        outputProbe,
        WaveformUnit.Voltage,
        _.volts,
        x => ComplexQuantity.real(Increment52References.amplifierVoltage(x, false).volts),
        voltageTolerance
      )
      val first = SimulationSession.compareTools(
        ampBench,
        ng,
        va,
        WaveformAxis.VoltageSweep,
        outputProbe,
        voltageTolerance
      )
      val repeat = SimulationSession.compareTools(
        ampBench,
        ng,
        va,
        WaveformAxis.VoltageSweep,
        outputProbe,
        voltageTolerance
      )
      check(
        "repeat-identities",
        first.passed && repeat.passed && first.comparison == repeat.comparison &&
          Vector(
            first.referenceEvidence -> repeat.referenceEvidence,
            first.candidateEvidence -> repeat.candidateEvidence
          ).forall: (left, right) =>
            left.nonEmpty && right.nonEmpty && left.get.requestSha256 == right.get.requestSha256 &&
              left.get.artifactSha256 == right.get.artifactSha256 &&
              left.get.directory != right.get.directory,
        s"${first.outcome}/${repeat.outcome}"
      )
      first.referenceEvidence.foreach(receipt("repeat-first-reference", _))
      first.candidateEvidence.foreach(receipt("repeat-first-candidate", _))
      repeat.referenceEvidence.foreach(receipt("repeat-second-reference", _))
      repeat.candidateEvidence.foreach(receipt("repeat-second-candidate", _))
      val wrong = get(Waveform.reference(
        WaveformAxis.VoltageSweep,
        WaveformUnit.Voltage,
        Vector.tabulate(9)(i => {
          val x = -1 + i * 0.25;
          WaveformSample(
            x.volts,
            ComplexQuantity.real((Increment52References.amplifierVoltage(x, false) + 0.1).volts)
          )
        }),
        Origin
      ))
      val mismatch = SimulationSession.compare(ampBench, va, outputProbe, wrong, voltageTolerance)
      check(
        "deliberate-reference-disagreement",
        mismatch.succeeded && !mismatch.passed && mismatch.comparison.exists(_.failureCount == 9),
        mismatch
      )
      mismatch.evidence.foreach(receipt("deliberate-reference-disagreement", _))
      val foreign = SimulationSession.compareTools(
        ampBench,
        ng,
        va,
        WaveformAxis.VoltageSweep,
        Probe.voltage("foreign", Output),
        voltageTolerance
      )
      check(
        "foreign-probe",
        foreign.outcome == CrossToolOutcome.InvalidRequest && foreign.referenceEvidence.isEmpty,
        foreign
      )
      val unavailable = SimulationSession.compareTools(
        ampBench,
        ng,
        va.copy(executable = out.resolve("missing-vacask")),
        WaveformAxis.VoltageSweep,
        outputProbe,
        voltageTolerance
      )
      check("missing-tool", unavailable.outcome == CrossToolOutcome.ToolFailure, unavailable)
      val profile = SimulationSession.compareTools(
        ampBench,
        ng,
        va.copy(profile = "unqualified-profile"),
        WaveformAxis.VoltageSweep,
        outputProbe,
        voltageTolerance
      )
      check("unknown-profile", profile.outcome == CrossToolOutcome.UnsupportedCapability, profile)

      val controlled = compile("controlled", Increment52Sources.controlled())
      val nodes = Vector.tabulate(5)(SimulationNode.Node(_))
      val drives = Vector.tabulate(4)(i =>
        ElectricalDrive.voltage(i, nodes(i), Ground, Vector(0.25, -0.25, 0.0, 1.0)(i).volts)
      )
      val controlledProbe = Probe.voltage("weighted-output", nodes(4))
      val controlledBench = get(SimulationBench.create(
        controlled,
        pins(
          controlled,
          "clock" -> nodes(0),
          "related" -> nodes(1),
          "async" -> nodes(2),
          "reset" -> nodes(3),
          "output" -> nodes(4),
          "ground" -> Ground
        ),
        drives,
        SimulationAnalysis.dc(drives(2), (-0.5).volts, 0.5.volts, 0.25.volts),
        Vector(controlledProbe)
      ))
      exercise(
        "controlled-dc",
        controlledBench,
        WaveformAxis.VoltageSweep,
        controlledProbe,
        WaveformUnit.Voltage,
        _.volts,
        x => ComplexQuantity.real((0.25 + 2 * -0.25 + 4 * x + 8).volts),
        voltageTolerance
      )

      val sweep = get(SimulationSweep.run(
        controlledBench,
        Vector(SweepAxis("clock", drives(0), Vector(0.0.volts, 0.5.volts))),
        va
      ))
      check(
        "vacask-public-sweep-coordinate-order",
        sweep.map(_.coordinates.head.value) == Vector(0.0, 0.5) &&
          sweep.map(_.identity).distinct.size == 2,
        sweep.map(_.coordinates)
      )
      sweep.zipWithIndex.foreach: (point, ordinal) =>
        receipt(s"vacask-public-sweep-$ordinal", point.result.evidence)
        check(
          s"vacask-public-sweep-$ordinal-independent",
          point.result.succeeded && point.result.sampleCount == 5 &&
            (0 until point.result.sampleCount).forall: index =>
              val x = get(point.result.axis(index))
              val expected = point.coordinates.head.value + 2 * -0.25 + 4 * x + 8
              val actual =
                get(point.result.measure(controlledProbe, SampleReduction.At(index))).value.siValue
              math.abs(actual - expected) <=
                Increment52References.VoltageTolerance.absolute +
                Increment52References.VoltageTolerance.relative * math.abs(expected)
          ,
          point.result.error
        )

      val rlc = compile("rlc", Increment52Sources.rlc())
      val rlcPins = pins(rlc, "input" -> Input, "output" -> Output, "ground" -> Ground)
      val rlcBench = get(SimulationBench.create(
        rlc,
        rlcPins,
        Vector(voltage),
        SimulationAnalysis.acLinear(5, Frequency.hertz(10), Frequency.hertz(10010)),
        Vector(outputProbe)
      ))
      exercise(
        "rlc-ac",
        rlcBench,
        WaveformAxis.Frequency,
        outputProbe,
        WaveformUnit.Voltage,
        Frequency.hertz,
        x => {
          val v = Increment52References.rlcTransfer(x);
          ComplexQuantity(v.real.volts, v.imaginary.volts)
        },
        Tolerance(
          Increment52References.RlcTolerance.absolute.volts,
          Increment52References.RlcTolerance.relative
        ),
        components =
          Vector(ComplexComponent.Real, ComplexComponent.Imaginary, ComplexComponent.Magnitude)
      )
      val strict = SimulationSession.compareTools(
        rlcBench,
        ng,
        va,
        WaveformAxis.Frequency,
        outputProbe,
        Tolerance(0.0.volts, 0.0),
        ComplexComponent.Imaginary
      )
      check(
        "deliberate-zero-tolerance-cross-disagreement",
        strict.outcome == CrossToolOutcome.Disagreement && strict.succeeded && !strict.passed,
        strict
      )
      strict.referenceEvidence.foreach(receipt("zero-tolerance-reference", _))
      strict.candidateEvidence.foreach(receipt("zero-tolerance-candidate", _))
      val unsupported = get(SimulationBench.create(
        rlc,
        rlcPins,
        Vector(voltage),
        SimulationAnalysis.acDecade(4, Frequency.hertz(10), Frequency.hertz(50)),
        Vector(outputProbe)
      ))
      val rejected = SimulationSession.compareTools(
        unsupported,
        ng,
        va,
        WaveformAxis.Frequency,
        outputProbe,
        voltageTolerance
      )
      check(
        "unsupported-analysis-before-launch",
        rejected.outcome == CrossToolOutcome.UnsupportedCapability &&
          rejected.referenceEvidence.isEmpty && rejected.candidateEvidence.isEmpty,
        rejected
      )

      val rejectedSweepTools = va.copy(evidenceRoot = out.resolve("unsupported-sweep-no-execution"))
      val rejectedSweep = SimulationSweep.run(
        unsupported,
        Vector(SweepAxis("bias", voltage, Vector(0.0.volts, 0.5.volts))),
        rejectedSweepTools
      )
      check(
        "unsupported-sweep-before-launch",
        rejectedSweep.isLeft && !Files.exists(rejectedSweepTools.evidenceRoot),
        rejectedSweep
      )

      val rc = compile("rc", Increment52Sources.rc())
      val ramp = ElectricalDrive.current(
        0,
        Ground,
        Input,
        0.0.amps,
        waveform = Vector(0.0 -> 0.0, 0.001 -> 0.0, 0.0011 -> 0.001, 0.01 -> 0.001).map((t, i) =>
          t.seconds -> i.amps
        )
      )
      val rcProbe = Probe.voltage("rc-voltage", Input)
      def rcBench(step: Double): SimulationBench = get(SimulationBench.create(
        rc,
        pins(rc, "positive" -> Input, "ground" -> Ground),
        Vector(ramp),
        SimulationAnalysis.transient(step.seconds, 0.01.seconds, false),
        Vector(rcProbe)
      ))
      val transientTolerance = Tolerance(
        Increment49References.TransientTolerance.absolute.volts,
        Increment49References.TransientTolerance.relative
      )
      val coarseBench = rcBench(1e-5)
      val refinedBench = rcBench(1e-6)
      val coarse = SimulationSession.compareTools(
        coarseBench,
        ng,
        va,
        WaveformAxis.Time,
        rcProbe,
        transientTolerance,
        alignment = WaveformAlignment.Linear
      )
      coarse.referenceEvidence.foreach(receipt("rc-coarse10us-reference", _))
      coarse.candidateEvidence.foreach(receipt("rc-coarse10us-candidate", _))
      comparisons += s"rc-coarse10us-baseline\tReal\tLinear\t${coarse.comparison}"
      check(
        "rc-coarse10us-observed-disagreement",
        coarse.outcome == CrossToolOutcome.Disagreement &&
          coarse.succeeded && coarse.comparison.exists(_.failureCount > 0),
        s"${coarse.outcome}: ${coarse.comparison}"
      )
      val refined = SimulationSession.compareTools(
        refinedBench,
        ng,
        va,
        WaveformAxis.Time,
        rcProbe,
        transientTolerance,
        alignment = WaveformAlignment.Linear
      )
      refined.referenceEvidence.foreach(receipt("rc-refined1us-causal-reference", _))
      refined.candidateEvidence.foreach(receipt("rc-refined1us-causal-candidate", _))
      check(
        "rc-step-only-causal-identity",
        refined.passed &&
          Vector(
            coarse.referenceEvidence -> refined.referenceEvidence,
            coarse.candidateEvidence -> refined.candidateEvidence
          ).forall: (before, after) =>
            before.nonEmpty && after.nonEmpty && before.get.modelSha256 == after.get.modelSha256 &&
              before.get.artifactSha256 == after.get.artifactSha256 &&
              before.get.requestSha256 != after.get.requestSha256,
        s"coarse=${coarse.outcome}; refined=${refined.outcome}; ${refined.comparison}"
      )
      val exactTransient = SimulationSession.compareTools(
        refinedBench,
        ng,
        va,
        WaveformAxis.Time,
        rcProbe,
        transientTolerance
      )
      check(
        "rc-exact-grid-mismatch",
        exactTransient.outcome == CrossToolOutcome.IncompatibleResults &&
          !exactTransient.succeeded,
        s"${exactTransient.outcome}: ${exactTransient.error}"
      )
      exactTransient.referenceEvidence.foreach(receipt("rc-exact-reference", _))
      exactTransient.candidateEvidence.foreach(receipt("rc-exact-candidate", _))
      exercise(
        "rc-refined1us-pwl-transient",
        refinedBench,
        WaveformAxis.Time,
        rcProbe,
        WaveformUnit.Voltage,
        _.seconds,
        x => ComplexQuantity.real(Increment49References.rcVoltage(x).volts),
        Tolerance(
          Increment49References.TransientTolerance.absolute.volts,
          Increment49References.TransientTolerance.relative
        ),
        WaveformAlignment.Linear
      )
    finally
      write("receipts.tsv", receipts.mkString("\n") + "\n")
      write("comparisons.tsv", comparisons.mkString("\n") + "\n")
      write("checks.tsv", checks.mkString("\n") + "\n")
      write("summary.txt", s"failures=${failures.size}\n" + failures.mkString("\n") + "\n")
      println(s"F53 evidence=$out; failures=${failures.size}")
    require(failures.isEmpty, failures.mkString("\n"))
