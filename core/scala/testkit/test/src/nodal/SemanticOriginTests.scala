package nodal.internal.testkit

import nodal.*
import nodal.internal.testkit.duplicate.alpha.DuplicateOriginAlpha
import nodal.internal.testkit.duplicate.beta.DuplicateOriginBeta

import utest.*

enum SemanticOriginState:
  case Idle, Active

given HwEnum[SemanticOriginState] = HwEnum.derived

final class SemanticOriginLeaf extends Module:
  val core: ClockDomain = ClockDomain.required("core")
  val output: Signal[UInt] = out(UInt(8))

  core:
    val state = Reg(0.U(8))
    val incremented = state + 1.U(8)
    state := incremented
    output := state

final class SemanticOriginTop extends Module:
  val root: ClockDomain = ClockDomain.external(
    "root",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.AsyncAssertSyncRelease(2),
    resetPolarity = ResetPolarity.ActiveLow,
    frequency = 500.MHz
  )
  val shaped: Signal[Vec[UInt]] = wire(Vec(UInt(8), 2, 2))
  val firstLane: Expr[UInt] = shaped.at(0, 0)
  val namedLane: Expr[UInt] = (firstLane + 1.U(8)).named("pixel_sum")
  val synchronized: Expr[Bool] = Cdc.sync(true.B, root)
  val resetTree: Expr[Reset] = ResetController.combine(root.reset)
  val piped: Expr[UInt] = namedLane.delay(2)
  val fifoStream: Stream[UInt] = Cdc.fifo(Stream(firstLane), root, depth = 8)
  val machine: FsmDefinition[SemanticOriginState] =
    FsmDefinition[SemanticOriginState]("semanticOrigin")

  fsm(machine, SemanticOriginState.Idle)(_ => ())

  root:
    val state = Reg(0.U(8))
    state := piped
    val _ = RegNext(namedLane, 0.U(8))
    val child = instance(new SemanticOriginLeaf)
    child.domain(root)

object SemanticOriginTests extends TestSuite:
  private val counterOnly =
    raw"(?:module|instance|input|output|wire|variable|register|memory|expr)_\d+".r

  val tests: Tests = Tests:
    test("semantic names replace traversal-counter-only names"):
      val first = ConstructionKernel.inspect(new SemanticOriginTop)
      val second = ConstructionKernel.inspect(new SemanticOriginTop)

      assert(first == second)
      assert(first.modules.exists(_.path == "SemanticOriginTop.child"))
      assert(first.modules.head.declarations.exists(_.name == "shaped"))
      assert(first.names.exists(entry =>
        entry.category == "expression" && entry.name == "pixel_sum"
      ))
      assert(
        !first.names.exists(entry =>
          counterOnly.pattern.matcher(entry.name).matches()
        )
      )
      assert(first.names.exists(_.provenance == "scala-declaration"))
      assert(first.names.exists(_.provenance == "shaped-view") ||
        first.names.exists(_.name == "firstLane"))

    test("expression source maps survive inlined origins"):
      val snapshot = ConstructionKernel.inspect(new SemanticOriginTop)
      val expressionOrigins =
        snapshot.origins.filter(origin => origin.kind == "expression" && origin.inlined)
      val mapped = snapshot.sourceMap.map(_.semanticPath).toSet

      assert(expressionOrigins.nonEmpty)
      assert(expressionOrigins.forall(origin => mapped.contains(origin.semanticPath)))
      assert(snapshot.sourceMap.exists(entry =>
        entry.source.path.endsWith("SemanticOriginTests.scala") &&
          entry.source.line > 0 &&
          entry.source.column > 0 &&
          entry.source.endLine >= entry.source.line &&
          entry.source.endColumn > 0
      ))

      val emission = Nodal.emit(new SemanticOriginTop)
      assert(emission.report.sourceMap == snapshot.sourceMap)

    test("generated infrastructure names cover required categories"):
      val snapshot = ConstructionKernel.inspect(new SemanticOriginTop)
      val categories = snapshot.generatedNames.map(_.category).toSet
      val required = Set(
        "clock-port",
        "reset-port",
        "synchronizer",
        "fifo",
        "reset-controller",
        "crossing",
        "pipeline-state",
        "fsm-state",
        "anonymous-register",
        "temporary"
      )

      assert(required.subsetOf(categories))
      assert(snapshot.generatedNames.forall(entry =>
        !counterOnly.pattern.matcher(entry.name).matches()
      ))

    test("origin graph records parents and sink affinity"):
      val snapshot = ConstructionKernel.inspect(new SemanticOriginTop)
      val named = snapshot.origins.find(_.semanticPath.endsWith(".pixel_sum")).get

      assert(named.parents.nonEmpty)
      assert(snapshot.origins.exists(_.sink.exists(_.endsWith(".state"))))
      assert(snapshot.origins.map(_.id).distinct.size == snapshot.origins.size)

    test("same-basename source files use owner context"):
      val alpha = ConstructionKernel.inspect(new DuplicateOriginAlpha)
      val beta = ConstructionKernel.inspect(new DuplicateOriginBeta)

      assert(alpha.sourceMap.exists(entry =>
        entry.source.path.endsWith("duplicate/alpha/DuplicateSource.scala")
      ))
      assert(beta.sourceMap.exists(entry =>
        entry.source.path.endsWith("duplicate/beta/DuplicateSource.scala")
      ))
      assert(!alpha.sourceMap.exists(entry =>
        entry.source.path.endsWith("duplicate/beta/DuplicateSource.scala")
      ))
      assert(!beta.sourceMap.exists(entry =>
        entry.source.path.endsWith("duplicate/alpha/DuplicateSource.scala")
      ))
