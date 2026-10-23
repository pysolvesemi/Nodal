package nodal.internal.bridge

import nodal.*

/** Exact hierarchy ingress and transport. Occurrence-dependent timing and dependency proof belongs
  * to the mandatory native hierarchy owner, not a second bridge CDC engine.
  */
private[nodal] object HierarchyMlir:
  final case class Projection(owner: String, requirement: String, kind: String)

  final class Plan(snapshot: ConstructionSnapshot):
    private def fail(message: String, path: String): Nothing = throw new BridgeException(
      BridgeDiagnostic("NODAL-BRIDGE-057-HIERARCHY", message, Some(path))
    )
    private def check(valid: Boolean, message: String, path: String): Unit =
      if !valid then fail(message, path)
    private val modules = snapshot.modules.map(m => m.path -> m).toMap
    private val domains =
      snapshot.modules.flatMap(m => m.domains.map(d => d.path -> (m.path, d))).toMap
    private val families = snapshot.generatedRegions.filter(region =>
      region.yieldedArea.nonEmpty && region.digitalBody.nonEmpty
    ).map(region => region.path -> region).toMap
    private def actualScope(instance: KernelInstanceSnapshot, owner: String): Boolean =
      instance.generatedScope.forall(scope =>
        families.get(scope.owner).exists(region =>
          region.owner == owner && region.induction == scope.induction
        )
      )
    private val occupied = snapshot.modules.flatMap(_.declarations.map(_.path)).toSet ++
      snapshot.digitalExpressions.map(_.path) ++ snapshot.parameterExpressions.map(_.path)
    private val projectionEntries = snapshot.modules.flatMap: module =>
      module.domains.flatMap: domain =>
        val entries = domain.clockProjection.map(_ -> Projection(module.path, domain.path, "Clock"))
          .toVector ++
          domain.resetProjection.map(_ -> Projection(module.path, domain.path, "Reset"))
        check(
          if domain.kind == "required" then
            domain.edge.isEmpty && domain.resetPolicy.isEmpty &&
            domain.relationship.isEmpty && domain.attributes.isEmpty
          else entries.isEmpty,
          "formal capability requires an exact unconstrained local requirement",
          domain.path
        )
        entries.foreach: (path, _) =>
          check(
            path.startsWith(module.path + ".") && !occupied(path),
            "formal capability identity collides or is foreign",
            path
          )
        entries
    check(
      projectionEntries.map(_._1).distinct.size == projectionEntries.size,
      "formal capability identities must be unique",
      snapshot.root
    )
    val projections: Map[String, Projection] = projectionEntries.toMap
    def formal(domain: String): Boolean = domains.get(domain).exists(_._2.kind == "required")
    private val projectionsByOwner = projectionEntries.groupBy(_._2.owner)
      .view.mapValues(_.sortBy(_._1)).toMap
    def moduleProjections(module: String): Vector[(String, Projection)] =
      projectionsByOwner.getOrElse(module, Vector.empty)

    snapshot.modules.foreach: module =>
      module.domains.foreach: domain =>
        check(
          domain.path.startsWith(module.path + "."),
          "domain is foreign to its owner",
          domain.path
        )
      module.instances.foreach: instance =>
        val child = modules.getOrElse(
          instance.childModule,
          fail("instance child is absent", instance.path)
        )
        val requirements = child.domains.filter(_.kind == "required").map(d => d.path -> d).toMap
        val actuals = instance.actualBindings
        check(
          actuals.size == requirements.size &&
            actuals.map(_.requirement).distinct.size == actuals.size &&
            actuals.map(_.requirement).toSet == requirements.keySet,
          "instance requires exactly one executable binding per child requirement",
          instance.path
        )
        check(
          instance.bindings.map(_._1).distinct.size == instance.bindings.size &&
            instance.bindings.toMap ==
            actuals.map(a => requirements(a.requirement).name -> a.actual).toMap,
          "instance binding dictionary differs from executable local bindings",
          instance.path
        )
        actuals.foreach: binding =>
          check(
            actualScope(instance, module.path) && binding.path.startsWith(instance.path + ".") &&
              Set("default", "inherited", "named")(binding.origin),
            "instance domain binding requires exact lexical ownership and a valid origin",
            binding.path
          )
          val actual = domains.get(binding.actual).filter(_._1 == module.path).map(_._2).getOrElse(
            fail(
              "instance actual must be an exact parent-local domain or requirement",
              binding.path
            )
          )
          val fields = actual.attributes.toMap
          val clock =
            if actual.kind == "required" then actual.clockProjection else fields.get("clock_source")
          val reset = if actual.kind == "required" then actual.resetProjection
          else
            Option.unless(
              actual.resetPolicy.contains("none")
            )(fields.get("effective_reset_source")).flatten
          check(
            clock.contains(binding.clock) && reset == binding.reset,
            "instance capability operands differ from the actual local binding",
            binding.path
          )
          check(
            requirements(binding.requirement).resetProjection.isEmpty || binding.reset.nonEmpty,
            "demanded requirement Reset has no actual operand",
            binding.path
          )
      val proxyKeys = module.declarations.flatMap(_.instancePort.map(p =>
        (p.instance, p.port, p.selection, p.region)
      ))
      check(
        proxyKeys.distinct.size == proxyKeys.size,
        "aliased instance port references must share one declaration",
        module.path
      )
      module.declarations.foreach: declaration =>
        val proxyKind = Set("instance-input", "instance-output")(declaration.kind)
        check(
          proxyKind == declaration.instancePort.nonEmpty,
          "instance port declarations require exact typed boundary evidence",
          declaration.path
        )
        declaration.instancePort.foreach: proxy =>
          val instance = module.instances.find(_.path == proxy.instance).getOrElse(
            fail("instance port does not name an immediate owned instance", declaration.path)
          )
          val port =
            modules(instance.childModule).declarations.find(_.path == proxy.port).getOrElse(
              fail("instance port does not name an exact child declaration", declaration.path)
            )
          check(
            Set("input", "output")(proxy.direction) && port.kind == proxy.direction &&
              declaration.kind == "instance-" + proxy.direction &&
              declaration.dataType.nonEmpty && port.dataType.nonEmpty &&
              declaration.domain.isEmpty &&
              declaration.attributes.isEmpty &&
              (if proxy.selection.isEmpty then
                 declaration.generatedScope == instance.generatedScope && proxy.region.isEmpty
               else
                 instance.generatedScope.nonEmpty && proxy.selection.exists(selection =>
                   selection.family == instance.generatedScope.get.owner &&
                     selection.access == "read_only"
                 ) && declaration.generatedScope.forall(scope => proxy.region.contains(scope.owner))) &&
              actualScope(instance, module.path) &&
              port.generatedScope.isEmpty && declaration.conservativeDimensions.isEmpty,
            "instance port requires exact digital type, direction and direct boundary ownership",
            declaration.path
          )

    def validatePortTypes(
        compare: (
            KernelDeclarationSnapshot,
            KernelDeclarationSnapshot,
            String,
            KernelInstanceSnapshot
        ) => Boolean
    ): Unit =
      snapshot.modules.foreach: module =>
        val instances = module.instances.map(value => value.path -> value).toMap
        module.declarations.foreach: declaration =>
          declaration.instancePort.foreach: proxy =>
            val instance = instances(proxy.instance)
            val port = modules(instance.childModule).declarations.find(_.path == proxy.port).get
            check(
              compare(declaration, port, module.path, instance),
              "instance port type differs from its exact finalized child binding",
              declaration.path
            )

    private val bindingPaths =
      snapshot.modules.flatMap(_.instances.flatMap(_.actualBindings.map(_.path)))
    check(
      bindingPaths.distinct.size == bindingPaths.size,
      "instance domain binding source identities must be unique",
      snapshot.root
    )

    def physicalRoots(module: KernelModuleSnapshot): Vector[String] =
      module.instances.flatMap(_.actualBindings.flatMap(a => Vector(a.clock) ++ a.reset))

    def roots(module: KernelModuleSnapshot): Vector[String] =
      physicalRoots(module) ++
        module.declarations.filter(value =>
          value.instancePort.exists(_.region.isEmpty) && value.generatedScope.isEmpty
        ).map(_.path)

    def renderBindings(
        module: KernelModuleSnapshot,
        values: Map[String, (String, String)],
        symbol: (String, String) => String,
        metadata: String => String,
        location: String => String,
        generatedScope: Option[String] = None
    ): Vector[String] =
      def quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") +
        "\""
      module.instances.filter(_.generatedScope.map(_.owner) == generatedScope)
        .sortBy(_.path).flatMap: instance =>
          instance.actualBindings.sortBy(_.requirement).map: binding =>
            val child = modules(instance.childModule)
            val requirement = child.domains.find(_.path == binding.requirement).get
            val actual = domains(binding.actual)._2
            val operands = (Vector(binding.clock) ++ binding.reset).map(path =>
              values.getOrElse(path, fail("instance actual has no owned SSA producer", path))
            )
            val expected = Vector("!nodal.capability<\"clock\">") ++
              binding.reset.map(_ => "!nodal.capability<\"reset\">")
            check(
              operands.map(_._2) == expected,
              "instance binding capability types differ",
              binding.path
            )
            val instanceName = instance.path.substring(instance.path.lastIndexOf('.') + 1)
            s"\"nodal.instance_domain_binding\"(${operands.map(_._1).mkString(", ")}) <{" +
              s"instance = @${quoted(symbol("instance", instanceName))}, " +
              s"requirement = @${quoted(symbol("domain", requirement.name))}, " +
              s"actual = @${quoted(symbol("domain", actual.name))}, origin = ${quoted(binding.origin)}, " +
              s"metadata = ${metadata(binding.path)}}> : (${operands.map(_._2).mkString(", ")}) -> ()${location(binding.path)}"
