package nodal.internal.bridge

import nodal.*
import scala.collection.mutable

/** Clock identity and actual producer graph. Physical boundary identity stays with its owner. */
private[nodal] object ClockMlir:
  enum Identity:
    case Physical(value: PhysicalControlMlir.Identity)
    case Derived(path: String)

  final case class Resolved(identity: Identity, physical: Option[PhysicalControlMlir.Resolved])

  final class Resolver(snapshot: ConstructionSnapshot, controls: PhysicalControlMlir.Resolver):
    private val domains =
      snapshot.modules.flatMap(m => m.domains.map(d => d.path -> (m.path, d))).toMap
    private val expressions = snapshot.digitalExpressions.map(e => e.path -> e).toMap
    private val declarations = snapshot.modules.flatMap(_.declarations).map(d => d.path -> d).toMap
    private val cache = mutable.Map.empty[(String, String), Resolved]
    private var work = 0L
    private def fail(message: String, path: String): Nothing = throw new BridgeException(
      BridgeDiagnostic("NODAL-CLOCK-057-001", message, Some(path))
    )
    private def charge(depth: Int, path: String): Unit =
      work += 1
      if depth > 128 || work > 524288 then fail("clock proof graph exceeds budget", path)

    private def domain(path: String, owner: String): KernelDomainSnapshot =
      domains.get(path).filter(_._1 == owner).map(_._2).getOrElse(
        fail("clock parent or destination is not an exact owned domain", path)
      )

    def domainClock(path: String, owner: String): (Identity, String) =
      val d = domain(path, owner)
      resolve(
        d.attributes.toMap.getOrElse("clock_source", fail("domain lacks actual Clock", path)),
        owner
      ).identity ->
        d.edge.filter(Set("rising", "falling")).getOrElse(fail("domain lacks actual edge", path))

    def resolve(path: String, owner: String): Resolved = visit(path, owner, Set.empty, 0)

    private def visit(path: String, owner: String, active: Set[String], depth: Int): Resolved =
      charge(depth, path)
      if active(path) then fail("clock producer graph is cyclic", path)
      cache.getOrElseUpdate((path, owner), compute(path, owner, active + path, depth))

    private def compute(path: String, owner: String, active: Set[String], depth: Int): Resolved =
      expressions.get(path).filter(e =>
        e.clock.nonEmpty || OwnedClockContract.Operations(e.operation)
      ) match
        case None =>
          val physical = controls.resolve(path, owner, "Clock")
          Resolved(Identity.Physical(physical.identity), Some(physical))
        case Some(e) =>
          val c = e.clock.getOrElse(fail("owned clock requires its typed producer contract", path))
          val gate = c.kind == "gate"
          val parentCount = if gate then 1 else 2
          val controlCount = if gate then 2 else 1
          if !Set("gate", "mux")(c.kind) || e.operation != "clock_" + c.kind ||
            e.owner != owner || e.region.nonEmpty || e.dataType != "Clock" ||
            e.literal.nonEmpty || e.enumIdentity.nonEmpty || e.caseIds.nonEmpty ||
            e.reset.nonEmpty || e.crossing.nonEmpty || e.memory.nonEmpty || e.structure.nonEmpty ||
            e.materializationIntent.nonEmpty || c.parents.size != parentCount ||
            c.controlKinds.size != controlCount || c.controlDomains.size != controlCount ||
            e.operands.size !=
              (if gate then 3 else 4) ||
              c.contract != OwnedClockContract.contract(c.kind) ||
              c.obligations.size != OwnedClockContract.obligations(c.kind).size ||
              c.obligations.toMap != OwnedClockContract.obligations(c.kind).toMap
          then fail("owned clock requires exact schema, operands and obligations", path)
          val output = domain(c.destination, owner)
          val fields = output.attributes.toMap
          if output.kind != "generated" || fields.get("clock_source") != Some(path) ||
            output.edge != Some(c.edge) || !Set("rising", "falling")(c.edge) ||
            fields.get("clock_parent") != c.parents.headOption ||
            fields.get("clock_parent_1") != c.parents.drop(1).headOption ||
            !fields.get("clock_relation").contains("unknown")
          then fail("clock result and generated domain disagree", path)
          val identities = c.parents.zipWithIndex.map: (parent, index) =>
            val d = domain(parent, owner)
            if d.edge != Some(c.edge) ||
              d.attributes.toMap.get("clock_source") != Some(e.operands(index))
            then
              fail("clock parent edge or actual operand differs", path)
            visit(e.operands(index), owner, active, depth + 1).identity
          if !gate && identities.distinct.size != 2 then
            fail("mux requires two distinct actual parent clocks", path)
          c.controlKinds.zip(c.controlDomains).zipWithIndex.foreach: (control, index) =>
            val (kind, source) = control
            val input = e.operands(parentCount + index)
            val dataType = expressions.get(
              input
            ).map(_.dataType).orElse(declarations.get(input).flatMap(_.dataType))
            if !dataType.contains("Bool") || !Set("constant", "external", "domain")(kind) ||
              ((kind == "domain") != source.nonEmpty)
            then fail("clock controls require exact Bool and provenance category", path)
            source.foreach: actual =>
              val _ = domain(actual, owner)
              if gate && actual != c.parents.head then
                fail("gate control domain differs from parent", path)
          Resolved(Identity.Derived(path), None)

    // Domain topology is independently preflighted, including unused and resetless declarations.
    private val domainDone = mutable.HashSet.empty[String]
    private def preflight(path: String, active: Set[String], depth: Int): Unit =
      charge(depth, path)
      if active(path) then fail("clock domain parent graph is cyclic", path)
      if !domainDone(path) then
        val (owner, d) = domains(path)
        if d.attributes.map(_._1).distinct.size != d.attributes.size then
          fail("clock domain has duplicate attributes", path)
        Vector("clock_parent", "clock_parent_1").flatMap(d.attributes.toMap.get).foreach: parent =>
          val _ = domain(parent, owner)
          preflight(parent, active + path, depth + 1)
        domainDone += path
    domains.keys.foreach(preflight(_, Set.empty, 0))
    expressions.valuesIterator.filter(e =>
      e.clock.nonEmpty || OwnedClockContract.Operations(e.operation)
    )
      .foreach(e => resolve(e.path, e.owner))

    def validateResets(facts: ResetMlir.Facts): Unit =
      val consumers = domains.valuesIterator.flatMap: (owner, d) =>
        d.attributes.toMap.get("clock_source").map(source => resolve(source, owner).identity -> d)
      .toVector.groupMap(_._1)(_._2)
      val state = expressions.valuesIterator.map(e =>
        e.clock.map(c => if c.kind == "gate" then 1L else 22L).getOrElse(0L) +
          e.reset.filter(_.kind == "sync").map(_.stages.toLong).getOrElse(0L)
      ).sum
      if state > 262144 then fail("clock and release state exceeds expansion budget", snapshot.root)
      def ownRelease(
          d: KernelDomainSnapshot,
          producer: String,
          roots: Map[PhysicalControlMlir.Identity, String]
      ): Unit =
        val release = d.attributes.toMap.get("effective_reset_source").flatMap(expressions.get)
        val valid = release.exists(e =>
          e.reset.exists(r =>
            r.kind == "sync" && r.stages == 2 && r.destination.contains(d.path) &&
              r.inputs.size == 2 && r.inputs(1) == producer && facts.rawAssertions(r.inputs.head) &&
              facts.producers.get(r.inputs.head).contains(roots)
          )
        )
        if !valid then
          fail(
            "owned clock requires its independent two-stage output release from raw assertion",
            d.path
          )
      expressions.valuesIterator.filter(_.clock.nonEmpty).foreach: expression =>
        val c = expression.clock.get
        val parentRoots = c.parents.map(p => facts.domains.getOrElse(p, Map.empty))
        if c.kind == "mux" then
          val assertion = expression.operands.last
          if parentRoots.head.isEmpty || parentRoots.distinct.size != 1 ||
            !facts.rawAssertions(assertion) ||
            !facts.producers.get(assertion).contains(parentRoots.head)
          then fail("mux requires common raw assertion and safe parent releases", expression.path)
        val expected = parentRoots.head
        if facts.domains.getOrElse(c.destination, Map.empty) != expected then
          fail("derived domain reset roots differ from parent assertion", expression.path)
        if expected.nonEmpty then
          ownRelease(domain(c.destination, expression.owner), expression.path, expected)
        if c.kind == "mux" then
          consumers.getOrElse(Identity.Derived(expression.path), Vector.empty).foreach: d =>
            if !facts.domains.get(d.path).contains(expected) then
              fail("every mux-clock consumer requires common reset and safe output release", d.path)
            ownRelease(d, expression.path, expected)
