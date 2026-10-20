package nodal.internal.testkit

import java.nio.file.{Files, Path}

import nodal.*
import nodal.internal.bridge.*
import nodal_external.Increment57GrayPayloads
import utest.*

final class OwnedWaiverPayload[A <: Data](
    dataType: DataType[A],
    relation: ClockRelation = ClockRelation.Unknown,
    id: String = "owned-waiver",
    reason: String = "physical boundary reviewed by user",
    nested: Boolean = false
) extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  var payload = Option.empty[Signal[A]]
  source:
    val input = in(dataType)
    payload = Some(input)
  val transferTarget = if relation == ClockRelation.Same then source else destination
  val first = Cdc.waive(payload.get, transferTarget, CdcWaiver(id, reason, relation))
  val result = if nested then
    Cdc.waive(
      first,
      source,
      CdcWaiver("nested-waiver", "second explicit boundary", ClockRelation.Unknown)
    )
  else first
  val target = if nested then source else transferTarget
  target:
    val output = out(dataType)
    output := result

final class OwnedWaiverVariableWidth extends Module:
  val width = param(4.integer, range = 2 to 8)
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  source:
    val input = in(UInt(width))
    val _ = Cdc.waive(input, destination, CdcWaiver("variable", "not fixed", ClockRelation.Unknown))

final class OwnedWaiverBehavior(mode: String) extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  val unknown = if mode == "unknown" then Some(in(Bool)) else None
  var payload = Option.empty[Signal[Bool]]
  var other = Option.empty[Signal[Bool]]
  source:
    val input = in(Bool)
    payload = Some(input)
  destination:
    val input = in(Bool)
    other = Some(input)
  if mode == "forged" then
    ConstructionKernel.expression(new KernelExpr[Bool](
      Vector(payload.get),
      resultType = Some(KernelTypeDescriptor("Bool")),
      operation = Some(ExecutableWaiverContract.Operation)
    ))
  else if mode == "cycle" then
    val feedback = wire(Bool)
    val result =
      Cdc.waive(feedback, destination, CdcWaiver("cycle", "not storage", ClockRelation.Unknown))
    feedback := result
  else
    val selected: Expr[Bool] = mode match
      case "constant" => true.B
      case "unknown" => unknown.get
      case "mixed" => payload.get && other.get
      case "pulse" | "pulse-level" | "pulse-gray" | "pulse-handshake" | "pulse-fifo" =>
        Pulse(payload.get).value
      case "clock-cast" => source.clock.get.asInstanceOf[Expr[Bool]]
      case _ => payload.get
    val to = if mode == "same-async" then source else destination
    val relation =
      if mode == "same-async" then ClockRelation.Asynchronous else ClockRelation.Unknown
    val result =
      Cdc.waive(selected, to, CdcWaiver("boundary", "user accepts physical hazard", relation))
    if mode == "duplicate" then
      val _ =
        Cdc.waive(payload.get, destination, CdcWaiver("boundary", "different rationale", relation))
    if mode == "pulse-level" then
      val _ = Cdc.sync(result, source)
    if mode == "pulse-gray" then
      val _ = Cdc.gray(Gray(result), source)
    if mode == "pulse-handshake" then
      val _ = Cdc.handshake(result, source)
    if mode == "pulse-fifo" then
      val _ = Cdc.fifo(Stream(result), source)
    destination:
      val state = Reg.uninitialized(Bool)
      state := (if mode == "unwaived" then payload.get else result)
      val output = out(Bool)
      output := result

final class OwnedWaiverLineage(mode: String) extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val middle = ClockDomain.external(
    "middle",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    10.MHz
  )
  var input = Option.empty[Signal[Bool]]
  var other = Option.empty[Signal[Bool]]
  source:
    val payload = in(Bool)
    val separate = in(Bool)
    input = Some(payload)
    other = Some(separate)
  val first = Cdc.sync(input.get, middle)
  val second = if mode == "aliases" then first
  else
    Cdc.sync(
      if mode == "unrelated" || mode == "nested-unrelated" then other.get else input.get,
      middle
    )
  val before = if mode == "before" then first && second else first
  val firstAlias =
    Cdc.waive(before, destination, CdcWaiver("first", "stateless alias", ClockRelation.Unknown))
  val secondAlias =
    Cdc.waive(second, destination, CdcWaiver("second", "stateless alias", ClockRelation.Unknown))
  if mode.startsWith("nested") then
    val next = Cdc.sync(firstAlias, middle)
    middle:
      val output = out(Bool)
      output := next && (if mode == "nested-alias" then next else second)
  else
    destination:
      val output = out(Bool)
      output := firstAlias && secondAlias

final class OwnedWaiverLeaf extends Module:
  val source = ClockDomain.required("source")
  val destination = ClockDomain.required("destination")
  var input = Option.empty[Signal[Bool]]
  var output = Option.empty[Signal[Bool]]
  source:
    val payload = in(Bool)
    input = Some(payload)
  val result = Cdc.waive(
    input.get,
    destination,
    CdcWaiver("shared-authored-id", "owned local boundary", ClockRelation.Unknown)
  )
  destination:
    val value = out(Bool)
    value := result
    output = Some(value)

final class OwnedWaiverHierarchy extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  var input = Option.empty[Signal[Bool]]
  source:
    val value = in(Bool)
    input = Some(value)
  val first =
    instance(new OwnedWaiverLeaf).domain(_.source, source).domain(_.destination, destination)
  val second =
    instance(new OwnedWaiverLeaf).domain(_.source, source).domain(_.destination, destination)
  first(_.input.get) := input.get
  second(_.input.get) := input.get
  destination:
    val output = out(Bool)
    output := first(_.output.get) && second(_.output.get)

object Increment57WaiverSourceTests extends TestSuite:
  private def capture(mode: String = "ordinary") =
    ConstructionKernel.inspect(new OwnedWaiverBehavior(mode))
  private def reject(body: => Any, text: String): Unit =
    val result = scala.util.Try(body)
    assert(result.isFailure, result.failed.get.getMessage.contains(text))
  private def bridge(snapshot: ConstructionSnapshot) = ScalaToMlirBridge.fromSnapshot(snapshot).text
  private def payload[A <: Data](
      dataType: DataType[A],
      relation: ClockRelation = ClockRelation.Unknown
  ) =
    ConstructionKernel.inspect(new OwnedWaiverPayload(dataType, relation))

  val tests = Tests:
    test("owned exact scalar aggregate and nested identity records survive bridge"):
      Vector(
        payload(Bool),
        payload(Bits(8)),
        payload(UInt(8)),
        payload(SInt(8)),
        payload(Increment57GrayPayloads.noZeroEnum),
        payload(Vec(UInt(3), 2)),
        payload(Struct(
          "WaiverPacket",
          StructField("flag", Bool),
          StructField("data", Vec(UInt(3), 2))
        )),
        ConstructionKernel.inspect(new OwnedWaiverPayload(UInt(8), nested = true))
      ).foreach: snapshot =>
        val values = snapshot.digitalExpressions.filter(_.waiver.nonEmpty)
        assert(values.nonEmpty, values.forall(_.operands.size == 1))
        assert(values.forall(_.waiver.get.contract == ExecutableWaiverContract.Contract))
        assert(values.forall(_.waiver.get.obligations == ExecutableWaiverContract.Obligations))
        val text = bridge(snapshot)
        assert(text.contains("\"nodal.waiver\""), !text.contains("\"nodal.crossing\""))
        assert(text.contains("physical_safety = \"user_responsibility\""))
        assert(
          text.contains("reset = \"not_exempt\""),
          text.contains("delivery = \"not_guaranteed\"")
        )
        assert(values.forall(value => snapshot.waivers.exists(_.semanticPath == value.path)))
        assert(ReproducibilityContract.canonicalSnapshot(snapshot).contains("stateless_waiver_v1"))
      assert(capture() == capture())
      assert(capture().digitalExpressions.count(_.waiver.nonEmpty) == 1)
    test("stateless waiver report names never invent a physical synchronizer identity"):
      val snapshot = ConstructionKernel.inspect(new OwnedWaiverLineage("aliases"))
      snapshot.digitalExpressions.foreach: expression =>
        val categories = snapshot.generatedNames.filter(_.origin == expression.path).map(_.category)
        if expression.waiver.nonEmpty then
          assert(categories.contains("waiver"))
          assert(!categories.contains("synchronizer"), !categories.contains("crossing"))
        if expression.crossing.nonEmpty then
          assert(categories.contains("synchronizer"), categories.contains("crossing"))
      assert(ReproducibilityContract.describe(
        snapshot
      ).manifest.text.contains("\"category\": \"waiver\""))
    test("all six exact relation variants retain typed fields without authorizing ordinary input"):
      Vector(
        ClockRelation.Same,
        ClockRelation.Ratio(1, 5, 0.deg),
        ClockRelation.Synchronous(true),
        ClockRelation.MutuallyExclusive,
        ClockRelation.Asynchronous,
        ClockRelation.Unknown
      ).foreach: relation =>
        val snapshot = payload(Bool, relation)
        assert(snapshot.digitalExpressions.flatMap(_.waiver).head.relation ==
          ClockRelationshipModel.intent(relation))
        assert(bridge(snapshot).contains("relation = {kind ="))
      reject(payload(Bool, ClockRelation.Ratio(0, 5)), "ratio requires")
      reject(capture("unwaived"), "NODAL-CDC-001")
      reject(capture("same-async"), "NODAL-CLOCK-RELATION-057-001")
    test("exact source domain real ownership and no constant or capability source"):
      reject(capture("constant"), "exactly one known")
      reject(capture("unknown"), "unknown or unresolved")
      reject(capture("mixed"), "exactly one known")
      reject(capture("clock-cast"), "finite Vec/Struct bit patterns")
      reject(capture("forged"), "owned Cdc.waive")
      reject(payload(Vec(Reset, 2)), "finite Vec/Struct bit patterns")
      reject(payload(Real), "finite Vec/Struct bit patterns")
      reject(ConstructionKernel.inspect(new OwnedWaiverVariableWidth), "fixed whole")
      reject(payload(Bits(4097)), "4096")
      assert(bridge(payload(Bits(4096))).contains("nodal.waiver"))
    test("nonblank bounded valid UTF8 IDs and reasons are exact and unique per module"):
      Vector("", " ", "\u00a0\u2003", "x" * 257, "\ud800").foreach: id =>
        reject(ConstructionKernel.inspect(new OwnedWaiverPayload(Bool, id = id)), "id requires")
      Vector("", "\n\u3000", "x" * 4097, "\udfff").foreach: reason =>
        reject(
          ConstructionKernel.inspect(new OwnedWaiverPayload(Bool, reason = reason)),
          "reason requires"
        )
      reject(capture("duplicate"), "unique")
      val snapshot = ConstructionKernel.inspect(new OwnedWaiverPayload(
        Bool,
        id = "é" * 128,
        reason = " \\\"\n/* physical */ λ " + "x" * 4000
      ))
      assert(bridge(snapshot).contains("\\0A"))
      assert(bridge(ConstructionKernel.inspect(new OwnedWaiverHierarchy)).contains("nodal.waiver"))
    test("stateless aliases retain Pulse classification before forbidden transport relabeling"):
      assert(capture("pulse").digitalExpressions.exists(_.waiver.nonEmpty))
      Vector("pulse-level", "pulse-gray", "pulse-handshake", "pulse-fifo").foreach(mode =>
        reject(capture(mode), "Pulse-classified")
      )
    test("waiver lineage preserves independent chains including upstream stateful boundaries"):
      Vector("ordinary", "before", "nested").foreach(mode =>
        reject(ConstructionKernel.inspect(new OwnedWaiverLineage(mode)), "NODAL-CDC-057-002")
      )
      Vector("aliases", "unrelated", "nested-alias", "nested-unrelated").foreach(mode =>
        assert(
          bridge(ConstructionKernel.inspect(new OwnedWaiverLineage(mode))).contains("nodal.waiver")
        )
      )
      reject(capture("cycle"), "combinational dependency cycle")
    test("bridge rejects descriptor only malformed closed records and foreign endpoints"):
      val snapshot = payload(Bool)
      val value = snapshot.digitalExpressions.find(_.waiver.nonEmpty).get
      def malformed(changed: KernelDigitalExpressionSnapshot): Unit =
        reject(
          bridge(snapshot.copy(digitalExpressions =
            snapshot.digitalExpressions.map(v => if v.path == value.path then changed else v)
          )),
          "NODAL-BRIDGE"
        )
      malformed(value.copy(waiver = None))
      malformed(value.copy(operands = Vector.empty))
      malformed(value.copy(dataType = "UInt(2)"))
      malformed(value.copy(waiver = value.waiver.map(_.copy(contract = "descriptor"))))
      malformed(value.copy(waiver = value.waiver.map(_.copy(destinationDomain = "foreign.domain"))))
      malformed(value.copy(waiver = value.waiver.map(_.copy(obligations = Vector.empty))))
      malformed(value.copy(waiver = value.waiver.map(_.copy(id = " "))))
      malformed(value.copy(waiver =
        value.waiver.map(_.copy(relation = ClockRelationshipModel.RelationIntent("ratio")))
      ))

object Increment57WaiverSourceReplay:
  def main(args: Array[String]): Unit =
    val directory = Files.createDirectories(Path.of(args(0)))
    def save(name: String, snapshot: ConstructionSnapshot): Unit =
      val _ = Files.writeString(
        directory.resolve(name + ".mlir"),
        ScalaToMlirBridge.fromSnapshot(snapshot).text
      )
    val scalar = ConstructionKernel.inspect(new OwnedWaiverPayload(UInt(8)))
    save("scalar", scalar)
    save("bool", ConstructionKernel.inspect(new OwnedWaiverPayload(Bool)))
    save("aggregate", ConstructionKernel.inspect(new OwnedWaiverPayload(Vec(UInt(3), 2))))
    save("nested", ConstructionKernel.inspect(new OwnedWaiverPayload(UInt(8), nested = true)))
    save("hierarchy", ConstructionKernel.inspect(new OwnedWaiverHierarchy))
    Vector("aliases", "unrelated", "nested-alias", "nested-unrelated").foreach(mode =>
      save("lineage-" + mode, ConstructionKernel.inspect(new OwnedWaiverLineage(mode)))
    )
    save(
      "escaped",
      ConstructionKernel.inspect(new OwnedWaiverPayload(
        Bool,
        id = "é-owned",
        reason = "reason\n/* no injected code */\\\" λ"
      ))
    )
    save(
      "forged-source-negative",
      scalar.copy(digitalExpressions =
        scalar.digitalExpressions.map(value =>
          value.copy(waiver = value.waiver.map(w => w.copy(sourceDomain = w.destinationDomain)))
        )
      )
    )
