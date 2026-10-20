package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration
import nodal.*
import nodal.internal.bridge.*
import utest.*

/** Test-only raw graph adapter. Reflection bypasses authored-source admission explicitly; the real
  * production callback entrypoint still receives and verifies its ordinary checked carrier.
  */
private object SequentialBridgeKernelFixture:
  private val kernelClass = Class.forName("nodal.ConstructionKernel$")
  private val kernel = kernelClass.getField("MODULE$").get(())
  private val callbacksClass = Class.forName("nodal.DigitalCollectionCallback$")
  private val callbacks = callbacksClass.getField("MODULE$").get(())
  private val checked = callbacksClass.getMethod("checkedSequential", classOf[Function0[?]])
  private val branchMethod = kernelClass.getMethod(
    "behavioralBranch",
    classOf[String],
    classOf[Option[?]],
    classOf[Function0[?]]
  )
  private val comparisonMethod = kernelClass.getMethod(
    "behavioralComparison",
    classOf[Expr[?]],
    classOf[Expr[?]],
    classOf[String]
  )

  private def invoke(method: java.lang.reflect.Method, owner: AnyRef, arguments: AnyRef*): AnyRef =
    try method.invoke(owner, arguments*)
    catch
      case failure: java.lang.reflect.InvocationTargetException =>
        scala.util.Failure[Nothing](Option(failure.getCause).getOrElse(failure)).get

  def branch(kind: String, condition: Option[Expr[Bool]], body: () => Unit): Unit =
    val carrier = invoke(checked, callbacks, body)
    val _ = invoke(branchMethod, kernel, kind, condition, carrier)

  def comparison(left: Expr[Integer], right: Expr[Integer], operation: String): Expr[Bool] =
    invoke(comparisonMethod, kernel, left, right, operation).asInstanceOf[Expr[Bool]]

/** Trusted construction witnesses exercise bridge admission independently of frontend attestation.
  */
final class SequentialBridgeGuard(defaultMode: Int = 0) extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val core = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "core"
  )
  val MODE = param(defaultMode.integer)
  val enable = in(Bool)
  val input = in(UInt(8))
  val output = out(UInt(8))
  core:
    val state = Reg(0.U(8))
    val condition = SequentialBridgeKernelFixture.comparison(MODE, 0.integer, "eq")
    SequentialBridgeKernelFixture.branch(
      "when",
      Some(condition),
      () => {
        state := input
        SequentialBridgeKernelFixture.branch("when", Some(enable), () => { state := ~input })
      }
    )
    SequentialBridgeKernelFixture.branch("otherwise", None, () => { state := input })
    output := state

final class SequentialBridgeStructural extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val MODE = param(0.integer)
  val input = in(Bits(8))
  val output = out(Bits(8))
  val predicate = ConstructionKernel.staticPredicate("eq", Vector(MODE, 0.integer))
  val choice = ConstructionKernel.structuralIf(predicate, true)(
    () => Some(~input),
    () => Some(input)
  ).get.asInstanceOf[Expr[Bits]]
  output := choice

final class SequentialBridgeScopedPredicate extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Falling,
    ResetPolicy.AsyncExternalRelease,
    ResetPolarity.ActiveLow,
    100.MHz
  )
  val MODE = param(0.integer)
  val enable = in(Bool)
  val input = in(UInt(8))
  val output = out(UInt(8))
  core:
    val state = Reg(0.U(8))
    SequentialBridgeKernelFixture.branch(
      "when",
      Some(enable),
      () => {
        val condition = SequentialBridgeKernelFixture.comparison(MODE, 1.integer, "ne")
        SequentialBridgeKernelFixture.branch("when", Some(condition), () => { state := input })
      }
    )
    output := state

final class SequentialBridgePureChoice extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val MODE = param(0.integer)
  val enable = in(Bool)
  val first = in(UInt(8))
  val second = in(UInt(8))
  val output = out(UInt(8))
  core:
    val state = Reg(0.U(8))
    SequentialBridgeKernelFixture.branch(
      "when",
      Some(enable),
      () => {
        val predicate = ConstructionKernel.staticPredicate("eq", Vector(MODE, 0.integer))
        val choice = ConstructionKernel.structuralIf(predicate, true)(
          () => Some(first ^ second),
          () => Some(second)
        ).get.asInstanceOf[Expr[UInt]]
        state := choice
      }
    )
    output := state

final class SequentialBridgeProjectedDescriptor(execute: Boolean = false) extends Module:
  val clocks = in(Vec(Clock, 2))
  val reset = in(Reset)
  val core = ClockDomain.from(
    clocks.at(0),
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "core"
  )
  if execute then
    core:
      val state = Reg(0.U(8))
      state := 1.U(8)

final class SequentialBridgeTwoDomains(alias: Boolean = false, execute: Boolean = true)
    extends Module:
  val firstClock = in(Clock)
  val firstReset = in(Reset)
  val secondClock = if alias then firstClock else in(Clock)
  val secondReset = if alias then firstReset else in(Reset)
  val first = ClockDomain.from(
    firstClock,
    firstReset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "first"
  )
  val second = ClockDomain.from(
    secondClock,
    secondReset,
    ClockEdge.Falling,
    ResetPolicy.AsyncExternalRelease,
    ResetPolarity.ActiveLow,
    50.MHz,
    "second"
  )
  if execute then
    first:
      val firstState = Reg(0.U(8))
      firstState := 1.U(8)
    second:
      val secondState = Reg(0.U(8))
      secondState := 2.U(8)

object Increment56SequentialBridgeTests extends TestSuite:
  private def sample: ConstructionSnapshot = ConstructionKernel.inspect(new SequentialBridgeGuard)
  private def rejected(snapshot: ConstructionSnapshot, message: String): Unit =
    val result = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot))
    assert(result.isFailure)
    assert(result.failed.get.getMessage.contains(message))

  val tests: Tests = Tests:
    test("actual typed binding and nested behavioral SSA retain source ordering"):
      val captured = sample
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(text.contains("\"nodal.local_domain_binding\""))
      assert(text.contains("!nodal.capability<\"clock\">"))
      assert(text.contains("!nodal.capability<\"reset\">"))
      assert(text.contains("\"nodal.behavioral_predicate\""))
      assert(text.sliding("\"nodal.behavioral_if\"".length).count(_ == "\"nodal.behavioral_if\"") ==
        2)
      assert(!text.contains("nodal.structural_yield"))
      assert(text.contains("kind = \"register_next\""))
      val mode = captured.modules.head.declarations.find(_.name == "MODE").get
      assert(!mode.attributes.contains("classification" -> "structural"))
      assert(!mode.attributes.exists(_._1 == "structural_effects"))
      assert(text == ScalaToMlirBridge.fromSnapshot(captured).text)
      assert(ReproducibilityContract.canonicalSnapshot(captured).contains("behavioral_ifs"))

    test("parameter default does not select or remove behavioral arms"):
      val zero = sample
      val one = ConstructionKernel.inspect(new SequentialBridgeGuard(1))
      assert(zero.behavioralIfs.size == one.behavioralIfs.size)
      assert(zero.digitalAssignments.size == one.digitalAssignments.size)
      assert(ScalaToMlirBridge.fromSnapshot(one).text.contains("nodal.behavioral_predicate"))

    test("structural value predecessor keeps both yields and static condition"):
      val text = ScalaToMlirBridge.lower(new SequentialBridgeStructural).text
      assert(text.contains("\"nodal.structural_if\""))
      assert(text.sliding("\"nodal.structural_yield\"".length).count(_ ==
        "\"nodal.structural_yield\"") == 2)
      assert(!text.contains("\"nodal.behavioral_if\""))

    test("scoped behavioral Integer captures preserve ordinary parameters and external inputs"):
      val captured = ConstructionKernel.inspect(new SequentialBridgeScopedPredicate)
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      val mode = captured.modules.head.declarations.find(_.name == "MODE").get
      assert(!mode.attributes.contains("classification" -> "structural"))
      assert(captured.behavioralIfs.exists(_.trueArm.arguments.exists(_.dataType == "Integer")))
      assert(text.contains("nodal.behavioral_predicate"))
      assert(text.contains("reset_polarity = \"active_low\""))
      assert(text.contains("edge = \"falling\""))
      assert(text.contains("core_clock"), text.contains("core_reset"))

    test("pure structural RHS retains real yields inside its behavioral captures"):
      val captured = ConstructionKernel.inspect(new SequentialBridgePureChoice)
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(captured.structuralIfs.size == 1, captured.behavioralIfs.size == 1)
      assert(text.contains("nodal.structural_if"), text.contains("nodal.behavioral_if"))
      assert(text.sliding("\"nodal.structural_yield\"".length).count(_ ==
        "\"nodal.structural_yield\"") == 2)
      val choice = captured.structuralIfs.head
      rejected(
        captured.copy(structuralIfs =
          Vector(choice.copy(trueArm =
            choice.trueArm.copy(yieldValue = choice.falseArm.yieldValue)
          ))
        ),
        "exact lexical scope"
      )
      val altered = choice.copy(trueArm =
        choice.trueArm.copy(captures =
          choice.trueArm.captures.updated(0, captured.behavioralIfs.head.predicate)
        )
      )
      rejected(captured.copy(structuralIfs = Vector(altered)), "actual predicate")
      val arm = choice.trueArm
      assert(arm.captures.size == 2)
      val renamed = arm.arguments.map(_.path).zip(arm.arguments.map(_.path).reverse).toMap
      val reordered = captured.copy(
        structuralIfs = Vector(choice.copy(trueArm = arm.copy(captures = arm.captures.reverse))),
        digitalExpressions = captured.digitalExpressions.map(value =>
          if value.path == choice.path then
            value.copy(operands =
              choice.predicate +: (arm.captures.reverse ++ choice.falseArm.captures)
            )
          else if value.region.contains(arm.path) then
            value.copy(operands = value.operands.map(path => renamed.getOrElse(path, path)))
          else value
        )
      )
      // Explicit capture permutation preserves executable order modulo local SSA names.
      def alphaNormalized(value: String): String =
        val names = scala.collection.mutable.LinkedHashMap.empty[String, String]
        "%[A-Za-z_][A-Za-z_0-9]*".r.replaceAllIn(
          value,
          matched =>
            names.getOrElseUpdate(matched.matched, s"%ssa${names.size}")
        )
      assert(alphaNormalized(ScalaToMlirBridge.fromSnapshot(reordered).text) ==
        alphaNormalized(text))
      rejected(captured.copy(structuralIfs = reordered.structuralIfs), "both capture segments")
      rejected(
        captured.copy(modules =
          captured.modules.map(module =>
            module.copy(declarations =
              module.declarations.map(declaration =>
                if declaration.name == "MODE" then
                  declaration.copy(attributes =
                    declaration.attributes.filterNot((key, _) =>
                      Set("classification", "structural_effects")(key)
                    )
                  )
                else declaration
              )
            )
          )
        ),
        "actual structural topology classification"
      )

    test("projected clock descriptor stays honest and cannot activate executable state"):
      val captured = ConstructionKernel.inspect(new SequentialBridgeProjectedDescriptor)
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(!text.contains("nodal.local_domain_binding"))
      rejected(
        ConstructionKernel.inspect(new SequentialBridgeProjectedDescriptor(true)),
        "actual owned declaration"
      )

    test("distinct physical roots resolve exact domains and contradictory aliases reject"):
      val captured = ConstructionKernel.inspect(new SequentialBridgeTwoDomains)
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(text.sliding("\"nodal.local_domain_binding\"".length).count(_ ==
        "\"nodal.local_domain_binding\"") == 2)
      for execute <- Vector(false, true) do
        val invalid = scala.util.Try(ConstructionKernel.inspect(new SequentialBridgeTwoDomains(
          alias = true,
          execute = execute
        )))
        assert(invalid.failed.toOption.exists(_.getMessage.contains("contradictory polarities")))

    test("actual public chain and pure choice reach the same bridge owners"):
      val captured =
        ConstructionKernel.inspect(new nodal_external.Increment56SequentialBridgeSource)
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(captured.behavioralIfs.size == 3, captured.structuralIfs.size == 1)
      assert(text.contains("nodal.local_domain_binding"))
      assert(text.contains("nodal.behavioral_if"), text.contains("nodal.structural_if"))

    test("forged behavioral roots and parent scopes reject"):
      val captured = sample
      val root = captured.behavioralIfs.find(_.parentRegion.isEmpty).get
      rejected(
        captured.copy(behavioralIfs =
          captured.behavioralIfs.map(value =>
            if value == root then value.copy(root = root.path + ".forged") else value
          )
        ),
        "outer update root"
      )
      rejected(
        captured.copy(behavioralIfs =
          captured.behavioralIfs.map(value =>
            if value == root then value.copy(parentRegion = Some(root.trueArm.path)) else value
          )
        ),
        "lexical scope"
      )

    test("forged arm captures, yields and runtime predicate types reject"):
      val captured = sample
      val root = captured.behavioralIfs.find(_.parentRegion.isEmpty).get
      val changed =
        root.copy(trueArm = root.trueArm.copy(captures = root.trueArm.captures :+ root.predicate))
      rejected(
        captured.copy(behavioralIfs =
          captured.behavioralIfs.map(value =>
            if value == root then changed else value
          )
        ),
        "behavioral arm"
      )
      rejected(
        captured.copy(behavioralIfs =
          captured.behavioralIfs.map(value =>
            if value == root then
              value.copy(trueArm = value.trueArm.copy(yieldValue = Some(value.predicate)))
            else value
          )
        ),
        "behavioral arm"
      )
      val rawPredicate = captured.staticPredicates.head.path
      rejected(
        captured.copy(behavioralIfs =
          captured.behavioralIfs.map(value =>
            if value == root then value.copy(predicate = rawPredicate) else value
          )
        ),
        "canonical Bool"
      )

    test("forged physical binding operand types and missing producer reject"):
      val captured = sample
      def changeClock(path: String): ConstructionSnapshot = captured.copy(modules =
        captured.modules.map(module =>
          module.copy(domains =
            module.domains.map(domain =>
              domain.copy(attributes =
                domain.attributes.map((key, value) =>
                  key -> (if key == "clock_source" then path else value)
                )
              )
            )
          )
        )
      )
      val input = captured.modules.head.declarations.find(_.name == "input").get.path
      rejected(changeClock(input), "typed input boundary")
      rejected(changeClock(captured.root + ".missingClock"), "actual owned declaration")

    test("configured native normalizes actual public and trusted sequential witnesses"):
      sys.env.get("NODAL_NODALC") match
        case None => println("SKIP sequential native replay: NODAL_NODALC is not configured")
        case Some(executable) =>
          val directory = Path.of(".work", "increment56-sequential-bridge").toAbsolutePath
          Files.createDirectories(directory)
          val request = NativeCompilerRequest(
            Path.of(executable).toAbsolutePath,
            Vector("--nodal-normalize-sequential"),
            directory,
            timeout = Duration.ofSeconds(90)
          )
          def normalize(name: String, document: NodalMlirDocument): String =
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess => success.normalizedMlir
              case failure: NativeCompilerFailure =>
                scala.util.Failure[Nothing](new java.lang.AssertionError(
                  s"$name: ${failure.diagnostic}\n${failure.standardError}"
                )).get
          val positives: Vector[(String, () => Module)] = Vector(
            "public-chain" -> (() => new nodal_external.Increment56SequentialBridgeSource),
            "nested-guard" -> (() => new SequentialBridgeGuard),
            "external-scoped" -> (() => new SequentialBridgeScopedPredicate),
            "pure-structural-rhs" -> (() => new SequentialBridgePureChoice),
            "projected-descriptor" -> (() => new SequentialBridgeProjectedDescriptor),
            "two-domains" -> (() => new SequentialBridgeTwoDomains)
          )
          positives.foreach: (name, source) =>
            val document = ScalaToMlirBridge.lower(source())
            Files.writeString(directory.resolve(name + ".source.mlir"), document.text)
            val normalized = normalize(name, document)
            Files.writeString(directory.resolve(name + ".normalized.mlir"), normalized)
            assert(!normalized.contains("\"nodal.behavioral_if\""))
            assert(!normalized.contains("kind = \"register_next\""))
            if name != "projected-descriptor" then
              assert(normalized.contains("\"nodal.sequential_update\""))
              if name != "two-domains" then
                assert(normalized.contains("\"nodal.sequential_select\""))
              assert(normalized.contains("!nodal.capability<\"clock\">"))
              assert(normalized.contains("!nodal.capability<\"reset\">"))
            else assert(!normalized.contains("\"nodal.local_domain_binding\""))
            if Set("public-chain", "pure-structural-rhs")(name) then
              assert(normalized.contains("\"nodal.structural_if\""))
              assert(normalized.sliding("\"nodal.structural_yield\"".length).count(_ ==
                "\"nodal.structural_yield\"") == 2)
            assert(normalize(name + "-roundtrip", document.copy(text = normalized)) == normalized)
          val guarded = ScalaToMlirBridge.lower(new SequentialBridgeGuard)
          val unbound = guarded.copy(text =
            guarded.text.linesIterator.filterNot(
              _.contains("\"nodal.local_domain_binding\"")
            ).mkString("\n") + "\n"
          )
          NativeCompilerClient.run(unbound, request) match
            case failure: NativeCompilerFailure => assert(failure.standardError.contains("binding"))
            case _: NativeCompilerSuccess => assert(false)
