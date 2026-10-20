package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

final class RelationshipCaughtFailure extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val root = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  try
    val _ = ClockDomain.generated("bad", clock, root, ClockRelation.Ratio(0, 1), root.reset)
  catch case _: IllegalArgumentException => ()

final class RelationshipGateForgery extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val gate = ClockGate(root, true.B)
  val _ = ClockDomain.generated(
    "forged",
    gate.clock.get,
    root,
    ClockRelation.Same,
    Rdc.sync(root.reset, stages = 2)
  )

final class RelationshipAliasContradiction(phase: Boolean) extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val root = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  val _ = ClockDomain.generated(
    "bad",
    clock,
    root,
    if phase then ClockRelation.Ratio(1, 1, 90.deg) else ClockRelation.Ratio(2, 1),
    root.reset
  )

final class RelationshipCallbackCapture(caught: Boolean) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val values = in(Vec(Bool, 1))
  val _ = ConstructionKernel.collection("map", Vector(values), None): arguments =>
    if caught then
      try {
        val _ = ClockDomain.generated("bad", root.clock.get, root, ClockRelation.Same, root.reset)
      } catch case _: Throwable => ()
    else
      val _ = ClockDomain.generated("bad", root.clock.get, root, ClockRelation.Same, root.reset)
    arguments.head

final class RelationshipGateControlSource(relation: ClockRelation) extends Module:
  val clock = in(Clock)
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val child =
    ClockDomain.generated("child", clock, root, relation, Rdc.sync(root.reset, stages = 2))
  var control: Option[Expr[Bool]] = None
  child:
    val enable = in(Bool)
    control = Some(enable)
  val gated = ClockGate(root, control.get)

final class RelationshipSharedReleaseSource(structural: Boolean) extends Module:
  val clock = in(Clock)
  val alternateClock = in(Clock)
  val reset = in(Reset)
  val root = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  val release = Rdc.sync(reset, to = root, stages = 2)
  val child = ClockDomain.generated(
    "child",
    if structural then clock else alternateClock,
    root,
    ClockRelation.Same,
    release
  )

final class RelationshipForgedClockSource(resetCast: Boolean) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val bit = in(Bool)
  val forged =
    if resetCast then root.reset.asInstanceOf[Expr[Clock]] else bit.asInstanceOf[Expr[Clock]]
  val _ = ClockDomain.generated("forged", forged, root, ClockRelation.Same, root.reset)

object Increment57RelationshipTests extends TestSuite:
  private def capture(structural: Boolean = false, nested: Boolean = false) =
    ConstructionKernel.inspect(new Increment57RelationshipSource(structural, nested))
  private def change(s: ConstructionSnapshot)(
      f: KernelClockRelationshipSnapshot => KernelClockRelationshipSnapshot
  ) =
    s.copy(modules =
      s.modules.map(m =>
        m.copy(domains =
          m.domains.map(d =>
            d.copy(relationship = d.relationship.map(f))
          )
        )
      )
    )
  private def rejects(s: ConstructionSnapshot): Unit =
    assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(s)).isFailure)
  private val categories = Vector(
    ClockRelation.Ratio(1, 1),
    ClockRelation.Synchronous(true),
    ClockRelation.Synchronous(false),
    ClockRelation.MutuallyExclusive,
    ClockRelation.Asynchronous,
    ClockRelation.Unknown
  )
  private def endpoint(path: String, identity: String = "", frequency: Option[String] = None) =
    ClockRelationshipModel.Endpoint(
      path,
      "top",
      path + "_clock",
      if identity.isEmpty then path else identity,
      true,
      "rising",
      frequency
    )
  private def relation(
      source: String,
      destination: String,
      kind: String = "same",
      multiply: Int = 1,
      divide: Int = 1,
      phase: String = "0.0",
      phaseKnown: Boolean = false,
      structural: Boolean = false
  ) =
    val proof = ClockRelationshipModel.proof(kind, structural)
    KernelClockRelationshipSnapshot(
      source + "_" + destination + "_" + kind,
      source,
      destination,
      source + "_clock",
      destination + "_clock",
      kind,
      ClockRelationshipModel.Contract,
      proof,
      ClockRelationshipModel.obligations(kind, proof),
      Option.when(kind == "ratio")(multiply),
      Option.when(kind == "ratio")(divide),
      Option.when(kind == "ratio")(phase),
      Option.when(kind == "synchronous")(phaseKnown)
    )
  private def index(
      nodes: Vector[ClockRelationshipModel.Endpoint],
      relations: Vector[KernelClockRelationshipSnapshot]
  ) =
    new ClockRelationshipModel.Index(
      nodes,
      relations,
      (message, path) => throw new IllegalArgumentException(path + ": " + message)
    )
  private def checkIntent(
      model: ClockRelationshipModel.Index,
      intent: ClockRelationshipModel.RelationIntent,
      valid: Boolean = true,
      source: String = "a",
      destination: String = "b"
  ): Unit =
    val names = Vector("a", "b", "c", "alias")
    def permission = names.flatMap(a => names.map(b => model.permitsSampling(a, b)))
    def timing = scala.util.Try(model.verifyBoundaryIntent(
      "a",
      "b",
      ClockRelationshipModel.intent(ClockRelation.Asynchronous),
      "probe"
    )).isSuccess
    val before = permission
    val timingBefore = timing
    val result =
      scala.util.Try(model.verifyBoundaryIntent(source, destination, intent, "waiver.intent"))
    assert(result.isSuccess == valid, permission == before, timing == timingBefore)
    if result.isFailure then assert(result.failed.get.getMessage.startsWith("waiver.intent:"))

  val tests = Tests:
    test("external Same has closed actual operands and active stream obligation"):
      val s = capture()
      val relationship = s.modules.head.domains.flatMap(_.relationship).head
      assert(relationship.proof == "external_contract", relationship.kind == "same")
      assert(relationship.obligations == Vector("active_edges" -> "identical_stream"))
      val text = ScalaToMlirBridge.fromSnapshot(s).text
      assert(text.contains("nodal.clock_relationship"), text.contains("relationship_v1"))
      assert(
        text.contains("Increment57RelationshipSource.scala"),
        s.digitalExpressions.count(_.reset.exists(_.kind == "sync")) == 2
      )
    test("structural Same retains domain and independent register identities"):
      val s = capture(structural = true)
      assert(s.modules.head.domains.flatMap(_.relationship).head.proof == "structural")
      assert(s.modules.head.declarations.count(_.kind == "register") == 3)
      assert(ScalaToMlirBridge.fromSnapshot(s).text.contains("proof = \"structural\""))
    test("nested Same closure allows mixed actual source roots without merging them"):
      val s = capture(nested = true)
      assert(s.modules.head.domains.flatMap(_.relationship).size == 2)
      assert(s.digitalExpressions.count(_.reset.exists(_.kind == "sync")) == 3)
      assert(ScalaToMlirBridge.fromSnapshot(s).text.contains("identical_stream"))
    test("shaped external inputs retain element identity"):
      assert(ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new Increment57ShapedRelationshipSource)
      ).text.contains("nodal.clock_relationship"))
    test("all categories retain typed fields without inventing permission"):
      categories.foreach: category =>
        val s = ConstructionKernel.inspect(new Increment57RelationshipCategorySource(category))
        assert(ScalaToMlirBridge.fromSnapshot(s).text.contains("nodal.clock_relationship"))
    test("every non Same direct sample remains causal CDC rejection"):
      categories.foreach: category =>
        val result =
          scala.util.Try(ConstructionKernel.inspect(new Increment57RelationshipCategorySource(
            category,
            sample = true
          )))
        assert(result.isFailure, result.failed.get.getMessage.contains("NODAL-CDC-001"))
    test("Same does not select an arbitrary actual CDC source"):
      val result =
        scala.util.Try(ConstructionKernel.inspect(new Increment57RelationshipCategorySource(
          ClockRelation.Same,
          cdcMixed = true
        )))
      assert(
        result.isFailure,
        result.failed.get.getMessage.contains("exactly one known source domain")
      )
    test("Same cannot retarget receiving clock reset release"):
      rejects(ConstructionKernel.inspect(new Increment57RelationshipCategorySource(
        ClockRelation.Same,
        reuseReset = true
      )))
    test("actual structural identity may reuse compatible release"):
      assert(ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new RelationshipSharedReleaseSource(structural = true))
      ).text.contains("structural"))
    test("gate ancestry and constant enabled gate do not authorize Same"):
      assert(scala.util.Try(ConstructionKernel.inspect(new RelationshipGateForgery)).isFailure)
    test("bad ratios and caught errors poison the construction transaction"):
      assert(scala.util.Try(ConstructionKernel.inspect(new RelationshipCaughtFailure)).isFailure)
      for category <- Vector(ClockRelation.Ratio(-1, 1), ClockRelation.Ratio(1, 0)) do
        assert(scala.util.Try(
          ConstructionKernel.inspect(new Increment57RelationshipCategorySource(category))
        ).isFailure)
    test("callback errors reject even when caught"):
      for caught <- Vector(false, true) do
        assert(scala.util.Try(
          ConstructionKernel.inspect(new RelationshipCallbackCapture(caught))
        ).isFailure)
    test("physical aliases reject nonidentity ratio and nonzero periodic phase"):
      for phase <- Vector(false, true) do
        assert(scala.util.Try(
          ConstructionKernel.inspect(new RelationshipAliasContradiction(phase))
        ).isFailure)
    test("bridge rejects forged actual operands proof obligations and fields"):
      val s = capture()
      val r = s.modules.head.domains.flatMap(_.relationship).head
      for bad <- Vector(
          r.copy(sourceClock = r.destinationClock),
          r.copy(destinationClock = r.sourceClock),
          r.copy(source = "foreign"),
          r.copy(destination = "foreign"),
          r.copy(path = "forged"),
          r.copy(proof = "structural"),
          r.copy(obligations = Vector.empty),
          r.copy(contract = "legacy"),
          r.copy(kind = "invalid"),
          r.copy(multiply = Some(1)),
          r.copy(phaseKnown = Some(true))
        )
      do
        rejects(change(s)(_ => bad))
    test("bridge rejects parent cycles and mismatching inherited edges"):
      val s = capture()
      val child = s.modules.head.domains.find(_.relationship.nonEmpty).get
      for changed <- Vector(
          child.copy(edge = Some("falling")),
          child.copy(attributes =
            child.attributes.map((k, v) => if k == "clock_parent" then k -> child.path else k -> v)
          )
        )
      do
        rejects(s.copy(modules =
          s.modules.map(m =>
            m.copy(domains = m.domains.map(d => if d.path == child.path then changed else d))
          )
        ))
    test("descriptor only relation never adds sampling permission"):
      val s = capture()
      val descriptor = s.copy(modules =
        s.modules.map(m => m.copy(domains = m.domains.map(_.copy(relationship = None))))
      )
      val text = ScalaToMlirBridge.fromSnapshot(descriptor).text
      assert(
        !text.contains("nodal.clock_relationship"),
        text.contains("clock_relation = \"alias\"")
      )
    test("normalized exact signed and wrapped ratios preserve period phase"):
      val nodes = Vector(endpoint("a"), endpoint("b"))
      val model = index(
        nodes,
        Vector(
          relation("a", "b", "ratio", 6, 4, "-270.0"),
          relation("a", "b", "ratio", 3, 2, "450.0")
        )
      )
      assert(!model.permitsSampling("a", "b"))
    test("Same chains and physical aliases preserve separate permission semantics"):
      val nodes = Vector(endpoint("a"), endpoint("b"), endpoint("c"), endpoint("alias", "a"))
      val model = index(nodes, Vector(relation("a", "b"), relation("b", "c")))
      assert(model.permitsSampling("a", "c"), !model.permitsSampling("a", "alias"))
    test("consistent diamond is declaration order independent"):
      val nodes = Vector(endpoint("a"), endpoint("b"), endpoint("c"), endpoint("d"))
      val rs = Vector(
        relation("a", "b", "ratio", 2, 1, "90.0"),
        relation("a", "c", "ratio", 2, 1, "90.0"),
        relation("b", "d", "ratio", 1, 2),
        relation("c", "d", "ratio", 1, 2)
      )
      val _ = index(nodes, rs)
      val _ = index(nodes.reverse, rs.reverse)
      assert(scala.util.Try(index(
        nodes,
        rs.updated(3, rs(3).copy(phase = Some("90.0")))
      )).isFailure)
    test("reciprocal ratio cannot erase accumulated phase"):
      val nodes = Vector(endpoint("a"), endpoint("b"), endpoint("c", "a"))
      assert(scala.util.Try(index(
        nodes,
        Vector(
          relation("a", "b", "ratio", 2, 1, "90.0"),
          relation("b", "c", "ratio", 1, 2, "0.0")
        )
      )).isFailure)
    test("known frequency constraints are exact and absent frequency stays absent"):
      val nodes =
        Vector(endpoint("a", frequency = Some("100.0")), endpoint("b", frequency = Some("200.0")))
      val _ = index(nodes, Vector(relation("a", "b", "ratio", 2, 1)))
      assert(scala.util.Try(index(nodes, Vector(relation("a", "b")))).isFailure)
    test("unknown is neutral while asynchronous contradicts timing connectivity"):
      val nodes = Vector(endpoint("a"), endpoint("b"), endpoint("c"))
      val _ = index(nodes, Vector(relation("a", "b"), relation("a", "b", "unknown")))
      assert(scala.util.Try(index(
        nodes,
        Vector(
          relation("a", "b", "synchronous"),
          relation("b", "c", "synchronous"),
          relation("a", "c", "asynchronous")
        )
      )).isFailure)
    test("exclusive is not Same and can coexist with asynchronous classification"):
      val nodes = Vector(endpoint("a"), endpoint("b"))
      val model = index(
        nodes,
        Vector(relation("a", "b", "mutually_exclusive"), relation("a", "b", "asynchronous"))
      )
      assert(!model.permitsSampling("a", "b"))
      assert(scala.util.Try(index(
        nodes,
        Vector(relation("a", "b"), relation("a", "b", "mutually_exclusive"))
      )).isFailure)
    test("synchronous knowledge duplicate cannot silently change meaning"):
      val nodes = Vector(endpoint("a"), endpoint("b"))
      assert(scala.util.Try(index(
        nodes,
        Vector(
          relation("a", "b", "synchronous", phaseKnown = true),
          relation("a", "b", "synchronous", phaseKnown = false)
        )
      )).isFailure)
    test("canonical finite decimal ingress rejects malformed or nonfinite phase"):
      val nodes = Vector(endpoint("a"), endpoint("b"))
      for phase <- Vector("NaN", "Infinity", "1e400", "01.0", "1.00", "90") do
        assert(scala.util.Try(index(
          nodes,
          Vector(relation("a", "b", "ratio", phase = phase))
        )).isFailure)
      val _ = index(
        nodes,
        Vector(relation(
          "a",
          "b",
          "ratio",
          phase = java.lang.Double.toString(Double.MinPositiveValue)
        ))
      )
    test("exact arithmetic resource overflow rejects before expansion"):
      val nodes = (0 to 140).map(i => endpoint("n" + f"$i%03d")).toVector
      val rs = (0 until 140).map(i =>
        relation(nodes(i).path, nodes(i + 1).path, "ratio", 1, Int.MaxValue)
      ).toVector
      assert(scala.util.Try(index(nodes, rs)).isFailure)
    test("4096 bit exact boundary permits fitting multiplication and cancellation"):
      val a = new ClockRelationshipModel.Arithmetic(message =>
        throw new IllegalArgumentException(message)
      )
      val maximum = a.decimal(((BigInt(1) << 4096) - 1).toString)
      val large = a.decimal((BigInt(1) << 4095).toString)
      assert(maximum * a.one == maximum, large + -large == a.zero, large + a.zero == large)
      assert(scala.util.Try(maximum + a.one).isFailure)
      assert(scala.util.Try(large * a.integer(2)).isFailure)
    test("gate control accepts verified Same but not frequency or timing metadata"):
      assert(ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new RelationshipGateControlSource(ClockRelation.Same))
      ).text.contains("nodal.clock_gate"))
      assert(scala.util.Try(
        ConstructionKernel.inspect(new RelationshipGateControlSource(ClockRelation.Ratio(1, 1)))
      ).isFailure)
    test("Same and physical aliases cannot conceal contradictory phase knowledge"):
      val nodes = Vector(endpoint("a"), endpoint("b"), endpoint("c"))
      assert(scala.util.Try(index(
        nodes,
        Vector(
          relation("a", "b"),
          relation("a", "c", "synchronous", phaseKnown = true),
          relation("b", "c", "synchronous", phaseKnown = false)
        )
      )).isFailure)
    test("operating mode exclusion may coexist with relative timing obligations"):
      val nodes = Vector(endpoint("a"), endpoint("b"))
      for kind <- Vector("ratio", "synchronous") do
        val model =
          index(nodes, Vector(relation("a", "b", "mutually_exclusive"), relation("a", "b", kind)))
        assert(!model.permitsSampling("a", "b"))
    test("Same does not reuse explicit release across distinct inputs"):
      rejects(ConstructionKernel.inspect(new RelationshipSharedReleaseSource(structural = false)))
    test("source rejects Reset and Bool cast as Clock before granting relationship"):
      for resetCast <- Vector(false, true) do
        val result =
          scala.util.Try(ConstructionKernel.inspect(new RelationshipForgedClockSource(resetCast)))
        assert(result.isFailure, result.failed.get.getMessage.contains("exact scalar Clock"))
    test("boundary intents remain read only for all six categories without independent proof"):
      val nodes = Vector(endpoint("a"), endpoint("b"), endpoint("c"))
      val model = index(nodes, Vector.empty)
      for relation <- ClockRelation.Same +: categories do
        checkIntent(model, ClockRelationshipModel.intent(relation))
      assert(!model.permitsSampling("a", "b"))
    test("boundary field admission shares canonical kind and bounded arithmetic validation"):
      val ratio = ClockRelationshipModel.intent(ClockRelation.Ratio(2, 1, 90.deg))
      ClockRelationshipModel.verifyBoundaryIntentFields(
        ratio,
        message => throw new IllegalArgumentException(message)
      )
      val malformed = Vector(
        ratio.copy(kind = "other"),
        ratio.copy(multiply = None),
        ratio.copy(multiply = Some(0)),
        ratio.copy(divide = Some(-1)),
        ratio.copy(phase = Some("90.00")),
        ratio.copy(phase = Some("NaN")),
        ratio.copy(phaseKnown = Some(true)),
        ClockRelationshipModel.RelationIntent("same", multiply = Some(1)),
        ClockRelationshipModel.RelationIntent("synchronous")
      )
      val model = index(Vector(endpoint("a"), endpoint("b")), Vector.empty)
      malformed.foreach: intent =>
        assert(scala.util.Try(ClockRelationshipModel.verifyBoundaryIntentFields(
          intent,
          message => throw new IllegalArgumentException(message)
        )).isFailure)
        checkIntent(model, intent, valid = false)
      checkIntent(model, ratio)
    test("boundary Same and Ratio compare independent exact period and phase"):
      val nodes = Vector(endpoint("a"), endpoint("b"))
      val model = index(nodes, Vector(relation("a", "b", "ratio", 2, 1, "90.0")))
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Ratio(4, 2, 450.deg)))
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Same), valid = false)
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Ratio(2, 1)), valid = false)
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Ratio(2, 1, 90.deg)))
    test("boundary independent frequencies constrain period but never invent relative phase"):
      val nodes =
        Vector(endpoint("a", frequency = Some("100.0")), endpoint("b", frequency = Some("200.0")))
      val model = index(nodes, Vector.empty)
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Ratio(2, 1, 90.deg)))
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Same), valid = false)
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Ratio(3, 1)), valid = false)
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Asynchronous))
    test("boundary independent frequency constraints include established timing components"):
      val nodes = Vector(
        endpoint("a", frequency = Some("100.0")),
        endpoint("b"),
        endpoint("c", frequency = Some("400.0"))
      )
      val model = index(nodes, Vector(relation("a", "b", "ratio", 2, 1)))
      checkIntent(
        model,
        ClockRelationshipModel.intent(ClockRelation.Ratio(2, 1, 90.deg)),
        source = "b",
        destination = "c"
      )
      checkIntent(
        model,
        ClockRelationshipModel.intent(ClockRelation.Same),
        valid = false,
        source = "b",
        destination = "c"
      )
    test("boundary timing intent cannot contradict normalized asynchronous classification"):
      val nodes = Vector(endpoint("a"), endpoint("b"), endpoint("c"))
      val model = index(nodes, Vector(relation("a", "b"), relation("b", "c", "asynchronous")))
      for relation <-
          Vector(ClockRelation.Same, ClockRelation.Ratio(1, 1), ClockRelation.Synchronous(true))
      do
        checkIntent(
          model,
          ClockRelationshipModel.intent(relation),
          valid = false,
          destination = "c"
        )
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Unknown), destination = "c")
    test("boundary exclusion and physical or Same identity retain their independent facts"):
      val nodes = Vector(endpoint("a"), endpoint("b"), endpoint("alias", "a"))
      val same = index(nodes, Vector(relation("a", "b")))
      checkIntent(
        same,
        ClockRelationshipModel.intent(ClockRelation.MutuallyExclusive),
        valid = false
      )
      checkIntent(
        same,
        ClockRelationshipModel.intent(ClockRelation.MutuallyExclusive),
        valid = false,
        destination = "alias"
      )
      checkIntent(
        same,
        ClockRelationshipModel.intent(ClockRelation.Ratio(1, 1, 90.deg)),
        valid = false,
        destination = "alias"
      )
      checkIntent(same, ClockRelationshipModel.intent(ClockRelation.Unknown))
      val exclusive = index(nodes, Vector(relation("a", "b", "mutually_exclusive")))
      checkIntent(exclusive, ClockRelationshipModel.intent(ClockRelation.Same), valid = false)
      checkIntent(exclusive, ClockRelationshipModel.intent(ClockRelation.Ratio(1, 1)))
    test("boundary synchronous phase knowledge preserves existing global compatibility"):
      val nodes = Vector(endpoint("a"), endpoint("b"), endpoint("c"))
      val model = index(
        nodes,
        Vector(
          relation("a", "b", "synchronous", phaseKnown = true),
          relation("b", "c", "synchronous")
        )
      )
      checkIntent(
        model,
        ClockRelationshipModel.intent(ClockRelation.Synchronous(false)),
        valid = false
      )
      checkIntent(
        model,
        ClockRelationshipModel.intent(ClockRelation.Asynchronous),
        valid = false,
        destination = "c"
      )
      val ratio = index(nodes, Vector(relation("a", "b", "ratio", 2, 1)))
      checkIntent(ratio, ClockRelationshipModel.intent(ClockRelation.Synchronous(false)))
    test("boundary opposite edges retain correlation and frequency without inferring duty phase"):
      val nodes = Vector(
        endpoint("a"),
        endpoint("b", "a").copy(edge = "falling"),
        endpoint("c").copy(edge = "falling")
      )
      val model = index(nodes, Vector.empty)
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Unknown))
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Same), valid = false)
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Asynchronous), valid = false)
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Ratio(1, 1, 90.deg)))
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Ratio(2, 1)), valid = false)
      checkIntent(
        model,
        ClockRelationshipModel.intent(ClockRelation.Asynchronous),
        destination = "c"
      )
    test("boundary Same rationale never merges distinct derived producers"):
      val nodes = Vector(endpoint("a").copy(external = false), endpoint("b").copy(external = false))
      val model = index(nodes, Vector.empty)
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Same))
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Asynchronous))
      assert(scala.util.Try(index(nodes, Vector(relation("a", "b")))).isFailure)
    test("boundary foreign malformed and missing endpoints reject without mutating permissions"):
      val nodes = Vector(
        endpoint("a"),
        endpoint("b"),
        endpoint("foreign").copy(owner = "other"),
        endpoint("bad").copy(edge = "invalid")
      )
      val model = index(nodes, Vector.empty)
      for target <- Vector("foreign", "bad", "missing") do
        checkIntent(
          model,
          ClockRelationshipModel.intent(ClockRelation.Unknown),
          valid = false,
          destination = target
        )
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Same))
    test("boundary timing connectivity normalizes independent asynchronous exclusions"):
      val nodes = Vector(endpoint("a"), endpoint("b"), endpoint("c"))
      val model =
        index(nodes, Vector(relation("a", "b", "ratio", 2, 1), relation("b", "c", "asynchronous")))
      for relation <-
          Vector(ClockRelation.Same, ClockRelation.Ratio(1, 1), ClockRelation.Synchronous(true))
      do
        checkIntent(
          model,
          ClockRelationshipModel.intent(relation),
          valid = false,
          destination = "c"
        )
      checkIntent(model, ClockRelationshipModel.intent(ClockRelation.Unknown), destination = "c")
    test("boundary unannotated opposite-edge alias retains independently known physical frequency"):
      val nodes = Vector(
        endpoint("a", frequency = Some("100.0")),
        endpoint("b", "a").copy(edge = "falling"),
        endpoint("c", frequency = Some("200.0"))
      )
      val model = index(nodes, Vector.empty)
      checkIntent(
        model,
        ClockRelationshipModel.intent(ClockRelation.Ratio(2, 1, 90.deg)),
        source = "b",
        destination = "c"
      )
      checkIntent(
        model,
        ClockRelationshipModel.intent(ClockRelation.Same),
        valid = false,
        source = "b",
        destination = "c"
      )
      checkIntent(
        model,
        ClockRelationshipModel.intent(ClockRelation.Ratio(1, 1)),
        valid = false,
        source = "b",
        destination = "c"
      )
    test(
      "global physical frequency consistency checks inferred opposite-edge facts without first-match selection"
    ):
      val nodes = Vector(
        endpoint("x", frequency = Some("100.0")),
        endpoint("a", "osc"),
        endpoint("b", "osc").copy(edge = "falling"),
        endpoint("y", frequency = Some("100.0")).copy(edge = "falling"),
        endpoint("c", frequency = Some("400.0"))
      )
      val consistent = index(nodes, Vector(relation("x", "a", "ratio", 2, 1)))
      checkIntent(
        consistent,
        ClockRelationshipModel.intent(ClockRelation.Ratio(2, 1, 90.deg)),
        source = "b",
        destination = "c"
      )
      val compatible = index(
        nodes,
        Vector(
          relation("x", "a", "ratio", 2, 1),
          relation("y", "b", "ratio", 2, 1, "90.0")
        )
      )
      assert(!compatible.permitsSampling("a", "b"))
      val contradictory = scala.util.Try(index(
        nodes,
        Vector(
          relation("x", "a", "ratio", 2, 1),
          relation("y", "b", "ratio", 3, 1)
        )
      ))
      assert(
        contradictory.isFailure,
        contradictory.failed.get.getMessage.contains(
          "inconsistent independently inferred frequencies"
        )
      )
