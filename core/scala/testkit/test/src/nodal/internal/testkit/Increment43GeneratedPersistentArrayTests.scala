package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class Increment43PersistentGeneratedSymbolic extends Module:
  val count = param(3.integer, range = 1 to 5)
  val lanes = param(2.integer, range = 2 to 4)
  val level = param(1.25.V)
  hdlRange(1, count + 1.integer, 2): _ =>
    analogProcedure:
      val cells = variable(
        Vec(Real, lanes, 2),
        Vec.fill(Vec(Real, lanes, 3))(level).slice(1, 3, axis = 1)
      )
      cells.writable.at(0, 1) := cells.at(0, 0) + 1.0.V
      on(initialStep):
        val copied = variable(Vec(Real, lanes, 2), cells)
        copied.writable.at(0, 0) := 2.0.V

final class Increment43PersistentGeneratedMixed extends Module:
  val count = param(2.integer, range = 1 to 4)
  val taps = inout(Electrical, count)
  val common = inout(Electrical)
  hdlRange(0, count): i =>
    val element = instance(new Increment43TopologyCell)
    connect(taps.at(i), element(_.p))
    connect(common, element(_.n))
    analogProcedure:
      val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(0.5.V))
      cells.writable.at(1) := cells.at(0) + 1.0.V
      on(initialStep):
        val copied = variable(Vec(Real, 2), cells)
        copied.writable.at(0) := 2.0.V

final class Increment43PersistentGeneratedNested extends Module:
  val count = param(2.integer, range = 1 to 3)
  hdlRange(0, count): _ =>
    analogProcedure:
      val outer = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(5.5.real))
      outer.writable.at(0) := outer.at(1)
    hdlRange(0, 2): _ =>
      analogProcedure:
        val inner = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(6.5.real))
        inner.writable.at(1) := inner.at(0)

final class Increment43PersistentGeneratedSiblings extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val left = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(7.5.real))
      left.writable.at(0) := left.at(1)
  hdlRange(0, 2): _ =>
    analogProcedure:
      val right = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(8.5.real))
      right.writable.at(1) := right.at(0)

final class Increment43PersistentGeneratedSingleton extends Module:
  hdlRange(1, 2, 2): _ =>
    analogProcedure:
      val cells = variable(Vec(Real, 1), Vec.fill(Vec(Real, 1))(9.5.real))
      cells.writable.at(0) := cells.at(0) + 1.0.real

object Increment43PersistentEmptyCaptures:
  var count = 0

final class Increment43PersistentGeneratedEmpty extends Module:
  hdlRange(-3, -3): _ =>
    Increment43PersistentEmptyCaptures.count += 1
    analogProcedure:
      val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(10.5.real))
      cells.writable.at(0) := cells.at(1)

final class Increment43PersistentGeneratedChild extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  hdlRange(0, 2): _ =>
    analogProcedure:
      val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(0.75.real))
      cells.writable.at(0) := cells.at(1)
  analog:
    I(p, n) <+ V(p, n) / 1.kOhm

final class Increment43PersistentGeneratedReusable extends Module:
  val taps = inout(Electrical, 2)
  val common = inout(Electrical)
  hdlRange(0, 2): i =>
    val element = instance(new Increment43PersistentGeneratedChild)
    connect(taps.at(i), element(_.p))
    connect(common, element(_.n))
    analogProcedure:
      val parentCells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(0.875.real))
      parentCells.writable.at(0) := parentCells.at(1)

final class Increment43PersistentOrdinaryAndGenerated extends Module:
  val ordinary = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(11.5.real))
  analogProcedure:
    ordinary.writable.at(0) := ordinary.at(1)
  hdlRange(0, 2): _ =>
    analogProcedure:
      val generated = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(12.5.real))
      generated.writable.at(0) := generated.at(1)

final class Increment43PersistentGeneratedAndOrdinary extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val generated = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(13.5.real))
      generated.writable.at(0) := generated.at(1)
  val ordinary = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(14.5.real))
  analogProcedure:
    ordinary.writable.at(0) := ordinary.at(1)

final class Increment43PersistentDuplicateProcedure(generated: Boolean) extends Module:
  def bodies(): Unit =
    analogProcedure:
      val first = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.real))
      first.writable.at(0) := 2.0.real
    analogProcedure:
      val second = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(3.0.real))
      second.writable.at(0) := 4.0.real
  if generated then hdlRange(0, 2)(_ => bodies())
  else bodies()

final class Increment43PersistentOrdinaryCapture extends Module:
  val ordinary = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.real))
  hdlRange(0, 2): _ =>
    analogProcedure:
      val generated = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(2.0.real))
      generated.writable := ordinary

final class Increment43PersistentGeneratedFailure(form: Int) extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  var escaped: Option[Variable[Vec[Real]]] = None
  hdlRange(0, if form == 2 then 0 else 2): _ =>
    analogProcedure:
      if form == 0 then
        val source = variable(Real, 1.0.real)
        val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(source))
        cells.writable.at(0) := 2.0.real
      else if form == 1 then
        val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(V(p, n)))
        cells.writable.at(0) := 2.0.V
      else if form == 2 then
        val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.V))
        cells.writable.at(0) := 2.0.real
      else
        val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.real))
        escaped = Some(cells)
        cells.writable.at(0) := 2.0.real
  if form == 3 then
    analogProcedure:
      escaped.get.writable.at(0) := 3.0.real

object Increment43GeneratedPersistentArrayTests extends TestSuite:
  val models: Vector[() => Module] = Vector(
    () => new Increment43WritableGeneratedRootFill,
    () => new Increment43PersistentGeneratedSymbolic,
    () => new Increment43PersistentGeneratedMixed,
    () => new Increment43PersistentGeneratedNested,
    () => new Increment43PersistentGeneratedSiblings,
    () => new Increment43PersistentGeneratedSingleton,
    () => new Increment43PersistentGeneratedEmpty,
    () => new Increment43PersistentGeneratedReusable,
    () => new Increment43PersistentOrdinaryAndGenerated,
    () => new Increment43PersistentGeneratedAndOrdinary
  )

  private def run(
      document: NodalMlirDocument,
      tool: String,
      arguments: Vector[String],
      directory: Path
  ): String =
    NativeCompilerClient.run(
      document,
      NativeCompilerRequest(Path.of(tool), arguments, directory, timeout = Duration.ofSeconds(90))
    ) match
      case value: NativeCompilerSuccess => value.normalizedMlir
      case failure: NativeCompilerFailure =>
        throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")

  private def assertTarget(
      snapshot: ConstructionSnapshot,
      source: NodalMlirDocument,
      output: String
  ): Unit =
    val regions = snapshot.generatedRegions.map(region => region.path -> region).toMap
    snapshot.generatedRegions.foreach: region =>
      val generate = source.text.linesIterator.find(line =>
        line.contains("\"nodal.generate\"") && line.contains(s"region_id = \"${region.path}\"")
      ).get
      val induction = "induction = \"([^\"]+)\"".r.findFirstMatchIn(generate).get.group(1)
      val upper = if region.upperExclusive.toLongOption.nonEmpty then region.upperExclusive
      else if snapshot.parameterExpressions.exists(_.path == region.upperExclusive) then
        "(count + 1)"
      else region.upperExclusive.split('.').last
      val clause = s"for ($induction = ${region.lower}; $induction < $upper; " +
        s"$induction = $induction + ${region.step}) begin : ${region.path.split('.').last}"
      assert(output.linesIterator.count(_.trim == clause) == 1)
      assert(output.contains(s"\"source_scope\":\"${region.path}\""))
    def name(path: String): String = "event_" + path.replace('.', '_')
    def initial(path: String): String = snapshot.root match
      case "Increment43WritableGeneratedRootFill" => "4.5"
      case "Increment43PersistentGeneratedSymbolic" => "level"
      case "Increment43PersistentGeneratedMixed" => "0.5"
      case "Increment43PersistentGeneratedNested" =>
        if path.endsWith(".outer") then "5.5" else "6.5"
      case "Increment43PersistentGeneratedSiblings" =>
        if path.endsWith(".left") then "7.5" else "8.5"
      case "Increment43PersistentGeneratedSingleton" => "9.5"
      case "Increment43PersistentGeneratedEmpty" => "10.5"
      case "Increment43PersistentGeneratedReusable" =>
        if path.endsWith(".parentCells") then "0.875" else "0.75"
      case "Increment43PersistentOrdinaryAndGenerated" =>
        if path.endsWith(".ordinary") then "11.5" else "12.5"
      case "Increment43PersistentGeneratedAndOrdinary" =>
        if path.endsWith(".ordinary") then "14.5" else "13.5"
    snapshot.analogProcedural.foreach: program =>
      val root = program.variables.head
      val target = name(root.authoredPath.get)
      val extent = if snapshot.root == "Increment43PersistentGeneratedSymbolic" then "(lanes * 2)"
      else if snapshot.root == "Increment43PersistentGeneratedSingleton" then "1" else "2"
      val range = if extent == "1" then "0:0"
      else if extent == "2" then "0:1"
      else s"0:($extent - 1)"
      val declaration = s"real $target[$range] = '{$extent{${initial(root.authoredPath.get)}}};"
      assert(output.linesIterator.count(_.trim == declaration) == 1)
      val offset = output.indexOf(declaration)
      program.generatedOwner.foreach: owner =>
        assert(regions.contains(owner))
        val scopeOffset = output.indexOf(s"\"source_scope\":\"$owner\"")
        assert(scopeOffset >= 0, offset > scopeOffset)
        assert(offset < output.indexOf("analog begin", scopeOffset))
      assert(output.linesIterator.count(_.trim.startsWith(s"$target[")) == 1)
      assert(!output.contains(name(root.variable.identity) + "_initializer"))
      program.variables.drop(1).foreach: local =>
        val copied = name(local.authoredPath.get)
        assert(output.contains(s"real $copied[$range];"))
        assert(output.linesIterator.count(_.trim.startsWith(s"$copied[")) == 2)
        val event = output.indexOf("@(initial_step)")
        val localCopy = output.linesIterator.find(_.trim.startsWith(s"$copied[")).get.trim
        val copyOffset = output.indexOf(localCopy)
        assert(event >= 0, copyOffset > event)
        assert(output.substring(event, copyOffset).contains(s" = $target["))
    val expectedEvents =
      if Set(
          "Increment43PersistentGeneratedSymbolic",
          "Increment43PersistentGeneratedMixed"
        ).contains(snapshot.root)
      then 1
      else 0
    assert(output.sliding("initial_step".length).count(_ == "initial_step") == expectedEvents)
    assert(!output.contains("analog initial"), output.length < 50000)
    if snapshot.root == "Increment43PersistentGeneratedSymbolic" then
      assert(output.contains("parameter integer count = 3 from [1:5];"))
      assert(output.contains("parameter integer lanes = 2 from [2:4];"))
      assert(output.contains("parameter real level = 1.25;"))
      assert(!output.contains("(lanes * 3)"))
    if snapshot.root == "Increment43PersistentGeneratedMixed" ||
      snapshot.root == "Increment43PersistentGeneratedReusable"
    then
      assert(output.contains("element element_instance(.n(common), .p(taps[node_map_taps_0_0]));"))
      assert(output.linesIterator.count(_.startsWith("module element(")) == 1)

  val tests: Tests = Tests:
    test("generated persistent roots retain actual scope static units and entry copy sources"):
      models.foreach: model =>
        val snapshot = ConstructionKernel.inspect(model())
        val regions = snapshot.generatedRegions.map(region => region.path -> region).toMap
        val programs = snapshot.analogProcedural
        assert(programs.nonEmpty)
        programs.foreach: program =>
          program.generatedOwner.foreach: owner =>
            val region = regions(owner)
            assert(program.generatedInduction.contains(region.induction))
          val root = program.variables.head
          assert(root.initializer.nonEmpty, root.initializer.get.allReads.isEmpty)
          assert(root.variable.valueType.dimensions.nonEmpty)
          assert(root.authoredPath.nonEmpty, root.source.exists(_.line > 0))
        val first = ScalaToMlirBridge.fromSnapshot(snapshot).text
        val second = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(model())).text
        assert(first == second)
        assert(first.contains("nodal.analog_array_fill"))
        if snapshot.root == "Increment43PersistentGeneratedSymbolic" then
          val program = programs.head
          val root = program.variables.head
          assert(root.variable.valueType.dimensions == Vector("lanes", "2"))
          assert(root.variable.valueType.dimension == "voltage")
          assert(root.initializer.get.source.exists(
            _.isInstanceOf[AnalogProceduralRuntime.ShapedSource.View]
          ))
          assert(program.variables.last.initializer.get.allReads.nonEmpty)
          assert(first.contains("nodal.shape_slice"))
        if snapshot.root != "Increment43PersistentGeneratedMixed" &&
          snapshot.root != "Increment43PersistentGeneratedReusable"
        then assert(!first.contains("^bb0("))

    test("empty capture and sibling storage keep distinct declared ownership"):
      Increment43PersistentEmptyCaptures.count = 0
      val empty = ConstructionKernel.inspect(new Increment43PersistentGeneratedEmpty)
      assert(Increment43PersistentEmptyCaptures.count == 1)
      assert(empty.generatedRegions.head.maximumTripCount == 0)
      assert(empty.analogProcedural.head.variables.head.initializer.nonEmpty)
      for model <- Vector[() => Module](
          () => new Increment43PersistentGeneratedNested,
          () => new Increment43PersistentGeneratedSiblings
        )
      do
        val snapshot = ConstructionKernel.inspect(model())
        val programs = snapshot.analogProcedural
        assert(programs.size == 2)
        assert(programs.map(_.generatedOwner).distinct.size == 2)
        assert(programs.flatMap(_.variables.map(_.variable.identity)).distinct.size == 2)
        assert(programs.flatMap(_.variables.flatMap(_.authoredPath)).distinct.size == 2)
        assert(programs.last.identityPrefix == programs.last.generatedOwner)
        val forged = snapshot.copy(analogProcedural =
          programs.updated(
            1,
            programs(1).copy(identityPrefix = Some(snapshot.root + ".forged"))
          )
        )
        val rejected = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
        assert(rejected.getMessage.contains("procedural identity prefix disagrees"))
      for model <- Vector[() => Module](
          () => new Increment43PersistentOrdinaryAndGenerated,
          () => new Increment43PersistentGeneratedAndOrdinary
        )
      do
        val snapshot = ConstructionKernel.inspect(model())
        val programs = snapshot.analogProcedural
        assert(programs.size == 2, programs.count(_.generatedOwner.isEmpty) == 1)
        assert(programs.flatMap(_.variables.map(_.variable.identity)).distinct.size == 2)
        assert(programs.flatMap(_.assignments.map(_.identity)).distinct.size == 2)
        assert(programs.last.identityPrefix.nonEmpty)

      val scoped = ConstructionKernel.inspect(new Increment43PersistentScopedControlsParent)
      val programs = scoped.analogProcedural
      val owner = scoped.root + ".controls"
      val expressions = programs.flatMap(_.controlExpressions)
      assert(programs.size == 2, programs.forall(_.owner == owner))
      assert(programs.map(_.controlFlow.get.root.identity).distinct.size == 2)
      assert(expressions.nonEmpty, expressions.map(_.identity).distinct.size == expressions.size)
      assert(programs.forall(_.variables.forall(_.variable.owner == owner)))
      assert(programs.last.identityPrefix == programs.last.generatedOwner)
      val reconstructed = ScalaToMlirBridge.fromSnapshot(scoped).text
      val identities = programs.flatMap(program =>
        program.variables.map(_.variable.identity) ++ program.controlExpressions.map(_.identity)
      )
      assert(identities.forall: path =>
        reconstructed.sliding(s"semantic_path = \"$path\"".length).count(
          _ == s"semantic_path = \"$path\""
        ) >= 2)
      assert(reconstructed == ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new Increment43PersistentScopedControlsParent)
      ).text)

    test("generated initializers preserve mutable unit scope and duplicate procedure rejection"):
      val failures = Vector(0, 2, 3).map: form =>
        scala.util.Try(ConstructionKernel.inspect(new Increment43PersistentGeneratedFailure(form)))
          .failed.get.getMessage
      assert(failures(0).contains("static fill without storage reads"))
      assert(failures(1).contains("NODAL-ANALOG-033-013"))
      assert(failures(2).contains("NODAL-ANALOG-033-010"))
      assert(failures(2).contains("another generated or ordinary procedure scope"))
      for generated <- Vector(false, true) do
        val repeated = scala.util.Try(ConstructionKernel.inspect(
          new Increment43PersistentDuplicateProcedure(generated)
        )).failed.get.getMessage
        assert(repeated.contains("NODAL-ANALOG-033-020"))
      val ordinary = scala.util.Try(ConstructionKernel.inspect(
        new Increment43PersistentOrdinaryCapture
      )).failed.get.getMessage
      assert(ordinary.contains("NODAL-ANALOG-033-010"))

    test("generated persistent probe fill rejects at the native static initializer boundary"):
      val compiler = sys.env.getOrElse(
        "NODAL_NODALC",
        throw new java.lang.AssertionError("configured nodalc is required")
      )
      val source = ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new Increment43PersistentGeneratedFailure(1)),
        Backend.VerilogA
      )
      val directory = Path.of(".work", "generated-persistent-negative").toAbsolutePath
      Files.createDirectories(directory)
      Files.writeString(directory.resolve("probe-initializer.source.mlir"), source.text)
      NativeCompilerClient.run(
        source,
        NativeCompilerRequest(Path.of(compiler), Vector.empty, directory)
      ) match
        case failure: NativeCompilerFailure =>
          assert(failure.standardError.contains("NODAL-ANALOG-037-009"))
          assert(
            failure.standardError.contains("persistent shaped initialization requires static fill")
          )
        case _ =>
          throw new java.lang.AssertionError("live probe root initializer unexpectedly accepted")

    test("generated persistent arrays emit per occurrence declarations in VA and AMS"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = Path.of(".work", "generated-persistent-public").toAbsolutePath
          Files.createDirectories(directory)
          models.foreach: model =>
            val snapshot = ConstructionKernel.inspect(model())
            Vector(Backend.VerilogA -> "va", Backend.VerilogAMS -> "vams").foreach:
              (backend, extension) =>
                val source = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
                val stem = s"${snapshot.root}.$extension"
                Files.writeString(directory.resolve(stem + ".source.mlir"), source.text)
                val normalized = run(
                  source,
                  nodalc,
                  Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                  directory
                )
                Files.writeString(directory.resolve(stem + ".normalized.mlir"), normalized)
                val flag = if backend == Backend.VerilogA then "--nodal-to-verilog-a"
                else "--nodal-to-verilog-ams"
                val output =
                  run(source.copy(text = normalized), translator, Vector(flag), directory)
                assert(output ==
                  run(source.copy(text = normalized), translator, Vector(flag), directory))
                Files.writeString(directory.resolve(stem), output)
                assertTarget(snapshot, source, output)
        case _ =>
          throw new java.lang.AssertionError("configured nodalc and translator are required")

final class Increment43PersistentScopedControls extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val first = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.real))
      analogConditional:
        analogWhen(first.at(0) > 0.0.real):
          first.writable.at(1) := first.at(0)
  hdlRange(0, 2): _ =>
    analogProcedure:
      val second = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(2.0.real))
      analogConditional:
        analogWhen(second.at(0) > 0.0.real):
          second.writable.at(1) := second.at(0)

final class Increment43PersistentScopedControlsParent extends Module:
  val controls = instance(new Increment43PersistentScopedControls)
