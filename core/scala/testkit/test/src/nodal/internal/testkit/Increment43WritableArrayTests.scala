package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class Increment43WritableEventArray extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    on(initialStep):
      val cells = variable(Vec(Real, 3), Vec.fill(Vec(Real, 3))(0.0.real))
      cells.writable.at(0) := 1.0.real
      cells.writable.at(1) := 2.0.real
      cells.writable.at(2) := 3.0.real
      val reused = cells.writable.slice(0, 2).value
      cells.writable.slice(1, 3) := reused
      sink := cells.at(1) + cells.at(2)
      cells.writable.at(0) := 4.0.real
      cells.writable.slice(1, 3) := reused
      val copied = variable(Vec(Real, 3), cells)
      copied.writable.reshape(1, 3).row(0).at(2) := sink
  analog:
    V(p, n) <+ 0.0.V

final class Increment43WritableSymbolicArray extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val rows = param(2.integer, range = 2 to 4)
  val lane = param(0.integer, range = 0 to 1)
  analogProcedure:
    on(initialStep):
      val cells = variable(Vec(Real, rows, 3), Vec.fill(Vec(Real, rows, 3))(0.0.V))
      cells.writable.row(lane).slice(1, 3).flatten.at(1) := 2.0.V
      val copy = variable(Vec(Real, rows, 3), cells)
      copy.writable := cells
  analog:
    V(p, n) <+ 0.0.V

final class Increment43WritableFixedCoverage extends Module:
  analogProcedure:
    on(initialStep):
      val cells = variable(Vec(Real, 2))
      cells.writable.at(0) := 1.0.real
      cells.writable.reshape(1, 2).row(0).at(1) := 2.0.real
      val copy = variable(Vec(Real, 2), cells)
      copy.writable.at(0) := copy.at(1)

final class Increment43WritableNoncontiguous extends Module:
  analogProcedure:
    on(initialStep):
      val cells = variable(Vec(Real, 2, 3))
      cells.writable.slice(1, 3, axis = 1) := Vec.fill(Vec(Real, 2, 2))(4.0.real)
      val sink = variable(Real, cells.slice(1, 3, axis = 1).flatten.at(2))
      cells.writable.at(1, 1) := sink

final class Increment43WritableSymbolicCoverage(reverse: Boolean) extends Module:
  val width = param(2.integer, range = 2 to 4)
  analogProcedure:
    on(initialStep):
      val cells = variable(Vec(Real, 2, width))
      if reverse then
        cells.writable.slice(1, 2).flatten := Vec.fill(Vec(Real, width))(1.0.real)
        val copied = variable(Vec(Real, width), cells.row(1))
        copied.writable.at(0) := 2.0.real
      else
        cells.writable.row(1) := Vec.fill(Vec(Real, width))(1.0.real)
        val copied = variable(Vec(Real, width), cells.slice(1, 2).flatten)
        copied.writable.at(0) := 2.0.real

final class Increment43WritableSingleton extends Module:
  analogProcedure:
    on(initialStep):
      val cells = variable(Vec(Real, 1, 1), Vec.fill(Vec(Real, 1, 1))(0.0.real))
      val copied = variable(Vec(Real, 1), cells.flatten)
      copied.writable.at(0) := cells.flatten.at(0)

final class Increment43WritableGenerated extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  hdlRange(0, 2): _ =>
    analogProcedure:
      on(initialStep):
        val cells = variable(
          Vec(Real, 2),
          Vec.fill(Vec(Real, 2))(0.0.real)
        )
        cells.writable.at(0) := 3.0.real
        cells.writable.slice(1, 2) := cells.slice(0, 1)
  analog:
    V(p, n) <+ 0.0.V

final class Increment43WritableRootFill extends Module:
  val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(3.5.real))
  analogProcedure:
    on(initialStep):
      cells.writable.at(0) := 1.0.real

final class Increment43WritableFillReadsOnce extends Module:
  analogProcedure:
    on(initialStep):
      val scalar = variable(Real, 2.0.real)
      val cells = variable(Vec(Real, 3), Vec.fill(Vec(Real, 3))(scalar + 1.0.real))
      scalar := 7.0.real
      cells.writable := Vec.fill(Vec(Real, 3))(scalar)

final class Increment43WritableNestedFill extends Module:
  analogProcedure:
    on(initialStep):
      val selected = Vec.fill(Vec(Real, 2))(1.0.real).at(0)
      val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(selected))
      cells.writable := Vec.fill(Vec(Real, 2))(selected)

final class Increment43WritableFailure(form: Int) extends Module:
  val index = param(0.integer, range = 0 to 2)
  val cells = variable(Vec(Real, 2))
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    if form == 4 then
      on(initialStep):
        cells.writable.at(0) := 1.0.real
      sink := cells.at(0)
    else
      on(initialStep):
        form match
          case 0 =>
            cells.writable.at(0) := 1.0.real
            sink := cells.at(1)
          case 1 => cells.writable := Vec.fill(Vec(Real, 1, 2))(0.0.real)
          case 2 => cells.writable.at(0) := 1.0.V
          case 3 =>
            val destination = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(0.0.real))
            destination.writable := cells
          case 5 =>
            val dynamic = variable(Integer, 0.integer)
            cells.writable.at(dynamic) := 1.0.real
          case 6 => cells.writable.at(index) := 1.0.real
          case 7 =>
            cells.writable.at(0) := 1.0.real
            cells.writable.reshape(1, 2).row(0).at(0) := 2.0.real
            val copy = variable(Vec(Real, 2), cells)
            copy.writable.at(0) := 3.0.real
          case _ => ()

final class Increment43WritableUnsupportedElement extends Module:
  val cells = variable(Vec(Integer, 2))
  analogProcedure:
    on(initialStep):
      ()

final class Increment43WritableEscape extends Module:
  analogProcedure:
    var escaped: Option[VariableSelection[Real]] = None
    on(initialStep):
      val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(0.0.real))
      escaped = Some(cells.writable.at(0))
    escaped.get := 1.0.real

final class Increment43WritableControlBoundary(form: Int) extends Module:
  val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(0.0.real))
  analogProcedure:
    if form == 0 then
      analogConditional:
        analogWhen(cells.at(0) > 0.0.real):
          cells.writable.at(1) := 1.0.real
    else if form == 1 then
      analogCase(cells.at(0) > 0.0.real):
        analogCaseArm(true):
          cells.writable.at(1) := 1.0.real
    else if form == 2 then
      on(cross(cells.at(0))):
        cells.writable.at(1) := 1.0.real
    else
      on(initialStep):
        val local = variable(Real, cells.at(0) + 1.0.real)
        local := 2.0.real

final class Increment43WritableBranches(missing: Boolean) extends Module:
  val flag = variable(Bool, true.B)
  val cells = variable(Vec(Real, 2))
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    on(initialStep):
      analogConditional:
        analogWhen(flag):
          cells.writable := Vec.fill(Vec(Real, 2))(1.0.real)
        analogOtherwise:
          cells.writable.at(if missing then 1 else 0) := 2.0.real
      sink := cells.at(0)

final class Increment43WritableCaughtFailure extends Module:
  analogProcedure:
    on(initialStep):
      val cells = variable(Vec(Real, 1))
      val rejected = scala.util.Try(cells.writable.at(0) := 1.0.V).failed.get
      assert(rejected.getMessage.contains("NODAL-ANALOG-033-013"))
      cells.writable.at(0) := 1.0.real
      val copy = variable(Vec(Real, 1), cells)
      copy.writable.at(0) := 2.0.real

final class Increment43WritableFlat extends Module:
  val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(0.0.real))
  analogProcedure:
    cells.writable.at(0) := 1.0.real
    cells.writable := cells

final class Increment43WritableOrdinaryArray extends Module:
  val cells = variable(Vec(Real, 3))
  val copied = variable(Vec(Real, 3))
  val sink = variable(Real, 0.0.real)
  analogProcedure:
    cells.writable := Vec.fill(Vec(Real, 3))(0.0.real)
    cells.writable.at(0) := 1.0.real
    cells.writable.at(1) := 2.0.real
    cells.writable.at(2) := 3.0.real
    val reused = cells.slice(0, 2)
    cells.writable.slice(1, 3) := reused
    sink := cells.at(1) + cells.at(2)
    cells.writable.at(0) := 4.0.real
    cells.writable.slice(1, 3) := reused
    copied.writable := cells
    copied.writable.reshape(1, 3).row(0).at(2) := sink

final class Increment43WritableOrdinarySymbolic extends Module:
  val rows = param(2.integer, range = 2 to 4)
  val lane = param(0.integer, range = 0 to 1)
  analogProcedure:
    analogConditional:
      analogWhen(true.B):
        val cells = variable(Vec(Real, rows, 3), Vec.fill(Vec(Real, rows, 3))(0.0.V))
        cells.writable.row(lane).at(2) := 2.0.V
        cells.writable.slice(0, 2, axis = 1) := cells.slice(1, 3, axis = 1)
        val copied = variable(Vec(Real, rows, 3), cells)
        copied.writable.row(lane).flatten.at(0) := cells.row(lane).at(1)

final class Increment43WritableOrdinaryGenerated extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      analogConditional:
        analogWhen(true.B):
          val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(0.0.real))
          cells.writable.at(0) := 3.0.real
          cells.writable.slice(1, 2) := cells.slice(0, 1)

final class Increment43WritableOrdinaryFailure(form: Int) extends Module:
  val cells = variable(Vec(Real, 2))
  val sink = variable(Real, 0.0.real)
  if form == 0 then cells.writable := Vec.fill(Vec(Real, 2))(1.0.real)
  else
    analogProcedure:
      if form == 1 then
        cells.writable.at(0) := 1.0.real
        sink := cells.at(1)
      else if form == 2 then
        var escaped: Option[Expr[Real]] = None
        analogConditional:
          analogWhen(true.B):
            val local = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(0.0.real))
            escaped = Some(local.at(0))
        cells.writable := Vec.fill(Vec(Real, 2))(escaped.get)
      else if form == 3 then
        cells.writable := Vec.fill(Vec(Real, 2))(cells.at(0))
      else
        cells.writable := Vec.fill(Vec(Real, 2))(0.0.real)
        analogConditional:
          analogWhen(cells.at(0) > 0.0.real):
            cells.writable.at(1) := 1.0.real

final class Increment43WritableOrdinaryRootFill extends Module:
  val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(2.5.real))
  val copied = variable(Vec(Real, 2))
  analogProcedure:
    copied.writable := cells
    cells.writable.at(0) := 7.0.real
    copied.writable.at(1) := cells.at(0)

final class Increment43WritablePersistentSymbolic extends Module:
  val rows = param(2.integer, range = 2 to 4)
  val level = param(1.25.V)
  val cells = variable(
    Vec(Real, rows, 2),
    Vec.fill(Vec(Real, rows, 3))(level).slice(0, 2, axis = 1)
  )
  analogProcedure:
    cells.writable.row(0).at(1) := 2.0.V

final class Increment43WritableGeneratedRootFill extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(4.5.real))
      cells.writable.at(0) := 3.0.real

final class Increment43WritableMutableRootFill extends Module:
  val scalar = variable(Real, 1.0.real)
  val cells = variable(Vec(Real, 2), Vec.fill(Vec(Real, 2))(scalar))
  analogProcedure:
    cells.writable.at(0) := 2.0.real

object Increment43WritableArrayTests extends TestSuite:
  private def failure(top: => Module): String =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get.getMessage

  private def document(top: => Module): NodalMlirDocument =
    ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(top))

  private def native(body: (Path, Path, Path) => Unit): Unit =
    (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
      case (Some(compiler), Some(translator)) =>
        val directory = Files.createTempDirectory("nodal-writable-array-")
        try body(Path.of(compiler), Path.of(translator), directory)
        finally
          val files = Files.walk(directory)
          try files.sorted(java.util.Comparator.reverseOrder()).forEach(path => {
              val _ = Files.deleteIfExists(path)
            })
          finally files.close()
      case _ => ()

  private def run(
      document: NodalMlirDocument,
      executable: Path,
      directory: Path,
      arguments: Vector[String]
  ): NativeCompilerResult =
    NativeCompilerClient.run(
      document,
      NativeCompilerRequest(
        executable = executable,
        arguments = arguments,
        workingDirectory = directory,
        timeout = Duration.ofSeconds(60)
      )
    )

  private def success(result: NativeCompilerResult): String = result match
    case value: NativeCompilerSuccess => value.normalizedMlir
    case value: NativeCompilerFailure =>
      throw new java.lang.AssertionError(s"${value.diagnostic}\n${value.standardError}")

  val tests: Tests = Tests:
    test("event-local arrays retain typed lexical projections and fresh snapshot copies"):
      val snapshot = ConstructionKernel.inspect(new Increment43WritableEventArray)
      assert(snapshot.shapeIndices.isEmpty, snapshot.shapeViews.isEmpty)
      val program = snapshot.analogProcedural.head
      assert(program.variables.count(_.variable.valueType.dimensions.nonEmpty) == 2)
      val declarations = program.variables.filter(_.variable.valueType.dimensions.nonEmpty)
      assert(declarations.map(_.authoredPath) == Vector(
        Some("Increment43WritableEventArray.cells"),
        Some("Increment43WritableEventArray.copied")
      ))
      assert(declarations.map(_.source.map(_.line)) == Vector(Some(16), Some(25)))
      assert(
        declarations.forall(_.source.exists(_.file.endsWith("Increment43WritableArrayTests.scala")))
      )
      val values = program.controlExpressions.filter(_.role == "assignment-value").map(_.value)
      assert(values.count(_.valueType.dimensions == Vector("2")) == 2)
      assert(
        values.exists(_.reads.count(_.isInstanceOf[AnalogProceduralRuntime.StorageSelection]) == 2)
      )
      val first = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(first == document(new Increment43WritableEventArray).text)
      assert(first.contains("nodal.analog_array_fill"), first.contains("value_form = \"snapshot\""))
      assert(
        first.contains("storage = \"procedural\""),
        first.contains("!nodal.shaped<\"3\", !nodal.variable")
      )

    test("symbolic storage preserves parameter shapes and quantity units"):
      val snapshot = ConstructionKernel.inspect(new Increment43WritableSymbolicArray)
      val arrays =
        snapshot.analogProcedural.head.variables.filter(_.variable.valueType.dimensions.nonEmpty)
      assert(arrays.forall(_.variable.valueType.dimensions == Vector("rows", "3")))
      assert(arrays.forall(_.variable.valueType.dimension == "voltage"))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.contains("!nodal.shaped<\"rows,3\", !nodal.quantity<\"real\", \"voltage\""))
      assert(text.contains("nodal.const_parameter_ref"))

    test("literal selected writes establish whole coverage without lane expansion"):
      val snapshot = ConstructionKernel.inspect(new Increment43WritableFixedCoverage)
      assert(snapshot.analogProcedural.head.variables.size == 2)
      val singleton = ConstructionKernel.inspect(new Increment43WritableSingleton)
      assert(singleton.analogProcedural.head.variables.last.variable.valueType.dimensions ==
        Vector("1"))
      assert(ScalaToMlirBridge.fromSnapshot(singleton).text.contains("dimensions = \"1\""))
      assert(document(new Increment43WritableNoncontiguous).text.contains("nodal.shape_slice"))
      assert(failure(new Increment43WritableFailure(7)).contains("NODAL-ANALOG-034-004"))
      for reverse <- Vector(false, true) do
        assert(document(
          new Increment43WritableSymbolicCoverage(reverse)
        ).text.contains("nodal.shape_slice"))

    test("array definite assignment checks sibling RHS and event boundaries"):
      for form <- Vector(0, 3, 4) do
        assert(failure(new Increment43WritableFailure(form)).contains("NODAL-ANALOG-034-004"))
      assert(failure(new Increment43WritableEscape).contains("NODAL-ANALOG-033-010"))

    test("shape units runtime indices and unsupported elements fail closed"):
      assert(failure(new Increment43WritableFailure(1)).contains("NODAL-SHAPE-043-007"))
      assert(failure(new Increment43WritableFailure(2)).contains("NODAL-ANALOG-033-013"))
      for form <- Vector(5, 6) do
        assert(failure(new Increment43WritableFailure(form)).contains("NODAL-SHAPE-043-002"))
      assert(failure(new Increment43WritableUnsupportedElement).contains("NODAL-ANALOG-033-019"))

    test("fill captures one scalar read group per consuming statement"):
      val snapshot = ConstructionKernel.inspect(new Increment43WritableFillReadsOnce)
      val program = snapshot.analogProcedural.head
      val initializer =
        program.variables.find(_.variable.valueType.dimensions.nonEmpty).get.initializer.get
      assert(initializer.source.exists(_.isInstanceOf[AnalogProceduralRuntime.ShapedSource.Fill]))
      assert(initializer.allReads.distinct.size == 1)
      assert(document(new Increment43WritableFillReadsOnce).text.split(
        "nodal.analog_array_fill",
        -1
      ).length == 3)

      val nested = document(new Increment43WritableNestedFill).text
      assert(nested.contains("nodal.shape_index"), nested.contains("storage = \"structural\""))

    test("public fixed and symbolic event arrays native verify and emit deterministically"):
      native: (compiler, translator, directory) =>
        for source <- Vector(
            document(new Increment43WritableEventArray),
            document(new Increment43WritableSymbolicArray),
            document(new Increment43WritableNoncontiguous),
            document(new Increment43WritableFixedCoverage),
            document(new Increment43WritableNestedFill),
            document(new Increment43WritableBranches(false)),
            document(new Increment43WritableGenerated),
            document(new Increment43WritableSymbolicCoverage(false)),
            document(new Increment43WritableSymbolicCoverage(true))
          )
        do
          val normalized = success(run(
            source,
            compiler,
            directory,
            Vector("--pass-pipeline=builtin.module(nodal-gate-default)")
          ))
          val emitted = success(run(
            source.copy(text = normalized),
            translator,
            directory,
            Vector("--nodal-to-verilog-a")
          ))
          val repeated = success(run(
            source.copy(text = normalized),
            translator,
            directory,
            Vector("--nodal-to-verilog-a")
          ))
          assert(emitted == repeated, emitted.contains("for ("), emitted.contains("real "))

    test("persistent root fills retain static units and structural view sources"):
      val ordinary = ConstructionKernel.inspect(new Increment43WritableOrdinaryRootFill)
      val program = ordinary.analogProcedural.head
      assert(program.variables.map(_.initializer.nonEmpty) == Vector(true, false))
      assert(program.variables.head.initializer.get.allReads.isEmpty)
      assert(program.variables.map(_.authoredPath) == Vector(
        Some("Increment43WritableOrdinaryRootFill.cells"),
        Some("Increment43WritableOrdinaryRootFill.copied")
      ))
      val symbolic = ConstructionKernel.inspect(new Increment43WritablePersistentSymbolic)
      val root = symbolic.analogProcedural.head.variables.head
      assert(root.variable.valueType.dimensions == Vector("rows", "2"))
      assert(root.variable.valueType.dimension == "voltage")
      assert(root.initializer.get.source.exists(
        _.isInstanceOf[AnalogProceduralRuntime.ShapedSource.View]
      ))
      assert(root.initializer.get.allReads.isEmpty)
      assert(ScalaToMlirBridge.fromSnapshot(symbolic).text.contains("nodal.shape_slice"))
      val generated = ConstructionKernel.inspect(new Increment43WritableGeneratedRootFill)
      assert(generated.analogProcedural.head.generatedOwner.nonEmpty)
      assert(generated.analogProcedural.head.variables.head.initializer.nonEmpty)
      val rejected = failure(new Increment43WritableMutableRootFill)
      assert(
        rejected.contains("NODAL-SHAPE-043-007"),
        rejected.contains("static fill without storage reads")
      )

    test("persistent root fills emit compact static declarations without runtime resets"):
      native: (compiler, translator, directory) =>
        for (owner, snapshot, pattern, eventful) <- Vector(
            (
              "Increment43WritableOrdinaryRootFill",
              ConstructionKernel.inspect(new Increment43WritableOrdinaryRootFill),
              "[0:1] = '{2{2.5}};",
              false
            ),
            (
              "Increment43WritableRootFill",
              ConstructionKernel.inspect(new Increment43WritableRootFill),
              "[0:1] = '{2{3.5}};",
              true
            ),
            (
              "Increment43WritablePersistentSymbolic",
              ConstructionKernel.inspect(new Increment43WritablePersistentSymbolic),
              "[0:((rows * 2) - 1)] = '{(rows * 2){level}};",
              false
            )
          )
        do
          for (backend, language) <- Vector(
              Backend.VerilogA -> "--nodal-to-verilog-a",
              Backend.VerilogAMS -> "--nodal-to-verilog-ams"
            )
          do
            val source = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
            val normalized = success(run(
              source,
              compiler,
              directory,
              Vector("--pass-pipeline=builtin.module(nodal-gate-default)")
            ))
            val emitted =
              success(run(source.copy(text = normalized), translator, directory, Vector(language)))
            val repeated =
              success(run(source.copy(text = normalized), translator, directory, Vector(language)))
            assert(emitted == repeated)
            val declaration = s"real event_${owner}_cells$pattern"
            assert(emitted.contains(declaration))
            assert(emitted.indexOf(declaration) < emitted.indexOf("analog begin"))
            assert(!emitted.contains("_initializer"), !emitted.contains("analog initial"))
            assert(emitted.sliding("initial_step".length).count(_ == "initial_step") ==
              (if eventful then 1 else 0))
            val body = emitted.substring(emitted.indexOf("analog begin"))
            val prefix = s"event_${owner}_"
            val writes =
              body.linesIterator.map(_.trim).filter(_.startsWith(s"${prefix}cells[")).toVector
            assert(writes.size == 1)
            val initialValue = owner match
              case "Increment43WritableOrdinaryRootFill" => "2.5"
              case "Increment43WritableRootFill" => "3.5"
              case _ => "level"
            assert(!body.contains(initialValue))
            if owner == "Increment43WritableOrdinaryRootFill" then
              assert(emitted.contains(s"real ${prefix}copied[0:1];"))
              val sequence = Vector(
                s"${prefix}statement_0_value_read_0[${prefix}statement_0_value_read_0_copy_index] = ${prefix}cells[${prefix}statement_0_value_read_0_copy_index];",
                s"${prefix}copied[${prefix}statement_0_copy_index] = ${prefix}statement_0_value_read_0[${prefix}statement_0_copy_index];",
                s"${prefix}cells[${prefix}statement_1_target_map] = 7.0;",
                s"${prefix}statement_2_read_0 = ${prefix}cells[${prefix}statement_2_read_0_target_map];",
                s"${prefix}copied[${prefix}statement_2_target_map] = ${prefix}statement_2_read_0;"
              ).map(body.indexOf)
              assert(
                sequence.forall(_ >= 0),
                sequence == sequence.sorted,
                sequence.distinct.size == sequence.size
              )
            if owner == "Increment43WritablePersistentSymbolic" then
              assert(emitted.contains("parameter integer rows = 2 from [2:4];"))
              assert(emitted.contains("parameter real level = 1.25;"))
              assert(!emitted.contains("(rows * 3)"))

    test("generated persistent root fill emits a per occurrence declaration"):
      native: (compiler, translator, directory) =>
        val source = document(new Increment43WritableGeneratedRootFill)
        val verified = success(run(
          source,
          compiler,
          directory,
          Vector("--pass-pipeline=builtin.module(nodal-gate-default)")
        ))
        val emitted = success(
          run(source.copy(text = verified), translator, directory, Vector("--nodal-to-verilog-a"))
        )
        val declaration = "real event_Increment43WritableGeneratedRootFill_cells[0:1] = '{2{4.5}};"
        assert(emitted.contains(declaration), emitted.contains("generate"))
        assert(emitted.indexOf(declaration) < emitted.indexOf("analog begin"))
        assert(!emitted.contains("initial_step"))
        assert(emitted.linesIterator.count(
          _.trim.startsWith("event_Increment43WritableGeneratedRootFill_cells[")
        ) == 1)

    test("selected controls and compound selected initializers retain typed captures"):
      for form <- 0 to 2 do
        assert(document(new Increment43WritableControlBoundary(form)).text
          .contains("reference_form = \"storage\""))
      assert(document(new Increment43WritableControlBoundary(3)).text
        .contains("initializer_form = \"expression\""))

    test("selected branch intersections and caught failures retain exact coverage"):
      assert(document(new Increment43WritableBranches(false)).text.contains("nodal.analog_if"))
      assert(failure(new Increment43WritableBranches(true)).contains("NODAL-ANALOG-034-004"))
      val recovered = ConstructionKernel.inspect(new Increment43WritableCaughtFailure)
      assert(recovered.analogProcedural.head.controlExpressions.count(_.role ==
        "assignment-value") == 2)
      assert(recovered.shapeIndices.isEmpty, recovered.shapeViews.isEmpty)

    test("generated lexical storage retains ownership and per-use source maps"):
      val snapshot = ConstructionKernel.inspect(new Increment43WritableGenerated)
      val program = snapshot.analogProcedural.head
      assert(program.generatedOwner.nonEmpty, program.generatedInduction.nonEmpty)
      assert(program.variables.forall(_.source.exists(_.line > 0)))
      val first = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(first == document(new Increment43WritableGenerated).text)
      assert(first.contains("generated_owner ="), first.contains("authored_path ="))
      assert(
        first.contains("kind = \"storage_snapshot\""),
        first.contains("storage = \"procedural\"")
      )
      val sourceMap = AnalogProceduralMlir.sourceMapEntries(snapshot)
      assert(sourceMap.exists(_.contains(".value.read_0")))
      assert(sourceMap.forall(_.contains("line =")))

    test("ordinary arrays retain source order and typed event-free snapshots"):
      val snapshot = ConstructionKernel.inspect(new Increment43WritableOrdinaryArray)
      val program = snapshot.analogProcedural.head
      val arrays = program.variables.filter(_.variable.valueType.dimensions.nonEmpty)
      assert(arrays.map(_.authoredPath) == Vector(
        Some("Increment43WritableOrdinaryArray.cells"),
        Some("Increment43WritableOrdinaryArray.copied")
      ))
      assert(arrays.forall(_.initializer.isEmpty))
      assert(arrays.map(_.source.map(_.line)) == Vector(Some(212), Some(213)))
      assert(program.assignments.map(_.authoredOrder) == (0 until 10).toVector)
      val source = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(source == document(new Increment43WritableOrdinaryArray).text)
      assert(!source.contains("nodal.analog_event_control"))
      assert(
        source.contains("nodal.analog_array_fill"),
        source.contains("value_form = \"snapshot\"")
      )
      val firstRead =
        source.indexOf("read_id = \"Increment43WritableOrdinaryArray.statement_4.value.read_0\"")
      val mutation = source.indexOf(source.linesIterator.find(line =>
        line.contains("\"nodal.analog_assign\"") && line.contains("authored_order = 6 : i64")
      ).get)
      val secondRead =
        source.indexOf("read_id = \"Increment43WritableOrdinaryArray.statement_7.value.read_0\"")
      assert(firstRead >= 0, mutation > firstRead, secondRead > mutation)
      val symbolic = ConstructionKernel.inspect(new Increment43WritableOrdinarySymbolic)
      assert(symbolic.analogProcedural.head.variables.forall(_.variable.valueType.dimension ==
        "voltage"))
      val generated = ConstructionKernel.inspect(new Increment43WritableOrdinaryGenerated)
      assert(generated.analogProcedural.head.generatedOwner.nonEmpty)
      assert(generated.analogProcedural.head.generatedInduction.nonEmpty)
      for captured <- Vector(snapshot, symbolic, generated) do
        val entries = AnalogProceduralMlir.sourceMapEntries(captured)
        assert(entries.exists(_.contains(".value.read_0")), entries.forall(_.contains("line =")))
        assert(captured.analogProcedural.head.variables.forall(_.source.exists(_.line > 0)))

    test("ordinary arrays native verify and emit without synthesized events"):
      native: (compiler, translator, directory) =>
        for (owner, source) <- Vector(
            "Increment43WritableOrdinaryArray" -> document(new Increment43WritableOrdinaryArray),
            "Increment43WritableOrdinarySymbolic" ->
              document(new Increment43WritableOrdinarySymbolic),
            "Increment43WritableOrdinaryGenerated" ->
              document(new Increment43WritableOrdinaryGenerated)
          )
        do
          val normalized = success(run(
            source,
            compiler,
            directory,
            Vector("--pass-pipeline=builtin.module(nodal-gate-default)")
          ))
          val emitted = success(run(
            source.copy(text = normalized),
            translator,
            directory,
            Vector("--nodal-to-verilog-a")
          ))
          val repeated = success(run(
            source.copy(text = normalized),
            translator,
            directory,
            Vector("--nodal-to-verilog-a")
          ))
          assert(emitted == repeated, emitted.contains("for ("), emitted.contains("real "))
          assert(!emitted.contains("initial_step"), !emitted.contains("@("))

          def ordered(markers: String*): Unit =
            val positions = markers.map(emitted.indexOf)
            assert(
              positions.forall(_ >= 0),
              positions == positions.sorted,
              positions.distinct.size == positions.size
            )
          val prefix = s"event_${owner}_"
          def loop(name: String): String = s"for ($prefix${name}_copy_index = 0;"
          owner match
            case "Increment43WritableOrdinaryArray" =>
              assert(
                emitted.contains(s"real ${prefix}cells[0:2];"),
                emitted.contains(s"real ${prefix}copied[0:2];")
              )
              ordered(
                "analog begin",
                s"${prefix}statement_0_value = 0.0;",
                loop("statement_4_value_read_0"),
                loop("statement_4"),
                s"${prefix}sink = (",
                s"${prefix}cells[${prefix}statement_6_target_map] = 4.0;",
                loop("statement_7_value_read_0"),
                loop("statement_7"),
                loop("statement_8_value_read_0"),
                loop("statement_8")
              )
            case "Increment43WritableOrdinarySymbolic" =>
              assert(emitted.contains(s"real ${prefix}cells[0:((rows * 3) - 1)];"))
              assert(emitted.contains("parameter integer rows = 2 from [2:4];"))
              val index = s"${prefix}statement_1_value_read_0_copy_index"
              assert(emitted.contains(s"((($index / 2) * 3) + (($index % 2) + 1))"))
              ordered(
                "if (1) begin",
                s"${prefix}procedure_if_0_branch_0_variable_0_initializer = 0.0;",
                loop("statement_1_value_read_0"),
                loop("statement_1"),
                loop("procedure_if_0_branch_0_variable_1_initializer_read_0"),
                loop("procedure_if_0_branch_0_variable_1")
              )
            case _ =>
              assert(emitted.contains("genvar hdl_range_"))
              ordered(
                "begin : generate_0",
                s"real ${prefix}cells[0:1];",
                "if (1) begin",
                s"${prefix}procedure_if_0_branch_0_variable_0_initializer = 0.0;",
                s"${prefix}cells[${prefix}statement_0_target_map] = 3.0;",
                loop("statement_1_value_read_0"),
                loop("statement_1")
              )

    test("ordinary array placement and captured reads retain causal rejection"):
      assert(failure(new Increment43WritableOrdinaryFailure(0)).contains("NODAL-ANALOG-033-008"))
      for form <- Vector(1, 3) do
        assert(
          failure(new Increment43WritableOrdinaryFailure(form)).contains("NODAL-ANALOG-033-011")
        )
      assert(failure(new Increment43WritableOrdinaryFailure(2)).contains("NODAL-ANALOG-033-010"))
      assert(document(new Increment43WritableOrdinaryFailure(4)).text
        .contains("reference_form = \"storage\""))

    test("coverage keys compare shared deep coordinate DAGs exactly"):
      import AnalogStorageCoverage.Coordinate.*
      def chain(token: String): AnalogStorageCoverage.Coordinate = (0 until 25).foldLeft[
        AnalogStorageCoverage.Coordinate
      ](Static(token)):
        case (value, _) => Add(value, Divide(value, Literal(2)))
      val first = AnalogStorageCoverage.Region(Literal(3), chain("Aa"))
      val second = AnalogStorageCoverage.Region(Literal(3), chain("Aa"))
      val different = AnalogStorageCoverage.Region(Literal(3), chain("BB"))
      assert(first == second, first.hashCode == second.hashCode, first != different)
      val shared = Add(Static("width"), Literal(1))
      val sharedRegion = AnalogStorageCoverage.Region(Literal(2), Multiply(shared, shared))
      val independent = AnalogStorageCoverage.Region(
        Literal(2),
        Multiply(
          Add(Static("width"), Literal(1)),
          Add(Static("width"), Literal(1))
        )
      )
      assert(sharedRegion == independent)

    test("bridge rejects forged storage roots indices and RHS shapes"):
      val snapshot = ConstructionKernel.inspect(new Increment43WritableFlat)
      val program = snapshot.analogProcedural.head
      val assignment = program.assignments.head
      val target = assignment.target.asInstanceOf[AnalogProceduralRuntime.StorageSelection]
      val index = target.projections.head.asInstanceOf[KernelShapeIndexSnapshot]
      val badIndex = target.copy(projections = Vector(index.copy(indices = Vector("3"))))
      val badRoot = target.copy(root = target.root.copy(owner = "foreign"))
      val badValue = program.assignments(1).value.copy(valueType =
        program.assignments(1).value.valueType.copy(dimensions = Vector("1", "2"))
      )
      val variants = Vector(
        program.copy(assignments = assignment.copy(target = badIndex) +: program.assignments.tail),
        program.copy(assignments = assignment.copy(target = badRoot) +: program.assignments.tail),
        program.copy(assignments =
          program.assignments.updated(1, program.assignments(1).copy(value = badValue))
        )
      )
      variants.foreach: invalid =>
        val rejected =
          scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot.copy(analogProcedural =
            Vector(invalid)
          )))
        assert(rejected.isFailure)
