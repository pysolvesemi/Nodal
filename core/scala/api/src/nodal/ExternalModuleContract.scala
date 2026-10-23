package nodal

/** An explicit declaration-only flavor of Module. External behavior belongs to the supplied
  * implementation, not an executable Nodal body. Signatures support fixed scalar digital inout
  * pins and fixed positive all-resolved Interface arrays with portable black-box placement.
  */
abstract class ExternalModule(externalName: String) extends Module:
  ConstructionKernel.declareExternalModule(this, externalName)

  protected final def externalInterfaceArray[I <: Interface, R <: RoleKind](
      definition: InterfaceType[I],
      role: Role[R],
      count: Int,
      name: String,
      placement: InoutPlacement,
      profile: ResolutionProfile
  ): InterfaceArray[I, R] =
    ConstructionKernel.externalInterfaceArray(
      this,
      definition,
      role,
      count,
      name,
      placement,
      profile
    )

private[nodal] object ExternalModuleContract:
  private def fail(message: String, path: String): Nothing =
    throw new ConstructionException(KernelDiagnostic("NODAL-EXTERNAL-058-001", message, Some(path)))

  def validateName(name: String, path: String): Unit =
    if name == null || name.length > 256 || !name.matches("[A-Za-z_][A-Za-z0-9_$]*") then
      fail("external module requires one canonical exact physical symbol", path)

  def validateArray(
      definition: InterfaceType[?],
      role: Role[?],
      count: Int,
      placement: InoutPlacement,
      profile: ResolutionProfile
  ): Unit =
    if count <= 0 || placement != InoutPlacement.BlackBoxPin ||
      profile != ResolutionProfile.PortableBoundaryOnly
    then
      fail(
        "external InterfaceArray requires fixed positive count and portable black-box placement",
        definition.name
      )
    def fixed(dataType: DataType[?]): Boolean =
      val descriptor = CandidateRuntime.typeDescriptor(dataType)
      Set("Bits", "UInt", "SInt")(descriptor.kind) && descriptor.arguments.size == 1 &&
      (descriptor.arguments.head match
        case width: Int => width > 0
        case width: KernelExpr[?] => width.literal.exists(literal =>
            literal.kind == "integer" && literal.dataType == KernelTypeDescriptor("Integer") &&
              literal.value.toLongOption.exists(_ > 0)
          )
        case _ => false)
    val selected = ConstructionInterfaceLayout.leaves(definition, role)
    val all = (definition.roles :+ role).flatMap(value =>
      ConstructionInterfaceLayout.leaves(definition, value)
    )
    if selected.isEmpty || selected.exists(_.access != "connect") ||
      all.exists(leaf => leaf.protocol != "resolved" || !leaf.dataType.exists(fixed))
    then
      fail(
        "external InterfaceArray requires a complete fixed scalar resolved definition and connect role",
        definition.name
      )

  /** Exact direct external declarations and their actual unselected immediate-child projections. */
  def externalInterfacePaths(snapshot: ConstructionSnapshot): Set[String] =
    val owners = snapshot.modules.filter(_.externalName.nonEmpty).map(_.path).toSet
    val direct = snapshot.interfaceContracts.filter(endpoint =>
      owners(endpoint.owner) && endpoint.boundary.isEmpty
    ).map(_.path).toSet
    direct ++ snapshot.interfaceContracts.filter(endpoint =>
      endpoint.boundary.exists(boundary =>
        boundary.selection.isEmpty && direct(boundary.endpoint) &&
          boundary.instance.exists(path =>
            snapshot.modules.find(_.path == endpoint.owner).exists(
              _.instances.exists(instance =>
                instance.path == path && instance.generatedScope.isEmpty &&
                  snapshot.interfaceContracts.exists(child =>
                    child.path == boundary.endpoint &&
                      child.owner == instance.childModule
                  )
              )
            )
          )
      )
    ).map(_.path)

  def externalNetPaths(snapshot: ConstructionSnapshot): Set[String] =
    val owners = snapshot.modules.iterator.filter(_.externalName.nonEmpty).map(_.path).toSet
    snapshot.modules.iterator.filter(module => owners(module.path))
      .flatMap(_.declarations.iterator.filter(_.kind == "digital-inout").map(_.path)).toSet ++
      snapshot.interfaceContracts.filter(endpoint => owners(endpoint.owner))
        .flatMap(_.members.map(_.path))

  /** Read-only snapshot admission shared by construction and bridge ingress. Native signature
    * verification independently checks the actual emitted declaration body.
    */
  def validate(snapshot: ConstructionSnapshot): Unit =
    val external = snapshot.modules.filter(_.externalName.nonEmpty)
    if external.nonEmpty then
      val arrayOwners =
        external.filter(_.declarations.exists(_.kind == "interface-array")).map(_.path).toSet
      snapshot.modules.foreach: module =>
        module.instances.filter(instance => arrayOwners(instance.childModule)).foreach: instance =>
          if instance.generatedScope.nonEmpty then
            fail("external InterfaceArray instances require direct Module ownership", instance.path)
      val modulePaths = snapshot.modules.iterator.map(_.path).toSet
      def lexicalOwner(path: String): String =
        var current = path
        while !modulePaths(current) && current.contains('.') do
          current = current.substring(0, current.lastIndexOf('.'))
        current
      val bodies = (
        snapshot.parameterExpressions.iterator.map(_.owner) ++
          snapshot.digitalExpressions.iterator.map(_.owner) ++
          snapshot.digitalAssignments.iterator.map(_.owner) ++
          snapshot.digitalConnectivity.iterator.map(_.owner) ++
          snapshot.topology.iterator.map(_.owner) ++
          snapshot.eventProcesses.iterator.map(_.owner) ++
          snapshot.memoryWrites.iterator.map(_.owner) ++
          snapshot.digitalCollections.iterator.map(_.owner) ++
          snapshot.digitalLoops.iterator.map(_.owner) ++
          snapshot.iterationIndices.iterator.map(_.owner) ++
          snapshot.staticPredicates.iterator.map(_.owner) ++
          snapshot.structuralIfs.iterator.map(_.owner) ++
          snapshot.behavioralIfs.iterator.map(_.owner) ++
          snapshot.flatFsms.iterator.map(_.owner) ++
          snapshot.enumSwitches.iterator.map(_.owner) ++
          snapshot.generatedRegions.iterator.map(_.owner) ++
          snapshot.areas.iterator.map(_.owner) ++
          snapshot.shapeIndices.iterator.map(_.owner) ++
          snapshot.shapeViews.iterator.map(_.owner) ++
          snapshot.conservativeArrayBindings.iterator.map(_.owner) ++
          snapshot.instanceArrayRoots.iterator.map(_.owner) ++
          snapshot.analogRegions.iterator.map(_.module) ++
          snapshot.continuousOperators.iterator.map(_.owner) ++
          snapshot.waveformOperators.iterator.map(_.owner) ++
          snapshot.noiseOperators.iterator.map(_.owner) ++
          snapshot.transferOperators.iterator.map(_.owner) ++
          snapshot.analogFunctions.iterator.map(_.owner) ++
          snapshot.analogProcedural.iterator.map(_.owner) ++
          snapshot.analogSemantics.equations.iterator.map(_.metadata.owner) ++
          snapshot.analogSemantics.contributions.iterator.flatMap(
            _.terms.iterator.map(_.metadata.owner)
          ) ++
          snapshot.analogSemantics.components.iterator.map(_.owner) ++
          snapshot.analogSemantics.states.iterator.map(_.owner) ++
          snapshot.analogSemantics.parameters.iterator.map(_.owner) ++
          snapshot.waivers.iterator.map(value => lexicalOwner(value.semanticPath))
      ).toSet
      val nets = snapshot.resolvedNets.iterator.map(net => net.path -> net).toMap
      val signatures = scala.collection.mutable.HashMap.empty[
        String,
        (
            String,
            Vector[(String, Option[String], Vector[(String, String)])],
            Vector[KernelInterfaceSnapshot]
        )
      ]
      external.foreach: module =>
        val name = module.externalName.get
        validateName(name, module.path)
        if module.domains.nonEmpty || module.instances.nonEmpty || module.declarations.isEmpty ||
          bodies(module.path)
        then
          fail(
            "external module is declaration-only and cannot contain executable bodies, domains, instances or empty signatures",
            module.path
          )
        val endpoints = snapshot.interfaceContracts.filter(_.owner == module.path)
        if endpoints.map(_.path).toSet !=
            module.declarations.filter(_.kind == "interface-array").map(_.path).toSet
        then
          fail(
            "external Interface inventory differs from its exact declaration signature",
            module.path
          )
        module.declarations.foreach: pin =>
          if pin.kind == "interface-array" then
            val endpoint = endpoints.find(_.path == pin.path).getOrElse(
              fail("external Interface pin has no complete signature", pin.path)
            )
            val attrs = pin.attributes.toMap
            def fixed(member: KernelInterfaceMemberSnapshot): Boolean =
              member.protocol == "resolved" && member.mode.nonEmpty &&
                member.dataType.matches("(?:Bits|UInt|SInt)\\([1-9][0-9]*\\)")
            if pin.domain.nonEmpty || pin.generatedScope.nonEmpty ||
              pin.interfaceBoundary.nonEmpty ||
              pin.resolvedBoundary.nonEmpty || pin.resolvedMember.nonEmpty ||
              pin.conservativeMember.nonEmpty ||
              endpoint.domain.nonEmpty || endpoint.boundary.nonEmpty ||
              endpoint.definitionContexts.nonEmpty ||
              endpoint.actualMemberTypes.nonEmpty || endpoint.dimensions.size != 1 ||
              !endpoint.dimensions.forall(value => value.toLongOption.exists(_ > 0)) ||
              attrs.get("placement") != Some(InoutPlacement.BlackBoxPin.toString) ||
              attrs.get("profile") != Some(ResolutionProfile.PortableBoundaryOnly.toString) ||
              endpoint.members.isEmpty ||
              !endpoint.members.forall(member => fixed(member) && member.access == "connect") ||
              endpoint.roles.isEmpty || !endpoint.roles.forall(_.members.forall(fixed))
            then
              fail(
                "external Interface signature requires direct fixed positive portable resolved connect pins without domain",
                pin.path
              )
          else
            val net = nets.getOrElse(
              pin.path,
              fail("external pin has no exact resolved declaration", pin.path)
            )
            if pin.kind != "digital-inout" || pin.domain.nonEmpty || pin.generatedScope.nonEmpty ||
              pin.interfaceBoundary.nonEmpty || pin.resolvedBoundary.nonEmpty ||
              pin.resolvedMember.nonEmpty || pin.conservativeMember.nonEmpty ||
              net.boundary.nonEmpty || net.member.nonEmpty || net.operations.nonEmpty ||
              net.placement != InoutPlacement.BlackBoxPin.toString ||
              net.profile != ResolutionProfile.PortableBoundaryOnly.toString ||
              !pin.dataType.contains(net.dataType)
            then
              fail(
                "external signature permits only direct portable black-box digital inout declarations",
                pin.path
              )
        val canonicalEndpoints = endpoints.map(endpoint =>
          endpoint.copy(
            path = module.declarations.find(_.path == endpoint.path).get.name,
            owner = "",
            members = endpoint.members.map(member => member.copy(path = member.name)),
            roles = endpoint.roles.map(role =>
              role.copy(members = role.members.map(member => member.copy(path = member.name)))
            )
          )
        )
        val signature = (
          module.className,
          module.declarations.map(pin => (pin.name, pin.dataType, pin.attributes)),
          canonicalEndpoints
        )
        signatures.get(name).foreach: previous =>
          if previous != signature then
            fail(
              "one external physical symbol cannot name different exact signatures or declaration classes",
              module.path
            )
        signatures.getOrElseUpdate(name, signature)
