package nodal.internal.testkit

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*

import utest.*

private trait Increment43SliceClock:
  this: Module =>
  val root: ClockDomain = ClockDomain.external(
    "root",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.Sync,
    resetPolarity = ResetPolarity.ActiveHigh,
    frequency = 100.MHz
  )

final class Increment43FixedSlices extends Module, Increment43SliceClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 4, 6))
  val first: Expr[Vec[Real]] = samples.slice(1, 3)
  val last: Expr[Vec[Real]] = first.slice(0.integer, 2, axis = 1)
  val singleton: Expr[Vec[Real]] = last.slice(1, 2)
  val selected: Expr[Real] = singleton.at(0, 1)

final class Increment43ComposedSlices extends Module, Increment43SliceClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 3, 4, 5))
  val sliced: Expr[Vec[Real]] = samples.slice(1, 4, axis = 2)
  val row: Expr[Vec[Real]] = sliced.plane(1).row(2)
  val reshaped: Expr[Vec[Real]] = row.slice(1, 3).reshape(1, 2)
  val selected: Expr[Real] = reshaped.flatten.slice(0, 1).at(0)

final class Increment43SymbolicSlices extends Module, Increment43SliceClock:
  val lower: Param[Integer] = param(1.integer, range = 1 to 2)
  val upper: Param[Integer] = param(5.integer, range = 5 to 6)
  val samples: Signal[Vec[Real]] = in(Vec(Real, 8, 2))
  val selected: Expr[Vec[Real]] = samples.slice(lower, upper).slice(0, 2, axis = 1)

final class Increment43FullExtentSlices extends Module, Increment43SliceClock:
  val lanes: Param[Integer] = param(4.integer, range = 4 to 8)
  val extent: Expr[Integer] = lanes + 1.integer
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes, extent))
  val first: Expr[Vec[Real]] = samples.slice(0, lanes)
  val last: Expr[Vec[Real]] = first.slice(0.integer, extent, axis = 1)
  val selected: Expr[Real] = last.row(0).at(0)

final class Increment43IntegerSlices extends Module, Increment43SliceClock:
  val samples: Signal[Vec[Integer]] = in(Vec(Integer, 4, 3))
  val selected: Expr[Integer] = samples.slice(1, 3).row(0).at(2)

final class Increment43InvalidLiteralSlice(lower: Int, upper: Int, axis: Int)
    extends Module, Increment43SliceClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 4, 3))
  val selected: Expr[Vec[Real]] = samples.slice(lower, upper, axis)

final class Increment43UnprovedSlice extends Module, Increment43SliceClock:
  val lanes: Param[Integer] = param(4.integer, range = 4 to 8)
  val other: Param[Integer] = param(4.integer, range = 4 to 8)
  val samples: Signal[Vec[Real]] = in(Vec(Real, lanes))
  val selected: Expr[Vec[Real]] = samples.slice(0, other)

final class Increment43UnboundedSlice extends Module, Increment43SliceClock:
  val upper: Param[Integer] = param(3.integer)
  val samples: Signal[Vec[Real]] = in(Vec(Real, 8))
  val selected: Expr[Vec[Real]] = samples.slice(0, upper)

final class Increment43DynamicSlice extends Module, Increment43SliceClock:
  val upper: Signal[Integer] = in(Integer)
  val samples: Signal[Vec[Real]] = in(Vec(Real, 8))
  val selected: Expr[Vec[Real]] = samples.slice(0, upper)

final class Increment43WireSlice extends Module:
  val samples: Signal[Vec[Real]] = wire(Vec(Real, 4))
  val selected: Expr[Vec[Real]] = samples.slice(0, 2)

final class Increment43ForgedSlice extends Module, Increment43SliceClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 4))
  val forged = new KernelExpr[Vec[Real]](
    Vector(samples),
    resultType = Some(KernelTypeDescriptor("Vec", Vector(Integer, Vector(2)))),
    operation = Some("shape_slice")
  )
  ConstructionKernel.shapeView(forged, samples, Vector(2), Some(ShapeSliceSelection(0, 0, 2)))

final class Increment43ScalarSlice extends Module, Increment43SliceClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 4))
  val scalar: Expr[Real] = samples.at(0)
  val selected: Expr[Vec[Real]] = scalar.asInstanceOf[Expr[Vec[Real]]].slice(0, 1)

final class Increment43CaughtSliceFailure extends Module, Increment43SliceClock:
  val lower: Param[Integer] = param(1.integer, range = 1 to 2)
  val upper: Param[Integer] = param(5.integer, range = 5 to 6)
  val samples: Signal[Vec[Real]] = in(Vec(Real, 4))
  val rejected = scala.util.Try(samples.slice(lower, upper)).failed.get
  val selected: Expr[Vec[Real]] = samples.slice(0, 2)

final class Increment43WideSlice(lastExtent: Int) extends Module, Increment43SliceClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, Int.MaxValue, Int.MaxValue, lastExtent))
  val selected: Expr[Vec[Real]] = samples.slice(0, 1)

final class Increment43ForeignSliceSource extends Module, Increment43SliceClock:
  val child = new Increment43FixedSlices
  val selected: Expr[Vec[Real]] = child.last.slice(0, 1)

final class Increment43ForeignSliceBound extends Module, Increment43SliceClock:
  val child = new Increment43SymbolicSlices
  val samples: Signal[Vec[Real]] = in(Vec(Real, 8))
  val selected: Expr[Vec[Real]] = samples.slice(0, child.upper)

final class Increment43UncapturedSlice extends Module:
  val forged = new KernelExpr[Vec[Real]](
    Vector.empty,
    resultType = Some(KernelTypeDescriptor("Vec", Vector(Real, Vector(4)))),
    operation = Some("shape_slice")
  )
  ConstructionKernel.expression(forged)
  val selected: Expr[Vec[Real]] = forged.slice(0, 2)

final class Increment43SingletonBoundSlice extends Module, Increment43SliceClock:
  val lower: Param[Integer] = param(0.integer, range = 0 to 0)
  val upper: Param[Integer] = param(5.integer, range = 5 to 6)
  val samples: Signal[Vec[Real]] = in(Vec(Real, 8))
  val selected: Expr[Vec[Real]] = samples.slice(lower, upper)

final class Increment43SliceTargetBoundary extends Module, Increment43SliceClock:
  val samples: Signal[Vec[Real]] = in(Vec(Real, 4, 3))
  val selected: Expr[Real] = samples.slice(1, 3).row(0).at(1)
  val p = inout(Electrical)
  val n = inout(Electrical)
  analog:
    I(p, n) <+ (V(p, n) / 2.kOhm)

object Increment43ShapeSliceTests extends TestSuite:
  private def failure(top: => Module): ConstructionException =
    scala.util.Try(ConstructionKernel.inspect(top)).failed.get.asInstanceOf[ConstructionException]

  private def bridgeFailure(snapshot: ConstructionSnapshot): BridgeException =
    scala.util.Try(
      ScalaToMlirBridge.fromSnapshot(snapshot)
    ).failed.get.asInstanceOf[BridgeException]

  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try stream.forEach(delete)
      finally stream.close()
    Files.deleteIfExists(path)

  val tests: Tests = Tests:
    test("fixed slices preserve rank singleton extents and immediate source identity"):
      val snapshot = ConstructionKernel.inspect(new Increment43FixedSlices)
      assert(snapshot.shapeViews.map(_.dimensions) ==
        Vector(Vector("2", "6"), Vector("2", "2"), Vector("1", "2")))
      assert(snapshot.shapeViews.map(_.slice.map(_.axis)) == Vector(Some(0), Some(1), Some(0)))
      assert(snapshot.shapeViews(1).slice.get.lower == "0")
      assert(snapshot.shapeViews(1).input == snapshot.shapeViews(0).path)
      assert(snapshot.shapeIndices.head.input == snapshot.shapeViews.last.path)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document == ScalaToMlirBridge.lower(new Increment43FixedSlices))
      assert(document.text.contains("\"nodal.shape_slice\"(%shape_slice_0,"))
      assert(document.text.contains("\"nodal.shape_index\"(%shape_slice_2,"))
      assert(document.text.contains("access = \"read_only\""))
      assert(document.text.contains("!nodal.shaped<\"1,2\", f64>"))

    test("symbolic slices retain canonical subtraction and full extent identities"):
      val snapshot = ConstructionKernel.inspect(new Increment43SymbolicSlices)
      val view = snapshot.shapeViews.head
      val length = snapshot.parameterExpressions.find(_.path == view.dimensions.head).get
      assert(length.operation == "analog_sub")
      assert(length.operands == Vector(view.slice.get.upperExclusive, view.slice.get.lower))
      Vector("lower", "upper").foreach: name =>
        val attributes =
          snapshot.modules.head.declarations.find(_.name == name).get.attributes.toMap
        assert(attributes("structural_effects").split(",").contains("shape"))
      val full = ConstructionKernel.inspect(new Increment43FullExtentSlices)
      assert(full.shapeViews.head.dimensions.head == "lanes")
      assert(full.shapeViews(1).dimensions(1) == full.shapeViews(1).slice.get.upperExclusive)
      assert(full.parameterExpressions.count(_.operation == "analog_sub") == 0)
      assert(ScalaToMlirBridge.fromSnapshot(full) ==
        ScalaToMlirBridge.lower(new Increment43FullExtentSlices))
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("operator_name = \"sub\""))

    test("slice chains compose through plane row reshape flatten and scalar selection"):
      val snapshot = ConstructionKernel.inspect(new Increment43ComposedSlices)
      assert(snapshot.shapeViews.count(_.slice.nonEmpty) == 3)
      assert(snapshot.shapeViews.count(_.slice.isEmpty) == 2)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document == ScalaToMlirBridge.lower(new Increment43ComposedSlices))
      assert(document.text.contains("\"nodal.shape_slice\"(%shape_index_1,"))
      assert(document.text.contains("\"nodal.shape_view\"(%shape_slice_1)"))
      assert(ScalaToMlirBridge.lower(
        new Increment43IntegerSlices
      ).text.contains("!nodal.shaped<\"2,3\", i64>"))

    test("invalid slice bounds owners and element carriers reject before publication"):
      Vector((-1, 2, 0), (0, 0, 0), (3, 2, 0), (0, 5, 0), (0, 2, -1), (0, 2, 2)).foreach:
        (lower, upper, axis) =>
          assert(failure(new Increment43InvalidLiteralSlice(lower, upper, axis)).diagnostic.code ==
            "NODAL-SHAPE-043-004")
      Vector(
        failure(new Increment43UnprovedSlice),
        failure(new Increment43UnboundedSlice),
        failure(new Increment43DynamicSlice),
        failure(new Increment43WireSlice),
        failure(new Increment43ForgedSlice),
        failure(new Increment43ScalarSlice)
      ).foreach: error =>
        assert(error.diagnostic.code == "NODAL-SHAPE-043-004")

    test("caught slice failures publish neither a view nor structural endpoint effects"):
      val snapshot = ConstructionKernel.inspect(new Increment43CaughtSliceFailure)
      assert(snapshot.shapeViews.size == 1)
      assert(snapshot.shapeViews.head.dimensions == Vector("2"))
      assert(snapshot.parameterExpressions.isEmpty)
      Vector("lower", "upper").foreach: name =>
        val attributes =
          snapshot.modules.head.declarations.find(_.name == name).get.attributes.toMap
        assert(!attributes.get("structural_effects").exists(_.split(",").contains("shape")))
      assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("nodal.shape_slice"))

    test("bridge independently rejects forged slice shape bounds length and source graphs"):
      val fixed = ConstructionKernel.inspect(new Increment43FixedSlices)
      val original = fixed.shapeViews.head
      val selection = original.slice.get
      Vector(
        original.copy(slice = Some(selection.copy(axis = -1))),
        original.copy(slice = Some(selection.copy(axis = 2))),
        original.copy(slice = Some(selection.copy(lower = "-1"))),
        original.copy(slice = Some(selection.copy(upperExclusive = "5"))),
        original.copy(slice = Some(selection.copy(lower = "01"))),
        original.copy(dimensions = Vector("1", "6")),
        original.copy(dimensions = Vector("2", "5")),
        original.copy(dimensions = Vector("2")),
        original.copy(input = original.path),
        original.copy(input = s"${original.owner}.missing"),
        original.copy(input = "Other.samples")
      ).foreach: forged =>
        val error = bridgeFailure(fixed.copy(shapeViews = forged +: fixed.shapeViews.tail))
        assert(error.diagnostic.code == "NODAL-BRIDGE-046")
      val symbolic = ConstructionKernel.inspect(new Increment43SymbolicSlices)
      val lengthPath = symbolic.shapeViews.head.dimensions.head
      Vector("analog_add", "analog_mul").foreach: operation =>
        val forged = symbolic.copy(parameterExpressions =
          symbolic.parameterExpressions.map(expression =>
            if expression.path == lengthPath then expression.copy(operation = operation)
            else expression
          )
        )
        assert(bridgeFailure(forged).diagnostic.code == "NODAL-BRIDGE-046")
      val reversed = symbolic.copy(parameterExpressions =
        symbolic.parameterExpressions.map(expression =>
          if expression.path == lengthPath then
            expression.copy(operands = expression.operands.reverse)
          else expression
        )
      )
      assert(bridgeFailure(reversed).diagnostic.code == "NODAL-BRIDGE-046")
      val missing = symbolic.copy(parameterExpressions =
        symbolic.parameterExpressions.filterNot(_.path == lengthPath)
      )
      assert(bridgeFailure(missing).diagnostic.code == "NODAL-BRIDGE-046")
      val effects = symbolic.copy(modules =
        symbolic.modules.map(module =>
          module.copy(declarations =
            module.declarations.map(declaration =>
              if declaration.name == "lower" then
                declaration.copy(attributes =
                  declaration.attributes.filterNot(_._1 == "structural_effects")
                )
              else declaration
            )
          )
        )
      )
      assert(bridgeFailure(effects).diagnostic.code == "NODAL-BRIDGE-046")
      val full = ConstructionKernel.inspect(new Increment43FullExtentSlices)
      val fullView = full.shapeViews.head
      val fullPathExtent = fullView.copy(dimensions =
        fullView.dimensions.updated(0, fullView.slice.get.upperExclusive)
      )
      assert(bridgeFailure(full.copy(shapeViews =
        fullPathExtent +: full.shapeViews.tail
      )).diagnostic.code == "NODAL-BRIDGE-046")
      val literalPath = s"${original.owner}.forged_literal"
      val literal = KernelParameterExpressionSnapshot(
        literalPath,
        original.owner,
        "integer",
        Vector.empty,
        "Integer",
        Some("2"),
        None
      )
      val literalExtent = original.copy(dimensions = original.dimensions.updated(0, literalPath))
      assert(bridgeFailure(fixed.copy(
        parameterExpressions = Vector(literal),
        shapeViews = literalExtent +: fixed.shapeViews.tail
      )).diagnostic.code == "NODAL-BRIDGE-046")

    test("slice boundaries preserve signed64 counts and reject foreign or coincident identities"):
      val wide = ConstructionKernel.inspect(new Increment43WideSlice(2))
      assert(wide.shapeViews.head.dimensions == Vector("1", Int.MaxValue.toString, "2"))
      assert(ScalaToMlirBridge.fromSnapshot(wide).text.contains("2147483647,2147483647,2"))
      Vector(
        failure(new Increment43WideSlice(3)),
        failure(new Increment43ForeignSliceSource),
        failure(new Increment43ForeignSliceBound),
        failure(new Increment43UncapturedSlice)
      ).foreach: error =>
        assert(error.diagnostic.code == "NODAL-SHAPE-043-004")
      val singleton = ConstructionKernel.inspect(new Increment43SingletonBoundSlice)
      val view = singleton.shapeViews.head
      assert(singleton.parameterExpressions.find(_.path == view.dimensions.head).get.operation ==
        "analog_sub")
      val coincident = singleton.copy(shapeViews = Vector(view.copy(dimensions = Vector("upper"))))
      assert(bridgeFailure(coincident).diagnostic.code == "NODAL-BRIDGE-046")
      val forgedWide = wide.copy(
        modules = wide.modules.map(module =>
          module.copy(declarations =
            module.declarations.map(declaration =>
              if declaration.name == "samples" then
                declaration.copy(dataType = Some("Vec(Real;2147483647x2147483647x3)"))
              else declaration
            )
          )
        ),
        shapeViews = wide.shapeViews.map(_.copy(dimensions = Vector("1", "2147483647", "3")))
      )
      assert(bridgeFailure(forgedWide).diagnostic.code == "NODAL-BRIDGE-046")

    test(
      "configured mixed-signal target rejects missing explicit bridge before layout lowering"
    ):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(compiler), Some(translator)) =>
          val directory = Files.createTempDirectory("nodal-increment43-slice-target-")
          try
            val document = ScalaToMlirBridge.lower(
              new Increment43SliceTargetBoundary,
              EmitOptions(backend = Backend.VerilogAMS)
            )
            assert(document.text.contains("mixed_signal"))
            val request = NativeCompilerRequest(
              executable = Path.of(compiler).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            val normalized = NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess => success.normalizedMlir
              case failure: NativeCompilerFailure =>
                throw new java.lang.AssertionError(
                  s"${failure.diagnostic}\n${failure.standardError}"
                )
            assert(normalized.contains("nodal.shape_slice"))
            val translated = NativeCompilerClient.run(
              document,
              request.copy(
                executable = Path.of(translator).toAbsolutePath,
                arguments = Vector("--nodal-to-verilog-ams")
              )
            )
            translated match
              case failure: NativeCompilerFailure =>
                assert(failure.standardError.contains("NODAL-VERIFY-ANALOG-004"))
                assert(failure.standardError.contains(
                  "mixed digital/analog module requires an explicit bridge"
                ))
                assert(!failure.standardError.contains("NODAL-BACKEND-PROFILE-001"))
              case success: NativeCompilerSuccess => throw new java.lang.AssertionError(
                  s"unsupported slice target unexpectedly emitted: ${success.normalizedMlir}"
                )
          finally delete(directory)
        case _ => ()

    test("configured native verifies public fixed symbolic and composed slice transport"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = Files.createTempDirectory("nodal-increment43-slice-")
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            Vector(
              ScalaToMlirBridge.lower(new Increment43FixedSlices),
              ScalaToMlirBridge.lower(new Increment43SymbolicSlices),
              ScalaToMlirBridge.lower(new Increment43FullExtentSlices),
              ScalaToMlirBridge.lower(new Increment43ComposedSlices),
              ScalaToMlirBridge.lower(new Increment43IntegerSlices),
              ScalaToMlirBridge.lower(new Increment43WideSlice(2)),
              ScalaToMlirBridge.lower(new Increment43SingletonBoundSlice)
            ).foreach: document =>
              NativeCompilerClient.run(document, request) match
                case success: NativeCompilerSuccess =>
                  assert(success.normalizedMlir.contains("nodal.shape_slice"))
                case failure: NativeCompilerFailure =>
                  scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")
          finally delete(directory)
