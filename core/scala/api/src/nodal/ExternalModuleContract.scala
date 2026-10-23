package nodal

/** An explicit declaration-only flavor of Module. External behavior belongs to the supplied
  * implementation, not an executable Nodal body. The initial signature supports fixed scalar
  * portable digital inout pins only.
  */
abstract class ExternalModule(externalName: String) extends Module:
  ConstructionKernel.declareExternalModule(this, externalName)

private[nodal] object ExternalModuleContract:
  private def fail(message: String, path: String): Nothing =
    throw new ConstructionException(KernelDiagnostic("NODAL-EXTERNAL-058-001", message, Some(path)))

  def validateName(name: String, path: String): Unit =
    if name == null || name.length > 256 || !name.matches("[A-Za-z_][A-Za-z0-9_$]*") then
      fail("external module requires one canonical exact physical symbol", path)

  def externalNetPaths(snapshot: ConstructionSnapshot): Set[String] =
    snapshot.modules.iterator.filter(_.externalName.nonEmpty)
      .flatMap(_.declarations.iterator.map(_.path)).toSet

  /** Read-only snapshot admission shared by construction and bridge ingress. Native signature
    * verification independently checks the actual emitted declaration body.
    */
  def validate(snapshot: ConstructionSnapshot): Unit =
    val external = snapshot.modules.filter(_.externalName.nonEmpty)
    if external.nonEmpty then
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
          snapshot.interfaceContracts.iterator.map(_.owner) ++
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
        (String, Vector[(String, Option[String], Vector[(String, String)])])
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
        module.declarations.foreach: pin =>
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
        val signature = module.className ->
          module.declarations.map(pin => (pin.name, pin.dataType, pin.attributes))
        signatures.get(name).foreach: previous =>
          if previous != signature then
            fail(
              "one external physical symbol cannot name different exact signatures or declaration classes",
              module.path
            )
        signatures.getOrElseUpdate(name, signature)
