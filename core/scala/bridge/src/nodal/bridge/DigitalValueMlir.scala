package nodal.internal.bridge

import nodal.*

import scala.collection.mutable

/** One bounded typed value DAG for finite digital values and isolated collection callbacks. Width
  * and declaration ownership remain in the existing module/type renderer; enum ABI policy remains
  * in EnumMlir.
  */
private[nodal] object DigitalValueMlir:
  enum StaticPurpose:
    case RuntimeValue, Structural, TypeArithmetic

  final case class ScalarType(kind: String, width: String, lower: Long, upper: Long)
  final case class ScalarFacts(kind: String, bounds: Option[ScalarType])
  final case class Rendered(lines: Vector[String], values: Map[String, (String, String)])
  final case class Projection(lines: Vector[String], value: (String, String))
  private final case class Proof(constant: Option[BigInt], nonnegative: Boolean)
  private val Unknown = Proof(None, false)

  private def fail(message: String, path: String): Nothing =
    scala.util.Failure[Nothing](
      new BridgeException(BridgeDiagnostic("NODAL-BRIDGE-054-ENUM", message, Some(path)))
    ).get

  private def requireValid(condition: Boolean, message: String, path: String): Unit =
    if !condition then fail(message, path)

  private def quoted(value: String): String =
    val escaped = value.flatMap:
      case '\\' => "\\\\"
      case '"' => "\\\""
      case character if character.isControl =>
        character.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          .map(byte => f"\\${byte & 0xff}%02X").mkString
      case character => character.toString
    s"\"$escaped\""

  private def operation(
      name: String,
      result: String,
      resultType: String,
      operands: Vector[(String, String)],
      attributes: String,
      sourceLocation: String
  ): String =
    s"$result = ${quoted(name)}(${operands.map(_._1).mkString(", ")}) <$attributes> : " +
      s"(${operands.map(_._2).mkString(", ")}) -> $resultType$sourceLocation"

  private def requiredWidth(kind: String, value: BigInt): Long =
    if kind == "SInt" then value.bitLength.toLong + 1L
    else if value >= 0 then math.max(1, value.bitLength).toLong
    else Long.MaxValue

  final class Plan(
      snapshot: ConstructionSnapshot,
      enums: EnumMlir.Plan,
      clocks: ClockMlir.Resolver,
      hierarchy: HierarchyMlir.Plan,
      relationships: ClockRelationshipMlir.Plan
  ):
    private val modules = snapshot.modules.map(value => value.path -> value).toMap
    private val physicalRootsByOwner = snapshot.modules.map(module =>
      module.path -> hierarchy.physicalRoots(module).toSet
    ).toMap
    private val declarations = snapshot.modules.flatMap(module =>
      module.declarations.map(value => value.path -> (module.path, value))
    ).toMap
    private val interfaceEndpoints =
      snapshot.interfaceContracts.map(value => value.path -> value).toMap
    private val interfaceMemberOwners = snapshot.interfaceContracts.flatMap(endpoint =>
      endpoint.members.map(_.path -> endpoint.path)
    ).toMap
    private val expressions = snapshot.digitalExpressions.map(value => value.path -> value).toMap
    private val eventProcesses = snapshot.eventProcesses.map(value => value.path -> value).toMap
    private val assignmentsByEventProcess =
      snapshot.digitalAssignments.filter(_.eventProcess.nonEmpty)
        .groupBy(_.eventProcess.get)
    private val assignmentTargetCounts =
      snapshot.digitalAssignments.groupMapReduce(_.target)(_ => 1)(_ + _)
    private val eventTargetSets = snapshot.eventProcesses.map(value =>
      value.path -> value.storageTargets.toSet
    ).toMap
    private val eventPorts =
      val processPorts = snapshot.eventProcesses.flatMap(value =>
        Vector(value.trigger) ++ value.storageTargets ++ value.externalCaptures
      ).toSet
      // @none serializes absence of a domain, not sampling permission. Every actual finite
      // unannotated input boundary in this verified event-bearing root keeps that absence,
      // including unused ports and represented collection operands through pure aliases.
      val rootInputs = if eventProcesses.nonEmpty then
        declarations.iterator.collect:
          case (path, (owner, declaration))
              if owner == snapshot.root && declaration.kind == "input" &&
                declaration.domain.isEmpty && declaration.resolvedDomain.isEmpty &&
                declaration.generatedScope.isEmpty && declaration.dataType.exists(finiteType) &&
                assignmentTargetCounts.getOrElse(path, 0) == 0 => path
        .toSet
      else Set.empty[String]
      processPorts ++ rootInputs
    def eventPort(path: String): Boolean = eventPorts(path)

    requireValid(
      snapshot.eventProcesses.size == eventProcesses.size && eventProcesses.size <= DigitalEventContract.MaximumNodes,
      "event process identities must be unique and bounded",
      snapshot.root
    )
    snapshot.eventProcesses.foreach: process =>
      requireValid(
        process.owner == snapshot.root && modules.contains(process.owner) &&
          process.path.startsWith(process.owner + ".") &&
          Set("rising", "falling")(process.edge) && process.storageTargets.nonEmpty &&
          process.storageTargets.distinct == process.storageTargets &&
          process.externalCaptures.distinct == process.externalCaptures &&
          process.externalCaptures.size <= DigitalEventContract.MaximumCaptures,
        "event process requires the actual root, one edge and unique bounded operand inventories",
        process.path
      )
      def boundary(path: String, kind: String, bool: Boolean = false): Unit =
        val declaration = declarations.get(path).filter(_._1 == process.owner).map(_._2)
          .getOrElse(fail("event operand requires an actual owned boundary declaration", path))
        requireValid(
          declaration.kind == kind && declaration.domain.isEmpty &&
            declaration.resolvedDomain.isEmpty && declaration.generatedScope.isEmpty &&
            (!bool || declaration.dataType.contains("Bool")),
          "event operand requires an unannotated exact root boundary",
          path
        )
      boundary(process.trigger, "input", bool = true)
      process.storageTargets.foreach(boundary(_, "output"))
      process.externalCaptures.foreach(boundary(_, "input"))
      val updates = assignmentsByEventProcess.getOrElse(process.path, Vector.empty)
      requireValid(
        updates.map(_.target).sorted == process.storageTargets.sorted &&
          updates.forall(value =>
            value.owner == process.owner && value.region.isEmpty &&
              value.kind == "event_update"
          ),
        "event process storage inventory must match its exact unconditional updates",
        process.path
      )
      process.storageTargets.foreach: target =>
        requireValid(
          assignmentTargetCounts.getOrElse(target, 0) == 1,
          "event storage must have exactly one event writer and no continuous writer",
          target
        )

    private val byOwner = snapshot.digitalExpressions.groupBy(_.owner)
    private val collections = snapshot.digitalCollections.map(value => value.path -> value).toMap
    private val loops = snapshot.digitalLoops.map(value => value.path -> value).toMap
    private val iterationIndices = snapshot.iterationIndices.map(value => value.path -> value).toMap
    private val indicesByRegion = snapshot.iterationIndices.groupBy(_.region)
    private val loopsByOwner = snapshot.digitalLoops.groupBy(_.owner)
    private val predicates = snapshot.staticPredicates.map(value => value.path -> value).toMap
    private val predicatesByRegion = snapshot.staticPredicates.groupBy(_.region)
    private final case class Conditional(
        path: String,
        owner: String,
        parentRegion: Option[String],
        predicate: String,
        trueArm: KernelStructuralArmSnapshot,
        falseArm: KernelStructuralArmSnapshot,
        dataType: Option[String],
        sourceOrdinal: Int,
        behavioralRoot: Option[String]
    )
    private val conditionalRecords = snapshot.structuralIfs.map(value =>
      Conditional(
        value.path,
        value.owner,
        value.parentRegion,
        value.predicate,
        value.trueArm,
        value.falseArm,
        value.dataType,
        value.sourceOrdinal,
        None
      )
    ) ++ snapshot.behavioralIfs.map(value =>
      Conditional(
        value.path,
        value.owner,
        value.parentRegion,
        value.predicate,
        value.trueArm,
        value.falseArm,
        None,
        value.sourceOrdinal,
        Some(value.root)
      )
    )
    private val conditionals = conditionalRecords.map(value => value.path -> value).toMap
    private val conditionalsByRegion =
      conditionalRecords.groupBy(value => value.owner -> value.parentRegion)
    private val arms = conditionalRecords.flatMap(value =>
      Vector(value.trueArm, value.falseArm).map(arm => arm.path -> (value, arm))
    ).toMap
    private val machines = snapshot.flatFsms.map(value => value.path -> value).toMap
    private val stacks = new FsmStackMlir.Plan(snapshot, fail)
    private val composition = new FsmCompositionMlir.Plan(snapshot, fail, stacks.summaries)
    private val timing = new FsmTimingMlir.Plan(snapshot, fail)
    private def machineInputs(value: KernelFlatFsmSnapshot): Vector[String] =
      Vector(value.storage) ++ value.control.toVector.flatMap(control =>
        Vector(control.storage, control.restart, control.cancel, control.enable)
      ) ++ value.timerStorage ++ value.stack.toVector.flatMap(stack =>
        Vector(stack.pointerStorage) ++ stack.frameStorage
      ) ++ value.transitions.map(_.guard)
    private def machineProjections(value: KernelFlatFsmSnapshot): Vector[(String, String, String)] =
      Vector(
        (value.nextLocal, "flat_fsm_next", s"Bits(${value.localWidth})"),
        (value.transitionTaken, "flat_fsm_taken", "Bool")
      ) ++ value.control.toVector.flatMap(control =>
        Vector(
          (control.entered, "flat_fsm_entered", "Bool"),
          (control.exited, "flat_fsm_exited", "Bool")
        )
      ) ++ value.composition.toVector.map(composition =>
        (composition.establishment, FlatFsmRuntime.EstablishmentOperation, "Bool")
      )
    private val privateMachineStorage = snapshot.flatFsms.flatMap(value =>
      Vector(value.storage) ++ value.control.map(_.storage) ++ value.timerStorage ++
        value.stack.toVector.flatMap(stack => Vector(stack.pointerStorage) ++ stack.frameStorage)
    ).toSet
    private val enumDefinitions =
      snapshot.enumDefinitions.map(value => value.identity -> value).toMap
    private val machineResults = snapshot.flatFsms.flatMap(value =>
      machineProjections(value).map((path, _, _) => path -> value)
    ).toMap
    private val actions = snapshot.flatFsms.flatMap(value =>
      value.actions.map(action => action.path -> (value, action))
    ).toMap
    private val switches = snapshot.enumSwitches.map(value => value.path -> value).toMap
    private val switchesByRegion =
      snapshot.enumSwitches.groupBy(value => value.owner -> value.parentRegion)
    private val switchArms = snapshot.enumSwitches.flatMap(value =>
      (value.arms :+ value.invalidArm).map(arm => arm.path -> (value, arm))
    ).toMap
    private val generated = snapshot.generatedRegions.filter(_.digitalBody.nonEmpty)
      .map(value => value.path -> value).toMap
    private val generatedDeclarationOwners = generated.valuesIterator.flatMap(region =>
      region.declarations.map(_ -> region)
    ).toMap
    private val generatedExports = generated.valuesIterator.flatMap(region =>
      region.exports.map(exported => (region.path -> exported.member) -> exported)
    ).toMap
    requireValid(
      generated.valuesIterator.map(
        _.declarations.size.toLong
      ).sum == generatedDeclarationOwners.size &&
        generated.valuesIterator.map(_.exports.size.toLong).sum == generatedExports.size &&
        generatedExports.size <= 65536,
      "generated declaration/export inventories must be unique and bounded",
      snapshot.root
    )
    private val generatedByRegion =
      generated.values.toVector.groupBy(value => value.owner -> value.lexicalParent)
    private final case class Region(
        path: String,
        owner: String,
        parentRegion: Option[String],
        arguments: Vector[KernelDigitalRegionArgument],
        captures: Vector[String]
    )
    private val regions = (snapshot.digitalCollections.map(value =>
      Region(value.path, value.owner, value.parentRegion, value.arguments, value.captures)
    ) ++ snapshot.digitalLoops.map(value =>
      Region(value.path, value.owner, None, value.arguments, value.captures)
    ) ++ arms.valuesIterator.map((value, arm) =>
      Region(arm.path, value.owner, value.parentRegion, arm.arguments, arm.captures)
    ) ++ actions.valuesIterator.map((machine, action) =>
      Region(action.path, machine.owner, machine.parentRegion, action.arguments, action.captures)
    ) ++ switchArms.valuesIterator.map((value, arm) =>
      Region(arm.path, value.owner, value.parentRegion, arm.arguments, arm.captures)
    ) ++ generated.valuesIterator.map(value =>
      Region(
        value.path,
        value.owner,
        value.lexicalParent,
        value.digitalBody.get.arguments,
        value.digitalBody.get.captures
      )
    )).map(value => value.path -> value).toMap
    private def hasBehavioralAncestor(region: String): Boolean =
      var current = Option(region)
      val seen = mutable.HashSet.empty[String]
      while current.nonEmpty do
        val path = current.get
        requireValid(
          seen.add(path) && seen.size <= 128,
          "conditional lexical scope contains a cycle or exceeds depth limit",
          region
        )
        if sequentialRoot(path).nonEmpty then return true
        current =
          regions.getOrElse(path, fail("conditional lexical scope is absent", region)).parentRegion
      false

    private def sequentialRoot(region: String): Option[String] =
      arms.get(region).flatMap(_._1.behavioralRoot)
        .orElse(actions.get(region).map(_._1.path))
        .orElse(switchArms.get(region).map(_._1.root))

    private val arguments = regions.valuesIterator.flatMap(region =>
      region.arguments.map(argument => argument.path -> (region, argument))
    ).toMap
    private val byRegion = snapshot.digitalExpressions.groupBy(_.region)
    private val staticExpressions =
      snapshot.parameterExpressions.map(value => value.path -> value).toMap
    private val staticByRegion = snapshot.parameterExpressions.groupBy(_.region)
    private lazy val loopBoundQuery = new StructuralParameterBinding.Query(snapshot)
    private val childrenByRegion = regions.values.toVector.groupBy(_.parentRegion)
    private val argumentsByRegion = regions.view.mapValues(_.arguments.map(_.path)).toMap
    private def bodyValuePaths(region: String): Vector[String] =
      val pending = mutable.ArrayDeque(region)
      val result = mutable.ArrayBuffer.empty[String]
      var scopes = 0
      while pending.nonEmpty do
        val current = pending.removeLast()
        scopes += 1
        requireValid(
          scopes <= 65536,
          "collection scope traversal exceeds its bounded graph",
          region
        )
        result ++= byRegion.getOrElse(Some(current), Vector.empty).map(_.path)
        result ++= staticByRegion.getOrElse(Some(current), Vector.empty).map(_.path)
        result ++= predicatesByRegion.getOrElse(Some(current), Vector.empty).map(_.path)
        result ++= conditionalsByRegion.getOrElse(
          regions(current).owner -> Some(current),
          Vector.empty
        ).map(_.path)
        result ++= argumentsByRegion.getOrElse(current, Vector.empty)
        result ++= generated.get(current).toVector.flatMap(_.declarations)
        pending ++= childrenByRegion.getOrElse(Some(current), Vector.empty).map(_.path)
      result.toVector

    def valueOwner(path: String): Option[String] =
      expressions.get(path).map(_.owner).orElse(predicates.get(path).map(_.owner))
        .orElse(staticExpressions.get(path).map(_.owner))
        .orElse(iterationIndices.get(path).map(_.owner))
        .orElse(declarations.get(path).map(_._1))
        .orElse(arguments.get(path).map(_._1.owner))
        .orElse(hierarchy.projections.get(path).map(_.owner))
        .orElse(generated.get(path).filter(_.yieldedArea.nonEmpty).map(_.owner))

    def valueRegion(path: String): Option[String] =
      expressions.get(path).flatMap(_.region).orElse(predicates.get(path).flatMap(_.region))
        .orElse(staticExpressions.get(path).flatMap(_.region))
        .orElse(iterationIndices.get(path).map(_.region))
        .orElse(arguments.get(path).map(_._1.path))
        .orElse(declarations.get(path).flatMap(value =>
          value._2.instancePort.flatMap(
            _.region
          ).orElse(value._2.interfaceBoundary.flatMap(_.region))
            .orElse(value._2.generatedScope.map(_.owner))
        ))
        .orElse(generated.get(path).filter(_.yieldedArea.nonEmpty).flatMap(_.lexicalParent))
        .orElse(interfaceMemberOwners.get(path).flatMap(valueRegion))

    def validateInterfaceDefinitionContext(
        context: KernelInterfaceDefinitionContextSnapshot,
        endpointPath: String
    ): Unit =
      val endpoint = interfaceEndpoints.getOrElse(
        endpointPath,
        fail("Interface definition context has no actual endpoint", endpointPath)
      )
      val original = endpoint.boundary.map(_.endpoint).getOrElse(endpointPath)
      val owner = declarations.getOrElse(
        original,
        fail("Interface definition context has no original declaration", endpointPath)
      )._1
      requireValid(
        context.owner == owner && context.sourceOrdinal >= 0 &&
          regions.get(context.region).exists(_.owner == owner),
        "Interface definition requires its actual module and construction region",
        endpointPath
      )
      requireStructuralFamilyScope(Some(context.region), endpointPath)
      var scope = valueRegion(original)
      var count = 0
      while scope.nonEmpty && !scope.contains(context.region) do
        count += 1
        requireValid(count <= 128, "Interface definition ancestry exceeds its budget", endpointPath)
        scope = regions.getOrElse(
          scope.get,
          fail("Interface definition use region is absent", endpointPath)
        ).parentRegion
      requireValid(
        scope.contains(context.region),
        "Interface definition escaped its actual lexical construction region",
        endpointPath
      )

    private def requireStructuralFamilyScope(scope: Option[String], consumer: String): Unit =
      var current = scope
      val visited = mutable.HashSet.empty[String]
      while current.nonEmpty do
        val path = current.get
        requireValid(
          visited.add(path) && visited.size <= 128 &&
            (generated.contains(path) || arms.get(path).exists(_._1.behavioralRoot.isEmpty)),
          "family capability requires actual structural-only lexical ancestry",
          consumer
        )
        current = regions.getOrElse(path, fail("family scope is absent", consumer)).parentRegion

    private def structuralFamilySource(
        path: String,
        owner: String,
        scope: Option[String],
        consumer: String,
        depth: Int = 0
    ): String =
      requireValid(depth < 128, "family capture chain exceeds depth", consumer)
      requireVisible(path, owner, scope, consumer)
      requireStructuralFamilyScope(scope, consumer)
      generated.get(path) match
        case Some(family) =>
          requireValid(
            family.yieldedArea.nonEmpty,
            "family capture requires actual family result",
            consumer
          )
          family.path
        case None =>
          val (region, argument) =
            arguments.getOrElse(path, fail("family capture has no exact argument", consumer))
          requireValid(
            arms.get(region.path).exists(_._1.behavioralRoot.isEmpty) &&
              argument.dataType == "GeneratedFamily" && argument.role == "family_capture",
            "family capture requires exact non-Data structural arm argument",
            consumer
          )
          val index = region.arguments.indexWhere(_.path == path)
          val source = region.captures.lift(index).getOrElse(fail(
            "family argument has no capture edge",
            consumer
          ))
          structuralFamilySource(source, owner, region.parentRegion, consumer, depth + 1)

    /** Resolve only existing typed physical capture edges back to the original binding root. */
    private def physicalControlSource(
        path: String,
        owner: String,
        scope: Option[String],
        consumer: String,
        depth: Int = 0
    ): String =
      requireValid(depth < 128, "physical capture chain exceeds depth", consumer)
      requireVisible(path, owner, scope, consumer)
      requireStructuralFamilyScope(scope, consumer)
      val dataType = expressionType(path)
      requireValid(
        Set("Clock", "Reset")(dataType),
        "physical capture requires a scalar control",
        consumer
      )
      arguments.get(path) match
        case Some((region, argument)) =>
          val offset = if generated.get(region.path).exists(_.yieldedArea.nonEmpty) then 1
          else if arms.get(region.path).exists(_._1.behavioralRoot.isEmpty) then 0
          else fail("physical capture requires an actual family or structural arm", consumer)
          val index = region.arguments.indexWhere(_.path == path) - offset
          val source = region.captures.lift(index).getOrElse(
            fail("physical argument has no actual capture edge", consumer)
          )
          requireValid(
            argument.role == "capture" && expressionType(source) == dataType,
            "physical capture must preserve its exact control type and role",
            consumer
          )
          physicalControlSource(source, owner, region.parentRegion, consumer, depth + 1)
        case None =>
          requireValid(
            scope.isEmpty && physicalRootsByOwner(owner)(path),
            "physical capture must terminate at an actual module-owned binding root",
            consumer
          )
          path

    def requireVisible(
        path: String,
        owner: String,
        region: Option[String],
        consumer: String
    ): Unit =
      requireValid(
        valueOwner(path).contains(owner) && valueRegion(path) == region,
        "digital operand must belong to its exact lexical scope; free values need explicit captures",
        consumer
      )
    private val shapeInputs =
      snapshot.shapeIndices.map(value => value.path -> (value.owner, value.input)) ++
        snapshot.shapeViews.map(value => value.path -> (value.owner, value.input))
    private val shapeByPath = shapeInputs.toMap
    private val generatedShapePaths =
      snapshot.shapeIndices.filter(_.generatedScope.nonEmpty).map(_.path).toSet ++
        snapshot.shapeViews.filter(_.generatedScope.nonEmpty).map(_.path)
    private val assignmentsByRegion =
      snapshot.digitalAssignments.groupBy(value => value.owner -> value.region)
    private val assignmentPaths = snapshot.digitalAssignments.map(_.path).toSet
    private val memoryPortTargets = mutable.HashMap.empty[
      (String, String, String),
      (KernelDeclarationSnapshot, KernelDomainSnapshot)
    ]
    def memoryPort(
        owner: String,
        memory: String,
        domain: String,
        path: String
    ): (KernelDeclarationSnapshot, KernelDomainSnapshot) =
      memoryPortTargets.getOrElseUpdate(
        (owner, memory, domain),
        DigitalStorageMlir.memoryPort(modules(owner), memory, domain, path)
      )
    private val memoryWritesByRegion =
      snapshot.memoryWrites.groupBy(value => value.owner -> value.region)
    private val memoryPathsByOwner = (snapshot.digitalExpressions.flatMap(value =>
      value.memory.map(port => value.owner -> port.memory)
    ) ++ snapshot.memoryWrites.map(value => value.owner -> value.memory))
      .groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    def accessedMemories(owner: String): Set[String] =
      memoryPathsByOwner.getOrElse(owner, Set.empty)
    private val selectorsByPath =
      (snapshot.shapeIndices.map(value => value.path -> value.indices) ++
        snapshot.shapeViews.map(value =>
          value.path -> value.slice.toVector.flatMap(slice =>
            Vector(slice.lower, slice.upperExclusive)
          )
        )).toMap

    requireValid(
      loops.size == snapshot.digitalLoops.size && loops.size <= 65536 &&
        regions.size == collections.size + loops.size + arms.size + generated.size + actions.size + switchArms.size &&
        iterationIndices.size == snapshot.iterationIndices.size && iterationIndices.size <= 65536,
      "duplicate or excessive digital loop, region or index identities",
      snapshot.root
    )
    snapshot.digitalLoops.foreach: loop =>
      requireValid(
        modules.contains(loop.owner) && loop.path.startsWith(loop.owner + ".") &&
          loop.sourceOrdinal >= 0 && loop.maximumTripCount >= 0 && loop.maximum.forall(_ >= 0) &&
          loop.captures.distinct.size == loop.captures.size &&
          loop.arguments.size == loop.captures.size + 1 && loop.arguments.size <= 4098 &&
          !expressions.contains(loop.path) && !declarations.contains(loop.path) &&
          !staticExpressions.contains(loop.path) && !iterationIndices.contains(loop.path),
        "digital loop requires canonical identity, bounds, captures and induction argument",
        loop.path
      )
      loop.generatedRegion.foreach: path =>
        requireValid(
          snapshot.generatedRegions.exists(value =>
            value.path == path && value.owner == loop.owner && value.parent.isEmpty &&
              value.lower == loop.lower &&
              value.upperExclusive == loop.upperExclusive && value.step == loop.step &&
              value.maximum == loop.maximum && value.maximumTripCount == loop.maximumTripCount
          ),
          "digital range partition must retain its exact source domain",
          loop.path
        )
      Vector(loop.lower, loop.upperExclusive, loop.step).foreach: input =>
        input.toLongOption match
          case Some(value) => requireValid(
              input == value.toString,
              "loop bound literal must be canonical signed i64",
              loop.path
            )
          case None => requireVisible(input, loop.owner, None, loop.path)
      loop.captures.foreach(requireVisible(_, loop.owner, None, loop.path))
      try
        val query = loopBoundQuery
        val lower = query.resolve(loop.owner, loop.lower)
        val upper = query.resolve(loop.owner, loop.upperExclusive)
        val step = query.resolve(loop.owner, loop.step)
        if loop.generatedRegion.isEmpty then
          requireValid(
            loop.lower == "0" && loop.step == "1" && query.bounds(upper).lower >= 0,
            "explicit loop requires lower zero, step one and a nonnegative count envelope",
            loop.path
          )
        // Walk the retained operands as well as the normalized value: even an inactive
        // choose branch may not hide a parameter-dependent source step.
        val stepVisited = mutable.HashSet.empty[String]
        val stepPending = mutable.ArrayDeque(loop.step)
        while stepPending.nonEmpty do
          val input = stepPending.removeHead()
          if input.toLongOption.isEmpty && stepVisited.add(input) then
            requireValid(
              !declarations.contains(input) && stepVisited.size <= 65536,
              "loop step requires a parameter-free invariant constant DAG",
              loop.path
            )
            staticExpressions.get(input).foreach(value => stepPending ++= value.operands)
        val stepGraph = query.exportValues(Vector(step))
        requireValid(
          stepGraph.nodes(stepGraph.dimensions.head).literal.nonEmpty,
          "loop step requires a proved invariant constant DAG",
          loop.path
        )
        val domain = IterationDomain.boundedDigital(
          query.bounds(lower),
          query.bounds(upper),
          query.bounds(step),
          loop.maximum,
          identicalBounds = lower == upper
        )
          .fold(problem => fail(problem.message, loop.path), identity)
        requireValid(
          domain.maximumTripCount == loop.maximumTripCount,
          "loop trip envelope differs from its actual static bound DAGs",
          loop.path
        )
      catch
        case error: IllegalArgumentException => fail(error.getMessage, loop.path)
      loop.arguments.zipWithIndex.foreach: (argument, index) =>
        val expectedType =
          if index == 0 then "Integer" else expressionType(loop.captures(index - 1))
        val role = if index == 0 then "induction"
        else if expectedType == "Integer" then "static_capture" else "capture"
        requireValid(
          argument.path == s"${loop.path}.arg$index" && argument.role == role &&
            argument.dataType == expectedType && expectedType != "Real" &&
            !expressions.contains(argument.path) && !declarations.contains(argument.path) &&
            !staticExpressions.contains(argument.path) && !iterationIndices.contains(argument.path),
          "loop argument must retain its canonical identity, role and exact capture type",
          argument.path
        )
      requireValid(
        byRegion.getOrElse(Some(loop.path), Vector.empty).size.toLong +
          staticByRegion.getOrElse(Some(loop.path), Vector.empty).size.toLong +
          indicesByRegion.getOrElse(loop.path, Vector.empty).size + loop.arguments.size <= 4098L,
        "loop body exceeds its bounded value budget",
        loop.path
      )
    loopsByOwner.foreach: (owner, values) =>
      val ordinals = values.map(_.sourceOrdinal) ++ assignmentsByRegion
        .getOrElse(owner -> None, Vector.empty).map(_.sourceOrdinal)
      requireValid(
        ordinals.distinct.size == ordinals.size,
        "module digital effects require distinct source ordinals",
        owner
      )
    def parentRegion(path: String): Option[String] = regions.get(path).flatMap(_.parentRegion)

    /** Follow only actual capture edges; a type or spelling never promotes an index. */
    def isGeneratedIndex(path: String): Boolean =
      val active = mutable.HashSet.empty[String]
      def visit(value: String): Boolean =
        if !active.add(value) || active.size > 128 then false
        else
          arguments.get(value) match
            case Some((scope, argument))
                if generated.contains(scope.path) && argument.role == "induction" => true
            case Some((scope, argument)) =>
              val index = scope.arguments.indexWhere(_.path == argument.path) -
                (scope.arguments.size - scope.captures.size)
              scope.captures.lift(index).exists(visit)
            case None =>
              staticExpressions.get(value).exists(expression => expression.operands.exists(visit))
      visit(path)

    private def finiteType(value: String): Boolean =
      value == "Bool" || Vector(
        "Bits(",
        "UInt(",
        "SInt(",
        "Enum(",
        "Struct(",
        "Vec("
      ).exists(value.startsWith)

    requireValid(
      predicates.size == snapshot.staticPredicates.size && predicates.size <= 65536 &&
        conditionals.size == conditionalRecords.size && conditionals.size <= 65536 &&
        arms.size == 2 * conditionals.size,
      "duplicate or excessive structural predicate, conditional or arm identities",
      snapshot.root
    )
    private def predicateDependencies(roots: Vector[String]): Set[String] =
      val pending = mutable.ArrayDeque.from(roots)
      val seen = mutable.HashSet.empty[String]
      while pending.nonEmpty do
        val path = pending.removeHead()
        if seen.add(path) then
          requireValid(seen.size <= 65536, "predicate consumer graph exceeds its budget", path)
          predicates.get(path).foreach(value =>
            pending ++= value.operands.filter(predicates.contains)
          )
      seen.toSet
    private val structuralPredicates =
      predicateDependencies(snapshot.structuralIfs.map(_.predicate))
    private val behavioralPredicates = predicateDependencies(snapshot.digitalExpressions
      .filter(_.operation == "behavioral_predicate").flatMap(_.operands))
    private def requiresTopology(path: String): Boolean =
      structuralPredicates.contains(path) || !behavioralPredicates.contains(path)

    private def requireStaticPredicateInput(
        path: String,
        owner: String,
        topology: Boolean,
        structural: Boolean = false,
        admittedWidthFormal: String => Boolean = _ => false
    ): Unit =
      val pending = mutable.ArrayDeque(path)
      val seen = mutable.HashSet.empty[String]
      while pending.nonEmpty do
        val input = pending.removeHead()
        if seen.add(input) then
          requireValid(seen.size <= 65536, "static predicate Integer DAG exceeds its budget", path)
          arguments.get(input) match
            case Some((scope, argument))
                if generated.contains(scope.path) && argument.role == "induction" => ()
            case Some((_, argument))
                if admittedWidthFormal(input) &&
                  (argument.role.startsWith("left_width:") ||
                    argument.role.startsWith("right_width:")) => ()
            case Some((scope, argument)) =>
              val capture = scope.arguments.indexWhere(
                _.path == input
              ) - (scope.arguments.size - scope.captures.size)
              requireValid(
                argument.role == "static_capture" && capture >= 0 && capture < scope.captures.size,
                "structural predicate cannot use procedural induction or proof-only width formals",
                path
              )
              pending += scope.captures(capture)
            case None => staticExpressions.get(input) match
                case Some(value) =>
                  requireValid(
                    value.owner == owner && value.dataType == "Integer" && value.unit.forall(
                      _.isEmpty
                    ) &&
                      (value.operation == "integer" && value.literal.nonEmpty && value.operands.isEmpty ||
                        value.literal.isEmpty && IterationDomain.staticArithmetic(
                          value.operation
                        ).exists(_.arity == value.operands.size)),
                    "static predicate requires a closed dimensionless Integer DAG",
                    path
                  )
                  pending ++= value.operands
                case None =>
                  val declaration = declarations.get(input).filter(_._1 == owner).map(_._2)
                    .getOrElse(fail(
                      "static predicate Integer input has no actual owned producer",
                      path
                    ))
                  val attributes = declaration.attributes.toMap
                  requireValid(
                    declaration.kind == "parameter" && declaration.dataType.contains("Integer") &&
                      (!(topology || structural) || attributes.get(
                        "classification"
                      ).contains("structural") &&
                        (!topology || attributes.get(
                          "structural_effects"
                        ).exists(_.split(",").contains("topology")))),
                    "static predicate parameter requires actual structural topology classification",
                    path
                  )

    private val admittedStructuralInputs = mutable.HashSet.empty[(String, String, StaticPurpose)]
    def requireStructuralStaticInput(
        path: String,
        owner: String,
        purpose: StaticPurpose,
        admittedWidthFormal: String => Boolean
    ): Unit =
      val key = (owner, path, purpose)
      if !admittedStructuralInputs.contains(key) then
        requireStaticPredicateInput(
          path,
          owner,
          topology = false,
          structural = true,
          admittedWidthFormal =
            if purpose == StaticPurpose.TypeArithmetic then admittedWidthFormal else _ => false
        )
        admittedStructuralInputs.add(key)

    snapshot.staticPredicates.foreach: predicate =>
      val comparison = Set("eq", "ne", "lt", "le", "gt", "ge").contains(predicate.operation)
      requireValid(
        modules.contains(predicate.owner) && predicate.path.startsWith(predicate.owner + ".") &&
          !expressions.contains(predicate.path) && !declarations.contains(predicate.path) &&
          !staticExpressions.contains(predicate.path) && !arguments.contains(predicate.path) &&
          predicate.region.forall(path => regions.get(path).exists(_.owner == predicate.owner)) &&
          (comparison && predicate.operands.size == 2 || predicate.operation == "not" &&
            predicate.operands.size == 1 || Set("and", "or").contains(predicate.operation) &&
            predicate.operands.size == 2),
        "static predicate requires a canonical closed comparison or Boolean DAG",
        predicate.path
      )
      predicate.operands.foreach: input =>
        requireVisible(input, predicate.owner, predicate.region, predicate.path)
        requireValid(
          expressionType(input) == (if comparison then "Integer" else "StaticPredicate"),
          "static predicate operand has the wrong stage or type",
          predicate.path
        )
        if comparison then
          requireStaticPredicateInput(input, predicate.owner, requiresTopology(predicate.path))
    snapshot.structuralIfs.foreach: conditional =>
      requireValid(
        modules.contains(
          conditional.owner
        ) && conditional.path.startsWith(conditional.owner + ".") &&
          conditional.sourceOrdinal >= 0 &&
          conditional.parentRegion.forall(path =>
            regions.get(path).exists(_.owner == conditional.owner) &&
              (!hasBehavioralAncestor(path) || conditional.dataType.nonEmpty)
          ) &&
          predicates.get(conditional.predicate).exists(value =>
            value.owner == conditional.owner &&
              value.region == conditional.parentRegion
          ) && conditional.dataType.forall(finiteType),
        "structural if requires an owned static predicate and an optional finite read-only result",
        conditional.path
      )
      val operands =
        conditional.predicate +: (conditional.trueArm.captures ++ conditional.falseArm.captures)
      requireValid(
        conditional.dataType match
          case Some(dataType) => expressions.get(conditional.path).exists(value =>
              value.operation == "structural_if" && value.owner == conditional.owner &&
                value.region == conditional.parentRegion && value.operands == operands &&
                value.dataType == dataType && value.literal.isEmpty && value.enumIdentity.isEmpty &&
                value.caseIds.isEmpty
            )
          case None => !expressions.contains(conditional.path),
        "structural if result must match its actual predicate, both capture segments and ABI",
        conditional.path
      )
      Vector(conditional.trueArm -> "then", conditional.falseArm -> "else").foreach:
        (arm, suffix) =>
          requireValid(
            arm.path == s"${conditional.path}.$suffix" && arm.arguments.size == arm.captures.size &&
              arm.captures.distinct.size == arm.captures.size && arm.arguments.size <= 4098 &&
              arm.yieldValue.nonEmpty == conditional.dataType.nonEmpty,
            "structural arm requires canonical identity, unique captures and the exact yield arity",
            arm.path
          )
          arm.captures.foreach(requireVisible(
            _,
            conditional.owner,
            conditional.parentRegion,
            conditional.path
          ))
          arm.arguments.zipWithIndex.foreach: (argument, index) =>
            val expected = expressionType(arm.captures(index))
            requireValid(
              argument.path == s"${arm.path}.arg$index" &&
                argument.role == (if expected == "GeneratedFamily" then "family_capture"
                                  else if expected != "Integer" then "capture"
                                  else if isIterationIndex(arm.captures(index)) then "index_capture"
                                  else "static_capture") &&
                (expected == "GeneratedFamily" && argument.dataType == expected ||
                  expected == "Integer" || finiteType(expected) ||
                  Set("Clock", "Reset")(expected) && argument.dataType == expected) &&
                !expressions.contains(argument.path) && !staticExpressions.contains(
                  argument.path
                ) &&
                !declarations.contains(argument.path) && !predicates.contains(argument.path),
              "structural arm argument requires its exact capture role and canonical identity",
              argument.path
            )
            if expected == "GeneratedFamily" then
              val _ = structuralFamilySource(
                argument.path,
                conditional.owner,
                Some(arm.path),
                argument.path
              )
            if Set("Clock", "Reset")(expected) then
              val _ = physicalControlSource(
                argument.path,
                conditional.owner,
                Some(arm.path),
                argument.path
              )
          arm.yieldValue.foreach(requireVisible(
            _,
            conditional.owner,
            Some(arm.path),
            conditional.path
          ))
          var parent = conditional.parentRegion
          val visited = mutable.HashSet(arm.path)
          while parent.nonEmpty do
            requireValid(
              visited.add(parent.get) && visited.size <= 128,
              "structural lexical scope contains a cycle or exceeds depth limit",
              arm.path
            )
            parent = regions.getOrElse(
              parent.get,
              fail("structural parent scope is absent", arm.path)
            ).parentRegion
    private def behavioralParentRoot(parent: Option[String], owner: String): Option[String] =
      var current = parent
      var root = Option.empty[String]
      val seen = mutable.HashSet.empty[String]
      while current.nonEmpty do
        val path = current.get
        val region = regions.getOrElse(path, fail("behavioral lexical parent is absent", path))
        requireValid(
          seen.add(path) && seen.size <= 128 && region.owner == owner &&
            (sequentialRoot(path).nonEmpty || arms.contains(path) ||
              generated.get(path).exists(_.yieldedArea.nonEmpty)),
          "behavioral ancestry requires actual sequential, structural or family regions",
          path
        )
        root = root.orElse(sequentialRoot(path))
        current = region.parentRegion
      root

    snapshot.behavioralIfs.foreach: conditional =>
      requireValid(
        modules.contains(
          conditional.owner
        ) && conditional.path.startsWith(conditional.owner + ".") &&
          conditional.sourceOrdinal >= 0 && !expressions.contains(conditional.path) &&
          !predicates.contains(conditional.path) && !declarations.contains(conditional.path) &&
          !staticExpressions.contains(conditional.path) && !arguments.contains(conditional.path) &&
          conditional.parentRegion.forall(path =>
            regions.get(path).exists(_.owner == conditional.owner)
          ) &&
          conditional.root == behavioralParentRoot(conditional.parentRegion, conditional.owner)
            .getOrElse(conditional.path),
        "behavioral if requires one owned outer update root and a behavioral lexical parent",
        conditional.path
      )
      requireVisible(
        conditional.predicate,
        conditional.owner,
        conditional.parentRegion,
        conditional.path
      )
      requireValid(
        expressionType(conditional.predicate) == "Bool",
        "behavioral if requires an actual canonical Bool predicate",
        conditional.path
      )
      Vector(conditional.trueArm -> "then", conditional.falseArm -> "else").foreach:
        (arm, suffix) =>
          requireValid(
            arm.path == s"${conditional.path}.$suffix" && arm.arguments.size == arm.captures.size &&
              arm.captures.distinct.size == arm.captures.size && arm.arguments.size <= 4098 &&
              arm.yieldValue.isEmpty,
            "behavioral arm requires canonical identity, unique captures and no yield",
            arm.path
          )
          arm.captures.foreach(requireVisible(
            _,
            conditional.owner,
            conditional.parentRegion,
            arm.path
          ))
          arm.arguments.zipWithIndex.foreach: (argument, index) =>
            val expected = expressionType(arm.captures(index))
            requireValid(
              argument.path == s"${arm.path}.arg$index" &&
                argument.role == sequentialCaptureRole(arm.captures(index)) &&
                (Set("Integer", "Real")(expected) || finiteType(expected)) &&
                !expressions.contains(argument.path) && !staticExpressions.contains(
                  argument.path
                ) &&
                !declarations.contains(argument.path) && !predicates.contains(argument.path),
              "behavioral arm argument requires its actual finite capture and canonical identity",
              argument.path
            )
          var ancestor = conditional.parentRegion
          val visited = mutable.HashSet(arm.path)
          while ancestor.nonEmpty do
            requireValid(
              visited.add(ancestor.get) && visited.size <= 128,
              "behavioral lexical scope contains a cycle or exceeds depth limit",
              arm.path
            )
            ancestor = regions.getOrElse(
              ancestor.get,
              fail("behavioral parent scope is absent", arm.path)
            ).parentRegion
    expressions.valuesIterator.filter(_.operation == "structural_if").foreach(value =>
      requireValid(
        conditionals.contains(value.path),
        "structural result has no actual regions",
        value.path
      )
    )
    generated.valuesIterator.foreach: value =>
      requireValid(
        !value.lexicalParent.exists(hasBehavioralAncestor),
        "behavioral body cannot create generated structure",
        value.path
      )
      val body = value.digitalBody.get
      var lexical = value.lexicalParent
      var nearest: Option[String] = None
      val ancestry = mutable.HashSet(value.path)
      while lexical.nonEmpty do
        val path = lexical.get
        requireValid(
          ancestry.add(path) && ancestry.size <= 128,
          "generated lexical ancestry contains a cycle or exceeds depth",
          value.path
        )
        val parent = regions.getOrElse(path, fail("generated lexical parent is absent", value.path))
        requireValid(
          parent.owner == value.owner &&
            (generated.contains(path) || arms.get(path).exists(_._1.behavioralRoot.isEmpty)),
          "generated lexical ancestry requires owned Generate or structural arms",
          value.path
        )
        if nearest.isEmpty && generated.contains(path) then nearest = Some(path)
        lexical = parent.parentRegion
      requireValid(
        nearest == value.parent,
        "generated ancestry differs from its actual lexical parent chain",
        value.path
      )
      requireValid(
        body.arguments.size == body.captures.size + 1 && body.arguments.size <= 4098 &&
          body.arguments.head.path == value.induction && body.arguments.head.dataType == "Integer" &&
          body.arguments.head.role == "induction" && body.sourceOrdinal >= 0 &&
          body.captures.distinct.size == body.captures.size &&
          (value.yieldedArea.nonEmpty || value.declarations.isEmpty) &&
          !snapshot.digitalLoops.exists(_.generatedRegion.contains(value.path)),
        "digital Generate requires one actual induction and one retained whole-body partition",
        value.path
      )
      requireValid(
        value.exports.isEmpty || value.yieldedArea.nonEmpty,
        "generated exports require an actual yielded Area family",
        value.path
      )
      value.yieldedArea.foreach: area =>
        requireValid(
          value.parent.forall(parent =>
            generated.get(parent).exists(enclosing =>
              enclosing.owner == value.owner && enclosing.yieldedArea.nonEmpty
            )
          ) && snapshot.areas.exists(record =>
            record.path == area && record.owner == value.owner &&
              record.generatedScope.contains(KernelGeneratedScope(value.path, value.induction))
          ) && value.exports.size <= 65536 &&
            value.exports.map(_.member).distinct.size == value.exports.size,
          "generated storage family requires exact owned family ancestry and unique bounded export keys",
          value.path
        )
        value.declarations.foreach: path =>
          val storage = declarations.get(path).filter(_._1 == value.owner).map(_._2)
            .getOrElse(fail("generated storage has no actual module declaration", path))
          requireValid(
            (Set("wire", "register")(storage.kind) || generatedInstancePort(path) ||
              Set("interface-port", "interface-array")(storage.kind)) &&
              storage.generatedScope.contains(KernelGeneratedScope(value.path, value.induction)),
            "family storage must retain its exact generated owner and induction",
            path
          )
        value.exports.foreach: exported =>
          requireValid(
            exported.member.split("\\.", -1).forall(_.matches("[A-Za-z_][A-Za-z0-9_]*")) &&
              (if exported.kind == "storage" then
                 generatedDeclarationOwners.get(exported.target).exists(_.path == value.path) &&
                 generatedStorage(exported.target) &&
                 Set("read_only", "read_write")(exported.access)
               else if exported.kind == "family" then
                 exported.access == "read_only" && generated.get(exported.target).exists(target =>
                   target.yieldedArea.nonEmpty && target.owner == value.owner &&
                     target.parent.contains(value.path) && target.lexicalParent.contains(value.path)
                 )
               else if exported.kind == "instance" then
                 exported.access == "read_only" && modules(value.owner).instances.exists(instance =>
                   instance.path == exported.target && instance.generatedScope.contains(
                     KernelGeneratedScope(value.path, value.induction)
                   )
                 )
               else if exported.kind == "interface" then
                 exported.access == "read_only" && declarations.get(exported.target).exists(
                   (owner, endpoint) =>
                     owner == value.owner && Set(
                       "interface-port",
                       "interface-array"
                     )(endpoint.kind) &&
                       endpoint.interfaceBoundary.isEmpty && endpoint.generatedScope.contains(
                         KernelGeneratedScope(value.path, value.induction)
                       )
                 )
               else false),
            "generated export requires a canonical key, exact direct owner and attested access",
            value.path
          )
      body.captures.foreach(requireVisible(_, value.owner, value.lexicalParent, value.path))
      body.arguments.drop(1).zipWithIndex.foreach: (argument, index) =>
        requireValid(
          argument.path == s"${value.path}.arg${index + 1}" &&
            argument.role == (if expressionType(body.captures(index)) == "Integer" then
                                "static_capture"
                              else "capture"),
          "digital Generate capture alias has an invalid canonical identity or role",
          argument.path
        )

    def generatedStorage(path: String): Boolean = declarations.get(path).exists: (_, declaration) =>
      declaration.generatedScope.exists(scope =>
        generatedDeclarationOwners.get(path).exists(region =>
          region.yieldedArea.nonEmpty && region.path == scope.owner &&
            region.induction == scope.induction && Set("wire", "register")(declaration.kind)
        )
      )

    def generatedInstancePort(path: String): Boolean = declarations.get(path).exists:
      (owner, declaration) =>
        Set("instance-input", "instance-output")(declaration.kind) &&
          declaration.generatedScope.exists(scope =>
            generatedDeclarationOwners.get(path).exists(region =>
              region.yieldedArea.nonEmpty && region.owner == owner && region.path == scope.owner &&
                region.induction == scope.induction
            ) && declaration.instancePort.exists(proxy =>
              modules(owner).instances.exists(instance =>
                instance.path == proxy.instance && instance.generatedScope.contains(scope)
              )
            )
          )

    def generatedDeclaration(path: String): Boolean =
      generatedStorage(path) || generatedInstancePort(path)

    def generatedInterfaceDeclaration(path: String): Boolean = declarations.get(path).exists:
      (owner, declaration) =>
        Set("interface-port", "interface-array")(declaration.kind) &&
          declaration.generatedScope.exists(scope =>
            generatedDeclarationOwners.get(path).exists(family =>
              family.yieldedArea.nonEmpty && family.owner == owner && family.path == scope.owner &&
                family.induction == scope.induction && family.declarations.contains(path)
            ) && snapshot.interfaceContracts.exists(endpoint =>
              endpoint.path == path && endpoint.owner == owner &&
                endpoint.boundary == declaration.interfaceBoundary &&
                endpoint.boundary.forall(boundary =>
                  boundary.selection.isEmpty && boundary.region.contains(scope.owner) &&
                    boundary.instance.exists(instancePath =>
                      modules(owner).instances.exists(instance =>
                        instance.path == instancePath && instance.generatedScope.contains(scope)
                      )
                    )
                )
            )
          )

    /** Interface symbols retain lexical declaration identity; only their Data operands use explicit
      * SSA capture aliases. Reuse this plan's actual structural scope inventory.
      */
    def validateInterfaceEffect(
        effect: KernelDigitalConnectivitySnapshot,
        endpoints: Vector[String]
    ): Unit =
      val selected = endpoints.exists(path =>
        declarations.get(path).exists(_._2.interfaceBoundary
          .exists(_.selection.nonEmpty))
      )
      val ordered = effect.region.nonEmpty || selected
      requireValid(
        effect.sourceOrdinal.nonEmpty == ordered && effect.sourceOrdinal.forall(_ >= 0),
        "Interface effect must retain its actual regional or selected source order",
        effect.path
      )
      if ordered then
        requireStructuralFamilyScope(effect.region, effect.path)
        effect.region.foreach(path =>
          requireValid(
            regions(path).owner == effect.owner,
            "Interface effect region belongs to another Module",
            effect.path
          )
        )
      endpoints.foreach: path =>
        requireValid(
          valueOwner(path).contains(effect.owner),
          "Interface effect endpoint belongs to another Module",
          effect.path
        )
        val declarationScope = valueRegion(path)
        var use = effect.region
        val visited = mutable.HashSet.empty[String]
        while use.nonEmpty && use != declarationScope do
          val current = use.get
          requireValid(
            visited.add(current) && visited.size <= 128,
            "Interface symbol use ancestry is cyclic or excessive",
            effect.path
          )
          use = regions.getOrElse(
            current,
            fail("Interface symbol use has no actual lexical owner", effect.path)
          ).parentRegion
        requireValid(
          use == declarationScope,
          "Interface endpoint escaped its actual lexical declaration or selected use region",
          effect.path
        )
      if ordered then
        (effect.value.toVector ++ effect.enable).foreach(path =>
          requireVisible(path, effect.owner, effect.region, effect.path)
        )

    private def generatedAccess(exported: KernelGeneratedAreaExport, access: String): String =
      if access == "read_only" then "read"
      else
        requireValid(
          access == "read_write" && exported.access == "read_write",
          "generated member cannot expand export access",
          exported.target
        )
        declarations(exported.target)._2.kind

    private lazy val generatedLexicalQueries =
      mutable.HashMap.empty[String, StructuralParameterBinding.Query]
    private lazy val activeGeneratedLexicalQueries = mutable.HashSet.empty[String]
    private def generatedLexicalQuery(path: String): StructuralParameterBinding.Query =
      generatedLexicalQueries.getOrElseUpdate(
        path, {
          requireValid(
            activeGeneratedLexicalQueries.size < 128 && activeGeneratedLexicalQueries.add(path),
            "generated lexical query ancestry contains a cycle or exceeds depth",
            path
          )
          try
            val region =
              regions.getOrElse(path, fail("generated lexical query owner is absent", path))
            val parent = region.parentRegion.map(generatedLexicalQuery).getOrElse(loopBoundQuery)
            generated.get(path) match
              case Some(family) => parent.generatedBody(family)
              case None => arms.get(path) match
                  case Some((conditional, arm)) if conditional.behavioralRoot.isEmpty =>
                    parent.conditionalArm(conditional.parentRegion, arm)
                  case _ =>
                    fail("generated lexical query requires actual Generate or structural arm", path)
          finally activeGeneratedLexicalQueries.remove(path)
        }
      )

    private def generatedSelectionExport(
        owner: String,
        path: String,
        region: Option[String],
        member: KernelGeneratedMemberSnapshot
    ): (
        KernelGeneratedRegionSnapshot,
        KernelGeneratedAreaExport,
        StructuralParameterBinding.SelectedGeneratedContext
    ) =
      val family = generated.getOrElse(
        member.family,
        fail("generated member has no actual family", path)
      )
      val exported = generatedExports.get(member.family -> member.member)
        .getOrElse(fail("generated member has no actual export", path))
      requireValid(
        member.selectionPrefix.size < 128,
        "generated family projection chain exceeds depth",
        path
      )
      val query = region.map(generatedLexicalQuery).getOrElse(loopBoundQuery)
      val context = query.selectedGenerated(owner)
      def imported(source: StructuralParameterBinding.Query, tokens: Vector[String]): Vector[Int] =
        context.importValues(source, tokens.map(source.resolve(owner, _)))
      def ordinalValid(selectedFamily: KernelGeneratedRegionSnapshot, ordinal: String): Unit =
        ordinal.toLongOption match
          case Some(value) => requireValid(
              ordinal == value.toString,
              "generated ordinal literal must be canonical signed i64",
              path
            )
          case None =>
            requireVisible(ordinal, owner, region, path)
            requireStaticPredicateInput(ordinal, owner, topology = true)
        try
          val proof = new StructuralPredicateOrdering.Query[String, Int](
            path =>
              val predicate =
                predicates.getOrElse(path, fail("ordering predicate is absent", path))
              val source = predicate.region.map(generatedLexicalQuery).getOrElse(loopBoundQuery)
              if Set("eq", "ne", "lt", "le", "gt", "ge")(predicate.operation) then
                val operands = imported(source, predicate.operands)
                StructuralPredicateOrdering.Compare(predicate.operation, operands(0), operands(1))
              else StructuralPredicateOrdering.Logic(predicate.operation, predicate.operands)
            ,
            query.bounds,
            _ == _
          )
          def prove(left: Int, right: Int, inclusive: Boolean): Boolean =
            if proof.less(left, right, inclusive) then true
            else
              var current = region
              val visited = mutable.HashSet.empty[String]
              var proved = false
              while current.nonEmpty && !proved do
                val path = current.get
                requireValid(
                  visited.size < 128 && visited.add(path),
                  "ordering lexical ancestry is cyclic or excessive",
                  path
                )
                arms.get(path).foreach: (conditional, arm) =>
                  if conditional.behavioralRoot.isEmpty then
                    proved = proof.under(
                      conditional.predicate,
                      arm.path == conditional.falseArm.path,
                      left,
                      right,
                      inclusive
                    )
                current = regions.getOrElse(
                  path,
                  fail("ordering lexical region is absent", path)
                ).parentRegion
              proved
          val headerQuery =
            selectedFamily.lexicalParent.map(generatedLexicalQuery).getOrElse(loopBoundQuery)
          context.admit(selectedFamily, ordinal, headerQuery, prove)
        catch
          case error: IllegalArgumentException => fail(error.getMessage, path)

      var expected = member.selectionPrefix.headOption.map(_.family).getOrElse(member.family)
      val root =
        generated.getOrElse(expected, fail("selected family root is absent", path))
      member.familyInput.foreach(path =>
        requireValid(
          arguments.get(path).exists(value =>
            value._2.role == "family_capture" && value._2.dataType == "GeneratedFamily"
          ),
          "family input must name its actual non-Data structural capture argument",
          path
        )
      )
      if member.familyInput.isEmpty then
        requireValid(
          root.lexicalParent == region,
          "selected family root escaped handle-definition scope",
          path
        )
      val input = member.familyInput.getOrElse(root.path)
      requireValid(
        structuralFamilySource(
          input,
          owner,
          region,
          path
        ) == root.path,
        "selected family capture must retain its exact root producer",
        path
      )
      member.selectionPrefix.foreach: selection =>
        requireValid(
          selection.family == expected,
          "selected family chain is discontinuous",
          path
        )
        val parent =
          generated.getOrElse(selection.family, fail("selected family is absent", path))
        val next = generatedExports.get(selection.family -> selection.member).getOrElse(
          fail("selected family member has no exact export", path)
        )
        requireValid(
          parent.owner == owner && next.kind == "family" &&
            next.access == "read_only" && generated.get(next.target).exists(child =>
              child.owner == owner && child.parent.contains(
                parent.path
              ) && child.lexicalParent.contains(parent.path)
            ),
          "selected family must follow an exact directly nested family export",
          path
        )
        ordinalValid(parent, selection.ordinal)
        expected = next.target
      requireValid(
        expected == family.path && family.yieldedArea.nonEmpty && family.owner == owner,
        "generated selection requires its exact family export chain",
        path
      )
      val _ = ordinalValid(family, member.ordinal)
      (family, exported, context)

    private def generatedMemberExport(expression: KernelDigitalExpressionSnapshot)
        : (KernelGeneratedRegionSnapshot, KernelGeneratedAreaExport) =
      val member = expression.generatedMember.get
      val (family, exported, _) = generatedSelectionExport(
        expression.owner,
        expression.path,
        expression.region,
        member
      )
      requireValid(
        exported.kind == "storage" &&
          expression.dataType == declarations(exported.target)._2.dataType.get &&
          expression.literal.isEmpty && expression.enumIdentity.isEmpty && expression.caseIds.isEmpty &&
          expression.structure.isEmpty && expression.materializationIntent.isEmpty &&
          expression.memory.isEmpty && expression.crossing.isEmpty && expression.waiver.isEmpty &&
          expression.reset.isEmpty && expression.clock.isEmpty && expression.operands.isEmpty,
        "generated projection requires its exact export type and selected family chain",
        expression.path
      )
      val _ = generatedAccess(exported, member.access)
      family -> exported

    private def selectedInstanceExport(declaration: KernelDeclarationSnapshot)
        : (
            KernelGeneratedRegionSnapshot,
            KernelGeneratedAreaExport,
            StructuralParameterBinding.SelectedGeneratedContext
        ) =
      val boundary = declaration.instancePort.get
      val member = boundary.selection.get
      val result = generatedSelectionExport(
        declarations(declaration.path)._1,
        declaration.path,
        boundary.region,
        member
      )
      requireValid(
        result._2.kind == "instance" && result._2.access == "read_only" &&
          member.access == "read_only" && result._2.target == boundary.instance,
        "selected Instance port requires its exact exported occurrence and read capability",
        declaration.path
      )
      result

    def interfaceSelection(path: String)
        : Option[(
            KernelGeneratedRegionSnapshot,
            KernelGeneratedAreaExport,
            StructuralParameterBinding.SelectedGeneratedContext
        )] =
      declarations.get(path).flatMap: (owner, declaration) =>
        declaration.interfaceBoundary.flatMap: boundary =>
          boundary.selection.map: selection =>
            val result = generatedSelectionExport(owner, path, boundary.region, selection)
            requireValid(
              selection.access == "read_only" && result._2.access == "read_only" &&
                (boundary.instance match
                  case Some(instance) =>
                    result._2.kind == "instance" && result._2.target == instance
                  case None =>
                    result._2.kind == "interface" && result._2.target == boundary.endpoint),
              "selected Interface requires its exact original exported endpoint or child",
              path
            )
            result

    /** An original regional member retains its actual definition query at every lexical use. */
    def interfaceDefinitionTypeContext(path: String)
        : Option[(StructuralParameterBinding.Query, String)] =
      interfaceMemberOwners.get(path).flatMap(interfaceEndpoints.get).flatMap: endpoint =>
        if endpoint.boundary.nonEmpty then None
        else
          val member = endpoint.members.find(_.path == path).getOrElse(
            fail("Interface type use lost its complete member identity", path)
          )
          val application = member.nestedInterfaces.lastOption.map(_._1).getOrElse("")
          endpoint.definitionContexts.find(_.application == application).map: context =>
            validateInterfaceDefinitionContext(context, endpoint.path)
            generatedLexicalQuery(context.region) -> member.path

    /** Exact selected direct endpoint use; definition ownership never comes from the projection. */
    def directInterfaceTypeContext(path: String): Option[(
        StructuralParameterBinding.SelectedGeneratedContext,
        StructuralParameterBinding.Query,
        String
    )] =
      val root = interfaceMemberOwners.getOrElse(path, path)
      interfaceEndpoints.get(root).flatMap: endpoint =>
        endpoint.boundary.filter(value => value.instance.isEmpty && value.selection.nonEmpty).map:
          boundary =>
            val original = interfaceEndpoints.getOrElse(
              boundary.endpoint,
              fail("selected direct type has no actual original endpoint", path)
            )
            val member = endpoint.members.find(_.path == path)
            val source = member.map(value =>
              original.members.find(_.name == value.name).getOrElse(
                fail("selected direct type has no exact original member", path)
              )
            )
            val context = if source.nonEmpty then
              val application = source.get.nestedInterfaces.lastOption.map(_._1).getOrElse("")
              original.definitionContexts.find(_.application == application)
            else None
            context.foreach(validateInterfaceDefinitionContext(_, original.path))
            val originalScope =
              if source.nonEmpty then context.map(_.region) else valueRegion(original.path)
            (
              interfaceSelection(root).get._3,
              originalScope.map(generatedLexicalQuery).getOrElse(loopBoundQuery),
              source.map(_.path).getOrElse(original.path)
            )

    def instancePortQuery(
        path: String,
        queries: StructuralParameterBinding.InstanceQueries
    ): Option[StructuralParameterBinding.Query] =
      val root = interfaceMemberOwners.getOrElse(path, path)
      declarations.get(root).flatMap: (owner, declaration) =>
        val port = declaration.instancePort.map(value => value.instance -> value.selection)
        val endpoint = declaration.interfaceBoundary.flatMap(value =>
          value.instance.map(_ -> value.selection)
        )
        port.orElse(endpoint).map: (instancePath, selection) =>
          val instance = modules(owner).instances.find(_.path == instancePath).getOrElse(
            fail("selected type query has no actual occurrence", path)
          )
          instance.generatedScope match
            case None => queries.query(owner, instance.path)
            case Some(scope) =>
              val body = generatedLexicalQuery(scope.owner)
              if selection.nonEmpty then
                val context = if port.nonEmpty then selectedInstanceExport(declaration)._3
                else interfaceSelection(root).get._3
                queries.selected(owner, instance.path, context, body)
              else queries.generated(owner, instance.path, body)

    private def assignmentRoot(
        path: String,
        owner: String,
        region: Option[String] = None,
        depth: Int = 0
    ): String =
      requireValid(depth <= 128, "digital assignment projection exceeds depth limit", path)
      requireVisible(path, owner, region, path)
      shapeByPath.get(path) match
        case Some((shapeOwner, input)) =>
          requireValid(shapeOwner == owner, "digital assignment projection has another owner", path)
          requireValid(
            !generatedShapePaths.contains(path),
            "digital assignment projection cannot use generated storage",
            path
          )
          assignmentRoot(input, owner, region, depth + 1)
        case None => arguments.get(path) match
            case Some((scope, argument)) =>
              val capture = scope.arguments.indexWhere(_.path == argument.path) -
                (scope.arguments.size - scope.captures.size)
              requireValid(
                (loops.contains(scope.path) || arms.contains(scope.path) || generated.contains(
                  scope.path
                ) ||
                  actions.contains(scope.path) || switchArms.contains(scope.path)) &&
                  capture >= 0 && capture < scope.captures.size,
                "digital assignment requires an actual regional capture of writable storage",
                path
              )
              assignmentRoot(scope.captures(capture), owner, scope.parentRegion, depth + 1)
            case None => path

    private def wholeRegisterTarget(path: String, depth: Int = 0): Boolean =
      requireValid(depth <= 128, "register capture chain exceeds depth limit", path)
      !shapeByPath.contains(path) && (arguments.get(path) match
        case Some((scope, argument)) =>
          val index = scope.arguments.indexWhere(_.path == argument.path) -
            (scope.arguments.size - scope.captures.size)
          scope.captures.lift(index).exists(wholeRegisterTarget(_, depth + 1))
        case None => declarations.get(path).exists(_._2.kind == "register"))

    def boundaryRoot(path: String, owner: String): KernelDeclarationSnapshot =
      val root = assignmentRoot(path, owner)
      declarations.get(root).filter(_._1 == owner).map(_._2).getOrElse(
        fail("capability wiring requires an actual owned boundary declaration", path)
      )

    private def sequentialCaptureRole(path: String): String =
      val isStatic = expressionType(path) == "Integer" &&
        (staticExpressions.contains(path) || declarations.get(path).exists(value =>
          value._2.kind == "parameter"
        ) || arguments.get(path).exists(_._2.role == "static_capture") || isGeneratedIndex(path))
      if isStatic then "static_capture" else "capture"

    private def validateSequentialArm(
        owner: String,
        parent: Option[String],
        path: String,
        captures: Vector[String],
        regionArguments: Vector[KernelDigitalRegionArgument],
        prefix: Vector[(String, String)]
    ): Unit =
      requireValid(
        path.startsWith(owner + ".") && captures.distinct.size == captures.size &&
          regionArguments.size == captures.size + prefix.size && regionArguments.size <= 4098,
        "sequential region requires unique captures and its exact bounded context prefix",
        path
      )
      captures.foreach(requireVisible(_, owner, parent, path))
      regionArguments.zipWithIndex.foreach: (argument, index) =>
        val (expected, role) = prefix.lift(index).getOrElse {
          val dataType = expressionType(captures(index - prefix.size))
          dataType -> sequentialCaptureRole(captures(index - prefix.size))
        }
        requireValid(
          argument.path == s"$path.arg$index" && argument.role == role &&
            prefix.lift(index).forall(_._1 == argument.dataType) &&
            (Set("Integer", "Real")(expected) || finiteType(expected)) &&
            !expressions.contains(argument.path) && !declarations.contains(argument.path) &&
            !staticExpressions.contains(argument.path) && !predicates.contains(argument.path),
          "sequential argument requires its canonical identity, role and exact actual capture type",
          argument.path
        )

    requireValid(
      machines.size == snapshot.flatFsms.size && machines.size <= 4096 &&
        switches.size == snapshot.enumSwitches.size && switches.size <= 65536 &&
        machineResults.size == snapshot.flatFsms.map(value =>
          2 + 2 * value.control.size + value.composition.size
        ).sum &&
        privateMachineStorage.size == snapshot.flatFsms.map(value =>
          1 + value.control.size + value.timerStorage.size + value.stack.map(s =>
            1 + s.capacity
          ).getOrElse(0)
        ).sum &&
        snapshot.flatFsms.map(_.storage).distinct.size == machines.size &&
        actions.size == snapshot.flatFsms.map(_.actions.size).sum &&
        switchArms.size == snapshot.enumSwitches.map(value => value.arms.size + 1).sum,
      "duplicate or excessive FSM, result, action or switch identities",
      snapshot.root
    )
    declarations.valuesIterator.foreach: (owner, declaration) =>
      if declaration.attributes.exists(_._1 == "initializer_policy") then
        requireValid(
          privateMachineStorage(declaration.path),
          "source conditional initialization belongs to actual private FSM storage",
          declaration.path
        )
        val _ = DigitalStorageMlir.initializerPolicy(modules(owner), declaration)
    private var sequentialDependencyWork = 0
    snapshot.flatFsms.foreach: machine =>
      val path = machine.path
      val canonical = enumDefinitions.get(machine.enumIdentity).getOrElse(
        fail("flat FSM canonical enum is absent", path)
      )
      val cases = canonical.cases.map(_.id)
      val caseSet = cases.toSet
      val domain =
        modules.get(machine.owner).toVector.flatMap(_.domains).find(_.path == machine.domain)
      val contextual = domain.exists(value =>
        value.kind == "required" && !value.binding.exists(binding =>
          modules(machine.owner).domains.exists(_.path == binding)
        )
      )
      requireValid(
        modules.contains(machine.owner) && path.startsWith(machine.owner + ".") &&
          machine.parentRegion.isEmpty && machine.sourceOrdinal >= 0 && machine.localWidth > 0 &&
          machine.localWidth <= 4096 && machine.states.nonEmpty && machine.states.size <= 4096 &&
          machine.transitions.size.toLong + machine.actions.size <= 65536 &&
          machine.actions.map(_.captures.size.toLong).sum <= 8192 &&
          Set("error", "recover_to_initial")(machine.illegalState) &&
          (contextual || domain.exists(value =>
            Set("bound", "external")(value.kind) &&
              value.resetPolicy.exists(Set("none", "sync", "async")) &&
              value.attributes.exists(_._1 == "clock_source")
          )) &&
          !expressions.contains(path) && !declarations.contains(path) && !regions.contains(path) &&
          !conditionals.contains(path) && !switches.contains(path),
        "flat FSM requires one bounded owned direct physical-domain or unresolved local requirement",
        path
      )
      def validateInitializerPolicy(storage: String): Unit =
        requireValid(
          DigitalStorageMlir.initializerPolicy(
            modules(machine.owner),
            declarations(storage)._2
          ) == Option.when(contextual)("if_reset"),
          "FSM initialization must retain its exact physical or contextual reset policy",
          path
        )
      requireVisible(machine.storage, machine.owner, None, path)
      requireValid(
        declarations.get(machine.storage).exists((owner, value) =>
          owner == machine.owner && value.kind == "register" && value.generatedScope.isEmpty &&
            value.dataType.contains(s"Bits(${machine.localWidth})") &&
            (value.domain.contains(machine.domain) || value.attributes.contains(
              "declared_domain" -> machine.domain
            ))
        ),
        "flat FSM storage must be an actual whole local Bits register in its exact domain",
        path
      )
      validateInitializerPolicy(machine.storage)
      requireValid(
        machine.nextLocal == path + ".next" && machine.transitionTaken == path + ".taken",
        "flat FSM results must retain canonical next and taken identities",
        path
      )
      machine.control.foreach: control =>
        requireValid(
          control.entered == path + ".entered" && control.exited == path + ".exited",
          "controlled FSM events must retain canonical identities",
          path
        )
        requireVisible(control.storage, machine.owner, None, path)
        requireValid(
          declarations.get(control.storage).exists((owner, value) =>
            owner == machine.owner && value.kind == "register" && value.generatedScope.isEmpty &&
              value.dataType.contains("Bits(2)") && (value.domain.contains(machine.domain) ||
                value.attributes.contains("declared_domain" -> machine.domain))
          ),
          "controlled FSM status must be a distinct actual local two-bit register",
          path
        )
        validateInitializerPolicy(control.storage)
        Vector(control.restart, control.cancel, control.enable).foreach: input =>
          requireVisible(input, machine.owner, None, path)
          requireValid(
            expressionType(input) == "Bool",
            "controlled FSM requires actual Bool controls",
            path
          )
        val initial = DigitalStorageMlir.initializer(declarations(control.storage)._2)
        requireValid(
          if domain.get.resetPolicy.contains("none") then initial.isEmpty
          else
            initial.flatMap(expressions.get).exists(value =>
              value.owner == machine.owner && value.region.isEmpty && value.dataType == "Bits(2)" &&
                value.operation == "integer" && value.operands.isEmpty && value.literal.contains(
                  "0"
                )
            )
          ,
          "controlled FSM status resets to exact Dormant or stays deliberately uninitialized",
          path
        )
      machine.timerStorage.foreach: storage =>
        requireVisible(storage, machine.owner, None, path)
        requireValid(
          declarations.get(storage).exists((owner, value) =>
            owner == machine.owner && value.kind == "register" && value.generatedScope.isEmpty &&
              value.dataType.contains("UInt(31)") && (value.domain.contains(machine.domain) ||
                value.attributes.contains("declared_domain" -> machine.domain))
          ),
          "FSM timer must be a distinct actual local UInt31 register in its exact domain",
          path
        )
        validateInitializerPolicy(storage)
        val initial = DigitalStorageMlir.initializer(declarations(storage)._2)
        requireValid(
          if domain.get.resetPolicy.contains("none") then initial.isEmpty
          else
            initial.flatMap(expressions.get).exists(value =>
              value.owner == machine.owner && value.region.isEmpty && value.dataType == "UInt(31)" &&
                value.operation == "integer" && value.operands.isEmpty && value.literal.contains(
                  "0"
                )
            )
          ,
          "FSM timer resets to exact zero or stays deliberately uninitialized",
          path
        )
      machine.stack.foreach: stack =>
        (Vector(stack.pointerStorage -> s"UInt(${stack.pointerWidth})") ++
          stack.frameStorage.map(_ -> s"Bits(${machine.localWidth})")).foreach:
          (storage, expected) =>
            requireVisible(storage, machine.owner, None, path)
            requireValid(
              declarations.get(storage).exists((owner, value) =>
                owner == machine.owner && value.kind == "register" && value.generatedScope.isEmpty &&
                  value.dataType.contains(expected) && (value.domain.contains(machine.domain) ||
                    value.attributes.contains("declared_domain" -> machine.domain))
              ),
              "FSM stack storage must be distinct whole private registers in its exact domain",
              path
            )
            val declaration = declarations(storage)._2
            val initial = DigitalStorageMlir.initializer(declaration)
            if storage == stack.pointerStorage then
              validateInitializerPolicy(storage)
              requireValid(
                if domain.get.resetPolicy.contains("none") then initial.isEmpty
                else
                  initial.flatMap(expressions.get).exists(value =>
                    value.owner == machine.owner && value.region.isEmpty && value.dataType == expected &&
                      value.operation == "integer" && value.operands.isEmpty && value.literal.contains(
                        "0"
                      )
                  )
                ,
                "FSM stack pointer requires exact zero or deliberate resetless initialization",
                path
              )
            else
              requireValid(
                initial.isEmpty && !declaration.attributes.exists(_._1 == "initializer_policy"),
                "FSM stack frames must stay deliberately uninitialized even in contextual domains",
                path
              )
      machineProjections(machine).foreach: (result, operation, dataType) =>
        requireValid(
          expressions.get(result).exists(value =>
            value.owner == machine.owner &&
              value.region.isEmpty && value.operation == operation && value.dataType == dataType &&
              value.operands == Vector(
                machine.storage
              ) && value.literal.isEmpty && value.enumIdentity.isEmpty &&
              value.caseIds.isEmpty && value.structure.isEmpty && value.materializationIntent.isEmpty
          ),
          "flat FSM projection requires its actual storage, type and canonical operation",
          result
        )
      requireValid(
        machine.localCodes.map(_.caseId) == cases && caseSet(machine.initialCase) &&
          machine.states.map(_.caseId).distinct.size == cases.size && machine.states.map(
            _.caseId
          ).toSet == cases.toSet,
        "flat FSM must retain complete canonical enum case and state inventories",
        path
      )
      val codes = machine.localCodes.map: entry =>
        requireValid(
          entry.code.length <= 1234 && entry.code.matches("0|[1-9][0-9]*"),
          "flat FSM local code must be bounded canonical unsigned decimal",
          path
        )
        val code = BigInt(entry.code)
        requireValid(
          code.bitLength <= machine.localWidth,
          "flat FSM local code exceeds local width",
          path
        )
        code
      requireValid(
        codes.distinct.size == codes.size && codes.map(_.bitLength.toLong.max(1)).sum <= 16777216,
        "flat FSM local codes must be unique and within metadata budget",
        path
      )
      val codeByCase = cases.zip(codes).toMap
      val transitionOrdinals = machine.transitions.map(_.sourceOrdinal).toSet
      val initial = DigitalStorageMlir.initializer(declarations(machine.storage)._2)
      requireValid(
        if domain.get.resetPolicy.contains("none") then initial.isEmpty
        else
          initial.flatMap(expressions.get).exists(value =>
            value.owner == machine.owner &&
              value.region.isEmpty && value.dataType == s"Bits(${machine.localWidth})" && value.operation == "integer" &&
              value.operands.isEmpty && value.literal.contains(
                codeByCase(machine.initialCase).toString
              )
          )
        ,
        "flat FSM storage reset initializer must match its exact local initial code",
        path
      )
      val compact = math.max(1, BigInt(cases.size - 1).bitLength)
      val policy = machine.requestedEncoding match
        case "compact" | "auto" => machine.localWidth == compact &&
          codes == cases.indices.map(BigInt(_)).toVector && machine.resolvedEncoding ==
            (if machine.requestedEncoding == "auto" then "auto-compact-v1" else "compact")
        case "one_hot" => machine.localWidth == cases.size &&
          codes == cases.indices.map(index =>
            BigInt(1) << index
          ).toVector && machine.resolvedEncoding == "one_hot"
        case "gray" => machine.localWidth == compact &&
          codes == cases.indices.map(index =>
            BigInt(index ^ (index >> 1))
          ).toVector && machine.resolvedEncoding == "gray"
        case "custom" => machine.resolvedEncoding == "custom"
        case _ => false
      requireValid(policy, "flat FSM encoding policy disagrees with actual local width/codes", path)
      val ordinals = machine.states.map(_.sourceOrdinal) ++ machine.transitions.map(
        _.sourceOrdinal
      ) ++ machine.actions.map(_.sourceOrdinal)
      requireValid(
        ordinals.sorted == ordinals.indices.toVector &&
          machine.states.map(_.sourceOrdinal) == machine.states.map(_.sourceOrdinal).sorted &&
          machine.transitions.map(_.sourceOrdinal) == machine.transitions.map(
            _.sourceOrdinal
          ).sorted &&
          machine.actions.map(_.sourceOrdinal) == machine.actions.map(_.sourceOrdinal).sorted,
        "flat FSM state, transition and action source order must be exact and unique",
        path
      )
      val outgoing = machine.transitions.groupBy(_.sourceCase)
      machine.states.foreach: state =>
        val edges = outgoing.getOrElse(state.caseId, Vector.empty)
        requireValid(
          state.terminal == edges.isEmpty && edges.map(_.mode).distinct.size <= 1,
          "flat FSM terminal/dead-end or transition-mode inventory is inconsistent",
          path
        )
      machine.transitions.foreach: edge =>
        requireVisible(edge.guard, machine.owner, None, path)
        requireValid(
          caseSet(edge.sourceCase) && edge.destinationCase.forall(caseSet) &&
            Set("exclusive", "priority")(edge.mode) && expressionType(edge.guard) == "Bool",
          "flat FSM transition requires exact states and an actual Bool guard",
          path
        )
        if machine.requestedEncoding == "gray" && edge.sourceCase != edge.destinationCase.get then
          requireValid(
            (codeByCase(edge.sourceCase) ^ codeByCase(edge.destinationCase.get)).bitCount == 1,
            "flat FSM Gray transition must change exactly one local bit",
            path
          )
      if machine.stack.isEmpty then
        val _ = FsmStackMlir.structure(machine, fail)
      machine.actions.foreach: action =>
        val statePhase = Set("active", "entry", "exit")(action.phase)
        requireValid(
          action.path == s"$path.action${action.sourceOrdinal}" &&
            (statePhase && action.ownerCase.exists(
              cases.contains
            ) && action.transitionOrdinal.isEmpty ||
              action.phase == "transition" && action.ownerCase.isEmpty && action.transitionOrdinal.exists(
                ordinal =>
                  transitionOrdinals(ordinal)
              )),
          "flat FSM action phase and owner must match exact source inventory",
          action.path
        )
        validateSequentialArm(
          machine.owner,
          None,
          action.path,
          action.captures,
          action.arguments,
          Vector(
            s"Bits(${machine.localWidth})" -> "current_local",
            s"Bits(${machine.localWidth})" -> "next_local",
            "Bool" -> "transition_taken"
          )
        )
      // A result depends on storage/guards, never action captures. Detect actual graph feedback
      // before rendering and do not specialize static predicates to their declared defaults.
      val seen = mutable.HashSet.empty[String]
      val frontier = mutable.ArrayDeque.from(
        machineInputs(machine).drop(1) ++ machine.actions.flatMap(_.captures)
      )
      while frontier.nonEmpty do
        val input = frontier.removeHead()
        if seen.add(input) then
          sequentialDependencyWork += 1
          requireValid(
            sequentialDependencyWork <= 65536 && !machineProjections(machine).exists(_._1 == input),
            "flat FSM guard/action capture has a forward dependency on its own next/taken result",
            path
          )
          expressions.get(input).foreach(value => frontier ++= value.operands)
          predicates.get(input).foreach(value => frontier ++= value.operands)
          staticExpressions.get(input).foreach(value => frontier ++= value.operands)
    expressions.valuesIterator.filter(value =>
      Set(
        "flat_fsm_next",
        "flat_fsm_taken",
        "flat_fsm_entered",
        "flat_fsm_exited",
        FlatFsmRuntime.EstablishmentOperation
      )(value.operation)
    ).foreach: value =>
      requireValid(
        machineResults.contains(value.path),
        "flat FSM projection has no actual machine owner",
        value.path
      )
    composition.validate()
    timing.validate()
    snapshot.enumSwitches.foreach: value =>
      val canonical = enumDefinitions.get(value.enumIdentity).getOrElse(
        fail("enum switch canonical enum is absent", value.path)
      )
      requireValid(
        modules.contains(value.owner) && value.path.startsWith(value.owner + ".") &&
          value.sourceOrdinal >= 0 && value.caseIds.distinct.size == canonical.cases.size &&
          value.caseIds.toSet == canonical.cases.map(
            _.id
          ).toSet && value.arms.size == value.caseIds.size &&
          value.parentRegion.forall(path => regions.get(path).exists(_.owner == value.owner)) &&
          (if value.parentRegion.isEmpty then value.root == value.path
           else value.parentRegion.flatMap(sequentialRoot).contains(value.root)) &&
          !expressions.contains(value.path) && !declarations.contains(
            value.path
          ) && !regions.contains(value.path) &&
          !conditionals.contains(value.path),
        "enum switch requires exhaustive unique cases, exact lexical ownership and one sequential root",
        value.path
      )
      requireVisible(value.selector, value.owner, value.parentRegion, value.path)
      requireValid(
        expressionType(
          value.selector
        ) == s"Enum(${canonical.identity},${canonical.width},${canonical.hash})",
        "enum switch selector must retain exact canonical enum ABI",
        value.path
      )
      (value.arms :+ value.invalidArm).zipWithIndex.foreach: (arm, index) =>
        requireValid(
          arm.path == s"${value.path}.arm$index" && arm.yieldValue.isEmpty,
          "enum switch arm requires source-correlated identity without yield",
          arm.path
        )
        validateSequentialArm(
          value.owner,
          value.parentRegion,
          arm.path,
          arm.captures,
          arm.arguments,
          Vector.empty
        )
      requireValid(
        (value.arms :+ value.invalidArm).map(_.captures.size.toLong).sum <= 8192,
        "enum switch captures exceed aggregate budget",
        value.path
      )

    requireValid(
      snapshot.digitalAssignments.map(_.path).distinct.size == snapshot.digitalAssignments.size,
      "duplicate digital assignment identities",
      snapshot.root
    )
    snapshot.digitalAssignments.foreach: assignment =>
      requireValid(
        modules.contains(assignment.owner) && assignment.path.startsWith(assignment.owner + ".") &&
          assignment.sourceOrdinal >= 0 && assignment.region.forall(region =>
            regions.get(region).exists(value =>
              value.owner == assignment.owner &&
                (loops.contains(region) || arms.contains(region) || generated.contains(
                  region
                ) || actions.contains(region) || switchArms.contains(region))
            )
          ),
        "digital assignment has no exact digital effect scope or source order",
        assignment.path
      )
      Vector(assignment.target, assignment.value).foreach(input =>
        requireVisible(input, assignment.owner, assignment.region, assignment.path)
      )
      val target = declarations.get(
        assignmentRoot(assignment.target, assignment.owner, assignment.region)
      ).map(_._2).getOrElse(
        fail("digital assignment target requires an owned declaration", assignment.path)
      )
      requireValid(
        (assignment.kind == "event_update") == assignment.eventProcess.nonEmpty &&
          assignment.eventProcess.forall(eventProcesses.contains),
        "event update requires its exact process reference and ordinary assignments cannot carry one",
        assignment.path
      )
      val valid = assignment.kind match
        case "event_update" => target.kind == "output" && assignment.region.isEmpty &&
          assignment.target == target.path && assignment.eventProcess.exists(path =>
            eventTargetSets(path)(target.path)
          )
        case "continuous" => Set("wire", "output", "instance-input").contains(target.kind) &&
          !assignment.region.exists(hasBehavioralAncestor)
        case "register_next" => target.kind == "register" &&
          (assignment.region.isEmpty && assignment.target == target.path ||
            assignment.region.flatMap(sequentialRoot).nonEmpty &&
            wholeRegisterTarget(assignment.target))
        case _ => false
      requireValid(
        valid && (target.generatedScope.isEmpty || generatedDeclaration(target.path)) &&
          !privateMachineStorage(target.path),
        "digital assignment kind differs from its exact sink kind or uses an unsupported projected register update",
        assignment.path
      )
    requireValid(
      snapshot.memoryWrites.size <= 65536 &&
        snapshot.memoryWrites.map(_.path).distinct.size == snapshot.memoryWrites.size,
      "duplicate or excessive memory write identities",
      snapshot.root
    )
    snapshot.memoryWrites.foreach: write =>
      requireValid(
        modules.contains(write.owner) && write.path.startsWith(write.owner + ".") &&
          !expressions.contains(write.path) && !declarations.contains(write.path) &&
          !staticExpressions.contains(write.path) && !arguments.contains(write.path) &&
          !assignmentPaths.contains(write.path) &&
          !conditionals.contains(write.path) && !machines.contains(write.path) &&
          !switches.contains(write.path) && !regions.contains(write.path) &&
          !predicates.contains(write.path) && !iterationIndices.contains(write.path) &&
          write.path == write.path.trim && write.sourceOrdinal >= 0 &&
          write.region.forall(region =>
            regions.get(region).exists(_.owner == write.owner) && sequentialRoot(region).nonEmpty
          ),
        "memory write requires an exact module or sequential effect scope and source order",
        write.path
      )
      val _ = memoryPort(write.owner, write.memory, write.domain, write.path)
      Vector(write.address, write.value, write.mask).foreach(input =>
        requireVisible(input, write.owner, write.region, write.path)
      )

    assignmentsByRegion.foreach:
      case ((owner, region), assignments) =>
        if region.isEmpty && (loopsByOwner.contains(owner) || assignments.exists(
            _.sourceOrdinal != 0
          ))
        then
          val ordinals = assignments.map(_.sourceOrdinal) ++
            loopsByOwner.getOrElse(owner, Vector.empty).map(_.sourceOrdinal)
          requireValid(
            ordinals.distinct.size == ordinals.size,
            "module digital effects require distinct source ordinals",
            owner
          )

    snapshot.digitalLoops.foreach: loop =>
      val scope = loop.owner -> Some(loop.path)
      val ordinals = assignmentsByRegion.getOrElse(scope, Vector.empty).map(_.sourceOrdinal) ++
        conditionalsByRegion.getOrElse(scope, Vector.empty).map(_.sourceOrdinal)
      requireValid(
        ordinals.sorted == ordinals.indices.toVector,
        "loop assignment source ordinals must be unique and contiguous from zero",
        loop.path
      )

    requireValid(
      snapshot.parameterExpressions.size <= 65536 &&
        staticExpressions.size == snapshot.parameterExpressions.size,
      "duplicate or excessive static expression identities",
      snapshot.root
    )
    snapshot.parameterExpressions.foreach: expression =>
      requireValid(
        !expressions.contains(expression.path) && !arguments.contains(expression.path) &&
          !declarations.contains(expression.path),
        "static and digital identities must be distinct",
        expression.path
      )
      expression.region.foreach: region =>
        val pure = expression.literal match
          case Some(_) => expression.operation == "integer" && expression.operands.isEmpty
          case None => IterationDomain.staticArithmetic(expression.operation).exists(_.arity ==
              expression.operands.size)
        requireValid(
          regions.get(region).exists(_.owner == expression.owner) &&
            expression.dataType == "Integer" && expression.unit.forall(_.isEmpty) && pure,
          "regional static expressions require an exact owner and pure Integer operation",
          expression.path
        )
        if loops.contains(region) then
          requireValid(
            expression.operands.forall(input =>
              staticExpressions.contains(input) ||
                arguments.get(input).exists(_._2.role == "static_capture")
            ),
            "loop-local statics require only local statics and exact static captures, never induction",
            expression.path
          )
      expression.operands.foreach: input =>
        if expression.region.nonEmpty || arguments.contains(input) ||
          staticExpressions.get(input).exists(_.region.nonEmpty)
        then
          requireVisible(input, expression.owner, expression.region, expression.path)

    private val resetFacts = ResetMlir.validate(snapshot, clocks)
    clocks.validateResets(resetFacts)
    private val resetRoots = resetFacts.domains

    requireValid(
      snapshot.digitalExpressions.size <= 65536 &&
        expressions.size == snapshot.digitalExpressions.size,
      "duplicate or excessive digital expression identities",
      snapshot.root
    )
    val waiverIds = mutable.HashSet.empty[(String, String)]
    snapshot.digitalExpressions.foreach: expression =>
      DigitalMaterializationMlir.validate(expression)
      requireValid(
        (expression.operation == GeneratedAreaRuntime.MemberOperation) == expression.generatedMember.nonEmpty,
        "only generated member operations carry family projection metadata",
        expression.path
      )
      expression.generatedMember.foreach: _ =>
        val _ = generatedMemberExport(expression)
      requireValid(
        DigitalAggregateContract.Operations.contains(expression.operation) ==
          expression.structure.nonEmpty,
        "only Struct operations carry schema evidence",
        expression.path
      )
      requireValid(
        modules.contains(expression.owner) && expression.path.startsWith(expression.owner + ".") &&
          !declarations.contains(expression.path) && expression.operands.size <= 4098,
        "digital expression has invalid module ownership, identity or operand count",
        expression.path
      )
      requireValid(
        (expression.operation == "memory_read") == expression.memory.nonEmpty,
        "only memory reads carry one exact memory port identity",
        expression.path
      )
      requireValid(
        ExecutableCrossingContract.Operations(expression.operation) == expression.crossing.nonEmpty,
        "only owned crossings carry an executable crossing contract",
        expression.path
      )
      requireValid(
        (expression.operation == ExecutableWaiverContract.Operation) == expression.waiver.nonEmpty,
        "only owned waivers carry an executable waiver contract",
        expression.path
      )
      expression.waiver.foreach: waiver =>
        requireValid(
          expression.region.isEmpty && expression.operands.size == 1 &&
            expression.dataType == expressionType(expression.operands.head) &&
            waiver.contract == ExecutableWaiverContract.Contract &&
            waiver.obligations.size == ExecutableWaiverContract.Obligations.size &&
            waiver.obligations.toMap == ExecutableWaiverContract.Obligations.toMap &&
            expression.literal.isEmpty && expression.enumIdentity.isEmpty &&
            expression.caseIds.isEmpty && expression.structure.isEmpty &&
            expression.materializationIntent.isEmpty && expression.memory.isEmpty &&
            expression.crossing.isEmpty && expression.reset.isEmpty && expression.clock.isEmpty &&
            Vector(waiver.sourceDomain, waiver.destinationDomain).forall(domain =>
              modules(expression.owner).domains.exists(_.path == domain)
            ),
          "waiver requires exact owned payload, closed obligations and local actual endpoint references",
          expression.path
        )
        ExecutableWaiverContract.validateText(
          waiver.id,
          waiver.reason,
          message => fail(message, expression.path)
        )
        requireValid(
          waiverIds.add(expression.owner -> waiver.id),
          "waiver id must be unique in its authored module",
          expression.path
        )
        ClockRelationshipModel.verifyBoundaryIntentFields(
          waiver.relation,
          message => fail(message, expression.path)
        )
        if !Vector(waiver.sourceDomain, waiver.destinationDomain).exists(hierarchy.formal) then
          relationships.verifyBoundaryIntent(
            waiver.sourceDomain,
            waiver.destinationDomain,
            waiver.relation,
            expression.path
          )
      expression.crossing.foreach: crossing =>
        if crossing.kind == "fifo" then
          requireValid(
            expression.operation == "cdc_fifo",
            "FIFO results require the owned FIFO operation",
            expression.path
          )
        else
          requireValid(
            expression.region.isEmpty && expression.operands.size == 1 &&
              expression.dataType == expressionType(expression.operands.head) &&
              (ExecutableCrossingContract.bitPatternPayload(
                crossing.kind
              ) || expression.dataType == "Bool") &&
              ExecutableCrossingContract.Kinds(crossing.kind) &&
              expression.operation == "cdc_" + crossing.kind &&
              crossing.contract == ExecutableCrossingContract.contract(crossing.kind) &&
              crossing.obligations.size == ExecutableCrossingContract.obligations(
                crossing.kind
              ).size &&
              crossing.obligations.toMap == ExecutableCrossingContract.obligations(
                crossing.kind
              ).toMap &&
              ExecutableCrossingContract.validStages(crossing.kind, crossing.stages) &&
              crossing.depth.isEmpty && crossing.identity.isEmpty && crossing.resultRole == "payload" &&
              expression.literal.isEmpty && expression.enumIdentity.isEmpty &&
              expression.caseIds.isEmpty && expression.structure.isEmpty &&
              expression.materializationIntent.isEmpty && expression.memory.isEmpty &&
              crossing.sourceDomain != crossing.destinationDomain &&
              Vector(crossing.sourceDomain, crossing.destinationDomain).forall(domain =>
                modules(expression.owner).domains.exists(value =>
                  value.path == domain
                )
              ),
            "crossing requires exact payload, closed obligations, distinct actual endpoints and bounded stages",
            expression.path
          )
        if crossing.kind != "fifo" && ExecutableCrossingContract.commonResetEpoch(crossing.kind) &&
          !Vector(crossing.sourceDomain, crossing.destinationDomain).exists(hierarchy.formal)
        then
          val source = resetRoots.getOrElse(crossing.sourceDomain, Map.empty)
          requireValid(
            source.nonEmpty && resetRoots.get(crossing.destinationDomain).contains(source),
            s"${crossing.kind} requires common nonempty physical reset roots and polarities with safe release",
            expression.path
          )
      requireValid(
        Set(
          "reset_boundary",
          "rdc_sync",
          "reset_combine"
        ).contains(expression.operation) == expression.reset.nonEmpty,
        "reset producers require one exact typed reset contract",
        expression.path
      )
      requireValid(
        OwnedClockContract.Operations(expression.operation) == expression.clock.nonEmpty,
        "owned clock producers require one exact typed clock contract",
        expression.path
      )
      expression.reset.foreach: reset =>
        requireValid(
          expression.region.isEmpty && expression.dataType == "Reset" &&
            expression.literal.isEmpty && expression.crossing.isEmpty && expression.memory.isEmpty &&
            expression.enumIdentity.isEmpty && expression.structure.isEmpty && expression.materializationIntent.isEmpty &&
            reset.inputs == expression.operands && Set(
              "active_high",
              "active_low"
            ).contains(reset.polarity),
          "invalid owned reset producer facts",
          expression.path
        )
        val expected = reset.kind match
          case "boundary" => Vector("Reset")
          case "sync" => Vector("Reset", "Clock")
          case "combine" => Vector.fill(expression.operands.size)("Reset")
          case _ => fail("unknown reset producer", expression.path)
        requireValid(
          expression.operands.map(expressionType) == expected && expected.nonEmpty &&
            (reset.kind != "combine" || expected.size <= 32) &&
            (reset.kind != "sync" || reset.stages >= 2 && reset.stages <= 32 && reset.destination.nonEmpty),
          "reset operands, stages or destination are invalid",
          expression.path
        )
        reset.destination.foreach(domain =>
          requireValid(
            modules(expression.owner).domains.exists(value =>
              value.path == domain
            ),
            "reset destination is not an exact owned domain",
            expression.path
          )
        )
      expression.memory.foreach: port =>
        requireValid(
          expression.region.isEmpty && expression.operands.size == 1 &&
            expression.literal.isEmpty && expression.enumIdentity.isEmpty &&
            expression.caseIds.isEmpty && expression.structure.isEmpty &&
            expression.materializationIntent.isEmpty,
          "memory read requires one module-level address without unrelated expression facts",
          expression.path
        )
        val _ = memoryPort(expression.owner, port.memory, port.domain, expression.path)
      expression.region.foreach: region =>
        requireValid(
          regions.get(region).exists(_.owner == expression.owner),
          "digital expression has no exact lexical region owner",
          expression.path
        )
      expression.operands.foreach: operand =>
        requireVisible(operand, expression.owner, expression.region, expression.path)

    // The source exposes three ordinary expression identities, but the native boundary is
    // one operation. Validate the complete group once rather than treating its control
    // results as unrelated payload crossings.
    private val fifoResults = snapshot.digitalExpressions.filter(_.operation == "cdc_fifo")
      .groupBy(expression =>
        expression.crossing.flatMap(_.identity).getOrElse(
          fail("FIFO results require one stable boundary identity", expression.path)
        )
      ).map: (identity, results) =>
        requireValid(
          results.size == 3 && results.flatMap(_.crossing.map(_.resultRole)).sorted ==
            Vector("payload", "ready", "valid"),
          "FIFO requires exactly one payload, valid and ready result",
          identity
        )
        val ordered = Vector("payload", "valid", "ready").map(role =>
          results.find(_.crossing.exists(_.resultRole == role)).get
        )
        val payload = ordered.head
        val crossing = payload.crossing.get
        requireValid(
          payload.path == identity && payload.operands.size == 3 &&
            payload.dataType == expressionType(payload.operands.head) &&
            payload.operands.drop(1).forall(expressionType(_) == "Bool") &&
            ordered.drop(1).forall(_.dataType == "Bool") &&
            results.forall(result =>
              result.owner == payload.owner && result.region.isEmpty &&
                result.operands == payload.operands && result.literal.isEmpty &&
                result.enumIdentity.isEmpty && result.caseIds.isEmpty &&
                result.structure.isEmpty && result.materializationIntent.isEmpty &&
                result.memory.isEmpty && result.reset.isEmpty &&
                result.crossing.exists(_.copy(resultRole = "payload") == crossing)
            ) &&
            crossing.kind == "fifo" &&
            crossing.contract == ExecutableCrossingContract.contract("fifo") &&
            crossing.obligations.size == ExecutableCrossingContract.obligations("fifo").size &&
            crossing.obligations.toMap == ExecutableCrossingContract.obligations("fifo").toMap &&
            crossing.depth.exists(ExecutableCrossingContract.validDepth) &&
            ExecutableCrossingContract.validStages("fifo", crossing.stages) &&
            crossing.sourceDomain != crossing.destinationDomain &&
            Vector(crossing.sourceDomain, crossing.destinationDomain).forall(domain =>
              modules(payload.owner).domains.exists(value =>
                value.path == domain
              )
            ),
          "FIFO requires coherent typed results, exact operands, closed obligations, bounded depth and actual endpoints",
          identity
        )
        val source = resetRoots.getOrElse(crossing.sourceDomain, Map.empty)
        requireValid(
          (Vector(crossing.sourceDomain, crossing.destinationDomain).exists(hierarchy.formal) ||
            source.nonEmpty && resetRoots.get(crossing.destinationDomain).contains(source)),
          "fifo requires common nonempty physical reset roots and polarities with safe release",
          identity
        )
        identity -> ordered

    requireValid(
      snapshot.digitalCollections.size <= 65536 &&
        collections.size == snapshot.digitalCollections.size &&
        arguments.size == regions.valuesIterator.map(_.arguments.size).sum,
      "duplicate or excessive collection or argument identities",
      snapshot.root
    )
    snapshot.digitalCollections.foreach: collection =>
      val path = collection.path
      requireValid(
        modules.contains(collection.owner) && path.startsWith(collection.owner + ".") &&
          DigitalCollectionContract.Kinds.contains(collection.kind) &&
          collection.inputs.size == (if collection.kind == "zip_with" then 2 else 1) &&
          collection.seed.nonEmpty == Set("fold_left", "scan_left").contains(collection.kind) &&
          collection.captures.distinct.size == collection.captures.size &&
          collection.arguments.size <= 4098,
        "collection kind, identity, operand segments or argument count is invalid",
        path
      )
      requireValid(
        byRegion.getOrElse(Some(path), Vector.empty).size.toLong +
          staticByRegion.getOrElse(Some(path), Vector.empty).size.toLong +
          collection.arguments.size <= 4098L,
        "collection values, static expressions and arguments exceed the bounded region budget",
        path
      )
      val operands = collection.inputs ++ collection.seed ++ collection.captures
      requireValid(
        expressions.get(path).exists(value =>
          value.operation == DigitalCollectionContract.Operation &&
            value.owner == collection.owner && value.region == collection.parentRegion &&
            value.operands == operands && value.dataType == collection.dataType &&
            value.literal.isEmpty && value.enumIdentity.isEmpty && value.caseIds.isEmpty
        ),
        "collection must have one matching ordinary digital result record",
        path
      )
      collection.arguments.zipWithIndex.foreach: (argument, index) =>
        requireValid(
          argument.path == s"$path.arg$index" && !expressions.contains(argument.path) &&
            !declarations.contains(argument.path) && !staticExpressions.contains(argument.path),
          "collection argument identity must be canonical and distinct from all values",
          path
        )
      var parent = collection.parentRegion
      val ancestors = mutable.HashSet(path)
      while parent.nonEmpty do
        val identity = parent.get
        requireValid(
          ancestors.add(identity) && ancestors.size <= 128,
          "collection lexical owners contain a cycle or exceed depth limit",
          path
        )
        val outer = regions.getOrElse(
          identity,
          fail("collection parent region is absent", path)
        )
        requireValid(
          outer.owner == collection.owner,
          "collection parent has another module owner",
          path
        )
        parent = outer.parentRegion
      operands.foreach(requireVisible(_, collection.owner, collection.parentRegion, path))
      requireVisible(collection.yieldValue, collection.owner, Some(path), path)
    snapshot.digitalExpressions.filter(_.operation == DigitalCollectionContract.Operation).foreach:
      expression =>
        requireValid(
          collections.contains(expression.path),
          "collection result has no region record",
          expression.path
        )
    (snapshot.shapeIndices.map(value => (value.path, value.owner, value.input, value.region)) ++
      snapshot.shapeViews.map(value =>
        (value.path, value.owner, value.input, value.region)
      )).foreach: (path, owner, input, region) =>
      if expressions.contains(path) || region.nonEmpty then
        requireValid(
          expressions.get(path).exists(value =>
            value.operation == "shape_reference" && value.region == region
          ),
          "digital shape projection and expression must share one exact region",
          path
        )
        requireVisible(input, owner, region, path)

    private val indexDependencies = mutable.HashMap.empty[String, Boolean]
    private val activeIndices = mutable.HashSet.empty[String]
    def iterationLoop(path: String): Option[String] =
      val seen = mutable.HashSet.empty[String]
      def visit(input: String): Option[String] =
        if !seen.add(input) || seen.size > 128 then None
        else
          iterationIndices.get(input).flatMap(value =>
            val owners = value.operands.flatMap(visit).distinct
            Option.when(owners.size == 1)(owners.head)
          ).orElse {
            arguments.get(input).flatMap: (scope, argument) =>
              if loops.contains(scope.path) && argument.role == "induction" then Some(scope.path)
              else if argument.role == "index_capture" then
                val index = scope.arguments.indexWhere(_.path == input) -
                  (scope.arguments.size - scope.captures.size)
                scope.captures.lift(index).flatMap(visit)
              else None
          }
      visit(path)
    def isIterationIndex(path: String): Boolean = iterationLoop(path).nonEmpty
    private def enclosingIterationLoop(region: String): Option[String] =
      val seen = mutable.HashSet.empty[String]
      var current = Option(region)
      while current.nonEmpty && !loops.contains(current.get) && seen.add(
          current.get
        ) && seen.size <= 128
      do
        current = regions.get(current.get).flatMap(_.parentRegion)
      current.filter(loops.contains)
    private def validateIterationIndex(path: String, region: String, depth: Int = 0): Boolean =
      requireValid(depth <= 128, "iteration index graph exceeds depth limit", path)
      val owner =
        regions.getOrElse(region, fail("iteration index has no exact loop owner", path)).owner
      requireVisible(path, owner, Some(region), path)
      indexDependencies.getOrElseUpdate(
        path, {
          requireValid(activeIndices.add(path), "iteration index graph contains a cycle", path)
          try
            iterationIndices.get(path) match
              case Some(value) =>
                requireValid(
                  value.region == region && value.path.startsWith(value.owner + ".") &&
                    value.path.trim == value.path && Set("add", "sub").contains(value.operation) &&
                    value.operands.size == 2 && !expressions.contains(path) &&
                    !staticExpressions.contains(path) && !declarations.contains(path) &&
                    !arguments.contains(path),
                  "iteration index requires one actual affine add/sub DAG in its loop",
                  path
                )
                val dependencies = value.operands.map(validateIterationIndex(_, region, depth + 1))
                requireValid(
                  dependencies.count(identity) == 1 &&
                    (value.operation != "sub" || dependencies.head),
                  "iteration index must be induction plus/minus one static offset",
                  path
                )
                true
              case None if arguments.get(path).exists(_._2.role == "index_capture") =>
                val (scope, argument) = arguments(path)
                val index = scope.arguments.indexWhere(_.path == argument.path) -
                  (scope.arguments.size - scope.captures.size)
                val source = scope.captures.lift(index).getOrElse(
                  fail("iteration selector capture has no actual source", path)
                )
                val parent = scope.parentRegion.getOrElse(
                  fail("iteration selector capture has no actual parent", path)
                )
                requireValid(
                  iterationLoop(
                    source
                  ).nonEmpty && iterationLoop(source) == enclosingIterationLoop(region),
                  "iteration selector capture must retain its actual enclosing loop",
                  path
                )
                validateIterationIndex(source, parent, depth + 1)
              case None
                  if arguments.get(path).exists(value =>
                    loops.contains(value._1.path) && value._2.role == "induction"
                  ) => true
              case None =>
                requireValid(
                  expressionType(path) == "Integer" &&
                    (staticExpressions.contains(path) ||
                      arguments.get(path).exists(_._2.role == "static_capture")),
                  "iteration offset must be a local static DAG or actual static capture",
                  path
                )
                staticExpressions.get(path).foreach: expression =>
                  requireValid(
                    expression.operands.forall(input =>
                      !validateIterationIndex(input, region, depth + 1)
                    ),
                    "loop-local static offset must not depend on induction",
                    path
                  )
                false
          finally { val _ = activeIndices.remove(path) }
        }
      )
    snapshot.iterationIndices.foreach: value =>
      requireValid(
        regions.get(value.region).exists(_.owner == value.owner) &&
          enclosingIterationLoop(value.region).nonEmpty &&
          iterationLoop(value.path) == enclosingIterationLoop(value.region),
        "iteration index has no exact loop owner",
        value.path
      )
      val _ = validateIterationIndex(value.path, value.region)

    def parameterDefault(
        declaration: KernelDeclarationSnapshot,
        reference: String,
        scalarType: (String, String) => Option[ScalarType]
    ): String =
      literalAttribute(
        reference,
        declaration.dataType.getOrElse(
          fail("digital parameter has no type", declaration.path)
        ),
        declarations(declaration.path)._1,
        declaration.path,
        scalarType
      )

    def literalAttribute(
        reference: String,
        expectedType: String,
        owner: String,
        path: String,
        scalarType: (String, String) => Option[ScalarType]
    ): String =
      expressions.get(reference).foreach: value =>
        requireValid(
          value.owner == owner && value.region.isEmpty,
          "digital parameter literal cannot escape its module lexical scope",
          path
        )
      if expectedType.startsWith("Enum(") then
        enums.literalAttribute(
          expressions.getOrElse(
            reference,
            fail("enum parameter value has no captured literal", path)
          ),
          expectedType,
          owner,
          path
        )
      else
        val literal = expressions.get(reference) match
          case Some(value) =>
            requireValid(
              value.owner == owner && value.dataType == expectedType,
              "digital parameter literal requires exact ownership and type",
              path
            )
            value
          case None =>
            // Scalar defaults historically carry their canonical literal spelling. The exact
            // declaration type supplies the proof; a default is never a width assumption.
            KernelDigitalExpressionSnapshot(
              path,
              owner,
              if expectedType == "Bool" then "boolean"
              else if expectedType == "Real" then "real_literal"
              else "integer",
              Vector.empty,
              expectedType,
              Some(reference),
              None,
              Vector.empty
            )
        scalarLiteral(literal, scalarType)._1

    def expressionType(path: String): String =
      expressions.get(path).map(_.dataType)
        .orElse(predicates.get(path).map(_ => "StaticPredicate"))
        .orElse(staticExpressions.get(path).map(_.dataType))
        .orElse(declarations.get(path).flatMap(_._2.dataType))
        .orElse(arguments.get(path).map(_._2.dataType))
        .orElse(iterationIndices.get(path).map(_ => "Integer"))
        .orElse(hierarchy.projections.get(path).map(_.kind))
        .orElse(generated.get(path).filter(_.yieldedArea.nonEmpty).map(_ => "GeneratedFamily"))
        .getOrElse(fail("digital operand requires an exact type", path))

    private def scalarLiteral(
        value: KernelDigitalExpressionSnapshot,
        scalarType: (String, String) => Option[ScalarType],
        checkedFacts: Option[(String, String) => Option[ScalarFacts]] = None
    ): (String, Proof) =
      requireValid(
        value.enumIdentity.isEmpty && value.caseIds.isEmpty && value.operands.isEmpty,
        "scalar literal cannot carry operands or enum facts",
        value.path
      )
      val literal = value.literal.getOrElse(fail("scalar literal is absent", value.path))
      value.dataType match
        case "Bool" =>
          requireValid(
            value.operation == "boolean" && (literal == "true" || literal == "false"),
            "invalid Boolean literal in digital closure",
            value.path
          )
          literal -> Proof(Some(if literal == "true" then BigInt(1) else BigInt(0)), true)
        case "Real" =>
          requireValid(
            value.operation == "real_literal",
            "invalid real literal operation",
            value.path
          )
          val number = literal.toDoubleOption.filter(_.isFinite).getOrElse(
            fail("digital real literal must be finite", value.path)
          )
          s"${java.lang.Double.toString(number)} : f64" -> Unknown
        case dataType =>
          requireValid(
            value.operation == "integer" && literal.length <= 315654,
            "invalid or excessive integer literal",
            value.path
          )
          val number = scala.util.Try(BigInt(literal)).getOrElse(
            fail("digital integer literal is malformed", value.path)
          )
          requireValid(
            number.toString == literal,
            "integer literal must be canonical decimal",
            value.path
          )
          val facts = if dataType == "Integer" then
            ScalarFacts("SInt", Some(ScalarType("SInt", "64", 64, 64)))
          else
            checkedFacts.map(_(dataType, value.path)).getOrElse(
              scalarType(dataType, value.path).map(info => ScalarFacts(info.kind, Some(info)))
            ).getOrElse(fail("digital literal requires a scalar integer type", value.path))
          requireValid(
            Set("Bits", "UInt", "SInt")(facts.kind) && (facts.kind == "SInt" || number >= 0),
            "digital literal requires a valid scalar kind and signedness",
            value.path
          )
          facts.bounds.foreach: info =>
            requireValid(
              requiredWidth(info.kind, number) <= info.lower,
              "integer literal does not fit every legal declared width",
              value.path
            )
          val known = Option.when(facts.bounds.nonEmpty && number.bitLength <= 4096)(number)
          s"$number : i${math.max(64, number.bitLength + 1)}" -> Proof(known, number >= 0)

    private def scalarConversion(
        value: KernelDigitalExpressionSnapshot,
        sourceType: String,
        input: Proof,
        scalarType: (String, String) => Option[ScalarType],
        widthRelation: (ScalarType, ScalarType, Int, String) => Boolean
    ): Proof =
      val path = value.path
      requireValid(
        value.literal.isEmpty && value.enumIdentity.isEmpty && value.caseIds.isEmpty &&
          value.operands.size == 1 && DigitalScalarContract.Operations.contains(value.operation),
        "digital conversion requires one typed operand and a closed operation",
        path
      )
      val source = scalarType(sourceType, value.operands.head).getOrElse(
        fail("digital conversion requires an exact Bits, UInt or SInt operand", path)
      )
      if value.operation == "digital_to_real" then
        requireValid(
          source.kind == "UInt" && value.dataType == "Real" &&
            (source.upper <= 1023L || input.constant.exists(_.toDouble.isFinite)),
          "numeric Real conversion requires a finite unsigned numeric proof",
          path
        )
        Unknown
      else
        val result = scalarType(value.dataType, path).getOrElse(
          fail("digital conversion requires an exact digital scalar result", path)
        )
        val sameKind = source.kind == result.kind
        val sameWidth = widthRelation(source, result, 0, path)
        val valid = value.operation match
          case "digital_extend" => sameKind && result.lower >= source.upper
          case "digital_truncate" => sameKind && result.upper <= source.lower
          case "digital_wrap" | "digital_saturate" => sameKind
          case "digital_resize_checked" => sameKind &&
            (result.lower >= source.upper ||
              input.constant.exists(requiredWidth(result.kind, _) <= result.lower))
          case "digital_to_signed" => source.kind == "UInt" && result.kind == "SInt" &&
            widthRelation(source, result, 1, path)
          case "digital_to_unsigned" => source.kind == "SInt" && result.kind == "UInt" &&
            sameWidth && input.nonnegative
          case "digital_reinterpret_signed" => source.kind == "UInt" && result.kind == "SInt" &&
            sameWidth
          case "digital_reinterpret_unsigned" => source.kind == "SInt" && result.kind == "UInt" &&
            sameWidth
          case _ => false
        requireValid(
          valid,
          "digital conversion violates width, signedness or checked-value proof",
          path
        )
        val constant = input.constant.flatMap: number =>
          value.operation match
            case "digital_extend" | "digital_resize_checked" | "digital_to_signed" |
                "digital_to_unsigned" => Some(number)
            case _ if requiredWidth(result.kind, number) <= result.lower => Some(number)
            case _ if result.lower != result.upper || result.upper > 4096L => None
            case operation =>
              val width = result.upper.toInt
              val modulus = BigInt(1) << width
              if operation == "digital_saturate" then
                val lower = if result.kind == "SInt" then -(modulus >> 1) else BigInt(0)
                val upper = if result.kind == "SInt" then (modulus >> 1) - 1 else modulus - 1
                Some(number.max(lower).min(upper))
              else
                val bits = ((number % modulus) + modulus) % modulus
                Some(if result.kind == "SInt" && bits.testBit(width - 1) then bits - modulus
                else bits)
        val preservesNonnegative = Set(
          "digital_extend",
          "digital_resize_checked",
          "digital_saturate",
          "digital_to_signed",
          "digital_to_unsigned"
        ).contains(value.operation)
        Proof(
          constant,
          result.kind != "SInt" || constant.exists(_ >= 0) ||
            (preservesNonnegative && input.nonnegative)
        )

    /** Validate the untrusted snapshot independently of source construction. The existing value
      * schedule supplies proofs once per SSA node; width algebra stays with its canonical owner.
      */
    private def combinational(
        value: KernelDigitalExpressionSnapshot,
        inputs: Vector[Proof],
        scalarType: (String, String) => Option[ScalarType],
        widthRelation: (ScalarType, ScalarType, Int, String) => Boolean,
        widthFormula: (ScalarType, String, Vector[ScalarType], Long, String) => Boolean,
        exactValueType: (String, String, String) => Boolean
    ): Proof =
      val path = value.path
      val operation = value.operation
      val arity = operation match
        case "digital_neg" | "digital_not" | "bool_not" => 1
        case "digital_add" | "digital_sub" | "digital_mul" | "digital_eq" | "digital_ne" |
            "digital_lt" | "digital_le" | "digital_gt" | "digital_ge" | "digital_and" |
            "digital_or" | "digital_xor" | "digital_concat" | "digital_shl" | "digital_shr" |
            "digital_logical_shr" | "digital_div" | "digital_rem" | "digital_min" |
            "digital_max" | "bool_and" | "bool_or" => 2
        case "digital_extract" | "digital_select" => 3
        case _ => 0
      requireValid(
        arity != 0 && value.operands.size == arity && inputs.size == arity &&
          value.literal.isEmpty && value.enumIdentity.isEmpty && value.caseIds.isEmpty,
        "digital combinational expression requires an exact opcode, arity and ordinary value facts",
        path
      )
      val operandTypes = value.operands.map(expressionType)
      if operation == "digital_select" then
        val selected = value.dataType
        val finite = selected == "Bool" || scalarType(selected, path).nonEmpty ||
          selected.startsWith("Enum(") || selected.startsWith("Vec(") ||
          selected.startsWith("Struct(")
        requireValid(
          operandTypes.head == "Bool" && finite &&
            exactValueType(operandTypes(1), selected, path) &&
            exactValueType(operandTypes(2), selected, path),
          "digital selection requires Bool and identical complete finite branch types",
          path
        )
        val constant = inputs.head.constant.flatMap(number =>
          inputs(if number == 0 then 2 else 1).constant
        ).orElse(inputs(1).constant.filter(number => inputs(2).constant.contains(number)))
        val nonnegative = inputs.head.constant.map(number =>
          inputs(if number == 0 then 2 else 1).nonnegative
        ).getOrElse(inputs(1).nonnegative && inputs(2).nonnegative)
        Proof(constant, nonnegative || constant.exists(_ >= 0))
      else if DigitalCombinationalContract.ReachableBooleanOperations.contains(operation) then
        requireValid(
          value.dataType == "Bool" && operandTypes.forall(_ == "Bool"),
          "digital Boolean expression requires actual Bool operands and result",
          path
        )
        val constant = if operation == "bool_not" then
          inputs.head.constant.map(number => if number == 0 then BigInt(1) else BigInt(0))
        else
          for left <- inputs(0).constant; right <- inputs(1).constant yield
            val truth = if operation == "bool_and" then left != 0 && right != 0
            else left != 0 || right != 0
            if truth then BigInt(1) else BigInt(0)
        Proof(constant, true)
      else
        val sources = operandTypes.zip(value.operands).map: (dataType, operand) =>
          scalarType(dataType, operand).getOrElse(
            fail("digital scalar operation requires exact Bits, UInt or SInt operands", path)
          )
        val source = sources.head
        val comparison = Set(
          "digital_eq",
          "digital_ne",
          "digital_lt",
          "digital_le",
          "digital_gt",
          "digital_ge"
        ).contains(operation)
        val result = if comparison then
          requireValid(value.dataType == "Bool", "digital comparison requires a Bool result", path)
          ScalarType("Bool", "1", 1, 1)
        else
          scalarType(value.dataType, path).getOrElse(
            fail("digital scalar operation requires an exact bit-vector result", path)
          )
        def numeric(kind: String): Boolean = kind == "UInt" || kind == "SInt"
        def equalWidth(left: ScalarType, right: ScalarType): Boolean =
          widthRelation(left, right, 0, path)
        def literalWidth(number: Long): ScalarType =
          ScalarType("UInt", number.toString, number, number)
        def formula(operator: String, operands: Vector[ScalarType], increment: Long = 0L): Boolean =
          widthFormula(result, operator, operands, increment, path)
        def staticIndex(index: Int): Long =
          val operand = expressions.get(value.operands(index)).getOrElse(
            fail("digital shift and extraction indices require actual UInt literal operands", path)
          )
          requireValid(
            operand.operation == "integer" && sources(index).kind == "UInt" &&
              operand.operands.isEmpty && operand.literal.nonEmpty,
            "digital shift and extraction indices require actual UInt literal operands",
            path
          )
          inputs(index).constant.filter(number => number >= 0 && number <= Int.MaxValue)
            .map(_.toLong).getOrElse(
              fail("digital static index is outside the nonnegative Int range", path)
            )
        var amount = 0L
        var lower = 0L
        var upper = 0L
        val valid = operation match
          case "digital_add" | "digital_sub" =>
            numeric(source.kind) && sources(1).kind == source.kind &&
            result.kind == (if operation == "digital_sub" then "SInt" else source.kind) &&
            formula("static_integer_max", sources, 1L)
          case "digital_mul" =>
            numeric(source.kind) && sources(1).kind == source.kind && result.kind == source.kind &&
            formula("analog_add", sources)
          case "digital_div" | "digital_rem" =>
            requireValid(
              inputs(1).constant.exists(_ != 0),
              "division and remainder require an independently proven exact nonzero divisor",
              path
            )
            numeric(source.kind) && sources(1).kind == source.kind && result.kind == source.kind &&
            (if operation == "digital_div" && source.kind == "SInt" then
               widthRelation(source, result, 1, path)
             else equalWidth(source, result))
          case "digital_min" | "digital_max" =>
            numeric(source.kind) && sources(1).kind == source.kind && result.kind == source.kind &&
            formula("static_integer_max", sources)
          case "digital_neg" =>
            numeric(source.kind) && result.kind == "SInt" &&
            formula("analog_add", Vector(source, literalWidth(1)))
          case _ if comparison =>
            source.kind == sources(1).kind &&
            (numeric(source.kind) ||
              (Set("digital_eq", "digital_ne").contains(operation) &&
                source.kind == "Bits" && equalWidth(source, sources(1))))
          case "digital_and" | "digital_or" | "digital_xor" | "digital_not" =>
            sources.forall(other => other.kind == source.kind && equalWidth(source, other)) &&
            result.kind == source.kind && equalWidth(source, result)
          case "digital_concat" =>
            result.kind == "Bits" && formula("analog_add", sources)
          case "digital_shl" | "digital_shr" | "digital_logical_shr" =>
            amount = staticIndex(1)
            source.kind == result.kind &&
            (if operation == "digital_shl" && amount != 0 then
               formula("analog_add", Vector(source, literalWidth(amount)))
             else equalWidth(source, result))
          case "digital_extract" =>
            lower = staticIndex(1)
            upper = staticIndex(2)
            upper > lower && upper <= source.lower && result.kind == "Bits" &&
            equalWidth(result, literalWidth(upper - lower))
          case _ => false
        requireValid(
          valid,
          "digital combinational expression violates exact kind, width or static range proof",
          path
        )
        def at(index: Int): Option[BigInt] = inputs(index).constant
        def pair(function: (BigInt, BigInt) => BigInt): Option[BigInt] =
          for left <- at(0); right <- at(1) yield function(left, right)
        def test(function: (BigInt, BigInt) => Boolean): Option[BigInt] =
          pair((left, right) => if function(left, right) then BigInt(1) else BigInt(0))
        def fixedWidth(info: ScalarType): Option[Int] =
          Option.when(info.lower == info.upper && info.upper <= 4096)(info.upper.toInt)
        def bits(number: BigInt, width: Int): BigInt = number & ((BigInt(1) << width) - 1)
        def decoded(number: BigInt, width: Int): BigInt =
          val encoded = bits(number, width)
          if result.kind == "SInt" && encoded.testBit(width - 1) then
            encoded - (BigInt(1) << width)
          else encoded
        val constant = (operation match
          case "digital_add" => pair(_ + _)
          case "digital_sub" => pair(_ - _)
          case "digital_mul" => pair(_ * _)
          case "digital_div" => pair(_ / _)
          case "digital_rem" => pair(_ % _)
          case "digital_min" => pair(_.min(_))
          case "digital_max" => pair(_.max(_))
          case "digital_neg" => at(0).map(-_)
          case "digital_eq" => test(_ == _)
          case "digital_ne" => test(_ != _)
          case "digital_lt" => test(_ < _)
          case "digital_le" => test(_ <= _)
          case "digital_gt" => test(_ > _)
          case "digital_ge" => test(_ >= _)
          case "digital_and" => pair(_ & _)
          case "digital_or" => pair(_ | _)
          case "digital_xor" => pair(_ ^ _)
          case "digital_not" =>
            for number <- at(0); width <- fixedWidth(result) yield decoded(~number, width)
          case "digital_concat" =>
            for
              left <- at(0); right <- at(1)
              leftWidth <- fixedWidth(source); rightWidth <- fixedWidth(sources(1))
            yield (bits(left, leftWidth) << rightWidth) | bits(right, rightWidth)
          case "digital_extract" if upper - lower <= 4096 =>
            at(0).map(number => bits(number >> lower.toInt, (upper - lower).toInt))
          case "digital_shl" =>
            at(0).filter(number => amount <= 4096 || number == 0).map(_ << amount.toInt)
          case "digital_shr" => at(0).map(_ >> amount.toInt)
          case "digital_logical_shr" if amount == 0 => at(0)
          case "digital_logical_shr" if source.kind != "SInt" || inputs.head.nonnegative =>
            at(0).map(_ >> amount.toInt)
          case "digital_logical_shr" =>
            for number <- at(0); width <- fixedWidth(source)
            yield decoded(bits(number, width) >> amount.toInt, width)
          case _ => None
        ).filter(_.bitLength <= 4096)
        val nonnegative = result.kind != "SInt" || constant.exists(_ >= 0) ||
          (Set("digital_add", "digital_mul").contains(operation) && inputs.forall(_.nonnegative)) ||
          (Set("digital_shl", "digital_shr").contains(operation) && inputs.head.nonnegative) ||
          (operation == "digital_logical_shr" && (amount > 0 || inputs.head.nonnegative))
        Proof(constant, nonnegative)

    /** Topological emission scales with unique nodes and edges, never with use-tree expansion. */
    def renderModule(
        module: KernelModuleSnapshot,
        nativeType: (String, String) => String,
        computedType: (String, String) => String,
        scalarType: (String, String) => Option[ScalarType],
        scalarFacts: (String, String) => Option[ScalarFacts],
        widthRelation: (ScalarType, ScalarType, Int, String) => Boolean,
        widthFormula: (ScalarType, String, Vector[ScalarType], Long, String) => Boolean,
        exactValueType: (String, String, String) => Boolean,
        widthFamily: (String, String, String) => Boolean,
        structType: (String, String) => (String, Vector[(String, String)]),
        vectorType: (String, String) => (String, Vector[String]),
        declarationValue: (
            KernelDeclarationSnapshot,
            String,
            String,
            Option[(String, String)],
            Vector[(String, String)]
        ) => Vector[String],
        declarationInitializer: KernelDeclarationSnapshot => Option[String],
        metadata: (String, Boolean) => String,
        sourceLocation: String => String,
        projectionValue: (String, String => (String, String)) => Projection,
        declarationNativeType: (KernelDeclarationSnapshot, String) => String,
        specializedValue: (
            KernelDigitalExpressionSnapshot,
            String,
            String,
            Vector[(String, String)]
        ) => Projection,
        additionalRoots: Vector[String],
        initialValues: Map[String, (String, String)],
        domainReference: String => String,
        staticValue: (String, Option[String], mutable.ArrayBuffer[String], StaticPurpose) => (
            String,
            String
        ),
        prepareType: (String, String, Option[String], mutable.ArrayBuffer[String]) => Unit,
        bindStaticArgument: (String, (String, String)) => Unit,
        withCollectionContexts: (KernelDigitalCollectionSnapshot, () => Unit) => Unit,
        reductionArguments: KernelDigitalCollectionSnapshot => Option[Vector[(String, String)]],
        collectionArgumentMatches: (
            KernelDigitalCollectionSnapshot,
            KernelDigitalRegionArgument,
            String
        ) => Boolean,
        collectionResultMatches: (KernelDigitalCollectionSnapshot, String) => Boolean,
        withLoopContext: (KernelDigitalLoopSnapshot, () => Unit) => Unit,
        withArmContext: (
            Option[String],
            KernelStructuralArmSnapshot,
            () => Unit
        ) => Unit,
        withGeneratedContext: (KernelGeneratedRegionSnapshot, () => Unit) => Unit,
        regionalTypeMatches: (String, String, String) => Boolean,
        validatePredicateInput: String => Unit,
        renderAssignment: (
            KernelDigitalAssignmentSnapshot,
            (String, String),
            (String, String)
        ) => String,
        effectMetadata: (String, Int) => String,
        renderMemoryRead: (
            KernelDigitalExpressionSnapshot,
            String,
            String,
            (String, String)
        ) => String,
        renderMemoryWrite: (KernelMemoryWriteSnapshot, Vector[(String, String)]) => String,
        renderGeneratedInstances: (
            KernelGeneratedRegionSnapshot,
            Map[String, (String, String)]
        ) => Vector[String],
        symbol: (String, String) => String,
        interfaceEndpoint: (String, Vector[(String, String)]) => Vector[String],
        interfaceSymbol: String => String,
        interfaceEffects: (
            Vector[KernelDigitalConnectivitySnapshot],
            String => (String, String)
        ) => Vector[String],
        interfaceDefinitions: Vector[(KernelInterfaceDefinitionContextSnapshot, Vector[String])],
        renderInterfaceDefinition: KernelInterfaceDefinitionContextSnapshot => Vector[String]
    ): Rendered =
      def captureNativeType(argument: KernelDigitalRegionArgument): String =
        if Set("Clock", "Reset")(argument.dataType) then
          nativeType(argument.dataType, argument.path)
        else computedType(argument.dataType, argument.path)
      var crossingStateBits = BigInt(0)
      def bitPatternWidth(text: String, path: String, fixed: Boolean = false): BigInt =
        var remaining = 65536
        def visit(value: String, depth: Int): BigInt =
          remaining -= 1
          requireValid(
            depth <= 128 && remaining >= 0,
            "crossing type exceeds structural budget",
            path
          )
          val result = if value == "Bool" then BigInt(1)
          else if value.startsWith("Enum(") then BigInt(enums.bitWidth(value, path))
          else if value.startsWith("Struct(") then
            structType(value, path)._2.foldLeft(BigInt(0))((total, field) =>
              total + visit(field._2, depth + 1)
            )
          else if value.startsWith("Vec(") then
            val (element, dimensions) = vectorType(value, path)
            dimensions.foldLeft(visit(element, depth + 1))((total, dimension) =>
              val bounds = loopBoundQuery.bounds(loopBoundQuery.resolve(module.path, dimension))
              requireValid(
                !fixed || bounds.lower == bounds.upper,
                "waiver requires fixed whole payload extent",
                path
              )
              total * bounds.upper
            )
          else
            scalarType(value, path).map(info => {
              requireValid(
                !fixed || info.lower == info.upper,
                "waiver requires fixed whole payload width",
                path
              )
              BigInt(info.upper)
            }).getOrElse(
              fail(
                "crossing requires finite Bool, bit-vector, Enum or Vec/Struct bit patterns",
                path
              )
            )
          requireValid(
            result > 0 && result <= Long.MaxValue,
            "crossing type footprint is excessive",
            path
          )
          result
        visit(text, 0)
      val eventSamples = snapshot.eventProcesses.iterator.filter(_.owner == module.path)
        .flatMap(_.externalCaptures).toSet
      module.declarations.iterator.filter(value => eventPort(value.path)).foreach: declaration =>
        val fixed = declaration.kind == "output" || eventSamples(declaration.path)
        val bits = bitPatternWidth(declaration.dataType.get, declaration.path, fixed)
        requireValid(
          !fixed || bits <= DigitalEventContract.MaximumPayloadBits,
          "event sampled input/storage requires a fixed whole payload of 1 through 4096 bits",
          declaration.path
        )
      val lines = mutable.ArrayBuffer.empty[String]
      val values = mutable.HashMap.from(initialValues)
      val heights = mutable.HashMap.from(initialValues.keysIterator.map(_ -> 0))
      val proofs = mutable.HashMap.from(initialValues.keysIterator.map(_ -> Unknown))
      val active = mutable.HashSet.empty[String]
      var ordinal = 0
      def exactUnsignedWidth(text: String, path: String, expected: Int): Boolean =
        scalarType(text, path).exists(info =>
          Set("Bits", "UInt").contains(info.kind) &&
            info.lower == expected.toLong && info.upper == expected.toLong
        )
      val emittedEffects = mutable.HashSet.empty[String]
      val emittedInterfaces = mutable.HashSet.empty[String]
      val emittedInterfaceDefinitions = mutable.HashSet.empty[(String, String, Int)]
      val orderedInterfaceEffects = snapshot.digitalConnectivity.filter(value =>
        value.owner == module.path && value.sourceOrdinal.nonEmpty
      )
        .groupBy(value => value.region -> value.sourceOrdinal.get)
      val interfaceMembers = snapshot.interfaceContracts.flatMap(endpoint =>
        endpoint.members.map(_.path -> endpoint.path)
      ).toMap
      val localInterfaceEndpoints = module.declarations.filter(value =>
        Set("interface-port", "interface-array")(value.kind)
      )
      def definitionKey(context: KernelInterfaceDefinitionContextSnapshot) =
        (context.region, context.identity, context.sourceOrdinal)
      def emitInterfaceDefinition(
          context: KernelInterfaceDefinitionContextSnapshot,
          dependencies: Vector[String],
          depth: Int,
          region: Option[String],
          output: mutable.ArrayBuffer[String]
      ): Unit =
        requireValid(
          emittedInterfaceDefinitions.add(definitionKey(context)),
          "regional Interface definition was emitted twice",
          context.identity
        )
        dependencies.foreach(path =>
          emit(path, depth + 1, region, output, typeArithmetic = true)
        )
        output ++= renderInterfaceDefinition(context)
      def scheduledInterfaceDefinitions(
          region: Option[String],
          depth: Int,
          output: mutable.ArrayBuffer[String]
      ) =
        interfaceDefinitions.filter(value => region.contains(value._1.region)).map:
          (context, dependencies) =>
            (
              context.sourceOrdinal,
              context.region + ".interface." + context.identity,
              () => emitInterfaceDefinition(context, dependencies, depth, region, output)
            )
      def emitInterface(
          path: String,
          depth: Int,
          region: Option[String],
          output: mutable.ArrayBuffer[String]
      ): Unit =
        val declaration = declarations(path)._2
        val deferred = declaration.generatedScope.nonEmpty ||
          declaration.interfaceBoundary.exists(value =>
            value.selection.nonEmpty || value.region.nonEmpty
          )
        if deferred && emittedInterfaces.add(path) then
          requireValid(
            valueRegion(path) == region,
            "Interface endpoint must render in its exact actual use region",
            path
          )
          if declaration.interfaceBoundary.isEmpty then
            interfaceEndpoints(path).definitionContexts.foreach(context =>
              requireValid(
                emittedInterfaceDefinitions(definitionKey(context)),
                "Interface endpoint precedes its actual lexical definition",
                path
              )
            )
          val selection = declaration.interfaceBoundary.flatMap(_.selection).map(value =>
            val _ = interfaceSelection(path)
            emitGeneratedSelection(value, path, region, depth + 1, output)
          ).getOrElse(Vector.empty)
          output ++= interfaceEndpoint(path, selection)
      def emitInterfaceEffects(
          group: Vector[KernelDigitalConnectivitySnapshot],
          depth: Int,
          region: Option[String],
          output: mutable.ArrayBuffer[String]
      ): Unit =
        group.foreach: effect =>
          (Vector(effect.target) ++ effect.source).foreach(path =>
            interfaceMembers.get(path).foreach(emitInterface(_, depth + 1, region, output))
          )
        output ++= interfaceEffects(group, path => emit(path, depth + 1, region, output))
      def scheduledInterfaces(
          region: Option[String],
          depth: Int,
          output: mutable.ArrayBuffer[String]
      ) =
        orderedInterfaceEffects.iterator.collect:
          case ((scope, sourceOrdinal), group) if scope == region =>
            (
              sourceOrdinal,
              group.map(_.path).min,
              () => emitInterfaceEffects(group, depth + 1, region, output)
            )
        .toVector
      def clearRegion(path: String): Unit =
        bodyValuePaths(path).foreach: identity =>
          values.remove(identity)
          heights.remove(identity)
          proofs.remove(identity)
          emittedEffects.remove(identity)

      def emitMemoryWrite(
          write: KernelMemoryWriteSnapshot,
          depth: Int,
          output: mutable.ArrayBuffer[String]
      ): Unit =
        val paths = Vector(write.address, write.value, write.mask)
        val operands = paths.map(emit(_, depth + 1, write.region, output))
        val declaration = memoryPort(module.path, write.memory, write.domain, write.path)._1
        requireValid(
          scalarType(expressionType(write.address), write.path).exists(_.kind == "UInt") &&
            scalarType(expressionType(write.mask), write.path).exists(value =>
              Set("Bits", "UInt").contains(value.kind)
            ) && exactValueType(
              expressionType(write.value),
              declaration.dataType.get,
              write.path
            ) &&
            paths.zip(operands).forall((path, operand) =>
              operand._2 == nativeType(expressionType(path), path)
            ),
          "memory write requires UInt address, Bits mask and exact element value types",
          write.path
        )
        output += renderMemoryWrite(write, operands)

      def emitScope(region: String, depth: Int, output: mutable.ArrayBuffer[String]): Unit =
        val scoped = Some(region)
        val effects = scheduledInterfaceDefinitions(scoped, depth, output) ++ scheduledInterfaces(
          scoped,
          depth,
          output
        ) ++
          assignmentsByRegion.getOrElse(module.path -> scoped, Vector.empty)
            .map(value =>
              (
                value.sourceOrdinal,
                value.path,
                () => {
                  val target = emit(value.target, depth + 1, scoped, output)
                  val source = emit(value.value, depth + 1, scoped, output)
                  output += renderAssignment(value, target, source)
                }
              )
            ) ++ memoryWritesByRegion.getOrElse(module.path -> scoped, Vector.empty).map(value =>
            (value.sourceOrdinal, value.path, () => emitMemoryWrite(value, depth + 1, output))
          ) ++ conditionalsByRegion.getOrElse(module.path -> scoped, Vector.empty)
            .map(value =>
              (
                value.sourceOrdinal,
                value.path,
                () => { emitConditional(value, depth + 1, output); () }
              )
            ) ++ switchesByRegion.getOrElse(module.path -> scoped, Vector.empty).map(value =>
            (value.sourceOrdinal, value.path, () => emitSwitch(value, depth + 1, output))
          ) ++
          generatedByRegion.getOrElse(module.path -> scoped, Vector.empty).map(value =>
            (
              value.digitalBody.get.sourceOrdinal,
              value.path,
              () => emitGenerated(value, depth + 1, output)
            )
          )
        requireValid(
          effects.map(_._1).distinct.size == effects.size,
          "digital regional effects require distinct source ordinals",
          region
        )
        effects.sortBy(value => (value._1, value._2)).foreach(value => value._3())
        localInterfaceEndpoints.filter(value => valueRegion(value.path) == scoped).foreach(value =>
          emitInterface(value.path, depth + 1, scoped, output)
        )
        staticByRegion.getOrElse(scoped, Vector.empty).sortBy(_.path).foreach(value =>
          if !values.contains(value.path) then
            emit(
              value.path,
              depth + 1,
              scoped,
              output,
              topology = sequentialRoot(region).isEmpty,
              typeArithmetic = true
            )
        )
        predicatesByRegion.getOrElse(scoped, Vector.empty).sortBy(_.path).foreach(value =>
          emit(value.path, depth + 1, scoped, output)
        )
        indicesByRegion.getOrElse(region, Vector.empty).sortBy(_.path).foreach(value =>
          emit(value.path, depth + 1, scoped, output)
        )
        byRegion.getOrElse(scoped, Vector.empty).sortBy(_.path).foreach(value =>
          emit(value.path, depth + 1, scoped, output)
        )

      // Every sequential body enters the existing typed value and regional effect scheduler.
      // Context-prefix arguments retain their source identities; captures are never reordered or
      // repaired with textual substitution.
      def renderSequentialRegion(
          parent: Option[String],
          path: String,
          captures: Vector[String],
          regionArguments: Vector[KernelDigitalRegionArgument],
          prefix: Vector[(String, String)],
          depth: Int
      ): String =
        val local = mutable.ArrayBuffer.empty[String]
        var blockArguments = Vector.empty[String]
        val lexical =
          KernelStructuralArmSnapshot(path, captures, regionArguments.drop(prefix.size), None)
        withArmContext(
          parent,
          lexical,
          () => {
            clearRegion(path)
            blockArguments = regionArguments.zipWithIndex.map: (argument, index) =>
              val captured = captures.lift(index - prefix.size)
              captured.foreach: input =>
                requireValid(
                  regionalTypeMatches(argument.dataType, expressionType(input), argument.path),
                  "sequential capture must preserve its exact complete type",
                  argument.path
                )
              val name = s"%sequential_region_${ordinal}_arg$index"
              val dataType = computedType(argument.dataType, argument.path)
              requireValid(
                prefix.lift(index).forall(_._2 == dataType),
                "sequential context prefix has a forged native type",
                argument.path
              )
              values.update(argument.path, name -> dataType)
              heights.update(argument.path, captured.map(heights(_) + 1).getOrElse(0))
              proofs.update(argument.path, captured.map(proofs).getOrElse(Unknown))
              if argument.role == "static_capture" then
                bindStaticArgument(argument.path, name -> dataType)
              s"$name: $dataType"
            ordinal += 1
            emitScope(path, depth + 1, local)
          }
        )
        val block =
          (if blockArguments.isEmpty then "^bb0:\n"
           else s"^bb0(${blockArguments.mkString(", ")}):\n") + local.mkString("\n")
        "{\n" + block.linesIterator.map("  " + _).mkString("\n") + "\n}"

      def emitSwitch(
          value: KernelEnumSwitchSnapshot,
          depth: Int,
          output: mutable.ArrayBuffer[String]
      ): Unit =
        if !emittedEffects.contains(value.path) then
          requireValid(
            depth <= 128 && active.add(value.path),
            "enum switch dependency graph contains a cycle or exceeds depth limit",
            value.path
          )
          val selector = emit(value.selector, depth + 1, value.parentRegion, output)
          val arms = value.arms :+ value.invalidArm
          val operands = Vector(selector) ++ arms.flatMap(_.captures).map(
            emit(_, depth + 1, value.parentRegion, output, topology = false)
          )
          val rendered = arms.map(arm =>
            renderSequentialRegion(
              value.parentRegion,
              arm.path,
              arm.captures,
              arm.arguments,
              Vector.empty,
              depth + 1
            )
          )
          val attributes =
            s"{contract_version = \"enum-switch-v1\", region_id = ${quoted(value.path)}, " +
              s"enum_ref = ${enums.reference(value.enumIdentity, value.path)}, " +
              s"case_ids = ${value.caseIds.map(quoted).mkString("[", ", ", "]")}, " +
              s"capture_counts = array<i32: ${arms.map(_.captures.size).mkString(", ")}>, " +
              s"arm_ids = ${arms.map(arm => quoted(arm.path)).mkString("[", ", ", "]")}, " +
              s"metadata = ${effectMetadata(value.path, value.sourceOrdinal)}}"
          output += s"${quoted("nodal.enum_switch")}(${operands.map(_._1).mkString(", ")}) <$attributes> " +
            rendered.mkString(
              "(",
              ", ",
              ")"
            ) + s" : (${operands.map(_._2).mkString(", ")}) -> ()${sourceLocation(value.path)}"
          emittedEffects += value.path
          active.remove(value.path)

      def emitMachine(
          machine: KernelFlatFsmSnapshot,
          depth: Int,
          output: mutable.ArrayBuffer[String]
      ): Unit =
        if !emittedEffects.contains(machine.path) then
          requireValid(
            depth <= 128 && active.add(machine.path),
            "flat FSM actual dependency graph contains a cycle or exceeds depth limit",
            machine.path
          )
          // The effective reset is an actual next/taken dependency even though it is owned
          // by the local domain binding rather than the flat_fsm operand segment. Emitting
          // only its raw input would leave a typed boundary/release producer after this use.
          module.domains.find(_.path == machine.domain).toVector.flatMap(_.attributes)
            .collect {
              case (key, path) if Set("clock_source", "effective_reset_source")(key) => path
            }
            .foreach(path => { emit(path, depth + 1, None, output); () })
          val inputs = machineInputs(machine)
          val operands = (inputs ++ machine.actions.flatMap(_.captures)).map(
            emit(_, depth + 1, None, output, topology = false)
          )
          val result = s"%flat_fsm_$ordinal"
          ordinal += 1
          val bits = s"!nodal.bits<${machine.localWidth}>"
          val rendered = machine.actions.map(action =>
            renderSequentialRegion(
              None,
              action.path,
              action.captures,
              action.arguments,
              Vector(
                "current_local" -> bits,
                "next_local" -> bits,
                "transition_taken" -> "!nodal.bits<1>"
              ),
              depth + 1
            )
          )
          def dictionary(fields: Vector[(String, String)]): String =
            fields.map((key, value) => s"$key = $value").mkString("{", ", ", "}")
          val states = machine.states.map(state =>
            dictionary(Vector(
              "case_id" -> quoted(state.caseId),
              "terminal" -> state.terminal.toString,
              "ordinal" -> s"${state.sourceOrdinal} : i64"
            ))
          )
          val transitions = machine.transitions.map(edge =>
            dictionary(Vector("source_case" -> quoted(edge.sourceCase)) ++
              edge.destinationCase.map(value => "destination_case" -> quoted(value)) ++ Vector(
                "mode" -> quoted(edge.mode),
                "ordinal" -> s"${edge.sourceOrdinal} : i64"
              ) ++
              Option.when(machine.stack.nonEmpty)("kind" -> quoted(edge.kind)) ++
              edge.resumeCase.map(value => "resume_case" -> quoted(value)))
          )
          val actions = machine.actions.map(action =>
            dictionary(Vector(
              "region_id" -> quoted(action.path),
              "phase" -> quoted(action.phase),
              "ordinal" -> s"${action.sourceOrdinal} : i64"
            ) ++
              action.ownerCase.map(value => "owner_case" -> quoted(value)) ++
              action.transitionOrdinal.map(value => "transition_ordinal" -> s"$value : i64"))
          )
          val counts = if machine.actions.isEmpty then "array<i32>"
          else s"array<i32: ${machine.actions.map(_.captures.size).mkString(", ")}>"
          val attributes = dictionary(Vector(
            "contract_version" -> quoted(
              if machine.stack.nonEmpty then "flat-fsm-v5"
              else if machine.timerEdges.nonEmpty || machine.transferEdges.nonEmpty then
                "flat-fsm-v4"
              else if machine.composition.nonEmpty then "flat-fsm-v3"
              else if machine.control.nonEmpty then "flat-fsm-v2" else "flat-fsm-v1"
            ),
            "machine_id" -> quoted(machine.path),
            "enum_ref" -> enums.reference(machine.enumIdentity, machine.path),
            "domain" -> domainReference(machine.domain),
            "initial_case" -> quoted(machine.initialCase),
            "requested_encoding" -> quoted(machine.requestedEncoding),
            "resolved_encoding" -> quoted(machine.resolvedEncoding),
            "local_width" -> s"${machine.localWidth} : i64",
            "illegal_policy" -> quoted(machine.illegalState),
            "case_ids" -> machine.localCodes.map(value => quoted(value.caseId)).mkString(
              "[",
              ", ",
              "]"
            ),
            "local_codes" -> machine.localCodes.map(value =>
              s"${value.code} : ui${machine.localWidth}"
            ).mkString("[", ", ", "]"),
            "states" -> states.mkString("[", ", ", "]"),
            "transitions" -> transitions.mkString("[", ", ", "]"),
            "actions" -> actions.mkString("[", ", ", "]"),
            "action_capture_counts" -> counts,
            "metadata" -> effectMetadata(machine.path, machine.sourceOrdinal)
          ) ++ machine.stack.map(stack =>
            def policy(target: Option[String]): String = dictionary(Vector("kind" -> quoted(
              if target.nonEmpty then "trap" else "assert_hold"
            )) ++
              target.map(value => "target_case" -> quoted(value)))
            "stack" -> dictionary(Vector(
              "capacity" -> s"${stack.capacity} : i64",
              "pointer_width" -> s"${stack.pointerWidth} : i64",
              "overflow" -> policy(stack.overflow),
              "underflow" -> policy(stack.underflow),
              "invalid_frame" -> policy(stack.invalidFrame)
            ))
          ) ++ Option.when(
            machine.stack.nonEmpty || machine.timerEdges.nonEmpty || machine.transferEdges.nonEmpty
          )(Vector(
            "lifecycle_mode" -> quoted(if machine.composition.nonEmpty then "composed"
            else if machine.control.nonEmpty then "controlled" else "legacy"),
            "timer_edges" -> machine.timerEdges.map(edge =>
              dictionary(Vector(
                "ordinal" -> s"${edge.ordinal} : i64",
                "cycles" -> s"${edge.cycles} : i64"
              ))
            ).mkString("[", ", ", "]"),
            "transfer_edges" -> machine.transferEdges.map(edge =>
              dictionary(Vector("ordinal" -> s"${edge.ordinal} : i64"))
            ).mkString("[", ", ", "]")
          )).toVector.flatten)
          val resultPaths = machineProjections(machine).map(_._1)
          val resultTypes = Vector(bits) ++ resultPaths.drop(1).map(_ => "!nodal.bits<1>")
          output += s"$result:${resultPaths.size} = ${quoted("nodal.flat_fsm")}(${operands.map(_._1).mkString(", ")}) <$attributes>" +
            (if rendered.isEmpty then "" else rendered.mkString(" (", ", ", ")")) +
            s" : (${operands.map(_._2).mkString(", ")}) -> (${resultTypes.mkString(", ")})${sourceLocation(machine.path)}"
          val height = inputs.map(heights).maxOption.getOrElse(0) + 1
          resultPaths.zip(resultTypes).zipWithIndex.map((value, index) =>
            (value._1, s"$result#$index", value._2)
          ).foreach: (path, name, dataType) =>
            values.update(path, name -> dataType)
            heights.update(path, height)
            proofs.update(path, Unknown)
          emittedEffects += machine.path
          active.remove(machine.path)

      def emitTiming(
          path: String,
          depth: Int,
          output: mutable.ArrayBuffer[String]
      ): (String, String) =
        values.getOrElseUpdate(
          path, {
            val query = timing.queries(path)
            requireValid(
              depth <= 128 && active.add(path),
              "timing predicate dependency is cyclic",
              path
            )
            val inputs = query.inputs.map(emit(_, depth + 1, None, output, topology = false))
            val result = s"%fsm_timing_$ordinal"
            ordinal += 1
            val attributes = s"{domain = ${domainReference(query.machine.domain)}" +
              query.cycles.map(value => s", cycles = $value : i64").getOrElse("") +
              s", metadata = ${metadata(path, false)}}"
            output += operation(
              if query.cycles.nonEmpty then "nodal.fsm_elapsed" else "nodal.fsm_transfer",
              result,
              "!nodal.bits<1>",
              inputs,
              attributes,
              sourceLocation(path)
            )
            heights.update(path, query.inputs.map(heights).maxOption.getOrElse(0) + 1)
            proofs.update(path, Unknown)
            active.remove(path)
            result -> "!nodal.bits<1>"
          }
        )

      def emitComposition(path: String, depth: Int, output: mutable.ArrayBuffer[String]): Unit =
        if !values.contains(path) then
          val join = composition.joins.get(path)
          val activation = composition.activations.get(path)
          val parent = join.map(_.parent).orElse(activation.map(_.parent)).get
          val ownerCase = join.map(_.value.ownerCase).orElse(activation.map(_.value.ownerCase)).get
          val children = join.map(_.value.children).orElse(activation.map(_.value.children)).get
          val resultPaths = join.map(value => Vector(value.value.result)).getOrElse(
            Vector(
              activation.get.value.restart,
              activation.get.value.cancel,
              activation.get.value.enable
            )
          )
          val identity = if join.nonEmpty then resultPaths.head
          else activation.get.value.restart.stripSuffix(".restart")
          requireValid(
            depth <= 128 && active.add(identity),
            "composition value dependency is cyclic",
            path
          )
          val inputs = if join.nonEmpty then composition.identityInputs(parent, children)
          else composition.activationInputs(parent) ++ composition.childIdentityInputs(children)
          val operands = inputs.map(emit(_, depth + 1, None, output, topology = false))
          val result = s"%fsm_composition_$ordinal"
          ordinal += 1
          val attributes =
            s"{composition_contract = \"fsm-composition-v1\", domain = ${domainReference(parent.domain)}, owner_case = ${quoted(ownerCase)}" +
              join.map(value =>
                s", mode = ${quoted(value.value.policy)}, selection = ${quoted(value.value.selection)}"
              ).getOrElse("") +
              s", metadata = ${metadata(identity, false)}}"
          val resultTypes = resultPaths.map(_ => "!nodal.bits<1>")
          val name = if join.nonEmpty then "nodal.fsm_join" else "nodal.fsm_activation"
          output += s"$result:${resultPaths.size} = ${quoted(name)}(${operands.map(_._1).mkString(", ")}) <$attributes> : " +
            s"(${operands.map(_._2).mkString(", ")}) -> (${resultTypes.mkString(", ")})${sourceLocation(identity)}"
          val height = inputs.map(heights).maxOption.getOrElse(0) + 1
          resultPaths.zipWithIndex.foreach: (resultPath, index) =>
            values.update(resultPath, s"$result#$index" -> "!nodal.bits<1>")
            heights.update(resultPath, height)
            proofs.update(resultPath, Unknown)
          active.remove(identity)

      def emitConditional(
          conditional: Conditional,
          depth: Int,
          output: mutable.ArrayBuffer[String]
      ): Option[(String, String)] =
        val path = conditional.path
        if emittedEffects.contains(path) then values.get(path)
        else
          requireValid(
            depth <= 128 && active.add(path),
            "structural conditional graph contains a cycle or exceeds depth limit",
            path
          )
          val predicate = emit(conditional.predicate, depth + 1, conditional.parentRegion, output)
          requireValid(
            predicate._2 == (if conditional.behavioralRoot.nonEmpty then "!nodal.bits<1>"
                             else "!nodal.static_predicate"),
            "conditional predicate has the wrong static or behavioral type",
            path
          )
          val result = conditional.dataType.map: sourceType =>
            prepareType(sourceType, path, conditional.parentRegion, output)
            val name = s"%digital_if_$ordinal"
            ordinal += 1
            name -> computedType(sourceType, path)
          val allCaptures = Vector(conditional.trueArm, conditional.falseArm).map(arm =>
            arm.captures.map(emit(
              _,
              depth + 1,
              conditional.parentRegion,
              output,
              topology = conditional.behavioralRoot.isEmpty,
              typeArithmetic = true
            ))
          )
          val rendered = Vector(conditional.trueArm, conditional.falseArm).zip(allCaptures).map:
            (arm, captures) =>
              val local = mutable.ArrayBuffer.empty[String]
              var blockArguments = Vector.empty[String]
              withArmContext(
                conditional.parentRegion,
                arm,
                () => {
                  clearRegion(arm.path)
                  blockArguments = arm.arguments.zipWithIndex.map: (argument, index) =>
                    requireValid(
                      (Set(
                        "GeneratedFamily",
                        "Clock",
                        "Reset"
                      )(argument.dataType) && expressionType(
                        arm.captures(index)
                      ) == argument.dataType) ||
                        regionalTypeMatches(
                          argument.dataType,
                          expressionType(arm.captures(index)),
                          argument.path
                        ),
                      "structural arm capture must preserve its exact complete type",
                      argument.path
                    )
                    val name = s"%structural_arm_${ordinal}_arg$index"
                    val dataType = if argument.dataType == "GeneratedFamily" then
                      "!nodal.generated_family"
                    else captureNativeType(argument)
                    values.update(argument.path, name -> dataType)
                    heights.update(argument.path, heights(arm.captures(index)) + 1)
                    proofs.update(argument.path, proofs(arm.captures(index)))
                    if argument.role == "static_capture" then
                      bindStaticArgument(argument.path, name -> dataType)
                    s"$name: $dataType"
                  ordinal += 1
                  emitScope(arm.path, depth + 1, local)
                  val yielded = arm.yieldValue.map(emit(_, depth + 1, Some(arm.path), local))
                  arm.yieldValue.zip(conditional.dataType).foreach: (input, expected) =>
                    requireValid(
                      finiteType(expressionType(input)) &&
                        regionalTypeMatches(expressionType(input), expected, path),
                      "structural yield must match the enclosing exact finite read-only ABI",
                      path
                    )
                  if conditional.behavioralRoot.isEmpty then
                    local += s"${quoted("nodal.structural_yield")}(${yielded.map(_._1).mkString}) " +
                      s"<{metadata = ${metadata(arm.path + ".yield", false)}}> : (${yielded.map(_._2).mkString}) -> ()${sourceLocation(path)}"
                }
              )
              // Capture identity is retained through actual first SSA/type use, not declaration order.
              val text = local.mkString("\n")
              val names = blockArguments.map(_.takeWhile(_ != ':'))
              // Inspect operation operands and typed ABI only. Authored metadata and locations
              // cannot manufacture capture use (including deliberately SSA-looking source names).
              def executableText(line: String): String =
                val result = new StringBuilder
                var index = 0
                var quotedText = false
                var escaped = false
                while index < line.length do
                  val character = line(index)
                  if !quotedText && line.startsWith("metadata = {", index) then
                    index += "metadata = {".length
                    var braces = 1
                    var quotedMetadata = false
                    var escapedMetadata = false
                    while index < line.length && braces > 0 do
                      val current = line(index)
                      if escapedMetadata then escapedMetadata = false
                      else if current == '\\' && quotedMetadata then escapedMetadata = true
                      else if current == '"' then quotedMetadata = !quotedMetadata
                      else if !quotedMetadata && current == '{' then braces += 1
                      else if !quotedMetadata && current == '}' then braces -= 1
                      index += 1
                    result.append(' ')
                  else if !quotedText && line.startsWith(" loc(", index) then index = line.length
                  else
                    result.append(character)
                    if escaped then escaped = false
                    else if character == '\\' && quotedText then escaped = true
                    else if character == '"' then quotedText = !quotedText
                    index += 1
                result.toString
              val operandPattern = "\"nodal\\.[a-z0-9_]+\"\\(([^)]*)\\)".r
              val nameIndices = names.zipWithIndex.toMap
              val argumentIndices = arm.arguments.zipWithIndex.collect {
                case (argument, index) if argument.dataType == "Integer" => argument.path -> index
              }.toMap
              val namePattern = "%structural_arm_[0-9]+_arg[0-9]+(?![A-Za-z0-9_])".r
              val tokenPattern =
                (java.util.regex.Pattern.quote(arm.path) + "\\.arg[0-9]+(?![A-Za-z0-9_])").r
              val order =
                local.iterator.flatMap(_.linesIterator).map(executableText).flatMap: line =>
                  val operands = operandPattern.findAllMatchIn(line).flatMap(value =>
                    namePattern.findAllIn(value.group(1)).flatMap(nameIndices.get)
                  ).toVector
                  val signature = line.lastIndexOf(" : ")
                  val schema = line.indexOf("struct_schema = ")
                  val typeText = (if schema >= 0 then line.substring(schema) else "") +
                    (if signature >= 0 then line.substring(signature + 3) else "")
                  operands ++ tokenPattern.findAllIn(typeText).flatMap(argumentIndices.get).toVector
                .toVector.distinct
              requireValid(
                order.size == captures.size,
                "structural capture has no actual body SSA or type use",
                arm.path
              )
              val renames = order.zipWithIndex.map((old, current) =>
                arm.arguments(old).path -> s"${arm.path}.arg$current"
              ).toMap
              val pathPattern =
                (java.util.regex.Pattern.quote(arm.path) + "\\.arg[0-9]+(?![A-Za-z0-9_])").r
              def canonical(text: String): String = pathPattern.replaceAllIn(
                text,
                matched =>
                  java.util.regex.Matcher.quoteReplacement(renames.getOrElse(
                    matched.matched,
                    matched.matched
                  ))
              )
              val block =
                s"^bb0(${order.map(blockArguments).map(canonical).mkString(", ")}):\n" + canonical(
                  text
                )
              (order.map(captures), block.linesIterator.map("  " + _).mkString("\n"))
          val operands = Vector(predicate) ++ rendered.flatMap(_._1)
          val contract =
            if conditional.behavioralRoot.nonEmpty then "behavioral-if-v1" else "structural-if-v1"
          val attributes =
            s"{contract_version = ${quoted(contract)}, region_id = ${quoted(path)}, " +
              s"operandSegmentSizes = array<i32: 1, ${rendered(0)._1.size}, ${rendered(1)._1.size}>, metadata = ${
                  if conditional.behavioralRoot.nonEmpty
                  then effectMetadata(path, conditional.sourceOrdinal)
                  else metadata(path, result.nonEmpty)
                }}"
          val assignment = result.map(value => value._1 + " = ").getOrElse("")
          val name = if conditional.behavioralRoot.nonEmpty then "nodal.behavioral_if"
          else "nodal.structural_if"
          output += s"$assignment${quoted(name)}(${operands.map(_._1).mkString(", ")}) <$attributes> " +
            rendered.map(value => "{\n" + value._2 + "\n}").mkString("(", ", ", ")") +
            s" : (${operands.map(_._2).mkString(", ")}) -> ${result.map(_._2).getOrElse("()")}${sourceLocation(path)}"
          result.foreach(value => values.update(path, value))
          heights.update(
            path,
            (Vector(
              conditional.predicate
            ) ++ conditional.trueArm.yieldValue ++ conditional.falseArm.yieldValue)
              .map(heights).maxOption.getOrElse(0) + 1
          )
          proofs.update(path, Unknown)
          active.remove(path)
          emittedEffects.add(path)
          result

      def emitGenerated(
          region: KernelGeneratedRegionSnapshot,
          depth: Int,
          output: mutable.ArrayBuffer[String]
      ): Unit =
        requireValid(
          depth <= 128 && emittedEffects.add(region.path),
          "digital Generate has duplicate execution or exceeds depth limit",
          region.path
        )
        val body = region.digitalBody.get
        val header = Vector(region.lower, region.upperExclusive, region.step).map: path =>
          if path.toLongOption.nonEmpty then
            staticValue(path, region.lexicalParent, output, StaticPurpose.Structural)
          else emit(path, depth + 1, region.lexicalParent, output)
        requireValid(
          header.forall(_._2 == "i64"),
          "Generate bounds require three actual i64 operands",
          region.path
        )
        val captures =
          body.captures.map(emit(
            _,
            depth + 1,
            region.lexicalParent,
            output,
            topology = false,
            typeArithmetic = true
          ))
        val induction = s"%generated_index_$ordinal"
        ordinal += 1
        val local = mutable.ArrayBuffer.empty[String]
        withGeneratedContext(
          region,
          () => {
            clearRegion(region.path)
            body.arguments.zipWithIndex.foreach: (argument, index) =>
              val value = if index == 0 then induction -> "i64"
              else captures(index - 1)._1 -> captureNativeType(argument)
              if index != 0 then
                requireValid(
                  (Set("Clock", "Reset")(argument.dataType) &&
                    expressionType(body.captures(index - 1)) == argument.dataType) ||
                    regionalTypeMatches(
                      argument.dataType,
                      expressionType(body.captures(index - 1)),
                      argument.path
                    ),
                  "Generate capture alias must preserve its exact type",
                  argument.path
                )
              values.update(argument.path, value)
              heights.update(
                argument.path,
                if index == 0 then 0 else heights(body.captures(index - 1)) + 1
              )
              proofs.update(
                argument.path,
                if index == 0 then Unknown else proofs(body.captures(index - 1))
              )
              if argument.dataType == "Integer" then bindStaticArgument(argument.path, value)
            val physicalAliases = body.arguments.drop(1).filter(argument =>
              Set("Clock", "Reset")(argument.dataType)
            ).map: argument =>
              physicalControlSource(
                argument.path,
                region.owner,
                Some(region.path),
                argument.path
              ) -> values(argument.path)
            val requiredPhysical = module.instances.filter(
              _.generatedScope.exists(_.owner == region.path)
            ).flatMap(_.actualBindings.flatMap(binding => Vector(binding.clock) ++ binding.reset))
            requireValid(
              requiredPhysical.forall(physicalAliases.toMap.contains),
              "generated instance requires the exact scoped capture for every physical binding",
              region.path
            )
            // Actual bindings are static values, not structural effects by themselves. Emit
            // their original Integer DAG through this same value/cache owner before rendering
            // the binding, so the trailing retained-static pass cannot reinterpret its purpose.
            module.instances.filter(_.generatedScope.exists(_.owner == region.path))
              .flatMap(_.parameterBindings.map(_._2)).distinct.foreach: path =>
                if staticExpressions.get(path).exists(_.dataType == "Integer") then
                  val _ = emit(path, depth + 1, Some(region.path), local, topology = false)
            local ++= renderGeneratedInstances(region, values.toMap ++ physicalAliases)
            region.declarations.foreach: path =>
              if Set("interface-port", "interface-array")(declarations(path)._2.kind) then
                // Regional definitions participate in lexical effect order, not the storage prelude.
                if interfaceEndpoints(path).definitionContexts.isEmpty then
                  emitInterface(path, depth + 1, Some(region.path), local)
              else emit(path, depth + 1, Some(region.path), local)
            emitScope(region.path, depth + 1, local)
            region.exports.foreach: exported =>
              val symbolExport = Set("instance", "interface")(exported.kind)
              val value = if symbolExport then Vector.empty
              else if exported.kind == "family" then
                Vector(values.getOrElse(
                  exported.target,
                  fail("family export must follow its exact nested Generate producer", region.path)
                ))
              else Vector(emit(exported.target, depth + 1, Some(region.path), local))
              val access = generatedAccess(exported, exported.access)
              val identity = s"${region.path}.export.${exported.member}"
              val kind = if exported.kind == "storage" then ""
              else
                "kind = " + quoted(exported.kind) + ", "
              val target = if !symbolExport then ""
              else
                "target = @" + quoted(if exported.kind == "interface" then
                  interfaceSymbol(exported.target)
                else
                  symbol(
                    exported.kind,
                    exported.target.substring(exported.target.lastIndexOf('.') + 1)
                  )) + ", "
              val attributes = kind + target +
                s"member = ${quoted(exported.member)}, access = ${quoted(access)}, " +
                s"metadata = ${metadata(identity, false)}"
              local += s"${quoted("nodal.generated_export")}(${value.map(_._1).mkString(", ")}) <{$attributes}> : (${value.map(_._2).mkString(", ")}) -> ()${sourceLocation(exported.target)}"

          }
        )
        // Generate is non-isolated: source capture aliases must serialize as actual outer
        // SSA/type identities, never as invented extra Generate block arguments.
        val aliases = body.arguments.drop(1).map(_.path).zip(body.captures).toMap
        val aliasPattern =
          (java.util.regex.Pattern.quote(region.path) + "\\.arg[0-9]+(?![A-Za-z0-9_])").r
        val text = aliasPattern.replaceAllIn(
          local.mkString("\n"),
          matched =>
            java.util.regex.Matcher.quoteReplacement(aliases.getOrElse(
              matched.matched,
              matched.matched
            ))
        )
        val block = (s"^bb0($induction: i64):\n" + text)
          .linesIterator.map("  " + _).mkString("\n")
        val attributes = s"{induction = \"i\", region_id = ${quoted(region.path)}, " +
          s"induction_path = ${quoted(region.induction)}, metadata = ${effectMetadata(region.path, body.sourceOrdinal)}}"
        val handle = region.yieldedArea.map: _ =>
          val result = s"%generated_family_$ordinal"
          ordinal += 1
          values.update(region.path, result -> "!nodal.generated_family")
          heights.update(region.path, header.map(_._1).size)
          proofs.update(region.path, Unknown)
          result
        output += handle.map(_ + " = ").getOrElse("") +
          s"${quoted("nodal.generate")}(${header.map(_._1).mkString(", ")}) <$attributes> " +
          s"({\n$block\n}) : (${header.map(_._2).mkString(", ")}) -> ${
              if handle.nonEmpty then "!nodal.generated_family" else "()"
            }${sourceLocation(region.path)}"

      def emitFifo(
          identity: String,
          depth: Int,
          output: mutable.ArrayBuffer[String]
      ): Unit =
        if !values.contains(identity) then
          requireValid(
            depth <= 128 && active.add(identity),
            "FIFO actual dependency graph contains a cycle or exceeds depth limit",
            identity
          )
          val results = fifoResults(identity)
          val payload = results.head
          val crossing = payload.crossing.get
          results.foreach(result => prepareType(result.dataType, result.path, None, output))
          val operands = payload.operands.map(emit(_, depth + 1, None, output))
          val resultTypes = results.map(result => computedType(result.dataType, result.path))
          requireValid(
            operands.zip(payload.operands).forall((value, input) =>
              value._2 == nativeType(expressionType(input), input)
            ) &&
              operands.map(_._2) == resultTypes &&
              exactValueType(payload.dataType, expressionType(payload.operands.head), identity),
            "FIFO requires the exact complete payload type and Bool control operands/results",
            identity
          )
          crossingStateBits += ExecutableCrossingContract.fifoStateBits(
            bitPatternWidth(payload.dataType, identity),
            crossing.depth.get
          )
          requireValid(
            crossingStateBits <= ExecutableCrossingContract.MaximumStateBits,
            "crossing state exceeds its expansion budget",
            identity
          )
          val result = s"%async_fifo_$ordinal"
          ordinal += 1
          val obligations = crossing.obligations.map((name, value) =>
            s"$name = ${quoted(value)}"
          ).mkString("obligations = {", ", ", "}, ")
          val attributes =
            s"{kind = ${quoted(crossing.kind)}, contract = ${quoted(crossing.contract)}, " +
              s"depth = ${crossing.depth.get} : i64, source_domain = ${domainReference(crossing.sourceDomain)}, " +
              s"destination_domain = ${domainReference(crossing.destinationDomain)}, " +
              obligations + s"metadata = ${metadata(identity, false)}}"
          output += s"$result:3 = ${quoted("nodal.async_fifo")}(${operands.map(_._1).mkString(", ")}) <$attributes>" +
            s" : (${operands.map(_._2).mkString(", ")}) -> (${resultTypes.mkString(", ")})${sourceLocation(identity)}"
          val height = payload.operands.map(heights).max + 1
          results.zip(resultTypes).zipWithIndex.foreach: (value, index) =>
            val (expression, dataType) = value
            values.update(expression.path, s"$result#$index" -> dataType)
            heights.update(expression.path, height)
            proofs.update(expression.path, Unknown)
          active.remove(identity)

      def emitGeneratedSelection(
          member: KernelGeneratedMemberSnapshot,
          path: String,
          region: Option[String],
          depth: Int,
          output: mutable.ArrayBuffer[String]
      ): Vector[(String, String)] =
        val root = member.selectionPrefix.headOption.map(_.family).getOrElse(member.family)
        var handle = values.getOrElse(
          member.familyInput.getOrElse(root),
          fail("generated selection must follow its actual family producer", path)
        )
        def ordinalValue(value: String): (String, String) =
          if value.toLongOption.nonEmpty then
            staticValue(value, region, output, StaticPurpose.Structural)
          else emit(value, depth + 1, region, output)
        member.selectionPrefix.zipWithIndex.foreach: (selection, index) =>
          val selected = ordinalValue(selection.ordinal)
          requireValid(
            handle._2 == "!nodal.generated_family" && selected._2 == "i64",
            "selected family requires exact family and static ordinal operands",
            path
          )
          val result = s"%selected_family_$ordinal"
          ordinal += 1
          val attributes = s"{kind = ${quoted("family")}, member = ${quoted(selection.member)}, " +
            s"access = ${quoted("read")}, metadata = ${metadata(s"$path.family$index", false)}}"
          output += operation(
            "nodal.generated_member",
            result,
            "!nodal.generated_family",
            Vector(handle, selected),
            attributes,
            sourceLocation(path)
          )
          handle = result -> "!nodal.generated_family"
        val selected = ordinalValue(member.ordinal)
        requireValid(
          handle._2 == "!nodal.generated_family" && selected._2 == "i64",
          "generated selection requires exact family and static ordinal operands",
          path
        )
        Vector(handle, selected)

      def emit(
          path: String,
          depth: Int,
          region: Option[String],
          output: mutable.ArrayBuffer[String],
          topology: Boolean = true,
          typeArithmetic: Boolean = false
      ): (String, String) =
        requireValid(depth <= 128, "digital expression graph exceeds depth limit", path)
        requireVisible(path, module.path, region, path)
        if predicates.contains(path) then
          values.getOrElseUpdate(
            path, {
              requireValid(active.add(path), "static predicate graph contains a cycle", path)
              val predicate = predicates(path)
              val comparison = Set("eq", "ne", "lt", "le", "gt", "ge").contains(predicate.operation)
              if comparison then predicate.operands.foreach(validatePredicateInput)
              val operands = predicate.operands.map(emit(
                _,
                depth + 1,
                region,
                output,
                requiresTopology(path)
              ))
              requireValid(
                operands.forall(_._2 == (if comparison then "i64" else "!nodal.static_predicate")),
                "static predicate requires exact typed operands",
                path
              )
              val result = s"%static_predicate_$ordinal"
              ordinal += 1
              output += operation(
                if comparison then "nodal.static_compare" else "nodal.static_logic",
                result,
                "!nodal.static_predicate",
                operands,
                s"{${if comparison then "comparison" else "operator_name"} = ${quoted(predicate.operation)}, metadata = ${metadata(path, false)}}",
                sourceLocation(path)
              )
              heights.update(path, predicate.operands.map(heights).maxOption.getOrElse(0) + 1)
              proofs.update(path, Unknown)
              active.remove(path)
              result -> "!nodal.static_predicate"
            }
          )
        else if timing.queries.contains(path) then emitTiming(path, depth + 1, output)
        else if composition.joins.contains(path) || composition.activations.contains(path) then
          emitComposition(path, depth + 1, output)
          values.getOrElse(path, fail("composition result has no actual producer", path))
        else if machineResults.contains(path) then
          emitMachine(machineResults(path), depth + 1, output)
          values.getOrElse(path, fail("flat FSM result has no actual producer", path))
        else if expressions.get(path).exists(_.operation == "cdc_fifo") then
          emitFifo(expressions(path).crossing.get.identity.get, depth, output)
          requireValid(
            depth + heights(path) <= 128,
            "digital expression graph exceeds depth limit",
            path
          )
          values.getOrElse(path, fail("FIFO result has no actual producer", path))
        else if conditionals.contains(path) then
          emitConditional(conditionals(path), depth, output).getOrElse(
            fail("statement structural if cannot be used as a value", path)
          )
        else if iterationIndices.contains(path) then
          values.getOrElseUpdate(
            path, {
              requireValid(active.add(path), "iteration index graph contains a cycle", path)
              val index = iterationIndices(path)
              val operands = index.operands.map(emit(_, depth + 1, region, output))
              requireValid(operands.forall(_._2 == "i64"), "iteration operands must be i64", path)
              val result = s"%digital_index_$ordinal"
              ordinal += 1
              output += operation(
                "nodal.iteration_index",
                result,
                "i64",
                operands,
                s"{operator_name = ${quoted(index.operation)}, metadata = ${metadata(path, false)}}",
                sourceLocation(path)
              )
              heights.update(path, index.operands.map(heights).max + 1)
              proofs.update(path, Unknown)
              active.remove(path)
              result -> "i64"
            }
          )
        else if staticExpressions.contains(path) ||
          declarations.get(path).exists(value =>
            value._2.kind == "parameter" && value._2.dataType.contains("Integer")
          )
        then
          val purpose =
            if !topology then StaticPurpose.RuntimeValue
            else if typeArithmetic then StaticPurpose.TypeArithmetic
            else StaticPurpose.Structural
          val value = staticValue(path, region, output, purpose)
          values.update(path, value)
          heights.update(path, 0)
          proofs.update(path, Unknown)
          value
        else
          values.get(path) match
            case Some(value) =>
              requireValid(
                depth + heights(path) <= 128,
                "digital expression graph exceeds depth limit",
                path
              )
              value
            case None =>
              requireValid(active.add(path), "digital expression graph contains a cycle", path)
              val result = s"%digital_value_$ordinal"
              var actualResult = result
              ordinal += 1
              val sourceType = expressionType(path)
              requireValid(
                region.isEmpty || region.exists(
                  hasBehavioralAncestor
                ) || (sourceType != "Integer" && sourceType != "Real"),
                "collection expressions require finite digital values",
                path
              )
              prepareType(sourceType, path, region, output)
              expressions.get(path).flatMap(_.structure).foreach(value =>
                prepareType(value.schemaType, path, region, output)
              )
              val payloadType =
                if expressions.get(path).exists(e =>
                    e.operation != "shape_reference" && e.reset.isEmpty && e.clock.isEmpty
                  )
                then
                  computedType(sourceType, path)
                else nativeType(sourceType, path)
              val dataType = declarations.get(path).map(value =>
                declarationNativeType(value._2, payloadType)
              ).getOrElse(payloadType)
              val unsigned = scalarType(sourceType, path).exists(_.kind != "SInt")
              val (text, height, proof) = expressions.get(path) match
                case Some(expression) =>
                  val typeCaptures =
                    collections.get(path).map(_.captures.toSet).getOrElse(Set.empty)
                  val operands = expression.operands.map(input =>
                    emit(
                      input,
                      depth + 1,
                      region,
                      output,
                      typeArithmetic = typeCaptures.contains(input)
                    )
                  )
                  val height = expression.operands.map(heights).maxOption.getOrElse(-1) + 1
                  val specialized =
                    DigitalInterfaceContract.ValueOperations.contains(expression.operation)
                  if !specialized && expression.operation != "behavioral_predicate" then
                    expression.operands.zip(operands).foreach: (input, actual) =>
                      requireValid(
                        actual._2 == nativeType(expressionType(input), input),
                        "a digital capability cannot be used as an ordinary typed value",
                        path
                      )
                  if expression.generatedMember.nonEmpty then
                    val member = expression.generatedMember.get
                    val (family, exported) = generatedMemberExport(expression)
                    val _ = family
                    val operands = emitGeneratedSelection(member, path, region, depth, output)
                    val attributes = s"{member = ${quoted(member.member)}, " +
                      s"access = ${quoted(generatedAccess(exported, member.access))}, metadata = ${metadata(path, false)}}"
                    (
                      Vector(operation(
                        "nodal.generated_member",
                        result,
                        dataType,
                        operands,
                        attributes,
                        sourceLocation(path)
                      )),
                      height,
                      Unknown
                    )
                  else if expression.clock.nonEmpty then
                    val clock = expression.clock.get
                    val controlDomains = clock.controlKinds.zip(clock.controlDomains).map:
                      case ("domain", Some(domain)) => domainReference(domain)
                      case (kind, _) => quoted(kind)
                    val obligations = clock.obligations.map((key, value) =>
                      s"$key = ${quoted(value)}"
                    ).mkString(", ")
                    val attrs = Vector(
                      s"parent_domains = [${clock.parents.map(domainReference).mkString(", ")}]",
                      s"domain = ${domainReference(clock.destination)}",
                      s"edge = ${quoted(clock.edge)}",
                      s"contract = ${quoted(clock.contract)}",
                      s"control_domains = [${controlDomains.mkString(", ")}]",
                      s"obligations = {$obligations}",
                      s"metadata = ${metadata(path, false)}"
                    )
                    (
                      Vector(operation(
                        "nodal.clock_" + clock.kind,
                        result,
                        dataType,
                        operands,
                        "{" + attrs.mkString(", ") + "}",
                        sourceLocation(path)
                      )),
                      height,
                      Unknown
                    )
                  else if expression.reset.nonEmpty then
                    val reset = expression.reset.get
                    val attrs = Vector(
                      s"reset_polarity = ${quoted(reset.polarity)}",
                      s"metadata = ${metadata(path, false)}"
                    ) ++
                      (reset.kind match
                        case "boundary" => Vector(
                            s"release = ${quoted(reset.release)}"
                          ) ++ reset.destination.toVector.map(value =>
                            s"release_domain = ${domainReference(value)}"
                          )
                        case "sync" => Vector(
                            s"destination_domain = ${domainReference(reset.destination.get)}",
                            s"stages = ${reset.stages} : i64"
                          )
                        case _ => Vector.empty)
                    (
                      Vector(operation(
                        "nodal.reset_" + reset.kind,
                        result,
                        dataType,
                        operands,
                        "{" + attrs.mkString(", ") + "}",
                        sourceLocation(path)
                      )),
                      height,
                      Unknown
                    )
                  else if expression.operation == ExecutableWaiverContract.Operation then
                    val waiver = expression.waiver.get
                    requireValid(
                      operands.map(_._2) == Vector(dataType) &&
                        exactValueType(
                          expression.dataType,
                          expressionType(expression.operands.head),
                          path
                        ) &&
                        bitPatternWidth(
                          expression.dataType,
                          path,
                          fixed = true
                        ) <= ExecutableWaiverContract.MaximumPayloadBits,
                      "waiver requires the exact complete fixed whole input/result native type of 1 through 4096 bits",
                      path
                    )
                    val relation = waiver.relation
                    val fields = Vector("kind = " + quoted(relation.kind)) ++
                      relation.multiply.map(value => s"multiply = $value : i32") ++
                      relation.divide.map(value => s"divide = $value : i32") ++
                      relation.phase.map(value => "phase = " + quoted(value)) ++
                      relation.phaseKnown.map(value => "phase_known = " + value.toString)
                    val obligations = waiver.obligations.map((name, value) =>
                      s"$name = ${quoted(value)}"
                    ).mkString("{", ", ", "}")
                    val attributes =
                      s"{contract = ${quoted(waiver.contract)}, source_domain = ${domainReference(waiver.sourceDomain)}, " +
                        s"destination_domain = ${domainReference(waiver.destinationDomain)}, id = ${quoted(waiver.id)}, " +
                        s"reason = ${quoted(waiver.reason)}, relation = {${fields.mkString(", ")}}, " +
                        s"obligations = $obligations, metadata = ${metadata(path, false)}}"
                    (
                      Vector(operation(
                        "nodal.waiver",
                        result,
                        dataType,
                        operands,
                        attributes,
                        sourceLocation(path)
                      )),
                      height,
                      proofs.getOrElse(expression.operands.head, Unknown)
                    )
                  else if ExecutableCrossingContract.Operations(expression.operation) then
                    val crossing = expression.crossing.get
                    requireValid(
                      operands.map(_._2) == Vector(dataType) &&
                        exactValueType(
                          expression.dataType,
                          expressionType(expression.operands.head),
                          path
                        ),
                      "crossing requires the exact complete input/result native type",
                      path
                    )
                    val bits = if ExecutableCrossingContract.bitPatternPayload(crossing.kind) then
                      bitPatternWidth(expression.dataType, path)
                    else BigInt(1)
                    crossingStateBits += ExecutableCrossingContract.stateBits(
                      crossing.kind,
                      bits,
                      crossing.stages
                    )
                    requireValid(
                      crossingStateBits <= ExecutableCrossingContract.MaximumStateBits,
                      "crossing state exceeds its expansion budget",
                      path
                    )
                    val obligations = if crossing.obligations.isEmpty then ""
                    else
                      crossing.obligations.map((name, value) => s"$name = ${quoted(value)}")
                        .mkString("obligations = {", ", ", "}, ")
                    val attributes =
                      s"{kind = ${quoted(crossing.kind)}, contract = ${quoted(crossing.contract)}, " +
                        s"stages = ${crossing.stages} : i64, source_domain = ${domainReference(crossing.sourceDomain)}, " +
                        s"destination_domain = ${domainReference(crossing.destinationDomain)}, " +
                        obligations + s"metadata = ${metadata(path, false)}}"
                    (
                      Vector(operation(
                        "nodal.crossing",
                        result,
                        dataType,
                        operands,
                        attributes,
                        sourceLocation(path)
                      )),
                      height,
                      Unknown
                    )
                  else if expression.operation == "memory_read" then
                    val port = expression.memory.get
                    val declaration = memoryPort(module.path, port.memory, port.domain, path)._1
                    requireValid(
                      scalarType(
                        expressionType(expression.operands.head),
                        path
                      ).exists(_.kind == "UInt") &&
                        exactValueType(expression.dataType, declaration.dataType.get, path),
                      "memory read requires UInt address and exact declared element result type",
                      path
                    )
                    (
                      Vector(renderMemoryRead(expression, result, dataType, operands.head)),
                      height,
                      Unknown
                    )
                  else if expression.operation == "behavioral_predicate" then
                    requireValid(
                      expression.dataType == "Bool" && operands.map(_._2) == Vector(
                        "!nodal.static_predicate"
                      ) &&
                        expression.literal.isEmpty && expression.enumIdentity.isEmpty && expression.caseIds.isEmpty,
                      "behavioral predicate requires one actual checked static predicate and a Bool result",
                      path
                    )
                    (
                      Vector(operation(
                        "nodal.behavioral_predicate",
                        result,
                        dataType,
                        operands,
                        s"{metadata = ${metadata(path, false)}}",
                        sourceLocation(path)
                      )),
                      height,
                      Unknown
                    )
                  else if expression.operation == DigitalCollectionContract.Operation then
                    val collection = collections(path)
                    val vectors =
                      collection.inputs.map(input => vectorType(expressionType(input), path))
                    def shaped(element: String, dimensions: Vector[String]): String =
                      s"Vec($element;${dimensions.mkString("x")})"
                    if collection.kind == "zip_with" then
                      requireValid(
                        exactValueType(
                          shaped("Bool", vectors(0)._2),
                          shaped("Bool", vectors(1)._2),
                          path
                        ),
                        "collection zip requires identical complete dimensions",
                        path
                      )
                    val reduction = reductionArguments(collection)
                    val primary = reduction.getOrElse(collection.seed match
                      case Some(seed) =>
                        Vector(expressionType(seed) -> "accumulator", vectors.head._1 -> "element")
                      case None => vectors.zipWithIndex.map((value, index) =>
                          value._1 -> (if vectors.size == 1 then "element" else s"element$index")
                        ))
                    val expected = primary ++ collection.captures.map: input =>
                      val dataType = expressionType(input)
                      dataType -> (if dataType == "Integer" then "static_capture" else "capture")
                    requireValid(
                      collection.arguments.size == expected.size,
                      "collection block arguments do not match operand segments",
                      path
                    )
                    val local = mutable.ArrayBuffer.empty[String]
                    var blockArguments = Vector.empty[String]
                    var yielded = "" -> ""
                    val startOrdinal = ordinal
                    val bodyPaths = bodyValuePaths(path)
                    withCollectionContexts(
                      collection,
                      () => {
                        ordinal = startOrdinal
                        local.clear()
                        bodyPaths.foreach: identity =>
                          values.remove(identity)
                          heights.remove(identity)
                          proofs.remove(identity)
                          emittedEffects.remove(identity)
                        blockArguments = collection.arguments.zip(expected).zipWithIndex.map:
                          case ((argument, (expectedType, role)), index) =>
                            requireValid(
                              argument.role == role &&
                                collectionArgumentMatches(collection, argument, expectedType) &&
                                argument.dataType != "Real" &&
                                (argument.dataType != "Integer" || role == "static_capture" ||
                                  role.startsWith("left_width:") ||
                                  role.startsWith("right_width:")),
                              "collection block argument must retain the complete finite operand type and canonical role",
                              argument.path
                            )
                            val name = s"${result}_arg$index"
                            val argumentType = computedType(argument.dataType, argument.path)
                            values.update(argument.path, name -> argumentType)
                            if argument.dataType == "Integer" then
                              bindStaticArgument(argument.path, name -> argumentType)
                            val capture = collection.captures.lift(index - primary.size)
                            heights.update(argument.path, capture.map(heights(_) + 1).getOrElse(0))
                            proofs.update(
                              argument.path,
                              capture.map(proofs).getOrElse(Proof(
                                None,
                                scalarType(argument.dataType, argument.path).exists(_.kind !=
                                  "SInt")
                              ))
                            )
                            s"$name: $argumentType"
                        val bodyExpressions = byRegion.getOrElse(Some(path), Vector.empty)
                        // Retained unused static operations are part of the closed pure body too.
                        staticByRegion.getOrElse(Some(path), Vector.empty).sortBy(_.path).foreach(
                          value =>
                            staticValue(value.path, Some(path), local, StaticPurpose.TypeArithmetic)
                        )
                        bodyExpressions.filterNot(value =>
                          DigitalCombinationalContract.ReachableBooleanOperations.contains(
                            value.operation
                          )
                        ).sortBy(_.path).foreach(value =>
                          emit(value.path, depth + 1, Some(path), local)
                        )
                        yielded = emit(collection.yieldValue, depth + 1, Some(path), local)
                        val yieldedType = expressionType(collection.yieldValue)
                        requireValid(
                          yieldedType != "Integer" && yieldedType != "Real",
                          "collection yield requires a finite digital value",
                          path
                        )
                        if reduction.isEmpty then
                          requireValid(
                            collectionResultMatches(collection, yieldedType),
                            "collection result must retain exact element type, complete seed type and every input dimension",
                            path
                          )
                        bodyExpressions.filter(value =>
                          DigitalCombinationalContract.ReachableBooleanOperations.contains(
                            value.operation
                          )
                        ).foreach(value =>
                          requireValid(
                            values.contains(value.path),
                            "ordinary Boolean operations require a reachable digital value root",
                            value.path
                          )
                        )
                        local +=
                          s"${quoted("nodal.collection_yield")}(${yielded._1}) <{metadata = ${metadata(path, false)}}> : (${yielded._2}) -> ()${sourceLocation(path)}"
                      }
                    )
                    val attributes =
                      s"{kind = ${quoted(collection.kind)}, region_id = ${quoted(path)}, " +
                        s"operandSegmentSizes = array<i32: ${collection.inputs.size}, ${collection.seed.size}, ${collection.captures.size}>, metadata = ${metadata(path, true)}}"
                    val block = s"^bb0(${blockArguments.mkString(", ")}):\n" + local.mkString("\n")
                    val nested = block.linesIterator.map("  " + _).mkString("\n")
                    val text =
                      s"$result = ${quoted("nodal.collection")}(${operands.map(_._1).mkString(", ")}) <$attributes> ({\n$nested\n}) : " +
                        s"(${operands.map(_._2).mkString(", ")}) -> $dataType${sourceLocation(path)}"
                    (
                      Vector(text),
                      math.max(height, heights(collection.yieldValue) + 1),
                      Proof(None, unsigned)
                    )
                  else if specialized then
                    requireValid(
                      region.isEmpty,
                      "interface effects are not pure collection operations",
                      path
                    )
                    val projected = specializedValue(expression, result, dataType, operands)
                    requireValid(
                      projected.value._2 == dataType,
                      "specialized digital result differs from its exact source type",
                      path
                    )
                    actualResult = projected.value._1
                    (projected.lines, height, Proof(None, unsigned))
                  else if expression.operation == "shape_reference" then
                    requireValid(
                      expression.operands.isEmpty && expression.literal.isEmpty &&
                        expression.enumIdentity.isEmpty && expression.caseIds.isEmpty &&
                        shapeByPath.get(path).exists(_._1 == module.path),
                      "digital projection must name one authoritative owned shape record",
                      path
                    )
                    val selectors = selectorsByPath.getOrElse(path, Vector.empty)
                      .filter(_.toLongOption.isEmpty)
                    val expectedDependencies = shapeByPath(path)._2 +: selectors
                    val dependencies = mutable.ArrayBuffer.empty[String]
                    val projected = projectionValue(
                      path,
                      dependency =>
                        requireValid(
                          expectedDependencies.contains(dependency),
                          "shape resolver requested a different immediate input",
                          path
                        )
                        dependencies += dependency
                        emit(dependency, depth + 1, region, output)
                    )
                    requireValid(
                      dependencies.toVector == expectedDependencies &&
                        projected.value._2 == dataType,
                      "digital projection result or dependency differs from its exact source contract",
                      path
                    )
                    actualResult = projected.value._1
                    (projected.lines, dependencies.map(heights).max + 1, Proof(None, unsigned))
                  else if expression.operation.startsWith("enum_") then
                    enums.validateExpression(
                      expression,
                      expression.operands.map(expressionType),
                      exactUnsignedWidth
                    )
                    (
                      Vector(enums.renderExpression(
                        expression,
                        result,
                        dataType,
                        operands,
                        sourceLocation(path)
                      )),
                      height,
                      Proof(None, unsigned)
                    )
                  else if DigitalAggregateContract.Operations.contains(expression.operation) then
                    val evidence = expression.structure.get
                    def finite(dataType: String): Boolean =
                      dataType == "Bool" || scalarType(dataType, path).nonEmpty ||
                        dataType.startsWith("Enum(") || dataType.startsWith("Vec(") ||
                        dataType.startsWith("Struct(")
                    requireValid(
                      finite(expression.dataType) &&
                        expression.operands.forall(input => finite(expressionType(input))),
                      "Struct operations cannot expose standalone Real or Integer leaves",
                      path
                    )
                    val schema = structType(evidence.schemaType, path)
                    requireValid(
                      expression.literal.isEmpty && expression.enumIdentity.isEmpty &&
                        expression.caseIds.isEmpty,
                      "Struct operations cannot carry scalar or enum facts",
                      path
                    )
                    val fieldAttributes = if expression.operation == "digital_struct_get" then
                      requireValid(
                        expression.operands.size == 1 && evidence.ordinal.nonEmpty &&
                          evidence.name.nonEmpty,
                        "Struct get requires one receiver and an exact member",
                        path
                      )
                      val receiver = expressionType(expression.operands.head)
                      val fields = structType(receiver, path)._2
                      val index = evidence.ordinal.get
                      requireValid(
                        widthFamily(evidence.schemaType, receiver, path) && index >= 0 &&
                          index < fields.size && fields(index)._1 == evidence.name.get &&
                          schema._2(index)._1 == evidence.name.get &&
                          exactValueType(fields(index)._2, expression.dataType, path),
                        "Struct get schema, ordinal, name or actual field result type differs",
                        path
                      )
                      s", field_ordinal = $index : i64, field_name = ${quoted(evidence.name.get)}"
                    else
                      val fields = structType(expression.dataType, path)._2
                      requireValid(
                        evidence.ordinal.isEmpty && evidence.name.isEmpty &&
                          widthFamily(evidence.schemaType, expression.dataType, path) &&
                          expression.operands.size == fields.size &&
                          fields.zip(expression.operands).forall((field, input) =>
                            exactValueType(field._2, expressionType(input), path)
                          ),
                        "Struct make requires the declared family and exact ordered field operands",
                        path
                      )
                      ""
                    val attributes = s"{operator_name = ${quoted(expression.operation)}, " +
                      s"struct_schema = ${nativeType(evidence.schemaType, path)}$fieldAttributes, " +
                      s"metadata = ${metadata(path, true)}}"
                    (
                      Vector(operation(
                        "nodal.digital_expr",
                        result,
                        dataType,
                        operands,
                        attributes,
                        sourceLocation(path)
                      )),
                      height,
                      Proof(None, unsigned)
                    )
                  else if expression.operation.startsWith("digital_") ||
                    DigitalCombinationalContract.ReachableBooleanOperations.contains(
                      expression.operation
                    )
                  then
                    val proof =
                      if expression.operation == DigitalMaterializationContract.Operation then
                        requireValid(
                          exactValueType(
                            expressionType(expression.operands.head),
                            expression.dataType,
                            path
                          ) &&
                            (expression.dataType == "Bool" ||
                              scalarType(expression.dataType, path).nonEmpty ||
                              expression.dataType.startsWith("Enum(") ||
                              expression.dataType.startsWith("Vec(") ||
                              expression.dataType.startsWith("Struct(")),
                          "digital alias must preserve one exact complete finite value type",
                          path
                        )
                        proofs(expression.operands.head)
                      else if DigitalScalarContract.Operations.contains(expression.operation) then
                        requireValid(
                          expression.operands.size == 1,
                          "digital conversion requires one operand",
                          path
                        )
                        scalarConversion(
                          expression,
                          expressionType(expression.operands.head),
                          proofs(expression.operands.head),
                          scalarType,
                          widthRelation
                        )
                      else
                        combinational(
                          expression,
                          expression.operands.map(proofs),
                          scalarType,
                          widthRelation,
                          widthFormula,
                          exactValueType
                        )
                    val attributes =
                      s"{operator_name = ${quoted(expression.operation)}, metadata = ${metadata(path, true)}}"
                    (
                      Vector(operation(
                        "nodal.digital_expr",
                        result,
                        dataType,
                        operands,
                        attributes,
                        sourceLocation(path)
                      )),
                      height,
                      proof
                    )
                  else
                    val (literal, proof) = scalarLiteral(expression, scalarType, Some(scalarFacts))
                    val spelling = if expression.dataType == "Bool" then
                      if expression.literal.contains("true") then "1" else "0"
                    else expression.literal.get
                    val attributes =
                      s"{value = $literal, spelling = ${quoted(spelling)}, metadata = ${metadata(path, false)}}"
                    (
                      Vector(operation(
                        "nodal.const_literal",
                        result,
                        dataType,
                        Vector.empty,
                        attributes,
                        sourceLocation(path)
                      )),
                      height,
                      proof
                    )
                case None =>
                  val declaration = declarations(path)._2
                  requireValid(
                    declaration.generatedScope.isEmpty ||
                      (generatedDeclaration(path) && declaration.generatedScope.exists(scope =>
                        region.contains(scope.owner)
                      )),
                    "digital operand cannot escape its exact generated declaration scope",
                    path
                  )
                  val initialPath = declarationInitializer(declaration)
                  initialPath.foreach: input =>
                    val inputOwner =
                      expressions.get(input).map(_.owner).orElse(declarations.get(input).map(_._1))
                    requireValid(
                      inputOwner.contains(module.path),
                      "digital initializer must belong to its exact module",
                      path
                    )
                    requireValid(
                      exactValueType(expressionType(input), sourceType, path),
                      "digital initializer requires the exact storage type",
                      path
                    )
                  val initial = initialPath.map: input =>
                    val initialRegion = valueRegion(input)
                    requireValid(
                      initialRegion == region || initialRegion.isEmpty,
                      "generated storage initializer must be local or module-owned",
                      path
                    )
                    emit(input, depth + 1, initialRegion, output)
                  val height = initialPath.map(heights(_) + 1).getOrElse(0)
                  (
                    declarationValue(
                      declaration,
                      result,
                      dataType,
                      initial,
                      declaration.instancePort.flatMap(_.selection).map: member =>
                        val _ = selectedInstanceExport(declaration)
                        emitGeneratedSelection(member, path, region, depth, output)
                      .getOrElse(Vector.empty)
                    ),
                    height,
                    Proof(None, unsigned)
                  )
              output ++= text
              val value = actualResult -> dataType
              values.update(path, value)
              heights.update(path, height)
              proofs.update(path, proof)
              active.remove(path)
              value
      val moduleExpressions = byOwner.getOrElse(module.path, Vector.empty).filter(_.region.isEmpty)
      def emitModuleRoots(): Unit =
        moduleExpressions.filterNot(value =>
          DigitalCombinationalContract.ReachableBooleanOperations.contains(value.operation)
        ).sortBy(_.path).foreach(value => emit(value.path, 0, None, lines))
        additionalRoots.distinct.sorted.foreach: path =>
          val owner = valueOwner(path)
          requireValid(
            owner.contains(module.path),
            "specialized digital root has another owner",
            path
          )
          emit(path, 0, None, lines)
        module.declarations.filter(value =>
          Set("wire", "register").contains(value.kind) &&
            value.generatedScope.isEmpty
        )
          .sortBy(_.path).foreach(value => emit(value.path, 0, None, lines))
      // Actual parent physical controls are module-owned producers. A generated
      // binding may consume them, but cannot relocate their physical identity.
      hierarchy.physicalRoots(module).distinct.sorted.foreach(emit(_, 0, None, lines))
      val moduleChoices = conditionalsByRegion.getOrElse(module.path -> None, Vector.empty)
      // A value choice may contain writes. Materialize its dependencies on demand at the
      // authored effect position; retain the predecessor pure-root schedule otherwise.
      if moduleChoices.isEmpty && !snapshot.flatFsms.exists(_.owner == module.path) &&
        !switchesByRegion.contains(module.path -> None) &&
        !generatedByRegion.getOrElse(
          module.path -> None,
          Vector.empty
        ).exists(_.yieldedArea.nonEmpty)
      then emitModuleRoots()
      // Port values are the canonical storage operands. Materialize all process headers before
      // updates; native target lowering supplies the pre-event state cuts/backedges.
      snapshot.eventProcesses.filter(_.owner == module.path).sortBy(_.path).foreach: process =>
        val trigger = emit(process.trigger, 0, None, lines)
        val targets = process.storageTargets.map(emit(_, 0, None, lines))
        val captures = process.externalCaptures.map(emit(_, 0, None, lines))
        val operands = Vector(trigger) ++ targets ++ captures
        val obligations = DigitalEventContract.Obligations.map((key, value) =>
          s"$key = ${quoted(value)}"
        ).mkString("{", ", ", "}")
        val attributes = s"sym_name = ${quoted(process.path)}, edge = ${quoted(process.edge)}, " +
          s"contract = ${quoted(DigitalEventContract.Contract)}, obligations = $obligations, " +
          s"operandSegmentSizes = array<i32: 1, ${targets.size}, ${captures.size}>, " +
          s"metadata = ${metadata(process.path, false)}"
        lines += s"${quoted("nodal.event_process")}(${operands.map(_._1).mkString(", ")}) <{$attributes}> : " +
          s"(${operands.map(_._2).mkString(", ")}) -> ()${sourceLocation(process.path)}"
      def emitModuleAssignment(assignment: KernelDigitalAssignmentSnapshot): Unit =
        val target = emit(assignment.target, 0, None, lines)
        val value = emit(assignment.value, 0, None, lines)
        lines += renderAssignment(assignment, target, value)
      def emitLoop(loop: KernelDigitalLoopSnapshot): Unit =
        val header = Vector(loop.lower, loop.upperExclusive, loop.step).map: path =>
          if path.toLongOption.nonEmpty then
            staticValue(path, None, lines, StaticPurpose.Structural)
          else emit(path, 0, None, lines)
        requireValid(
          header.forall(_._2 == "i64"),
          "loop header requires static i64 operands",
          loop.path
        )
        val captures = loop.captures.map(emit(_, 0, None, lines, typeArithmetic = true))
        val local = mutable.ArrayBuffer.empty[String]
        val blockArguments = loop.arguments.zipWithIndex.map: (argument, index) =>
          val name = s"%loop_${ordinal}_arg$index"
          val dataType = if index == 0 then "i64" else captures(index - 1)._2
          values.update(argument.path, name -> dataType)
          heights.update(
            argument.path,
            if index == 0 then 0 else heights(loop.captures(index - 1)) + 1
          )
          proofs.update(
            argument.path,
            if index == 0 then Unknown else proofs(loop.captures(index - 1))
          )
          if argument.dataType == "Integer" then
            bindStaticArgument(argument.path, name -> dataType)
          s"$name: $dataType"
        ordinal += 1
        withLoopContext(
          loop,
          () => {
            // All regional effects use the same scoped SSA schedule, including both
            // alternatives of parameter-controlled structural choices in a procedural loop.
            emitScope(loop.path, 0, local)
          }
        )
        local += s"${quoted("nodal.hardware_loop_end")}() : () -> ()"
        // Transport follows the actual first SSA use, while source capture identities remain
        // associated with their original operand. Do not introduce a fake use for dead captures.
        val argumentNames = blockArguments.map(_.takeWhile(_ != ':'))
        val argumentOrdinals = argumentNames.zipWithIndex.toMap
        val capturePattern = "%loop_[0-9]+_arg[0-9]+(?![A-Za-z0-9_])".r
        val operandPattern = "\"nodal\\.[a-z0-9_]+\"\\(([^)]*)\\)".r
        val used = local.iterator.flatMap(line => operandPattern.findAllMatchIn(line))
          .flatMap(value => capturePattern.findAllIn(value.group(1)))
          .flatMap(argumentOrdinals.get).filter(_ != 0).toVector.distinct
        requireValid(
          used.size == captures.size,
          "loop capture has no actual body SSA use",
          loop.path
        )
        val captureOrder = used.map(_ - 1)
        val orderedArguments = blockArguments.head +: used.map(blockArguments)
        val operands = header ++ captureOrder.map(captures)
        val maximum = loop.maximum.map(value => s", maximum = $value : i64").getOrElse("")
        val attributes =
          s"{contract_version = \"bounded-digital-v1\", effect_policy = \"ordered\", " +
            s"region_id = ${quoted(loop.path)}, induction_path = ${quoted(loop.arguments.head.path)}, " +
            s"induction = \"i\"$maximum, " +
            s"metadata = ${effectMetadata(loop.path, loop.sourceOrdinal)}}"
        val block = s"^bb0(${orderedArguments.mkString(", ")}):\n" + local.mkString("\n")
        val nested = block.linesIterator.map("  " + _).mkString("\n")
        lines += s"${quoted("nodal.hardware_loop")}(${operands.map(_._1).mkString(", ")}) <$attributes> " +
          s"({\n$nested\n}) : (${operands.map(_._2).mkString(", ")}) -> ()${sourceLocation(loop.path)}"
      val effects = scheduledInterfaces(None, 0, lines) ++
        assignmentsByRegion.getOrElse(module.path -> None, Vector.empty)
          .map(value => (value.sourceOrdinal, value.path, () => emitModuleAssignment(value))) ++
        memoryWritesByRegion.getOrElse(module.path -> None, Vector.empty)
          .map(value =>
            (value.sourceOrdinal, value.path, () => emitMemoryWrite(value, 0, lines))
          ) ++
        loopsByOwner.getOrElse(module.path, Vector.empty)
          .map(value => (value.sourceOrdinal, value.path, () => emitLoop(value))) ++
        moduleChoices.map(value =>
          (
            value.sourceOrdinal,
            value.path,
            () => { emitConditional(value, 0, lines); () }
          )
        ) ++
        generatedByRegion.getOrElse(module.path -> None, Vector.empty)
          .map(value =>
            (value.digitalBody.get.sourceOrdinal, value.path, () => emitGenerated(value, 0, lines))
          ) ++
        snapshot.flatFsms.filter(_.owner == module.path)
          .map(value => (value.sourceOrdinal, value.path, () => emitMachine(value, 0, lines))) ++
        switchesByRegion.getOrElse(module.path -> None, Vector.empty)
          .map(value => (value.sourceOrdinal, value.path, () => emitSwitch(value, 0, lines)))
      requireValid(
        effects.forall(_._1 == 0) && !loopsByOwner.contains(module.path) &&
          !memoryWritesByRegion.contains(module.path -> None) &&
          conditionalsByRegion.getOrElse(module.path -> None, Vector.empty).isEmpty &&
          generatedByRegion.getOrElse(module.path -> None, Vector.empty).isEmpty ||
          effects.map(_._1).distinct.size == effects.size,
        "module digital effects require distinct source ordinals",
        module.path
      )
      effects.sortBy(value => (value._1, value._2)).foreach(value => value._3())
      localInterfaceEndpoints.filter(value => valueRegion(value.path).isEmpty).foreach(value =>
        emitInterface(value.path, 0, None, lines)
      )
      if moduleChoices.nonEmpty || snapshot.flatFsms.exists(_.owner == module.path) ||
        switchesByRegion.contains(module.path -> None) ||
        generatedByRegion.getOrElse(
          module.path -> None,
          Vector.empty
        ).exists(_.yieldedArea.nonEmpty)
      then emitModuleRoots()
      predicatesByRegion.getOrElse(None, Vector.empty).filter(_.owner == module.path)
        .sortBy(_.path).foreach(value => emit(value.path, 0, None, lines))
      moduleExpressions.filter(value =>
        DigitalCombinationalContract.ReachableBooleanOperations.contains(value.operation)
      ).foreach(value =>
        requireValid(
          values.contains(value.path),
          "ordinary Boolean operations require a reachable digital value root",
          value.path
        )
      )
      Rendered(lines.toVector, values.iterator.filter((path, _) => valueRegion(path).isEmpty).toMap)
