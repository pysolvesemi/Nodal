package nodal.internal.bridge

import nodal.*
import scala.collection.mutable

/** Independent bounded verification of the actual reset producer graph at snapshot ingress. */
private[nodal] object ResetMlir:
  final case class Facts(
      domains: Map[String, Map[PhysicalControlMlir.Identity, String]],
      producers: Map[String, Map[PhysicalControlMlir.Identity, String]],
      rawAssertions: Set[String]
  )

  def validate(snapshot: ConstructionSnapshot, clocks: ClockMlir.Resolver): Facts =
    def fail(message: String, path: String): Nothing = throw new BridgeException(
      BridgeDiagnostic("NODAL-RDC-001", message, Some(path))
    )
    val values = snapshot.digitalExpressions.filter(_.reset.nonEmpty).map(v => v.path -> v).toMap
    val domains = snapshot.modules.flatMap(_.domains).map(d => d.path -> d).toMap
    val controls = new PhysicalControlMlir.Resolver(snapshot)
    val domainOwners = snapshot.modules.flatMap(m => m.domains.map(d => d.path -> m.path)).toMap
    val polarities = mutable.Map.empty[PhysicalControlMlir.Identity, String]
    val synchronousClocks = mutable.Map.empty[
      PhysicalControlMlir.Identity,
      (ClockMlir.Identity, String)
    ]
    final case class Release(
        kind: String,
        clock: Option[(ClockMlir.Identity, String)] = None,
        producer: Option[String] = None
    )
    val lineageMemo =
      mutable.Map.empty[(String, String), Map[PhysicalControlMlir.Identity, (String, Release)]]
    var work = 0L
    def clock(domain: String): (ClockMlir.Identity, String) =
      clocks.domainClock(
        domain,
        domainOwners.getOrElse(domain, fail("clock domain is not owned", domain))
      )
    def visit(
        path: String,
        owner: String,
        chain: Set[String],
        depth: Int
    ): Map[PhysicalControlMlir.Identity, (String, Release)] =
      work += 1
      if depth > 128 || work > 524288 || chain(path) then
        fail("reset graph is cyclic or excessive", path)
      lineageMemo.getOrElseUpdate((path, owner), compute(path, owner, chain, depth))
    def compute(
        path: String,
        owner: String,
        chain: Set[String],
        depth: Int
    ): Map[PhysicalControlMlir.Identity, (String, Release)] =
      val e = values.getOrElse(path, fail("reset has no actual typed producer", path))
      val r = e.reset.get
      if !Set("boundary", "sync", "combine")(r.kind) ||
        !Set("active_high", "active_low")(r.polarity) || e.dataType != "Reset"
      then
        fail("invalid reset capability contract", path)
      if r.destination.exists(d => !domainOwners.get(d).contains(owner)) then
        fail("reset destination is foreign", path)
      if r.kind == "combine" &&
        (r.inputs.isEmpty || r.inputs.size > 32 || r.stages != 0 || r.destination.nonEmpty ||
          r.release != "raw")
      then
        fail("invalid combined reset contract", path)
      if r.kind == "sync" && r.release != "sync" then
        fail("invalid synchronized reset release", path)
      if e.owner != owner || e.region.nonEmpty then
        fail("reset producer is foreign or regional", path)
      if r.inputs != e.operands ||
        r.kind !=
          (e.operation match
            case "reset_boundary" => "boundary"
            case "rdc_sync" => "sync"
            case "reset_combine" => "combine"
            case _ => "invalid")
      then fail("reset operation differs from retained contract", path)
      if r.kind == "boundary" then
        if r.inputs.size != 1 || !Set("raw", "sync", "external")(r.release) ||
          ((r.release == "raw") != r.destination.isEmpty) || r.stages != 0
        then
          fail("invalid reset boundary release evidence", path)
        val resolved = controls.resolve(r.inputs.head, owner, "Reset")
        val raw = resolved.declaration
        if r.release == "sync" then
          val destination =
            r.destination.getOrElse(fail("synchronous reset lacks destination", path))
          val actualClock = clock(destination)
          if raw.dataType.contains("Reset") then
            val sourceDomain = raw.domain.flatMap(domains.get).getOrElse(fail(
              "synchronous reset lacks owned domain evidence",
              path
            ))
            if !domainOwners.get(sourceDomain.path).contains(owner) ||
              !sourceDomain.resetPolicy.contains("sync") ||
              actualClock != clock(sourceDomain.path)
            then
              fail("synchronous reset boundary differs from actual source clock and edge", path)
          else
            // A shaped port may carry independent synchronous reset elements. The actual
            // consuming binding, not the containing port's domain, owns this scalar proof.
            val destinationDomain = domains.getOrElse(
              destination,
              fail(
                "shaped synchronous reset lacks owned destination",
                path
              )
            )
            val attributes = destinationDomain.attributes.toMap
            if !destinationDomain.resetPolicy.contains("sync") ||
              !attributes.get("effective_reset_source").contains(path)
            then fail("shaped synchronous reset requires its exact consuming Sync binding", path)
            val declared = attributes.getOrElse(
              "reset_source",
              fail(
                "shaped synchronous reset lacks declared physical source",
                path
              )
            )
            if controls.resolve(declared, owner, "Reset").identity != resolved.identity then
              fail("shaped synchronous reset differs from the declared physical element", path)
          synchronousClocks.get(resolved.identity).foreach(previous =>
            if previous != actualClock then
              fail(
                "physical reset element has contradictory synchronous clock or edge evidence",
                path
              )
          )
          synchronousClocks(resolved.identity) = actualClock
        polarities.get(resolved.identity).foreach(p =>
          if p != r.polarity then fail("physical reset has contradictory polarities", path)
        )
        polarities(resolved.identity) = r.polarity
        Map(resolved.identity ->
          (r.polarity, Release(r.release, r.destination.map(clock))))
      else
        val inputs = if r.kind == "sync" then r.inputs.take(1) else r.inputs
        val result = mutable.Map.empty[PhysicalControlMlir.Identity, (String, Release)]
        inputs.foreach: input =>
          visit(input, owner, chain + path, depth + 1).foreach: (root, fact) =>
            work += 1
            if work > 524288 then fail("reset lineage retention exceeds budget", path)
            result.get(root).foreach(previous =>
              if previous != fact then
                fail("independent releases of one reset root reconverge", path)
            )
            result(root) = fact
        if r.kind == "sync" then
          if r.destination.isEmpty || r.inputs.size != 2 || r.stages < 2 || r.stages > 32 ||
            clocks.resolve(r.inputs(1), owner).identity != clock(r.destination.get)._1
          then fail("reset sync has invalid physical destination", path)
          if values.get(r.inputs.head).flatMap(_.reset).forall(_.polarity != r.polarity) then
            fail("synchronizer cannot change reset polarity", path)
          result.iterator.map((root, fact) =>
            root -> (fact._1, Release("sync", producer = Some(path)))
          ).toMap
        else result.toMap
    def safe(path: String, destination: String, asynchronous: Boolean, depth: Int): Boolean =
      work += 1
      if depth > 128 || work > 524288 then fail("reset release proof exceeds depth budget", path)
      val r = values.getOrElse(path, fail("reset has no actual typed producer", path)).reset.get
      r.kind match
        case "sync" => r.destination.exists(d => clock(d) == clock(destination))
        case "combine" => r.inputs.forall(safe(_, destination, asynchronous, depth + 1))
        case "boundary" => r.release != "raw" && (!asynchronous || r.release == "external") &&
          r.destination.exists(d => clock(d) == clock(destination))
        case _ => false
    val domainRoots = mutable.Map.empty[String, Map[PhysicalControlMlir.Identity, String]]
    snapshot.modules.foreach: module =>
      values.valuesIterator.filter(_.owner == module.path).foreach(e =>
        visit(e.path, module.path, Set.empty, 0)
      )
      module.domains.filter(d => d.kind != "required" && d.resetPolicy.exists(_ != "none")).foreach:
        domain =>
          val fields = domain.attributes.toMap
          fields.get("effective_reset_source").foreach: reset =>
            if !safe(reset, domain.path, domain.resetPolicy.get != "sync", 0) then
              fail(
                "asynchronous reset release requires matching owned synchronization or external provider proof",
                domain.path
              )
            domainRoots(domain.path) =
              visit(reset, module.path, Set.empty, 0).view.mapValues(_._1).toMap
    val producerRoots = values.valuesIterator.map(e =>
      e.path -> visit(e.path, e.owner, Set.empty, 0).view.mapValues(_._1).toMap
    ).toMap
    val rawMemo = mutable.Map.empty[String, Boolean]
    def raw(path: String): Boolean = rawMemo.getOrElseUpdate(
      path, {
        val r = values(path).reset.get
        r.kind == "boundary" && r.release == "raw" ||
        r.kind == "combine" && r.inputs.forall(raw)
      }
    )
    Facts(domainRoots.toMap, producerRoots, values.keysIterator.filter(raw).toSet)
