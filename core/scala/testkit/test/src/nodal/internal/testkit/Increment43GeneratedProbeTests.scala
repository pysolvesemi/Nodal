package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class Increment43NestedSelectedProbes extends Module:
  val rows: Param[Integer] = param(3.integer, range = 2 to 5)
  val currents = inout(Electrical, rows, 2)
  val voltages = inout(Electrical, rows, 2)
  val outputs = inout(Electrical, rows, 2)
  val common = inout(Electrical)
  hdlRange(0, rows): row =>
    hdlRange(0, 2): column =>
      val current = currents.at(row, column)
      val voltage = voltages.at(row, column)
      val output = outputs.at(row, column)
      analog:
        I(output, common) <+ I(current, common) + I(current, common) + V(voltage, common) / 1.kOhm

final class Increment43InvariantProbeKinds(emptyPotential: Boolean) extends Module:
  val samples = inout(Electrical, 2)
  val outputs = inout(Electrical, 2)
  val common = inout(Electrical)
  hdlRange(0, 1): _ =>
    val selected = samples.at(0)
    val output = outputs.at(0)
    analog:
      I(output, common) <+ I(selected, common)
  hdlRange(0, if emptyPotential then 0 else 1): _ =>
    val selected = samples.at(0)
    val output = outputs.at(1)
    analog:
      I(output, common) <+ V(selected, common) / 1.kOhm

object Increment43GeneratedProbeTests extends TestSuite:
  val positiveModels: Vector[() => Module] = Vector(
    () => new Increment43NestedSelectedProbes,
    () => new Increment43InvariantProbeKinds(true)
  )

  private def run(document: NodalMlirDocument, tool: String, arguments: Vector[String], root: Path)
      : NativeCompilerResult = NativeCompilerClient.run(
    document,
    NativeCompilerRequest(Path.of(tool), arguments, root, timeout = Duration.ofSeconds(90))
  )

  private def success(result: NativeCompilerResult): String = result match
    case value: NativeCompilerSuccess => value.normalizedMlir
    case failure: NativeCompilerFailure =>
      throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")

  private def verifyProbePlacement(snapshot: ConstructionSnapshot, text: String): Unit =
    val probes = text.linesIterator.filter(_.contains("\"nodal.probe\"")).toVector
    val expected = if snapshot.root == "Increment43NestedSelectedProbes" then 2 else 1
    assert(probes.size == expected)
    val support = if expected == 2 then snapshot.generatedRegions.find(_.parent.nonEmpty).get
    else snapshot.generatedRegions.find(_.maximumTripCount == 1).get
    val generate = text.linesIterator.find(line =>
      line.contains("\"nodal.generate\"") && line.contains(s"region_id = \"${support.path}\"")
    ).get
    probes.foreach: probe =>
      assert(
        probe.takeWhile(_ == ' ').length == generate.takeWhile(_ == ' ').length + 2,
        probe.contains(s"generated_owner = \"${support.path}\""),
        probe.contains(s"generated_induction = \"${support.induction}\"")
      )
    assert(probes.count(_.contains("kind = \"flow\"")) == 1)
    if expected == 2 then assert(probes.count(_.contains("kind = \"potential\"")) == 1)
    val region = snapshot.analogRegions.find(_.generatedOwner.contains(support.path)).get
    val observed = region.expressions.filter(expression =>
      Set("potential_access", "flow_access").contains(expression.operation) &&
        !region.contributions.exists(_.target == expression.path)
    )
    assert(observed.size == (if expected == 2 then 3 else 1))
    observed.foreach(expression => assert(probes.exists(_.contains(s"\"${expression.path}\""))))

  val tests: Tests = Tests:
    test("generated source free accesses are consumed without sourcing their observed branches"):
      positiveModels.foreach: construct =>
        val snapshot = ConstructionKernel.inspect(construct())
        val repeated = ConstructionKernel.inspect(construct())
        assert(ScalaToMlirBridge.fromSnapshot(snapshot).text ==
          ScalaToMlirBridge.fromSnapshot(repeated).text)
        snapshot.analogRegions.foreach: region =>
          assert(region.generatedOwner.nonEmpty, region.contributions.size == 1)
          val target = region.expressions.find(_.path == region.contributions.head.target).get
          val selected = snapshot.shapeIndices.find(_.path == target.operands.head).get
          assert(selected.input == s"${snapshot.root}.outputs")
        val artifacts = ReproducibilityContract.describe(snapshot)
        assert(artifacts == ReproducibilityContract.describe(repeated))

    test("generated probe normalization preserves support provenance and VA AMS consumers"):
      val nodalc = sys.env.getOrElse(
        "NODAL_NODALC",
        throw new java.lang.AssertionError("configured nodalc is required")
      )
      val translator = sys.env.getOrElse(
        "NODAL_TRANSLATE",
        throw new java.lang.AssertionError("configured nodal-translate is required")
      )
      val directory = Path.of(".work", "generated-probes-public-final").toAbsolutePath
      Files.createDirectories(directory)
      positiveModels.foreach: construct =>
        Vector(Backend.VerilogA -> "va", Backend.VerilogAMS -> "vams").foreach:
          (backend, extension) =>
            val snapshot = ConstructionKernel.inspect(construct(), EmitOptions(backend = backend))
            val document = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
            val stem = snapshot.root + "." + extension
            Files.writeString(directory.resolve(stem + ".source.mlir"), document.text)
            val gate = Vector("--pass-pipeline=builtin.module(nodal-gate-default)")
            val normalized = success(run(document, nodalc, gate, directory))
            assert(normalized ==
              success(run(document.copy(text = normalized), nodalc, gate, directory)))
            verifyProbePlacement(snapshot, normalized)
            Files.writeString(directory.resolve(stem + ".normalized.mlir"), normalized)
            val flag = if backend == Backend.VerilogA then "--nodal-to-verilog-a"
            else "--nodal-to-verilog-ams"
            val output =
              success(run(document.copy(text = normalized), translator, Vector(flag), directory))
            assert(output ==
              success(run(document.copy(text = normalized), translator, Vector(flag), directory)))
            Files.writeString(directory.resolve(stem), output)
            val contributions = output.linesIterator.filter(_.contains("<+")).toVector
            assert(contributions.nonEmpty, contributions.forall(_.trim.startsWith("I(outputs[")))
            if snapshot.root == "Increment43NestedSelectedProbes" then
              assert(contributions.size == 1)
              val contribution = contributions.head
              assert(
                "I\\(currents\\[".r.findAllIn(contribution).size == 2,
                contribution.contains("V(voltages["),
                contribution.contains("/ 1000")
              )
              assert(output.contains("< rows;"), !output.contains("< 3;"))
            else
              assert(contributions.size == 2, output.contains("< 0;"))

    test("invariant selected source free flow and potential conflict across generated siblings"):
      val nodalc = sys.env.getOrElse(
        "NODAL_NODALC",
        throw new java.lang.AssertionError("configured nodalc is required")
      )
      val snapshot = ConstructionKernel.inspect(new Increment43InvariantProbeKinds(false))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val directory = Path.of(".work", "generated-probes-negative").toAbsolutePath
      Files.createDirectories(directory)
      Files.writeString(directory.resolve("mixed-invariant.source.mlir"), document.text)
      run(
        document,
        nodalc,
        Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
        directory
      ) match
        case failure: NativeCompilerFailure =>
          assert(
            failure.standardError.contains("NODAL-PROBE-KIND-001"),
            failure.standardError.contains("source-free")
          )
        case _ => throw new java.lang.AssertionError("mixed source-free probe kinds were accepted")
