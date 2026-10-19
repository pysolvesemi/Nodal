package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.time.Duration

import scala.collection.mutable
import scala.util.control.NonFatal

import nodal.*
import nodal.sim.*

/** Maintained scalar F52 replay through the public source/compiler/bench/session/waveform APIs. No
  * target model, compiler fallback, or private simulation entry point is supplied here. Arguments:
  * output-root nodalc translator openvaf compiler-profile ngspice simulator-profile base-HEAD
  * [compile-only|numerical|diode-only]. The default is numerical; hierarchy has a separate hook.
  */
object Increment52SourceReplay:
  private val Ground = SimulationNode.Ground
  private val Input = SimulationNode.Node(0)
  private val Output = SimulationNode.Node(1)
  private val Seed = 0xf052L
  private val Origin =
    WaveformProvenance("F52 independent analytical equations declared before execution")

  private final case class Fixture(
      id: String,
      capture: () => SimulationSource,
      analyses: String,
      reference: String
  )
  private val fixtures = Vector(
    Fixture("rc", () => Increment52Sources.rc(), "DC,AC,transient", "F49 parallel RC equations"),
    Fixture("rlc", () => Increment52Sources.rlc(), "AC", "H(jw)=1/(1-L*C*w*w+j*R*C*w)"),
    Fixture("diode", () => Increment52Sources.diode(), "DC,AC", "Shockley current and derivative"),
    Fixture("amplifier-affine", () => Increment52Sources.amplifier(false), "DC", "3*Vin+.1 V"),
    Fixture(
      "amplifier-clipped",
      () => Increment52Sources.amplifier(true),
      "DC",
      "clip(3*Vin+.1,-1,1) V"
    ),
    Fixture(
      "comparator",
      () => Increment52Sources.comparator(),
      "DC",
      "Vin<.2 V -> -1 V; otherwise +1 V"
    ),
    Fixture("oscillator", () => Increment52Sources.vco(0), "transient", "sin(2*pi*100*t) V"),
    Fixture(
      "vco",
      () => Increment52Sources.vco(),
      "transient",
      "sin(2*pi*(100*t+50*integral(control))) V"
    ),
    Fixture(
      "initial-step",
      () => Increment52Sources.initialStep(),
      "transient",
      "initial_step stores 1 V"
    ),
    Fixture(
      "controlled",
      () => Increment52Sources.controlled(),
      "DC Cartesian sweep",
      "clock+2*related+4*async+8*reset V"
    )
  )

  private def get[A](result: Either[SimulationError, A]): A =
    result.fold(
      error =>
        scala.util.Failure[A](new IllegalArgumentException(s"${error.code}: ${error.message}")).get,
      identity
    )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(value => f"${value & 0xff}%02x").mkString

  private def write(directory: Path, name: String, content: String): Unit =
    val _ = Files.writeString(
      directory.resolve(name),
      content,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE_NEW
    )

  private def repositoryRoot(path: Path): Path =
    if Files.isRegularFile(path.resolve("build.mill")) then path
    else
      val parent = Option(path.getParent)
      require(parent.nonEmpty, "replay output must be below the repository")
      repositoryRoot(parent.get)

  private def clean(value: Any): String = value.toString.replace('\t', ' ').replace('\n', ' ')

  private def bindings(source: SimulationSource, values: (String, SimulationNode)*)
      : Vector[(ModelPort, SimulationNode)] =
    values.toVector.map((name, node) => get(source.port(name)) -> node)

  private def seededParameters: Vector[(Double, Double)] =
    val random = new java.util.Random(Seed)
    Vector.tabulate(3)(_ => (500.0 + 250.0 * random.nextInt(8), (1 + random.nextInt(4)) * 0.5e-6))

  def main(arguments: Array[String]): Unit =
    require(
      arguments.length == 8 || arguments.length == 9,
      "expected output-root nodalc translator openvaf compiler-profile ngspice simulator-profile base-HEAD [compile-only|numerical|diode-only]"
    )
    val mode = arguments.lift(8).getOrElse("numerical")
    require(Set("compile-only", "numerical", "diode-only").contains(mode), "unknown replay mode")
    require(arguments(7).matches("[a-fA-F0-9]{40}"), "base HEAD must be a full Git revision")
    val root = Path.of(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val output = Files.createTempDirectory(root, s"scalar-$mode-")
    val repository = repositoryRoot(output)
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
    val outcomes = mutable.ArrayBuffer("case\tstage\tstatus\tdetail")
    val compileRows = mutable.ArrayBuffer(
      "case\tsucceeded\terror\tsourceSha256\tmodelSha256\tartifactSha256\tevidence"
    )
    val receipts = mutable.ArrayBuffer(
      "case\tstage\toutcome\tsamples\trequestSha256\tmodelSha256\tartifactSha256\trawSha256\tevidence"
    )
    val comparisons = mutable.ArrayBuffer(
      "case\tcomponent\tsamples\tfailures\tmaximumAbsoluteError\tmeanAbsoluteError\trmsError\tabsTolerance\trelTolerance\tunit"
    )
    val compiled = mutable.LinkedHashMap.empty[String, CompiledSimulationModel]

    // Persist references, stimuli, capability scope and deterministic choices before execution.
    write(
      output,
      "references.txt",
      Increment52References.declaration +
        "Diode DC solver request: current absolute tolerance=1e-18 A, relative=1e-6, voltage absolute=1e-9 V; unchanged acceptance=1e-15 A+1e-5*abs(reference). Other analyses retain the shared default solver settings. No gmin, temperature or model change. The diode-only mode also observes default solver precision on the same compiled artifact; any default numerical discrepancy is reported without requiring that every platform must reproduce it.\n" +
        "RC DC/AC: F49 CurrentTolerance (1e-10 A + 1e-6*abs(reference)); transient finite-rise current source: F49 TransientTolerance (2e-5 V + .002*abs(reference)).\n" +
        "Initial-step: zero-current unloaded source, UIC, 0 V bench initial condition; expected 1 V at all returned times, voltage tolerance 1e-9 V + 1e-6*abs(reference).\n" +
        "Controlled source: Cartesian clock in [-.5,0,.5,1] V, related in [-.25,.25,.75] V; async swept -.5 to .5 V in .25 V steps; reset=1 V. Vout=clock+2*related+4*async+8. Voltage tolerance 1e-9 V+1e-6*abs(reference).\n" +
        "Seeded recapture: java.util.Random seed 61522; R=(500+250*nextInt(8)) ohm, C=(1+nextInt(4))*.5e-6 F; three finite cases. DC I=-V/R; AC I=-(1/R+j*2*pi*f*C). Repeated captures and executions must retain exact identities and physical values.\n" +
        "DC/AC analytical functions are evaluated at actual physical coordinates without reading measured values; exact waveform alignment. VCO/RC independent 1 us reference grids are written before simulation; explicit linear alignment, no extrapolation.\n" +
        "Negative solver stimulus: two inconsistent ideal voltage sources, generated initial_step output=1 V against a 0 V external clamp; expect Nonconvergence, zero trusted samples. Language rejection, bench rejection, unavailable tool, solver nonconvergence and intentional numerical discrepancy remain separate classes.\n"
    )
    write(
      output,
      "run.txt",
      s"baseHEAD=${arguments(7)}\nsourceState=working tree; base HEAD does not certify a clean or published tree\nmode=$mode\ncompilerProfile=${arguments(4)}\nsimulatorProfile=${arguments(6)}\nseed=$Seed\n"
    )
    write(
      output,
      "case-manifest.tsv",
      "case\tanalyses\treference\tcapability\n" +
        fixtures.map(f =>
          s"${f.id}\t${f.analyses}\t${f.reference}\tscalar Verilog-A/OpenVAF/OSDI intersection"
        ).mkString("\n") +
        "\nhierarchy\tseparate materialization replay\tbehavior-bearing symbolic/literal parameter reference\tNOT-EXECUTED-HERE; independently recorded by Increment52HierarchyReplay\n" +
        "crossing-noise\tnone\tnone\tNOT-QUALIFIED in this finite profile\n"
    )
    write(
      output,
      "seeded-parameters.tsv",
      "ordinal\tseed\tresistanceOhms\tcapacitanceFarads\n" +
        seededParameters.zipWithIndex.map((values, index) =>
          s"$index\t$Seed\t${values._1}\t${values._2}"
        ).mkString("\n") + "\n"
    )
    val sourcePaths = Vector(
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment52SourceReplay.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment52AnalogFixtures.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment52References.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment52AdmissionTests.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment50SimulationTests.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment49SourceReplay.scala",
      "examples/continuousTimeApi/src/nodal/increment47fixture/Increment47ConstructionCheck.scala",
      "core/scala/sim/src/nodal/sim/SimulationCompilation.scala",
      "core/scala/sim/src/nodal/sim/SimulationBench.scala",
      "core/scala/sim/src/nodal/sim/SimulationSweep.scala",
      "core/scala/sim/src/nodal/sim/Waveform.scala",
      "core/scala/sim/src/nodal/sim/WaveformSimulation.scala",
      "core/scala/api/src/nodal/CandidateApi.scala",
      "core/scala/api/src/nodal/ConstructionSession.scala",
      "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
      "core/integrations/src/nodal/internal/integrations/OpenVafCompiler.scala",
      "core/integrations/src/nodal/internal/integrations/NgspiceSimulator.scala"
    )
    val retained = sourcePaths.map: relative =>
      val bytes = Files.readAllBytes(repository.resolve(relative))
      val target = output.resolve("source-files").resolve(relative + ".bytes")
      Files.createDirectories(target.getParent)
      Files.write(target, bytes, StandardOpenOption.CREATE_NEW)
      s"$relative\t${sha256(bytes)}\t${bytes.length}"
    write(output, "source-files.tsv", "path\tsha256\tbytes\n" + retained.mkString("\n") + "\n")

    def expect(condition: Boolean, name: String, stage: String, detail: String): Unit =
      outcomes += s"$name\t$stage\t${if condition then "PASS" else "FAIL"}\t${clean(detail)}"
      if !condition then failures += s"$name [$stage] $detail"

    def compile(name: String, capture: () => SimulationSource): Option[CompiledSimulationModel] =
      try
        val source = capture()
        write(output, s"$name-construction.json", source.canonicalConstruction + "\n")
        val result = SimulationCompiler.compile(source, compilerTools)
        val evidence = result.evidence
        compileRows += Vector(
          name,
          result.succeeded,
          clean(result.error),
          sha256(source.canonicalConstruction.getBytes(StandardCharsets.UTF_8)),
          evidence.flatMap(_.modelSha256).getOrElse(""),
          evidence.flatMap(_.artifactSha256).getOrElse(""),
          evidence.map(_.directory).getOrElse("")
        ).mkString("\t")
        expect(result.succeeded, name, "compile", s"${result.error}; ${evidence.map(_.directory)}")
        println(
          s"F52 compile $name: ${result.succeeded}; error=${result.error}; evidence=${evidence.map(_.directory)}"
        )
        result.model.foreach: model =>
          write(
            output,
            s"$name-generated.va",
            Files.readString(model.evidence.directory.resolve("model.va"))
          )
        result.model
      catch
        case NonFatal(error) =>
          expect(false, name, "capture", Option(error.getMessage).getOrElse(error.toString))
          println(s"F52 capture $name: failed: ${error.getMessage}")
          None

    def receipt(name: String, result: SimulationResult, expectedFailure: Boolean = false): Unit =
      val evidence = result.evidence
      receipts += Vector(
        name,
        if result.succeeded then "simulation" else "simulator-failure",
        evidence.outcome,
        result.sampleCount,
        evidence.requestSha256.getOrElse(""),
        evidence.modelSha256,
        evidence.artifactSha256.getOrElse(""),
        evidence.rawSha256.getOrElse(""),
        evidence.directory.getOrElse("")
      ).mkString("\t")
      if !expectedFailure then
        expect(result.succeeded, name, "simulation", s"${evidence.outcome}: ${result.error}")
      if result.succeeded then
        expect(
          evidence.rawPath.exists(path =>
            evidence.rawSha256.contains(sha256(Files.readAllBytes(path)))
          ),
          name,
          "artifact",
          "raw SHA-256 matches retained bytes"
        )
      println(
        s"F52 simulation $name: ${evidence.outcome}; samples=${result.sampleCount}; evidence=${evidence.directory}"
      )

    def compare[A, D](
        name: String,
        result: SimulationResult,
        probe: Probe[D],
        reference: Waveform[A, D],
        tolerance: Tolerance[D],
        alignment: WaveformAlignment = WaveformAlignment.Exact,
        components: Vector[ComplexComponent] = Vector(ComplexComponent.Real),
        required: Boolean = true
    ): Unit =
      if result.succeeded then
        val actual = get(result.waveform(reference.axis, probe))
        write(output, s"$name-actual.waveform", get(actual.serialize))
        write(output, s"$name-reference.waveform", get(reference.serialize))
        components.foreach: component =>
          val checked = get(actual.compare(reference, tolerance, component, alignment))
          comparisons += Vector(
            name,
            component,
            checked.sampleCount,
            checked.failureCount,
            checked.maximumAbsoluteError,
            checked.meanAbsoluteError,
            checked.rootMeanSquareError,
            tolerance.absolute.siValue,
            tolerance.relative,
            reference.unit.symbol
          ).mkString("\t")
          if required then expect(checked.passed, name, "numerical", s"$component: $checked")
          else
            outcomes += s"$name\tnumerical-observation\t${
                if checked.passed then "OBSERVED-PASS" else "OBSERVED-DISCREPANCY"
              }\t$component: $checked"

    def analytical[A, D](
        name: String,
        result: SimulationResult,
        probe: Probe[D],
        axis: WaveformAxis[A],
        unit: WaveformUnit[D],
        coordinate: Double => A,
        reference: Double => ComplexQuantity[D],
        tolerance: Tolerance[D],
        components: Vector[ComplexComponent] = Vector(ComplexComponent.Real),
        required: Boolean = true
    ): Unit =
      if result.succeeded then
        val expected = get(Waveform.reference(
          axis,
          unit,
          Vector.tabulate(result.sampleCount): index =>
            val x = get(result.axis(index))
            WaveformSample(coordinate(x), reference(x))
          ,
          Origin
        ))
        compare(
          name,
          result,
          probe,
          expected,
          tolerance,
          components = components,
          required = required
        )

    def run(name: String, bench: SimulationBench): SimulationResult =
      val result = SimulationSession.run(bench, simulatorTools)
      receipt(name, result)
      result

    def voltageTolerance(value: Increment49References.Tolerance): Tolerance[VoltageDimension] =
      Tolerance(value.absolute.volts, value.relative)
    def currentTolerance(value: Increment49References.Tolerance): Tolerance[CurrentDimension] =
      Tolerance(value.absolute.amps, value.relative)
    val voltageLimit = voltageTolerance(Increment52References.VoltageTolerance)
    val currentLimit = currentTolerance(Increment49References.CurrentTolerance)
    val complex =
      Vector(ComplexComponent.Real, ComplexComponent.Imaginary, ComplexComponent.Magnitude)

    try
      fixtures.filter(fixture => mode != "diode-only" || fixture.id == "diode").foreach(fixture =>
        compile(fixture.id, fixture.capture).foreach(model => compiled(fixture.id) = model)
      )
      if mode != "compile-only" then
        compiled.get("rc").foreach: model =>
          val pins = bindings(model.source, "positive" -> Input, "ground" -> Ground)
          val drive = ElectricalDrive.voltage(0, Input, Ground, 0.0.volts, Some(1.0.volts))
          val probe = Probe.current("source-current", drive)
          val dcBench = get(SimulationBench.create(
            model,
            pins,
            Vector(drive),
            SimulationAnalysis.dc(drive, (-1.0).volts, 1.0.volts, 0.5.volts),
            Vector(probe)
          ))
          val dc = run("rc-dc", dcBench)
          analytical(
            "rc-dc",
            dc,
            probe,
            WaveformAxis.VoltageSweep,
            WaveformUnit.Current,
            _.volts,
            x => ComplexQuantity.real(Increment49References.dcSourceCurrent(x).amps),
            currentLimit
          )
          val ac = run(
            "rc-ac",
            get(SimulationBench.create(
              model,
              pins,
              Vector(drive),
              SimulationAnalysis.acDecade(3, Frequency.hertz(10), Frequency.hertz(10000)),
              Vector(probe)
            ))
          )
          analytical(
            "rc-ac",
            ac,
            probe,
            WaveformAxis.Frequency,
            WaveformUnit.Current,
            Frequency.hertz,
            x =>
              val ref = Increment49References.acSourceCurrent(x)
              ComplexQuantity(ref.real.amps, ref.imaginary.amps)
            ,
            currentLimit,
            complex
          )
          val ramp = ElectricalDrive.current(
            1,
            Ground,
            Input,
            0.0.amps,
            waveform = Vector(
              0.0.seconds -> 0.0.amps,
              0.001.seconds -> 0.0.amps,
              0.0011.seconds -> 0.001.amps,
              0.01.seconds -> 0.001.amps
            )
          )
          val voltage = Probe.voltage("rc-voltage", Input)
          val reference = get(Waveform.reference(
            WaveformAxis.Time,
            WaveformUnit.Voltage,
            Vector.tabulate(10001)(i =>
              WaveformSample(
                (i / 1e6).seconds,
                ComplexQuantity.real(Increment49References.rcVoltage(i / 1e6).volts)
              )
            ),
            Origin
          ))
          write(output, "rc-transient-predeclared.waveform", get(reference.serialize))
          val tran = run(
            "rc-transient",
            get(SimulationBench.create(
              model,
              pins,
              Vector(ramp),
              SimulationAnalysis.transient(1e-5.seconds, 0.01.seconds, true),
              Vector(voltage),
              Vector(SimulationInitialCondition(Input, 0.0.volts))
            ))
          )
          compare(
            "rc-transient",
            tran,
            voltage,
            reference,
            voltageTolerance(Increment49References.TransientTolerance),
            WaveformAlignment.Linear
          )
          val repeated = run("rc-dc-repeated", dcBench)
          expect(
            dc.succeeded && repeated.succeeded && dc.sampleCount == repeated.sampleCount &&
              (0 until dc.sampleCount).forall(i =>
                dc.axis(i) == repeated.axis(i) &&
                  get(dc.measure(probe, SampleReduction.At(i))).value.siValue ==
                  get(repeated.measure(probe, SampleReduction.At(i))).value.siValue
              ),
            "rc-dc-repeated",
            "determinism",
            "exact physical coordinates and values"
          )
          expect(
            dc.evidence.requestSha256 == repeated.evidence.requestSha256 &&
              dc.evidence.modelSha256 == repeated.evidence.modelSha256 &&
              dc.evidence.artifactSha256 == repeated.evidence.artifactSha256 &&
              dc.evidence.directory != repeated.evidence.directory,
            "rc-dc-repeated",
            "determinism",
            "same identities; fresh evidence directory"
          )
          val missing = SimulationSession.run(
            dcBench,
            simulatorTools.copy(executable = output.resolve("missing-ngspice"))
          )
          receipt("missing-simulator", missing, true)
          expect(
            !missing.succeeded && missing.sampleCount == 0 &&
              missing.evidence.outcome == "ToolUnavailable",
            "missing-simulator",
            "expected-tool-failure",
            "unavailable executable is not a language or solver error"
          )
          val rejected = SimulationBench.create(
            model,
            pins.take(1),
            Vector(drive),
            dcBench.analysis,
            Vector(probe)
          )
          expect(
            rejected.left.toOption.exists(_.code == "SIM-BENCH"),
            "missing-binding",
            "expected-bench-rejection",
            clean(rejected)
          )
          if dc.succeeded then
            val wrong = get(Waveform.reference(
              WaveformAxis.VoltageSweep,
              WaveformUnit.Current,
              Vector.tabulate(dc.sampleCount)(i =>
                WaveformSample(
                  get(dc.axis(i)).volts,
                  ComplexQuantity.real((Increment49References.dcSourceCurrent(get(dc.axis(i))) +
                    0.1).amps)
                )
              ),
              Origin
            ))
            val mismatch =
              get(get(dc.waveform(WaveformAxis.VoltageSweep, probe)).compare(wrong, currentLimit))
            write(output, "intentional-mismatch-reference.waveform", get(wrong.serialize))
            expect(
              !mismatch.passed && mismatch.failureCount == dc.sampleCount && dc.succeeded,
              "intentional-mismatch",
              "expected-numerical-discrepancy",
              s"successful simulation remains successful: $mismatch"
            )

        compiled.get("rlc").foreach: model =>
          val drive = ElectricalDrive.voltage(0, Input, Ground, 0.0.volts, Some(1.0.volts))
          val probe = Probe.voltage("rlc-output", Output)
          val result = run(
            "rlc-ac",
            get(SimulationBench.create(
              model,
              bindings(model.source, "input" -> Input, "output" -> Output, "ground" -> Ground),
              Vector(drive),
              SimulationAnalysis.acDecade(4, Frequency.hertz(10), Frequency.hertz(10000)),
              Vector(probe)
            ))
          )
          analytical(
            "rlc-ac",
            result,
            probe,
            WaveformAxis.Frequency,
            WaveformUnit.Voltage,
            Frequency.hertz,
            x =>
              val ref = Increment52References.rlcTransfer(x)
              ComplexQuantity(ref.real.volts, ref.imaginary.volts)
            ,
            voltageTolerance(Increment52References.RlcTolerance),
            complex
          )

        compiled.get("diode").foreach: model =>
          val pins = bindings(model.source, "positive" -> Input, "ground" -> Ground)
          val drive = ElectricalDrive.voltage(
            0,
            Input,
            Ground,
            Increment52References.DiodeBiasVolts.volts,
            Some(1.0.volts)
          )
          val probe = Probe.current("diode-source-current", drive)
          val limit = currentTolerance(Increment52References.DiodeTolerance)
          val defaultPrecision = Option.when(mode == "diode-only") {
            val result = run(
              "diode-dc-default-precision",
              get(SimulationBench.create(
                model,
                pins,
                Vector(drive),
                SimulationAnalysis.dc(drive, (-0.1).volts, 0.5.volts, 0.05.volts),
                Vector(probe)
              ))
            )
            analytical(
              "diode-dc-default-precision",
              result,
              probe,
              WaveformAxis.VoltageSweep,
              WaveformUnit.Current,
              _.volts,
              x => ComplexQuantity.real(Increment52References.diodeSourceCurrent(x).amps),
              limit,
              required = false
            )
            result
          }
          val dc = run(
            "diode-dc",
            get(SimulationBench.create(
              model,
              pins,
              Vector(drive),
              SimulationAnalysis.dc(drive, (-0.1).volts, 0.5.volts, 0.05.volts),
              Vector(probe),
              solverTolerances = SolverTolerances(currentAbsolute = 1e-18.amps)
            ))
          )
          defaultPrecision.foreach: previous =>
            expect(
              previous.evidence.modelSha256 == dc.evidence.modelSha256 &&
                previous.evidence.artifactSha256 == dc.evidence.artifactSha256 &&
                previous.evidence.requestSha256 != dc.evidence.requestSha256 &&
                previous.evidence.directory != dc.evidence.directory,
              "diode-solver-precision",
              "causal-request",
              "same compiled artifact and physical stimulus; distinct solver request and fresh evidence"
            )
          analytical(
            "diode-dc",
            dc,
            probe,
            WaveformAxis.VoltageSweep,
            WaveformUnit.Current,
            _.volts,
            x => ComplexQuantity.real(Increment52References.diodeSourceCurrent(x).amps),
            limit
          )
          val ac = run(
            "diode-ac",
            get(SimulationBench.create(
              model,
              pins,
              Vector(drive),
              SimulationAnalysis.acLinear(3, Frequency.hertz(10), Frequency.hertz(10000)),
              Vector(probe)
            ))
          )
          analytical(
            "diode-ac",
            ac,
            probe,
            WaveformAxis.Frequency,
            WaveformUnit.Current,
            Frequency.hertz,
            _ =>
              ComplexQuantity.real(
                (-Increment52References.diodeConductance(Increment52References.DiodeBiasVolts)).amps
              ),
            limit,
            complex
          )

        Vector("amplifier-affine", "amplifier-clipped", "comparator").foreach: name =>
          compiled.get(name).foreach: model =>
            val drive = ElectricalDrive.voltage(0, Input, Ground, 0.0.volts)
            val probe = Probe.voltage("output", Output)
            val pins =
              bindings(model.source, "input" -> Input, "output" -> Output, "ground" -> Ground)
            val reference: Double => Double = if name == "comparator" then
              Increment52References.comparatorVoltage
            else x => Increment52References.amplifierVoltage(x, name == "amplifier-clipped")
            val result = run(
              s"$name-dc",
              get(SimulationBench.create(
                model,
                pins,
                Vector(drive),
                SimulationAnalysis.dc(drive, (-1.0).volts, 1.0.volts, 0.1.volts),
                Vector(probe)
              ))
            )
            analytical(
              s"$name-dc",
              result,
              probe,
              WaveformAxis.VoltageSweep,
              WaveformUnit.Voltage,
              _.volts,
              x => ComplexQuantity.real(reference(x).volts),
              voltageLimit
            )
            // Exact equality and both native-function clip knees are separate decimal stimuli.
            val boundaries = if name == "comparator" then Vector(0.2)
            else if name == "amplifier-clipped" then Vector((-1.0 - 0.1) / 3.0, (1.0 - 0.1) / 3.0)
            else Vector.empty
            boundaries.zipWithIndex.foreach: (point, index) =>
              val boundary = run(
                s"$name-boundary-$index",
                get(SimulationBench.create(
                  model,
                  pins,
                  Vector(drive),
                  SimulationAnalysis.dc(drive, point.volts, point.volts, 0.1.volts),
                  Vector(probe)
                ))
              )
              analytical(
                s"$name-boundary-$index",
                boundary,
                probe,
                WaveformAxis.VoltageSweep,
                WaveformUnit.Voltage,
                _.volts,
                x => ComplexQuantity.real(reference(x).volts),
                voltageLimit
              )

        Vector("oscillator" -> 0.0, "vco" -> Increment52References.SensitivityHertzPerVolt).foreach:
          (name, sensitivity) =>
            compiled.get(name).foreach: model =>
              val drive = ElectricalDrive.voltage(
                0,
                Input,
                Ground,
                0.0.volts,
                waveform = Some(VoltageWaveform(
                  Increment52References.ControlKnots.map((time, voltage) =>
                    time.seconds -> voltage.volts
                  )
                ))
              )
              val probe = Probe.voltage("oscillator-output", Output)
              val expected = get(Waveform.reference(
                WaveformAxis.Time,
                WaveformUnit.Voltage,
                Vector.tabulate(30001)(i =>
                  WaveformSample(
                    (i / 1e6).seconds,
                    ComplexQuantity.real(Increment52References.vcoVoltage(
                      i / 1e6,
                      sensitivity
                    ).volts)
                  )
                ),
                Origin
              ))
              write(output, s"$name-predeclared.waveform", get(expected.serialize))
              val result = run(
                s"$name-transient",
                get(SimulationBench.create(
                  model,
                  bindings(
                    model.source,
                    "control" -> Input,
                    "output" -> Output,
                    "ground" -> Ground
                  ),
                  Vector(drive),
                  SimulationAnalysis.transient(
                    Increment52References.StepSeconds.seconds,
                    Increment52References.StopSeconds.seconds,
                    true
                  ),
                  Vector(probe)
                ))
              )
              compare(
                s"$name-transient",
                result,
                probe,
                expected,
                voltageTolerance(Increment52References.OscillatorTolerance),
                WaveformAlignment.Linear
              )

        compiled.get("initial-step").foreach: model =>
          val pins = bindings(model.source, "positive" -> Input, "ground" -> Ground)
          val load = ElectricalDrive.current(0, Input, Ground, 0.0.amps)
          val probe = Probe.voltage("stored-output", Input)
          val analysis = SimulationAnalysis.transient(0.001.seconds, 0.01.seconds, true)
          val result = run(
            "initial-step-transient",
            get(SimulationBench.create(
              model,
              pins,
              Vector(load),
              analysis,
              Vector(probe),
              Vector(SimulationInitialCondition(Input, 0.0.volts))
            ))
          )
          analytical(
            "initial-step-transient",
            result,
            probe,
            WaveformAxis.Time,
            WaveformUnit.Voltage,
            _.seconds,
            _ => ComplexQuantity.real(1.0.volts),
            voltageLimit
          )
          val clamp = ElectricalDrive.voltage(0, Input, Ground, 0.0.volts)
          val contradictory = SimulationSession.run(
            get(SimulationBench.create(
              model,
              pins,
              Vector(clamp),
              analysis,
              Vector(probe)
            )),
            simulatorTools
          )
          receipt("inconsistent-ideal-sources", contradictory, true)
          expect(
            !contradictory.succeeded && contradictory.sampleCount == 0 &&
              contradictory.evidence.outcome == "Nonconvergence",
            "inconsistent-ideal-sources",
            "expected-solver-failure",
            s"${contradictory.evidence.outcome}: ${contradictory.error}"
          )

        compiled.get("controlled").foreach: model =>
          val nodes = Vector.tabulate(5)(SimulationNode.Node(_))
          val pins = bindings(
            model.source,
            "clock" -> nodes(0),
            "related" -> nodes(1),
            "async" -> nodes(2),
            "reset" -> nodes(3),
            "output" -> nodes(4),
            "ground" -> Ground
          )
          val drives = Vector.tabulate(4)(i =>
            ElectricalDrive.voltage(i, nodes(i), Ground, (if i == 3 then 1.0 else 0.0).volts)
          )
          val probe = Probe.voltage("weighted-output", nodes(4))
          val bench = get(SimulationBench.create(
            model,
            pins,
            drives,
            SimulationAnalysis.dc(drives(2), (-0.5).volts, 0.5.volts, 0.25.volts),
            Vector(probe)
          ))
          val axes = Vector(
            SweepAxis("clock", drives(0), Vector(-0.5, 0.0, 0.5, 1.0).map(_.volts)),
            SweepAxis("related", drives(1), Vector(-0.25, 0.25, 0.75).map(_.volts))
          )
          val sweep = get(SimulationSweep.run(bench, axes, simulatorTools))
          write(
            output,
            "sweep-coordinates.tsv",
            "identity\tcoordinates\n" + sweep.map(point =>
              s"${point.identity}\t${point.coordinates.mkString(";")}"
            ).mkString("\n") + "\n"
          )
          expect(
            sweep.size == 12 && sweep.map(_.identity).distinct.size == 12,
            "controlled-sweep",
            "scale",
            "12 uniquely identified Cartesian points, five DC samples each"
          )
          sweep.zipWithIndex.foreach: (point, ordinal) =>
            val name = s"controlled-sweep-$ordinal"
            receipt(name, point.result)
            analytical(
              name,
              point.result,
              probe,
              WaveformAxis.VoltageSweep,
              WaveformUnit.Voltage,
              _.volts,
              x =>
                ComplexQuantity.real((point.coordinates(0).value + 2 * point.coordinates(1).value +
                  4 * x + 8).volts),
              voltageLimit
            )
          val repeated = get(SimulationSweep.run(bench, axes, simulatorTools))
          repeated.zipWithIndex.foreach((point, ordinal) =>
            receipt(s"controlled-repeat-$ordinal", point.result)
          )
          expect(
            sweep.map(_.identity) == repeated.map(_.identity) && sweep.zip(repeated).forall:
              (left, right) =>
                left.coordinates == right.coordinates && left.result.succeeded &&
                  right.result.succeeded &&
                  left.result.sampleCount == right.result.sampleCount &&
                  left.result.evidence.requestSha256 == right.result.evidence.requestSha256 &&
                  left.result.evidence.directory != right.result.evidence.directory &&
                  (0 until left.result.sampleCount).forall(i =>
                    left.result.axis(i) == right.result.axis(i) &&
                      get(left.result.measure(probe, SampleReduction.At(i))).value.siValue ==
                      get(right.result.measure(probe, SampleReduction.At(i))).value.siValue
                  )
            ,
            "controlled-repeat",
            "determinism",
            "repeat preserves coordinates, identities and exact values with fresh receipts"
          )
          val noExecution =
            simulatorTools.copy(evidenceRoot = output.resolve("rejected-sweep-must-not-exist"))
          val rejected = SimulationSweep.run(
            bench,
            Vector(
              SweepAxis("clock", drives(0), Vector.fill(12)(0.0.volts)),
              SweepAxis("related", drives(1), Vector.fill(12)(0.0.volts))
            ),
            noExecution
          )
          expect(
            rejected.left.toOption.exists(_.code == "SIM-SWEEP") &&
              !Files.exists(noExecution.evidenceRoot),
            "oversized-sweep",
            "expected-resource-rejection",
            "144 points reject before simulator execution"
          )

        if mode == "numerical" then
          seededParameters.zipWithIndex.foreach: (parameters, ordinal) =>
            val (resistance, capacitance) = parameters
            val name = s"seeded-rc-$ordinal"
            val first = compile(name, () => Increment50Sources.rc(resistance, capacitance))
            val recaptured =
              compile(s"$name-recaptured", () => Increment50Sources.rc(resistance, capacitance))
            for model <- first; repeated <- recaptured do
              expect(
                model.source.canonicalConstruction == repeated.source.canonicalConstruction &&
                  model.evidence.sourceMlirSha256 == repeated.evidence.sourceMlirSha256 &&
                  model.evidence.modelSha256 == repeated.evidence.modelSha256,
                name,
                "deterministic-recapture",
                "same parameters reproduce canonical capture, IR and actual generated model"
              )
              val drive = ElectricalDrive.voltage(0, Input, Ground, 0.0.volts, Some(1.0.volts))
              val probe = Probe.current("seeded-current", drive)
              val pins = bindings(model.source, "positive" -> Input, "ground" -> Ground)
              val dc = run(
                s"$name-dc",
                get(SimulationBench.create(
                  model,
                  pins,
                  Vector(drive),
                  SimulationAnalysis.dc(drive, (-1.0).volts, 1.0.volts, 0.5.volts),
                  Vector(probe)
                ))
              )
              analytical(
                s"$name-dc",
                dc,
                probe,
                WaveformAxis.VoltageSweep,
                WaveformUnit.Current,
                _.volts,
                x => ComplexQuantity.real((-x / resistance).amps),
                currentLimit
              )
              val ac = run(
                s"$name-ac",
                get(SimulationBench.create(
                  model,
                  pins,
                  Vector(drive),
                  SimulationAnalysis.acLinear(3, Frequency.hertz(10), Frequency.hertz(10000)),
                  Vector(probe)
                ))
              )
              analytical(
                s"$name-ac",
                ac,
                probe,
                WaveformAxis.Frequency,
                WaveformUnit.Current,
                Frequency.hertz,
                x =>
                  ComplexQuantity((-1.0 / resistance).amps, (-2 * math.Pi * x * capacitance).amps),
                currentLimit,
                complex
              )

          val invalid = SimulationSource.capture(new Increment52RejectedSource("digital"))(model =>
            Vector(
              PortSelection("positive", model.positive),
              PortSelection("ground", model.negative)
            )
          )
          expect(
            invalid.left.toOption.exists(_.code == "SIM-CAPTURE"),
            "digital-effect",
            "expected-language-rejection",
            clean(invalid)
          )
    catch
      case NonFatal(error) =>
        expect(
          false,
          "replay",
          "unexpected-exception",
          Option(error.getMessage).getOrElse(error.toString)
        )
        error.printStackTrace()
    finally
      write(output, "compilation-receipts.tsv", compileRows.mkString("\n") + "\n")
      write(output, "simulation-receipts.tsv", receipts.mkString("\n") + "\n")
      write(output, "comparisons.tsv", comparisons.mkString("\n") + "\n")
      write(output, "outcomes.tsv", outcomes.mkString("\n") + "\n")
      write(
        output,
        "summary.txt",
        s"scalarReplayPassed=${failures.isEmpty}\nmode=$mode\ncompiled=${compiled.size}\nsimulations=${receipts.size -
            1}\ncomparisons=${comparisons.size - 1}\nfailures=${failures.size}\nhierarchy=NOT-EXECUTED-HERE; independently recorded by Increment52HierarchyReplay\n" +
          failures.mkString("\n") + "\n"
      )
      println(s"F52 scalar replay: mode=$mode; failures=${failures.size}; evidence=$output")
    require(failures.isEmpty, failures.mkString("F52 scalar replay failed:\n", "\n", ""))
