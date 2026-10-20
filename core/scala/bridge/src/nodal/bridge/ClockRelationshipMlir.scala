package nodal.internal.bridge

import nodal.*

/** Independent ingress proof using actual physical/derived Clock operands, not domain metadata. */
private[nodal] object ClockRelationshipMlir:
  final class Plan(snapshot: ConstructionSnapshot, clocks: ClockMlir.Resolver):
    private def fail(message: String, path: String): Nothing = throw new BridgeException(
      BridgeDiagnostic(ClockRelationshipModel.Code, message, Some(path))
    )
    private val domains =
      snapshot.modules.flatMap(m => m.domains.map(d => d.path -> (m.path, d))).toMap
    private val actual = domains.values.toVector.filter((_, d) =>
      d.kind != "required" && d.attributes.exists(_._1 == "clock_source")
    )
    private def identity(path: String, owner: String): (String, Boolean) =
      clocks.resolve(path, owner).identity match
        case ClockMlir.Identity.Physical(value) =>
          ("physical:" + value.root + ":" + value.scalarOffset, true)
        case ClockMlir.Identity.Derived(value) => ("derived:" + value, false)
    private val endpoints = actual.map: (owner, domain) =>
      val clock = domain.attributes.toMap.getOrElse(
        "clock_source",
        fail("relationship domain lacks actual Clock", domain.path)
      )
      val (physical, external) = identity(clock, owner)
      ClockRelationshipModel.Endpoint(
        domain.path,
        owner,
        clock,
        physical,
        external,
        domain.edge.getOrElse("unknown"),
        domain.attributes.toMap.get("frequency_hz")
      )
    private val byPath = endpoints.map(e => e.path -> e).toMap
    private val relationships = domains.values.toVector.flatMap: (owner, domain) =>
      domain.relationship.map: relation =>
        val parent = domain.attributes.toMap.get("clock_parent")
        if domain.kind != "generated" || relation.path != domain.path + ".relationship" ||
          relation.destination != domain.path || !parent.contains(relation.source)
        then
          fail(
            "typed relationship must belong to its exact generated domain and parent",
            domain.path
          )
        val source = byPath.getOrElse(
          relation.source,
          fail("relationship source must be actual and local", relation.path)
        )
        val destination = byPath.getOrElse(
          relation.destination,
          fail("relationship destination must be actual and local", relation.path)
        )
        if source.owner != owner || destination.owner != owner ||
          identity(relation.sourceClock, owner)._1 != source.identity ||
          identity(relation.destinationClock, owner)._1 != destination.identity
        then
          fail(
            "relationship actual Clock operands differ from their domain bindings",
            relation.path
          )
        val fields = domain.attributes.toMap
        val descriptorKind = if relation.kind == "same" then "alias" else relation.kind
        if !fields.get("clock_relation").contains(descriptorKind) ||
          fields.get("clock_multiply") != relation.multiply.map(_.toString) ||
          fields.get("clock_divide") != relation.divide.map(_.toString) ||
          fields.get("phase_degrees") != relation.phase ||
          fields.get("clock_phase_known") != relation.phaseKnown.map(_.toString)
        then
          fail("legacy description disagrees with executable typed relationship", relation.path)
        relation
    private val index = new ClockRelationshipModel.Index(endpoints, relationships, fail)
    def permitsSampling(source: String, destination: String): Boolean =
      index.permitsSampling(source, destination)
    def roots(module: String): Vector[String] =
      relationships.filter(r => byPath(r.destination).owner == module)
        .flatMap(r => Vector(r.sourceClock, r.destinationClock)).distinct
    def render(
        module: KernelModuleSnapshot,
        values: Map[String, (String, String)],
        symbol: (String, String) => String,
        metadata: String => String,
        location: String => String
    ): Vector[String] =
      def quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") +
        "\""
      def domain(path: String): String = "@" + quoted(symbol("domain", domains(path)._2.name))
      relationships.filter(r => byPath(r.destination).owner == module.path).sortBy(_.path).map: r =>
        val operands = Vector(r.sourceClock, r.destinationClock).map(path =>
          values.getOrElse(
            path,
            fail("relationship Clock operand has no actual SSA producer", r.path)
          )
        )
        if operands.exists(_._2 != "!nodal.capability<\"clock\">") then
          fail("relationship operands require exact Clock capability", r.path)
        val fields = Vector(
          "source = " + domain(r.source),
          "destination = " + domain(r.destination),
          "kind = " + quoted(r.kind),
          "contract = " + quoted(r.contract),
          "proof = " + quoted(r.proof)
        ) ++
          r.multiply.map(v => s"multiply = $v : i32") ++ r.divide.map(v => s"divide = $v : i32") ++
          r.phase.map(v => "phase = " + quoted(v)) ++
          r.phaseKnown.map(v => "phase_known = " + v.toString) ++
          Vector(
            "obligations = {" +
              r.obligations.map((k, v) => quoted(k) + " = " + quoted(v)).mkString(", ") + "}",
            "metadata = " + metadata(r.path)
          )
        s"\"nodal.clock_relationship\"(${operands.map(_._1).mkString(", ")}) <{${fields.mkString(", ")}}> : (${operands.map(_._2).mkString(", ")}) -> ()${location(r.destination)}"
