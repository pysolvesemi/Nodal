package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.time.Duration

import scala.collection.mutable

import nodal.*
import nodal.increment45fixture.AnalogCanonicalizationSource
import nodal.internal.integrations.*
import nodal.sim.*

/** Independently authored, unsummed contribution reference. Both positive contributions and both
  * observed contributions must survive compilation; no generated target text is substituted.
  */
private final class Increment52CanonicalizationReference extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val arithmetic = inout(Electrical)
  val observed = inout(Electrical)
  val gain = param(2.0.real)
  val offset = param(3.0.real)

  analog:
    V(positive, negative) <+ gain * 1.0.V + offset * 1.0.V
    V(positive, negative) <+ offset * 1.0.V + gain * 1.0.V
    V(arithmetic, negative) <+ gain * 1.0.V + 6.0.V
    V(observed, negative) <+ (AnalysisContext.time / 1.0.s) * 1.0.V +
      gain * 1.0.V + offset * 1.0.V
    V(observed, negative) <+ (AnalysisContext.time / 1.0.s) * 1.0.V +
      offset * 1.0.V + gain * 1.0.V

/** Bounded F45 numerical handoff through actual public capture/compilation and the existing finite
  * multi-output simulator adapter. Each source is compiled once, then reused for runtime
  * parameters.
  */
object Increment52CanonicalizationReplay:
  private val AbsoluteTolerance = 1e-8
  private val RelativeTolerance = 1e-6
  private val Outputs = Vector("positive", "arithmetic", "observed")
  private val Nodes = Vector.tabulate(3)(NgspiceNode.Node.apply)
  private val Parameters = Vector(
    ("defaults", 2.0, 3.0, false),
    ("gain", 4.0, 3.0, true),
    ("offset-cancellation", 2.0, -2.0, true),
    ("gain-repeat", 4.0, 3.0, true)
  )

  private def get[A](result: Either[SimulationError, A]): A =
    require(result.isRight, result.left.toOption.toString)
    result.toOption.get

  private def write(path: Path, content: String): Unit =
    val _ = Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)

  private def digest(path: Path): String =
    val hash = MessageDigest.getInstance("SHA-256")
    val stream = Files.newInputStream(path)
    try
      val buffer = new Array[Byte](65536)
      var count = stream.read(buffer)
      while count >= 0 do
        hash.update(buffer, 0, count)
        count = stream.read(buffer)
    finally stream.close()
    hash.digest().map(value => f"${value & 0xff}%02x").mkString

  private def captureOriginal(): SimulationSource =
    get(SimulationSource.capture(new AnalogCanonicalizationSource)(model =>
      Vector(
        PortSelection("positive", model.positive),
        PortSelection("negative", model.negative),
        PortSelection("arithmetic", model.arithmetic),
        PortSelection("observed", model.observed)
      )
    ))

  private def captureReference(): SimulationSource =
    get(SimulationSource.capture(new Increment52CanonicalizationReference)(model =>
      Vector(
        PortSelection("positive", model.positive),
        PortSelection("negative", model.negative),
        PortSelection("arithmetic", model.arithmetic),
        PortSelection("observed", model.observed)
      )
    ))

  private def expected(gain: Double, offset: Double, time: Double): Vector[Double] =
    Vector(2 * (gain + offset), gain + 6, 2 * time + 2 * (gain + offset))

  private def operationCount(text: String, operation: String): Int =
    text.linesIterator.count(_.contains(s"\"nodal.$operation\"("))

  private def checkStructure(model: CompiledSimulationModel, original: Boolean): Unit =
    val directory = model.evidence.directory
    val source = Files.readString(directory.resolve("source.mlir"))
    val normalized = Files.readString(directory.resolve("normalized.mlir"))
    val target = Files.readString(directory.resolve("model.va"))
    val contributions = if original then 4 else 5
    require(operationCount(source, "contribute") == contributions, "authored contributions changed")
    require(
      operationCount(normalized, "contribute") == contributions,
      "normalization lost or merged contributions"
    )
    require(
      target.linesIterator.count(_.trim.startsWith("V(positive, negative) <+")) == 2,
      "generated positive branch must retain two separately authored contributions"
    )
    require(
      target.contains("parameter real gain") && target.contains("parameter real offset"),
      "runtime parameters disappeared"
    )
    if original then
      require(operationCount(source, "analog_neg") == 2, "missing authored double negation")
      require(operationCount(normalized, "analog_neg") == 0, "double negation was not normalized")
      Vector("analog_abstime" -> 2, "analog_user_call" -> 3).foreach: (operation, count) =>
        require(operationCount(source, operation) == count, s"authored $operation count changed")
        require(
          operationCount(normalized, operation) == count,
          s"normalized $operation count changed"
        )
      require(!target.contains("unusedLiteral"), "proved unused literal local was not removed")
      require(
        target.contains("real unusedDynamic;") && target.contains("real unusedCall;"),
        "dynamic or call-fed function local was removed"
      )
      require("preserveSignal\\(gain\\)".r.findAllIn(target).size == 2, "function calls changed")
      require("identitySignal\\(signal\\)".r.findAllIn(target).size == 1, "nested call changed")
    else
      require(
        target.linesIterator.count(_.trim.startsWith("V(observed, negative) <+")) == 2,
        "unsummed reference observation contributions changed"
      )

  def main(arguments: Array[String]): Unit =
    require(
      arguments.length == 8,
      "expected output-root nodalc translator openvaf compiler-profile ngspice simulator-profile base-HEAD"
    )
    require(arguments(7).matches("[a-fA-F0-9]{40}"), "base HEAD must be a full Git revision")
    val root = Path.of(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val directory = Files.createTempDirectory(root, "canonicalization-")
    val tools = CompilationTools(
      Path.of(arguments(1)).toAbsolutePath.normalize(),
      Path.of(arguments(2)).toAbsolutePath.normalize(),
      Path.of(arguments(3)).toAbsolutePath.normalize(),
      arguments(4),
      directory.resolve("compilations"),
      Duration.ofSeconds(60)
    )
    val simulator = Path.of(arguments(5)).toAbsolutePath.normalize()
    val toolPaths = Vector(tools.nodalc, tools.translator, tools.openVaf, simulator)
    val toolHashes = toolPaths.map(path => path -> digest(path))
    write(
      directory.resolve("tools.tsv"),
      "path\tsha256\n" +
        toolHashes.map((path, hash) => s"$path\t$hash").mkString("\n") + "\n"
    )
    val sourcePaths = Vector(
      "examples/continuousTimeApi/src/nodal/increment45fixture/Increment45ConstructionCheck.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment52CanonicalizationReplay.scala",
      "core/scala/sim/src/nodal/sim/SimulationCompilation.scala",
      "core/integrations/src/nodal/internal/integrations/NgspiceCircuitBench.scala",
      "core/integrations/src/nodal/internal/integrations/NgspiceSimulator.scala"
    )
    val retained = sourcePaths.map: relative =>
      val destination = directory.resolve("source-files").resolve(relative + ".bytes")
      Files.createDirectories(destination.getParent)
      Files.copy(Path.of(relative), destination)
      s"$relative\t${digest(destination)}\t${Files.size(destination)}"
    write(
      directory.resolve("source-files.tsv"),
      "path\tsha256\tbytes\n" + retained.mkString("\n") + "\n"
    )
    write(
      directory.resolve("references.txt"),
      "F45 handoff: Vpositive=2*(gain+offset); Varithmetic=gain+6; Vobserved=2*timeSeconds+2*(gain+offset), in volts.\n" +
        "Unloaded outputs: three zero-current sources; negative terminal at reference ground. DC is the zero-current operating point.\n" +
        "Transient 0..2 s, maximum step 0.01 s; every returned point is compared with the analytical reference.\n" +
        "Every voltage: absolute error <= 1e-8 V + 1e-6*abs(reference). Defaults (2,3), gain (4,3), offset cancellation (2,-2), repeat (4,3).\n" +
        "Each of two source models is compiled once; parameter cases are external .model runtime bindings, not recapture.\n" +
        "Independent unsummed Scala reference has two positive contributions, gain*1 V+offset*1 V and offset*1 V+gain*1 V; observed similarly uses two contributions each containing time.\n" +
        "Source-to-reference DC comparison is exact-grid; transient uses shared Waveform linear alignment with no extrapolation. Both source models also meet the independent analytical references.\n" +
        "Function locals/call counts are structural witnesses; no numerical observability is claimed for unused locals. F32 equation inventories remain outside executable simulation.\n"
    )
    write(
      directory.resolve("run.txt"),
      s"baseHEAD=${arguments(7)}\nsourceState=retained working-tree bytes; base HEAD does not certify a clean or published tree\ncompilerProfile=${arguments(4)}\nsimulatorProfile=${arguments(6)}\n"
    )
    val receipts = mutable.ArrayBuffer(
      "case\tmodel\toutcome\tpoints\tcompileRequestSha256\tmodelSha256\tartifactSha256\trawSha256\tevidence"
    )
    val comparisons = mutable.ArrayBuffer("case\tcomparison\toutput\tpoints\tmaximumAbsoluteError")
    var passed = false
    try
      def compile(name: String, capture: () => SimulationSource): CompiledSimulationModel =
        val source = capture()
        require(capture().canonicalConstruction == source.canonicalConstruction, "capture changed")
        val result = SimulationCompiler.compile(source, tools)
        write(directory.resolve(s"$name-compile-result.txt"), result.toString)
        println(s"F52 canonicalization $name compile: ${result.succeeded}; ${result.error}")
        require(result.succeeded, s"$name compilation failed: ${result.error}")
        val model = result.model.get
        require(
          model.evidence.modelSha256.contains(digest(model.evidence.directory.resolve("model.va"))),
          "generated model hash differs from compiled model"
        )
        require(
          model.compiled.artifact.exists(artifact => digest(artifact.path) == artifact.sha256),
          "compiled artifact digest mismatch"
        )
        checkStructure(model, name == "original")
        model

      val original = compile("original", captureOriginal)
      val reference = compile("unsummed-reference", captureReference)
      val bindings = Outputs.zip(Nodes).toMap + ("negative" -> NgspiceNode.Ground)
      val sources = Nodes.zipWithIndex.map: (node, index) =>
        NgspiceSource(NgspiceSourceId(index), NgspiceSourceKind.Current, node, NgspiceNode.Ground)
      val analyses = Vector(
        "dc" -> NgspiceCircuitAnalysis.DcSweep(NgspiceSourceId(0), 0, 0, 1),
        "transient" -> NgspiceCircuitAnalysis.Transient(0.01, 2, false)
      )
      val executed = mutable.Map.empty[(String, String, String), NgspiceSimulationResult]
      Parameters.foreach: (name, gain, offset, overrideDefaults) =>
        analyses.foreach: (analysisName, analysis) =>
          val caseName = s"$name-$analysisName"
          Vector("original" -> original, "reference" -> reference).foreach: (modelName, model) =>
            val output = directory.resolve(s"$caseName-$modelName")
            val bench = NgspiceCircuitBench(
              NgspiceCircuitBinding(
                model.moduleName,
                model.portsInTargetOrder.map(port => bindings(port.label)),
                if overrideDefaults then Vector("gain" -> gain, "offset" -> offset)
                else Vector.empty
              ),
              Nodes,
              sources,
              analysis,
              Nodes.map(NgspiceProbe.Voltage.apply)
            )
            val result = NgspiceSimulator.simulate(NgspiceCircuitSimulateRequest(
              simulator,
              output,
              arguments(6),
              model.compiled,
              bench,
              runTimeout = Duration.ofSeconds(60)
            ))
            // Retain every classified failure before requiring numerical success.
            Files.createDirectories(output)
            write(output.resolve("replay-receipt.json"), NgspiceSimulator.receiptJson(result))
            receipts += Vector(
              caseName,
              modelName,
              result.outcome,
              result.validatedPoints,
              result.identity.compileRequestSha256,
              result.identity.modelSha256,
              result.identity.artifactSha256,
              result.raw.map(_.sha256),
              result.evidenceDirectory
            ).mkString("\t")
            require(
              result.outcome == NgspiceOutcome.Simulated,
              s"$caseName/$modelName: ${result.outcome}: ${result.message}"
            )
            require(result.raw.exists(raw => digest(raw.path) == raw.sha256), "raw digest mismatch")
            require(
              result.identity.modelSha256 == model.compiled.identity.modelSha256 &&
                result.identity.artifactSha256 == model.compiled.artifact.map(_.sha256) &&
                result.identity.compileRequestSha256 == model.compiled.identity.requestSha256,
              "runtime parameters changed compile/model/artifact identity"
            )
            val plot = result.plot.get
            require(
              plot.variables.size == 4 && plot.rows.nonEmpty,
              "unexpected three-output schema"
            )
            if analysisName == "dc" then
              require(plot.rows.size == 1, "DC must be one operating point")
            else
              require(
                plot.rows.head.head.real == 0 && math.abs(plot.rows.last.head.real - 2) < 1e-12,
                "transient does not cover 0..2 s"
              )
              require(
                plot.rows.sliding(2).forall(pair =>
                  pair(1).head.real - pair(0).head.real <= 0.01 + 1e-12
                ),
                "maximum timestep exceeded"
              )
            Outputs.zipWithIndex.foreach: (label, index) =>
              var maximum = 0.0
              plot.rows.foreach: row =>
                val time = if analysisName == "dc" then 0.0 else row.head.real
                val wanted = expected(gain, offset, time)(index)
                val actual = row(index + 1)
                val error = math.abs(actual.real - wanted)
                maximum = math.max(maximum, error)
                require(
                  actual.imaginary == 0 &&
                    error <= AbsoluteTolerance + RelativeTolerance * math.abs(wanted),
                  s"$caseName/$modelName/$label: $actual expected $wanted V"
                )
              comparisons +=
                s"$caseName\t$modelName-vs-analytical\t$label\t${plot.rows.size}\t$maximum"
            executed((name, analysisName, modelName)) = result

          val actual = executed((name, analysisName, "original"))
          val unsummed = executed((name, analysisName, "reference"))
          def compare[A](
              axis: WaveformAxis[A],
              coordinate: Double => A,
              alignment: WaveformAlignment
          ): Unit =
            Outputs.zipWithIndex.foreach: (label, index) =>
              def waveform(result: NgspiceSimulationResult) = get(Waveform.reference(
                axis,
                WaveformUnit.Voltage,
                result.plot.get.rows.map(row =>
                  WaveformSample(
                    coordinate(row.head.real),
                    ComplexQuantity.real(row(index + 1).real.volts)
                  )
                ),
                WaveformProvenance(
                  "executed source via finite multi-output adapter",
                  Some(result.identity.modelSha256),
                  result.identity.requestSha256,
                  result.raw.map(_.sha256)
                )
              ))
              val checked = get(waveform(actual).compare(
                waveform(unsummed),
                Tolerance(AbsoluteTolerance.volts, RelativeTolerance),
                alignment = alignment
              ))
              comparisons +=
                s"$caseName\toriginal-vs-unsummed\t$label\t${checked.sampleCount}\t${checked.maximumAbsoluteError}"
              require(checked.passed, s"$caseName/$label unsummed-source mismatch: $checked")
          if analysisName == "dc" then
            compare(WaveformAxis.CurrentSweep, _.amps, WaveformAlignment.Exact)
          else compare(WaveformAxis.Time, _.seconds, WaveformAlignment.Linear)
          println(
            s"F52 canonicalization $caseName: PASS; three outputs, analytical and unsummed source"
          )

      for analysis <- Vector("dc", "transient"); model <- Vector("original", "reference") do
        val first = executed(("gain", analysis, model))
        val repeat = executed(("gain-repeat", analysis, model))
        require(
          first.plot.get.rows == repeat.plot.get.rows &&
            first.identity.requestSha256 == repeat.identity.requestSha256,
          "repeated runtime parameters changed rows or request identity"
        )
      require(toolHashes.forall((path, hash) => digest(path) == hash), "tool changed during replay")
      passed = true
    finally
      write(directory.resolve("simulation-receipts.tsv"), receipts.mkString("\n") + "\n")
      write(directory.resolve("comparisons.tsv"), comparisons.mkString("\n") + "\n")
      write(
        directory.resolve("summary.txt"),
        s"passed=$passed\nsimulations=${receipts.size - 1}\ncomparisons=${comparisons.size - 1}\n" +
          "profile=F45 three-output DC and 0..2 s transient; runtime parameters; unsummed source comparison\n"
      )
      println(s"F52 canonicalization replay: passed=$passed; evidence=$directory")
