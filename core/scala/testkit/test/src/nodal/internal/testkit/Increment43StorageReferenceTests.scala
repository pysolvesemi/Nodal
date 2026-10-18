package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class Increment43LiveStorageConditions extends Module:
  val cells = variable(Vec(Real, 3), Vec.fill(Vec(Real, 3))(1.0.real))
  val flag = variable(Bool, true.B)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    val reused = (flag && (cells.at(0) > 0.0.real)) || (cells.at(1) > 0.0.real)
    analogConditional:
      analogWhen(reused):
        sink := 1.0.real
      analogElseWhen(AnalogMath.abs(cells.at(0)) > cells.at(2)):
        sink := 2.0.real
      analogOtherwise:
        sink := 3.0.real
    cells.writable.at(0) := -2.0.real
    analogConditional:
      analogWhen(reused):
        sink := 4.0.real
      analogOtherwise:
        sink := 5.0.real
    analogCase(cells.at(0) > cells.at(1)):
      analogCaseArm(true):
        sink := 6.0.real
      analogCaseArm(false):
        sink := 7.0.real

final class Increment43LiveStorageLoops extends Module:
  val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.real))
  val iterations = variable(Integer, 1.integer)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    analogConditional:
      analogWhen(cells.at(0) > 0.0.real):
        iterations := 2.integer
      analogOtherwise:
        iterations := 1.integer
    analogLoop(iterations, maximumIterations = 4, minimumIterations = 1):
      analogLoop(iterations, maximumIterations = 4, minimumIterations = 1):
        cells.writable.at(0) := cells.at(0) + 1.0.real
        analogConditional:
          analogWhen(cells.at(0) > 3.0.real):
            analogBreak()
          analogElseWhen(cells.at(0) > cells.at(1)):
            analogContinue()
          analogOtherwise:
            sink := 1.0.real

final class Increment43LiveStorageEvents extends Module:
  val voltage = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.V))
  val timing = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.ns))
  val enable = variable(Integer, 1.integer)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    val edge = cross(voltage.at(0), Edge.Rising, timing.at(0), voltage.at(1), enable)
    val level = above(voltage.at(1), timing.at(0), voltage.at(0), enable)
    on(edge or level):
      sink := 1.0.real
    voltage.writable.at(0) := 2.0.V
    on(edge):
      sink := 2.0.real
    on(timer(timing.at(0), timing.at(1), timing.at(0), enable)):
      sink := 3.0.real

final class Increment43LiveStoragePartial extends Module:
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    on(initialStep):
      val cells = variable(Vec(Real, 2))
      cells.writable.at(0) := 1.0.real
      analogConditional:
        analogWhen(cells.at(0) > 0.0.real):
          sink := 1.0.real
      analogCase(cells.at(0) > 0.0.real):
        analogCaseArm(true):
          sink := 2.0.real

final class Increment43LiveStorageSymbolic extends Module:
  val rows = param(2.integer, range = 2 to 4)
  val lane = param(0.integer, range = 0 to 1)
  val cells = variable(Vec(Real, rows, 3), Vec.fill(Vec(Real, rows, 3))(1.0.V))
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    analogConditional:
      analogWhen(cells.slice(1, 3, axis = 1).flatten.at(2) > cells.row(lane).at(2)):
        sink := 1.0.real
      analogOtherwise:
        sink := 2.0.real

final class Increment43LiveStorageGenerated extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      analogConditional:
        analogWhen(true.B):
          val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(1.0.real))
          analogConditional:
            analogWhen(cells.at(0) > cells.at(1)):
              cells.writable.at(0) := 2.0.real
            analogOtherwise:
              cells.writable.at(1) := 3.0.real

object Increment43StorageReferenceTests extends TestSuite:
  private def document(top: => Module): NodalMlirDocument =
    ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(top))

  private def native(body: (Path, Path, Path) => Unit): Unit =
    (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
      case (Some(compiler), Some(translator)) =>
        val directory = Files.createTempDirectory("nodal-storage-reference-")
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

  private def target(source: NodalMlirDocument, compiler: Path, translator: Path, directory: Path)
      : String =
    val normalized = run(
      source,
      compiler,
      directory,
      Vector("--pass-pipeline=builtin.module(nodal-gate-default)")
    )
    val first = run(
      source.copy(text = normalized),
      translator,
      directory,
      Vector("--nodal-to-verilog-a")
    )
    val second = run(
      source.copy(text = normalized),
      translator,
      directory,
      Vector("--nodal-to-verilog-a")
    )
    assert(first == second)
    first

  val tests: Tests = Tests:
    test("selected conditions bind all live leaves before their control owners"):
      val snapshot = ConstructionKernel.inspect(new Increment43LiveStorageConditions)
      val source = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(source == document(new Increment43LiveStorageConditions).text)
      assert(source.contains("reference_form = \"storage\""))
      assert(!source.contains("nodal.analog_variable_read"))
      assert(source.contains("!nodal.variable<\"boolean\", \"1\">"))
      val firstProjection = source.linesIterator.find(line =>
        line.contains("\"nodal.shape_index\"") &&
          line.contains(".condition_0.reference_1.storage.projection_0")
      ).get
      val firstIf = source.indexOf("\"nodal.analog_if\"")
      assert(source.indexOf(firstProjection) < firstIf)
      assert(source.contains(".selector.reference_0.storage.projection_0"))
      assert(source.contains("kind = \"storage_reference\""))
      val entries = AnalogProceduralMlir.sourceMapEntries(snapshot)
      assert(entries.exists(_.contains(".condition_0.reference_1")))
      assert(entries.exists(_.contains(".selector.reference_0")))
      assert(entries.forall(_.contains("line =")))

    test("event references preserve slot units and independent reused monitor occurrences"):
      val snapshot = ConstructionKernel.inspect(new Increment43LiveStorageEvents)
      val source = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(source == document(new Increment43LiveStorageEvents).text)
      assert(!source.contains("nodal.analog_variable_read"))
      assert(source.sliding("\"nodal.analog_cross\"".length)
        .count(_ == "\"nodal.analog_cross\"") == 2)
      assert(source.contains("\"nodal.analog_above\""), source.contains("\"nodal.analog_timer\""))
      assert(source.contains("\"nodal.analog_event_or\""))
      assert(source.contains("!nodal.variable<\"integer\", \"1\">"))
      val entries = AnalogProceduralMlir.sourceMapEntries(snapshot)
      assert(entries.exists(_.contains(".event.argument_0.reference_0")))
      assert(entries.exists(_.contains(".event.argument_2.reference_0")))

    test("partial symbolic and generated selections retain typed coverage and lineage"):
      val partial = document(new Increment43LiveStoragePartial).text
      assert(partial.contains("reference_form = \"storage\""))
      assert(!partial.contains("nodal.analog_variable_read"))
      val symbolic = document(new Increment43LiveStorageSymbolic).text
      assert(symbolic.contains("\"nodal.shape_slice\""), symbolic.contains("\"nodal.shape_view\""))
      assert(!symbolic.contains("nodal.analog_variable_read"))
      val generated = ConstructionKernel.inspect(new Increment43LiveStorageGenerated)
      val source = ScalaToMlirBridge.fromSnapshot(generated).text
      assert(generated.analogProcedural.head.generatedOwner.nonEmpty)
      assert(source.contains("generated_owner ="), source.contains("generated_induction ="))
      assert(!source.contains("nodal.analog_variable_read"))
      assert(AnalogProceduralMlir.sourceMapEntries(generated)
        .exists(_.contains(".condition_0.reference_0.storage.projection_0")))

    test("live conditions and loop body predicates native verify and emit deterministically"):
      native: (compiler, translator, directory) =>
        val emitted = target(
          document(new Increment43LiveStorageConditions),
          compiler,
          translator,
          directory
        )
        assert(emitted.contains("&&"), emitted.contains("||"), emitted.contains("else if ("))
        assert(emitted.contains("case ("), emitted.contains("abs("))
        assert(!emitted.contains("read_snapshot"), !emitted.contains("initial_step"))
        val prefix = "event_Increment43LiveStorageConditions_"
        val conditions = emitted.linesIterator.filter(_.trim.startsWith("if (")).toVector
        assert(conditions.size == 2)
        assert(
          conditions.head.contains(s"${prefix}cells[${prefix}if_0_condition_0_reference_1_map]")
        )
        assert(
          conditions.head.contains(s"${prefix}cells[${prefix}if_0_condition_0_reference_2_map]")
        )
        assert(conditions(1).contains(s"${prefix}cells[${prefix}if_1_condition_0_reference_1_map]"))
        assert(emitted.indexOf(" = -2.0;") < emitted.indexOf(conditions(1)))
        assert(emitted.linesIterator.count(_.trim.startsWith("real ")) == 2)
        val loops =
          target(document(new Increment43LiveStorageLoops), compiler, translator, directory)
        assert(loops.contains("_limit ="), loops.contains("_loop_2_continue = 1;"))
        assert(loops.contains("_loop_2_break = 1;"))
        val loopPrefix = "event_Increment43LiveStorageLoops_"
        assert(loops.indexOf(s"${loopPrefix}iterations = 2;") <
          loops.indexOf(s"${loopPrefix}loop_1_limit = ${loopPrefix}iterations;"))
        val predicate = s"if ((${loopPrefix}cells[${loopPrefix}if_3_condition_0_reference_0_map] > "
        assert(loops.contains(predicate))
        assert(loops.indexOf("_statement_2_target_map] = (") < loops.indexOf(predicate))
        assert(loops.contains("event_Increment43LiveStorageLoops_cells["))
        assert(!loops.contains("initial_step"))

    test("live cross above timer and reused OR monitors native verify and emit deterministically"):
      native: (compiler, translator, directory) =>
        val emitted = target(
          document(new Increment43LiveStorageEvents),
          compiler,
          translator,
          directory
        )
        assert(emitted.contains("cross("), emitted.contains("above("), emitted.contains("timer("))
        assert(emitted.contains(" or "), emitted.contains("@(cross("))
        assert(emitted.contains("event_Increment43LiveStorageEvents_voltage["))
        assert(emitted.contains("event_Increment43LiveStorageEvents_timing["))
        assert(!emitted.contains("read_snapshot"), !emitted.contains("initial_step"))
        val monitors = emitted.linesIterator.filter(_.trim.startsWith("@(")).toVector
        assert(monitors.size == 3)
        assert(monitors.head.contains(" or above("))
        assert(monitors.head.contains("_event_0_event_alternative_0_reference_0_map]"))
        assert(monitors(1).contains("_event_1_event_reference_0_map]"))
        assert(monitors(2).contains("_event_2_event_reference_0_map]"))
        assert(monitors(2).contains("_event_2_event_reference_2_map]"))
        assert(emitted.indexOf("_statement_1_target_map] = 2.0;") < emitted.indexOf(monitors(1)))
        assert(emitted.linesIterator.count(_.trim.startsWith("real ")) == 3)

    test("selected partial symbolic and generated references reach actual target layout"):
      native: (compiler, translator, directory) =>
        val partial = target(
          document(new Increment43LiveStoragePartial),
          compiler,
          translator,
          directory
        )
        assert(partial.contains("initial_step"), partial.contains("case ("))
        val symbolic = target(
          document(new Increment43LiveStorageSymbolic),
          compiler,
          translator,
          directory
        )
        assert(
          symbolic.contains("parameter integer rows"),
          symbolic.contains("parameter integer lane")
        )
        assert(
          symbolic.contains("%"),
          symbolic.contains("event_Increment43LiveStorageSymbolic_cells[")
        )
        assert(!symbolic.contains("read_snapshot"))
        val generated = target(
          document(new Increment43LiveStorageGenerated),
          compiler,
          translator,
          directory
        )
        assert(generated.contains("generate"), generated.contains("genvar"))
        assert(generated.contains("_cells["), !generated.contains("initial_step"))
