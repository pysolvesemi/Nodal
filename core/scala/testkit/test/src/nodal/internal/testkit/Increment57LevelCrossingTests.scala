package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import utest.*

private object LevelCrossingKernelFixture:
  def branch(condition: Expr[Bool])(body: => Unit): Unit =
    val callbacks = Class.forName("nodal.DigitalCollectionCallback$")
    val checked = callbacks.getMethod("checkedSequential", classOf[Function0[?]])
      .invoke(callbacks.getField("MODULE$").get(()), (() => body): Function0[Unit])
    val kernel = Class.forName("nodal.ConstructionKernel$")
    try
      val _ = kernel.getMethod(
        "behavioralBranch",
        classOf[String],
        classOf[Option[?]],
        classOf[Function0[?]]
      ).invoke(kernel.getField("MODULE$").get(()), "when", Some(condition), checked)
    catch
      case failure: java.lang.reflect.InvocationTargetException =>
        scala.util.Failure[Nothing](Option(failure.getCause).getOrElse(failure)).get

final class LevelCrossingCapture(mode: String = "register", stages: Int = 2, count: Int = 2)
    extends Module:
  val a = ClockDomain.external(
    "a",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val b = ClockDomain.external(
    "b",
    ClockEdge.Falling,
    if mode == "resetless" then ResetPolicy.None else ResetPolicy.Sync,
    ResetPolarity.ActiveLow,
    20.MHz
  )
  val unknown: Expr[Bool] = if mode == "unknown" then in(Bool) else true.B
  var output = Option.empty[Signal[Bool]]
  var input: Expr[Bool] = unknown
  var other: Expr[Bool] = unknown
  var separate: Expr[Bool] = unknown
  a:
    val source = Reg(false.B)
    source := true.B
    val separateSource = Reg(false.B)
    separateSource := false.B
    separate = separateSource
    val port = in(Bool)
    val wired = wire(Bool)
    if mode != "undriven" then wired := source
    val memory = Mem(Bool, 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, a)
    val read = memory.read(0.U(1))
    val pad = digitalInout(
      UInt(1),
      DriveMode.pushPull,
      InoutPlacement.TopLevelPin,
      ResolutionProfile.PortableBoundaryOnly,
      "source_pad"
    )
    pad.drive(0.U(1), source)
    input = mode match
      case "port" => port
      case "wire" | "undriven" => wired
      case "memory" => read
      case "interface" | "interface-mixed" => pad.read === 1.U(1)
      case "expression" => source && port
      case "unknown" => unknown
      case "constant" => true.B
      case "pulse" => Pulse(source).value
      case "forged-type" => 0.U(2).asInstanceOf[Expr[Bool]]
      case _ => source
    if mode == "interface-mixed" then
      b:
        val foreign = Reg(false.B)
        foreign := true.B
        pad.drive(1.U(1), foreign)
  b:
    output = Some(out(Bool))
    val state = Reg.uninitialized(Bool)
    other = state
  if mode == "outside" then
    val _ = Cdc.sync(input, b, stages)
  else
    b:
      val selected = if mode == "mixed" then input && other else input
      if mode == "callback" then
        LevelCrossingKernelFixture.branch(true.B) { val _ = Cdc.sync(selected, b, stages) }
      else if mode == "forged" then
        ConstructionKernel.expression(new KernelExpr[Bool](
          Vector(selected),
          resultType = Some(KernelTypeDescriptor("Bool")),
          operation = Some("cdc_sync")
        ))
      else
        val results = Vector.tabulate(count)(index =>
          Cdc.sync(
            if mode == "independent-sources" && index != 0 then separate else selected,
            if mode == "same" then a else b,
            stages
          )
        )
        val destination = Reg.uninitialized(Bool)
        if mode == "guard-reconverge" || mode == "guard-reused" then
          LevelCrossingKernelFixture.branch(results.head) {
            destination := (if mode == "guard-reused" then results.head else results.last)
          }
        else
          destination :=
            (if mode == "uncrossed" then input
             else if mode == "reconverge" || mode == "independent-sources" then
               results.head && results.last
             else if mode == "reused" then results.head && results.head
             else results.head)
        output.get := results.last

object Increment57LevelCrossingTests extends TestSuite:
  private def capture(
      mode: String = "register",
      stages: Int = 2,
      count: Int = 2
  ): ConstructionSnapshot =
    ConstructionKernel.inspect(new LevelCrossingCapture(mode, stages, count))

  private def rejected(mode: String, fragment: String, stages: Int = 2): Unit =
    val attempt = scala.util.Try(capture(mode, stages))
    assert(attempt.isFailure, attempt.failed.get.getMessage.contains(fragment))

  val tests: Tests = Tests:
    test("owned Bool source actual domains and independent identity survive bridge"):
      val value = capture()
      val crossings = value.digitalExpressions.filter(_.crossing.nonEmpty)
      assert(crossings.size == 2, crossings.map(_.path).distinct.size == 2)
      assert(crossings.forall(_.dataType == "Bool"))
      assert(crossings.map(_.operands).distinct.size == 1)
      assert(crossings.forall(_.crossing.get.sourceDomain.endsWith(".a")))
      assert(crossings.forall(_.crossing.get.destinationDomain.endsWith(".b")))
      val text = ScalaToMlirBridge.fromSnapshot(value).text
      assert(text.sliding("\"nodal.crossing\"".length).count(_ == "\"nodal.crossing\"") == 2)
      assert(text.contains("contract = \"level_v1\""), text.contains("stages = 2 : i64"))
      assert(text.contains("source_domain = @\"a\""))
      assert(text.contains("destination_domain = @\"b\""))
      assert(crossings.forall(record => text.contains("semantic_path = \"" + record.path + "\"")))
      assert(ReproducibilityContract.canonicalSnapshot(value).contains("level_v1"))
      assert(value == capture())
    test("register port memory Interface and Boolean expression preserve actual source"):
      Vector("register", "port", "wire", "memory", "interface", "expression", "resetless").foreach:
        mode =>
          val value = capture(mode, 32)
          assert(
            value.digitalExpressions.filter(_.crossing.nonEmpty).forall(_.crossing.get.stages == 32)
          )
          assert(ScalaToMlirBridge.fromSnapshot(value).text.contains("stages = 32 : i64"))
    test("stage bounds and native type spoofing reject"):
      Vector(Int.MinValue, 1, 33, Int.MaxValue).foreach(stages =>
        rejected("register", "between 2 and 32", stages)
      )
      rejected("forged-type", "owned Bool")
    test("unknown mixed and Pulse source provenance reject"):
      rejected("unknown", "unknown or unresolved")
      rejected("constant", "exactly one known")
      rejected("undriven", "no actual driver")
      rejected("mixed", "exactly one known")
      rejected("interface-mixed", "exactly one known")
      rejected("pulse", "Pulse-classified")
      rejected("same", "distinct source")
    test("module scope and owned construction are required"):
      assert(capture("outside").digitalExpressions.count(_.crossing.nonEmpty) == 1)
      rejected("callback", "callbacks are unsupported")
      rejected("forged", "owned Cdc.sync entrypoint")
    test("crossing does not authorize the uncrossed input"):
      rejected("uncrossed", "NODAL-CDC-001")
    test("independent shared-source reconvergence rejects but reuse is one boundary"):
      rejected("reconverge", "NODAL-CDC-057-002")
      rejected("guard-reconverge", "NODAL-CDC-057-002")
      assert(capture("guard-reused").behavioralIfs.size == 1)
      assert(capture("reused").digitalExpressions.count(_.crossing.nonEmpty) == 2)
      assert(capture("independent-sources").digitalExpressions.count(_.crossing.nonEmpty) == 2)
    test("bridge independently rejects malformed executable crossing records"):
      val value = capture()
      val crossing = value.digitalExpressions.find(_.crossing.nonEmpty).get
      def reject(altered: KernelDigitalExpressionSnapshot): Unit =
        val changed = value.copy(digitalExpressions =
          value.digitalExpressions.map(record =>
            if record.path == crossing.path then altered else record
          )
        )
        val result = scala.util.Try(ScalaToMlirBridge.fromSnapshot(changed))
        assert(result.isFailure)
      reject(crossing.copy(crossing = None))
      reject(crossing.copy(dataType = "UInt(2)"))
      reject(crossing.copy(operands = Vector.empty))
      reject(crossing.copy(crossing = crossing.crossing.map(_.copy(stages = 33))))
      reject(crossing.copy(crossing =
        crossing.crossing.map(record => record.copy(sourceDomain = record.destinationDomain))
      ))
      reject(crossing.copy(crossing = crossing.crossing.map(_.copy(destinationDomain = "absent"))))
    test("representative independent crossing count has deterministic linear record growth"):
      val first = capture(count = 128)
      val second = capture(count = 128)
      assert(first.digitalExpressions.count(_.crossing.nonEmpty) == 128)
      assert(first == second)
