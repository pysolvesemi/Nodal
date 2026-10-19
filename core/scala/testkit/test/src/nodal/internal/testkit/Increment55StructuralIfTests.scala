package nodal.internal.testkit

import nodal.*
import nodal_external.*
import utest.*

/** Trusted kernel witnesses check the single transaction independently of callback attestation. */
final class StructuralCaptureOnce extends Module:
  var trueCalls = 0
  var falseCalls = 0
  val MODE = param(0.integer)
  val predicate = ConstructionKernel.staticPredicate("eq", Vector(MODE, 0.integer))
  val _ = ConstructionKernel.structuralIf(predicate, false)(
    () => { trueCalls += 1; None },
    () => { falseCalls += 1; None }
  )

final class StructuralEscapedValue extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val input = in(Bits(8))
  val output = out(Bits(8))
  private var escaped: Expr[Bits] = input
  val predicate = ConstructionKernel.staticPredicate("eq", Vector(MODE, 0.integer))
  val _ = ConstructionKernel.structuralIf(predicate, false)(
    () => { escaped = ~input; None },
    () => None
  )
  output := escaped

final class StructuralCaughtFailure extends Module, StructuralIfClock:
  val MODE = param(0.integer)
  val first = in(Bits(8))
  val second = in(Bits(9))
  val predicate = ConstructionKernel.staticPredicate("eq", Vector(MODE, 0.integer))
  val ignored = scala.util.Try(ConstructionKernel.structuralIf(predicate, true)(
    () => Some(first),
    () => Some(second)
  ))

final class StructuralMissingAttestation extends Module:
  var trueCalls = 0
  var falseCalls = 0
  val MODE = param(0.integer)
  val predicate = ConstructionKernel.staticPredicate("eq", Vector(MODE, 0.integer))
  // Exercise the binary boundary independently of the frontend's authored-call rejection.
  private val callbacks = Class.forName("nodal.DigitalCollectionCallback$")
  private val carrier = callbacks.getMethod("checked0", classOf[Function0[?]]).invoke(
    callbacks.getField("MODULE$").get(()),
    (() => { trueCalls += 1 }): Function0[Unit]
  )
  private val runtime = Class.forName("nodal.StructuralIfRuntime$")
  val ignored = scala.util.Try(runtime.getMethod(
    "statement",
    classOf[StaticPredicate],
    classOf[Function0[?]],
    classOf[Function0[?]]
  ).invoke(
    runtime.getField("MODULE$").get(()),
    predicate,
    carrier,
    (() => { falseCalls += 1 }): Function0[Unit]
  ))

final class StructuralSiblingSelector extends Module, StructuralIfClock:
  val input = in(Vec(UInt(8), 2))
  val output = out(Vec(UInt(8), 2))
  private var escaped: Expr[Integer] = 0.integer
  ConstructionKernel.generatedRegion(0, 2, 1, None): index =>
    val predicate = ConstructionKernel.staticPredicate("eq", Vector(index, 0.integer))
    val _ = ConstructionKernel.structuralIf(predicate, false)(
      () => { escaped = index + 0.integer; None },
      () => { output.writable.at(escaped) := input.at(index); None }
    )

final class StructuralProceduralSiblingSelector extends Module, StructuralIfClock:
  val input = in(Vec(UInt(8), 2))
  val output = out(Vec(UInt(8), 2))
  val MODE = param(0.integer)
  private var escaped: Expr[Integer] = 0.integer
  ConstructionKernel.digitalLoop(LoopBound.Static(2)): index =>
    val predicate = ConstructionKernel.staticPredicate("eq", Vector(MODE, 0.integer))
    val _ = ConstructionKernel.structuralIf(predicate, false)(
      () => { escaped = index + 0.integer; None },
      () => { output.writable.at(escaped) := input.at(index); None }
    )

object Increment55StructuralIfTests extends TestSuite:
  private def rejected(source: => Module, fragment: String): Unit =
    val failure = scala.util.Try(ConstructionKernel.inspect(source)).failed.get
    assert(failure.getMessage.contains(fragment))

  val tests: Tests = Tests:
    test("parameter branches retain two actual yields and full i64 admission"):
      val zero = ConstructionKernel.inspect(new PublicStructuralValue(0))
      val one = ConstructionKernel.inspect(new PublicStructuralValue(1))
      assert(zero.structuralIfs.size == 1, one.structuralIfs.size == 1)
      assert(zero.staticPredicates.map(_.operation) == Vector("eq"))
      val branch = zero.structuralIfs.head
      assert(branch.trueArm.yieldValue.nonEmpty, branch.falseArm.yieldValue.nonEmpty)
      assert(branch.trueArm.yieldValue != branch.falseArm.yieldValue)
      assert(branch.trueArm.path == branch.path + ".then")
      assert(branch.falseArm.path == branch.path + ".else")
      assert(branch.dataType.contains("Bits(8)"))
      Vector(branch.path, branch.predicate).foreach: path =>
        val source = zero.sourceMap.find(_.semanticPath == path).get.source
        assert(source.path.endsWith("Increment55PublicStructuralIf.scala"), source.line > 0)
      assert(zero.digitalExpressions.exists(value =>
        value.path == branch.path &&
          value.operation == "structural_if" && value.operands.head == branch.predicate
      ))
      assert(zero.staticPredicates.size == one.staticPredicates.size)
      assert(zero.digitalExpressions.size == one.digitalExpressions.size)
      val parameter = zero.modules.head.declarations.find(_.name == "MODE").get
      assert(parameter.attributes.contains("classification" -> "structural"))
      assert(!parameter.attributes.exists(_._1.startsWith("integer_range")))

    test("statements and missing else retain explicit alternatives"):
      val writes = ConstructionKernel.inspect(new PublicStructuralWrites)
      val branch = writes.structuralIfs.head
      assert(branch.dataType.isEmpty)
      assert(branch.trueArm.yieldValue.isEmpty, branch.falseArm.yieldValue.isEmpty)
      assert(writes.digitalAssignments.flatMap(_.region).toSet ==
        Set(branch.trueArm.path, branch.falseArm.path))
      val missing = ConstructionKernel.inspect(new PublicStructuralMissingElse)
      assert(missing.structuralIfs.head.falseArm.captures.isEmpty)
      assert(missing.digitalAssignments.size == 1)

    test("nested logic and finite nominal aggregate values are retained"):
      val nested = ConstructionKernel.inspect(new PublicStructuralNested)
      assert(nested.structuralIfs.size == 2)
      assert(nested.staticPredicates.map(_.operation).toSet ==
        Set("eq", "ge", "ne", "not", "and", "or", "lt"))
      val nominal = ConstructionKernel.inspect(new PublicStructuralNominal)
      assert(nominal.structuralIfs.size == 2, nominal.enumDefinitions.size == 1)
      val shaped = ConstructionKernel.inspect(new PublicStructuralBoolVec)
      assert(shaped.structuralIfs.size == 2)

    test("actual Generate induction and body remain compact across lane envelopes"):
      val small = ConstructionKernel.inspect(new PublicStructuralGenerate(2))
      val large = ConstructionKernel.inspect(new PublicStructuralGenerate(1000000))
      assert(small.digitalLoops.isEmpty, large.digitalLoops.isEmpty)
      val generated = small.generatedRegions.head
      assert(generated.digitalBody.nonEmpty)
      assert(generated.digitalBody.get.arguments.head.path == generated.induction)
      assert(small.structuralIfs.forall(_.parentRegion.contains(generated.path)))
      assert(small.staticPredicates.size == large.staticPredicates.size)
      assert(small.digitalExpressions.size == large.digitalExpressions.size)
      assert(small.digitalAssignments.size == large.digitalAssignments.size)
      assert(small.shapeIndices.size == large.shapeIndices.size)
      assert(small.iterationIndices.isEmpty)
      val symbolic = ConstructionKernel.inspect(new PublicStructuralGeneratedValue)
      assert(symbolic.generatedRegions.head.maximumTripCount == 1000000)
      assert(symbolic.digitalLoops.isEmpty)

    test("parameter conditionals in procedural loops retain index captures without static facts"):
      Vector(() => new PublicStructuralLoopValue, () => new PublicStructuralLoopStatement).foreach:
        construct =>
          val snapshot = ConstructionKernel.inspect(construct())
          assert(snapshot.digitalLoops.size == 1, snapshot.structuralIfs.size == 1)
          assert(snapshot.generatedRegions.isEmpty)
          val branch = snapshot.structuralIfs.head
          assert(branch.parentRegion.contains(snapshot.digitalLoops.head.path))
          assert(Vector(branch.trueArm, branch.falseArm).forall(_.arguments.exists(_.role ==
            "index_capture")))
          assert(snapshot.staticPredicates.map(_.operation) == Vector("eq"))
          val index = branch.trueArm.arguments.find(_.role == "index_capture").get
          val parent = new StructuralParameterBinding.Query(snapshot)
          val constant = parent.exportValues(Vector(parent.resolve(snapshot.root, "0")))
          val forged = scala.util.Try(new StructuralParameterBinding.Query(
            snapshot,
            scope = Some(branch.trueArm.path),
            exact = Map(index.path -> constant),
            enclosing = Some(parent)
          )).failed.get
          assert(
            forged.getMessage.contains("Integer binding belongs to another lexical scope or type")
          )

    test("empty Generate retains the authored predicate without inventing a lane"):
      val empty = ConstructionKernel.inspect(new PublicStructuralEmptyGenerate)
      assert(empty.generatedRegions.head.maximumTripCount == 0)
      assert(empty.structuralIfs.size == 1, empty.staticPredicates.size == 1)
      assert(empty.digitalLoops.isEmpty)

    test("reduction alternatives retain exact type formula and raw static captures"):
      val snapshot = ConstructionKernel.inspect(new PublicStructuralReduction)
      assert(
        snapshot.digitalCollections.count(_.kind == "reduce_left") == 1,
        snapshot.structuralIfs.size == 1
      )
      val branch = snapshot.structuralIfs.head
      assert(branch.trueArm.yieldValue.nonEmpty, branch.falseArm.yieldValue.nonEmpty)
      assert(branch.parentRegion.nonEmpty)
      assert(snapshot.staticPredicates.map(_.operation) == Vector("eq"))

    test("both arm templates execute once and host Boolean if stays ordinary"):
      var counts = (0, 0)
      val _ = ConstructionKernel.inspect:
        val module = new StructuralCaptureOnce
        counts = module.trueCalls -> module.falseCalls
        module
      assert(counts == (1, 1))
      var hostCalls = 0
      val host = ConstructionKernel.inspect:
        val module = new PublicStructuralHostControl
        hostCalls = module.calls
        module
      assert(hostCalls == 1, host.structuralIfs.isEmpty, host.staticPredicates.isEmpty)

    test("both callback attestations are checked before either arm and poison on failure"):
      var counts = (-1, -1)
      var original = ""
      val failure = scala.util.Try(ConstructionKernel.inspect:
        val module = new StructuralMissingAttestation
        counts = module.trueCalls -> module.falseCalls
        original = module.ignored.failed.get.getCause.getMessage
        module).failed.get
      assert(counts == (0, 0))
      assert(original.contains("NODAL-STRUCTURAL-055-MISSING"))
      assert(failure.getMessage.contains("NODAL-CONSTRUCTOR-LIFECYCLE-021"))

    test("wrong ABI escaped branch values procedural stage and caught failures reject"):
      rejected(new PublicStructuralWrongWidth, "same complete hardware ABI")
      rejected(new PublicStructuralReductionWrongABI, "alternative ABI changes")
      rejected(new StructuralEscapedValue, "escaped")
      rejected(new PublicStructuralProceduralIndex, "procedural")
      rejected(new StructuralSiblingSelector, "escaped")
      rejected(new StructuralProceduralSiblingSelector, "escaped")
      var original = ""
      val caught = scala.util.Try(ConstructionKernel.inspect:
        val module = new StructuralCaughtFailure
        original = module.ignored.failed.get.getMessage
        module).failed.get
      assert(original.contains("same complete hardware ABI"))
      assert(caught.getMessage.contains("NODAL-CONSTRUCTOR-LIFECYCLE-021"))
