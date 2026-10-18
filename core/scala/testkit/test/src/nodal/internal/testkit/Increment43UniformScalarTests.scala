package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class Increment43SelectedScalarInitializers extends Module:
  val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(2.0.V))
  val offset = variable(Real, 0.5.V)
  val sink = variable(Real, 0.0.V)
  analogProcedure:
    on(initialStep):
      val sum = variable(Real, cells.at(0) + cells.at(1) + offset)
      val single = variable(Real, cells.at(0) + 1.0.V)
      val comparison = variable(Bool, cells.at(0) > cells.at(1))
      analogConditional:
        analogWhen(comparison):
          val branch = variable(Real, cells.at(1) + offset)
          sink := branch
        analogOtherwise:
          sink := sum + single

final class Increment43GeneratedScalarInitializer extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      on(initialStep):
        val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(2.0.V))
        val sum = variable(Real, cells.at(0) + cells.at(1) + 1.0.V)
        cells.writable.at(0) := sum

final class Increment43SharedUniformScalar extends Module:
  val scalar = variable(Real, 2.0.real)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    on(initialStep):
      val filled = Vec.fill(Vec(Real, 3))(scalar + 1.0.real)
      val reused = filled.at(0) + filled.at(2)
      val first = variable(Real, reused)
      scalar := 7.0.real
      sink := reused
      val equalLeft = Vec.fill(Vec(Real, 3))(scalar + 1.0.real)
      val equalRight = Vec.fill(Vec(Real, 3))(scalar + 1.0.real)
      val independent = variable(Real, equalLeft.at(0) + equalRight.at(0))
      sink := first + independent

final class Increment43LiveUniformScalar extends Module:
  val rows = param(2.integer, range = 2 to 4)
  val lane = param(0.integer, range = 0 to 1)
  val scalar = variable(Real, 1.0.real)
  val flag = variable(Bool, true.B)
  val iterations = variable(Integer, 2.integer)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    val selected = Vec.fill(Vec(Real, rows, 3))(scalar + 1.0.real)
      .slice(1, 3, axis = 1).row(lane).flatten.at(1)
    analogConditional:
      analogWhen(flag && (AnalogMath.abs(selected) > 0.0.real)):
        sink := 1.0.real
      analogElseWhen(selected < 0.0.real):
        sink := 2.0.real
    scalar := -4.0.real
    analogCase(selected > 0.0.real):
      analogCaseArm(true):
        sink := 3.0.real
      analogCaseArm(false):
        sink := 4.0.real
    analogLoop(iterations, maximumIterations = 3, minimumIterations = 1):
      scalar := scalar + 1.0.real
      analogConditional:
        analogWhen(selected > 0.0.real):
          analogBreak()
        analogOtherwise:
          analogContinue()

final class Increment43UniformScalarEvents extends Module:
  val signal = variable(Real, 1.0.V)
  val timing = variable(Real, 1.0.ns)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    val voltage = Vec.fill(Vec(Real, 2))(signal + 0.5.V)
    val timeTolerance = Vec.fill(Vec(Real, 2))(timing)
    val edge = cross(voltage.at(0), Edge.Rising, timeTolerance.at(1), 0.01.V, 1.integer)
    val level = Vec.fill(Vec(Real, 2))(signal)
    on(edge or above(level.at(1), 1.0.ns, 0.01.V, 1.integer)):
      sink := 1.0.real
    signal := 2.0.V
    on(edge):
      sink := 2.0.real
    val start = Vec.fill(Vec(Real, 2))(timing)
    val period = Vec.fill(Vec(Real, 2))(timing + 1.0.ns)
    val tolerance = Vec.fill(Vec(Real, 2))(0.1.ns)
    on(timer(start.at(0), period.at(1), tolerance.at(0), 1.integer)):
      sink := 3.0.real

final class Increment43StaticUniformScalar extends Module:
  val level = param(2.5.V)
  val selected = Vec.fill(Vec(Real, 2, 2))(level + 1.0.V).row(1).flatten.at(0)
  val cached = variable(Real, selected)
  val sink = variable(Real, 0.0.V)
  analogProcedure:
    sink := cached

final class Increment43RepeatedLiveUniformScalar(form: Int) extends Module:
  val signal = variable(Real, 1.0.V)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    val filled = Vec.fill(Vec(Real, 2))(signal)
    val selected = filled.at(0)
    if form == 0 then
      analogConditional:
        analogWhen(selected > selected):
          sink := 1.0.real
    else if form == 1 then
      analogConditional:
        analogWhen(filled.at(0) > filled.at(1)):
          sink := 1.0.real
    else if form == 2 then
      on(cross(selected, Edge.Rising, 1.0.ns, filled.at(1))):
        sink := 1.0.real
    else if form == 3 then
      on(cross(selected) or above(filled.at(1))):
        sink := 1.0.real
    else
      val times = Vec.fill(Vec(Real, 2))(1.0.ns)
      on(timer(times.at(0), times.at(1))):
        sink := 1.0.real

final class Increment43SharedLiveReuse extends Module:
  val signal = variable(Real, 1.0.V)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    val shared = Vec.fill(Vec(Real, 2))(signal + 0.5.V)
    val equalLeft = Vec.fill(Vec(Real, 2))(signal + 0.5.V)
    val equalRight = Vec.fill(Vec(Real, 2))(signal + 0.5.V)
    val event = cross(shared.at(0) + equalLeft.at(0)) or
      above(shared.at(1) + equalRight.at(1))
    on(event):
      sink := 1.0.real
    signal := 2.0.V
    on(event):
      sink := 2.0.real

final class Increment43LazySharedControl extends Module:
  val denominator = variable(Real, 0.0.real)
  val flag = variable(Bool, false.B)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    val shared = Vec.fill(Vec(Real, 2))(1.0.real / denominator)
    analogConditional:
      analogWhen(flag && (shared.at(0) > shared.at(1))):
        sink := 1.0.real
      analogElseWhen(!flag && (shared.at(0) < shared.at(1))):
        sink := 2.0.real
    analogCase(shared.at(0) > shared.at(1)):
      analogCaseArm(true):
        sink := 3.0.real
      analogCaseArm(false):
        sink := 4.0.real

final class Increment43GeneratedSharedLive extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val signal = variable(Real, 1.0.V)
      val shared = Vec.fill(Vec(Real, 2))(signal + 0.5.V)
      on(cross(shared.at(0)) or above(shared.at(1))):
        signal := signal + 1.0.V

final class Increment43LiveDiamond(levels: Int, consumers: Int = 1) extends Module:
  val signal = variable(Real, 1.0.V)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    var value: Expr[Real] = signal
    for _ <- 0 until levels do
      val fill = Vec.fill(Vec(Real, 2))(value)
      value = fill.at(0) + fill.at(1)
    val event = cross(value) or above(value)
    for _ <- 0 until consumers do
      on(event):
        sink := 1.0.real

final class Increment43MixedSharedLive extends Module:
  val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.V))
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    val shared = Vec.fill(Vec(Real, 2))(cells.at(0) + 0.5.V)
    on(cross(shared.at(0) + cells.at(1)) or above(shared.at(1))):
      sink := 1.0.real

final class Increment43SharedLiveMissingLane extends Module:
  analogProcedure:
    on(initialStep):
      val cells = variable(Vec(Real, 2))
      cells.writable.at(0) := 1.0.real
      val fill = Vec.fill(Vec(Real, 2))(cells.at(1))
      analogConditional:
        analogWhen(fill.at(0) > fill.at(1)):
          cells.writable.at(0) := 2.0.real

final class Increment43ScalarInitializerMissingLane extends Module:
  analogProcedure:
    on(initialStep):
      val cells = variable(Vec(Real, 2))
      cells.writable.at(0) := 1.0.real
      val result = variable(Real, cells.at(0) + cells.at(1) + 1.0.real)
      result := 2.0.real

final class Increment43MutableUniformScalarRoot extends Module:
  val input = variable(Real, 1.0.real)
  val cached = variable(Real, Vec.fill(Vec(Real, 2))(input).at(0))
  analogProcedure:
    cached := 2.0.real

final class Increment43WrappedScalarEffect(function: Boolean, consumer: Int) extends Module:
  val identitySignal = AnalogFunction("identitySignal", Real): f =>
    f.input("signal", Real)
  val sink = variable(Real, 0.0.real)
  var retained: Option[Expr[Real]] = None
  analog:
    retained = Some(if function then identitySignal(1.0.real) else ddt(1.0.ns))
  analogProcedure:
    val selected = Vec.fill(Vec(Real, 2))(retained.get).at(0)
    if consumer == 0 then
      on(initialStep):
        val local = variable(Real, selected)
        local := 1.0.real
    else if consumer == 1 then
      on(initialStep):
        sink := selected
    else if consumer == 2 then
      analogConditional:
        analogWhen(selected > 0.0.real):
          sink := 1.0.real
    else
      on(cross(selected)):
        sink := 1.0.real

object Increment43UniformScalarTests extends TestSuite:
  import AnalogProceduralRuntime.{ShapedSource, Value, VariableRecord}

  private def document(top: => Module): NodalMlirDocument =
    ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(top))

  private def failure(top: => Module): String =
    scala.util.Try(document(top)).failed.get.getMessage

  private def variable(snapshot: ConstructionSnapshot, name: String): VariableRecord =
    snapshot.analogProcedural.flatMap(_.variables)
      .find(_.authoredPath.exists(_.endsWith(s".$name"))).get

  private def operations(text: String, name: String): Vector[String] =
    text.linesIterator.filter(_.contains(s"\"nodal.$name\"(")).toVector

  private def declaration(text: String, record: VariableRecord): String =
    operations(text, "analog_variable")
      .find(_.contains(s"identity = \"${record.variable.identity}\"")).get

  private def operands(declaration: String): Int =
    val values = "\"nodal.analog_variable\"\\(([^)]*)\\)".r
      .findFirstMatchIn(declaration).get.group(1).trim
    if values.isEmpty then 0 else values.split(",").length

  private def fills(value: Value): Vector[Value] =
    value.source.toVector.flatMap {
      case ShapedSource.Fill(scalar) => Vector(value) ++ fills(scalar)
      case ShapedSource.View(input, _) => fills(input)
      case _: ShapedSource.Read => Vector.empty
    } ++ value.scalarSources.flatMap(fills)

  private def targetName(record: VariableRecord): String =
    "event_" + record.authoredPath.getOrElse(record.variable.identity).map(character =>
      if character.isLetterOrDigit || character == '_' then
        character
      else '_'
    )

  private def native(body: (Path, Path, Path) => Unit): Unit =
    (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
      case (Some(compiler), Some(translator)) =>
        val directory = Files.createTempDirectory("nodal-uniform-scalar-")
        try body(Path.of(compiler), Path.of(translator), directory)
        finally
          val files = Files.walk(directory)
          try files.sorted(java.util.Comparator.reverseOrder()).forEach(path => {
              val _ = Files.deleteIfExists(path)
            })
          finally files.close()
      case _ => ()

  private def run(
      source: NodalMlirDocument,
      executable: Path,
      directory: Path,
      arguments: Vector[String]
  ): String =
    NativeCompilerClient.run(
      source,
      NativeCompilerRequest(
        executable = executable,
        arguments = arguments,
        workingDirectory = directory,
        timeout = Duration.ofSeconds(60)
      )
    ) match
      case result: NativeCompilerSuccess => result.normalizedMlir
      case result: NativeCompilerFailure =>
        throw new java.lang.AssertionError(s"${result.diagnostic}\n${result.standardError}")

  private def targets(
      snapshot: ConstructionSnapshot,
      compiler: Path,
      translator: Path,
      directory: Path
  )(check: String => Unit): Unit =
    for (backend, argument) <- Vector(
        Backend.VerilogA -> "--nodal-to-verilog-a",
        Backend.VerilogAMS -> "--nodal-to-verilog-ams"
      )
    do
      val source = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
      val normalized = run(
        source,
        compiler,
        directory,
        Vector("--pass-pipeline=builtin.module(nodal-gate-default)")
      )
      val verified = source.copy(text = normalized)
      val emitted = run(verified, translator, directory, Vector(argument))
      assert(emitted == run(verified, translator, directory, Vector(argument)))
      check(emitted)

  val tests: Tests = Tests:
    test("selected scalar initializers capture complete expressions at lexical scope entry"):
      val snapshot = ConstructionKernel.inspect(new Increment43SelectedScalarInitializers)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text == document(new Increment43SelectedScalarInitializers).text)
      for (name, count, kind, units) <- Vector(
          ("sum", 3, "real", "voltage"),
          ("single", 1, "real", "voltage"),
          ("comparison", 2, "boolean", "1"),
          ("branch", 2, "real", "voltage")
        )
      do
        val record = variable(snapshot, name)
        val line = declaration(text, record)
        assert(record.initializer.get.allReads.distinct.size == count)
        assert(record.variable.declarationScope.size > 1)
        assert(line.contains("initializer_form = \"expression\""), operands(line) == count)
        assert(line.contains(s"initializer_kind = \"$kind\""))
        assert(line.contains(s"initializer_dimension = \"$units\""))
        assert(line.contains("initializer_reads = []"))
      val generated = ConstructionKernel.inspect(new Increment43GeneratedScalarInitializer)
      assert(generated.analogProcedural.head.generatedOwner.nonEmpty)
      assert(declaration(ScalaToMlirBridge.fromSnapshot(generated).text, variable(generated, "sum"))
        .contains("initializer_form = \"expression\""))
      native: (compiler, translator, directory) =>
        targets(snapshot, compiler, translator, directory): emitted =>
          for (name, operator) <-
              Vector("sum" -> " + ", "single" -> " + 1.0", "comparison" -> " > ")
          do
            val destination = targetName(variable(snapshot, name))
            val writes =
              emitted.linesIterator.map(_.trim).filter(_.startsWith(s"$destination = ")).toVector
            assert(writes.size == 1, writes.head.contains(operator))
            assert(emitted.indexOf("@(initial_step)") < emitted.indexOf(writes.head))
          val branch = targetName(variable(snapshot, "branch"))
          val condition = targetName(variable(snapshot, "comparison"))
          assert(emitted.indexOf(s"if ($condition)") < emitted.indexOf(s"$branch = "))
        targets(generated, compiler, translator, directory): emitted =>
          assert(emitted.contains("generate"), emitted.contains("genvar"))
          assert(emitted.contains("initial_step"), emitted.contains(" + 1.0)"))

    test("statement consumers share actual fill identity and refresh after mutation"):
      val snapshot = ConstructionKernel.inspect(new Increment43SharedUniformScalar)
      val first = variable(snapshot, "first").initializer.get
      val independent = variable(snapshot, "independent").initializer.get
      assert(first.scalarSources.size == 2, independent.scalarSources.size == 2)
      assert(fills(first).map(_.rendered).distinct.size == 1)
      assert(fills(independent).map(_.rendered).distinct.size == 2)
      assert(fills(independent).flatMap(_.source.collect { case ShapedSource.Fill(value) =>
        value.rendered
      })
        .distinct.size == 1)
      val reused = snapshot.analogProcedural.head.controlExpressions
        .filter(_.role == "assignment-value").map(_.value)
        .find(_.scalarSources.size == 2).get
      assert(fills(reused).map(_.rendered).distinct == fills(first).map(_.rendered).distinct)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      val producers = operations(text, "analog_array_fill")
      assert(producers.size == 4)
      val mutation = operations(text, "analog_assign").find(_.contains("value = \"7.0\"")).get
      assert(text.indexOf(producers.head) < text.indexOf(mutation))
      assert(text.indexOf(mutation) < text.indexOf(producers(1)))
      native: (compiler, translator, directory) =>
        targets(snapshot, compiler, translator, directory): emitted =>
          val evaluations =
            emitted.linesIterator.map(_.trim).filter(_.endsWith(" + 1.0);")).toVector
          assert(evaluations.size == 4)
          val write = s"${targetName(variable(snapshot, "scalar"))} = 7.0;"
          assert(emitted.indexOf(evaluations.head) < emitted.indexOf(write))
          assert(emitted.indexOf(write) < emitted.indexOf(evaluations(1)))
          assert(evaluations.map(_.takeWhile(_ != '=')).distinct.size == 4)

    test(
      "live uniform leaves preserve arithmetic short circuit symbolic views and loop reevaluation"
    ):
      val snapshot = ConstructionKernel.inspect(new Increment43LiveUniformScalar)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text == document(new Increment43LiveUniformScalar).text)
      assert(operations(text, "analog_array_fill").isEmpty)
      val reads = operations(text, "analog_variable_read")
      assert(reads.size == 1, reads.forall(_.contains("Increment43LiveUniformScalar.statement_")))
      assert(operations(text, "shape_index").isEmpty, operations(text, "shape_slice").isEmpty)
      assert(operations(text, "analog_if").size == 2, operations(text, "analog_case").size == 1)
      val provenance = AnalogProceduralMlir.sourceMapEntries(snapshot)
      assert(provenance.exists(_.contains(".condition_0.source")))
      native: (compiler, translator, directory) =>
        targets(snapshot, compiler, translator, directory): emitted =>
          val scalar = targetName(variable(snapshot, "scalar"))
          val conditions = emitted.linesIterator.map(_.trim).filter(_.startsWith("if (")).toVector
          assert(conditions.exists(line =>
            line.contains("&&") && line.contains("abs(") && line.contains(scalar)
          ))
          assert(emitted.contains("else if ("), emitted.contains("case ("))
          assert(emitted.contains("_limit ="), emitted.contains("_break = 1;"))
          assert(emitted.contains("_continue = 1;"), !emitted.contains("read_snapshot"))
          val predicate =
            conditions.find(line => line.contains(scalar) && !line.contains("abs(")).get
          val mutation = emitted.linesIterator.map(_.trim)
            .find(line => line.startsWith(s"$scalar = ") && line.contains(" + 1.0)")).get
          assert(emitted.indexOf(mutation) < emitted.indexOf(predicate))
          assert(!emitted.contains("_scalar_source_"), !emitted.contains("_copy_index"))
        val mixed = ConstructionKernel.inspect(new Increment43StorageReferenceFillFailure(true, 0))
        targets(mixed, compiler, translator, directory): emitted =>
          val cells = targetName(variable(mixed, "cells"))
          assert(emitted.linesIterator.exists(line =>
            line.trim.startsWith("if (") && line.contains(s"$cells[") && line.contains(" * ")
          ))
          assert(!emitted.contains("_scalar_source_"))

    test("cross timer and distinct OR fills remain at independent live monitor sites"):
      val snapshot = ConstructionKernel.inspect(new Increment43UniformScalarEvents)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(operations(text, "analog_cross").size == 2)
      assert(operations(text, "analog_above").size == 1, operations(text, "analog_timer").size == 1)
      assert(operations(text, "analog_event_or").size == 1)
      assert(operations(text, "analog_array_fill").isEmpty)
      assert(operations(text, "analog_variable_read").isEmpty)
      native: (compiler, translator, directory) =>
        targets(snapshot, compiler, translator, directory): emitted =>
          val monitors = emitted.linesIterator.map(_.trim).filter(_.startsWith("@(")).toVector
          val signal = targetName(variable(snapshot, "signal"))
          val timing = targetName(variable(snapshot, "timing"))
          assert(monitors.size == 3)
          assert(monitors.head.contains("cross("), monitors.head.contains(" or above("))
          assert(monitors.head.contains(signal), monitors.head.contains(timing))
          assert(monitors(1).contains(signal), !monitors(1).contains(" or "))
          assert(monitors(2).contains("timer("), monitors(2).contains(timing))
          assert(emitted.indexOf(s"$signal = 2.0;") < emitted.indexOf(monitors(1)))
          assert(!emitted.contains("_scalar_source_"), !emitted.contains("_copy_index"))

    test("static uniform scalar roots retain zero capture declaration lifetime"):
      val snapshot = ConstructionKernel.inspect(new Increment43StaticUniformScalar)
      val root = variable(snapshot, "cached")
      assert(root.variable.declarationScope.size <= 1)
      assert(root.initializer.get.allReads.isEmpty, fills(root.initializer.get).size == 1)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      val line = declaration(text, root)
      assert(operands(line) == 0, !line.contains("initializer_form"))
      assert(line.contains("initializer_dimension = \"voltage\""))
      assert(operations(text, "analog_array_fill").isEmpty)
      val reads = operations(text, "analog_variable_read")
      assert(
        reads.size == 1,
        reads.head.contains("Increment43StaticUniformScalar.statement_0.read_0")
      )
      native: (compiler, translator, directory) =>
        targets(snapshot, compiler, translator, directory): emitted =>
          val name = targetName(root)
          val declaration = s"real $name = (level + 1.0);"
          assert(emitted.contains(declaration))
          assert(emitted.indexOf(declaration) < emitted.indexOf("analog begin"))
          val body = emitted.substring(emitted.indexOf("analog begin"))
          assert(!body.contains(s"$name = "), !emitted.contains("initial_step"))
          assert(!emitted.contains("_initializer"), !emitted.contains("_copy_index"))

    test("live shared fills retain one deferred identity across tokens projections slots and OR"):
      for form <- 0 to 4 do
        val captured = ConstructionKernel.inspect(new Increment43RepeatedLiveUniformScalar(form))
        val text = ScalaToMlirBridge.fromSnapshot(captured).text
        val fills = operations(text, "analog_live_fill")
        assert(fills.size == 1, text.contains("reference_form = \"live\""))
        assert(operations(text, "analog_array_fill").isEmpty)
        assert(fills.head.contains("!nodal.analog_deferred<\"real\","))
        assert(!fills.head.contains("reads ="), !fills.head.contains("reference_form"))
        if form == 3 then assert(operations(text, "analog_event_or").size == 1)
        native: (compiler, translator, directory) =>
          targets(captured, compiler, translator, directory): emitted =>
            if form >= 2 then
              val monitors = emitted.linesIterator.filter(_.trim.startsWith("@(")).toVector
              assert(monitors.size == 1, monitors.head.contains("event_live_store("))
              assert(emitted.contains("event_live_ready_0 = 0;"))
              assert(emitted.indexOf("event_live_ready_0 = 0;") < emitted.indexOf(monitors.head))
              if form == 3 then assert(monitors.head.contains(" or "))
            else assert(emitted.contains("case ("))

    test("complete OR consumers preserve distinct equal text fills and event reuse resets"):
      val captured = ConstructionKernel.inspect(new Increment43SharedLiveReuse)
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      val fills = operations(text, "analog_live_fill")
      assert(fills.size == 6, fills.distinct.size == 6)
      assert(operations(text, "analog_event_or").size == 2)
      assert(operations(text, "analog_variable_read").isEmpty)
      val bodies = "value = \"([^\"]+)\"".r
      assert(fills.flatMap(line => bodies.findFirstMatchIn(line).map(_.group(1))).distinct.size ==
        1)
      native: (compiler, translator, directory) =>
        targets(captured, compiler, translator, directory): emitted =>
          val monitors = emitted.linesIterator.map(_.trim).filter(_.startsWith("@(")).toVector
          val resets = emitted.linesIterator.map(_.trim)
            .filter(line => line.startsWith("event_live_ready_") && line.endsWith(" = 0;")).toVector
          assert(monitors.size == 2, resets.size == 6, resets.distinct.size == 6)
          assert(monitors.forall(_.contains(" or ")))
          val mutation = s"${targetName(variable(captured, "signal"))} = 2.0;"
          assert(emitted.indexOf(monitors.head) < emitted.indexOf(mutation))
          assert(emitted.indexOf(mutation) < emitted.indexOf(monitors(1)))
          assert(resets.take(3).forall(reset =>
            emitted.indexOf(reset) < emitted.indexOf(monitors.head)
          ))
          assert(resets.drop(3).forall(reset => emitted.indexOf(reset) > emitted.indexOf(mutation)))

    test("lazy control consumers and generated event occurrences retain their actual owners"):
      val captured = ConstructionKernel.inspect(new Increment43LazySharedControl)
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(operations(text, "analog_live_fill").size == 3)
      assert(operations(text, "analog_if_arm").size == 2)
      assert(operations(text, "analog_case").size == 1)
      assert(text.contains("bool_and("), text.contains("bool_not("))
      val generated = ConstructionKernel.inspect(new Increment43GeneratedSharedLive)
      val generatedText = ScalaToMlirBridge.fromSnapshot(generated).text
      val definitions = operations(generatedText, "analog_live_fill")
      assert(definitions.size == 1)
      assert(definitions.head.contains("generated_owner ="))
      assert(definitions.head.contains("generated_induction ="))
      native: (compiler, translator, directory) =>
        targets(captured, compiler, translator, directory): emitted =>
          assert(emitted.contains("case ("), emitted.contains("else begin"))
        targets(generated, compiler, translator, directory): emitted =>
          assert(emitted.contains("genvar"), emitted.contains("event_live_ready_0 = 0;"))
          assert(emitted.linesIterator.count(_.trim.startsWith("@(")) == 1)

    test("shared events bind only direct handles and retain exact transitive selected reads"):
      val captured = ConstructionKernel.inspect(new Increment43MixedSharedLive)
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(operations(text, "analog_live_fill").size == 1)
      assert(operations(text, "shape_index").size == 2)
      val cross = operations(text, "analog_cross").head
      val above = operations(text, "analog_above").head
      assert(cross.contains("!nodal.variable<"), cross.contains("!nodal.analog_deferred<"))
      assert(!above.contains("!nodal.variable<"), above.contains("!nodal.analog_deferred<"))
      val provenance = AnalogProceduralMlir.sourceMapEntries(captured)
      assert(provenance.exists(_.contains(".event.live_fill_0")))
      assert(provenance.exists(_.contains(".event.live.reference_1.storage.projection_0")))
      native: (compiler, translator, directory) =>
        targets(captured, compiler, translator, directory): emitted =>
          val monitor = emitted.linesIterator.find(_.trim.startsWith("@(")).get
          assert(monitor.contains("event_live_store("), monitor.contains(" or above("))
          assert(monitor.contains(s"${targetName(variable(captured, "cells"))}["))

    test("nested live diamond emission preserves one cached value across a complete OR"):
      val captured = ConstructionKernel.inspect(new Increment43LiveDiamond(8))
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(operations(text, "analog_live_fill").size == 8)
      assert(text.length < 250000)
      native: (compiler, translator, directory) =>
        targets(captured, compiler, translator, directory): emitted =>
          val monitors = emitted.linesIterator.map(_.trim).filter(_.startsWith("@(")).toVector
          assert(monitors.size == 1, monitors.head.contains(" or "))
          assert("event_live_store\\(".r.findAllIn(monitors.head).size == 1020)
          assert(emitted.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 1024 * 1024)
    test("live event budgets reject before expansion while selected DA stays strict"):
      for depth <- Vector(12, 40) do
        val message = failure(new Increment43LiveDiamond(depth))
        assert(message.contains("NODAL-SHAPE-043-010"), message.contains("before expansion"))
      val aggregate = failure(new Increment43LiveDiamond(8, 100))
      assert(aggregate.contains("module resource limit"), aggregate.contains("before expansion"))
      val missing = failure(new Increment43SharedLiveMissingLane)
      assert(
        missing.contains("NODAL-ANALOG-034-004"),
        missing.contains("before definite initialization")
      )

    test("wrappers preserve incoming initialization effects and mutable root restrictions"):
      val missing = failure(new Increment43ScalarInitializerMissingLane)
      assert(
        missing.contains("NODAL-ANALOG-034-004"),
        missing.contains("before definite initialization")
      )
      val mutable = failure(new Increment43MutableUniformScalarRoot)
      assert(mutable.contains("NODAL-BRIDGE-049"), mutable.contains("persistent"))
      assert(mutable.contains("mutable fill sources"))
      for function <- Vector(false, true); consumer <- 0 to 3 do
        val message = failure(new Increment43WrappedScalarEffect(function, consumer))
        val code = if function then "NODAL-ANALOG-041-001"
        else if consumer <= 1 then "NODAL-ANALOG-037-007" else "NODAL-ANALOG-035-001"
        assert(message.contains(code))
        assert(message.contains(if function then "function" else "stateful"))
