package nodal.internal.bridge

import nodal.*
import scala.collection.mutable

/** Independent bounded verification of the actual reset producer graph at snapshot ingress. */
private[nodal] object ResetMlir:
  def validate(snapshot: ConstructionSnapshot): Unit =
    def fail(message: String, path: String): Nothing = throw new BridgeException(
      BridgeDiagnostic("NODAL-RDC-001", message, Some(path))
    )
    val values = snapshot.digitalExpressions.filter(_.reset.nonEmpty).map(v => v.path -> v).toMap
    val domains = snapshot.modules.flatMap(_.domains).map(d => d.path -> d).toMap
    val declarations = snapshot.modules.flatMap(_.declarations).map(d => d.path -> d).toMap
    val declarationOwners =
      snapshot.modules.flatMap(m => m.declarations.map(d => d.path -> m.path)).toMap
    val domainOwners = snapshot.modules.flatMap(m => m.domains.map(d => d.path -> m.path)).toMap
    val polarities = mutable.Map.empty[String, String]
    var work = 0L
    def clock(domain: String): (String, String) =
      val d = domains.getOrElse(domain, fail("reset destination is not an owned domain", domain))
      (
        d.attributes.toMap.getOrElse(
          "clock_source",
          fail("resolved domain lacks physical clock", domain)
        ),
        d.edge.getOrElse(fail("resolved domain lacks clock edge", domain))
      )
    def visit(
        path: String,
        owner: String,
        chain: Set[String],
        depth: Int
    ): Map[String, (String, String)] =
      work += 1
      if depth > 128 || work > 524288 || chain(path) then
        fail("reset graph is cyclic or excessive", path)
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
        val raw =
          declarations.getOrElse(r.inputs.head, fail("reset boundary lacks physical input", path))
        if raw.kind != "input" || !raw.dataType.contains("Reset") || raw.generatedScope.nonEmpty ||
          !declarationOwners.get(raw.path).contains(owner)
        then fail("reset boundary requires exact owned input", path)
        if r.release == "sync" then
          val sourceDomain = raw.domain.flatMap(domains.get).getOrElse(fail(
            "synchronous reset lacks owned domain evidence",
            path
          ))
          if !sourceDomain.resetPolicy.contains("sync") ||
            !r.destination.exists(d => clock(d) == clock(sourceDomain.path))
          then
            fail("synchronous reset boundary differs from actual source clock and edge", path)
        polarities.get(raw.path).foreach(p =>
          if p != r.polarity then fail("physical reset has contradictory polarities", path)
        )
        polarities(raw.path) = r.polarity
        Map(raw.path ->
          (r.polarity, r.destination.map(d => r.release + clock(d).toString).getOrElse("raw")))
      else
        val inputs = if r.kind == "sync" then r.inputs.take(1) else r.inputs
        val result = mutable.Map.empty[String, (String, String)]
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
            r.inputs(1) != clock(r.destination.get)._1
          then fail("reset sync has invalid physical destination", path)
          if values.get(r.inputs.head).flatMap(_.reset).forall(_.polarity != r.polarity) then
            fail("synchronizer cannot change reset polarity", path)
          result.iterator.map((root, fact) => root -> (fact._1, path)).toMap
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
