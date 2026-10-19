package nodal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.IdentityHashMap

import scala.collection.mutable

/** One mutable transaction. JVM identity is used only for transient lookup; stable paths use
  * hierarchy, explicit names, and deterministic local ordinals.
  */
private final class ConstructionSession(val options: EmitOptions):
  import ConstructionExpressionFacts.{disciplineDimension, waveformContinuity, waveformStatic}
  import ConstructionInterfaceLayout.{accessMember, memberName, validAccess}

  def inferAnalogDimension(value: Any): AnalogDimension =
    ConstructionExpressionFacts.inferAnalogDimension(value)

  def waveformConstant(value: Any): Option[Double] =
    ConstructionExpressionFacts.waveformConstant(value)

  /** Consumer placement follows actual registered results, even when source shape wrappers hide
    * their rendered operation. The existing operator inventories remain the classification owner.
    */
  def requireProceduralSources(expressions: Vector[KernelExpr[?]]): Unit =
    val references = expressions.flatMap(value => Option(expressionIds.get(value))).toSet
    def reject(reference: ExpressionRef, code: String, role: String): Nothing =
      AnalogProceduralConstruction.requireContinuousContext(role)
      fail(
        code,
        s"$role is not permitted in analog procedural expressions",
        Some(expressionPath(reference))
      )
    continuousOperators.find(value => references.contains(value.reference)).foreach: value =>
      reject(
        value.reference,
        "NODAL-ANALOG-035-001",
        s"stateful or continuous-time operator '${value.operation}'"
      )
    waveformOperators.find(value =>
      references.contains(value._1) && value._2 != "analog_abstime"
    ).foreach: value =>
      reject(value._1, "NODAL-ANALOG-036-001", s"time/waveform operator '${value._2}'")
    transferOperators.find(value => references.contains(value._1)).foreach: value =>
      reject(value._1, "NODAL-ANALOG-040-001", s"stateful transfer operator '${value._2}'")
    noiseOperators.find(value => references.contains(value._1)).foreach: value =>
      reject(value._1, "NODAL-ANALOG-039-001", s"noise effect '${value._2}'")

  private val userFunctions = new AnalogUserFunctionRuntime.Registry
  private var nextModule: Long = 0L
  private val moduleIds = new IdentityHashMap[AnyRef, java.lang.Long]()
  private val domainIds = new IdentityHashMap[AnyRef, DomainRef]()
  private val declarationIds = new IdentityHashMap[AnyRef, DeclarationRef]()
  private val expressionIds = new IdentityHashMap[AnyRef, ExpressionRef]()
  private val instanceIds = new IdentityHashMap[AnyRef, InstanceRecord]()
  private val instanceObjects = new IdentityHashMap[AnyRef, Instance[? <: Module]]()
  private val records: mutable.LinkedHashMap[Long, ModuleRecord] = mutable.LinkedHashMap.empty
  private val moduleStack: mutable.ArrayBuffer[ModuleRecord] = mutable.ArrayBuffer.empty
  private val domainStack: mutable.ArrayBuffer[ClockDomain] = mutable.ArrayBuffer.empty
  private val operations: mutable.ArrayBuffer[Operation] = mutable.ArrayBuffer.empty
  private val expressionValues: mutable.LinkedHashMap[ExpressionRef, KernelExpr[?]] =
    mutable.LinkedHashMap.empty
  private val analogRegions: mutable.ArrayBuffer[AnalogRegionRecord] = mutable.ArrayBuffer.empty
  private val analogStack: mutable.ArrayBuffer[AnalogRegionRecord] = mutable.ArrayBuffer.empty
  private val analogSemanticRecorder = new AnalogEquationRuntime.Recorder
  private var analogSemanticContext: Option[AnalogSemanticContext] = None
  private val continuousOperators: mutable.ArrayBuffer[ContinuousOperatorRecord] =
    mutable.ArrayBuffer.empty
  private val waveformOperators: mutable.ArrayBuffer[(
      ExpressionRef,
      String,
      Vector[Expr[Real]],
      String,
      Vector[String],
      String,
      String,
      String
  )] =
    mutable.ArrayBuffer.empty
  private val transferOperators: mutable.ArrayBuffer[
    (ExpressionRef, String, Int, Int, Vector[Expr[Real]], String)
  ] = mutable.ArrayBuffer.empty
  private val noiseOperators
      : mutable.ArrayBuffer[(ExpressionRef, String, String, Vector[Expr[Real]], String)] =
    mutable.ArrayBuffer.empty
  private var waveformForbiddenDepth = 0
  private val semanticOrigin = new SemanticOriginBuilder
  private var semanticResult: Option[SemanticOriginResult] = None
  private val rootParameterBindings: mutable.ArrayBuffer[(Any, Any)] =
    mutable.ArrayBuffer.empty
  private val generationStack: mutable.ArrayBuffer[GeneratedRegionRecord] =
    mutable.ArrayBuffer.empty
  private val generatedOwnershipRecords =
    mutable.LinkedHashMap.empty[(String, String), GeneratedRegionRecord]
  private val shapeIndices = mutable.LinkedHashMap.empty[ExpressionRef, ShapeIndexRecord]
  private val shapeViews = mutable.LinkedHashMap.empty[ExpressionRef, ShapeViewRecord]
  private val nodeSelectionLookup = mutable.HashMap.empty[
    (DeclarationRef | ExpressionRef, Option[GeneratedRegionRecord]),
    mutable.LinkedHashMap[StructuralShapeCoordinates.Region, ExpressionRef]
  ]
  private val instanceSelectionStack = mutable.ArrayBuffer.empty[Instance[?]]
  private var constructorFailure: Option[(Throwable, String)] = None

  private def fail(code: String, message: String, path: Option[String] = None): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(code, message, path))
    ).get

  private def moduleName(module: Module): String =
    val name = module.getClass.getSimpleName.stripSuffix("$")
    if name.isEmpty then "AnonymousModule" else name

  private def currentModule: ModuleRecord = moduleStack.lastOption.getOrElse(
    fail("NODAL-CONSTRUCT-016", "hardware construction has no active Module")
  )

  private def activeGeneratedRegion: Option[GeneratedRegionRecord] =
    moduleStack.lastOption.flatMap(module =>
      generationStack.reverseIterator.find(_.owner == module.handle)
    )

  private def generatedScopeSnapshot(region: Option[GeneratedRegionRecord])
      : Option[KernelGeneratedScope] =
    region.map(value =>
      KernelGeneratedScope(generatedRegionPath(value), expressionPath(value.induction))
    )

  def requireNoGeneratedEffect(role: String): Unit =
    activeGeneratedRegion.foreach: region =>
      fail(
        "NODAL-ITERATION-043-004",
        s"$role is not permitted inside structural hdlRange until generated ownership and target lowering are available",
        Some(generatedRegionPath(region))
      )

  def generatedRegionActive: Boolean = activeGeneratedRegion.nonEmpty

  def generatedContinuousRegionActive: Boolean =
    activeGeneratedRegion.exists: generated =>
      analogStack.lastOption.exists(_.generatedOwnership.contains(
        generatedRegionPath(generated) -> expressionPath(generated.induction)
      ))

  def generatedRegionOwnership: Option[(String, String)] =
    activeGeneratedRegion.map: region =>
      generatedRegionPath(region) -> expressionPath(region.induction)

  private def moduleHandle(module: Module): Long =
    Option(moduleIds.get(module)).map(_.longValue).getOrElse(
      fail("NODAL-OWNERSHIP-017", "Module is outside this construction transaction")
    )

  private def domainRef(domain: ClockDomain): DomainRef =
    Option(domainIds.get(domain)).getOrElse(
      fail("NODAL-DOMAIN-020", "ClockDomain is outside this construction transaction")
    )

  def beginModule(module: Module): Unit =
    if moduleIds.containsKey(module) then
      fail("NODAL-LIFECYCLE-016", "one Module entered construction twice")
    val handle = nextModule
    nextModule += 1
    val record = new ModuleRecord(
      handle,
      moduleName(module),
      moduleStack.lastOption.map(_.handle)
    )
    records += handle -> record
    moduleIds.put(module, java.lang.Long.valueOf(handle))
    semanticOrigin.captureModule(handle, module, record.className, record.parentAtConstruction)
    moduleStack += record

  def prepareConstructorAllocation(site: String): Int =
    constructorFailure.foreach: (_, firstSite) =>
      fail(
        "NODAL-CONSTRUCTOR-LIFECYCLE-018",
        s"construction transaction was already invalidated by a captured allocation at $firstSite",
        Some(site)
      )
    moduleStack.size

  def poisonConstructor(failure: Throwable, site: String): Unit =
    if constructorFailure.isEmpty then constructorFailure = Some(failure -> site)

  def abortConstructorAllocation(
      depth: Int,
      failure: Throwable,
      site: String,
      invalidate: Boolean
  ): Unit =
    if invalidate then poisonConstructor(failure, site)
    while moduleStack.size > depth do moduleStack.remove(moduleStack.size - 1)

  def constructorDepth: Int = moduleStack.size

  def bindConstructorParameters(
      module: Module,
      parameters: Vector[ConstructorCapturedParameter]
  ): Unit =
    val handle = moduleHandle(module)
    if moduleStack.lastOption.forall(_.handle != handle) then
      fail(
        "NODAL-CONSTRUCTOR-LIFECYCLE-019",
        "constructor parameters can only bind at the exact child Module.begin"
      )
    parameters.foreach: parameter =>
      registerDeclaration(
        parameter.carrier,
        KernelSignalKind.Parameter,
        CandidateRuntime.expressionDataType(parameter.default),
        Some(parameter.name),
        None,
        Vector(
          "default" -> parameter.default,
          "unit" -> CandidateRuntime.expressionUnit(parameter.default).getOrElse("")
        )
      )

  def registerDomain(domain: ClockDomain, kind: KernelDomainKind): Unit =
    requireNoGeneratedEffect(s"generated ${kind.label} domain declaration")
    val module = currentModule
    if domainIds.containsKey(domain) then
      fail("NODAL-DOMAIN-016", "one ClockDomain was registered twice")
    if module.domains.exists(_.name == domain.name) then
      fail("NODAL-DOMAIN-017", s"duplicate domain name '${domain.name}'")
    val reference = DomainRef(module.handle, module.domains.size)
    module.domains += DomainRecord(reference, domain, domain.name, kind)
    domainIds.put(domain, reference)
    semanticOrigin.captureDomain(module.handle, reference.index, domain, domain.name, kind.label)

  private def requireGeneratedDeclarationScope(reference: DeclarationRef): Unit =
    val generatedDeclaration =
      records(reference.module).generatedRegions.find(_.declarations.contains(reference))
    if generatedDeclaration.exists(region =>
        region.owner != currentModule.handle || !generationStack.exists(_ eq region)
      )
    then
      fail("NODAL-HIERARCHY-044", "local node escaped its generated lexical scope")

  private def validateGeneratedDependencies(values: Iterable[Any]): Unit =
    val pending = mutable.ArrayBuffer.from(values)
    val visited = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    while pending.nonEmpty do
      pending.remove(pending.size - 1) match
        case expression: KernelExpr[?]
            if Option(visited.put(expression, java.lang.Boolean.TRUE)).isEmpty =>
          if expression.operation.contains("generate_index") then
            val reference = Option(expressionIds.get(expression))
            if !generationStack.exists(region =>
                region.owner == currentModule.handle && reference.contains(region.induction)
              )
            then
              fail(
                "NODAL-ITERATION-043-004",
                "generated induction dependency escaped its active owning Module/Generate scope"
              )
          else pending ++= expression.operands
        case node: Node[?] =>
          node.endpointReference.foreach: reference =>
            val selection = shapeIndices.get(reference)
            val scope = selection.flatMap(_.generatedScope)
              .orElse(deferredNodeArray(reference).flatMap(_._1.generatedScope))
            if scope.exists(region =>
                region.owner != currentModule.handle || !generationStack.exists(_ eq region)
              )
            then
              fail("NODAL-HIERARCHY-044", "selected endpoint escaped its generated lexical scope")
            selection.flatMap(_.conservative).foreach: selected =>
              selected.array match
                case declaration: DeclarationRef => requireGeneratedDeclarationScope(declaration)
                case _ => ()
        case _ => ()

  def registerDeclaration(
      value: AnyRef,
      kind: KernelSignalKind,
      dataType: Option[DataType[? <: Data]],
      explicitName: Option[String],
      domain: Option[ClockDomain],
      attributes: Vector[(String, Any)],
      conservativeDimensions: Vector[Dimension] = Vector.empty
  ): Unit =
    val activeGeneratedVariable =
      kind == KernelSignalKind.Variable && AnalogProceduralConstruction.generatedProcedureActive
    if kind != KernelSignalKind.AnalogNode && kind != KernelSignalKind.AnalogNodeArray &&
      !activeGeneratedVariable
    then
      requireNoGeneratedEffect(s"generated ${kind.label} declaration")
    val module = currentModule
    validateGeneratedDependencies(attributes.map(_._2))
    dataType.foreach(validateShapeType(_, module.handle, kind == KernelSignalKind.Variable))
    if declarationIds.containsKey(value) then
      fail("NODAL-OWNERSHIP-016", s"${kind.label} was registered twice")
    val reference = DeclarationRef(module.handle, module.declarations.size)
    module.declarations += DeclarationRecord(
      reference,
      value,
      kind,
      dataType,
      explicitName,
      domain,
      attributes,
      conservativeDimensions
    )
    declarationIds.put(value, reference)
    semanticOrigin.captureDeclaration(
      module.handle,
      reference.index,
      value,
      kind.label,
      explicitName
    )
    generationStack.lastOption
      .filter(_.owner == module.handle)
      .foreach(_.declarations += reference)

  def registerNodeArray(
      value: NodeArray[?],
      discipline: Discipline,
      dimensions: Vector[Dimension],
      kind: KernelSignalKind
  ): Unit =
    val module = currentModule
    if !conservativeArrayKinds.contains(
        kind
      ) || discipline != Electrical || dimensions.isEmpty || analogStack.nonEmpty ||
      analogSemanticContext.nonEmpty
    then
      fail(
        "NODAL-SHAPE-043-005",
        "node arrays require an owned Electrical declaration outside analog regions"
      )
    if kind != KernelSignalKind.AnalogNodeArray then
      requireNoGeneratedEffect("generated boundary-array declaration")
    val analysis =
      new StructuralBoundAnalysis(
        module.handle,
        "NODAL-SHAPE-043-005",
        "node-array dimension",
        signed64Literals = true
      )
    val bounds = dimensions.map(analysis(_))
    if bounds.exists(_.bounds.lower <= 0L) ||
      bounds.foldLeft(BigInt(1))((count, bound) => count * BigInt(bound.bounds.upper)) >
        BigInt(Long.MaxValue)
    then
      fail(
        "NODAL-SHAPE-043-005",
        "node arrays require positive finite dimensions and signed-64-bit element counts"
      )
    registerDeclaration(
      value,
      kind,
      None,
      None,
      None,
      CandidateRuntime.conservativeAttributes(discipline),
      dimensions
    )
    bounds.flatMap(_.parameters).distinct.foreach: parameter =>
      markStructuralParameterEffect(parameter, "shape")
      markStructuralParameterEffect(parameter, "rank")

  private val conservativePortKinds =
    Set(KernelSignalKind.AnalogInput, KernelSignalKind.AnalogOutput, KernelSignalKind.AnalogInout)
  private val conservativeArrayKinds = conservativePortKinds + KernelSignalKind.AnalogNodeArray

  private def instanceArrayRoot(reference: ExpressionRef)
      : Option[(Operation, InstanceArrayRootRecord)] =
    operations.iterator.flatMap(operation =>
      operation.values.collect {
        case root: InstanceArrayRootRecord if root.reference == reference => operation -> root
      }
    ).nextOption()

  private def deferredNodeArray(reference: ExpressionRef)
      : Option[(Operation, DeferredNodeArrayRecord)] =
    operations.iterator.flatMap(operation =>
      operation.values.collect {
        case record: DeferredNodeArrayRecord if record.reference == reference => operation -> record
      }
    ).nextOption()

  def withInstanceSelection[A](instance: Instance[?])(body: => A): A =
    requireInstanceScope(instanceRecord(instance))
    instanceSelectionStack += instance
    try body
    finally instanceSelectionStack.remove(instanceSelectionStack.size - 1)

  def nodeArrayUse[D <: Discipline](value: NodeArray[D]): NodeArray[D] =
    Option(declarationIds.get(value)) match
      case Some(port) if port.module != currentModule.handle =>
        val instance = instanceSelectionStack.lastOption.getOrElse(
          fail("NODAL-SHAPE-043-007", "child array use requires an exact Instance selector")
        )
        val record = instanceRecord(instance)
        requireInstanceScope(record)
        val declaration = records(port.module).declarations(port.index)
        if record.child != port.module || !conservativePortKinds.contains(declaration.kind) ||
          declaration.conservativeDimensions.isEmpty
        then
          fail("NODAL-SHAPE-043-007", "Instance selector requires its own declared array boundary")
        operations.iterator.filter(operation =>
          operation.owner == currentModule.handle &&
            operation.generatedScope == activeGeneratedRegion
        ).flatMap(_.values.collect {
          case root: InstanceArrayRootRecord
              if root.instanceOrdinal == record.ordinal && root.port == port =>
            root.value.asInstanceOf[NodeArray[D]]
        }).nextOption().getOrElse(new NodeArray(
          value.discipline,
          NodeArrayShapeIntent.InstancePort(instance, value)
        ))
      case _ => value

  def registerInstanceNodeArray(
      value: NodeArray[?],
      instance: Instance[?],
      port: NodeArray[?]
  ): Unit =
    val occurrence = instanceRecord(instance)
    requireInstanceScope(occurrence)
    val declaration = Option(declarationIds.get(port)).getOrElse(
      fail("NODAL-SHAPE-043-007", "instance array root requires an actual declared boundary")
    )
    val record = records(declaration.module).declarations(declaration.index)
    if occurrence.child != declaration.module || !conservativePortKinds.contains(record.kind) ||
      record.conservativeDimensions.isEmpty || value.discipline != Electrical
    then
      fail("NODAL-SHAPE-043-007", "instance array root is not its exact Electrical boundary")
    val reference = captureExpression(value).getOrElse(
      fail("NODAL-SHAPE-043-007", "instance array requires an active module")
    )
    operation(
      "instance-node-array",
      new InstanceArrayRootRecord(value, reference, occurrence.ordinal, declaration)
    )

  private def nodeArrayDimensions(reference: DeclarationRef | ExpressionRef)
      : Option[Vector[Dimension]] =
    reference match
      case declaration: DeclarationRef =>
        Some(records(declaration.module).declarations(declaration.index)
          .conservativeDimensions.map(_.asInstanceOf[Dimension]))
      case expression: ExpressionRef => instanceArrayRoot(expression).flatMap(_._2.dimensions)
          .orElse(shapeViews.get(expression).map(_.dimensions))
          .orElse(shapeIndices.get(expression).filter(_.resultDimensions.nonEmpty)
            .map(_.resultDimensions.map(_.asInstanceOf[Dimension])))

  private def nodeArrayInput(array: NodeArray[?])
      : (DeclarationRef | ExpressionRef, DeclarationRef | ExpressionRef) =
    val module = currentModule.handle
    val input: DeclarationRef | ExpressionRef = Option(declarationIds.get(array))
      .orElse(Option(expressionIds.get(array))).getOrElse(
        fail("NODAL-SHAPE-043-006", "node-array reference has no owned captured identity")
      )
    val seen = mutable.HashSet.empty[ExpressionRef]
    def root(reference: DeclarationRef | ExpressionRef): DeclarationRef | ExpressionRef =
      reference match
        case declaration: DeclarationRef =>
          if declaration.module != module || !conservativeArrayKinds.contains(
              records(declaration.module).declarations(declaration.index).kind
            ) ||
            records(
              declaration.module
            ).declarations(declaration.index).conservativeDimensions.isEmpty
          then
            fail("NODAL-SHAPE-043-006", "node-array reference must retain its local owned root")
          requireGeneratedDeclarationScope(declaration)
          declaration
        case expression: ExpressionRef =>
          if expression.module != module || !seen.add(expression) then
            fail("NODAL-SHAPE-043-006", "node-array reference has a cyclic or foreign projection")
          instanceArrayRoot(expression) match
            case Some((operation, record)) =>
              requireInstanceScope(records(module).instances(record.instanceOrdinal))
              if operation.generatedScope != activeGeneratedRegion then
                fail("NODAL-HIERARCHY-044", "instance array escaped its exact generated scope")
              expression
            case None =>
              val scope = shapeViews.get(expression).flatMap(_.generatedScope)
                .orElse(shapeIndices.get(expression).flatMap(_.generatedScope))
                .orElse(deferredNodeArray(expression).flatMap(_._1.generatedScope))
              if scope.exists(region => !generationStack.exists(_ eq region)) then
                fail("NODAL-HIERARCHY-044", "node-array view escaped its generated scope")
              val parent = shapeViews.get(expression).map(_.input)
                .orElse(shapeIndices.get(
                  expression
                ).filter(_.resultDimensions.nonEmpty).map(_.input))
                .orElse(deferredNodeArray(expression).map(value =>
                  nodeArrayInput(value._2.input)._1
                ))
                .getOrElse(fail(
                  "NODAL-SHAPE-043-006",
                  "node-array reference has no shaped projection"
                ))
              root(parent)
    val owner = root(input)
    if array.discipline != Electrical || array.shapeIntent.knownDimensions.exists(dimensions =>
        nodeArrayDimensions(input).exists(_ != dimensions)
      )
    then
      fail("NODAL-SHAPE-043-006", "node-array reference changed discipline or captured dimensions")
    (input, owner)

  def registerNodeArrayProjection(value: NodeArray[?], origin: NodeArrayProjection): Unit =
    val (input, _) = nodeArrayInput(origin.input)
    if value.discipline != origin.input.discipline then
      fail("NODAL-SHAPE-043-006", "node-array view changed discipline")
    val operands = origin.indices ++ origin.reshape ++ origin.slice.toVector.flatMap(value =>
      Vector(value.lower, value.upperExclusive)
    )
    val analysis = new StructuralBoundAnalysis(
      currentModule.handle,
      "NODAL-SHAPE-043-006",
      "node-array view",
      signed64Literals = true
    )
    operands.foreach(nodeIndexBound(_, analysis))
    if nodeArrayDimensions(input).isEmpty then
      val reference = captureExpression(value, Vector(origin.input) ++ operands).get
      operation(
        "deferred-node-array",
        DeferredNodeArrayRecord(value, reference, origin.input, Some(origin))
      )
    else materializeNodeArrayProjection(value, origin, None)

  private def materializeNodeArrayProjection(
      value: NodeArray[?],
      origin: NodeArrayProjection,
      retained: Option[ExpressionRef]
  ): Unit =
    val (input, _) = nodeArrayInput(origin.input)
    val source = nodeArrayDimensions(input).getOrElse(
      fail("NODAL-SHAPE-043-006", "node-array projection input is unresolved")
    )
    val dimensions = value.shapeIntent.knownDimensions.getOrElse:
      if origin.indices.nonEmpty then source.drop(origin.indices.size)
      else if origin.flatten then
        CandidateRuntime.flattenShapeDimensions(source, signed64Literal = true)
      else
        origin.slice match
          case Some(slice) => CandidateRuntime.sliceShapeDimensions(
              source,
              slice.lower,
              slice.upperExclusive,
              slice.axis
            )
          case None => origin.reshape
    val module = currentModule.handle
    val path = pathOfReference(input)
    val code = if origin.slice.nonEmpty then "NODAL-SHAPE-043-004" else "NODAL-SHAPE-043-003"
    val analysis =
      new StructuralBoundAnalysis(module, code, "node-array view", signed64Literals = true)
    val parameters = if origin.indices.nonEmpty then
      if origin.indices.size >= source.size || dimensions != source.drop(origin.indices.size) then
        fail("NODAL-SHAPE-043-002", "node-array prefix result has incompatible dimensions")
      val indices = origin.indices.map(nodeIndexBound(_, analysis))
      val extents = source.take(origin.indices.size).map(analysis(_))
      origin.indices.indices.foreach: axis =>
        val valid = directGeneratedInduction(origin.indices(axis)) match
          case Some(region) => region.maximumTripCount == 0 || analysis.sameIdentity(
              region.upperExclusive.asInstanceOf[Dimension],
              source(axis)
            ) ||
            analysis(region.upperExclusive.asInstanceOf[Dimension]).bounds.upper <= extents(
              axis
            ).bounds.lower
          case None => indices(axis).bounds.lower >= 0 && indices(axis).bounds.upper < extents(
              axis
            ).bounds.lower
        if !valid then
          fail("NODAL-SHAPE-043-002", "node-array prefix is not valid for every legal extent")
      indices.flatMap(_.parameters)
    else validateShapeViewDimensions(source, dimensions, origin.slice, module, path, analysis)
    val reference = retained.getOrElse(captureExpression(
      value,
      Vector(origin.input) ++ origin.indices ++ dimensions
    ).get)
    if origin.indices.nonEmpty then
      shapeIndices.update(
        reference,
        ShapeIndexRecord(
          reference,
          input,
          origin.indices,
          dimensions,
          generatedScope = activeGeneratedRegion
        )
      )
    else
      shapeViews.update(
        reference,
        ShapeViewRecord(reference, input, dimensions, origin.slice, activeGeneratedRegion)
      )
    parameters.distinct.foreach(markStructuralParameterEffect(_, "shape"))

  private def directGeneratedInduction(value: Any): Option[GeneratedRegionRecord] = value match
    case expression: KernelExpr[?] if expression.operation.contains("generate_index") =>
      val reference = Option(expressionIds.get(expression)).getOrElse(
        fail("NODAL-SHAPE-043-006", "generated index has no captured identity")
      )
      val region = generationStack.find(value =>
        value.owner == currentModule.handle && value.induction == reference
      )
        .getOrElse(fail("NODAL-SHAPE-043-006", "generated index escaped its active owning scope"))
      if expression.resultType != Some(
          KernelTypeDescriptor("Integer")
        ) || expression.operands.nonEmpty
      then
        fail("NODAL-SHAPE-043-006", "generated index has invalid Integer provenance")
      Some(region)
    case _ => None

  private def nodeIndexBound(value: Dimension, analysis: StructuralBoundAnalysis): StructuralBound =
    directGeneratedInduction(value) match
      case None => analysis(value)
      case Some(region) =>
        val lower = analysis(region.lower.asInstanceOf[Dimension])
        val upper = analysis(region.upperExclusive.asInstanceOf[Dimension])
        val step = analysis(region.step.asInstanceOf[Dimension])
        if (region.maximumTripCount != 0 && lower.bounds.lower < 0) || step.bounds.lower <= 0 then
          fail(
            "NODAL-SHAPE-043-002",
            "generated node index requires nonnegative lower and positive step"
          )
        // An empty scope has no attained index. Keep a representable placeholder envelope;
        // its type, owner and shape checks still run before publishing the captured template.
        val maximum = if region.maximumTripCount == 0 then lower.bounds.upper
        else math.max(lower.bounds.upper, upper.bounds.upper - 1L)
        StructuralBound(
          value,
          IterationDomain.Bounds(lower.bounds.lower, maximum),
          (lower.parameters ++ upper.parameters ++ step.parameters).distinct
        )

  private def nodeSelectionMap(
      input: DeclarationRef | ExpressionRef,
      indices: Vector[Dimension]
  ): (DeclarationRef | ExpressionRef, StructuralShapeCoordinates.Region, Vector[(BigInt, BigInt)]) =
    val analysis = new StructuralBoundAnalysis(
      currentModule.handle,
      "NODAL-SHAPE-043-006",
      "node-array physical map",
      signed64Literals = true
    )
    val bounds = mutable.HashMap.empty[String, (BigInt, BigInt)]
    def token(value: Any): String =
      val dimension = value match
        case literal: Int => literal: Dimension
        case expression: Expr[?] => expression.asInstanceOf[Expr[Integer]]: Dimension
        case _ => fail("NODAL-SHAPE-043-006", "node-array map requires static Integer dimensions")
      val checked = nodeIndexBound(dimension, analysis)
      val identity = dimension match
        case literal: Int => literal.toString
        case parameter: Param[?] =>
          val ref = declarationIds.get(parameter); s"declaration:${ref.module}:${ref.index}"
        case expression: KernelExpr[?] => expression.literal match
            case Some(literal) => literal.value.toLong.toString
            case None =>
              val ref = expressionIds.get(expression)
              val kind = if expression.operation.contains("generate_index") then "induction"
              else "expression"
              s"$kind:${ref.module}:${ref.index}"
        case _ => fail("NODAL-SHAPE-043-006", "node-array map lacks canonical static identity")
      bounds.update(identity, BigInt(checked.bounds.lower) -> BigInt(checked.bounds.upper))
      identity
    val chain = mutable.ArrayBuffer.empty[StructuralShapeCoordinates.Projection]
    def collect(reference: DeclarationRef | ExpressionRef): DeclarationRef | ExpressionRef =
      reference match
        case declaration: DeclarationRef => declaration
        case expression: ExpressionRef if instanceArrayRoot(expression).nonEmpty => expression
        case expression: ExpressionRef =>
          shapeIndices.get(expression) match
            case Some(index) =>
              val root = collect(index.input)
              chain += StructuralShapeCoordinates.Projection.Index(
                index.indices.map(token),
                index.resultDimensions.map(token)
              )
              root
            case None =>
              val view = shapeViews(expression)
              val root = collect(view.input)
              chain += StructuralShapeCoordinates.Projection.View(
                view.dimensions.map(token),
                view.slice.map(selection =>
                  KernelShapeSliceSnapshot(
                    selection.axis,
                    token(selection.lower),
                    token(selection.upperExclusive)
                  )
                )
              )
              root
    val root = collect(input)
    val dimensions = nodeArrayDimensions(root).getOrElse(
      fail("NODAL-SHAPE-043-006", "physical array root is unresolved")
    ).map(token)
    chain += StructuralShapeCoordinates.Projection.Index(indices.map(token), Vector.empty)
    try
      val projections = chain.toVector
      val region = StructuralShapeCoordinates.scalarRegion(dimensions, projections)
      val axes = StructuralShapeCoordinates.rootCoordinates(dimensions, projections)
        .map(StructuralShapeCoordinates.bounds(_, bounds.apply))
      (root, region, axes)
    catch
      case error: StructuralShapeCoordinates.InvalidCoordinate =>
        fail("NODAL-SHAPE-043-006", error.getMessage)

  private def pathOfReference(reference: DeclarationRef | ExpressionRef): String = reference match
    case declaration: DeclarationRef => declarationPath(declaration)
    case expression: ExpressionRef => instanceArrayRoot(expression).map: (operation, root) =>
        val base = instancePath(
          operation.owner,
          root.instanceOrdinal
        ) + ".array_port_" + declarationName(root.port)
        base + operation.generatedScope.map(region =>
          s".in(${generatedRegionPath(region)})"
        ).getOrElse("")
      .getOrElse(expressionPath(expression))

  def registerNodeSelection(
      value: Node[?],
      array: NodeArray[?],
      indices: Vector[Dimension],
      retained: Option[ExpressionRef] = None
  ): ExpressionRef =
    val module = currentModule
    val (input, reference) = nodeArrayInput(array)
    if value.discipline != Electrical then
      fail("NODAL-SHAPE-043-006", "node-array selection requires Electrical capability")
    if indices.size != array.shapeIntent.rank then
      fail("NODAL-SHAPE-043-002", "node-array selection requires the complete declared rank")
    val analysis =
      new StructuralBoundAnalysis(
        module.handle,
        "NODAL-SHAPE-043-006",
        "node-array index",
        signed64Literals = true
      )
    val indexBounds = indices.map(nodeIndexBound(_, analysis))
    val dimensions = nodeArrayDimensions(input)
    if dimensions.isEmpty then
      val occurrence = captureExpression(value, Vector(array) ++ indices).get
      operation(
        "deferred-node-array",
        DeferredNodeArrayRecord(value, occurrence, array, None, indices)
      )
      return occurrence
    val dimensionBounds = dimensions.get.map(analysis(_))
    indices.indices.foreach: axis =>
      val index = indexBounds(axis)
      val dimension = dimensionBounds(axis)
      val valid = directGeneratedInduction(indices(axis)) match
        case Some(region) =>
          val upper = analysis(region.upperExclusive.asInstanceOf[Dimension])
          region.maximumTripCount == 0 ||
          analysis.sameIdentity(
            region.upperExclusive.asInstanceOf[Dimension],
            dimensions.get(axis)
          ) ||
          upper.bounds.upper <= dimension.bounds.lower
        case None => index.bounds.lower >= 0L && index.bounds.upper < dimension.bounds.lower
      if !valid then
        fail("NODAL-SHAPE-043-002", "node-array index is not valid for every legal extent")
    val (_, key, axes) = nodeSelectionMap(input, indices)
    val scopeKey = reference -> activeGeneratedRegion
    val existing = nodeSelectionLookup.get(scopeKey)
    val canonicalReference = existing.flatMap(_.get(key))
    if canonicalReference.isEmpty then
      existing.foreach(_.valuesIterator.foreach: previous =>
        val record = shapeIndices(previous)
        val (_, _, otherAxes) = nodeSelectionMap(record.input, record.indices)
        if !axes.zip(otherAxes).exists((left, right) => left._2 < right._1 || right._2 < left._1)
        then
          fail(
            "NODAL-SHAPE-043-006",
            "distinct node-array selectors may alias for a legal parameter setting"
          ))
    val occurrence = retained.getOrElse(captureExpression(
      value,
      Vector(array)
    ).getOrElse(fail("NODAL-SHAPE-043-006", "node-array selection requires an active Module")))
    val endpoint = canonicalReference match
      case Some(previous) =>
        val record = shapeIndices(previous)
        shapeIndices.update(
          previous,
          record.copy(sourceUses = record.sourceUses :+ (occurrence -> indices))
        )
        previous
      case None =>
        shapeIndices.update(
          occurrence,
          ShapeIndexRecord(
            occurrence,
            input,
            indices,
            Vector.empty,
            Some(ConservativeSelectionKey(reference, key)),
            Vector(occurrence -> indices),
            activeGeneratedRegion
          )
        )
        nodeSelectionLookup.getOrElseUpdate(
          scopeKey,
          mutable.LinkedHashMap.empty
        ).update(key, occurrence)
        occurrence
    indexBounds.flatMap(_.parameters).distinct.foreach(markStructuralParameterEffect(_, "shape"))
    expressionIds.put(value, endpoint)
    endpoint

  private def conservativeEndpointPath(index: ShapeIndexRecord): String =
    val indices = index.indices.map:
      case literal: Int => s"literal:$literal"
      case parameter: Param[?] => s"parameter:${declarationPath(declarationIds.get(parameter))}"
      case expression: KernelExpr[?] => expression.literal match
          case Some(literal) => s"literal:${literal.value.toLong}"
          case None =>
            val tag =
              if expression.operation.contains("generate_index") then "induction" else "expression"
            s"$tag:${expressionPath(expressionIds.get(expression))}"
      case _ => fail("NODAL-SHAPE-043-006", "node-array selector lacks canonical identity")
    val suffix =
      index.generatedScope.map(region => s".in(${generatedRegionPath(region)})").getOrElse("")
    s"${pathOfReference(index.input)}.at(${indices.mkString(",")})$suffix"

  private def validateShapeType(
      dataType: DataType[?],
      owner: Long,
      cardinality: Boolean
  ): Unit =
    val descriptor = CandidateRuntime.typeDescriptor(dataType)
    if descriptor.kind == "Vec" then validateShapeDescriptor(descriptor, owner, cardinality)

  private def validateShapeDescriptor(
      descriptor: KernelTypeDescriptor,
      owner: Long,
      cardinality: Boolean
  ): Unit =
    val element = descriptor.arguments.headOption.collect:
      case candidate: DataType[?] => candidate
    val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
      case values: Seq[?] => values.toVector
      case _ => Vector.empty
    if element.isEmpty || dimensions.isEmpty then
      fail("NODAL-SHAPE-043-001", "Vec requires an element type and at least one dimension")

    val analysis = new StructuralBoundAnalysis(owner, "NODAL-SHAPE-043-001", "Vec dimension")
    val bounds = dimensions.map: dimension =>
      val bound = dimension match
        case value: Int => analysis(value)
        case value: Param[?] => analysis(value.asInstanceOf[Expr[Integer]])
        case value: KernelExpr[?] => analysis(value.asInstanceOf[Expr[Integer]])
        case _ =>
          fail(
            "NODAL-SHAPE-043-001",
            "Vec dimensions require a positive static Integer expression"
          )
      if bound.bounds.lower <= 0L then
        fail(
          "NODAL-SHAPE-043-001",
          "Vec dimension must be positive for every legal parameter setting"
        )
      bound
    if cardinality &&
      bounds.foldLeft(BigInt(1))((size, bound) => size * BigInt(bound.bounds.upper)) >
        BigInt(Long.MaxValue)
    then fail("NODAL-SHAPE-043-001", "Vec maximum element count exceeds signed 64-bit bounds")
    validateShapeType(element.get, owner, cardinality)
    bounds.flatMap(_.parameters).distinct.foreach: reference =>
      markStructuralParameterEffect(reference, "shape")
      markStructuralParameterEffect(reference, "rank")

  private def minimumShapeExtent(
      value: Any,
      analysis: StructuralBoundAnalysis
  ): Long = value match
    case dimension: Int =>
      val lower = analysis(dimension).bounds.lower
      if lower <= 0L then
        fail("NODAL-SHAPE-043-002", "shape extent has no positive finite minimum")
      lower
    case dimension: Param[?] =>
      val lower = analysis(dimension.asInstanceOf[Expr[Integer]]).bounds.lower
      if lower <= 0L then
        fail("NODAL-SHAPE-043-002", "shape extent has no positive finite minimum")
      lower
    case dimension: KernelExpr[?] =>
      val lower = analysis(dimension.asInstanceOf[Expr[Integer]]).bounds.lower
      if lower <= 0L then
        fail("NODAL-SHAPE-043-002", "shape extent has no positive finite minimum")
      lower
    case _ => fail("NODAL-SHAPE-043-002", "shape extent has no proven finite minimum")

  private def structuralShapeInput[A <: Data](
      input: Expr[Vec[A]],
      owner: Long,
      code: String
  ): (DeclarationRef | ExpressionRef, Option[DataType[? <: Data]], String) =
    val reference: DeclarationRef | ExpressionRef = input match
      case candidate: AnyRef =>
        Option(declarationIds.get(candidate))
          .map(reference => reference: DeclarationRef | ExpressionRef)
          .orElse(
            Option(expressionIds.get(candidate)).map(reference =>
              reference: DeclarationRef | ExpressionRef
            )
          )
          .getOrElse(fail(code, "shape input has no structural identity"))
    val (inputOwner, path) = reference match
      case declaration: DeclarationRef => declaration.module -> declarationPath(declaration)
      case expression: ExpressionRef => expression.module -> expressionPath(expression)
    if inputOwner != owner then
      fail(code, "shape input must be owned by the active Module", Some(path))
    val dataType = reference match
      case declaration: DeclarationRef =>
        val record = records(declaration.module).declarations(declaration.index)
        if record.kind == KernelSignalKind.Input || record.kind == KernelSignalKind.Output ||
          (record.kind == KernelSignalKind.Variable &&
            CandidateRuntime.proceduralShapeSource(input))
        then
          record.dataType
        else None
      case expression: ExpressionRef =>
        val lexical = CandidateRuntime.proceduralShapeSource(input)
        val dimensions = Option.when(lexical)(CandidateRuntime.expressionDataType(input).toVector
          .flatMap(dataType => CandidateRuntime.typeDescriptor(dataType).arguments.lift(1).toVector)
          .flatMap { case values: Seq[?] => values.toVector; case _ => Vector.empty })
          .orElse(shapeViews.get(expression).map(_.dimensions))
          .orElse(shapeIndices.get(expression).filter(_.resultDimensions.nonEmpty)
            .map(_.resultDimensions))
          .getOrElse(
            fail(code, "shape input is not a captured structural shaped result", Some(path))
          )
        val dataType = expressionValues.get(expression)
          .flatMap(CandidateRuntime.expressionDataType)
          .getOrElse(fail(code, "structural shape input has no captured type", Some(path)))
        val descriptor = CandidateRuntime.typeDescriptor(dataType)
        val actualDimensions = descriptor.arguments.lift(1).toVector.flatMap:
          case values: Seq[?] => values.toVector
          case _ => Vector.empty
        if descriptor.kind != "Vec" || actualDimensions != dimensions then
          fail(
            code,
            "structural shape input type disagrees with its captured dimensions",
            Some(path)
          )
        Some(dataType)
    (reference, dataType, path)

  def registerShapeIndex[A <: Data, B <: Data](
      expression: KernelExpr[B],
      input: Expr[Vec[A]],
      indices: Vector[Dimension]
  ): Unit =
    val module = currentModule
    val (inputReference, structuralInput, inputPath) =
      structuralShapeInput(input, module.handle, "NODAL-SHAPE-043-002")
    structuralInput match
      case None =>
        // Preserve the previously exposed inert expression surface until shaped
        // internal storage has an owned SSA carrier and lowering contract.
        val _ = captureExpression(expression)
      case Some(dataType) =>
        val descriptor = Option(CandidateRuntime.typeDescriptor(dataType))
          .filter(_.kind == "Vec")
          .getOrElse(
            fail(
              "NODAL-SHAPE-043-002",
              "indexed structural value must have Vec type",
              Some(inputPath)
            )
          )
        val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
          case values: Seq[?] => values.toVector
          case _ => Vector.empty
        if indices.isEmpty || indices.size > dimensions.size then
          fail(
            "NODAL-SHAPE-043-002",
            "index rank is empty or exceeds shaped rank",
            Some(inputPath)
          )
        val resultDimensions = dimensions.drop(indices.size)
        val resultDescriptor = CandidateRuntime.expressionDataType(expression)
          .map(CandidateRuntime.typeDescriptor)
          .getOrElse(
            fail(
              "NODAL-SHAPE-043-002",
              "shape index result has no data type",
              Some(inputPath)
            )
          )
        val element = descriptor.arguments.headOption.collect:
          case candidate: DataType[?] => CandidateRuntime.typeDescriptor(candidate)
        .getOrElse(
          fail(
            "NODAL-SHAPE-043-002",
            "indexed structural value has no element type",
            Some(inputPath)
          )
        )
        if resultDimensions.isEmpty then
          if resultDescriptor != element then
            fail(
              "NODAL-SHAPE-043-002",
              "full-rank index result must match the shaped element type",
              Some(inputPath)
            )
        else
          val actualDimensions = resultDescriptor.arguments.lift(1).toVector.flatMap:
            case values: Seq[?] => values.toVector
            case _ => Vector.empty
          val resultElement = resultDescriptor.arguments.headOption.collect:
            case candidate: DataType[?] => CandidateRuntime.typeDescriptor(candidate)
          if resultDescriptor.kind != "Vec" || resultElement != Some(element) ||
            actualDimensions != resultDimensions
          then
            fail(
              "NODAL-SHAPE-043-002",
              "prefix index result must retain the exact remaining shaped dimensions",
              Some(inputPath)
            )
        val analysis = new StructuralBoundAnalysis(
          module.handle,
          "NODAL-SHAPE-043-002",
          "shape index"
        )
        val extentAnalysis = new StructuralBoundAnalysis(
          module.handle,
          "NODAL-SHAPE-043-002",
          "shape extent"
        )
        val positions = indices.map(value => value -> analysis(value))
        positions.zip(dimensions).zipWithIndex.foreach:
          case (((_, position), dimension), axis) =>
            val minimum = minimumShapeExtent(dimension, extentAnalysis)
            if position.bounds.lower < 0L || position.bounds.upper >= minimum then
              fail(
                "NODAL-SHAPE-043-002",
                s"index is not in bounds for every legal shape at axis $axis",
                Some(inputPath)
              )
            position.parameters.foreach(markStructuralParameterEffect(_, "shape"))
        val reference = captureExpression(expression).getOrElse(
          fail("NODAL-SHAPE-043-002", "shape index requires an active Module")
        )
        if !CandidateRuntime.proceduralShapeSource(input) then
          shapeIndices.update(
            reference,
            ShapeIndexRecord(reference, inputReference, positions.map(_._1), resultDimensions)
          )

  private final case class ShapeViewFactor(
      literal: Option[Long],
      root: Option[String],
      maximum: Long,
      parameters: Vector[DeclarationRef]
  )

  private def shapeViewFactors(
      value: Any,
      owner: Long,
      path: String,
      analysis: StructuralBoundAnalysis
  ): Vector[ShapeViewFactor] = value match
    case literal: Int if literal > 0 =>
      Vector(ShapeViewFactor(Some(literal.toLong), None, literal.toLong, Vector.empty))
    case expression: KernelExpr[?] if expression.literal.exists(_.kind == "integer") =>
      val value = expression.literal.flatMap(_.value.toLongOption).filter(_ > 0).getOrElse(
        fail("NODAL-SHAPE-043-003", "fixed shape-view dimensions must be positive", Some(path))
      )
      Vector(ShapeViewFactor(Some(value), None, value, Vector.empty))
    case parameter: Param[?] =>
      val reference = Option(declarationIds.get(parameter)).getOrElse(
        fail(
          "NODAL-SHAPE-043-003",
          "symbolic shape-view dimension has no declaration identity",
          Some(path)
        )
      )
      if reference.module != owner then
        fail(
          "NODAL-SHAPE-043-003",
          "symbolic shape-view dimension escapes its owning Module",
          Some(declarationPath(reference))
        )
      val bound = analysis(parameter.asInstanceOf[Expr[Integer]])
      if bound.bounds.lower <= 0L then
        fail(
          "NODAL-SHAPE-043-003",
          "symbolic shape-view dimension requires a positive finite parameter range",
          Some(declarationPath(reference))
        )
      Vector(
        ShapeViewFactor(
          None,
          Some(s"parameter:${reference.module}:${reference.index}"),
          bound.bounds.upper,
          bound.parameters
        )
      )
    case expression: KernelExpr[?] =>
      val reference = Option(expressionIds.get(expression)).getOrElse(
        fail(
          "NODAL-SHAPE-043-003",
          "compound shape-view dimension has no captured construction identity",
          Some(path)
        )
      )
      if reference.module != owner then
        fail(
          "NODAL-SHAPE-043-003",
          "compound shape-view dimension escapes its owning Module",
          Some(expressionPath(reference))
        )
      val bound = analysis(expression.asInstanceOf[Expr[Integer]])
      if bound.bounds.lower <= 0L then
        fail(
          "NODAL-SHAPE-043-003",
          "compound shape-view dimension requires a positive finite Integer proof",
          Some(expressionPath(reference))
        )
      if expression.operation.contains("analog_mul") then
        if expression.operands.size != 2 then
          fail(
            "NODAL-SHAPE-043-003",
            "multiplicative shape-view dimension has invalid arity",
            Some(expressionPath(reference))
          )
        expression.operands.flatMap:
          case parameter: Param[?] =>
            shapeViewFactors(parameter, owner, path, analysis)
          case child: KernelExpr[?] =>
            shapeViewFactors(child, owner, path, analysis)
          case _ =>
            fail(
              "NODAL-SHAPE-043-003",
              "multiplicative shape-view dimension has a non-static operand",
              Some(expressionPath(reference))
            )
      else
        Vector(
          ShapeViewFactor(
            None,
            Some(s"expression:${reference.module}:${reference.index}"),
            bound.bounds.upper,
            bound.parameters
          )
        )
    case _ =>
      fail(
        "NODAL-SHAPE-043-003",
        "shape-view dimensions require positive literals or bounded static Integer expressions",
        Some(path)
      )

  private def shapeViewSignature(
      dimensions: Vector[Any],
      owner: Long,
      path: String,
      analysis: StructuralBoundAnalysis
  ): (BigInt, Vector[String], BigInt, Vector[DeclarationRef]) =
    val factors = dimensions.flatMap(shapeViewFactors(_, owner, path, analysis))
    if factors.isEmpty then
      fail(
        "NODAL-SHAPE-043-003",
        "fixed shape view requires at least one result dimension",
        Some(path)
      )
    val literalProduct = factors.flatMap(_.literal).foldLeft(BigInt(1))(_ * _)
    val roots = factors.flatMap(_.root).sorted
    val worstCase =
      factors.foldLeft(BigInt(1))((product, factor) => product * BigInt(factor.maximum))
    val parameters = factors.flatMap(_.parameters).distinct
    (literalProduct, roots, worstCase, parameters)

  def registerShapeView[A <: Data](
      expression: KernelExpr[Vec[A]],
      input: Expr[Vec[A]],
      dimensions: Vector[Dimension],
      slice: Option[ShapeSliceSelection] = None
  ): Unit =
    val module = currentModule
    val code = if slice.nonEmpty then "NODAL-SHAPE-043-004" else "NODAL-SHAPE-043-003"
    val (inputReference, structuralInput, path) =
      structuralShapeInput(input, module.handle, code)
    if structuralInput.isEmpty then
      if slice.nonEmpty then
        fail(code, "shape slice requires a structural module port or shaped result", Some(path))
      val _ = captureExpression(expression)
    structuralInput.foreach: inputType =>
      val descriptor = Option(CandidateRuntime.typeDescriptor(inputType))
        .filter(_.kind == "Vec")
        .getOrElse(
          fail(
            code,
            "shape-view input must have Vec type",
            Some(path)
          )
        )
      val resultDescriptor = CandidateRuntime.expressionDataType(expression)
        .map(CandidateRuntime.typeDescriptor)
      val expectedDescriptor = KernelTypeDescriptor(
        "Vec",
        Vector(descriptor.arguments.head, dimensions)
      )
      if !resultDescriptor.contains(expectedDescriptor) then
        fail(
          code,
          "shape-view result must retain the element type and exact requested dimensions",
          Some(path)
        )
      val sourceDimensions = descriptor.arguments.lift(1).toVector.flatMap:
        case values: Seq[?] => values.toVector
        case _ => Vector.empty
      val analysis = new StructuralBoundAnalysis(
        module.handle,
        code,
        "shape view"
      )
      val parameters = validateShapeViewDimensions(
        sourceDimensions,
        dimensions,
        slice,
        module.handle,
        path,
        analysis
      )
      parameters.distinct.foreach(markStructuralParameterEffect(_, "shape"))
      val reference = captureExpression(expression).getOrElse(
        fail(code, "shape view requires an active Module")
      )
      if !CandidateRuntime.proceduralShapeSource(input) then
        shapeViews.update(reference, ShapeViewRecord(reference, inputReference, dimensions, slice))

  private def validateShapeViewDimensions(
      source: Vector[Any],
      dimensions: Vector[Dimension],
      slice: Option[ShapeSliceSelection],
      owner: Long,
      path: String,
      analysis: StructuralBoundAnalysis
  ): Vector[DeclarationRef] =
    val code = if slice.nonEmpty then "NODAL-SHAPE-043-004" else "NODAL-SHAPE-043-003"
    slice match
      case Some(selection) =>
        validateShapeSlice(selection, source, dimensions, path, analysis)
      case None =>
        val (sourceLiteralProduct, sourceRoots, sourceWorstCase, sourceParameters) =
          shapeViewSignature(source, owner, path, analysis)
        val (targetLiteralProduct, targetRoots, targetWorstCase, targetParameters) =
          shapeViewSignature(dimensions, owner, path, analysis)
        if sourceLiteralProduct != targetLiteralProduct || sourceRoots != targetRoots ||
          sourceWorstCase > BigInt(Long.MaxValue) || targetWorstCase > BigInt(Long.MaxValue)
        then
          fail(
            code,
            "shape view requires equal literal products and canonical static-expression root multisets within signed 64-bit bounds",
            Some(path)
          )
        sourceParameters ++ targetParameters

  private def validateShapeSlice(
      selection: ShapeSliceSelection,
      source: Vector[Any],
      result: Vector[Dimension],
      path: String,
      analysis: StructuralBoundAnalysis
  ): Vector[DeclarationRef] =
    def invalid(message: String): Nothing = fail("NODAL-SHAPE-043-004", message, Some(path))
    def dimension(value: Any): Dimension = value match
      case literal: Int => literal
      case expression: Expr[?] => expression.asInstanceOf[Expr[Integer]]
      case _ => invalid("shape slice dimension is not Integer data")
    def identity(value: Dimension): Either[Long, DeclarationRef | ExpressionRef] = value match
      case literal: Int => Left(literal.toLong)
      case parameter: Param[?] => Right(Option(declarationIds.get(parameter)).getOrElse(
          invalid("shape slice parameter has no captured identity")
        ))
      case expression: KernelExpr[?] => expression.literal match
          case Some(literal) => Left(literal.value.toLongOption.getOrElse(
              invalid("shape slice literal exceeds signed 64-bit bounds")
            ))
          case None => Right(Option(expressionIds.get(expression)).getOrElse(
              invalid("shape slice expression has no captured identity")
            ))
      case _ => invalid("shape slice value has no canonical static identity")
    if selection.axis < 0 || selection.axis >= source.size || result.size != source.size then
      invalid("shape slice axis or result rank does not match its input")
    val sourceDimensions = source.map(dimension)
    sourceDimensions.indices.filter(_ != selection.axis).foreach: axis =>
      if identity(sourceDimensions(axis)) != identity(result(axis)) then
        invalid("shape slice must retain every unselected dimension")
    val lower = analysis(selection.lower)
    val upper = analysis(selection.upperExclusive)
    val lengthDimension = result(selection.axis)
    val length = analysis(lengthDimension)
    val lowerIdentity = identity(selection.lower)
    val upperIdentity = identity(selection.upperExclusive)
    val lengthIdentity = identity(lengthDimension)
    val canonicalLength = (lowerIdentity, upperIdentity) match
      case (Left(lo), Left(hi)) =>
        val difference = BigInt(hi) - BigInt(lo)
        difference.isValidLong && lengthIdentity == Left(difference.toLong)
      case (Left(0L), _) => lengthIdentity == upperIdentity
      case _ => lengthDimension match
          case expression: KernelExpr[?]
              if expression.operation.contains("analog_sub") &&
                expression.operands.size == 2 =>
            identity(dimension(expression.operands(0))) == upperIdentity &&
            identity(dimension(expression.operands(1))) == lowerIdentity
          case _ => false
    if !canonicalLength || lower.bounds.lower < 0L || length.bounds.lower <= 0L then
      invalid(
        "shape slice requires nonnegative lower and canonical strictly positive upper-minus-lower length"
      )
    val sourceBounds = sourceDimensions.map(analysis(_))
    val resultBounds = result.map(analysis(_))
    if upperIdentity != identity(sourceDimensions(selection.axis)) &&
      upper.bounds.upper > sourceBounds(selection.axis).bounds.lower
    then
      invalid("shape slice upper bound is not valid for every legal input extent")
    Vector(sourceBounds, resultBounds).foreach: bounds =>
      if bounds.exists(_.bounds.lower <= 0L) ||
        bounds.foldLeft(BigInt(1))((count, bound) => count * BigInt(bound.bounds.upper)) >
          BigInt(Long.MaxValue)
      then
        invalid("shape slice requires positive dimensions and signed 64-bit maximum element counts")
    (sourceBounds ++ resultBounds ++ Vector(lower, upper, length)).flatMap(_.parameters)

  private def captureExpression(
      value: AnyRef,
      structuralOperands: Vector[Any] = Vector.empty
  ): Option[ExpressionRef] =
    moduleStack.lastOption.map: module =>
      validateGeneratedDependencies(value match
        case expression: KernelExpr[?] => expression.operands
        case _ => structuralOperands)
      val reference = ExpressionRef(module.handle, module.expressionCount)
      module.expressionCount += 1
      expressionIds.put(value, reference)
      val operands = value match
        case expression: KernelExpr[?] =>
          expressionValues.update(reference, expression)
          analogStack.lastOption.foreach(_.expressions += reference)
          expression.operands
        case _ => structuralOperands
      semanticOrigin.captureExpression(module.handle, reference.index, value, operands)
      reference

  def registerExpression(value: AnyRef): Unit = value match
    case expression: KernelExpr[?]
        if expression.operation.exists(_.startsWith(AnalogContextContract.Prefix)) =>
      val _ = AnalogContextContract.validate(expression)
      val _ = captureExpression(value)
    case expression: KernelExpr[?] if CandidateRuntime.proceduralShapeSource(expression) =>
      expression.operation match
        case Some("analog_array_fill") =>
          val dataType = CandidateRuntime.expressionDataType(expression).getOrElse(
            fail("NODAL-SHAPE-043-007", "array fill requires an exact Real Vec type")
          )
          val descriptor = CandidateRuntime.typeDescriptor(dataType)
          if descriptor.kind != "Vec" || descriptor.arguments.headOption != Some(Real) ||
            expression.operands.size != 1
          then fail("NODAL-SHAPE-043-007", "array fill requires scalar Real elements")
          validateShapeType(dataType, currentModule.handle, cardinality = true)
          val _ = captureExpression(value)
        case Some("shape_index") =>
          registerShapeIndex(
            expression.asInstanceOf[KernelExpr[Data]],
            expression.operands.head.asInstanceOf[Expr[Vec[Data]]],
            expression.operands.tail.map(_.asInstanceOf[Dimension])
          )
        case Some("shape_view" | "shape_slice") =>
          val dimensions = CandidateRuntime.expressionDataType(expression).toVector
            .flatMap(dataType =>
              CandidateRuntime.typeDescriptor(dataType).arguments.lift(1).toVector
            )
            .flatMap {
              case values: Seq[?] => values.toVector.map(_.asInstanceOf[Dimension]);
              case _ => Vector.empty
            }
          if expression.operation.contains("shape_slice") then
            fail("NODAL-SHAPE-043-007", "a slice must be captured by the typed slice constructor")
          registerShapeView(
            expression.asInstanceOf[KernelExpr[Vec[Data]]],
            expression.operands.head.asInstanceOf[Expr[Vec[Data]]],
            dimensions
          )
        case _ => val _ = captureExpression(value)
    case _ => val _ = captureExpression(value)

  def proceduralShapeDimension(value: Any): String =
    val owner = value match
      case reference: AnyRef => Option(declarationIds.get(reference)).map(_.module)
          .orElse(Option(
            expressionIds.get(reference)
          ).map(_.module)).getOrElse(currentModule.handle)
      case _ => currentModule.handle
    renderShapeDimension(value, owner)

  def registerContinuousOperator(
      value: KernelExpr[Real],
      operation: String,
      input: Expr[Real],
      initialValue: Option[Expr[Real]]
  ): Unit =
    val module = currentModule
    val context = analogSemanticContext match
      case Some(candidate) if candidate.module != module.handle =>
        fail(
          "NODAL-ANALOG-035-001",
          "continuous-time operator context belongs to another Module",
          Some(provisionalModulePath(module.handle))
        )
      case Some(candidate) =>
        candidate.kind match
          case AnalogEquationRuntime.RegionKind.Equation => "equation"
          case AnalogEquationRuntime.RegionKind.Contribution => "contribution"
          case AnalogEquationRuntime.RegionKind.InitialEquation =>
            fail(
              "NODAL-ANALOG-035-001",
              "ddt and idt are not legal in initial-equation regions",
              Some(provisionalModulePath(module.handle))
            )
          case AnalogEquationRuntime.RegionKind.Procedural =>
            fail(
              "NODAL-ANALOG-035-001",
              "ddt and idt are not legal in analog procedural regions",
              Some(provisionalModulePath(module.handle))
            )
      case None if analogStack.lastOption.exists(_.module == module.handle) =>
        "legacy-analog"
      case None =>
        fail(
          "NODAL-ANALOG-035-001",
          "ddt and idt require an equation, contribution, or legacy analog region",
          Some(provisionalModulePath(module.handle))
        )

    if operation != "analog_ddt" && operation != "analog_idt" then
      fail(
        "NODAL-ANALOG-035-002",
        s"unsupported continuous-time operator '$operation'",
        Some(provisionalModulePath(module.handle))
      )
    if operation == "analog_ddt" && initialValue.nonEmpty then
      fail(
        "NODAL-ANALOG-035-002",
        "ddt does not own an initial condition",
        Some(provisionalModulePath(module.handle))
      )

    val inputDimension = inferAnalogDimension(input)
    if inputDimension.isUnknown then
      fail(
        "NODAL-ANALOG-035-003",
        s"$operation input requires a known real physical dimension",
        pathOf(input)
      )
    val resultDimension =
      if operation == "analog_ddt" then inputDimension.divide(AnalogDimension.Time)
      else inputDimension.multiply(AnalogDimension.Time)
    if resultDimension.isUnknown then
      fail(
        "NODAL-ANALOG-035-003",
        s"$operation result dimension could not be canonicalized",
        pathOf(input)
      )

    initialValue.foreach: initial =>
      val initialDimension = inferAnalogDimension(initial)
      val compatible =
        initialDimension.isZero ||
          (!initialDimension.isUnknown && initialDimension.powers == resultDimension.powers)
      if !compatible then
        fail(
          "NODAL-ANALOG-035-004",
          s"idt initial condition dimension ${initialDimension.signature} does not match result dimension ${resultDimension.signature}",
          pathOf(initial).orElse(pathOf(input))
        )

    val reference = captureExpression(value).getOrElse(
      fail(
        "NODAL-ANALOG-035-002",
        "continuous-time operator has no active construction owner"
      )
    )
    continuousOperators += ContinuousOperatorRecord(
      reference,
      operation,
      input,
      initialValue,
      context,
      inputDimension,
      resultDimension
    )

  def withWaveformForbidden[A](body: => A): A =
    waveformForbiddenDepth += 1
    try body
    finally waveformForbiddenDepth -= 1

  def registerTransferOperator(
      value: KernelExpr[Real],
      kind: String,
      numeratorSize: Int,
      denominatorSize: Int,
      inputs: Vector[Expr[Real]]
  ): Unit =
    val module = currentModule
    if analogSemanticContext.nonEmpty ||
      !analogStack.lastOption.exists(_.module == module.handle) || waveformForbiddenDepth != 0
    then
      AnalogTransferContract.fail(1, "transfer state requires an unconditional analog region")
    def checkOwner(input: Any): Unit = input match
      case reference: AnyRef =>
        if Option(expressionIds.get(reference)).exists(_.module != module.handle) ||
          Option(declarationIds.get(reference)).exists(_.module != module.handle)
        then AnalogTransferContract.fail(2, "transfer operands must belong to their Module")
        input match
          case expression: KernelExpr[?] => expression.operands.foreach(checkOwner)
          case _ => ()
      case _ => ()
    inputs.foreach(checkOwner)
    val dimensions = inputs.map(inferAnalogDimension).map(_.signature)
    val result = AnalogTransferContract.validate(
      kind,
      numeratorSize,
      denominatorSize,
      inputs,
      dimensions,
      inputs.map(waveformConstant),
      inputs.map(waveformStatic)
    )
    val reference = captureExpression(value).getOrElse(
      AnalogTransferContract.fail(2, "transfer state has no construction owner")
    )
    transferOperators += ((reference, kind, numeratorSize, denominatorSize, inputs, result))

  def registerNoiseOperator(
      value: KernelExpr[Real],
      kind: String,
      label: String,
      inputs: Vector[Expr[Real]]
  ): Unit =
    val module = currentModule
    if analogSemanticContext.nonEmpty ||
      !analogStack.lastOption.exists(_.module == module.handle) || waveformForbiddenDepth != 0
    then
      AnalogNoiseContract.fail(
        1,
        "noise sources require an unconditional analog region; procedural and equation contexts are not supported"
      )
    def checkOwner(input: Any): Unit = input match
      case reference: AnyRef =>
        if Option(expressionIds.get(reference)).exists(_.module != module.handle) ||
          Option(declarationIds.get(reference)).exists(_.module != module.handle)
        then
          AnalogNoiseContract.fail(2, "noise operands must belong to the source Module")
        input match
          case expression: KernelExpr[?] =>
            if expression.operation.exists(_.startsWith(AnalogNoiseContract.Prefix)) then
              AnalogNoiseContract.fail(
                6,
                "a noise source cannot modulate another source in this profile"
              )
            expression.operands.foreach(checkOwner)
          case _ => ()
      case _ => ()
    inputs.foreach(checkOwner)
    val dimensions = inputs.map(inferAnalogDimension).map(_.signature)
    val result =
      AnalogNoiseContract.validate(kind, inputs, dimensions, inputs.map(waveformConstant))
    val reference = captureExpression(value).getOrElse(
      AnalogNoiseContract.fail(2, "noise source has no construction owner")
    )
    noiseOperators += ((reference, kind, label, inputs, result))

  def registerWaveformOperator(
      value: KernelExpr[Real],
      operation: String,
      inputs: Vector[Expr[Real]]
  ): Unit =
    val module = currentModule
    val context = analogSemanticContext match
      case Some(candidate)
          if operation == "analog_abstime" && candidate.module == module.handle &&
            candidate.kind == AnalogEquationRuntime.RegionKind.Procedural &&
            AnalogProceduralConstruction.procedureActive => "procedure"
      case Some(candidate)
          if candidate.module == module.handle &&
            candidate.kind == AnalogEquationRuntime.RegionKind.Equation => "equation"
      case Some(candidate)
          if candidate.module == module.handle &&
            candidate.kind == AnalogEquationRuntime.RegionKind.Contribution => "contribution"
      case None if analogStack.lastOption.exists(_.module == module.handle) => "legacy-analog"
      case _ => "illegal"
    if context == "illegal" || waveformForbiddenDepth != 0 then
      fail(
        "NODAL-ANALOG-036-001",
        "time/waveform operators require an unconditional continuous region"
      )
    val allowed = operation match
      case "analog_transition" => inputs.size >= 1 && inputs.size <= 5
      case "analog_slew" => inputs.size >= 1 && inputs.size <= 3
      case "analog_absdelay" => inputs.size == 2 || inputs.size == 3
      case "analog_abstime" => inputs.isEmpty
      case "analog_bound_step" => inputs.size == 1
      case _ => false
    if !allowed then fail("NODAL-ANALOG-036-002", "invalid time/waveform operator or arity")
    def checkOwner(input: Any): Unit = input match
      case reference: AnyRef =>
        val foreignExpression =
          Option(expressionIds.get(reference)).exists(_.module != module.handle)
        val foreignDeclaration =
          Option(declarationIds.get(reference)).exists(_.module != module.handle)
        if foreignExpression || foreignDeclaration then
          fail("NODAL-ANALOG-036-002", "waveform operands must belong to the operator Module")
        input match
          case expression: KernelExpr[?] => expression.operands.foreach(checkOwner)
          case _ => ()
      case _ => ()
    inputs.foreach(checkOwner)
    val dimensions = inputs.map(inferAnalogDimension)
    if dimensions.exists(_.isUnknown) then
      fail("NODAL-ANALOG-036-003", "waveform operand requires a known real dimension")
    val effect = operation == "analog_bound_step"
    val resultDimension =
      if effect then "none"
      else if operation == "analog_abstime" then "time"
      else dimensions.head.signature
    val inputContinuity =
      if operation == "analog_abstime" || effect then "none"
      else waveformContinuity(inputs.head)
    if operation == "analog_transition" &&
      !Set("constant", "piecewise-constant").contains(inputContinuity)
    then
      fail(
        "NODAL-ANALOG-036-005",
        "transition requires proven piecewise-constant input; use slew for continuous input"
      )
    inputs.zipWithIndex.foreach: (input, index) =>
      val timing = effect || (operation != "analog_slew" && index > 0)
      val rate = operation == "analog_slew" && index > 0
      val expected =
        if timing then AnalogDimension.Time
        else if rate then dimensions.head.divide(AnalogDimension.Time)
        else dimensions(index)
      // A dimensionless literal zero is accepted only for timing slots; a zero
      // carrying another physical dimension cannot disguise a unit mismatch.
      val zeroTime = timing && dimensions(index).powers.isEmpty &&
        waveformConstant(input).contains(0.0)
      if !zeroTime && dimensions(index).powers != expected.powers then
        fail(
          "NODAL-ANALOG-036-003",
          "time arguments require seconds and slew rates require input/time"
        )
      waveformConstant(input).foreach: number =>
        val badRange =
          if rate then (if index == 1 then number <= 0.0 else number >= 0.0)
          else if operation == "analog_absdelay" && index > 0 then number <= 0.0
          else timing && number < 0.0
        if !number.isFinite || badRange then
          fail("NODAL-ANALOG-036-004", "non-finite value or invalid waveform timing/rate range")
    if operation == "analog_absdelay" && inputs.size == 3 && !waveformStatic(inputs(2)) then
      fail("NODAL-ANALOG-036-007", "maximum delay must be a constant expression")
    val outputContinuity =
      if effect then "none"
      else waveformContinuity(value)
    val reference = captureExpression(value).getOrElse(
      fail("NODAL-ANALOG-036-002", "waveform operator has no construction owner")
    )
    waveformOperators +=
      ((
        reference,
        operation,
        inputs,
        context,
        dimensions.map(_.signature),
        resultDimension,
        inputContinuity,
        outputContinuity
      ))

  private def attachInstance(
      instance: Instance[? <: Module],
      childModule: Module,
      captured: Boolean
  ): Unit =
    if !moduleIds.containsKey(childModule) then
      fail(
        "NODAL-HIERARCHY-016",
        "child Module was constructed outside this construction transaction"
      )
    val childHandle = moduleHandle(childModule)
    if moduleStack.lastOption.forall(_.handle != childHandle) then
      fail(
        "NODAL-HIERARCHY-017",
        "instance(new Child) must immediately follow child construction"
      )
    val child = records(childHandle)
    moduleStack.remove(moduleStack.size - 1)
    val parent = currentModule
    if child.parentAtConstruction != Some(parent.handle) then
      fail("NODAL-HIERARCHY-018", "child construction owner does not match Instance owner")
    if child.attached then fail("NODAL-HIERARCHY-019", "child Module was attached twice")
    val record = new InstanceRecord(
      parent.instances.size,
      childHandle,
      domainStack.lastOption,
      captured,
      activeGeneratedRegion
    )
    parent.instances += record
    child.attached = true
    instanceIds.put(instance, record)
    instanceObjects.put(childModule, instance)
    semanticOrigin.captureInstance(
      parent.handle,
      record.ordinal,
      childHandle,
      instance,
      childModule
    )

  def attachInstance(instance: Instance[? <: Module], childModule: Module): Unit =
    attachInstance(instance, childModule, captured = false)

  def instance[M <: Module](module: M, captured: Boolean): (Instance[M], Boolean) =
    Option(instanceObjects.get(module)) match
      case Some(existing) =>
        val record = instanceRecord(existing)
        val parent = currentModule
        if !parent.instances.exists(_ eq record) then
          fail(
            "NODAL-PARAMETER-BINDING-020",
            "only the owning parent Module can retrieve a captured child Instance"
          )
        requireInstanceScope(record)
        if !record.captured || captured then
          fail("NODAL-HIERARCHY-017", "child Module was attached twice")
        (existing.asInstanceOf[Instance[M]], false)
      case None =>
        val created = new Instance(module)
        attachInstance(created, module, captured)
        (created, true)

  private def requireInstanceScope(record: InstanceRecord): Unit =
    if !currentModule.instances.exists(
        _ eq record
      ) || record.generatedScope != activeGeneratedRegion
    then
      fail("NODAL-HIERARCHY-044", "Instance handle escaped its exact lexical Module/Generate scope")

  def validateInstanceSelection(instance: AnyRef): Unit =
    requireInstanceScope(instanceRecord(instance))

  private def instanceRecord(instance: AnyRef): InstanceRecord =
    Option(instanceIds.get(instance)).getOrElse(
      fail("NODAL-BINDING-016", "binding targets an unknown Instance")
    )

  def bindDefault(instance: AnyRef, domain: ClockDomain): Unit =
    val record = instanceRecord(instance)
    requireInstanceScope(record)
    record.defaultBinding = Some(domain)

  def bindNamed(instance: AnyRef, requirement: ClockDomain, domain: ClockDomain): Unit =
    val record = instanceRecord(instance)
    requireInstanceScope(record)
    val requirementReference = domainRef(requirement)
    if requirementReference.module != record.child then
      fail("NODAL-BINDING-019", "selector domain does not belong to the child Instance")
    record.namedBindings += requirement -> domain

  def connectNodeArrays(left: NodeArray[?], right: NodeArray[?]): Unit =
    if analogStack.nonEmpty || analogSemanticContext.nonEmpty then
      fail("NODAL-SHAPE-043-007", "conservative array relations require module scope")
    val first = nodeArrayInput(nodeArrayUse(left))._1
    val second = nodeArrayInput(nodeArrayUse(right))._1
    if left.discipline != Electrical || right.discipline != Electrical then
      fail("NODAL-SHAPE-043-007", "array relation requires matching Electrical capabilities")
    operation(
      "node-array-connect",
      ConservativeArrayBindingRecord(
        first,
        second,
        semanticOrigin.captureSemanticSource()
      )
    )

  def connectNodes(left: AnyRef, right: AnyRef): Unit =
    val parent = currentModule
    val portKinds = Set(
      KernelSignalKind.AnalogInput,
      KernelSignalKind.AnalogOutput,
      KernelSignalKind.AnalogInout
    )

    def endpoint(value: AnyRef): Node[?] =
      val selected = value match
        case node: Node[?] => node.endpointReference.map: reference =>
            shapeIndices.get(reference).flatMap(_.conservative).map(_.array)
              .orElse(deferredNodeArray(reference).map(value => nodeArrayInput(value._2.input)._2))
              .getOrElse(fail("NODAL-SHAPE-043-006", "selected node has no captured array root"))
        case _ => None
      val physicalRoot = selected.orElse(Option(declarationIds.get(value))).getOrElse(
        fail("NODAL-HIERARCHY-038", "connection endpoint is outside this construction transaction")
      )
      val reference = physicalRoot match
        case declaration: DeclarationRef => declaration
        case expression: ExpressionRef =>
          val (operation, root) = instanceArrayRoot(expression).getOrElse(
            fail("NODAL-SHAPE-043-006", "selected child node lost its qualified root")
          )
          requireInstanceScope(records(operation.owner).instances(root.instanceOrdinal))
          root.port
      val owner = records(reference.module)
      val declaration = owner.declarations(reference.index)
      val node = value match
        case candidate: Node[?] => candidate
        case _ =>
          fail("NODAL-HIERARCHY-039", "conservative connection requires a node or analog port")
      val local = reference.module == parent.handle &&
        (portKinds.contains(declaration.kind) || declaration.kind == KernelSignalKind.AnalogNode ||
          (selected.nonEmpty && declaration.kind == KernelSignalKind.AnalogNodeArray))
      // attachInstance alone marks a child attached, after recording it in its exact parent.
      val childPort = owner.attached && owner.parentAtConstruction.contains(parent.handle) &&
        portKinds.contains(declaration.kind)
      if childPort then
        parent.instances.find(_.child == reference.module).foreach(requireInstanceScope)
      if local then
        requireGeneratedDeclarationScope(reference)
        val selectionScope = value match
          case selectedNode: Node[?] =>
            selectedNode.endpointReference.flatMap(shapeIndices.get).flatMap(_.generatedScope)
          case _ => None
        if selectionScope.exists(region => !generationStack.exists(_ eq region)) then
          fail("NODAL-HIERARCHY-044", "selected endpoint escaped its generated lexical scope")
      if !local && !childPort then
        fail(
          "NODAL-HIERARCHY-040",
          "connection endpoint must be local or a declared port of an attached immediate child",
          Some(declarationPath(reference))
        )
      node

    def natures(discipline: Discipline): (Nature, Nature) = discipline match
      case Electrical => Voltage -> Current
      case named: NamedDiscipline => named.potential -> named.flow

    val leftNode = endpoint(left)
    val rightNode = endpoint(right)
    val (leftPotential, leftFlow) = natures(leftNode.discipline)
    val (rightPotential, rightFlow) = natures(rightNode.discipline)
    if !(leftPotential eq rightPotential) || !(leftFlow eq rightFlow) ||
      (leftPotential eq leftFlow)
    then
      fail(
        "NODAL-HIERARCHY-041",
        "conservative connection requires compatible potential and flow nature declarations"
      )
    val dimensionsKnown = Vector(true, false).forall: potential =>
      val leftDimension = disciplineDimension(leftNode.discipline, potential)
      val rightDimension = disciplineDimension(rightNode.discipline, potential)
      !leftDimension.isUnknown && !rightDimension.isUnknown &&
      leftDimension.powers == rightDimension.powers
    if !dimensionsKnown then
      fail("NODAL-HIERARCHY-042", "conservative connection dimensions could not be proven")
    operation("node-connect", left, right)

  private def overrideTypeSignature(value: Any, owner: Long): Option[String] =
    value match
      case expression: Expr[?] =>
        CandidateRuntime.expressionDataType(expression).map(renderType(_, owner))
      case _ => None

  private def overrideDimensionSignature(value: Any): Option[String] =
    val dimension = inferAnalogDimension(value)
    if dimension.isUnknown then None else Some(dimension.signature)

  private def overrideEvidence(
      parent: ModuleRecord,
      instance: InstanceRecord,
      target: DeclarationRecord,
      value: Any
  ): AnalogHierarchyOverridePolicy.Evidence =
    val referencedDeclarationOwners = mutable.ArrayBuffer.empty[Long]
    val expressionOwners = mutable.ArrayBuffer.empty[Long]
    val operations = mutable.ArrayBuffer.empty[String]
    val visited = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    var dynamicDependency = false

    def declaration(reference: DeclarationRef): DeclarationRecord =
      records(reference.module).declarations(reference.index)

    def visit(candidate: Any): Unit = candidate match
      case _: String | _: Int | _: Long | _: Double | _: Float | _: Boolean | _: BigInt => ()
      case parameter: Param[?] =>
        Option(declarationIds.get(parameter)) match
          case Some(reference) =>
            referencedDeclarationOwners += reference.module
            if declaration(reference).kind != KernelSignalKind.Parameter then
              dynamicDependency = true
          case None => dynamicDependency = true
      case expression: KernelExpr[?] =>
        if Option(visited.put(expression, java.lang.Boolean.TRUE)).isEmpty then
          Option(expressionIds.get(expression)) match
            case Some(reference) => expressionOwners += reference.module
            case None if expression.literal.nonEmpty => ()
            case None => dynamicDependency = true
          if expression.literal.isEmpty then
            expression.operation match
              case Some(operation) => operations += operation
              case None => operations += "<unknown>"
          expression.operands.foreach(visit)
      case reference: AnyRef =>
        Option(declarationIds.get(reference)) match
          case Some(declarationReference) =>
            referencedDeclarationOwners += declarationReference.module
            if declaration(declarationReference).kind != KernelSignalKind.Parameter then
              dynamicDependency = true
          case None =>
            Option(expressionIds.get(reference)) match
              case Some(expressionReference) => expressionOwners += expressionReference.module
              case None => dynamicDependency = true
      case _ => ()

    visit(value)

    val targetType = target.dataType.map(renderType(_, instance.child))
    val requiresDimension =
      target.dataType.exists: dataType =>
        Set("Real", "Bool").contains(CandidateRuntime.typeDescriptor(dataType).kind)

    AnalogHierarchyOverridePolicy.Evidence(
      parentOwner = parent.handle,
      duplicate = instance.parameterOverrides.exists:
        case (existing: AnyRef, _) =>
          Option(declarationIds.get(existing)).contains(target.reference)
        case _ => false,
      referencedDeclarationOwners = referencedDeclarationOwners.toVector,
      expressionOwners = expressionOwners.toVector,
      dynamicDependency = dynamicDependency,
      operations = operations.toVector,
      targetTypeSignature = targetType,
      valueTypeSignature = overrideTypeSignature(value, parent.handle),
      requiresDimension = requiresDimension,
      targetDimensionSignature =
        if requiresDimension then overrideDimensionSignature(target.value) else None,
      valueDimensionSignature =
        if requiresDimension then overrideDimensionSignature(value) else None
    )

  def overrideParameter(instance: AnyRef, parameter: Any, value: Any): Unit =
    val record = instanceRecord(instance)
    val parent = currentModule
    if !parent.instances.exists(_ eq record) then
      fail(
        "NODAL-PARAMETER-BINDING-020",
        "instance parameter override must be recorded by the owning parent Module"
      )
    requireInstanceScope(record)
    val targetReference = parameter match
      case reference: AnyRef =>
        Option(declarationIds.get(reference)).getOrElse(
          fail(
            "NODAL-PARAMETER-BINDING-016",
            "instance parameter override targets an unknown parameter",
            Some(instancePath(parent.handle, record.ordinal))
          )
        )
      case _ =>
        fail(
          "NODAL-PARAMETER-BINDING-016",
          "instance parameter override is not a declaration",
          Some(instancePath(parent.handle, record.ordinal))
        )
    if targetReference.module != record.child then
      fail(
        "NODAL-PARAMETER-BINDING-017",
        "instance parameter override targets another Module",
        Some(instancePath(parent.handle, record.ordinal))
      )
    val target = records(record.child).declarations(targetReference.index)
    if target.kind != KernelSignalKind.Parameter then
      fail(
        "NODAL-PARAMETER-BINDING-018",
        "instance parameter override target is not a child parameter",
        Some(instancePath(parent.handle, record.ordinal))
      )

    AnalogHierarchyOverridePolicy.validate(
      overrideEvidence(parent, record, target, value)
    ) match
      case Left(rejection) =>
        fail(
          rejection.code,
          rejection.message,
          Some(instancePath(parent.handle, record.ordinal))
        )
      case Right(()) =>
        record.parameterOverrides += parameter -> value

  def commitConstructorAllocation(
      module: Module,
      parameters: Vector[ConstructorCapturedParameter]
  ): Boolean =
    val handle = moduleHandle(module)
    val record = records(handle)
    record.parentAtConstruction match
      case Some(_) =>
        val (instance, attached) = this.instance(module, captured = true)
        parameters.foreach: parameter =>
          parameter.actual.foreach(actual =>
            overrideParameter(instance, parameter.carrier, actual)
          )
        attached
      case None =>
        if moduleStack.headOption.forall(_.handle != handle) then
          fail(
            "NODAL-CONSTRUCTOR-LIFECYCLE-020",
            "captured root allocation is not the active construction root"
          )
        parameters.foreach: parameter =>
          parameter.actual.foreach: actual =>
            val targetReference = Option(declarationIds.get(parameter.carrier)).getOrElse(
              fail(
                "NODAL-PARAMETER-BINDING-016",
                "root constructor binding targets an unknown parameter"
              )
            )
            val target = record.declarations(targetReference.index)
            val duplicate = rootParameterBindings.exists:
              case (existing: AnyRef, _) =>
                Option(declarationIds.get(existing)).contains(targetReference)
              case _ => false
            val synthetic = new InstanceRecord(-1, handle, None, captured = true)
            if duplicate then synthetic.parameterOverrides += parameter.carrier -> actual
            AnalogHierarchyOverridePolicy.validate(
              overrideEvidence(record, synthetic, target, actual)
            ) match
              case Left(rejection) =>
                fail(rejection.code, rejection.message, Some(record.className))
              case Right(()) =>
                rootParameterBindings += parameter.carrier -> actual
        false

  def withDomain[A](domain: ClockDomain)(body: => A): A =
    if !domainIds.containsKey(domain) then
      fail("NODAL-DOMAIN-018", "lexical ClockDomain is outside this transaction")
    domainRef(domain)
    domainStack += domain
    try body
    finally
      val removed = domainStack.remove(domainStack.size - 1)
      if removed ne domain then fail("NODAL-DOMAIN-019", "lexical domain stack is corrupt")

  def currentModulePath: String = provisionalModulePath(currentModule.handle)

  private final case class StructuralBound(
      value: Any,
      bounds: IterationDomain.Bounds,
      parameters: Vector[DeclarationRef],
      identity: Option[Int] = None
  )

  private def structuralRangeAttribute(
      declaration: DeclarationRecord,
      name: String
  ): Option[Int] =
    declaration.attributes.collectFirst:
      case (key, value: Int) if key == name => value

  // This analysis traverses captured expressions, not a second expression graph. Its cache lives
  // only for one generated domain, and arithmetic stays with the shared IterationDomain owner.
  private final class StructuralBoundAnalysis(
      owner: Long,
      diagnosticCode: String = "NODAL-ITERATION-043-001",
      subject: String = "hdlRange",
      signed64Literals: Boolean = false
  ):
    private val cache = mutable.HashMap.empty[ExpressionRef, StructuralBound]
    private val active = mutable.HashSet.empty[ExpressionRef]
    private val identityIds = mutable.HashMap.empty[(Int, String, Vector[Int]), Int]
    private val identityKeys = mutable.ArrayBuffer.empty[(Int, String, Vector[Int])]

    private def intern(key: (Int, String, Vector[Int])): Int = identityIds.getOrElseUpdate(
      key, {
        val id = identityKeys.size
        identityKeys += key
        id
      }
    )

    private def parameterFact(parameter: Param[?]): StructuralBound =
      val fact = parameterBound(parameter, owner, diagnosticCode, subject)
      val reference = fact.parameters.head
      fact.copy(identity =
        Some(intern((1, s"${reference.module}:${reference.index}", Vector.empty)))
      )

    def sameIdentity(left: Dimension, right: Dimension): Boolean =
      apply(left).identity == apply(right).identity

    private def invalid(message: String, path: Option[String] = None): Nothing =
      fail(diagnosticCode, message.replace("hdlRange", subject), path)

    private def expressionBound(expression: KernelExpr[?]): StructuralBound =
      val reference = Option(expressionIds.get(expression)).getOrElse(
        invalid("compound hdlRange expression has no captured construction identity")
      )
      if reference.module != owner then
        invalid(
          "compound hdlRange expression escapes its owning Module",
          Some(expressionPath(reference))
        )
      if !expression.resultType.contains(KernelTypeDescriptor("Integer")) then
        invalid("hdlRange expression must have Integer type", Some(expressionPath(reference)))
      cache.get(reference) match
        case Some(result) => result
        case None =>
          if active.size >= 512 || !active.add(reference) then
            invalid("hdlRange expression is cyclic or exceeds the supported dependency depth")
          try
            val result = expression.literal match
              case Some(_) => literalBound(expression)
              case None =>
                val operation = expression.operation match
                  case Some("analog_add") => IterationDomain.Arithmetic.Add
                  case Some("analog_sub") => IterationDomain.Arithmetic.Subtract
                  case Some("analog_mul") => IterationDomain.Arithmetic.Multiply
                  case Some("analog_div") => IterationDomain.Arithmetic.Divide
                  case Some("analog_neg") => IterationDomain.Arithmetic.Negate
                  case _ =>
                    invalid("hdlRange expression is not supported pure Integer arithmetic")
                val arity = if operation == IterationDomain.Arithmetic.Negate then 1 else 2
                if expression.operands.size != arity then
                  invalid("hdlRange Integer expression has invalid arity")
                val operands = expression.operands.map:
                  case parameter: Param[?] =>
                    parameterFact(parameter)
                  case child: KernelExpr[?] => expressionBound(child)
                  case _ => invalid("hdlRange expression has a non-static or untyped operand")
                // Correlation is valid only for the very same captured operand. Equal intervals
                // on independent parameters are not an equality proof.
                val bounds =
                  if operation == IterationDomain.Arithmetic.Subtract &&
                    sameStructuralBound(expression.operands(0), expression.operands(1))
                  then IterationDomain.Bounds(0L, 0L)
                  else
                    IterationDomain.arithmetic(operation, operands.map(_.bounds))
                      .fold(
                        problem => invalid(problem.message, Some(expressionPath(reference))),
                        identity
                      )
                val operandIds = operands.map(_.identity.get)
                val key = if operandIds.forall(id => identityKeys(id)._1 == 0) then
                  (0, bounds.lower.toString, Vector.empty[Int])
                else (2, expression.operation.get, operandIds)
                StructuralBound(
                  expression,
                  bounds,
                  operands.flatMap(_.parameters).distinct,
                  Some(intern(key))
                )
            cache.update(reference, result)
            result
          finally
            val _ = active.remove(reference)

    private def literalBound(expression: KernelExpr[?]): StructuralBound =
      val literal = expression.literal
        .filter(value =>
          value.kind == "integer" && value.dataType == KernelTypeDescriptor("Integer") &&
            expression.resultType.contains(KernelTypeDescriptor("Integer"))
        )
        .flatMap(value =>
          if signed64Literals then value.value.toLongOption
          else value.value.toIntOption.map(_.toLong)
        )
        .getOrElse(invalid("hdlRange literal must be a representable public Integer literal"))
      StructuralBound(
        expression,
        IterationDomain.Bounds(literal, literal),
        Vector.empty,
        Some(intern((0, literal.toString, Vector.empty)))
      )

    def apply(value: Int | Expr[Integer]): StructuralBound = value match
      case literal: Int =>
        StructuralBound(
          literal,
          IterationDomain.Bounds(literal, literal),
          Vector.empty,
          Some(intern((0, literal.toString, Vector.empty)))
        )
      case parameter: Param[?] => parameterFact(parameter)
      // Preserve the existing by-value handling of a direct immutable literal. A literal inside
      // an expression graph, in contrast, needs a captured owner for canonical serialization.
      case expression: KernelExpr[?] if expression.literal.nonEmpty => literalBound(expression)
      case expression: KernelExpr[?] => expressionBound(expression)
      case _ => invalid("hdlRange bound must be a static Integer expression")

  private def parameterBound(
      parameter: Param[?],
      owner: Long,
      diagnosticCode: String,
      subject: String
  ): StructuralBound =
    val reference = Option(declarationIds.get(parameter)).getOrElse(
      fail(
        diagnosticCode,
        s"symbolic $subject value must be a parameter in the active construction transaction"
      )
    )
    if reference.module != owner then
      fail(
        diagnosticCode,
        s"symbolic $subject value must be owned by the active Module",
        Some(declarationPath(reference))
      )
    val declaration = records(reference.module).declarations(reference.index)
    val parameterType = declaration.dataType.map(renderType(_, owner))
    if declaration.kind != KernelSignalKind.Parameter || !parameterType.contains("Integer") then
      fail(
        diagnosticCode,
        s"symbolic $subject value must be an integer parameter",
        Some(declarationPath(reference))
      )
    val lower = structuralRangeAttribute(declaration, "integer_range_lower").getOrElse(
      fail(
        diagnosticCode,
        s"symbolic $subject parameter requires a finite declared integer range",
        Some(declarationPath(reference))
      )
    )
    val upper = structuralRangeAttribute(declaration, "integer_range_upper").getOrElse(
      fail(
        diagnosticCode,
        s"symbolic $subject parameter requires a finite declared integer range",
        Some(declarationPath(reference))
      )
    )
    if lower > upper then
      fail(
        diagnosticCode,
        s"symbolic $subject parameter range is not ordered",
        Some(declarationPath(reference))
      )
    StructuralBound(parameter, IterationDomain.Bounds(lower, upper), Vector(reference))

  private def sameStructuralBound(left: Any, right: Any): Boolean =
    (left, right) match
      case (lhs: Int, rhs: Int) => lhs == rhs
      case (lhs: AnyRef, rhs: AnyRef) => lhs eq rhs
      case _ => false

  // Replicating analog nodes changes topology; generate is a construct, not an effect.
  private def markStructuralParameterEffect(reference: DeclarationRef, effect: String): Unit =
    val module = records(reference.module)
    val declaration = module.declarations(reference.index)
    val previousEffects = declaration.attributes.collectFirst:
      case ("structural_effects", value: String) => value
    val effects =
      (previousEffects.toVector.flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty) :+ effect)
        .distinct
        .sorted
        .mkString(",")
    val retained = declaration.attributes.filterNot: (name, _) =>
      name == "classification" || name == "structural_effects"
    module.declarations.update(
      reference.index,
      declaration.copy(
        attributes = retained ++ Vector(
          "classification" -> "structural",
          "structural_effects" -> effects
        )
      )
    )

  private def markStructuralParameter(reference: DeclarationRef): Unit =
    markStructuralParameterEffect(reference, "topology")

  def withGeneratedRegion(
      lower: Int | Expr[Integer],
      upperExclusive: Int | Expr[Integer],
      step: Int | Expr[Integer],
      maximum: Option[Int]
  )(body: Expr[Integer] => Unit): Unit =
    val module = currentModule
    val analysis = new StructuralBoundAnalysis(module.handle)
    val lowerBound = analysis(lower)
    val upperBound = analysis(upperExclusive)
    val stepBound = analysis(step)
    val envelope = IterationDomain
      .structural(
        lowerBound.bounds,
        upperBound.bounds,
        stepBound.bounds,
        maximum,
        identicalBounds = analysis.sameIdentity(lower, upperExclusive)
      )
      .fold(
        problem =>
          fail(
            problem.kind match
              case IterationDomain.ProblemKind.InvalidDirection => "NODAL-ITERATION-043-003"
              case IterationDomain.ProblemKind.InvalidStep => "NODAL-ITERATION-043-002"
              case _ => "NODAL-ITERATION-043-001",
            problem.message,
            Some(provisionalModulePath(module.handle))
          ),
        identity
      )

    Vector(lowerBound, upperBound, stepBound).flatMap(_.parameters).distinct.foreach(
      markStructuralParameter
    )

    val induction = new KernelExpr[Integer](
      Vector.empty,
      resultType = Some(KernelTypeDescriptor("Integer")),
      operation = Some("generate_index")
    )
    val inductionReference = captureExpression(induction).getOrElse(
      fail("NODAL-ITERATION-043-001", "hdlRange induction has no active construction owner")
    )
    val parentOrdinal = generationStack.lastOption
      .filter(_.owner == module.handle)
      .map(_.ordinal)
    val record = new GeneratedRegionRecord(
      module.handle,
      module.generatedRegions.size,
      parentOrdinal,
      inductionReference,
      lower,
      upperExclusive,
      step,
      maximum,
      envelope.maximumTripCount
    )
    module.generatedRegions += record
    generationStack += record
    generatedOwnershipRecords.update(
      generatedRegionPath(record) -> expressionPath(record.induction),
      record
    )
    val domainCount = module.domains.size
    val analogRegionCount = analogRegions.size
    val continuousOperatorCount = continuousOperators.size
    val waveformOperatorCount = waveformOperators.size
    val transferOperatorCount = transferOperators.size
    val noiseOperatorCount = noiseOperators.size
    try
      body(induction)
      val generatedContext =
        generatedRegionPath(record) -> expressionPath(record.induction)
      val unsupportedDeclaration = record.declarations.iterator
        .map(reference => module.declarations(reference.index))
        .find: declaration =>
          declaration.kind != KernelSignalKind.AnalogNode &&
            declaration.kind != KernelSignalKind.AnalogNodeArray &&
            !(declaration.kind == KernelSignalKind.Variable &&
              AnalogProceduralConstruction.retainsGeneratedVariable(
                declaration.value,
                generatedContext
              ))
      unsupportedDeclaration.foreach: declaration =>
        fail(
          "NODAL-ITERATION-043-004",
          s"generated object kind '${declaration.kind.label}' is not enabled by the current F-043 checkpoint",
          Some(declarationPath(declaration.reference))
        )
      val generatedAnalogRegions = analogRegions.drop(analogRegionCount)
        .filter(_.generatedOwnership.contains(generatedContext))
      val generatedOperators = continuousOperators.drop(continuousOperatorCount).filter: operator =>
        generatedAnalogRegions.exists(_.expressions.contains(operator.reference))
      val generatedWaveforms = waveformOperators.drop(waveformOperatorCount).filter:
        case (reference, _, _, _, _, _, _, _) =>
          generatedAnalogRegions.exists(_.expressions.contains(reference))
      val generatedTransfers = transferOperators.drop(transferOperatorCount).filter:
        case (reference, _, _, _, _, _) =>
          generatedAnalogRegions.exists(_.expressions.contains(reference))
      val generatedNoise = noiseOperators.drop(noiseOperatorCount).filter:
        case (reference, _, _, _, _) =>
          generatedAnalogRegions.exists(_.expressions.contains(reference))
      val generatedOperatorReferences = generatedOperators.map(_.reference).toSet
      val generatedWaveformReferences = generatedWaveforms.map(_._1).toSet
      val generatedTransferReferences = generatedTransfers.map(_._1).toSet
      val generatedNoiseReferences = generatedNoise.map(_._1).toSet
      val generatedExpressionReferences =
        generatedOperatorReferences ++ generatedWaveformReferences ++ generatedTransferReferences ++
          generatedNoiseReferences
      val supportedGeneratedAnalog =
        generatedAnalogRegions.nonEmpty &&
          generatedAnalogRegions.forall: analog =>
            analog.generatedOwnership.contains(generatedContext) &&
              (analog.contributions.nonEmpty ||
                analog.expressions.exists(reference =>
                  generatedExpressionReferences.contains(reference) ||
                    expressionValues.get(reference).exists(_.operation.exists(
                      _.startsWith(AnalogContextContract.Prefix)
                    ))
                )) &&
              analog.expressions.forall: reference =>
                !generatedExpressionReferences.contains(reference) ||
                  generatedOperators.exists(operator =>
                    operator.reference == reference &&
                      Set("analog_ddt", "analog_idt").contains(operator.operation)
                  ) || generatedWaveforms.exists(waveform =>
                    waveform._1 == reference &&
                      Set(
                        "analog_transition",
                        "analog_slew",
                        "analog_absdelay",
                        "analog_abstime",
                        "analog_bound_step"
                      )
                        .contains(waveform._2)
                  ) || generatedTransfers.exists(transfer =>
                    transfer._1 == reference &&
                      Set("laplace_nd", "zi_nd").contains(transfer._2)
                  ) || generatedNoise.exists(noise =>
                    noise._1 == reference &&
                      Set("white", "flicker", "table").contains(noise._2)
                  )
      val changedAnalog = generatedAnalogRegions.nonEmpty || generatedOperators.nonEmpty ||
        generatedWaveforms.nonEmpty || generatedTransfers.nonEmpty || generatedNoise.nonEmpty
      if changedAnalog && !supportedGeneratedAnalog then
        fail(
          "NODAL-ITERATION-043-004",
          "generated continuous regions require an owned ddt, idt, transition, slew, " +
            "absdelay, abstime, boundStep, laplaceNd, ziNd, noise expression, or additive " +
            "contribution",
          Some(generatedRegionPath(record))
        )
      if module.domains.size != domainCount then
        fail(
          "NODAL-ITERATION-043-004",
          "generated domains require their own lexical ownership contract",
          Some(provisionalModulePath(module.handle))
        )
    catch
      case failure: Throwable =>
        poisonConstructor(failure, generatedRegionPath(record))
        throw failure
    finally
      val removed = generationStack.remove(generationStack.size - 1)
      if removed ne record then
        fail("NODAL-ITERATION-043-004", "generated-region ownership stack is corrupt")

  def captureAnalogProceduralSource: Option[AnalogProceduralRuntime.Source] =
    semanticOrigin
      .captureSemanticSource()
      .map(source =>
        AnalogProceduralRuntime.Source(
          source.path,
          source.line,
          source.column
        )
      )

  def currentDomain: Option[ClockDomain] = domainStack.lastOption

  def withAnalogRegion[A](body: => A): A =
    val module = currentModule
    val generatedOwnership = generationStack.lastOption
      .filter(_.owner == module.handle)
      .map(region => generatedRegionPath(region) -> expressionPath(region.induction))
    val record =
      new AnalogRegionRecord(
        module.handle,
        analogRegions.count(_.module == module.handle),
        generatedOwnership
      )
    analogRegions += record
    analogStack += record
    try body
    finally
      val removed = analogStack.remove(analogStack.size - 1)
      if removed ne record then fail("NODAL-ANALOG-LIFECYCLE-001", "analog region stack is corrupt")

  private def equationSource: AnalogEquationRuntime.SourceSpan =
    semanticOrigin.captureSemanticSource()
      .map(span => AnalogEquationRuntime.SourceSpan(span.path, span.line, span.column))
      .getOrElse(AnalogEquationRuntime.SourceSpan("unknown.scala", 1, 1))

  private def equationComponent(owner: String): Option[String] =
    analogSemanticRecorder.currentComponent.map: component =>
      if component.owner != owner then
        fail("NODAL-ANALOG-134-001", "physical component belongs to another Module", Some(owner))
      component.identity

  def withPhysicalComponent[A](contract: PhysicalComponentContract)(body: => A): A =
    requireNoGeneratedEffect("physical component")
    val owner = provisionalModulePath(currentModule.handle)
    if contract.name.trim.isEmpty then
      fail("NODAL-ANALOG-134-001", "physical component name must be non-empty", Some(owner))
    val record = AnalogEquationRuntime.ComponentRecord(
      s"$owner.component.${contract.name.trim}",
      owner,
      contract,
      equationSource
    )
    if analogSemanticRecorder.containsComponent(record.identity) then
      fail("NODAL-ANALOG-134-001", "duplicate physical component identity", Some(record.identity))
    analogSemanticRecorder.component(record)(body)

  def declareAnalogState(state: AnalogState): Unit =
    requireNoGeneratedEffect("analog state declaration")
    val owner = provisionalModulePath(currentModule.handle)
    val component = equationComponent(owner)
    if state.name.trim.isEmpty then
      fail("NODAL-ANALOG-134-001", "analog state name must be non-empty", Some(owner))
    val identity = s"${component.getOrElse(owner)}.state.${state.name.trim}"
    if analogSemanticRecorder.containsState(identity) then
      fail("NODAL-ANALOG-134-001", "duplicate analog state identity", Some(identity))
    val dimension = inferAnalogDimension(state)
    if dimension.isUnknown then
      fail(
        "NODAL-ANALOG-134-002",
        "analog state requires a known physical dimension",
        Some(identity)
      )
    val (initialization, initializer) = state.initialization match
      case StateInitialization.Fixed(value) => "fixed" -> Some(value)
      case StateInitialization.Guess(value) => "guess" -> Some(value)
      case StateInitialization.InitialEquation => "initial-equation" -> None
      case StateInitialization.SteadyState => "steady-state" -> None
      case StateInitialization.OperatingPoint => "operating-point" -> None
      case StateInitialization.SolverSelected => "solver-selected" -> None
    initializer.foreach: value =>
      if CandidateRuntime.expressionDataType(value).exists(_ != Real) ||
        inferAnalogDimension(value).signature != dimension.signature
      then
        fail(
          "NODAL-ANALOG-134-002",
          "state initializer has incompatible physical type",
          Some(identity)
        )
    analogSemanticRecorder.state(AnalogEquationRuntime.StateRecord(
      identity,
      owner,
      component,
      state,
      dimension.signature,
      initialization,
      initializer.map(value => analogExpression(value, dimension.canonical)),
      equationSource
    ))

  private def analogSemanticSnapshot(): AnalogEquationRuntime.Snapshot =
    val original = analogSemanticRecorder.snapshot
    if original.components.isEmpty && original.states.isEmpty then original
    else
      val owners = records.valuesIterator.map(record =>
        provisionalModulePath(record.handle) -> modulePath(record.handle)
      ).toMap
      def owner(path: String): String = owners.getOrElse(path, path)
      def identity(path: String, previousOwner: String): String =
        owner(previousOwner) + path.stripPrefix(previousOwner)
      original.copy(
        components = original.components.map(record =>
          record.copy(
            identity = identity(record.identity, record.owner),
            owner = owner(record.owner)
          )
        ),
        states = original.states.map(record =>
          record.copy(
            identity = identity(record.identity, record.owner),
            owner = owner(record.owner),
            component = record.component.map(identity(_, record.owner))
          )
        ),
        equations = original.equations.map: record =>
          val previousOwner = record.metadata.owner
          record.copy(
            identity = AnalogEquationRuntime.EquationIdentity(identity(
              record.identity.value,
              previousOwner
            )),
            metadata = record.metadata.copy(
              owner = owner(previousOwner),
              component = record.metadata.component.map(identity(_, previousOwner))
            )
          )
      )

  def withAnalogSemanticRegion[A](
      kind: AnalogEquationRuntime.RegionKind
  )(body: => A): A =
    val module = currentModule
    if analogSemanticContext.nonEmpty then
      fail(
        "NODAL-ANALOG-032-001",
        "analog semantic regions cannot overlap",
        Some(provisionalModulePath(module.handle))
      )
    analogSemanticContext = Some(AnalogSemanticContext(module.handle, kind))
    try
      analogSemanticRecorder.region(kind)(body) match
        case Right(value) => value
        case Left(error) =>
          fail(error.code, error.message, Some(provisionalModulePath(module.handle)))
    finally analogSemanticContext = None

  def recordAnalogEquation(
      left: Expr[Real],
      right: Expr[Real],
      options: EquationOptions
  ): Unit =
    val owner = provisionalModulePath(currentModule.handle)
    val (leftDimension, rightDimension) = requireCompatibleDimensions(
      left,
      right,
      "NODAL-ANALOG-032-006",
      "equation operands"
    )
    val identity = equationIdentity(
      equationComponent(owner).getOrElse(owner),
      options.id.map(_.value),
      s"${renderAnalogValue(left)}===${renderAnalogValue(right)}"
    )
    val metadata = analogMetadata(
      owner,
      options.guard,
      options.analyses.values.map(analysisName),
      options.continuity
    )
    accept(
      analogSemanticRecorder.recordEquation(
        identity,
        analogExpression(left, leftDimension),
        analogExpression(right, rightDimension),
        metadata
      ),
      owner
    )

  def recordInitialAnalogEquation(
      left: Expr[Real],
      right: Expr[Real],
      options: InitialEquationOptions
  ): Unit =
    val owner = provisionalModulePath(currentModule.handle)
    val (leftDimension, rightDimension) = requireCompatibleDimensions(
      left,
      right,
      "NODAL-ANALOG-032-006",
      "initial-equation operands"
    )
    val identity = equationIdentity(
      equationComponent(owner).getOrElse(owner),
      options.id.map(_.value),
      s"initial:${renderAnalogValue(left)}===${renderAnalogValue(right)}"
    )
    val metadata = analogMetadata(
      owner,
      options.guard,
      Set("initialization"),
      options.continuity
    )
    accept(
      analogSemanticRecorder.recordEquation(
        identity,
        analogExpression(left, leftDimension),
        analogExpression(right, rightDimension),
        metadata
      ),
      owner
    )

  def recordAnalogContribution(
      target: Expr[Real],
      value: Expr[Real],
      options: ContributionOptions
  ): Unit =
    val owner = provisionalModulePath(currentModule.handle)
    val (targetDimension, valueDimension) = requireCompatibleDimensions(
      target,
      value,
      "NODAL-ANALOG-032-011",
      "contribution target and value"
    )
    val targetRecord = analogContributionTarget(target, owner).copy(dimension = targetDimension)
    val explicitIdentity = options.id.map(_.value)
    val identity = contributionIdentity(
      owner,
      explicitIdentity,
      s"${targetRecord.identity}<+${renderAnalogValue(value)}"
    )
    val metadata = analogMetadata(
      owner,
      options.guard,
      options.analyses.values.map(analysisName),
      options.continuity
    )
    accept(
      analogSemanticRecorder.recordContribution(
        identity,
        targetRecord,
        analogExpression(value, valueDimension),
        metadata
      ),
      owner
    )

  def recordShortAnalogContribution(target: Expr[Real], value: Expr[Real]): Unit =
    analogSemanticContext match
      case Some(_) => recordAnalogContribution(target, value, ContributionOptions())
      case None => operation("analog-contribute", target, value)

  private def accept[A](
      result: Either[AnalogEquationRuntime.Diagnostic, A],
      owner: String
  ): Unit = result match
    case Right(_) => ()
    case Left(error) => fail(error.code, error.message, Some(owner))

  private def equationIdentity(
      owner: String,
      explicit: Option[String],
      fallbackSeed: String
  ): AnalogEquationRuntime.EquationIdentity =
    val local = semanticIdentity("equation", explicit, fallbackSeed)
    AnalogEquationRuntime.EquationIdentity(s"$owner.$local")

  private def contributionIdentity(
      owner: String,
      explicit: Option[String],
      fallbackSeed: String
  ): AnalogEquationRuntime.ContributionIdentity =
    val local = semanticIdentity("contribution", explicit, fallbackSeed)
    AnalogEquationRuntime.ContributionIdentity(s"$owner.$local")

  private def semanticIdentity(
      kind: String,
      explicit: Option[String],
      fallbackSeed: String
  ): String = explicit match
    case Some(value) if value.trim.isEmpty =>
      val suffix = if kind == "equation" then "015" else "016"
      fail(s"NODAL-ANALOG-032-$suffix", s"$kind identity must be non-empty")
    case Some(value) => value.trim
    case None => s"${kind}_${stableSemanticDigest(fallbackSeed).take(16)}"

  private def stableSemanticDigest(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def analogMetadata(
      owner: String,
      guard: Option[Expr[Bool]],
      analyses: Set[String],
      continuity: ContinuityClass
  ): AnalogEquationRuntime.Metadata =
    val source = semanticOrigin
      .captureSemanticSource()
      .map(span => AnalogEquationRuntime.SourceSpan(span.path, span.line, span.column))
      .getOrElse(AnalogEquationRuntime.SourceSpan("unknown.scala", 1, 1))
    AnalogEquationRuntime.Metadata(
      owner,
      guard.map(value =>
        AnalogEquationRuntime.Expression(
          renderAnalogValue(value),
          "1",
          AnalogEquationRuntime.ValueKind.Boolean
        )
      ),
      analyses,
      continuityName(continuity),
      source,
      equationComponent(owner)
    )

  private def analysisName(kind: AnalysisKind): String = kind match
    case AnalysisKind.Initialization => "initialization"
    case AnalysisKind.Dc => "dc"
    case AnalysisKind.OperatingPoint => "operating-point"
    case AnalysisKind.Transient => "transient"
    case AnalysisKind.Ac => "ac"
    case AnalysisKind.Noise => "noise"

  private def continuityName(value: ContinuityClass): String = value match
    case ContinuityClass.Unspecified => "unspecified"
    case ContinuityClass.Discontinuous => "discontinuous"
    case ContinuityClass.C0 => "c0"
    case ContinuityClass.C1 => "c1"
    case ContinuityClass.C2 => "c2"

  private def analogExpression(
      value: Expr[?],
      dimension: String
  ): AnalogEquationRuntime.Expression =
    AnalogEquationRuntime.Expression(
      renderAnalogValue(value),
      dimension,
      AnalogEquationRuntime.ValueKind.Real,
      Some(value)
    )

  private def requireCompatibleDimensions(
      left: Expr[Real],
      right: Expr[Real],
      diagnostic: String,
      label: String
  ): (String, String) =
    val leftDimension = inferAnalogDimension(left)
    val rightDimension = inferAnalogDimension(right)
    if leftDimension.isUnknown || rightDimension.isUnknown then
      fail(
        diagnostic,
        s"$label require known physical dimensions",
        pathOf(left).orElse(pathOf(right))
      )
    val leftCanonical = leftDimension.canonical
    val rightCanonical = rightDimension.canonical
    if leftCanonical != rightCanonical then
      fail(
        diagnostic,
        s"$label have incompatible dimensions: $leftCanonical versus $rightCanonical",
        pathOf(left).orElse(pathOf(right))
      )
    leftCanonical -> rightCanonical

  def registerAnalogFunction(expression: KernelExpr[Real], id: String): Unit =
    // Infer before capture: a failed call cannot leave a partially accepted expression.
    val _ = inferAnalogDimension(expression)
    val _ = AnalogFunctionContract.constant(id, expression.operands.map(waveformConstant))
    registerExpression(expression)

  def defineUserFunction[A <: Data](
      name: String,
      resultType: DataType[A],
      units: PhysicalDimension,
      body: AnalogFunctionBody => Expr[A]
  ): AnalogFunction[A] =
    if analogStack.nonEmpty || analogSemanticContext.nonEmpty then
      AnalogUserFunctionRuntime.fail(1, "analog function declarations belong directly to a Module")
    userFunctions.define(
      currentModule.handle,
      name,
      resultType,
      units,
      () => captureAnalogProceduralSource,
      body
    )

  def callUserFunction[A <: Data](
      function: AnalogFunction[A],
      arguments: Vector[Expr[?]]
  ): Expr[A] =
    val module = currentModule
    if analogSemanticContext.nonEmpty || !analogStack.lastOption.exists(_.module == module.handle)
    then
      AnalogUserFunctionRuntime.fail(
        1,
        "analog function calls currently require an analog expression region"
      )
    val definition = userFunctions.validate(function, module.handle)
    def owned(value: Any): Unit = value match
      case reference: AnyRef =>
        if Option(expressionIds.get(reference)).exists(_.module != module.handle) ||
          Option(declarationIds.get(reference)).exists(_.module != module.handle)
        then AnalogUserFunctionRuntime.fail(5, "analog function argument belongs to another Module")
        value match
          case expression: KernelExpr[?] => expression.operands.foreach(owned)
          case _ => ()
      case _ => ()
    arguments.foreach: argument =>
      if !expressionIds.containsKey(argument) && !declarationIds.containsKey(argument) then
        AnalogUserFunctionRuntime.fail(5, "function call argument has no active construction owner")
      owned(argument)
    val types = arguments.map: argument =>
      val kind = CandidateRuntime.expressionDataType(argument) match
        case Some(Integer) => "integer"
        case Some(Real) | None => "real"
        case _ => AnalogUserFunctionRuntime.fail(3, "unsupported function argument scalar type")
      AnalogUserFunctionRuntime.ValueType(kind, inferAnalogDimension(argument).signature)
    AnalogUserFunctionRuntime.checkArguments(definition, types)
    val expression = AnalogUserFunctionRuntime.expression(function, arguments)
    registerExpression(expression)
    expression

  private def analogContributionTarget(
      target: Expr[Real],
      owner: String
  ): AnalogEquationRuntime.ContributionTarget = target match
    case expression: KernelExpr[?] =>
      val kind = expression.operation match
        case Some("potential_access") | Some("candidate-branch-potential") =>
          AnalogEquationRuntime.ContributionKind.Potential
        case Some("flow_access") | Some("candidate-branch-flow") =>
          AnalogEquationRuntime.ContributionKind.Flow
        case _ =>
          fail(
            "NODAL-ANALOG-133-005",
            "contribution target must be a potential or flow access",
            pathOf(target)
          )
      val (identity, orientation) = contributionTargetIdentity(expression.operands, owner)
      AnalogEquationRuntime.ContributionTarget(
        identity,
        kind,
        inferAnalogDimension(target).canonical,
        orientation
      )
    case _ =>
      fail(
        "NODAL-ANALOG-133-005",
        "contribution target must be a potential or flow access",
        pathOf(target)
      )

  private def contributionTargetIdentity(
      values: Vector[Any],
      owner: String
  ): (String, String) =
    val branchIdentity = values.collectFirst:
      case branch: Branch[?] =>
        val positive = renderAnalogValue(branch.positive)
        val negative = renderAnalogValue(branch.negative)
        val identity = branch.name
          .filter(_.trim.nonEmpty)
          .map(name => s"$owner.branch.${name.trim}")
          .getOrElse(s"$owner.branch.$positive->$negative")
        identity -> s"$positive->$negative"
    branchIdentity
      .orElse:
        val endpoints = values.collect:
          case node: Node[?] => renderAnalogValue(node)
          case terminal: Terminal[?] => renderAnalogValue(terminal)
          case view: TerminalView[?, ?] => renderAnalogValue(view.terminal)
        endpoints match
          case Vector(positive, negative, _*) =>
            Some(s"$owner.branch.$positive->$negative" -> s"$positive->$negative")
          case Vector(single) =>
            Some(s"$owner.branch.$single->reference" -> s"$single->reference")
          case _ => None
      .getOrElse:
        val rendered = values.map(renderAnalogValue).mkString("(", ",", ")")
        s"$owner.branch.$rendered" -> rendered

  /** F32 authored text is retained verbatim, but its tree expansion must be bounded before
    * materialization. Identity memoization makes the preflight follow shared expression DAGs once;
    * cached heights still check the full path when a dependency is reached at a greater depth.
    */
  private def renderAnalogValue(value: Any): String =
    val maximumCharacters = 1024L * 1024L
    val maximumDepth = 512
    final case class Rendering(
        prefix: String,
        children: Vector[Any] = Vector.empty,
        separator: String = "",
        suffix: String = ""
    )
    val memo = new IdentityHashMap[AnyRef, (Rendering, Long, Int)]()
    val active = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    def invalid(): Nothing = fail(
      "NODAL-ANALOG-134-001",
      "authored analog expression exceeds the supported 1048576-character or 512-level limit, or is cyclic"
    )
    def describe(value: Any): Rendering = value match
      case expression: KernelExpr[?] if expression.literal.nonEmpty =>
        val literal = expression.literal.map(_.value).getOrElse("")
        val unit = expression.operands.lift(1).collect { case text: String => text }.getOrElse("")
        Rendering(if unit.isEmpty then literal else s"$literal $unit")
      case expression: KernelExpr[?] =>
        Rendering(
          expression.operation.getOrElse("expression") + "(",
          expression.operands,
          ",",
          ")"
        )
      case branch: Branch[?] =>
        branch.name.filter(_.trim.nonEmpty).map(Rendering(_)).getOrElse(
          Rendering("", Vector(branch.positive, branch.negative), "->")
        )
      case state: AnalogState => Rendering(s"state:${state.name}")
      case terminal: Terminal[?] =>
        Rendering(pathOf(terminal).getOrElse(s"terminal:${terminal.name}"))
      case node: Node[?] => Rendering(pathOf(node).getOrElse("analog-node"))
      case parameter: Param[?] => Rendering(pathOf(parameter).getOrElse("parameter"))
      case reference: AnyRef =>
        Rendering(pathOf(reference).getOrElse(stableClassName(reference)))
      case other => Rendering(other.toString)
    def preflight(value: Any, depth: Int): (Rendering, Long, Int) =
      if depth >= maximumDepth then invalid()
      val reference = value match
        case value: AnyRef => Some(value)
        case _ => None
      if reference.exists(active.containsKey) then invalid()
      reference.flatMap(value => Option(memo.get(value))) match
        case Some(result) =>
          if depth + result._3 >= maximumDepth then invalid()
          result
        case None =>
          reference.foreach(value => active.put(value, java.lang.Boolean.TRUE))
          try
            val rendering = describe(value)
            var size = rendering.prefix.length.toLong + rendering.suffix.length.toLong +
              rendering.separator.length.toLong * (rendering.children.size - 1).max(0)
            if size > maximumCharacters then invalid()
            var height = 0
            rendering.children.foreach: child =>
              val (_, childSize, childHeight) = preflight(child, depth + 1)
              if childSize > maximumCharacters - size then invalid()
              size += childSize
              height = height.max(childHeight + 1)
            val result = (rendering, size, height)
            reference.foreach(value => memo.put(value, result))
            result
          finally reference.foreach(active.remove)
    val (_, size, _) = preflight(value, 0)
    val rendered = new java.lang.StringBuilder(size.toInt)
    def append(value: Any): Unit =
      val rendering = value match
        case reference: AnyRef => Option(memo.get(reference)).map(_._1).getOrElse(describe(value))
        case _ => describe(value)
      rendered.append(rendering.prefix)
      rendering.children.zipWithIndex.foreach: (child, index) =>
        if index != 0 then rendered.append(rendering.separator)
        append(child)
      rendered.append(rendering.suffix)
    append(value)
    rendered.toString

  def operation(kind: String, values: Any*): Unit =
    if kind == "statement" then
      val effect = moduleStack.lastOption.flatMap(module =>
        values.headOption.map(renderAny(_, module.handle))
      ).getOrElse(kind)
      analogSemanticRecorder.unsupported(s"unrepresented source effect: $effect")
    val generatedAnalogContribution =
      kind == "analog-contribute" && generatedContinuousRegionActive
    val structuralTopology = Set(
      "node-connect",
      "node-array-connect",
      "instance-node-array",
      "deferred-node-array"
    ).contains(kind)
    if !generatedAnalogContribution && !structuralTopology then
      requireNoGeneratedEffect(s"generated '$kind' effect")
    if kind == "assignment" || kind == "analog-assignment" then
      analogSemanticContext.foreach: context =>
        if context.kind != AnalogEquationRuntime.RegionKind.Procedural then
          fail(
            "NODAL-ANALOG-133-007",
            "procedural assignment is illegal in a declarative analog region",
            Some(provisionalModulePath(context.module))
          )
    if kind == "analog-contribute" then
      val region = analogStack.lastOption.getOrElse(
        fail("NODAL-ANALOG-LIFECYCLE-002", "analog contribution is outside an analog region")
      )
      if values.size != 2 then
        fail("NODAL-ANALOG-LIFECYCLE-003", "analog contribution requires target and value")
      region.contributions += ((values(0), values(1)))
    if !generatedAnalogContribution then
      val owner = moduleStack.lastOption.map(_.handle).getOrElse(
        fail("NODAL-LIFECYCLE-018", "operation requires an active Module")
      )
      val captured = Operation(
        owner,
        kind,
        values.toVector,
        activeGeneratedRegion,
        if structuralTopology && activeGeneratedRegion.nonEmpty then
          semanticOrigin.captureSemanticSource()
        else None
      )
      operations += captured
      moduleStack.lastOption.foreach(module =>
        // Admission distinguishes analog storage effects; source naming retains
        // the shared assignment sink-affinity semantics for both kinds.
        semanticOrigin.captureOperation(
          module.handle,
          if kind == "analog-assignment" then "assignment" else kind,
          captured.values
        )
      )

  private def provisionalModulePath(handle: Long): String =
    val record = records(handle)
    record.parentAtConstruction match
      case None => record.className
      case Some(parentHandle) =>
        val parent = records(parentHandle)
        val ordinal = parent.instances
          .find(_.child == handle)
          .map(_.ordinal)
          .orElse:
            if !record.attached && moduleStack.exists(_.handle == handle) then
              Some(parent.instances.size)
            else None
          .getOrElse(
            fail("NODAL-HIERARCHY-020", "child Module has no Instance record")
          )
        s"${provisionalModulePath(parentHandle)}.${record.className}_$ordinal"

  private def modulePath(handle: Long): String =
    semanticResult.flatMap(_.modulePaths.get(handle)).getOrElse(provisionalModulePath(handle))

  private def domainName(reference: DomainRef): String =
    semanticResult
      .flatMap(_.domainNames.get(reference.module -> reference.index))
      .getOrElse(records(reference.module).domains(reference.index).name)

  private def domainPath(reference: DomainRef): String =
    semanticResult
      .flatMap(_.domainPaths.get(reference.module -> reference.index))
      .getOrElse(s"${modulePath(reference.module)}.${domainName(reference)}")

  private def declarationName(reference: DeclarationRef): String =
    semanticResult
      .flatMap(_.declarationNames.get(reference.module -> reference.index))
      .getOrElse:
        val declaration = records(reference.module).declarations(reference.index)
        declaration.explicitName.getOrElse(
          s"${declaration.kind.label}_${reference.index}"
        )

  private def declarationPath(reference: DeclarationRef): String =
    semanticResult
      .flatMap(_.declarationPaths.get(reference.module -> reference.index))
      .getOrElse(s"${modulePath(reference.module)}.${declarationName(reference)}")

  private def expressionPath(reference: ExpressionRef): String =
    semanticResult
      .flatMap(_.expressionPaths.get(reference.module -> reference.index))
      .getOrElse(s"${modulePath(reference.module)}.expr_${reference.index}")

  private def instancePath(parent: Long, ordinal: Int): String =
    semanticResult
      .flatMap(_.instancePaths.get(parent -> ordinal))
      .getOrElse(s"${modulePath(parent)}.instance_$ordinal")

  def pathOf(value: Any): Option[String] = value match
    case node: Node[?] if node.endpointReference.nonEmpty =>
      Option(expressionIds.get(node)).orElse(node.endpointReference).flatMap(
        shapeIndices.get
      ).map(conservativeEndpointPath)
    case reference: AnyRef =>
      Option(declarationIds.get(reference))
        .map(declarationPath)
        .orElse(Option(expressionIds.get(reference)).map(expressionPath))
        .orElse(Option(domainIds.get(reference)).map(domainPath))
        .orElse(Option(moduleIds.get(reference)).map(handle => modulePath(handle.longValue)))
    case _ => None

  private def selectedPath(value: AnyRef): Option[String] =
    if declarationIds.containsKey(value) || expressionIds.containsKey(value) ||
      domainIds.containsKey(value) || moduleIds.containsKey(value)
    then pathOf(value)
    else None

  private def selectedOperand(value: Any): KernelSelectedOperand = value match
    case text: String => KernelSelectedOperand.Literal("string", text)
    case value: Int => KernelSelectedOperand.Literal("integer", value.toString)
    case value: Long => KernelSelectedOperand.Literal("long", value.toString)
    case value: Double =>
      KernelSelectedOperand.Literal("real", java.lang.Double.toString(value))
    case value: Boolean => KernelSelectedOperand.Literal("boolean", value.toString)
    case reference: AnyRef if selectedPath(reference).nonEmpty =>
      KernelSelectedOperand.Reference(selectedPath(reference).get)
    case _: Expr[?] | _: ClockDomain | _: Module | _: Node[?] | _: NodeArray[?] =>
      fail(
        "NODAL-SELECTION-050-001",
        "selected expression references a foreign construction handle"
      )
    case _ => KernelSelectedOperand.Opaque("unsupported")

  def operationKinds: Vector[String] = operations.iterator.map(_.kind).toSet.toVector.sorted

  /** Called only after finish has resolved final names and structural aliases. */
  def selectedReference(value: AnyRef): KernelSelectedReference =
    val path = selectedPath(value).getOrElse(
      fail("NODAL-SELECTION-050-001", "selected handle is outside this construction transaction")
    )
    Option(declarationIds.get(value)).map: reference =>
      val declaration = records(reference.module).declarations(reference.index)
      KernelSelectedReference(
        path,
        modulePath(reference.module),
        declaration.kind.label,
        declaration.dataType.map(renderType(_, reference.module))
      )
    .orElse(Option(domainIds.get(value)).map: reference =>
      KernelSelectedReference(
        path,
        modulePath(reference.module),
        "domain",
        None
      )).orElse(Option(expressionIds.get(value)).map: reference =>
      val expression = expressionValues.get(reference)
      KernelSelectedReference(
        path,
        modulePath(reference.module),
        "expression",
        expression.flatMap(CandidateRuntime.expressionDataType).map(renderType(
          _,
          reference.module
        )),
        expression.flatMap(_.operation),
        expression.toVector.flatMap(_.operands).map(selectedOperand)
      )).orElse(Option(moduleIds.get(value)).map: reference =>
      KernelSelectedReference(path, modulePath(reference.longValue), "module", None)).getOrElse(
      fail("NODAL-SELECTION-050-001", "selected handle has no canonical reference")
    )

  private def stableClassName(value: AnyRef): String =
    val name = value.getClass.getSimpleName.stripSuffix("$")
    if name.isEmpty then value.getClass.getName.split("\\.").last.stripSuffix("$") else name

  private def renderAny(value: Any, owner: Long): String = value match
    case candidate if Option(candidate).isEmpty => "null"
    case text: String => text
    case boolean: Boolean => boolean.toString
    case integer: Int => integer.toString
    case long: Long => long.toString
    case double: Double => java.lang.Double.toString(double)
    case big: BigInt => big.toString
    case expression: KernelExpr[?] if expression.literal.nonEmpty =>
      expression.literal.map(_.value).getOrElse("")
    case discipline: NamedDiscipline => discipline.name
    case Electrical => "electrical"
    case mode: DriveMode.Value[?] => mode.name
    case placement: InoutPlacement => placement.toString
    case profile: ResolutionProfile => profile.toString
    case dataType: DataType[?] => renderType(dataType, owner)
    case field: StructField[?] => s"${field.name}:${renderType(field.dataType, owner)}"
    case option: Option[?] => option.map(renderAny(_, owner)).getOrElse("none")
    case sequence: Seq[?] => sequence.map(renderAny(_, owner)).mkString("[", ",", "]")
    case set: Set[?] => set.toVector.map(renderAny(_, owner)).sorted.mkString("[", ",", "]")
    case reference: AnyRef =>
      Option(declarationIds.get(reference)).map(declarationPath)
        .orElse(
          Option(expressionIds.get(reference)).map(expressionPath)
        )
        .orElse(Option(domainIds.get(reference)).map(domainPath))
        .getOrElse(stableClassName(reference))
    case other => other.toString

  private def renderType(dataType: DataType[?], owner: Long): String =
    val descriptor = CandidateRuntime.typeDescriptor(dataType)
    descriptor.kind match
      case "Struct" =>
        val name = descriptor.arguments.headOption.collect { case value: String =>
          value
        }.getOrElse("Struct")
        val fields = descriptor.arguments.lift(1).toVector.flatMap:
          case values: Seq[?] => values.collect:
              case field: StructField[?] =>
                s"${field.name}:${renderType(field.dataType, owner)}"
          case _ => Vector.empty
        s"Struct($name{${fields.mkString(",")}})"
      case "Vec" =>
        val element = descriptor.arguments.headOption.collect:
          case value: DataType[?] => renderType(value, owner)
        val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
          case values: Seq[?] => values.map(renderShapeDimension(_, owner))
          case _ => Vector.empty
        s"Vec(${element.getOrElse("unknown")};${dimensions.mkString("x")})"
      case kind if descriptor.arguments.nonEmpty =>
        s"$kind(${descriptor.arguments.map(renderAny(_, owner)).mkString(",")})"
      case kind => kind

  private def renderShapeDimension(value: Any, owner: Long): String = value match
    case literal: Int => literal.toString
    case parameter: Param[?] =>
      Option(declarationIds.get(parameter)) match
        case Some(reference) if reference.module == owner => declarationName(reference)
        case Some(reference) =>
          fail(
            "NODAL-SHAPE-043-001",
            "symbolic Vec dimension escapes its owning Module",
            Some(declarationPath(reference))
          )
        case None => fail("NODAL-SHAPE-043-001", "symbolic Vec dimension has no identity")
    case expression: KernelExpr[?] if expression.literal.exists(_.kind == "integer") =>
      expression.literal.map(_.value).getOrElse("")
    case expression: KernelExpr[?] =>
      Option(expressionIds.get(expression)) match
        case Some(reference) if reference.module == owner => expressionPath(reference)
        case Some(reference) =>
          fail(
            "NODAL-SHAPE-043-001",
            "compound Vec dimension escapes its owning Module",
            Some(expressionPath(reference))
          )
        case None =>
          fail("NODAL-SHAPE-043-001", "compound Vec dimension has no identity")
    case _ =>
      fail("NODAL-SHAPE-043-001", "Vec dimension has no canonical static spelling")

  private def resolveDomains(): Map[DomainRef, String] =
    val resolved = mutable.LinkedHashMap.empty[DomainRef, String]

    def visible(domain: ClockDomain): String =
      resolved.getOrElse(
        domainRef(domain),
        fail("NODAL-BINDING-020", "bound domain is not visible from the parent Module")
      )

    def visit(handle: Long): Unit =
      val module = records(handle)
      val path = modulePath(handle)
      module.domains.filter(_.kind != KernelDomainKind.Required).foreach: domain =>
        resolved.update(domain.reference, domainPath(domain.reference))

      if module.parentAtConstruction.isEmpty then
        module.domains.filter(_.kind == KernelDomainKind.Required).foreach: requirement =>
          fail(
            "NODAL-ROOT-DOMAIN-016",
            s"top-level domain requirement '${requirement.name}' is unbound",
            Some(path)
          )

      module.instances.foreach: instance =>
        val child = records(instance.child)
        val requirements = child.domains.filter(_.kind == KernelDomainKind.Required).toVector
        val parentVisible =
          module.domains.flatMap(domain => resolved.get(domain.reference)).distinct.toVector
        requirements.foreach: requirement =>
          val named = instance.namedBindings.collectFirst:
            case (selected, actual) if selected eq requirement.domain => visible(actual)
          val binding = named
            .orElse(if requirements.size == 1 then instance.defaultBinding.map(visible) else None)
            .orElse(if requirements.size == 1 then instance.lexicalDomain.map(visible) else None)
            .orElse(if requirements.size == 1 && parentVisible.size == 1 then
              parentVisible.headOption
            else None)
          binding match
            case Some(actual) => resolved.update(requirement.reference, actual)
            case None =>
              fail(
                "NODAL-CHILD-DOMAIN-016",
                s"child domain requirement '${requirement.name}' is unbound",
                Some(modulePath(instance.child))
              )
        visit(instance.child)

    val roots = records.values.filter(_.parentAtConstruction.isEmpty).toVector
    if roots.size != 1 then fail("NODAL-ROOT-016", s"expected one root Module, found ${roots.size}")
    visit(roots.head.handle)
    resolved.toMap

  private def declarationDomain(
      declaration: DeclarationRecord,
      resolved: Map[DomainRef, String]
  ): Option[String] = declaration.domainCandidate match
    case Some(domain) =>
      resolved.get(domainRef(domain)).orElse(
        fail(
          "NODAL-DECL-DOMAIN-016",
          s"${declaration.kind.label} has an unresolved domain",
          Some(declarationPath(declaration.reference))
        )
      )
    case None if declaration.kind == KernelSignalKind.Register =>
      val module = records(declaration.reference.module)
      val choices =
        module.domains.flatMap(domain => resolved.get(domain.reference)).distinct.toVector
      choices match
        case Vector(single) => Some(single)
        case Vector() =>
          fail(
            "NODAL-STATE-DOMAIN-016",
            "state has no lexical or default domain",
            Some(declarationPath(declaration.reference))
          )
        case _ =>
          fail(
            "NODAL-MULTI-DOMAIN-016",
            "state in a multi-domain Module requires a lexical ClockDomain",
            Some(declarationPath(declaration.reference))
          )
    case None => None

  private def endpointAbi(
      definition: InterfaceType[?],
      role: Role[?],
      name: String,
      domain: ClockDomain,
      count: Option[Any],
      declaration: DeclarationRecord,
      resolved: Map[DomainRef, String]
  ): Vector[InterfaceAbiEntry] =
    val names = definition.members.map(memberName)
    if names.distinct.size != names.size then
      fail(
        "NODAL-INTERFACE-MEMBER-016",
        s"Interface '${definition.name}' has duplicate member names",
        Some(declarationPath(declaration.reference))
      )
    val grouped = role.access.groupBy(accessMember)
    val missing = names.filterNot(grouped.contains)
    val unknown = grouped.keySet.diff(names.toSet)
    val duplicate =
      grouped.collect { case (member, accesses) if accesses.size != 1 => member }.toVector
    if missing.nonEmpty || unknown.nonEmpty || duplicate.nonEmpty then
      fail(
        "NODAL-ROLE-COMPLETE-016",
        s"role '${role.name}' is incomplete",
        Some(declarationPath(declaration.reference))
      )
    val endpointDomain = resolved.getOrElse(
      domainRef(domain),
      fail("NODAL-INTERFACE-DOMAIN-016", s"Interface endpoint '$name' has no domain")
    )
    val owner = declaration.reference.module
    val suffix = count.map(value => s"[${renderAny(value, owner)}]").getOrElse("")
    definition.members.toVector.flatMap: member =>
      val memberNameValue = memberName(member)
      val access = grouped(memberNameValue).head
      if !validAccess(member, access) then
        fail(
          "NODAL-ROLE-ACCESS-016",
          s"role '${role.name}' has an invalid access for '$memberNameValue'",
          Some(declarationPath(declaration.reference))
        )
      ConstructionInterfaceLayout.expandMember(
        member,
        access,
        s"${modulePath(owner)}.$name$suffix.$memberNameValue",
        s"${name}_$memberNameValue",
        role.name,
        endpointDomain,
        dataType => renderType(dataType, owner)
      )

  private def interfaceAbi(resolved: Map[DomainRef, String]): Vector[InterfaceAbiEntry] =
    val entries = records.values.toVector.flatMap: module =>
      module.declarations.toVector.flatMap: declaration =>
        declaration.value match
          case port: InterfacePort[?, ?] =>
            endpointAbi(
              port.definition,
              port.role,
              port.name,
              port.domain,
              None,
              declaration,
              resolved
            )
          case array: InterfaceArray[?, ?] =>
            endpointAbi(
              array.definition,
              array.role,
              array.name,
              array.domain,
              Some(array.count),
              declaration,
              resolved
            )
          case _ => Vector.empty
    entries.sortBy(_.logicalPath)

  private def attribute(
      declaration: DeclarationRecord,
      name: String,
      owner: Long,
      default: String
  ): String = declaration.attributes.find(_._1 == name)
    .map(value => renderAny(value._2, owner))
    .getOrElse(default)

  private def resolvedNets(): Vector[KernelResolvedNetSnapshot] =
    val nets = records.values.toVector.flatMap: module =>
      module.declarations.collect:
        case declaration if declaration.kind == KernelSignalKind.DigitalInout =>
          val related = operations.iterator
            .filter: operation =>
              operation.values.exists:
                case reference: AnyRef => reference eq declaration.value
                case _ => false
            .map(_.kind)
            .toVector
          KernelResolvedNetSnapshot(
            declarationPath(declaration.reference),
            declaration.dataType.map(renderType(_, module.handle)).getOrElse("Bits"),
            attribute(declaration, "mode", module.handle, "unknown"),
            attribute(declaration, "placement", module.handle, "unknown"),
            attribute(declaration, "profile", module.handle, "unknown"),
            related
          )
    nets.sortBy(_.path)

  private def conservativeArrayBindingRecords
      : Vector[(KernelConservativeArrayBindingSnapshot, ConservativeArrayBindingRecord)] =
    val ordinals = mutable.HashMap.empty[Long, Int]
    operations.toVector.collect:
      case operation if operation.kind == "node-array-connect" =>
        val binding = operation.values match
          case Vector(value: ConservativeArrayBindingRecord) => value
          case _ => fail("NODAL-SHAPE-043-007", "array relation lost its typed captured payload")
        val ordinal = ordinals.getOrElse(operation.owner, 0)
        ordinals.update(operation.owner, ordinal + 1)
        val owner = modulePath(operation.owner)
        KernelConservativeArrayBindingSnapshot(
          s"$owner.array_binding_$ordinal",
          owner,
          pathOfReference(binding.left),
          pathOfReference(binding.right),
          generatedScopeSnapshot(operation.generatedScope)
        ) -> binding

  private def instanceArrayRootSnapshots(): Vector[KernelInstanceArrayRootSnapshot] =
    operations.toVector.flatMap: operation =>
      operation.values.collect:
        case root: InstanceArrayRootRecord =>
          KernelInstanceArrayRootSnapshot(
            pathOfReference(root.reference),
            modulePath(operation.owner),
            instancePath(operation.owner, root.instanceOrdinal),
            declarationPath(root.port),
            root.dimensions.getOrElse(fail(
              "NODAL-SHAPE-043-007",
              "instance array shape remains unresolved"
            ))
              .map(renderShapeDimension(_, operation.owner)),
            generatedScopeSnapshot(operation.generatedScope)
          )

  private def markConservativeBindingDependencies(): Unit =
    operations.foreach: operation =>
      operation.values.foreach:
        case root: InstanceArrayRootRecord =>
          val instance = records(operation.owner).instances(root.instanceOrdinal)
          val visited = new IdentityHashMap[AnyRef, java.lang.Boolean]()
          def visit(value: Any): Unit = value match
            case parameter: Param[?]
                if Option(visited.put(parameter, java.lang.Boolean.TRUE)).isEmpty =>
              val reference = Option(declarationIds.get(parameter)).getOrElse(
                fail("NODAL-SHAPE-043-007", "instance shape dependency has no owned declaration")
              )
              markStructuralParameterEffect(reference, "shape")
              markStructuralParameterEffect(reference, "rank")
              if reference.module == instance.child then
                val overrideValue = instance.parameterOverrides.collectFirst:
                  case (target: AnyRef, actual)
                      if Option(declarationIds.get(target)).contains(reference) => actual
                visit(overrideValue.getOrElse(parameter.default))
            case expression: KernelExpr[?]
                if Option(visited.put(expression, java.lang.Boolean.TRUE)).isEmpty =>
              expression.operands.foreach(visit)
            case _ => ()
          records(
            root.port.module
          ).declarations(root.port.index).conservativeDimensions.foreach(visit)
        case _ => ()

  private def materializeConservativeIntents(base: ConstructionSnapshot): Unit =
    val declarations = records.valuesIterator.flatMap(_.declarations.iterator)
      .map(value => declarationPath(value.reference) -> value.value).toMap
    val expressions =
      expressionValues.iterator.map((reference, value) => expressionPath(reference) -> value).toMap
    val captured = operations.toVector
    def inScope[A](operation: Operation)(body: => A): A =
      val previousModules = moduleStack.toVector
      val previousGeneration = generationStack.toVector
      moduleStack.clear(); moduleStack += records(operation.owner)
      generationStack.clear()
      def ancestors(region: GeneratedRegionRecord): Vector[GeneratedRegionRecord] =
        region.parentOrdinal.map(index =>
          ancestors(records(operation.owner).generatedRegions(index))
        )
          .getOrElse(Vector.empty) :+ region
      operation.generatedScope.foreach(region => generationStack ++= ancestors(region))
      try body
      finally
        moduleStack.clear(); moduleStack ++= previousModules
        generationStack.clear(); generationStack ++= previousGeneration
    captured.foreach: operation =>
      operation.values.foreach:
        case root: InstanceArrayRootRecord => inScope(operation):
            val graph = try
              StructuralParameterBinding.resolveDimensions(
                base,
                modulePath(operation.owner),
                instancePath(operation.owner, root.instanceOrdinal),
                declarationPath(root.port)
              )
            catch
              case error: IllegalArgumentException => fail("NODAL-SHAPE-043-007", error.getMessage)
            val values = mutable.HashMap.empty[Int, Dimension]
            def asExpression(value: Dimension): Expr[Integer] = value match
              case literal: Int => makeLiteral(literal.toLong)
              case expression: Expr[Integer] => expression
            def makeLiteral(value: Long): Expr[Integer] =
              val expression = new KernelExpr[Integer](
                Vector.empty,
                Some(KernelTypeDescriptor("Integer")),
                Some(KernelLiteral("integer", value.toString, KernelTypeDescriptor("Integer"))),
                Some("integer")
              )
              captureExpression(expression)
              expression
            def materialize(id: Int): Dimension = values.getOrElseUpdate(
              id,
              graph.existing.get(id).flatMap(path =>
                declarations.get(path).orElse(expressions.get(path))
              ) match
                case Some(value: Expr[?]) => value.asInstanceOf[Expr[Integer]]
                case _ =>
                  val node = graph.nodes(id)
                  node.literal match
                    case Some(value) => if value.isValidInt then value.toInt else makeLiteral(value)
                    case None => node.parameter match
                        case Some(path) => declarations.getOrElse(
                            path,
                            fail("NODAL-SHAPE-043-007", "substituted parent parameter is missing")
                          )
                            .asInstanceOf[Expr[Integer]]
                        case None =>
                          val expression = new KernelExpr[Integer](
                            node.operands.map(value => asExpression(materialize(value))),
                            Some(KernelTypeDescriptor("Integer")),
                            operation = node.operation
                          )
                          captureExpression(expression)
                          expression
            )
            root.dimensions = Some(graph.dimensions.map(materialize))
        case deferred: DeferredNodeArrayRecord => inScope(operation):
            deferred.projection match
              case Some(projection) => materializeNodeArrayProjection(
                  deferred.value.asInstanceOf[NodeArray[?]],
                  projection,
                  Some(deferred.reference)
                )
              case None => registerNodeSelection(
                  deferred.value.asInstanceOf[Node[?]],
                  deferred.input,
                  deferred.indices,
                  Some(deferred.reference)
                )
        case _ => ()

  private def finalizeConservativeArrayBindings(snapshot: ConstructionSnapshot): Unit =
    val dimensions = snapshot.modules.flatMap(_.declarations).map(value =>
      value.path -> value.conservativeDimensions
    ).toMap ++
      snapshot.instanceArrayRoots.map(value => value.path -> value.dimensions) ++
      snapshot.shapeViews.map(value => value.path -> value.dimensions) ++
      snapshot.shapeIndices.map(value => value.path -> value.resultDimensions)
    try
      snapshot.instanceArrayRoots.foreach(StructuralParameterBinding.validateRoot(snapshot, _))
      snapshot.conservativeArrayBindings.foreach: binding =>
        StructuralParameterBinding.validateRelation(
          snapshot,
          binding,
          dimensions.getOrElse(binding.left, Vector.empty),
          dimensions.getOrElse(binding.right, Vector.empty)
        )
    catch case error: IllegalArgumentException => fail("NODAL-SHAPE-043-007", error.getMessage)
    snapshot.modules.foreach: module =>
      module.instances.foreach: instance =>
        val child = snapshot.modules.find(_.path == instance.childModule).get
        child.declarations.filter(value =>
          conservativePortKinds.exists(_.label == value.kind) &&
            value.conservativeDimensions.nonEmpty
        ).foreach: port =>
          if !snapshot.instanceArrayRoots.exists(root =>
              root.instance == instance.path && root.port == port.path
            )
          then
            fail(
              "NODAL-HIERARCHY-043",
              "child array port requires complete incident coverage",
              Some(port.path)
            )

  private def topologyRecords(): Vector[(KernelTopologyEdge, Operation)] =
    val ordinals = mutable.HashMap.empty[Long, Int]
    val edges = operations.toVector.flatMap: operation =>
      if Set("node-connect", "terminal-connect", "inout-pass-through").contains(operation.kind) &&
        operation.values.size >= 2
      then
        (pathOf(operation.values(0)), pathOf(operation.values(1))) match
          case (Some(left), Some(right)) =>
            val (first, second) =
              if operation.kind == "node-connect" && right < left then right -> left
              else left -> right
            val ordinal = ordinals.getOrElse(operation.owner, 0)
            ordinals.update(operation.owner, ordinal + 1)
            val path = operation.generatedScope.map(_ =>
              s"${modulePath(operation.owner)}.connection_$ordinal"
            )
            Some(KernelTopologyEdge(
              modulePath(operation.owner),
              operation.kind,
              first,
              second,
              generatedScopeSnapshot(operation.generatedScope),
              path
            ) -> operation)
          case _ => None
      else None
    edges.sortBy((edge, _) => (edge.owner, edge.kind, edge.left, edge.right, edge.path))

  private def clockEdgeName(edge: ClockEdge): String = edge match
    case ClockEdge.Rising => "rising"
    case ClockEdge.Falling => "falling"

  private def resetPolicyName(policy: ResetPolicy): String = policy match
    case ResetPolicy.None => "none"
    case ResetPolicy.Sync => "sync"
    case ResetPolicy.Async => "async"
    case _: ResetPolicy.AsyncAssertSyncRelease => "async_assert_sync_release"

  private def resetPolicyAttributes(
      policy: ResetPolicy
  ): Vector[(String, String)] = policy match
    case ResetPolicy.AsyncAssertSyncRelease(stages) =>
      Vector("reset_stages" -> stages.toString)
    case _ => Vector.empty

  private def resetPolarityName(polarity: ResetPolarity): String = polarity match
    case ResetPolarity.ActiveHigh => "active_high"
    case ResetPolarity.ActiveLow => "active_low"

  private def relationAttributes(
      relation: ClockRelation
  ): Vector[(String, String)] = relation match
    case ClockRelation.Same => Vector("clock_relation" -> "alias")
    case ClockRelation.Ratio(multiply, divide, phase) =>
      Vector(
        "clock_relation" -> "ratio",
        "clock_multiply" -> multiply.toString,
        "clock_divide" -> divide.toString,
        "phase_degrees" -> java.lang.Double.toString(phase.degrees)
      )
    case ClockRelation.Synchronous(phaseKnown) =>
      Vector(
        "clock_relation" -> "synchronous",
        "clock_phase_known" -> phaseKnown.toString
      )
    case ClockRelation.MutuallyExclusive =>
      Vector("clock_relation" -> "mutually_exclusive")
    case ClockRelation.Asynchronous =>
      Vector("clock_relation" -> "asynchronous")
    case ClockRelation.Unknown => Vector("clock_relation" -> "unknown")

  private def domainSourcePath(value: AnyRef): String = selectedPath(value).getOrElse(
    fail("NODAL-DOMAIN-020", "ClockDomain source is outside this construction transaction")
  )

  private def snapshots(resolved: Map[DomainRef, String]): Vector[KernelModuleSnapshot] =
    records.values.toVector.sortBy(record => modulePath(record.handle)).map: module =>
      val domains = module.domains.toVector.map: domain =>
        val policyAttributes =
          domain.domain.resetPolicy.toVector.flatMap(resetPolicyAttributes)
        val metadata =
          domain.domain.resetPolarity
            .map(value => Vector("reset_polarity" -> resetPolarityName(value)))
            .getOrElse(Vector.empty) ++
            domain.domain.relation
              .map(relationAttributes)
              .getOrElse(Vector.empty) ++
            policyAttributes ++
            domain.domain.frequency.toVector.map(value =>
              "frequency_hz" -> java.lang.Double.toString(value.hertz)
            ) ++
            domain.domain.parent.toVector.map(value =>
              "clock_parent" -> domainPath(domainRef(value))
            ) ++
            domain.domain.clock.toVector.map(value => "clock_source" -> domainSourcePath(value)) ++
            Vector("reset_source" -> domainSourcePath(domain.domain.reset))
        KernelDomainSnapshot(
          domainPath(domain.reference),
          domainName(domain.reference),
          domain.kind.label,
          if domain.kind == KernelDomainKind.Required then resolved.get(domain.reference) else None,
          domain.domain.edge.map(clockEdgeName),
          domain.domain.resetPolicy.map(resetPolicyName),
          metadata.sortBy(_._1)
        )
      val declarations = module.declarations.toVector.map: declaration =>
        KernelDeclarationSnapshot(
          declarationPath(declaration.reference),
          declaration.kind.label,
          declarationName(declaration.reference),
          declaration.dataType.map(renderType(_, module.handle)),
          declarationDomain(declaration, resolved),
          declaration.attributes.map(value => value._1 -> renderAny(value._2, module.handle)),
          declaration.conservativeDimensions.map(renderShapeDimension(_, module.handle)),
          if declaration.kind == KernelSignalKind.AnalogNodeArray then
            generatedScopeSnapshot(module.generatedRegions.find(
              _.declarations.contains(declaration.reference)
            ))
          else None
        )
      val instances = module.instances.toVector.map: instance =>
        val child = records(instance.child)
        val bindings = child.domains.filter(_.kind == KernelDomainKind.Required).flatMap: domain =>
          resolved.get(domain.reference).map(domain.name -> _)
        val parameters = instance.parameterOverrides.toVector.map:
          case (parameter, value) =>
            val reference = parameter match
              case candidate: AnyRef =>
                Option(declarationIds.get(candidate)).getOrElse(
                  fail(
                    "NODAL-PARAMETER-BINDING-016",
                    "instance parameter override targets an unknown parameter",
                    Some(instancePath(module.handle, instance.ordinal))
                  )
                )
              case _ =>
                fail(
                  "NODAL-PARAMETER-BINDING-016",
                  "instance parameter override is not a declaration",
                  Some(instancePath(module.handle, instance.ordinal))
                )
            if reference.module != instance.child then
              fail(
                "NODAL-PARAMETER-BINDING-017",
                "instance parameter override targets another Module",
                Some(instancePath(module.handle, instance.ordinal))
              )
            declarationName(reference) -> renderAny(value, module.handle)
        KernelInstanceSnapshot(
          instancePath(module.handle, instance.ordinal),
          modulePath(instance.child),
          instance.lexicalDomain.flatMap(domain => resolved.get(domainRef(domain))),
          bindings.toVector.sortBy(_._1),
          parameters.sortBy(_._1),
          generatedScopeSnapshot(instance.generatedScope)
        )
      KernelModuleSnapshot(
        modulePath(module.handle),
        module.className,
        domains,
        declarations,
        instances
      )

  private def generatedBound(value: Any): String = value match
    case literal: Int => literal.toString
    case parameter: Param[?] =>
      Option(declarationIds.get(parameter)).map(declarationPath).getOrElse(
        fail("NODAL-ITERATION-043-001", "generated bound parameter has no semantic identity")
      )
    case expression: KernelExpr[?] if expression.literal.exists(_.kind == "integer") =>
      expression.literal.map(_.value).getOrElse(
        fail("NODAL-ITERATION-043-001", "generated integer literal has no value")
      )
    case expression: KernelExpr[?] =>
      Option(expressionIds.get(expression)).map(expressionPath).getOrElse(
        fail("NODAL-ITERATION-043-001", "generated expression has no captured semantic identity")
      )
    case _ =>
      fail("NODAL-ITERATION-043-001", "generated bound has no canonical symbolic representation")

  private def generatedRegionPath(record: GeneratedRegionRecord): String =
    val module = records(record.owner)
    val local = s"generate_${record.ordinal}"
    record.parentOrdinal match
      case None => s"${modulePath(record.owner)}.$local"
      case Some(parent) =>
        val parentRecord = module.generatedRegions(parent)
        s"${generatedRegionPath(parentRecord)}.$local"

  private def generatedSnapshots(): Vector[KernelGeneratedRegionSnapshot] =
    records.values.toVector
      .sortBy(record => modulePath(record.handle))
      .flatMap: module =>
        module.generatedRegions.toVector.map: region =>
          KernelGeneratedRegionSnapshot(
            path = generatedRegionPath(region),
            owner = modulePath(region.owner),
            parent = region.parentOrdinal.map(index =>
              generatedRegionPath(module.generatedRegions(index))
            ),
            induction = expressionPath(region.induction),
            lower = generatedBound(region.lower),
            upperExclusive = generatedBound(region.upperExclusive),
            step = generatedBound(region.step),
            maximum = region.maximum,
            maximumTripCount = region.maximumTripCount,
            declarations = region.declarations.toVector.map(declarationPath)
          )

  private def canonicalGeneratedOwnership(
      ownership: (String, String)
  ): (String, String) =
    val region = generatedOwnershipRecords.getOrElse(
      ownership,
      fail(
        "NODAL-ITERATION-043-004",
        "generated procedural ownership has no canonical region record",
        Some(ownership._1)
      )
    )
    generatedRegionPath(region) -> expressionPath(region.induction)

  private def shapeIndexSnapshots(): Vector[KernelShapeIndexSnapshot] =
    shapeIndices.values.toVector.map: index =>
      val inputPath = index.input match
        case reference: DeclarationRef => declarationPath(reference)
        case reference: ExpressionRef => pathOfReference(reference)
      KernelShapeIndexSnapshot(
        path = if index.conservative.nonEmpty then conservativeEndpointPath(index)
        else expressionPath(index.reference),
        owner = modulePath(index.reference.module),
        input = inputPath,
        indices = index.indices.map(generatedBound),
        resultDimensions =
          index.resultDimensions.map(renderShapeDimension(_, index.reference.module)),
        sourceUses = index.sourceUses.map(use => expressionPath(use._1)).sorted,
        generatedScope = generatedScopeSnapshot(index.generatedScope)
      )

  private def shapeViewSnapshots(): Vector[KernelShapeViewSnapshot] =
    shapeViews.values.toVector.map: view =>
      val inputPath = view.input match
        case reference: DeclarationRef => declarationPath(reference)
        case reference: ExpressionRef => pathOfReference(reference)
      KernelShapeViewSnapshot(
        path = expressionPath(view.reference),
        owner = modulePath(view.reference.module),
        input = inputPath,
        dimensions = view.dimensions.map(renderShapeDimension(_, view.reference.module)),
        slice = view.slice.map(selection =>
          KernelShapeSliceSnapshot(
            selection.axis,
            generatedBound(selection.lower),
            generatedBound(selection.upperExclusive)
          )
        ),
        generatedScope = generatedScopeSnapshot(view.generatedScope)
      )

  private def relationName(relation: ClockRelation): String =
    relationAttributes(relation).collectFirst:
      case ("clock_relation", value) => value
    .getOrElse("unknown")

  private def waiverSnapshots(
      sourceMap: Vector[SourceMapEntry]
  ): Vector[KernelWaiverSnapshot] =
    val sourceByPath = sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
    expressionValues.toVector.flatMap:
      case (reference, expression) =>
        expression.operands.collectFirst:
          case waiver: CdcWaiver =>
            val path = expressionPath(reference)
            KernelWaiverSnapshot(
              "cdc",
              waiver.id,
              waiver.reason,
              relationName(waiver.relation),
              path,
              expression.operands.headOption.flatMap(pathOf),
              expression.operands.collectFirst:
                case domain: ClockDomain => domainPath(domainRef(domain)),
              sourceByPath.get(path)
            )
    .sortBy(waiver => (waiver.semanticPath, waiver.id))

  private def continuousOperatorSnapshots(
      sourceMap: Vector[SourceMapEntry]
  ): Vector[KernelContinuousOperatorSnapshot] =
    val sourceByPath = sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
    val allAnalyses = Vector(
      "ac",
      "dc",
      "initialization",
      "noise",
      "operating-point",
      "transient"
    )
    continuousOperators.toVector.map: record =>
      val path = expressionPath(record.reference)
      val owner = modulePath(record.reference.module)
      val generatedOwnership = analogRegions.find(_.expressions.contains(record.reference))
        .flatMap(_.generatedOwnership)
        .map(canonicalGeneratedOwnership)
      val stateId =
        if record.operation == "analog_idt" then Some(s"$path.state") else None
      val initialization =
        if record.operation == "analog_ddt" then "none"
        else if record.initialCondition.nonEmpty then "fixed"
        else "solver-selected"
      KernelContinuousOperatorSnapshot(
        path = path,
        operation = record.operation,
        owner = owner,
        context = record.context,
        input = pathOf(record.input).getOrElse(
          fail(
            "NODAL-ANALOG-035-002",
            "continuous-time operator input has no semantic path",
            Some(path)
          )
        ),
        initialCondition = record.initialCondition.map: initial =>
          pathOf(initial).getOrElse(
            fail(
              "NODAL-ANALOG-035-002",
              "idt initial condition has no semantic path",
              Some(path)
            )
          ),
        inputDimension = record.inputDimension.signature,
        resultDimension = record.resultDimension.signature,
        stateId = stateId,
        initialization = initialization,
        analyses = allAnalyses,
        source = sourceByPath.get(path),
        generatedOwner = generatedOwnership.map(_._1),
        generatedInduction = generatedOwnership.map(_._2)
      )
    .sortBy(_.path)

  private def transferOperatorSnapshots(sourceMap: Vector[SourceMapEntry])
      : Vector[KernelTransferOperatorSnapshot] =
    val sources = sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
    transferOperators.toVector.map: (reference, kind, n, d, inputs, dimension) =>
      val path = expressionPath(reference)
      val generatedOwnership = analogRegions.find(_.expressions.contains(reference))
        .flatMap(_.generatedOwnership)
        .map(canonicalGeneratedOwnership)
      KernelTransferOperatorSnapshot(
        path,
        kind,
        modulePath(reference.module),
        n,
        d,
        inputs.map(input =>
          pathOf(input).getOrElse(
            AnalogTransferContract.fail(2, "transfer input has no semantic path")
          )
        ),
        dimension,
        sources.get(path),
        generatedOwnership.map(_._1),
        generatedOwnership.map(_._2)
      )
    .sortBy(_.path)

  private def noiseOperatorSnapshots(sourceMap: Vector[SourceMapEntry])
      : Vector[KernelNoiseOperatorSnapshot] =
    val sources = sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
    noiseOperators.toVector
      .sortBy: (reference, _, _, _, _) =>
        modulePath(reference.module) -> reference.index
      .map: (reference, kind, label, inputs, dimension) =>
        val path = expressionPath(reference)
        val generatedOwnership = analogRegions.find(_.expressions.contains(reference))
          .flatMap(_.generatedOwnership)
          .map(canonicalGeneratedOwnership)
        KernelNoiseOperatorSnapshot(
          path,
          kind,
          modulePath(reference.module),
          label,
          inputs.map(input =>
            pathOf(input).getOrElse(
              AnalogNoiseContract.fail(2, "noise input has no semantic path")
            )
          ),
          dimension,
          sources.get(path),
          generatedOwnership.map(_._1),
          generatedOwnership.map(_._2)
        )

  private def waveformOperatorSnapshots(
      sourceMap: Vector[SourceMapEntry]
  ): Vector[KernelWaveformOperatorSnapshot] =
    val sources = sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
    waveformOperators.toVector.map:
      case (
            reference,
            operation,
            inputs,
            context,
            dimensions,
            result,
            inputContinuity,
            outputContinuity
          ) =>
        val path = expressionPath(reference)
        val state = Set("analog_transition", "analog_slew", "analog_absdelay").contains(operation)
        val generatedOwnership = analogRegions.find(_.expressions.contains(reference))
          .flatMap(_.generatedOwnership)
          .map(canonicalGeneratedOwnership)
        KernelWaveformOperatorSnapshot(
          path,
          operation,
          modulePath(reference.module),
          context,
          inputs.map(input =>
            pathOf(input).getOrElse(
              fail("NODAL-ANALOG-036-002", "waveform input has no semantic path", Some(path))
            )
          ),
          dimensions,
          result,
          inputContinuity,
          outputContinuity,
          if state then Some(s"$path.state") else None,
          if operation == "analog_bound_step" then Vector("transient")
          else Vector("ac", "dc", "initialization", "noise", "operating-point", "transient"),
          sources.get(path),
          generatedOwnership.map(_._1),
          generatedOwnership.map(_._2)
        )
    .sortBy(_.path)

  private def structuralShapeExpressionRoots: Iterator[Any] =
    shapeIndices.valuesIterator.flatMap(index =>
      index.indices.iterator ++ index.resultDimensions.iterator ++
        index.sourceUses.iterator.flatMap(_._2.iterator)
    ) ++ operations.iterator.flatMap(_.values.collect { case root: InstanceArrayRootRecord =>
      root
    })
      .flatMap(_.dimensions.iterator.flatten) ++ shapeViews.valuesIterator.flatMap(view =>
      view.dimensions.iterator ++ view.slice.iterator.flatMap(selection =>
        Iterator(selection.lower, selection.upperExclusive)
      )
    )

  private def analogSnapshots(): Vector[KernelAnalogRegionSnapshot] =
    // Endpoint selectors are structural uses even when authored lexically inside analog.
    // Keep a shared expression in the runtime inventory if any runtime consumer needs it.
    val structural = capturedExpressionClosure(
      structuralShapeExpressionRoots
    )
    analogRegions.toVector.sortBy(region => (modulePath(region.module), region.ordinal)).map:
      region =>
        val generatedOwnership = region.generatedOwnership.map(canonicalGeneratedOwnership)
        val runtimeRoots = region.expressions.iterator.filterNot(structural.contains)
          .flatMap(expressionValues.get) ++ region.contributions.iterator.flatMap:
          (target, value) => Iterator(target, value)
        val runtime = if structural.isEmpty then Set.empty[ExpressionRef]
        else capturedExpressionClosure(runtimeRoots)
        val expressions = region.expressions.distinct.toVector
          .filter(reference => !structural.contains(reference) || runtime.contains(reference))
          .map: reference =>
            val expression = expressionValues.getOrElse(
              reference,
              fail(
                "NODAL-ANALOG-SNAPSHOT-001",
                "analog expression reference has no captured value",
                Some(expressionPath(reference))
              )
            )
            val operandPaths =
              if expression.literal.nonEmpty then Vector.empty
              else
                expression.operands.toVector.map: operand =>
                  pathOf(operand).getOrElse(
                    fail(
                      "NODAL-ANALOG-SNAPSHOT-002",
                      s"analog expression operand '${renderAny(operand, reference.module)}' has no semantic path",
                      Some(expressionPath(reference))
                    )
                  )
            KernelAnalogExpressionSnapshot(
              expressionPath(reference),
              expression.operation.getOrElse("unsupported_generic"),
              operandPaths,
              expression.literal.map(_.value),
              expression.operands.lift(1).collect { case unit: String => unit }
            )
        val byPath = expressions.map(expression => expression.path -> expression).toMap
        val contributions = region.contributions.toVector.zipWithIndex.map:
          case ((target, value), index) =>
            val targetPath = pathOf(target).getOrElse(
              fail("NODAL-ANALOG-SNAPSHOT-003", "contribution target has no semantic path")
            )
            val valuePath = pathOf(value).getOrElse(
              fail("NODAL-ANALOG-SNAPSHOT-004", "contribution value has no semantic path")
            )
            val kind = byPath.get(targetPath).map(_.operation) match
              case Some("potential_access") => "potential"
              case Some("flow_access") => "flow"
              case _ =>
                fail(
                  "NODAL-ANALOG-SNAPSHOT-005",
                  "contribution target must be V(...) or I(...) access",
                  Some(targetPath)
                )
            KernelAnalogContributionSnapshot(
              s"${modulePath(region.module)}.analog_${region.ordinal}.contribution_$index",
              targetPath,
              valuePath,
              kind,
              generatedOwnership.map(_._1),
              generatedOwnership.map(_._2)
            )
        KernelAnalogRegionSnapshot(
          s"${modulePath(region.module)}.analog_${region.ordinal}",
          modulePath(region.module),
          expressions,
          contributions,
          generatedOwnership.map(_._1),
          generatedOwnership.map(_._2)
        )

  private def capturedExpressionClosure(roots: Iterator[Any]): Set[ExpressionRef] =
    val reachable = mutable.LinkedHashSet.empty[ExpressionRef]
    val visited = new IdentityHashMap[AnyRef, java.lang.Boolean]()

    def visit(value: Any): Unit = value match
      case expression: KernelExpr[?] =>
        if Option(visited.put(expression, java.lang.Boolean.TRUE)).isEmpty then
          val reference = Option(expressionIds.get(expression)).getOrElse(
            fail(
              "NODAL-PARAMETER-BINDING-021",
              "static parameter expression has no captured semantic identity"
            )
          )
          reachable += reference
          if expression.literal.isEmpty then expression.operands.foreach(visit)
      case _: Param[?] => ()
      case _ => ()

    roots.foreach(visit)
    reachable.toSet

  private def parameterExpressionSnapshots(): Vector[KernelParameterExpressionSnapshot] =
    val roots = rootParameterBindings.iterator.map(_._2) ++ records.valuesIterator.flatMap(
      _.instances.iterator.flatMap(_.parameterOverrides.iterator.map(_._2))
    )
    val defaultRoots = records.valuesIterator.flatMap(_.declarations.iterator.filter(
      _.kind == KernelSignalKind.Parameter
    ).flatMap(_.attributes.collect { case ("default", value) => value }))
    val generatedRoots = records.valuesIterator.flatMap(
      _.generatedRegions.iterator.flatMap(region =>
        Iterator(region.lower, region.upperExclusive, region.step)
      )
    )
    def shapeDimensionRoots(dataType: DataType[?]): Vector[Any] =
      val descriptor = CandidateRuntime.typeDescriptor(dataType)
      if descriptor.kind != "Vec" then Vector.empty
      else
        val nested = descriptor.arguments.headOption.collect:
          case element: DataType[?] => shapeDimensionRoots(element)
        val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
          case values: Seq[?] => values.toVector
          case _ => Vector.empty
        dimensions ++ nested.getOrElse(Vector.empty)

    val shapeDimensionExpressionRoots = records.valuesIterator.flatMap(
      _.declarations.iterator.flatMap(declaration =>
        declaration.dataType.iterator.flatMap(shapeDimensionRoots) ++
          declaration.conservativeDimensions.iterator
      )
    )
    val lexicalShapeRoots = expressionValues.valuesIterator
      .filter(CandidateRuntime.proceduralShapeSource(_)).flatMap: expression =>
        val dimensions =
          CandidateRuntime.expressionDataType(expression).toVector.flatMap(shapeDimensionRoots)
        dimensions.iterator ++
          (if expression.operation.contains("analog_array_fill") then Iterator.empty
           else expression.operands.drop(1).iterator)
    val reachable = capturedExpressionClosure(
      (lexicalShapeRoots ++ roots ++ defaultRoots ++ generatedRoots ++ structuralShapeExpressionRoots ++
        shapeDimensionExpressionRoots).collect:
        case expression: KernelExpr[?]
            if expression.literal.isEmpty && !expression.operation.contains("generate_index") =>
          expression
    )

    expressionValues.toVector.collect:
      case (reference, expression) if reachable.contains(reference) =>
        if expression.operation.exists(operation =>
            operation.startsWith(AnalogContextContract.Prefix) ||
              operation.startsWith(
                AnalogFunctionRegistry.AnalysisPrefix
              ) || operation == "analog_abstime"
          )
        then
          AnalogContextContract.fail(
            4,
            "static parameter and shape expressions cannot read simulator context"
          )
        val path = expressionPath(reference)
        val dataType = CandidateRuntime.expressionDataType(expression)
          .map(renderType(_, reference.module))
          .getOrElse(
            fail(
              "NODAL-PARAMETER-BINDING-022",
              "static parameter expression type is unavailable",
              Some(path)
            )
          )
        val operands =
          if expression.literal.nonEmpty then Vector.empty
          else
            expression.operands.map: operand =>
              pathOf(operand).getOrElse(
                fail(
                  "NODAL-PARAMETER-BINDING-023",
                  "static parameter expression operand has no semantic identity",
                  Some(path)
                )
              )
        KernelParameterExpressionSnapshot(
          path,
          modulePath(reference.module),
          expression.operation.orElse(expression.literal.map(_.kind)).getOrElse(
            fail(
              "NODAL-PARAMETER-BINDING-024",
              "static parameter expression operation is unavailable",
              Some(path)
            )
          ),
          operands,
          dataType,
          expression.literal.map(_.value),
          expression.operands.lift(1).collect { case unit: String => unit }.filter(_.nonEmpty)
        )

  private def classify(snapshot: ConstructionSnapshot): DesignKind =
    val kinds = snapshot.modules.flatMap(_.declarations.map(_.kind)).toSet
    val analogKinds = Set(
      "analog-input",
      "analog-output",
      "analog-inout",
      "analog-node",
      "analog-node-array",
      "conservative-terminal",
      "analog-signal"
    )
    val analog =
      kinds.exists(analogKinds.contains) || snapshot.continuousOperators.nonEmpty ||
        snapshot.waveformOperators.nonEmpty || snapshot.noiseOperators.nonEmpty ||
        snapshot.transferOperators.nonEmpty || snapshot.analogFunctions.nonEmpty ||
        snapshot.analogRegions.nonEmpty ||
        snapshot.analogProcedural.nonEmpty || snapshot.analogSemantics.equations.nonEmpty ||
        snapshot.analogSemantics.contributions.nonEmpty || snapshot.analogSemantics.states.nonEmpty ||
        snapshot.analogSemantics.components.nonEmpty
    val storageKinds = if analog then Set("parameter", "variable") else Set.empty[String]
    val digital = (kinds -- analogKinds -- storageKinds).nonEmpty ||
      snapshot.interfaceAbi.nonEmpty ||
      snapshot.resolvedNets.nonEmpty
    (digital, analog) match
      case (true, true) => DesignKind.MixedSignal
      case (true, false) => DesignKind.DigitalOnly
      case (false, true) => DesignKind.AnalogOnly
      case _ => DesignKind.Unsupported

  def finish(root: Module): (Emission, ConstructionSnapshot) =
    constructorFailure.foreach: (failure, site) =>
      fail(
        "NODAL-CONSTRUCTOR-LIFECYCLE-021",
        s"captured constructor failure invalidated this transaction: ${failure.getClass.getName}",
        Some(site)
      )
    val rootHandle = moduleHandle(root)
    if moduleStack.size != 1 || moduleStack.last.handle != rootHandle then
      fail("NODAL-LIFECYCLE-017", "construction closed with an unattached child Module")
    moduleStack.remove(moduleStack.size - 1)
    if domainStack.nonEmpty then
      fail("NODAL-LIFECYCLE-018", "construction closed with a lexical domain still active")
    records.values.filterNot(_.attached).foreach: module =>
      fail("NODAL-HIERARCHY-021", s"Module '${module.className}' was not attached")

    semanticResult = Some(semanticOrigin.resolve())
    val resolved = resolveDomains()
    markConservativeBindingDependencies()
    if operations.exists(_.kind == "instance-node-array") then
      val base = ConstructionSnapshot(
        root = modulePath(rootHandle),
        modules = snapshots(resolved),
        parameterExpressions = parameterExpressionSnapshots(),
        interfaceAbi = Vector.empty,
        resolvedNets = Vector.empty,
        topology = Vector.empty
      )
      materializeConservativeIntents(base)
    val semantic = semanticOrigin.resolve()
    semanticResult = Some(semantic)
    val modules = snapshots(resolved)
    val sourceByPath = semantic.sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
    val rootSources = operations.iterator.flatMap(_.values.collect {
      case root: InstanceArrayRootRecord => root
    })
      .flatMap(root =>
        sourceByPath.get(expressionPath(root.reference)).map(source =>
          SourceMapEntry(pathOfReference(root.reference), source)
        )
      ).toVector
    val endpointSources = shapeIndices.valuesIterator.filter(_.conservative.nonEmpty).flatMap:
      index =>
        sourceByPath.get(expressionPath(index.sourceUses.head._1)).map(source =>
          SourceMapEntry(conservativeEndpointPath(index), source)
        )
    val abi = interfaceAbi(resolved)
    val arrayBindings = conservativeArrayBindingRecords
    val topologyEdges = topologyRecords()
    val recordedAnalogSemantics = analogSemanticSnapshot()
    val analogSemantics =
      if recordedAnalogSemantics.components.isEmpty && recordedAnalogSemantics.states.isEmpty then
        recordedAnalogSemantics
      else
        recordedAnalogSemantics.copy(
          parameters = records.valuesIterator.flatMap: module =>
            module.declarations.iterator.collect:
              case declaration if declaration.value.isInstanceOf[Param[?]] =>
                val parameter = declaration.value.asInstanceOf[Param[?]]
                AnalogEquationRuntime.ParameterReference(
                  parameter,
                  modulePath(module.handle),
                  declarationPath(declaration.reference),
                  inferAnalogDimension(parameter).signature
                )
          .toVector.sortBy(_.path)
        )
    val snapshot = ConstructionSnapshot(
      root = modulePath(rootHandle),
      rootParameterBindings = rootParameterBindings.toVector.map:
        case (parameter, value) =>
          val reference = parameter match
            case candidate: AnyRef => Option(declarationIds.get(candidate)).getOrElse(
                fail(
                  "NODAL-PARAMETER-BINDING-016",
                  "root constructor binding targets an unknown parameter"
                )
              )
            case _ =>
              fail(
                "NODAL-PARAMETER-BINDING-016",
                "root constructor binding is not a declaration"
              )
          declarationName(reference) -> renderAny(value, rootHandle),
      parameterExpressions = parameterExpressionSnapshots(),
      modules = modules,
      interfaceAbi = abi,
      resolvedNets = resolvedNets(),
      topology = topologyEdges.map(_._1),
      names = semantic.names,
      origins = semantic.origins,
      generatedNames = semantic.generatedNames,
      sourceMap =
        (semantic.sourceMap ++ rootSources ++ endpointSources ++ topologyEdges.flatMap(
          (edge, record) =>
            edge.path.flatMap(path => record.source.map(SourceMapEntry(path, _)))
        ) ++ arrayBindings.flatMap((binding, record) =>
          record.source.map(SourceMapEntry(binding.path, _))
        ) ++ AnalogUserFunctionRuntime.sourceMap(
          userFunctions.snapshots(modulePath)
        ) ++ AnalogEquationRuntime.sourceMap(analogSemantics)).sortBy(_.semanticPath),
      analogRegions = analogSnapshots(),
      continuousOperators = continuousOperatorSnapshots(semantic.sourceMap),
      waveformOperators = waveformOperatorSnapshots(semantic.sourceMap),
      noiseOperators = noiseOperatorSnapshots(semantic.sourceMap),
      transferOperators = transferOperatorSnapshots(semantic.sourceMap),
      analogFunctions = userFunctions.snapshots(modulePath),
      analogSemantics = analogSemantics,
      analogProcedural = AnalogProceduralConstruction.snapshots(
        module => modulePath(moduleHandle(module)),
        canonicalGeneratedOwnership
      ),
      waivers = waiverSnapshots(semantic.sourceMap),
      generatedRegions = generatedSnapshots(),
      shapeIndices = shapeIndexSnapshots(),
      shapeViews = shapeViewSnapshots(),
      conservativeArrayBindings = arrayBindings.map(_._1),
      instanceArrayRoots = instanceArrayRootSnapshots()
    )
    finalizeConservativeArrayBindings(snapshot)
    val kind = classify(snapshot)
    val report = DesignReport(
      designKind = kind,
      selectedBackend = options.backend,
      digitalProfile =
        if kind == DesignKind.AnalogOnly || kind == DesignKind.Unsupported then None
        else Some(options.digitalProfile),
      interfaceAbi = abi,
      sourceMap = snapshot.sourceMap,
      schedules = Vector.empty
    )
    Emission(Vector.empty, report) -> snapshot
