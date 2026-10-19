package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

final class StructuralIfCollectionBridgeSource extends Module, StructuralIfClock:
  val mode = param(0.integer)
  val input = in(Vec(UInt(8), 2))
  val output = out(Vec(UInt(8), 2))
  output := input.map(value => if mode == 0 then ~value else value)

final class StructuralIfEmptyCollectionBridgeSource extends Module, StructuralIfClock:
  val input = in(Vec(UInt(8), 2))
  hdlRange(0, 0): index =>
    val mapped = input.map(value => if index == 0 then ~value else value)
    val _ = mapped

final class StructuralIfValueEffectsBridgeSource extends Module, StructuralIfClock:
  val mode = param(0.integer)
  val first = in(Bits(8))
  val second = in(Bits(8))
  val before = out(Bits(8))
  val branchOutput = out(Bits(8))
  val after = out(Bits(8))
  before := first
  val selected: Expr[Bits] =
    if mode == 0 then
      branchOutput := first
      ~first
    else
      branchOutput := second
      ~second
  after := selected

object Increment55StructuralIfBridgeTests extends TestSuite:
  private def rejected(snapshot: ConstructionSnapshot, fragment: String): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(error.isInstanceOf[BridgeException])
    assert(error.getMessage.contains(fragment))

  val tests: Tests = Tests:
    test("statement and exact value alternatives retain real predicates and both yields"):
      val cases: Vector[() => Module] = Vector(
        () => new PublicStructuralValue(0),
        () => new PublicStructuralValue(1),
        () => new StructuralIfValueEffectsBridgeSource,
        () => new PublicStructuralWrites,
        () => new PublicStructuralNested,
        () => new PublicStructuralNominal,
        () => new PublicStructuralBoolVec,
        () => new PublicStructuralMissingElse,
        () => new PublicStructuralBranchCycle,
        () => new PublicStructuralReduction,
        () => new StructuralIfCollectionBridgeSource
      )
      cases.foreach: source =>
        val snapshot = ConstructionKernel.inspect(source())
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(text.linesIterator.count(_.contains("\"nodal.structural_if\"(")) ==
          snapshot.structuralIfs.size)
        assert(text.linesIterator.count(_.contains("\"nodal.structural_yield\"(")) ==
          snapshot.structuralIfs.size * 2)
        assert(text.contains("!nodal.static_predicate"))
        assert(text.contains("nodal.digital.quality"))
        assert(text.contains("contract_version = \"structural-if-v1\""))
        snapshot.structuralIfs.foreach: conditional =>
          Vector(conditional.trueArm, conditional.falseArm).foreach: arm =>
            assert(text.contains(s"semantic_path = \"${arm.path}.yield\""))
        assert(text == ScalaToMlirBridge.fromSnapshot(snapshot).text)
        val reversed = snapshot.copy(
          staticPredicates = snapshot.staticPredicates.reverse,
          structuralIfs = snapshot.structuralIfs.reverse,
          digitalExpressions = snapshot.digitalExpressions.reverse,
          digitalAssignments = snapshot.digitalAssignments.reverse
        )
        assert(text == ScalaToMlirBridge.fromSnapshot(reversed).text)
        val canonical = ReproducibilityContract.canonicalSnapshot(snapshot)
        assert(canonical.contains("\"structural_ifs\""))
        assert(canonical.contains("\"static_predicates\""))
        assert(canonical == ReproducibilityContract.canonicalSnapshot(reversed))

    test("value alternatives with writes retain parent and arm effect order"):
      val snapshot = ConstructionKernel.inspect(new StructuralIfValueEffectsBridgeSource)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      val conditional = snapshot.structuralIfs.head
      val parentWrites =
        snapshot.digitalAssignments.filter(_.region.isEmpty).sortBy(_.sourceOrdinal)
      assert(parentWrites.size == 2)
      assert(parentWrites.head.sourceOrdinal < conditional.sourceOrdinal)
      assert(conditional.sourceOrdinal < parentWrites.last.sourceOrdinal)
      val lines = text.linesIterator.toVector
      def writePosition(path: String): Int =
        lines.indexWhere(line =>
          line.contains("\"nodal.digital_assign\"(") &&
            line.contains(s"semantic_path = \"$path\"")
        )
      val choicePosition = lines.indexWhere(_.contains("\"nodal.structural_if\"("))
      assert(writePosition(parentWrites.head.path) >= 0)
      assert(writePosition(parentWrites.head.path) < choicePosition)
      assert(choicePosition < writePosition(parentWrites.last.path))
      Vector(conditional.trueArm, conditional.falseArm).foreach: arm =>
        val writes = snapshot.digitalAssignments.filter(_.region.contains(arm.path))
        assert(writes.size == 1)
        val yieldPosition = lines.indexWhere(_.contains(s"semantic_path = \"${arm.path}.yield\""))
        assert(writePosition(writes.head.path) > choicePosition)
        assert(writePosition(writes.head.path) < yieldPosition)

    test("generated lane choices serialize actual Generate induction and outer captures"):
      val cases: Vector[() => Module] = Vector(
        () => new PublicStructuralGenerate(3),
        () => new PublicStructuralGeneratedValue,
        () => new PublicStructuralEmptyGenerate,
        () => new StructuralIfEmptyCollectionBridgeSource
      )
      cases.foreach: source =>
        val snapshot = ConstructionKernel.inspect(source())
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        val generated = snapshot.generatedRegions.filter(_.digitalBody.nonEmpty)
        assert(generated.size == 1)
        assert(snapshot.digitalLoops.isEmpty)
        assert(text.linesIterator.count(_.contains("\"nodal.generate\"(")) == 1)
        assert(!text.contains("\"nodal.hardware_loop\"("))
        assert(!text.contains("\"nodal.hardware_loop_end\"("))
        assert(text.contains(s"induction_path = \"${generated.head.induction}\""))
        val header = text.linesIterator.find(_.contains("^bb0(%generated_index_")).get
        assert(header.count(_ == ':') == 2)
        assert(text == ScalaToMlirBridge.fromSnapshot(snapshot).text)
        assert(
          ReproducibilityContract.canonicalSnapshot(snapshot).contains("\"digital_generates\"")
        )

    test("parameter choices in procedural loops retain selector provenance and ordered scope"):
      Vector[() => Module](
        () => new PublicStructuralLoopValue,
        () => new PublicStructuralLoopStatement
      ).foreach: create =>
        val snapshot = ConstructionKernel.inspect(create())
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(snapshot.generatedRegions.isEmpty)
        assert(snapshot.digitalLoops.size == 1)
        assert(text.linesIterator.count(_.contains("\"nodal.hardware_loop\"(")) == 1)
        assert(text.linesIterator.count(_.contains("\"nodal.structural_if\"(")) == 1)
        assert(!text.contains("\"nodal.generate\"("))
        val boundProducers = text.linesIterator.filter(_.contains("use = \"loop_bound\"")).toVector
        assert(boundProducers.nonEmpty)
        assert(boundProducers.forall(_.contains("semantic_path =")))
        val conditional = snapshot.structuralIfs.head
        assert(Vector(conditional.trueArm, conditional.falseArm).exists(_.arguments.exists(_.role ==
          "index_capture")))
        val predicate = snapshot.staticPredicates.head
        val loop = snapshot.digitalLoops.head
        rejected(
          snapshot.copy(staticPredicates =
            snapshot.staticPredicates.map(value =>
              if value.path == predicate.path then
                value.copy(operands = value.operands.updated(0, loop.arguments.head.path))
              else value
            )
          ),
          "procedural induction"
        )
        val arm = conditional.trueArm
        val ordinal = arm.arguments.indexWhere(_.role == "index_capture")
        rejected(
          snapshot.copy(structuralIfs =
            snapshot.structuralIfs.map(value =>
              value.copy(trueArm =
                arm.copy(arguments =
                  arm.arguments.updated(
                    ordinal,
                    arm.arguments(ordinal).copy(role = "static_capture")
                  )
                )
              )
            )
          ),
          "exact capture role"
        )

    test("snapshot validation rejects predicate stage and lexical edge reroutes"):
      val snapshot = ConstructionKernel.inspect(new PublicStructuralValue(0))
      val conditional = snapshot.structuralIfs.head
      val predicate = snapshot.staticPredicates.head
      val input = snapshot.modules.head.declarations.find(_.kind == "input").get.path
      rejected(
        snapshot.copy(staticPredicates =
          snapshot.staticPredicates.map(value =>
            if value.path == predicate.path then
              value.copy(operands = value.operands.updated(0, input))
            else value
          )
        ),
        "wrong stage or type"
      )
      rejected(
        snapshot.copy(staticPredicates =
          snapshot.staticPredicates.map(value =>
            if value.path == predicate.path then value.copy(operation = "sampled") else value
          )
        ),
        "closed comparison"
      )
      rejected(
        snapshot.copy(structuralIfs =
          snapshot.structuralIfs.map(value =>
            value.copy(
              trueArm = value.trueArm.copy(yieldValue = value.falseArm.yieldValue)
            )
          )
        ),
        "exact lexical scope"
      )
      rejected(
        snapshot.copy(structuralIfs =
          snapshot.structuralIfs.map(value =>
            value.copy(
              falseArm = value.falseArm.copy(yieldValue = None)
            )
          )
        ),
        "yield arity"
      )
      rejected(
        snapshot.copy(structuralIfs =
          snapshot.structuralIfs.map(value =>
            value.copy(
              predicate = input
            )
          )
        ),
        "owned static predicate"
      )
      rejected(
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(value =>
            if value.path == conditional.path then value.copy(operands = value.operands.reverse)
            else value
          )
        ),
        "actual predicate"
      )

    test("snapshot validation rejects nominal ABI changes and duplicate source identities"):
      val snapshot = ConstructionKernel.inspect(new PublicStructuralNominal)
      val conditional = snapshot.structuralIfs.find(_.dataType.exists(_.startsWith("Struct("))).get
      val changed = conditional.dataType.get.replace("ConditionalPayload", "ForgedPayload")
      val rerouted = snapshot.copy(
        structuralIfs = snapshot.structuralIfs.map(value =>
          if value.path == conditional.path then value.copy(dataType = Some(changed)) else value
        ),
        digitalExpressions = snapshot.digitalExpressions.map(value =>
          if value.path == conditional.path then value.copy(dataType = changed) else value
        )
      )
      rejected(rerouted, "exact finite read-only ABI")
      val named = snapshot.names.find(value =>
        value.category == "expression" && value.semanticPath == conditional.path
      ).get
      rejected(snapshot.copy(names = snapshot.names :+ named), "one existing canonical name")
      val plain = ConstructionKernel.inspect(new PublicStructuralWrites)
      rejected(
        plain.copy(staticPredicates = plain.staticPredicates ++ plain.staticPredicates),
        "duplicate or excessive"
      )

    test("structural result remains read-only and actual capture uses ignore source metadata"):
      val value = ConstructionKernel.inspect(new PublicStructuralValue(0))
      val join = value.structuralIfs.head.path
      rejected(
        value.copy(digitalAssignments = value.digitalAssignments.map(_.copy(target = join))),
        "target requires an owned declaration"
      )
      val snapshot = ConstructionKernel.inspect(new PublicStructuralWrites)
      val module = snapshot.modules.head
      val extra = module.declarations.find(_.kind == "input").get.copy(
        path = module.path + ".unusedInput",
        name = "unusedInput"
      )
      val conditional = snapshot.structuralIfs.head
      val arm = conditional.trueArm
      val argument = KernelDigitalRegionArgument(
        s"${arm.path}.arg${arm.arguments.size}",
        extra.dataType.get,
        "capture"
      )
      val assignment = snapshot.digitalAssignments.find(_.region.contains(arm.path)).get
      val spellings = (0 to 20).map(index => s"%structural_arm_${index}_arg${arm.arguments.size}")
        .mkString("_") + ".scala"
      rejected(
        snapshot.copy(
          modules = Vector(module.copy(declarations = module.declarations :+ extra)),
          structuralIfs = Vector(conditional.copy(trueArm =
            arm.copy(
              captures = arm.captures :+ extra.path,
              arguments = arm.arguments :+ argument
            )
          )),
          sourceMap = snapshot.sourceMap.filterNot(_.semanticPath == assignment.path) :+
            SourceMapEntry(assignment.path, SourceSpan(spellings, 1, 1, 1, 1))
        ),
        "no actual body SSA or type use"
      )

    test("actual Generate snapshots retain bounds induction and lexical capture identity"):
      val snapshot = ConstructionKernel.inspect(new PublicStructuralGenerate(3))
      val generated = snapshot.generatedRegions.find(_.digitalBody.nonEmpty).get
      rejected(
        snapshot.copy(generatedRegions =
          snapshot.generatedRegions.map(value =>
            if value.path == generated.path then value.copy(induction = value.path + ".forged")
            else value
          )
        ),
        "induction"
      )
      val body = generated.digitalBody.get
      rejected(
        snapshot.copy(generatedRegions =
          snapshot.generatedRegions.map(value =>
            if value.path == generated.path then
              value.copy(digitalBody =
                Some(body.copy(
                  arguments =
                    body.arguments.updated(0, body.arguments.head.copy(role = "static_capture"))
                ))
              )
            else value
          )
        ),
        "induction"
      )

    test("reduction alternatives validate actual arithmetic in every width context"):
      val snapshot = ConstructionKernel.inspect(new PublicStructuralReduction)
      val conditional = snapshot.structuralIfs.head
      val addition = snapshot.digitalExpressions.find(value =>
        value.region.contains(conditional.falseArm.path) && value.operation == "digital_add"
      ).get
      // Left and right start at width 4. Later left reaches 5: right+right no longer
      // has the retained max(left,right)+1 ABI, even though the initial context agrees.
      val retainedLeft = addition.copy(
        path = conditional.falseArm.path + ".retained_left",
        operation = "digital_not",
        operands = Vector(addition.operands.head),
        dataType =
          conditional.falseArm.arguments.find(_.path == addition.operands.head).get.dataType
      )
      rejected(
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(value =>
            if value.path == addition.path then
              value.copy(operands = Vector(value.operands(1), value.operands(1)))
            else value
          ) :+ retainedLeft
        ),
        "violates exact kind, width or static range proof"
      )

    test("configured native independently validates actual structural source alternatives"):
      sys.env.get("NODAL_NODALC") match
        case None => println("SKIP structural-if native replay: NODAL_NODALC is not configured")
        case Some(executable) =>
          val directory = Path.of(".work", "increment55-structural-if-bridge").toAbsolutePath
          Files.createDirectories(directory)
          val request = NativeCompilerRequest(
            Path.of(executable).toAbsolutePath,
            Vector(
              "--pass-pipeline=builtin.module(nodal-plan-digital-loops,nodal-plan-digital-materialization)"
            ),
            directory,
            timeout = Duration.ofSeconds(90)
          )
          val positives: Vector[(String, () => Module)] = Vector(
            "value-default0" -> (() => new PublicStructuralValue(0)),
            "value-default1" -> (() => new PublicStructuralValue(1)),
            "value-effects" -> (() => new StructuralIfValueEffectsBridgeSource),
            "statement" -> (() => new PublicStructuralWrites),
            "nested" -> (() => new PublicStructuralNested),
            "nominal" -> (() => new PublicStructuralNominal),
            "bool-vec" -> (() => new PublicStructuralBoolVec),
            "generate" -> (() => new PublicStructuralGenerate(3)),
            "symbolic-generate" -> (() => new PublicStructuralGeneratedValue),
            "empty-generate" -> (() => new PublicStructuralEmptyGenerate),
            "map" -> (() => new StructuralIfCollectionBridgeSource),
            "empty-map" -> (() => new StructuralIfEmptyCollectionBridgeSource),
            "reduction" -> (() => new PublicStructuralReduction),
            "loop-value" -> (() => new PublicStructuralLoopValue),
            "loop-statement" -> (() => new PublicStructuralLoopStatement),
            "branch-cycle" -> (() => new PublicStructuralBranchCycle)
          )
          positives.foreach: (name, source) =>
            val document = ScalaToMlirBridge.lower(source())
            Files.writeString(directory.resolve(name + ".source.mlir"), document.text)
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                Files.writeString(directory.resolve(name + ".planned.mlir"), success.normalizedMlir)
                assert(success.normalizedMlir.contains("nodal.structural_if"))
                assert(success.normalizedMlir.contains("nodal.structural_yield"))
                assert(success.normalizedMlir.contains("structural_choices"))
                assert(success.normalizedMlir.contains("digital-ssa-v2"))
                if name == "statement" then
                  assert(success.normalizedMlir.contains("decisions = []"))
              case failure: NativeCompilerFailure =>
                scala.util.Failure[Nothing](new java.lang.AssertionError(
                  s"$name: ${failure.diagnostic}\n${failure.standardError}"
                )).get
          val negatives: Vector[(String, () => Module)] = Vector(
            "missing-else" -> (() => new PublicStructuralMissingElse),
            "duplicate" -> (() => new PublicStructuralDuplicate),
            "independent" -> (() => new PublicStructuralIndependent),
            "cross-lane" -> (() => new PublicStructuralCrossLane),
            "feasible-cycle" -> (() => new PublicStructuralFeasibleCycle)
          )
          negatives.foreach: (name, source) =>
            val document = ScalaToMlirBridge.lower(source())
            Files.writeString(directory.resolve(name + ".source.mlir"), document.text)
            NativeCompilerClient.run(document, request) match
              case failure: NativeCompilerFailure =>
                Files.writeString(directory.resolve(name + ".stderr"), failure.standardError)
                assert(failure.standardError.contains("NODAL-DIGITAL-055-010"))
              case _: NativeCompilerSuccess =>
                scala.util.Failure[Nothing](new java.lang.AssertionError(
                  s"native unexpectedly accepted $name"
                )).get
