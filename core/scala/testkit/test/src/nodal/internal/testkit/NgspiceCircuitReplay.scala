package nodal.internal.testkit

import java.nio.file.{Files, Path}

import nodal.internal.integrations.*

/** Focused pinned-tool replay of the new bench seam against the actual generated F49 RC source. */
object NgspiceCircuitReplay:
  def main(args: Array[String]): Unit =
    require(
      args.length == 4,
      "usage: NgspiceCircuitReplay generated-rc.va evidence-directory openvaf ngspice"
    )
    val source = Path.of(args(0)).toAbsolutePath
    val directory = Path.of(args(1)).toAbsolutePath
    Files.createDirectories(directory)
    val compiler = Path.of(args(2)).toAbsolutePath
    val simulator = Path.of(args(3)).toAbsolutePath
    val compiled = OpenVafCompiler.compile(OpenVafCompileRequest(
      Files.readAllBytes(source).toVector,
      "model.va",
      compiler,
      directory,
      "openvaf-reloaded-20260616-linux-x86_64"
    ))
    require(compiled.outcome == OpenVafOutcome.Compiled, compiled.message)
    val ground = NgspiceNode.Ground
    val node = NgspiceNode.Node(0)
    val id = NgspiceSourceId(0)
    val base = NgspiceCircuitBench(
      NgspiceCircuitBinding("RcFilter", Vector(ground, node), Vector("R" -> 1000, "C" -> 1e-6)),
      Vector(node),
      Vector(NgspiceSource(
        id,
        NgspiceSourceKind.Current,
        ground,
        node,
        ac = Some(NgspiceAcExcitation(0.001, 30))
      )),
      NgspiceCircuitAnalysis.DcSweep(id, -0.001, 0.001, 0.0005),
      Vector(NgspiceProbe.Current(id), NgspiceProbe.Voltage(node))
    )
    def run(name: String, bench: NgspiceCircuitBench): NgspiceSimulationResult =
      val result = NgspiceSimulator.simulate(NgspiceCircuitSimulateRequest(
        simulator,
        directory.resolve(name),
        "ngspice-47-local-linux-x86_64",
        compiled,
        bench
      ))
      require(
        result.outcome == NgspiceOutcome.Simulated,
        s"$name: ${result.outcome}: ${result.message}"
      )
      result
    def close(actual: Double, expected: Double, absolute: Double = 1e-10): Unit =
      require(
        math.abs(actual - expected) <= absolute + math.abs(expected) * 1e-6,
        s"actual=$actual expected=$expected"
      )
    val dc = run("current-dc", base)
    dc.plot.get.rows.foreach: row =>
      close(row(1).real, row(0).real)
      close(row(2).real, row(0).real * 1000)
    val ac =
      run("current-ac", base.copy(analysis = NgspiceCircuitAnalysis.AcDecadeSweep(1, 10, 1000)))
    val currentReal = 0.001 * math.cos(math.Pi / 6)
    val currentImaginary = 0.001 * math.sin(math.Pi / 6)
    ac.plot.get.rows.foreach: row =>
      close(row(1).real, currentReal)
      close(row(1).imaginary, currentImaginary)
      val conductance = 0.001
      val susceptance = 2 * math.Pi * row(0).real * 1e-6
      val denominator = conductance * conductance + susceptance * susceptance
      close(row(2).real, (currentReal * conductance + currentImaginary * susceptance) / denominator)
      close(
        row(2).imaginary,
        (currentImaginary * conductance - currentReal * susceptance) / denominator
      )
    val transient = base.copy(
      sources = base.sources.map(_.copy(transient =
        Some(NgspiceTransientWaveform.Pwl(
          Vector(0.0 -> 0.0, 0.001 -> 0.001, 0.01 -> 0.001)
        ))
      )),
      analysis = NgspiceCircuitAnalysis.Transient(1e-5, 0.01, true),
      initialConditions = Vector(NgspiceInitialCondition(node, 0))
    )
    val pwl = run("current-pwl", transient)
    pwl.plot.get.rows.foreach: row =>
      close(row(1).real, math.min(row(0).real, 0.001))
      val time = row(0).real
      val expected = if time <= 0.001 then 1000 * (time - 0.001 * (1 - math.exp(-time / 0.001)))
      else 1 + (math.exp(-1) - 1) * math.exp(-(time - 0.001) / 0.001)
      close(row(2).real, expected, 2e-5)
    val pulse = run(
      "current-pulse",
      transient.copy(sources =
        base.sources.map(_.copy(
          transient =
            Some(NgspiceTransientWaveform.Pulse(0, 0.001, 0.001, 0.001, 0.001, 0.02, 0.04))
        ))
      )
    )
    pulse.plot.get.rows.foreach: row =>
      close(row(1).real, math.max(0, math.min(0.001, row(0).real - 0.001)))
    val repeat = run("current-dc-repeat", base)
    require(repeat.identity.requestSha256 == dc.identity.requestSha256)
    require(repeat.plot.get.rows == dc.plot.get.rows)
    val lines = Vector(
      "compile=Compiled",
      s"dc=${dc.plot.get.rows.size}",
      s"ac=${ac.plot.get.rows.size}",
      s"pwl=${pwl.plot.get.rows.size}",
      s"pulse=${pulse.plot.get.rows.size}",
      "sensor-orientation=passed",
      "complex-ac-reference=passed",
      "pwl-voltage-reference=passed",
      "repeat=passed"
    )
    Files.writeString(directory.resolve("checks.txt"), lines.mkString("", "\n", "\n"))
    println(lines.mkString("\n"))
