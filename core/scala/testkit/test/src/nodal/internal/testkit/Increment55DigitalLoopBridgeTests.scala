package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

final class LoopStaticCaptureBridgeSource extends Module, BoundedLoopClock:
  val offset = 1.integer
  val input = in(Vec(UInt(8), 4))
  val scratch = wire(Vec(UInt(8), 4))
  loop(LoopBound.Static(3)): index =>
    val value = input.at(index.value)
    scratch.writable.at(index.value + offset) := value

final class LoopLiteralProofBridgeSource extends Module, BoundedLoopClock:
  val positive = 3.S(4)
  val scratch = wire(Vec(UInt(4), 2))
  loop(LoopBound.Static(2)): index =>
    scratch.writable.at(index.value) := positive.toUnsigned

object Increment55DigitalLoopBridgeTests extends TestSuite:
  private def rejected(snapshot: ConstructionSnapshot, fragment: String): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(error.isInstanceOf[BridgeException])
    assert(error.getMessage.contains(fragment))

  private def changeLoop(snapshot: ConstructionSnapshot)(
      update: KernelDigitalLoopSnapshot => KernelDigitalLoopSnapshot
  ): ConstructionSnapshot = snapshot.copy(digitalLoops = snapshot.digitalLoops.map(update))

  val tests: Tests = Tests:
    test("public static symbolic empty strided and nested templates serialize actual SSA"):
      val cases: Vector[() => Module] = Vector(
        () => new PublicIndependentLoop,
        () => new PublicSymbolicLoop,
        () => new PublicEmptyLoop,
        () => new PublicStridedLoop,
        () => new PublicNestedCollectionLoop,
        () => new PublicNestedLeftReductionLoop,
        () => new PublicNestedTreeReductionLoop,
        () => new PublicSymbolicNestedTreeReductionLoop,
        () => new PublicStaticOffsetLoop,
        () => new PublicNominalLoop,
        () => new PublicNegativeOriginLoop,
        () => new PublicSymbolicWidthLoop
      )
      cases.foreach: source =>
        val snapshot = ConstructionKernel.inspect(source())
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(text.linesIterator.count(_.contains("\"nodal.hardware_loop\"(")) == 1)
        assert(text.contains("contract_version = \"bounded-digital-v1\""))
        assert(text.contains("effect_policy = \"ordered\""))
        assert(text.contains("\"nodal.hardware_loop_end\"()"))
        assert(
          text.contains(s"induction_path = \"${snapshot.digitalLoops.head.arguments.head.path}\"")
        )
        assert(text == ScalaToMlirBridge.fromSnapshot(snapshot).text)
      val nested = ScalaToMlirBridge.lower(new PublicNestedCollectionLoop).text
      assert(nested.indexOf("\"nodal.hardware_loop\"(") < nested.indexOf("\"nodal.collection\"("))

    test("static captures preserve identity actual first-use order and scalar proofs"):
      val snapshot = ConstructionKernel.inspect(new LoopStaticCaptureBridgeSource)
      val loop = snapshot.digitalLoops.head
      assert(loop.arguments(2).role == "static_capture")
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      val block = text.linesIterator.find(line => line.contains("^bb0(%loop_")).get
      // Source captures the precomputed RHS first; the actual assignment emits the offset
      // before that RHS. Keep operand/argument pairing when canonicalizing transport.
      assert(block.indexOf("_arg2:") < block.indexOf("_arg1:"))
      assert(text.contains("\"nodal.iteration_index\"("))
      assert(ScalaToMlirBridge.lower(
        new LoopLiteralProofBridgeSource
      ).text.contains("digital_to_unsigned"))
      val local = ConstructionKernel.inspect(new PublicLocalStaticOffsetLoop)
      assert(ScalaToMlirBridge.fromSnapshot(local).text.contains("\"nodal.const_expr\"("))
      val staticExpression = local.parameterExpressions.find(value =>
        value.region.nonEmpty && value.literal.isEmpty
      ).get
      rejected(
        local.copy(parameterExpressions =
          local.parameterExpressions.map(value =>
            if value.path == staticExpression.path then
              value.copy(operands =
                value.operands.updated(0, local.digitalLoops.head.arguments.head.path)
              )
            else value
          )
        ),
        "never induction"
      )

    test("seed loop and final write retain actual module and body effect order"):
      val snapshot = ConstructionKernel.inspect(new PublicSeededLoop)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      val effects = text.linesIterator.filter(line =>
        line.contains("\"nodal.digital_assign\"(") || line.contains("\"nodal.hardware_loop\"(")
      ).toVector
      assert(effects.size == 4)
      assert(effects(0).contains("source_ordinal = 0 : i64"))
      assert(effects(1).contains("source_ordinal = 1 : i64"))
      assert(effects(2).contains("source_ordinal = 0 : i64"))
      assert(effects(3).contains("source_ordinal = 2 : i64"))
      assert(text.contains("\"nodal.iteration_index\"("))
      val loopBody = text.substring(
        text.indexOf("\"nodal.hardware_loop\"("),
        text.indexOf("\"nodal.hardware_loop_end\"()")
      )
      assert(!loopBody.contains("\"nodal.const_expr\"("))
      val reversedInventory =
        snapshot.copy(digitalAssignments = snapshot.digitalAssignments.reverse)
      assert(ScalaToMlirBridge.fromSnapshot(reversedInventory).text == text)
      rejected(changeLoop(snapshot)(_.copy(sourceOrdinal = 0)), "distinct source ordinals")
      val siblings = ScalaToMlirBridge.lower(new PublicTwoLoopPartitions).text
      val siblingEffects =
        siblings.linesIterator.filter(_.contains("\"nodal.hardware_loop\"(")).toVector
      assert(siblingEffects.size == 2)
      assert(siblingEffects(0).contains("source_ordinal = 0 : i64"))
      assert(siblingEffects(1).contains("source_ordinal = 1 : i64"))
      val strided = ConstructionKernel.inspect(new PublicStridedLoop)
      rejected(
        strided.copy(digitalAssignments =
          strided.digitalAssignments.map(
            _.copy(sourceOrdinal = 0)
          )
        ),
        "contiguous from zero"
      )

    test("actual bound DAGs reject forged envelope maximum step and induction header"):
      val snapshot = ConstructionKernel.inspect(new PublicIndependentLoop)
      rejected(changeLoop(snapshot)(_.copy(maximumTripCount = 3)), "trip envelope")
      rejected(changeLoop(snapshot)(_.copy(maximum = Some(3))), "maximum")
      rejected(changeLoop(snapshot)(_.copy(step = "0")), "step")
      rejected(changeLoop(snapshot)(_.copy(step = "-1")), "step")
      rejected(changeLoop(snapshot)(_.copy(lower = "1", maximumTripCount = 3)), "explicit loop")
      rejected(
        changeLoop(snapshot)(_.copy(upperExclusive = "-1", maximumTripCount = 0)),
        "nonnegative count"
      )
      rejected(
        changeLoop(snapshot)(loop => loop.copy(upperExclusive = loop.arguments.head.path)),
        "exact lexical scope"
      )
      val symbolic = ConstructionKernel.inspect(new PublicSymbolicLoop)
      val range = ConstructionKernel.inspect(new PublicMixedRange)
      val stepPath = range.root + ".singletonStep"
      val singleton = symbolic.modules.head.declarations.find(_.kind == "parameter").get.copy(
        path = stepPath,
        name = "singletonStep",
        attributes = Vector(
          "classification" -> "structural",
          "integer_range_lower" -> "1",
          "integer_range_upper" -> "1",
          "structural_effects" -> "generate"
        )
      )
      rejected(
        range.copy(
          modules = range.modules.map(module =>
            module.copy(declarations = module.declarations :+ singleton)
          ),
          digitalLoops = range.digitalLoops.map(_.copy(step = stepPath)),
          generatedRegions = range.generatedRegions.map(_.copy(step = stepPath))
        ),
        "parameter-free invariant"
      )
      rejected(
        symbolic.copy(modules =
          symbolic.modules.map(module =>
            module.copy(
              declarations = module.declarations.map(value =>
                if value.kind == "parameter" then
                  value.copy(attributes =
                    value.attributes.filterNot(pair =>
                      Set("integer_range_lower", "integer_range_upper").contains(pair._1)
                    )
                  )
                else value
              )
            )
          )
        ),
        "nonnegative count"
      )

    test("induction capture arity exact type provenance and lexical escape reject"):
      val snapshot = ConstructionKernel.inspect(new PublicIndependentLoop)
      rejected(
        changeLoop(snapshot)(loop => loop.copy(arguments = loop.arguments.tail)),
        "induction"
      )
      rejected(
        changeLoop(snapshot)(loop =>
          loop.copy(arguments =
            loop.arguments.updated(
              0,
              loop.arguments.head.copy(dataType = "UInt(8)")
            )
          )
        ),
        "canonical identity"
      )
      rejected(
        changeLoop(snapshot)(loop =>
          loop.copy(arguments =
            loop.arguments.updated(
              1,
              loop.arguments(1).copy(role = "induction")
            )
          )
        ),
        "canonical identity"
      )
      rejected(
        changeLoop(snapshot)(loop => loop.copy(captures = loop.captures :+ loop.captures.head)),
        "captures"
      )
      val loop = snapshot.digitalLoops.head
      val projection = snapshot.shapeIndices.find(_.region.contains(loop.path)).get
      rejected(
        snapshot.copy(shapeIndices =
          snapshot.shapeIndices.map(value =>
            if value.path == projection.path then value.copy(input = loop.captures.head) else value
          )
        ),
        "exact lexical scope"
      )
      rejected(
        snapshot.copy(digitalAssignments =
          snapshot.digitalAssignments.map(_.copy(region = None, sourceOrdinal = 1))
        ),
        "exact lexical scope"
      )
      rejected(
        snapshot.copy(digitalAssignments =
          snapshot.digitalAssignments.map(value =>
            value.copy(target = value.value)
          )
        ),
        "sink kind"
      )

    test("source-location text cannot impersonate an actual capture use"):
      val snapshot = ConstructionKernel.inspect(new PublicIndependentLoop)
      val module = snapshot.modules.head
      val extra = module.declarations.find(_.kind == "input").get.copy(
        path = module.path + ".unusedInput",
        name = "unusedInput"
      )
      val loop = snapshot.digitalLoops.head
      val assignment = snapshot.digitalAssignments.head
      rejected(
        snapshot.copy(
          modules = Vector(module.copy(declarations = module.declarations :+ extra)),
          digitalLoops = Vector(loop.copy(
            captures = loop.captures :+ extra.path,
            arguments = loop.arguments :+ KernelDigitalRegionArgument(
              loop.path + ".arg3",
              extra.dataType.get,
              "capture"
            )
          )),
          sourceMap = snapshot.sourceMap.filterNot(_.semanticPath == assignment.path) :+
            SourceMapEntry(assignment.path, SourceSpan("%loop_3_arg3.scala", 1, 1, 1, 1))
        ),
        "no actual body SSA use"
      )

    test("iteration arithmetic requires actual induction first for subtraction and static offsets"):
      val snapshot = ConstructionKernel.inspect(new PublicSeededLoop)
      val index = snapshot.iterationIndices.head
      rejected(snapshot.copy(iterationIndices = Vector(index.copy(operation = "mul"))), "affine")
      rejected(
        snapshot.copy(iterationIndices =
          Vector(index.copy(
            operation = "sub",
            operands = index.operands.reverse
          ))
        ),
        "induction plus/minus"
      )
      rejected(
        snapshot.copy(iterationIndices =
          Vector(index.copy(operands =
            Vector(index.operands.head, index.operands.head)
          ))
        ),
        "induction plus/minus"
      )
      rejected(
        snapshot.copy(iterationIndices = Vector(index.copy(region = snapshot.root + ".absent"))),
        "exact loop owner"
      )
      val forged = KernelParameterExpressionSnapshot(
        index.path,
        index.owner,
        "analog_add",
        index.operands,
        "Integer",
        None,
        Some(""),
        Some(index.region)
      )
      rejected(
        snapshot.copy(
          iterationIndices = Vector.empty,
          parameterExpressions = snapshot.parameterExpressions :+ forged
        ),
        "loop-local statics"
      )

    test("empty loops still validate types effects argument identity and ordinal sequence"):
      val snapshot = ConstructionKernel.inspect(new PublicEmptyLoop)
      assert(snapshot.digitalLoops.head.maximumTripCount == 0)
      rejected(
        changeLoop(snapshot)(loop =>
          loop.copy(arguments =
            loop.arguments.updated(
              0,
              loop.arguments.head.copy(path = loop.path + ".fake")
            )
          )
        ),
        "canonical identity"
      )
      rejected(
        snapshot.copy(digitalAssignments =
          snapshot.digitalAssignments.map(value =>
            value.copy(kind = "register_next")
          )
        ),
        "sink kind"
      )
      rejected(
        snapshot.copy(digitalAssignments =
          snapshot.digitalAssignments.map(value =>
            value.copy(sourceOrdinal = 1)
          )
        ),
        "contiguous from zero"
      )
      val unused = KernelDigitalExpressionSnapshot(
        snapshot.root + ".unused_effect",
        snapshot.root,
        "digital_external_latency",
        Vector.empty,
        "UInt(8)",
        None,
        None,
        Vector.empty,
        region = Some(snapshot.digitalLoops.head.path)
      )
      rejected(
        snapshot.copy(digitalExpressions = snapshot.digitalExpressions :+ unused),
        "exact opcode"
      )
      val index = snapshot.shapeIndices.head
      rejected(
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(value =>
            if value.path == index.path then value.copy(dataType = "Real") else value
          )
        ),
        "finite digital values"
      )

    test("range partition identity and loop provenance enter reproducibility"):
      val snapshot = ConstructionKernel.inspect(new PublicMixedRange)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.contains("\"nodal.generate\"("))
      assert(text.contains("\"nodal.hardware_loop\"("))
      val canonical = ReproducibilityContract.canonicalSnapshot(snapshot)
      assert(canonical.contains("\"generated_region\""))
      assert(canonical.contains("\"source_ordinal\""))
      assert(canonical.contains("\"loops\""))
      assert(canonical.contains("\"iteration_indices\""))
      rejected(
        changeLoop(snapshot)(_.copy(generatedRegion = Some("absent"))),
        "exact source domain"
      )
      rejected(
        snapshot.copy(generatedRegions =
          snapshot.generatedRegions.map(value =>
            value.copy(parent = Some(snapshot.root + ".outer"))
          )
        ),
        "exact source domain"
      )

    test("explicit and unified loops retain deterministic actual source spans and owners"):
      val structural = ConstructionKernel.inspect(new PublicUnusedStructuralIndexRange)
      assert(structural.digitalLoops.isEmpty && structural.iterationIndices.isEmpty)
      val structuralText = ScalaToMlirBridge.fromSnapshot(structural).text
      assert(structuralText.contains("\"nodal.generate\"("))
      assert(!structuralText.contains("\"nodal.hardware_loop\"("))
      val explicit = ConstructionKernel.inspect(new PublicIndependentLoop)
      val explicitLoop = explicit.digitalLoops.head
      val authored = explicit.sourceMap.find(_.semanticPath == explicitLoop.path).get.source
      assert(authored.path.endsWith("Increment55PublicBoundedLoops.scala"))
      assert(authored.line > 0 && authored.column > 0)
      val mixed = ConstructionKernel.inspect(new PublicMixedRange)
      val loop = mixed.digitalLoops.head
      val generated =
        mixed.generatedRegions.find(value => loop.generatedRegion.contains(value.path)).get
      val rangeSource = mixed.sourceMap.find(_.semanticPath == generated.induction).get.source
      val fallback = mixed.copy(sourceMap = mixed.sourceMap.filterNot(_.semanticPath == loop.path))
      Vector((explicit, explicitLoop, authored), (fallback, loop, rangeSource)).foreach:
        (snapshot, region, source) =>
          assert(region.owner == snapshot.root)
          val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
          val header = text.linesIterator.find(_.contains("\"nodal.hardware_loop\"(")).get
          assert(header.contains(s"semantic_path = \"${region.path}\""))
          assert(header.contains(s"source_end_line = ${source.endLine} : i64"))
          val afterEnd = text.substring(text.indexOf("\"nodal.hardware_loop_end\"()"))
          val location = s"loc(\"${source.path}\":${source.line}:${source.column})"
          assert(afterEnd.linesIterator.drop(1).next().contains(location))
          assert(ScalaToMlirBridge.fromSnapshot(snapshot.copy(sourceMap =
            snapshot.sourceMap.reverse
          )).text == text)

    test("configured native verifies plans and rejects causal loop and whole-model controls"):
      sys.env.get("NODAL_NODALC") match
        case None =>
          println("SKIP bounded-loop native replay: NODAL_NODALC is not configured")
        case Some(executable) =>
          val compiler = Path.of(executable).toAbsolutePath
          val directory = Path.of(".work", "increment55-loop-bridge").toAbsolutePath
          Files.createDirectories(directory)
          val request = NativeCompilerRequest(
            compiler,
            Vector(
              "--pass-pipeline=builtin.module(nodal-plan-digital-loops,nodal-plan-digital-materialization)"
            ),
            directory,
            timeout = Duration.ofSeconds(90)
          )
          def planned(document: NodalMlirDocument): String =
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess => success.normalizedMlir
              case failure: NativeCompilerFailure =>
                scala.util.Failure[Nothing](new java.lang.AssertionError(
                  s"${failure.diagnostic}\n${failure.standardError}"
                )).get
          val positives: Vector[(String, () => Module)] = Vector(
            "static" -> (() => new PublicIndependentLoop),
            "symbolic" -> (() => new PublicSymbolicLoop),
            "seeded" -> (() => new PublicSeededLoop),
            "strided" -> (() => new PublicStridedLoop),
            "nested" -> (() => new PublicNestedCollectionLoop),
            "nested-left-reduction" -> (() => new PublicNestedLeftReductionLoop),
            "nested-tree-reduction" -> (() => new PublicNestedTreeReductionLoop),
            "symbolic-nested-tree-reduction" -> (() => new PublicSymbolicNestedTreeReductionLoop),
            "mixed" -> (() => new PublicMixedRange),
            "empty" -> (() => new PublicEmptyLoop),
            "static-capture" -> (() => new LoopStaticCaptureBridgeSource),
            "literal-capture" -> (() => new LoopLiteralProofBridgeSource),
            "varying-offset" -> (() => new PublicStaticOffsetLoop),
            "local-offset" -> (() => new PublicLocalStaticOffsetLoop),
            "nominal" -> (() => new PublicNominalLoop),
            "negative-origin" -> (() => new PublicNegativeOriginLoop),
            "symbolic-width" -> (() => new PublicSymbolicWidthLoop),
            "sibling-loops" -> (() => new PublicTwoLoopPartitions)
          )
          positives.foreach: (name, source) =>
            val document = ScalaToMlirBridge.lower(source())
            Files.writeString(directory.resolve(name + ".source.mlir"), document.text)
            val normalized = planned(document)
            Files.writeString(directory.resolve(name + ".planned.mlir"), normalized)
            assert(normalized.contains("nodal.digital.loop_plans"))
            assert(normalized.contains("nodal.digital.materialization"))
            assert(normalized.contains("target_emission = \"not-implemented\""))
            assert(planned(document.copy(text = normalized)) == normalized)
          val structural = ScalaToMlirBridge.lower(new PublicUnusedStructuralIndexRange)
          Files.writeString(
            directory.resolve("unused-structural-index.source.mlir"),
            structural.text
          )
          val structuralResult = planned(structural)
          assert(structuralResult.contains("nodal.generate"))
          assert(!structuralResult.contains("nodal.hardware_loop"))
          val negatives: Vector[(String, () => Module, String)] = Vector(
            ("missing-lane", () => new PublicMissingLaneLoop, "complete physical driver coverage"),
            (
              "repeated-scalar",
              () => new PublicRepeatedScalarLoop,
              "injective physical write family"
            ),
            ("empty-output", () => new PublicEmptyOutputLoop, "undriven combinational wire region")
          )
          negatives.foreach: (name, source, fragment) =>
            val document = ScalaToMlirBridge.lower(source())
            Files.writeString(directory.resolve(name + ".source.mlir"), document.text)
            NativeCompilerClient.run(document, request) match
              case failure: NativeCompilerFailure =>
                Files.writeString(directory.resolve(name + ".stderr"), failure.standardError)
                assert(failure.standardError.contains("NODAL-DIGITAL-055-010"))
                assert(failure.standardError.contains(fragment))
              case _: NativeCompilerSuccess =>
                scala.util.Failure[Nothing](new java.lang.AssertionError(
                  s"native unexpectedly accepted $name"
                )).get
          sys.env.get("NODAL_TRANSLATE") match
            case None =>
              println("SKIP mixed-model export rejection: NODAL_TRANSLATE is not configured")
            case Some(translator) =>
              ScalaToMlirBridge.compileToVerilogA(
                new PublicMixedRange,
                compiler,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(90)
              ) match
                case Left(failure) =>
                  // The existing whole-model analog gate requires an explicit bridge.
                  // Planner admission alone does not promise target-language support.
                  assert(failure.standardError.contains("NODAL-VERIFY-ANALOG-004"))
                  assert(failure.standardError.contains("explicit bridge"))
                  assert(!failure.standardOutput.contains("endmodule"))
                case Right(_) =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(
                    "mixed loop model unexpectedly reached target emission"
                  )).get
