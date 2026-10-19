package nodal.internal.bridge

import nodal.*

/** Physical local domain ingress only. Native sequential ownership checks execution semantics. */
private[nodal] object SequentialMlir:
  private def fail(message: String, path: String): Nothing =
    scala.util.Failure[Nothing](new BridgeException(
      BridgeDiagnostic("NODAL-BRIDGE-056-SEQUENTIAL", message, Some(path))
    )).get

  private def quoted(value: String): String =
    val escaped = value.flatMap:
      case '\\' => "\\\\"
      case '"' => "\\\""
      case character if character.isControl => f"\\${character.toInt & 0xff}%02X"
      case character => character.toString
    s"\"$escaped\""

  private def bindings(
      module: KernelModuleSnapshot,
      resolveDomain: KernelDeclarationSnapshot => String,
      accessedMemories: Set[String],
      crossingDomains: Set[String]
  )
      : Vector[(KernelDomainSnapshot, Vector[String], String)] =
    val declarations = module.declarations.map(value => value.path -> value).toMap
    if declarations.size != module.declarations.size then
      fail("physical domain declarations must have unique identities", module.path)
    val declaredStateDomains = module.declarations.filter(value =>
      value.kind == "register" || (value.kind == "memory" && accessedMemories.contains(value.path))
    ).flatMap(value =>
      value.domain.toVector ++ value.attributes.collect { case ("declared_domain", path) => path }
    ).toSet
    val stateDomains = crossingDomains ++ declaredStateDomains ++ module.domains.filter(value =>
      declaredStateDomains.contains(value.path)
    ).flatMap(_.binding)
    module.domains.sortBy(_.path).flatMap: domain =>
      val fields = domain.attributes.toMap
      if fields.size != domain.attributes.size then
        fail("domain attributes must be unique", domain.path)
      val local = Set("bound", "external").contains(domain.kind)
      val supportedPolicy = domain.resetPolicy.exists(Set("none", "sync", "async").contains)
      val hasState = stateDomains.contains(domain.path)
      if local && fields.contains("clock_source") && !supportedPolicy && hasState then
        fail("executable state requires a supported direct reset policy", domain.path)
      // Unsupported derived/release descriptors without state remain declaration-only.
      val physical = Option.when(local && supportedPolicy)(fields.get("clock_source")).flatten
      physical.toVector.flatMap: clock =>
        if domain.binding.nonEmpty ||
          !domain.edge.exists(Set("rising", "falling").contains) ||
          !domain.resetPolicy.exists(Set("none", "sync", "async").contains)
        then
          fail("physical local domain has an unsupported kind, edge or reset policy", domain.path)
        val reset = if domain.resetPolicy.contains("none") then Vector.empty
        else
          Vector(fields.getOrElse(
            "reset_source",
            fail("reset-bearing domain has no actual reset", domain.path)
          ))
        val polarity = fields.getOrElse(
          "reset_polarity",
          fail("physical domain has no reset polarity", domain.path)
        )
        if !Set("active_high", "active_low").contains(polarity) then
          fail("physical domain reset polarity is invalid", domain.path)
        val operands = Vector(clock) ++ reset
        val exactInputs = operands.zip(Vector("Clock") ++ reset.map(_ => "Reset")).forall:
          (path, dataType) =>
            declarations.get(path).exists(declaration =>
              declaration.kind == "input" && declaration.dataType.contains(dataType) &&
                declaration.generatedScope.isEmpty && module.domains.filter(value =>
                  value.name == resolveDomain(declaration)
                ).exists(value =>
                  value.path == domain.path ||
                    (value.kind == "required" && value.binding.contains(domain.path))
                )
            )
        if hasState && !exactInputs then
          val missing = operands.find(path => !declarations.contains(path))
          fail(
            if missing.nonEmpty then "physical domain operand has no actual owned declaration"
            else "physical domain operand must be an exact same-domain typed input boundary",
            missing.getOrElse(domain.path)
          )
        Option.when(exactInputs)((domain, operands, polarity))

  final class Plan(
      module: KernelModuleSnapshot,
      resolveDomain: KernelDeclarationSnapshot => String,
      accessedMemories: Set[String] = Set.empty,
      crossingDomains: Set[String] = Set.empty
  ):
    private val physical = bindings(module, resolveDomain, accessedMemories, crossingDomains)
    crossingDomains.foreach: domain =>
      if !physical.exists(_._1.path == domain) then
        fail("level crossing endpoint requires an actual supported physical domain binding", domain)
    def roots: Vector[String] = physical.flatMap(_._2).distinct

    def render(
        values: Map[String, (String, String)],
        symbol: (String, String) => String,
        metadata: String => String,
        location: String => String
    ): Vector[String] = physical.map: (domain, inputs, polarity) =>
      val operands = inputs.map(path =>
        values.getOrElse(
          path,
          fail("physical domain input has no actual SSA producer", path)
        )
      )
      val expected = Vector("!nodal.capability<\"clock\">") ++
        inputs.drop(1).map(_ => "!nodal.capability<\"reset\">")
      if operands.map(_._2) != expected then
        fail("physical domain operand capability type differs", domain.path)
      s"\"nodal.local_domain_binding\"(${operands.map(_._1).mkString(", ")}) <{" +
        s"domain = @${quoted(symbol("domain", domain.name))}, reset_polarity = ${quoted(polarity)}, " +
        s"metadata = ${metadata(domain.path)}}> : (${operands.map(_._2).mkString(", ")}) -> ()${location(domain.path)}"
