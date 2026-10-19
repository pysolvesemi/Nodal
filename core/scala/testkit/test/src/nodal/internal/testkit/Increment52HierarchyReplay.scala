package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Duration
import java.security.MessageDigest

import nodal.*
import nodal.sim.*
import nodal.increment44fixture.AnalogContextSource
import nodal.internal.integrations.*

/** Whole-source hierarchy replay; only the shared compiler chooses materialization. */
object Increment52HierarchyReplay:
  private def write(path: Path, value: String): Unit =
    val _ = Files.writeString(path, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)

  private def digest(path: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
      .map(value => f"${value & 0xff}%02x").mkString

  def main(arguments: Array[String]): Unit =
    require(
      arguments.length == 7,
      "expected output-root nodalc translator openvaf compiler-profile ngspice simulator-profile"
    )
    val directory = Files.createTempDirectory(Path.of(arguments(0)).toAbsolutePath, "hierarchy-")
    val sourcePaths = Vector(
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment52HierarchyFixtures.scala",
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment52HierarchyReplay.scala",
      "core/scala/sim/src/nodal/sim/SimulationCompilation.scala",
      "core/compiler/lib/Backend/ScalarHierarchyMaterialization.cpp",
      "core/compiler/lib/Backend/Backend.cpp",
      "examples/continuousTimeApi/src/nodal/increment44fixture/Increment44ConstructionCheck.scala"
    )
    val retained = sourcePaths.map: relative =>
      val sourcePath = Path.of(relative)
      val destination = directory.resolve("source-files").resolve(relative + ".bytes")
      Files.createDirectories(destination.getParent)
      Files.copy(sourcePath, destination)
      s"$relative\t${digest(destination)}\t${Files.size(destination)}"
    write(
      directory.resolve("source-files.tsv"),
      "path\tsha256\tbytes\n" + retained.mkString("\n") + "\n"
    )
    val tools = CompilationTools(
      Path.of(arguments(1)).toAbsolutePath,
      Path.of(arguments(2)).toAbsolutePath,
      Path.of(arguments(3)).toAbsolutePath,
      arguments(4),
      directory.resolve("compilations"),
      Duration.ofSeconds(60),
      hierarchy = AnalogHierarchyMode.MaterializeScalar
    )
    write(
      directory.resolve("reference.txt"),
      "Whole captured graph: first -> nested leaf(rootGain); second -> leaf(3); third -> nested leaf(5); fourth -> leaf(rootGain).\n" +
        "Each leaf contributes I(p,g)=gain*V(p,g)/1000 ohm through an authored pure function.\n" +
        "DC source currents are negative model currents. V0 sweeps -1..1 step .5; V1=.5, V2=1.5, V3=-.25 V.\n" +
        "External runtime rootGain=2,4,6 on one compiled OSDI. Absolute tolerance 1e-12 A plus 1e-6 relative.\n" +
        "Repeated rootGain=4 must reproduce exact numerical rows and compile/model/artifact identities.\n"
    )
    val captured = Increment52HierarchySource.capture().toOption
    require(captured.nonEmpty, "hierarchy source capture failed")
    val source = captured.get
    require(
      Increment52HierarchySource.capture().toOption.exists(
        _.canonicalConstruction == source.canonicalConstruction
      ),
      "nondeterministic source capture"
    )
    write(directory.resolve("construction.json"), source.canonicalConstruction)
    val result = SimulationCompiler.compile(source, tools)
    write(directory.resolve("compile-result.txt"), result.toString)
    println(
      s"F52 hierarchy compile: ${result.succeeded}; ${result.error}; ${result.evidence.map(_.directory)}"
    )
    require(result.succeeded, s"whole hierarchy compilation failed: ${result.error}")
    val model = result.model.get
    val target = Files.readString(model.evidence.directory.resolve("model.va"))
    require(
      target.contains("nodal.hierarchy_correspondence"),
      "missing authored/materialized correspondence"
    )
    require(target.contains("parameter real rootGain"), "root runtime parameter disappeared")
    write(directory.resolve("model.va"), target)
    val portNodes = Map(
      "p0" -> NgspiceNode.Node(0),
      "p1" -> NgspiceNode.Node(1),
      "p2" -> NgspiceNode.Node(2),
      "p3" -> NgspiceNode.Node(3),
      "ground" -> NgspiceNode.Ground
    )
    val nodes = Vector.tabulate(4)(NgspiceNode.Node.apply)
    val ids = Vector.tabulate(4)(NgspiceSourceId.apply)
    val sources = Vector(0.0, 0.5, 1.5, -0.25).zipWithIndex.map: (voltage, index) =>
      NgspiceSource(
        ids(index),
        NgspiceSourceKind.Voltage,
        nodes(index),
        NgspiceNode.Ground,
        voltage
      )
    val observations = Vector(2.0, 4.0, 6.0, 4.0).zipWithIndex.map: (gain, ordinal) =>
      val output = Files.createDirectory(directory.resolve(s"gain-$ordinal"))
      val bench = NgspiceCircuitBench(
        NgspiceCircuitBinding(
          model.moduleName,
          model.portsInTargetOrder.map(port => portNodes(port.label)),
          Vector("rootGain" -> gain)
        ),
        nodes,
        sources,
        NgspiceCircuitAnalysis.DcSweep(ids.head, -1, 1, 0.5),
        ids.map(NgspiceProbe.Current.apply)
      )
      val simulated = NgspiceSimulator.simulate(NgspiceCircuitSimulateRequest(
        Path.of(arguments(5)).toAbsolutePath,
        output,
        arguments(6),
        model.compiled,
        bench,
        runTimeout = Duration.ofSeconds(60)
      ))
      write(output.resolve("receipt.json"), NgspiceSimulator.receiptJson(simulated))
      println(s"F52 hierarchy gain=$gain: ${simulated.outcome}; ${simulated.message}")
      require(simulated.outcome == NgspiceOutcome.Simulated, "hierarchy simulator failure")
      require(simulated.raw.exists(raw => digest(raw.path) == raw.sha256), "raw digest mismatch")
      val plot = simulated.plot.get
      require(plot.rows.size == 5 && plot.variables.size == 5, "unexpected hierarchy output schema")
      var maximum = 0.0
      plot.rows.foreach: row =>
        val expected = Vector(
          -gain * row.head.real / 1000,
          -3 * .5 / 1000,
          -5 * 1.5 / 1000,
          -gain * -.25 / 1000
        )
        expected.zipWithIndex.foreach: (wanted, index) =>
          val actual = row(index + 1)
          val error = math.abs(actual.real - wanted)
          maximum = math.max(maximum, error)
          require(
            error <= 1e-12 + 1e-6 * math.abs(wanted) && actual.imaginary == 0,
            s"hierarchy current mismatch gain=$gain port=$index: $actual expected $wanted"
          )
      write(
        output.resolve("comparison.txt"),
        s"points=${plot.rows.size}\nmaximumAbsoluteError=$maximum\n"
      )
      (simulated.identity.modelSha256, simulated.identity.artifactSha256, plot.rows)
    require(observations(1) == observations(3), "repeated runtime parameter result changed")
    require(
      observations.map(_._1).distinct.size == 1 && observations.map(_._2).distinct.size == 1,
      "runtime parameter execution changed compiled model"
    )
    val contextSource = SimulationSource.capture(new AnalogContextSource)(model =>
      Vector(PortSelection("positive", model.positive), PortSelection("negative", model.negative))
    )
    require(contextSource.isRight, "context source capture failed")
    val contextResult = SimulationCompiler.compile(
      contextSource.toOption.get,
      tools.copy(hierarchy = AnalogHierarchyMode.PreserveNamed)
    )
    require(contextResult.succeeded, s"context source compile failed: ${contextResult.error}")
    val contextModel = contextResult.model.get
    write(
      directory.resolve("context-reference.txt"),
      "V=(tempC+273.15)/(tnomC+273.15)+timeSeconds. Temperature pairs (27,27),(37,27),(27,47),(37,47) C.\n" +
        "Unloaded output, independent zero current source, transient 0..0.01 s. Absolute1e-9 V+relative1e-6.\n"
    )
    Vector((27.0, 27.0), (37.0, 27.0), (27.0, 47.0), (37.0, 47.0)).zipWithIndex.foreach:
      case ((temperature, nominal), ordinal) =>
        val output = Files.createDirectory(directory.resolve(s"context-$ordinal"))
        val driven = NgspiceNode.Node(0)
        val bench = NgspiceCircuitBench(
          NgspiceCircuitBinding(
            contextModel.moduleName,
            contextModel.portsInTargetOrder.map(port =>
              if port.label == "positive" then driven else NgspiceNode.Ground
            )
          ),
          Vector(driven),
          Vector(NgspiceSource(
            NgspiceSourceId(0),
            NgspiceSourceKind.Current,
            driven,
            NgspiceNode.Ground
          )),
          NgspiceCircuitAnalysis.Transient(0.001, 0.01, false),
          Vector(NgspiceProbe.Voltage(driven)),
          options = NgspiceSolverOptions(temp = temperature, tnom = nominal)
        )
        val simulated = NgspiceSimulator.simulate(NgspiceCircuitSimulateRequest(
          Path.of(arguments(5)).toAbsolutePath,
          output,
          arguments(6),
          contextModel.compiled,
          bench,
          runTimeout = Duration.ofSeconds(60)
        ))
        write(output.resolve("receipt.json"), NgspiceSimulator.receiptJson(simulated))
        require(
          simulated.outcome == NgspiceOutcome.Simulated,
          s"context simulator failure: ${simulated.message}"
        )
        require(
          simulated.raw.exists(raw => digest(raw.path) == raw.sha256),
          "context raw digest mismatch"
        )
        simulated.plot.get.rows.foreach: row =>
          val expected = (temperature + 273.15) / (nominal + 273.15) + row.head.real
          require(
            math.abs(row(1).real - expected) <= 1e-9 + 1e-6 * math.abs(expected),
            s"temperature/nominal/time mismatch: ${row(1)} expected $expected"
          )
        println(s"F52 runtime temp=$temperature tnom=$nominal: PASS; ${simulated.validatedPoints}")
    write(
      directory.resolve("state-reference.txt"),
      "Separate source: one real child contributes gain*V/1000; root initial_step sets held=1 V and contributes held/1000.\n" +
        "DC source current=-(gain*V+1)/1000 A; gain=2,6; V=-1..1 step .5. Acceptance1e-12 A+1e-6 relative.\n"
    )
    val stateSource = SimulationSource.capture(new Increment52HierarchyStateTop)(model =>
      Vector(
        PortSelection("positive", model.positive),
        PortSelection("ground", model.referenceNode)
      )
    )
    require(stateSource.isRight, "hierarchy/state source capture failed")
    val stateCompilation = SimulationCompiler.compile(stateSource.toOption.get, tools)
    require(
      stateCompilation.succeeded,
      s"hierarchy/state compilation failed: ${stateCompilation.error}"
    )
    val stateModel = stateCompilation.model.get
    Vector(2.0, 6.0).zipWithIndex.foreach: (gain, ordinal) =>
      val output = Files.createDirectory(directory.resolve(s"hierarchy-state-$ordinal"))
      val driven = NgspiceNode.Node(0)
      val sourceId = NgspiceSourceId(0)
      val bench = NgspiceCircuitBench(
        NgspiceCircuitBinding(
          stateModel.moduleName,
          stateModel.portsInTargetOrder.map(port =>
            if port.label == "positive" then driven else NgspiceNode.Ground
          ),
          Vector("rootGain" -> gain)
        ),
        Vector(driven),
        Vector(NgspiceSource(
          sourceId,
          NgspiceSourceKind.Voltage,
          driven,
          NgspiceNode.Ground
        )),
        NgspiceCircuitAnalysis.DcSweep(sourceId, -1, 1, .5),
        Vector(NgspiceProbe.Current(sourceId))
      )
      val simulated = NgspiceSimulator.simulate(NgspiceCircuitSimulateRequest(
        Path.of(arguments(5)).toAbsolutePath,
        output,
        arguments(6),
        stateModel.compiled,
        bench,
        runTimeout = Duration.ofSeconds(60)
      ))
      write(output.resolve("receipt.json"), NgspiceSimulator.receiptJson(simulated))
      require(
        simulated.outcome == NgspiceOutcome.Simulated,
        s"hierarchy/state simulator failed: ${simulated.message}"
      )
      require(
        simulated.raw.exists(raw => digest(raw.path) == raw.sha256),
        "state raw digest mismatch"
      )
      val rows = simulated.plot.get.rows
      require(rows.size == 5, "hierarchy/state missing DC points")
      rows.foreach: row =>
        val expected = -(gain * row.head.real + 1) / 1000
        require(
          math.abs(row(1).real - expected) <= 1e-12 + 1e-6 * math.abs(expected),
          s"hierarchy/state mismatch: ${row(1)} expected $expected"
        )
      println(s"F52 hierarchy initial_step/state gain=$gain: PASS")
    write(
      directory.resolve("summary.txt"),
      "PASS: four complete-graph runtime parameter runs; 80 current comparisons; exact repeat; four independent temperature/nominal transient cases; two hierarchy initial_step/state cases.\n"
    )
    println(s"F52 hierarchy PASS: $directory")
