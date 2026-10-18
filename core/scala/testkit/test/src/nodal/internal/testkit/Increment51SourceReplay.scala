package nodal.internal.testkit

import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.time.Duration

import scala.collection.mutable

import nodal.*
import nodal.internal.integrations.*
import nodal.sim.*

/** Generated F50 RC model replay through the public collected and non-collecting result APIs.
  * References are fixed independently before compilation. Arguments: output-root nodalc translator
  * openvaf compiler-profile ngspice simulator-profile HEAD. All run artifacts stay under .work.
  */
object Increment51SourceReplay:
  private val Ground = SimulationNode.Ground
  private val Positive = SimulationNode.Node(0)
  private val Authored = WaveformProvenance("independent RC equations, R=1000 ohm, C=1e-6 F")

  private def get[A](value: Either[SimulationError, A]): A =
    value.fold(
      error =>
        scala.util.Failure[A](new IllegalArgumentException(s"${error.code}: ${error.message}")).get,
      identity
    )

  private def write(directory: Path, name: String, text: String): Unit =
    val _ = Files.writeString(
      directory.resolve(name),
      text,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE_NEW
    )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(value => f"${value & 0xff}%02x").mkString

  private def repositoryRoot(path: Path): Path =
    if Files.isRegularFile(path.resolve("build.mill")) then path
    else
      val parent = Option(path.getParent)
      require(parent.nonEmpty, "replay output must be below the repository")
      repositoryRoot(parent.get)

  private def heapBytes: Long = Runtime.getRuntime.totalMemory() - Runtime.getRuntime.freeMemory()

  private def dcReference(offsetAmps: Double =
    0.0): Waveform[Quantity[VoltageDimension], CurrentDimension] =
    get(Waveform.reference(
      WaveformAxis.VoltageSweep,
      WaveformUnit.Current,
      Vector(-1.0, -0.5, 0.0, 0.5, 1.0).map(volts =>
        WaveformSample(
          volts.volts,
          ComplexQuantity.real((offsetAmps + Increment49References.dcSourceCurrent(volts)).amps)
        )
      ),
      Authored
    ))

  private def acReference: Waveform[Frequency, CurrentDimension] =
    get(Waveform.reference(
      WaveformAxis.Frequency,
      WaveformUnit.Current,
      Vector(10.0, 10000.0).map { hertz =>
        val expected = Increment49References.acSourceCurrent(hertz)
        WaveformSample(
          Frequency.hertz(hertz),
          ComplexQuantity(expected.real.amps, expected.imaginary.amps)
        )
      },
      Authored
    ))

  private def transientReference: Waveform[Quantity[TimeDimension], VoltageDimension] =
    get(Waveform.reference(
      WaveformAxis.Time,
      WaveformUnit.Voltage,
      Vector.tabulate(10001) { index =>
        val seconds = index.toDouble / 1000000.0
        WaveformSample(
          seconds.seconds,
          ComplexQuantity.real(Increment49References.rcVoltage(seconds).volts)
        )
      },
      Authored
    ))

  /** Measurement, not a universal benchmark: one 100k-row input, scalar reducer versus collector.
    * Weak references observe reachability after a GC request; GC timing is deliberately not a pass
    * criterion. Heap/timing observations include JVM noise and are never claimed constant memory.
    */
  private def measureLongTrace(output: Path): Unit =
    val count = 100000
    val raw = output.resolve("long-trace.raw")
    val writer =
      Files.newBufferedWriter(raw, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW)
    try
      writer.write(
        s"Title: independent affine trace\nPlotname: Transient Analysis\nFlags: real\nNo. Variables: 2\nNo. Points: $count\nVariables:\n0 time time\n1 v(n0) voltage\nValues:\n"
      )
      var index = 0
      while index < count do
        writer.write(s"$index\t${index.toDouble}\n\t${3.0 * index + 2.0}\n")
        index += 1
    finally writer.close()
    val reference = get(Waveform.reference(
      WaveformAxis.Time,
      WaveformUnit.Voltage,
      Vector(0.0, count.toDouble - 1).map(time =>
        WaveformSample(time.seconds, ComplexQuantity.real((3.0 * time + 2.0).volts))
      ),
      WaveformProvenance("independent affine reference y=3*t+2")
    ))
    write(output, "long-trace-reference.waveform", get(reference.serialize))
    val watched = mutable.ArrayBuffer.empty[WeakReference[Vector[NgspiceComplex]]]
    System.gc()
    val foldBefore = heapBytes
    var foldObserved = foldBefore
    val foldStart = System.nanoTime()
    val input = Files.newInputStream(raw)
    val folded = try
      NgspiceRaw.fold(input, NgspiceAnalysisKind.Transient)(_ =>
        Right(WaveformComparison.reducer(
          reference,
          Tolerance(1e-9.volts, 0.0),
          alignment = WaveformAlignment.Linear
        ))
      )((state, index, row) =>
        if index % 1000 == 0 then
          watched += new WeakReference(row)
          foldObserved = math.max(foldObserved, heapBytes)
        state.add(
          row.head.real.seconds,
          ComplexQuantity(row(1).real.volts, row(1).imaginary.volts)
        )
          .left.map(_.message).map(_ => state)
      )
    finally input.close()
    val foldedComparison = get(folded.fold(
      message => Left(SimulationError("REPLAY-FOLD", message)),
      _.finish()
    ))
    val foldElapsed = System.nanoTime() - foldStart
    require(
      foldedComparison.passed && foldedComparison.sampleCount == count,
      s"long trace fold mismatch: $foldedComparison"
    )
    System.gc()
    val foldAfter = heapBytes
    val liveSampledRows = watched.count(reference => Option(reference.get()).nonEmpty)

    val collectStart = System.nanoTime()
    val collectBefore = heapBytes
    var collectObserved = collectBefore
    val collectingInput = Files.newInputStream(raw)
    val collected = try
      NgspiceRaw.fold(collectingInput, NgspiceAnalysisKind.Transient)(_ =>
        Right(Vector.empty[Vector[NgspiceComplex]])
      )((rows, index, row) =>
        if index % 1000 == 0 then collectObserved = math.max(collectObserved, heapBytes)
        Right(rows :+ row)
      )
    finally collectingInput.close()
    val rows = collected.fold(
      message =>
        scala.util.Failure[Vector[Vector[NgspiceComplex]]](new IllegalArgumentException(
          message
        )).get,
      identity
    )
    val collectElapsed = System.nanoTime() - collectStart
    System.gc()
    val collectAfter = heapBytes
    require(
      rows.size == count && rows.indices.forall(index =>
        rows(index).head.real == index.toDouble && rows(index)(1).real == 3.0 * index + 2.0
      ),
      "collected long trace differs from independent affine equation"
    )
    write(
      output,
      "long-trace-retention.tsv",
      "mode\tpoints\tresultRetainedRows\treferencePoints\tinputBytes\telapsedNanos\theapBefore\tobservedHeap\theapAfterGcRequest\tsampledRowsAliveAfterGcRequest\n" +
        s"fold\t$count\t0\t2\t${Files.size(raw)}\t$foldElapsed\t$foldBefore\t$foldObserved\t$foldAfter\t$liveSampledRows/${watched.size}\n" +
        s"collector\t$count\t${rows.size}\t2\t${Files.size(raw)}\t$collectElapsed\t$collectBefore\t$collectObserved\t$collectAfter\tretained\n"
    )
    write(
      output,
      "measurement-limitations.txt",
      "One representative 100000-point affine trace, not a universal benchmark. Both paths use the same row parser and neither reads the raw file into a byte vector. The fold result retains only comparison state and a two-sample independent reference; the collector intentionally retains 100000 rows (200000 complex cells). Observed heap includes allocation/JIT/GC noise, is sampled rather than a peak, and a GC request is not a guarantee of collection. No runtime or constant-process-memory claim follows. Heap observations include preceding replay results. Sweep observed heap is sampled after execution and during reference processing, not an execution peak. The public SimulationSweep remains collecting and bounded by its existing retention guard.\n"
    )

  def main(arguments: Array[String]): Unit =
    require(
      arguments.length == 8,
      "expected output-root nodalc translator openvaf compiler-profile ngspice simulator-profile HEAD"
    )
    val root = Path.of(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val output = Files.createTempDirectory(root, "waveform-replay-")
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
    val outcomes = mutable.ArrayBuffer.empty[String]
    val comparisons = mutable.ArrayBuffer(
      "case\tmode\tcomponent\talignment\tsamples\tfailures\tmaxAbsoluteError\tmeanAbsoluteError\trmsError"
    )
    val receipts = mutable.ArrayBuffer(
      "case\tmode\toutcome\tsamples\trequestSha256\tmodelSha256\tartifactSha256\trawSha256\tevidence"
    )
    def expect(condition: Boolean, description: String): Unit =
      outcomes += s"${if condition then "PASS" else "FAIL"}\t$description"
      if !condition then failures += description
    def recordEvidence(
        name: String,
        mode: String,
        samples: Int,
        evidence: SimulationEvidence
    ): Unit =
      receipts += Vector(
        name,
        mode,
        evidence.outcome,
        samples.toString,
        evidence.requestSha256.getOrElse(""),
        evidence.modelSha256,
        evidence.artifactSha256.getOrElse(""),
        evidence.rawSha256.getOrElse(""),
        evidence.directory.fold("")(_.toString)
      ).mkString("\t")
      expect(
        evidence.rawPath.exists(path =>
          evidence.rawSha256.contains(sha256(Files.readAllBytes(path)))
        ),
        s"$name $mode raw digest matches the exact retained artifact"
      )
    def recordComparison(
        name: String,
        mode: String,
        component: ComplexComponent,
        alignment: WaveformAlignment,
        comparison: WaveformComparisonResult
    ): Unit =
      comparisons += Vector(
        name,
        mode,
        component.toString,
        alignment.toString,
        comparison.sampleCount.toString,
        comparison.failureCount.toString,
        comparison.maximumAbsoluteError.toString,
        comparison.meanAbsoluteError.toString,
        comparison.rootMeanSquareError.toString
      ).mkString("\t")

    val dcExpected = dcReference()
    val acExpected = acReference
    val transientExpected = transientReference
    write(
      output,
      "references.txt",
      "Independent references fixed before compilation or simulation.\n" +
        "RC R=1000 ohm, C=1e-6 F from the retained F50 source fixture. DC exact grid [-1,-.5,0,.5,1] V: source current=-V/R.\n" +
        "AC 5 linearly spaced simulation points from 10 to 10000 Hz, unit voltage drive: source current=-(1/R+j*2*pi*f*C). Independent endpoint reference uses explicit linear complex interpolation, then real/imaginary/magnitude projection.\n" +
        "Transient ramp I(t) rises 0 to .001 A from .001 to .0011 s, initial voltage=0, stop=.01 s. Independent analytic ODE solution from Increment49References sampled every 1 us; adaptive actual rows use explicit linear alignment, no extrapolation.\n" +
        "DC/AC tolerance: 1e-10 A + 1e-6*abs(reference); transient: 2e-5 V + .002*abs(reference).\n" +
        "Sweep: 32 independent current injections i*.00005 A for i=0..31, each 1001 DC rows from -1 to +1 V in .002 V steps. Current through driven voltage source=Iinjected-V/R. Independent reference endpoints -2,+2 V span the requested range; explicit Linear alignment accommodates the native decimal-grid representation without extrapolation. All 32032 rows remain collected under F50 bounds.\n"
    )
    write(output, "dc-reference.waveform", get(dcExpected.serialize))
    write(output, "ac-reference.waveform", get(acExpected.serialize))
    write(output, "transient-reference.waveform", get(transientExpected.serialize))
    write(
      output,
      "run.txt",
      s"baseHEAD=${arguments(7)}\nsourceState=working tree; base HEAD does not certify a clean or published tree\ncompilerProfile=${arguments(4)}\nsimulatorProfile=${arguments(6)}\n"
    )
    val sourcePaths = Vector(
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment51SourceReplay.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment51WaveformTests.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment50SimulationTests.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment49SourceReplay.scala",
      "core/scala/sim/src/nodal/sim/Waveform.scala",
      "core/scala/sim/src/nodal/sim/WaveformSimulation.scala",
      "core/scala/sim/src/nodal/sim/SimulationBench.scala",
      "core/scala/sim/src/nodal/sim/SimulationCompilation.scala",
      "core/scala/sim/src/nodal/sim/SimulationSweep.scala",
      "core/integrations/src/nodal/internal/integrations/NgspiceRaw.scala",
      "core/integrations/src/nodal/internal/integrations/NgspiceProfile.scala",
      "core/integrations/src/nodal/internal/integrations/NgspiceSimulator.scala"
    )
    val inventory = sourcePaths.map: relative =>
      val bytes = Files.readAllBytes(repository.resolve(relative))
      val target = output.resolve("source-files").resolve(relative + ".bytes")
      Files.createDirectories(target.getParent)
      Files.write(target, bytes, StandardOpenOption.CREATE_NEW)
      s"$relative\t${sha256(bytes)}\t${bytes.length}"
    write(output, "source-files.tsv", "path\tsha256\tbytes\n" + inventory.mkString("\n") + "\n")

    val source = Increment50Sources.rc()
    write(output, "source-construction.json", source.canonicalConstruction + "\n")
    val compiled = SimulationCompiler.compile(source, compilerTools)
    require(compiled.succeeded, s"generated RC compilation failed: ${compiled.error}")
    val model = compiled.model.get
    write(output, "rc-generated.va", Files.readString(model.evidence.directory.resolve("model.va")))
    val bindings =
      Vector(get(source.port("positive")) -> Positive, get(source.port("ground")) -> Ground)
    val drive = ElectricalDrive.voltage(0, Positive, Ground, 0.0.volts, ac = Some(1.0.volts))
    val current = Probe.current("source-current", drive)
    val currentTolerance = Tolerance(1e-10.amps, 1e-6)
    val dcAnalysis = SimulationAnalysis.dc(drive, (-1.0).volts, 1.0.volts, 0.5.volts)
    val dcBench =
      get(SimulationBench.create(model, bindings, Vector(drive), dcAnalysis, Vector(current)))

    def comparePair[A, D](
        name: String,
        bench: SimulationBench,
        probe: Probe[D],
        expected: Waveform[A, D],
        tolerance: Tolerance[D],
        alignment: WaveformAlignment,
        components: Vector[ComplexComponent]
    ): SimulationResult =
      val collected = SimulationSession.run(bench, simulatorTools)
      require(collected.succeeded, s"$name collected execution failed: ${collected.error}")
      recordEvidence(name, "collected", collected.sampleCount, collected.evidence)
      val waveform = get(collected.waveform(expected.axis, probe))
      val encoded = get(waveform.serialize)
      val decoded = get(WaveformCodec.decode(encoded, expected.axis, expected.unit))
      expect(
        encoded == get(waveform.serialize) && encoded == get(decoded.serialize),
        s"$name serialization is deterministic and byte-exact after typed roundtrip"
      )
      expect(
        waveform.provenance.rawSha256 == collected.evidence.rawSha256 &&
          waveform.provenance.modelSha256.contains(collected.evidence.modelSha256),
        s"$name waveform preserves executed artifact provenance"
      )
      write(output, s"$name-collected.waveform", encoded)
      components.foreach: component =>
        val comparison = get(waveform.compare(expected, tolerance, component, alignment))
        recordComparison(name, "collected", component, alignment, comparison)
        expect(comparison.passed, s"$name $component collected independent reference comparison")
        val streamed = SimulationSession.compare(
          bench,
          simulatorTools,
          probe,
          expected,
          tolerance,
          component,
          alignment
        )
        expect(
          streamed.succeeded,
          s"$name $component streaming execution succeeds: ${streamed.error}"
        )
        streamed.evidence.foreach(recordEvidence(
          name,
          s"streamed-$component",
          streamed.sampleCount,
          _
        ))
        streamed.comparison.foreach: summary =>
          recordComparison(name, "streamed", component, alignment, summary)
          expect(summary.passed, s"$name $component streaming independent reference comparison")
          expect(
            summary == comparison && streamed.sampleCount == collected.sampleCount,
            s"$name $component collected and non-collecting comparison summaries agree exactly"
          )
        expect(
          streamed.evidence.exists(evidence =>
            evidence.directory != collected.evidence.directory &&
              evidence.requestSha256 == collected.evidence.requestSha256 &&
              evidence.modelSha256 == collected.evidence.modelSha256 &&
              evidence.artifactSha256 == collected.evidence.artifactSha256
          ),
          s"$name $component fresh streaming run preserves request/model/artifact identity"
        )
      println(
        s"F51 $name: samples=${collected.sampleCount}; components=${components.mkString(",")}"
      )
      collected

    val dc = comparePair(
      "rc-dc",
      dcBench,
      current,
      dcExpected,
      currentTolerance,
      WaveformAlignment.Exact,
      Vector(ComplexComponent.Real)
    )
    val acBench = get(SimulationBench.create(
      model,
      bindings,
      Vector(drive),
      SimulationAnalysis.acLinear(5, Frequency.hertz(10), Frequency.hertz(10000)),
      Vector(current)
    ))
    val ac = comparePair(
      "rc-ac",
      acBench,
      current,
      acExpected,
      currentTolerance,
      WaveformAlignment.Linear,
      Vector(ComplexComponent.Real, ComplexComponent.Imaginary, ComplexComponent.Magnitude)
    )
    expect(
      get(ac.waveform(WaveformAxis.Frequency, current)).compare(
        acExpected,
        currentTolerance
      ).isLeft,
      "different AC grids reject default exact alignment"
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
    val voltage = Probe.voltage("rc-voltage", Positive)
    val transientBench = get(SimulationBench.create(
      model,
      bindings,
      Vector(ramp),
      SimulationAnalysis.transient(1e-5.seconds, 0.01.seconds, true),
      Vector(voltage),
      Vector(SimulationInitialCondition(Positive, 0.0.volts))
    ))
    val transient = comparePair(
      "rc-transient",
      transientBench,
      voltage,
      transientExpected,
      Tolerance(2e-5.volts, 0.002),
      WaveformAlignment.Linear,
      Vector(ComplexComponent.Real)
    )
    expect(
      transient.sampleCount > 1000 && transient.sampleCount != transientExpected.sampleCount,
      "adaptive transient rows differ from independently sampled reference"
    )
    expect(
      dc.waveform(WaveformAxis.Time, current).isLeft,
      "collected result rejects an incompatible physical axis"
    )
    val lookalike = Probe.current("source-current", drive)
    expect(
      dc.waveform(WaveformAxis.VoltageSweep, lookalike).isLeft,
      "collected result rejects a distinct probe with identical name and source"
    )

    val noExecution = simulatorTools.copy(evidenceRoot = output.resolve("preflight-must-not-exist"))
    val foreign =
      SimulationSession.compare(dcBench, noExecution, lookalike, dcExpected, currentTolerance)
    val wrongAxis =
      SimulationSession.compare(dcBench, noExecution, current, acExpected, currentTolerance)
    expect(
      Vector(foreign, wrongAxis).forall(result =>
        !result.succeeded && result.evidence.isEmpty &&
          result.error.nonEmpty && result.comparison.isEmpty && result.sampleCount == 0
      ),
      "foreign probe and wrong axis reject before simulator execution"
    )
    expect(!Files.exists(noExecution.evidenceRoot), "preflight errors create no tool evidence")

    val mismatch = SimulationSession.compare(
      dcBench,
      simulatorTools,
      current,
      dcReference(0.1),
      currentTolerance
    )
    expect(
      mismatch.succeeded && !mismatch.passed && mismatch.error.isEmpty &&
        mismatch.sampleCount == 5 && mismatch.comparison.exists(_.failureCount == 5) &&
        mismatch.evidence.exists(_.outcome == "Simulated"),
      "numerical mismatches remain completed comparisons with successful execution evidence"
    )
    mismatch.evidence.foreach(recordEvidence(
      "intentional-mismatch",
      "streamed",
      mismatch.sampleCount,
      _
    ))
    mismatch.comparison.foreach(recordComparison(
      "intentional-mismatch",
      "streamed",
      ComplexComponent.Real,
      WaveformAlignment.Exact,
      _
    ))
    val missing = SimulationSession.compare(
      dcBench,
      simulatorTools.copy(executable = output.resolve("missing-ngspice")),
      current,
      dcExpected,
      currentTolerance
    )
    expect(
      !missing.succeeded && !missing.passed && missing.error.nonEmpty &&
        missing.sampleCount == 0 && missing.comparison.isEmpty,
      "execution failure cannot return a numerical comparison or trusted count"
    )

    val injected = ElectricalDrive.current(1, Ground, Positive, 0.0.amps)
    val sweepBench = get(SimulationBench.create(
      model,
      bindings,
      Vector(drive, injected),
      SimulationAnalysis.dc(drive, (-1.0).volts, 1.0.volts, 0.002.volts),
      Vector(current)
    ))
    val offsets = Vector.tabulate(32)(index => index * 0.00005)
    System.gc()
    val sweepBefore = heapBytes
    val sweepStart = System.nanoTime()
    val sweep = get(SimulationSweep.run(
      sweepBench,
      Vector(SweepAxis("injection", injected, offsets.map(_.amps))),
      simulatorTools
    ))
    val sweepElapsed = System.nanoTime() - sweepStart
    var sweepObserved = heapBytes
    System.gc()
    val sweepAfter = heapBytes
    expect(
      sweep.map(_.coordinates.head.value) == offsets,
      "retained sweep keeps requested independent injection order"
    )
    sweep.zipWithIndex.foreach: (point, ordinal) =>
      require(point.result.succeeded, s"sweep $ordinal failed: ${point.result.error}")
      sweepObserved = math.max(sweepObserved, heapBytes)
      expect(point.result.sampleCount == 1001, s"retained sweep $ordinal has 1001 validated rows")
      val expected = get(Waveform.reference(
        WaveformAxis.VoltageSweep,
        WaveformUnit.Current,
        Vector(-2.0, 2.0).map(volts =>
          WaveformSample(
            volts.volts,
            ComplexQuantity.real((offsets(ordinal) - volts / 1000.0).amps)
          )
        ),
        Authored
      ))
      write(output, s"sweep-$ordinal-reference.waveform", get(expected.serialize))
      val comparison = get(get(point.result.waveform(WaveformAxis.VoltageSweep, current))
        .compare(expected, currentTolerance, alignment = WaveformAlignment.Linear))
      expect(comparison.passed, s"retained sweep $ordinal independent current reference")
      recordEvidence(
        s"sweep-$ordinal",
        "collected",
        point.result.sampleCount,
        point.result.evidence
      )
      recordComparison(
        s"sweep-$ordinal",
        "collected",
        ComplexComponent.Real,
        WaveformAlignment.Linear,
        comparison
      )
    val retainedRows = sweep.map(_.result.sampleCount).sum
    write(
      output,
      "sweep-retention.tsv",
      "runs\tretainedRows\tretainedComplexCells\telapsedNanos\theapBefore\tobservedHeap\theapAfterGcRequest\n" +
        s"${sweep.size}\t$retainedRows\t${retainedRows * 2}\t$sweepElapsed\t$sweepBefore\t$sweepObserved\t$sweepAfter\n"
    )
    val rejected = SimulationSweep.run(
      transientBench,
      Vector(SweepAxis("injection", ramp, Vector.fill(21)(0.001.amps))),
      noExecution
    )
    expect(
      rejected.left.toOption.exists(error =>
        error.code == "SIM-SWEEP" &&
          error.message.contains("4000000")
      ) && !Files.exists(noExecution.evidenceRoot),
      "streaming support preserves F50 bounded retained-sweep preflight"
    )

    measureLongTrace(output)
    expect(true, "100000-row affine trace fold and collector each match independent reference")
    write(output, "comparison-summaries.tsv", comparisons.mkString("\n") + "\n")
    write(output, "simulation-receipts.tsv", receipts.mkString("\n") + "\n")
    write(output, "outcomes.tsv", outcomes.mkString("\n") + "\n")
    write(
      output,
      "summary.txt",
      s"passed=${failures.isEmpty}\ncomparisons=${comparisons.size -
          1}\nsimulationReceipts=${receipts.size - 1}\nfailures=${failures.size}\n" +
        failures.mkString("\n") + "\n"
    )
    println(
      s"F51 waveform replay: comparisons=${comparisons.size - 1}; failures=${failures.size}; evidence=$output"
    )
    require(failures.isEmpty, failures.mkString("F51 waveform replay failed:\n", "\n", ""))
