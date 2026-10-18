package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Duration

import scala.collection.mutable

import nodal.*
import nodal.internal.bridge.*
import nodal.internal.integrations.*

/** Independent circuit references. These do not read the generated target or solver output. */
private[testkit] object Increment49References:
  final case class Tolerance(absolute: Double, relative: Double):
    require(absolute.isFinite && absolute >= 0 && relative.isFinite && relative >= 0)
    def accepts(actual: Double, expected: Double): Boolean =
      actual.isFinite && expected.isFinite &&
        math.abs(actual - expected) <= absolute + relative * math.abs(expected)

  val ResistanceOhms = 1000.0
  val CapacitanceFarads = 1e-6
  val FinalAmps = 1e-3
  val DelaySeconds = 1e-3
  val RiseSeconds = 1e-4
  val CurrentTolerance = Tolerance(1e-10, 1e-6)
  val VoltageTolerance = Tolerance(1e-9, 1e-6)
  val TransientTolerance = Tolerance(2e-5, 2e-3)

  def dcSourceCurrent(volts: Double): Double = -volts / ResistanceOhms
  def acSourceCurrent(hz: Double): NgspiceComplex =
    NgspiceComplex(-1 / ResistanceOhms, -2 * math.Pi * hz * CapacitanceFarads)

  // Solve C*dV/dt + V/R = I(t) with V(0)=0, a finite linear rise, then constant I.
  def rcVoltage(seconds: Double): Double =
    val tau = ResistanceOhms * CapacitanceFarads
    val elapsed = seconds - DelaySeconds
    val target = ResistanceOhms * FinalAmps
    if elapsed <= 0 then 0
    else if elapsed < RiseSeconds then
      target / RiseSeconds * (elapsed + tau * math.expm1(-elapsed / tau))
    else
      val rampEnd = target * (1 + tau / RiseSeconds * math.expm1(-RiseSeconds / tau))
      target + (rampEnd - target) * math.exp(-(elapsed - RiseSeconds) / tau)

  def contextVoltage(seconds: Double, tempC: Double, nominalC: Double): Double =
    (tempC + 273.15) / (nominalC + 273.15) + seconds
  def safeFunctionVoltage(seconds: Double): Double = 3.5 + math.sin(seconds)

/** Actual public-source -> IR -> Verilog-A -> OpenVAF -> ngspice replay.
  *
  * Arguments: output-directory nodalc translator openvaf compiler-profile ngspice simulator-profile
  * source-head-sha. Source bytes are retained from the working tree, not asserted clean/published.
  * References and acceptance tolerances are written before any simulation runs. No target rewrite,
  * handwritten substitute model, graph flattening or leaf-only compiler fallback is used.
  */
object Increment49SourceReplay:
  import Increment49References.*

  private def bytes(value: String): Vector[Byte] = value.getBytes(StandardCharsets.UTF_8).toVector
  private def digest(value: String): String = OpenVafDiagnosticMapper.sha256(bytes(value))
  private def json(value: String): String = "\"" + value.flatMap {
    case '"' => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case char if char < ' ' => f"\\u${char.toInt}%04x"
    case char => char.toString
  } + "\""
  private def obj(fields: (String, String)*): String =
    fields.map((key, value) => s"${json(key)}:$value").mkString("{", ",", "}")
  private def array(values: Iterable[String]): String = values.mkString("[", ",", "]")
  private def write(directory: Path, filename: String, value: String): Unit =
    val _ = Files.writeString(
      directory.resolve(filename),
      value,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE_NEW
    )

  private def repositoryRoot(path: Path): Path =
    if Files.isRegularFile(path.resolve("build.mill")) then path
    else
      val parent = Option(path.getParent)
      require(parent.nonEmpty, "evidence must be under the Nodal repository")
      repositoryRoot(parent.get)

  private def tolerance(value: Tolerance, units: String): String = obj(
    "absolute" -> value.absolute.toString,
    "relative" -> value.relative.toString,
    "units" -> json(units),
    "criterion" -> json("abs(actual-reference) <= absolute + relative*abs(reference)")
  )

  private def referenceDeclaration: String = obj(
    "schema" -> json("nodal.f049.independent-references-v1"),
    "declaredBeforeSimulation" -> "true",
    "rc" -> obj(
      "resistanceOhms" -> ResistanceOhms.toString,
      "capacitanceFarads" -> CapacitanceFarads.toString,
      "dc" -> json("voltage -1 to 1 V, step 0.5 V; I(Vdrive)=-V/R A"),
      "ac" ->
        json("unit AC voltage; 3 points/decade, 10 to 10000 Hz; I(Vdrive)=-(1/R+j*2*pi*f*C) A"),
      "transient" -> json(
        "I from 0 to 0.001 A; delay 0.001 s, linear rise 0.0001 s; zero initial V; C*dV/dt+V/R=I(t); step 0.00001 s, stop 0.01 s"
      ),
      "scale" -> json("DC -5 to 5 V, step 0.001 V; same transient step, stop 0.1 s"),
      "currentTolerance" -> tolerance(CurrentTolerance, "A"),
      "transientTolerance" -> tolerance(TransientTolerance, "V")
    ),
    "generatedVoltageSources" -> obj(
      "stimulus" -> json(
        "zero-current unloaded output; zero bench initial voltage with UIC; generated initial_step may override it"
      ),
      "grid" -> json("step 0.001 s, stop 1 s"),
      "storage" -> json("V=1 V after generated initial_step"),
      "context" -> json("V=(37+273.15)/(27+273.15)+t V; temp=37 C, tnom=27 C, t in seconds"),
      "safeFunction" -> json("V=3.5+sin(t/1 s) V"),
      "voltageTolerance" -> tolerance(VoltageTolerance, "V")
    ),
    "repeatPolicy" -> json(
      "same model/artifact/request identity and exact numeric values; ignore raw Date and nonphysical AC frequency imaginary slot"
    )
  ) + "\n"

  def main(arguments: Array[String]): Unit =
    require(
      arguments.length == 8,
      "expected output-directory nodalc translator openvaf compiler-profile ngspice simulator-profile source-head-sha"
    )
    val root = Path.of(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val directory = Files.createTempDirectory(root, "source-replay-")
    val repository = repositoryRoot(directory)
    val nodalc = Path.of(arguments(1)).toAbsolutePath.normalize()
    val translator = Path.of(arguments(2)).toAbsolutePath.normalize()
    val openvaf = Path.of(arguments(3)).toAbsolutePath.normalize()
    val compilerProfile = arguments(4)
    val ngspice = Path.of(arguments(5)).toAbsolutePath.normalize()
    val simulatorProfile = arguments(6)
    val sourceRevision = arguments(7)
    require(sourceRevision.matches("[a-fA-F0-9]{40}"), "expected actual full Git HEAD SHA")
    require(
      compilerProfile == "openvaf-reloaded-20260616-linux-x86_64",
      "this finite replay requires the context-capable pinned OpenVAF Reloaded profile"
    )
    require(NgspiceProfiles.all.exists(_.id == simulatorProfile), "unknown simulator profile")
    val failures = mutable.ArrayBuffer.empty[String]
    val compilerRows = mutable.ArrayBuffer.empty[String]
    val simulationRows = mutable.ArrayBuffer.empty[String]
    val generated = mutable.Map.empty[String, (String, String)]
    val compiled = mutable.Map.empty[String, OpenVafCompileResult]
    val simulations = mutable.Map.empty[String, NgspiceSimulationResult]

    // Persist the fixed acceptance contract before reading any new simulated values.
    write(directory, "references.json", referenceDeclaration)
    write(
      directory,
      "run.json",
      obj(
        "schema" -> json("nodal.f049.source-replay-v1"),
        "sourceRevision" -> json(sourceRevision),
        "sourceState" ->
          json("working-tree replay; HEAD does not certify a clean or published tree"),
        "compilerProfile" -> json(compilerProfile),
        "simulatorProfile" -> json(simulatorProfile),
        "graphPolicy" -> json("unchanged whole public model; no flattening or leaf extraction"),
        "nodalc" -> json(nodalc.toString),
        "translator" -> json(translator.toString)
      ) + "\n"
    )
    val sourceFiles =
      (Increment48PublicSources.all.map(_.sourceFile) ++ Vector(
        "core/scala/testkit/test/src/nodal/internal/testkit/Increment48SourceExport.scala",
        "core/scala/testkit/test/src/nodal/internal/testkit/Increment49SourceReplay.scala",
        "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
        "core/scala/bridge/src/nodal/bridge/NativeCompilerClient.scala",
        "core/integrations/src/nodal/internal/integrations/OpenVafCompiler.scala",
        "core/integrations/src/nodal/internal/integrations/OpenVafProfiles.scala",
        "core/integrations/src/nodal/internal/integrations/ToolProcessRunner.scala",
        "core/integrations/src/nodal/internal/integrations/NgspiceBench.scala",
        "core/integrations/src/nodal/internal/integrations/NgspiceProfile.scala",
        "core/integrations/src/nodal/internal/integrations/NgspiceProfiles.scala",
        "core/integrations/src/nodal/internal/integrations/NgspiceRaw.scala",
        "core/integrations/src/nodal/internal/integrations/NgspiceSimulator.scala",
        "core/compiler/backend-profiles-v0.1.json"
      )).distinct.sorted
    val retained = sourceFiles.map: relative =>
      val data = Files.readAllBytes(repository.resolve(relative))
      val path = directory.resolve("source-files").resolve(relative + ".bytes")
      Files.createDirectories(path.getParent)
      Files.write(path, data, StandardOpenOption.CREATE_NEW)
      obj(
        "path" -> json(relative),
        "sha256" -> json(OpenVafDiagnosticMapper.sha256(data.toVector)),
        "bytes" -> data.length.toString
      )
    write(directory, "source-files.json", array(retained) + "\n")

    def runNative(
        name: String,
        document: NodalMlirDocument,
        tool: Path,
        args: Vector[String],
        output: Path
    ): Either[String, NativeCompilerSuccess] =
      val result = NativeCompilerClient.run(
        document,
        NativeCompilerRequest(tool, args, output, timeout = Duration.ofSeconds(60))
      )
      write(output, s"$name.stdout.log", result.standardOutput)
      write(output, s"$name.stderr.log", result.standardError)
      write(output, s"$name.command.json", array(result.command.map(json)) + "\n")
      result match
        case success: NativeCompilerSuccess => Right(success)
        case failure: NativeCompilerFailure =>
          Left(s"${output.getFileName}: ${failure.diagnostic}")

    def checkGraph(mlir: String, target: String, output: Path): Unit =
      val symbol = "sym_name = \"([^\"]+)\"".r
      val moduleReference = "module = @([A-Za-z_][A-Za-z0-9_$]*)".r
      val definitions = mlir.linesIterator.filter(_.contains("\"nodal.module\"("))
        .flatMap(line => symbol.findFirstMatchIn(line).map(_.group(1))).toVector
      val emitted = "(?m)^module ([A-Za-z_][A-Za-z0-9_$]*)(?:\\(|;)".r
        .findAllMatchIn(target).map(_.group(1)).toVector
      val instances = mlir.linesIterator.filter(_.contains("\"nodal.instance\"("))
        .flatMap: line =>
          for
            child <- moduleReference.findFirstMatchIn(line).map(_.group(1))
            name <- symbol.findFirstMatchIn(line).map(_.group(1))
          yield child -> name
        .toVector
      val retained = instances.forall: (child, name) =>
        val pattern =
          ("(?m)^\\s*" + java.util.regex.Pattern.quote(child) +
            "(?:\\s*#\\(.*\\))?\\s+" + java.util.regex.Pattern.quote(name) + "\\(.*\\);$").r
        pattern.findFirstIn(target).nonEmpty
      val complete = definitions.nonEmpty && definitions.sorted == emitted.sorted &&
        definitions.distinct.size == definitions.size && retained
      write(
        output,
        "target-graph.json",
        obj(
          "complete" -> complete.toString,
          "normalizedModules" -> array(definitions.map(json)),
          "targetModules" -> array(emitted.map(json)),
          "instanceCount" -> instances.size.toString,
          "allInstancesRetained" -> retained.toString
        ) + "\n"
      )
      require(complete, s"${output.getFileName}: whole target graph was not retained")

    def compile(
        name: String,
        normalized: String,
        model: String,
        output: Path
    ): OpenVafCompileResult =
      val result = OpenVafCompiler.compile(OpenVafCompileRequest(
        modelBytes = bytes(model),
        logicalFilename = "model.va",
        executable = openvaf,
        evidenceDirectory = output,
        profileId = compilerProfile,
        compileTimeout = Duration.ofSeconds(60),
        sourceMap = Some(OpenVafSourceMapBinding(digest(model))),
        provenance = OpenVafProvenance(Some(sourceRevision), Some(digest(normalized)))
      ))
      write(output, "compiler-result.json", OpenVafCompiler.receiptJson(result))
      compiled(name) = result
      result.sourceMapping match
        case OpenVafSourceMapping.Valid(_, _) => ()
        case other => failures += s"$name: invalid source mapping $other"
      compilerRows +=
        s"$name\t${result.outcome}\t${result.identity.modelSha256}\t${result.artifact.map(_.sha256).getOrElse("")}"
      println(s"F49 compile $name: ${result.outcome}; evidence=$output")
      result

    // Keep all inherited capability outcomes visible, including complete unsupported graphs.
    Increment48PublicSources.all.foreach: witness =>
      val output = Files.createDirectory(directory.resolve(witness.name))
      val snapshot = Increment48PublicSources.capture(witness)
      write(output, "construction.json", ReproducibilityContract.canonicalSnapshot(snapshot))
      val source =
        ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA, sourceProvenance = true)
      write(output, "source.mlir", source.text)
      val normalizedResult = runNative(
        "nodalc",
        source,
        nodalc,
        Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
        output
      )
      require(normalizedResult.isRight, normalizedResult.left.toOption.getOrElse("native failure"))
      val normalized = normalizedResult.toOption.get.normalizedMlir
      write(output, "normalized.mlir", normalized)
      val targetResult = runNative(
        "translator",
        source.copy(text = normalized, sha256 = digest(normalized)),
        translator,
        Vector("--nodal-to-verilog-a"),
        output
      )
      require(targetResult.isRight, targetResult.left.toOption.getOrElse("translation failure"))
      val target = targetResult.toOption.get.normalizedMlir
      write(output, "model.va", target)
      checkGraph(normalized, target, output)
      generated(witness.name) = normalized -> target
      val result = compile(witness.name, normalized, target, output)
      val expected = witness.name match
        case "rc" | "storage" | "context" | "safe-function" => OpenVafOutcome.Compiled
        case "ceil-function" => OpenVafOutcome.CompilerCrash
        case _ => OpenVafOutcome.CompilerError
      if result.outcome != expected then
        failures += s"${witness.name}: expected $expected, observed ${result.outcome}"
      if expected != OpenVafOutcome.Compiled && result.artifact.nonEmpty then
        failures += s"${witness.name}: failed compiler retained a successful artifact"

    val terminals = Vector(NgspiceTerminal.Ground, NgspiceTerminal.Driven)
    val rc = NgspiceModuleBinding(
      "RcFilter",
      terminals,
      Vector("R" -> ResistanceOhms, "C" -> CapacitanceFarads)
    )
    val bindings = Map(
      "rc" -> rc,
      "storage" -> NgspiceModuleBinding("AnalogCapabilityStorage", terminals),
      "context" -> NgspiceModuleBinding("AnalogContextSource", terminals),
      "safe-function" -> NgspiceModuleBinding("AnalogLintSafeSource", terminals)
    )
    bindings.foreach: (name, binding) =>
      val pins = if name == "rc" then "n, p" else "negative, positive"
      require(
        generated(name)._2.contains(s"module ${binding.moduleName}($pins);"),
        s"$name: declared terminal order changed; do not infer or silently swap bench pins"
      )
    val options = NgspiceSolverOptions()
    val dc = NgspiceAnalysis.DcVoltageSweep(-1, 1, 0.5)
    val ac = NgspiceAnalysis.AcDecadeSweep(3, 10, 10000)
    val ramp = NgspiceCurrentStimulus.Ramp(FinalAmps, DelaySeconds, RiseSeconds)
    val transient = NgspiceAnalysis.TransientCurrent(ramp, 1e-5, 0.01)
    val unloaded = NgspiceAnalysis.TransientCurrent(
      NgspiceCurrentStimulus.Ramp(0, 0, 0.001),
      0.001,
      1
    )

    def simulate(
        name: String,
        source: String,
        analysis: NgspiceAnalysis,
        model: NgspiceModuleBinding,
        solver: NgspiceSolverOptions = options,
        expected: NgspiceOutcome = NgspiceOutcome.Simulated
    ): NgspiceSimulationResult =
      val output = Files.createDirectory(directory.resolve(name))
      val result = NgspiceSimulator.simulate(NgspiceSimulateRequest(
        executable = ngspice,
        evidenceDirectory = output,
        profileId = simulatorProfile,
        compiled = compiled(source),
        model = model,
        analysis = analysis,
        options = solver,
        runTimeout = Duration.ofSeconds(30)
      ))
      write(output, "simulation-result.json", NgspiceSimulator.receiptJson(result))
      simulations(name) = result
      simulationRows +=
        s"$name\t${result.outcome}\t${result.plot.map(_.rows.size).getOrElse(0)}\t${result.simulation.map(_.elapsedMillis).getOrElse(0L)}\t${result.raw.map(_.size).getOrElse(0L)}"
      if result.outcome != expected then
        failures += s"$name: expected $expected, observed ${result.outcome}: ${result.message}"
      println(
        s"F49 simulate $name: ${result.outcome}; points=${result.plot.map(_.rows.size).getOrElse(0)}; evidence=$output"
      )
      result

    def compare(
        name: String,
        reference: Vector[NgspiceComplex] => Vector[NgspiceComplex],
        checks: Vector[(String, Tolerance)]
    ): Unit =
      simulations(name).plot.foreach: plot =>
        require(plot.variables.tail.map(_.name) == checks.map(_._1), s"$name: unexpected variables")
        val maximum = Array.fill(checks.size)(0.0)
        val mismatches = Array.fill(checks.size)(0)
        plot.rows.foreach: row =>
          val expected = reference(row)
          require(expected.size == checks.size)
          checks.indices.foreach: index =>
            val actual = row(index + 1)
            val wanted = expected(index)
            val error = math.max(
              math.abs(actual.real - wanted.real),
              math.abs(actual.imaginary - wanted.imaginary)
            )
            maximum(index) = math.max(maximum(index), error)
            val limit = checks(index)._2
            if !limit.accepts(actual.real, wanted.real) ||
              !limit.accepts(actual.imaginary, wanted.imaginary)
            then mismatches(index) += 1
        val rows = checks.indices.map: index =>
          obj(
            "variable" -> json(checks(index)._1),
            "maxAbsoluteComponentError" -> maximum(index).toString,
            "mismatchedRows" -> mismatches(index).toString
          )
        write(
          directory.resolve(name),
          "numerical-comparison.json",
          obj(
            "points" -> plot.rows.size.toString,
            "results" -> array(rows),
            "passed" -> mismatches.forall(_ == 0).toString
          ) + "\n"
        )
        if mismatches.exists(_ != 0) then
          failures += s"$name: numerical mismatch ${mismatches.mkString(",")}"
        println(
          s"F49 compare $name: max component errors=${maximum.mkString(",")}; mismatches=${mismatches.sum}"
        )

    def compareRc(name: String, kind: NgspiceAnalysisKind): Unit = kind match
      case NgspiceAnalysisKind.Dc => compare(
          name,
          row =>
            Vector(
              NgspiceComplex(row.head.real, 0),
              NgspiceComplex(dcSourceCurrent(row.head.real), 0)
            ),
          Vector("v(p)" -> VoltageTolerance, "i(vdrive)" -> CurrentTolerance)
        )
      case NgspiceAnalysisKind.Ac => compare(
          name,
          row => Vector(NgspiceComplex(1, 0), acSourceCurrent(row.head.real)),
          Vector("v(p)" -> VoltageTolerance, "i(vdrive)" -> CurrentTolerance)
        )
      case NgspiceAnalysisKind.Transient => compare(
          name,
          row => Vector(NgspiceComplex(rcVoltage(row.head.real), 0)),
          Vector("v(p)" -> TransientTolerance)
        )

    Vector(
      "rc-dc" -> dc,
      "rc-ac" -> ac,
      "rc-transient" -> transient,
      "rc-dc-scale" -> NgspiceAnalysis.DcVoltageSweep(-5, 5, 0.001),
      "rc-transient-scale" -> NgspiceAnalysis.TransientCurrent(ramp, 1e-5, 0.1)
    )
      .foreach: (name, analysis) =>
        simulate(name, "rc", analysis, rc)
        compareRc(name, analysis.kind)

    Vector("storage", "context", "safe-function").foreach: source =>
      val name = s"$source-transient"
      val solver = if source == "context" then options.copy(temp = 37, tnom = 27) else options
      simulate(name, source, unloaded, bindings(source), solver)
      compare(
        name,
        row => {
          val expected = source match
            case "storage" => 1.0
            case "context" => contextVoltage(row.head.real, solver.temp, solver.tnom)
            case _ => safeFunctionVoltage(row.head.real)
          Vector(NgspiceComplex(expected, 0))
        },
        Vector("v(p)" -> VoltageTolerance)
      )

    // Recompile identical RC bytes, then repeat each analysis in a fresh evidence directory.
    val repeatOutput = Files.createDirectory(directory.resolve("rc-compile-repeat"))
    val (normalized, model) = generated("rc")
    val repeatCompile = compile("rc-repeat", normalized, model, repeatOutput)
    val firstCompile = compiled("rc")
    val compileIdentity = repeatCompile.identity.requestSha256.nonEmpty &&
      repeatCompile.identity.requestSha256 == firstCompile.identity.requestSha256 &&
      repeatCompile.identity.modelSha256 == firstCompile.identity.modelSha256 &&
      repeatCompile.artifact.map(_.sha256).nonEmpty &&
      repeatCompile.artifact.map(_.sha256) == firstCompile.artifact.map(_.sha256) &&
      repeatCompile.evidenceDirectory != firstCompile.evidenceDirectory
    if !compileIdentity then failures += "RC compiler/model/artifact repeat identity differs"
    val repeatChecks =
      Vector("rc-dc" -> dc, "rc-ac" -> ac, "rc-transient" -> transient).map: (name, analysis) =>
        val repeated = simulate(s"$name-repeat", "rc-repeat", analysis, rc)
        compareRc(s"$name-repeat", analysis.kind)
        val first = simulations(name)
        def numericRows(result: NgspiceSimulationResult) = result.plot.map(_.rows.map: row =>
          // The frequency imaginary slot is an ngspice writer artifact, not an AC coordinate.
          row.updated(0, NgspiceComplex(row.head.real, 0)))
        val identical = first.outcome == NgspiceOutcome.Simulated &&
          repeated.outcome == NgspiceOutcome.Simulated &&
          first.identity.requestSha256.nonEmpty &&
          first.identity.requestSha256 == repeated.identity.requestSha256 &&
          first.evidenceDirectory != repeated.evidenceDirectory &&
          first.plot.map(_.variables) == repeated.plot.map(_.variables) &&
          numericRows(first) == numericRows(repeated)
        if !identical then failures += s"$name: repeated request or numeric output differs"
        obj(
          "case" -> json(name),
          "sameRequestAndNumericOutput" -> identical.toString,
          "rawByteIdentityRequired" -> "false"
        )
    write(
      directory,
      "repeat-checks.json",
      obj(
        "sameCompileIdentity" -> compileIdentity.toString,
        "analyses" -> array(repeatChecks)
      ) + "\n"
    )

    simulate(
      "missing-module-control",
      "rc",
      dc,
      rc.copy(moduleName = "MissingGeneratedModule"),
      expected = NgspiceOutcome.LoadFailure
    )
    simulate(
      "conflicting-voltage-control",
      "storage",
      dc,
      bindings("storage"),
      expected = NgspiceOutcome.Nonconvergence
    )

    write(
      directory,
      "compiler-results.tsv",
      "case\toutcome\tmodelSha256\tartifactSha256\n" + compilerRows.mkString("\n") + "\n"
    )
    write(
      directory,
      "simulation-results.tsv",
      "case\toutcome\tpoints\telapsedMillis\trawBytes\n" + simulationRows.mkString("\n") + "\n"
    )
    write(
      directory,
      "replay-checks.json",
      obj(
        "failures" -> array(failures.map(json)),
        "scope" -> json(
          "selected generated-model DC/AC/transient and initialization/context checks; no general event/noise or universal simulator equivalence"
        )
      ) + "\n"
    )
    println(s"F49 public-source simulation replay retained at $directory")
    require(failures.isEmpty, failures.mkString("; "))
