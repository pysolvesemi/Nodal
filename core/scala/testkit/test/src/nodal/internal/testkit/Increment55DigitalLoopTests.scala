package nodal.internal.testkit

import nodal.*
import nodal_external.*
import utest.*

final class FixedSeededLoopSource(val lanes: Int) extends Module, BoundedLoopClock:
  val seed = in(UInt(8))
  val cells = wire(Vec(UInt(8), lanes + 1))
  val output = out(Vec(UInt(8), lanes + 1))
  cells.writable.at(0) := seed
  loop(LoopBound.Static(lanes)): index =>
    cells.writable.at(index.value + 1.integer) := (cells.at(index.value) + 1.U(8)).wrap(8)
  output := cells

final class EmptyBadLoopType extends Module, BoundedLoopClock:
  val input = in(UInt(4))
  val output = out(UInt(8))
  loop(LoopBound.Static(0))(_ => output := input)

final class EmptyUnsupportedIndex extends Module, BoundedLoopClock:
  loop(LoopBound.Static(0)): index =>
    val bad = index.value * 2.integer
    val _ = bad

final class NegativeLoopCount extends Module:
  loop(LoopBound.Static(-1))(_ => ())

final class WrongLoopMaximum extends Module, BoundedLoopClock:
  val count = param(2.integer, range = 0 to 8)
  loop(LoopBound.Symbolic(count, 7))(_ => ())

final class WrongLoopIndex extends Module, BoundedLoopClock:
  val count = param(2.integer, range = 1 to 8)
  val input = in(Vec(UInt(8), count))
  val output = out(Vec(UInt(8), count))
  loop(LoopBound.Symbolic(count, 8)): index =>
    output.writable.at(index.value + 1.integer) := input.at(index.value)

final class LoopInputWrite extends Module, BoundedLoopClock:
  val input = in(Vec(UInt(8), 1))
  loop(LoopBound.Static(0)): index =>
    input.writable.at(index.value) := 0.U(8)

final class LoopDifferentParameter extends Module, BoundedLoopClock:
  val count = param(2.integer, range = 1 to 8)
  val other = param(2.integer, range = 1 to 8)
  val input = in(Vec(UInt(8), count))
  val output = out(Vec(UInt(8), other))
  loop(LoopBound.Symbolic(count, 8)): index =>
    output.writable.at(index.value) := input.at(index.value)

final class ParameterStepLoop extends Module, BoundedLoopClock:
  val step = param(1.integer, range = 1 to 1)
  val input = in(Vec(UInt(8), 2))
  val output = out(Vec(UInt(8), 2))
  hdlRange(0, 2, step): index =>
    output.writable.at(index) := input.at(index)

final class LoopEscapedInduction extends Module, BoundedLoopClock:
  val input = in(Vec(UInt(8), 1))
  private var escaped: Expr[Integer] = 0.integer
  // Trusted kernel mutation fixture: test lexical rejection independently of source attestation.
  ConstructionKernel.digitalLoop(LoopBound.Static(0))(index => escaped = index)
  val selected = input.at(escaped)

final class LoopCaptureOnce extends Module:
  var invocations = 0
  // Private instrumentation is deliberately outside the sanctioned public callback profile.
  ConstructionKernel.digitalLoop(LoopBound.Static(0))(_ => invocations += 1)

final class DistinctRangeLoopScaffolds extends Module, BoundedLoopClock:
  val input = in(Vec(UInt(8), 4))
  val output = out(Vec(UInt(8), 4))
  hdlRange(0, 1)(_ => ())
  hdlRange(0, 2): index =>
    output.writable.at(index) := input.at(index)
  hdlRange(2, 4): index =>
    analog:
      val smoothed = transition(1.0.V)
      val _ = smoothed
    output.writable.at(index) := input.at(index)
  loop(LoopBound.Static(0))(_ => ())

object Increment55DigitalLoopTests extends TestSuite:
  private def rejected(source: => Module, fragment: String): Unit =
    val failure = scala.util.Try(ConstructionKernel.inspect(source)).failed.get
    assert(failure.isInstanceOf[ConstructionException])
    assert(failure.getMessage.contains(fragment))

  val tests: Tests = Tests:
    test("public loops retain real induction captures and source order"):
      val snapshot = ConstructionKernel.inspect(new PublicSeededLoop)
      val loop = snapshot.digitalLoops.head
      assert(loop.arguments.head.role == "induction")
      assert(loop.arguments.head.path == loop.path + ".arg0")
      assert(loop.captures == Vector(snapshot.root + ".cells"))
      assert(snapshot.iterationIndices.head.operands.head == loop.arguments.head.path)
      assert(snapshot.parameterExpressions.forall(!_.operands.contains(loop.arguments.head.path)))
      assert(snapshot.digitalAssignments.map(_.sourceOrdinal) == Vector(0, 0, 2))
      assert(loop.sourceOrdinal == 1)
      assert(snapshot.digitalAssignments(1).region.contains(loop.path))
      val source = snapshot.sourceMap.find(_.semanticPath == loop.path).get.source
      assert(source.path.endsWith("Increment55PublicBoundedLoops.scala"))
      assert(source.line > 0, source.endLine >= source.line)
      assert(loop.owner == snapshot.root)
      assert(snapshot == ConstructionKernel.inspect(new PublicSeededLoop))

    test("range partition scaffolds preserve authored analog identities and unique loop scopes"):
      val waveform = ConstructionKernel.inspect(new Increment43GeneratedWaveformOperator)
      assert(waveform == ConstructionKernel.inspect(new Increment43GeneratedWaveformOperator))
      assert(waveform.digitalLoops.isEmpty)
      val expected = Map(
        "analog_transition" -> "smoothed_1dca011e",
        "analog_slew" -> "limited_56ecdd40",
        "analog_absdelay" -> "delayed_76095227"
      )
      waveform.waveformOperators.foreach: value =>
        assert(value.path == s"${waveform.root}.${expected(value.operation)}")
        assert(value.stateId.contains(s"${value.path}.state"))
      val induction = waveform.generatedRegions.head.induction
      assert(waveform.names.count(_.semanticPath == induction) == 1)
      assert(!waveform.names.exists(_.semanticPath.startsWith(induction + "_")))
      val scopes = ConstructionKernel.inspect(new DistinctRangeLoopScaffolds)
      assert(scopes == ConstructionKernel.inspect(new DistinctRangeLoopScaffolds))
      assert(scopes.generatedRegions.size == 3, scopes.digitalLoops.size == 3)
      val rangeLoops = scopes.digitalLoops.filter(_.generatedRegion.nonEmpty)
      assert(rangeLoops.size == 2)
      rangeLoops.foreach: region =>
        assert(region.path == s"${region.generatedRegion.get}.digital_loop")
        assert(region.arguments.head.path == s"${region.path}.arg0")
      val identities = scopes.digitalLoops.flatMap(region =>
        region.path +: region.arguments.map(_.path)
      )
      assert(identities.distinct.size == identities.size)
      assert(identities.forall(path => !path.contains("expr_-")))
      assert(scopes.digitalAssignments.map(_.region).toSet == rangeLoops.map(region =>
        Some(region.path)
      ).toSet)

    test("empty singleton and large bounds retain one compact template"):
      val zero = ConstructionKernel.inspect(new FixedSeededLoopSource(0))
      val one = ConstructionKernel.inspect(new FixedSeededLoopSource(1))
      val two = ConstructionKernel.inspect(new FixedSeededLoopSource(2))
      val large = ConstructionKernel.inspect(new FixedSeededLoopSource(1000000))
      assert(Vector(zero, one, two, large).map(_.digitalLoops.head.maximumTripCount) ==
        Vector(0, 1, 2, 1000000))
      assert(two.digitalExpressions.size == large.digitalExpressions.size)
      assert(two.iterationIndices.size == large.iterationIndices.size)
      assert(two.digitalAssignments.size == large.digitalAssignments.size)
      var invocations = 0
      val _ = ConstructionKernel.inspect:
        val module = new LoopCaptureOnce
        invocations = module.invocations
        module
      assert(invocations == 1)

    test("unified range retains digital and independent analog partitions"):
      val mixed = ConstructionKernel.inspect(new PublicMixedRange)
      assert(mixed.digitalLoops.size == 1)
      assert(mixed.generatedRegions.size == 1)
      assert(mixed.analogRegions.nonEmpty)
      assert(mixed.digitalLoops.head.generatedRegion.contains(mixed.generatedRegions.head.path))
      val structural = ConstructionKernel.inspect(new PublicUnusedStructuralIndexRange)
      assert(structural.digitalLoops.isEmpty, structural.iterationIndices.isEmpty)
      assert(structural.parameterExpressions.forall(_.region.isEmpty))
      val strided = ConstructionKernel.inspect(new PublicStridedLoop)
      assert(strided.digitalLoops.head.step == "2")
      assert(strided.digitalAssignments.map(_.sourceOrdinal) == Vector(0, 1))
      val nested = ConstructionKernel.inspect(new PublicNestedCollectionLoop)
      assert(nested.digitalCollections.head.parentRegion.contains(nested.digitalLoops.head.path))
      Vector[() => Module](
        () => new PublicNestedLeftReductionLoop,
        () => new PublicNestedTreeReductionLoop,
        () => new PublicSymbolicNestedTreeReductionLoop
      ).foreach: construct =>
        val reduced = ConstructionKernel.inspect(construct())
        assert(
          reduced.digitalCollections.head.parentRegion.contains(reduced.digitalLoops.head.path)
        )
        assert(reduced.digitalAssignments.size == 1)

    test("static offset DAGs nominal values and canonical module widths retain lexical identities"):
      val offset = ConstructionKernel.inspect(new PublicStaticOffsetLoop)
      val local = ConstructionKernel.inspect(new PublicLocalStaticOffsetLoop)
      val nominal = ConstructionKernel.inspect(new PublicNominalLoop)
      val width = ConstructionKernel.inspect(new PublicSymbolicWidthLoop)
      val negative = ConstructionKernel.inspect(new PublicNegativeOriginLoop)
      assert(offset.iterationIndices.nonEmpty)
      assert(local.parameterExpressions.exists(_.region.contains(local.digitalLoops.head.path)))
      assert(nominal.digitalAssignments.size == 2)
      assert(width.digitalLoops.size == 1)
      assert(negative.digitalLoops.head.lower == "-2")
      val siblings = ConstructionKernel.inspect(new PublicTwoLoopPartitions)
      assert(siblings.digitalLoops.map(_.sourceOrdinal) == Vector(0, 1))
      assert(siblings.digitalAssignments.map(_.sourceOrdinal) == Vector(0, 0))

    test("bounds ownership types and unsupported selectors reject even for empty domains"):
      rejected(new PublicMaxOnlyLoop, "finite declared integer range")
      rejected(new ParameterStepLoop, "parameter-independent fixed DAG")
      rejected(new WrongLoopMaximum, "maximum")
      rejected(new NegativeLoopCount, "non-negative")
      rejected(new EmptyBadLoopType, "exactly matching")
      rejected(new EmptyUnsupportedIndex, "affine add/sub")
      rejected(new LoopInputWrite, "owned wire/output")
      rejected(new WrongLoopIndex, "not in bounds")
      rejected(new LoopDifferentParameter, "not in bounds")
      rejected(new LoopEscapedInduction, "escaped")
      val noModule = scala.util.Try(loop(LoopBound.Static(0))(_ => ())).failed.get
      assert(noModule.isInstanceOf[IllegalStateException])
      assert(noModule.getMessage.contains("loop requires an active Module"))
