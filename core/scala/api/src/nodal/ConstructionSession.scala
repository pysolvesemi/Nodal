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
  private val shapeIndices: mutable.ArrayBuffer[ShapeIndexRecord] = mutable.ArrayBuffer.empty
  private val shapeViews: mutable.ArrayBuffer[ShapeViewRecord] = mutable.ArrayBuffer.empty
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

  def requireNoGeneratedEffect(role: String): Unit =
    generationStack.lastOption.foreach: region =>
      fail(
        "NODAL-ITERATION-043-004",
        s"$role is not permitted inside structural hdlRange until generated ownership and target lowering are available",
        Some(generatedRegionPath(region))
      )

  private def moduleHandle(module: Module): Long =
    Option(moduleIds.get(module)).map(_.longValue).getOrElse(
      fail("NODAL-OWNERSHIP-017", "Module is outside this construction transaction")
    )

  private def domainRef(domain: ClockDomain): DomainRef =
    Option(domainIds.get(domain)).getOrElse(
      fail("NODAL-DOMAIN-020", "ClockDomain is outside this construction transaction")
    )

  def beginModule(module: Module): Unit =
    requireNoGeneratedEffect("child Module construction")
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

  def registerDeclaration(
      value: AnyRef,
      kind: KernelSignalKind,
      dataType: Option[DataType[? <: Data]],
      explicitName: Option[String],
      domain: Option[ClockDomain],
      attributes: Vector[(String, Any)]
  ): Unit =
    if kind != KernelSignalKind.AnalogNode then
      requireNoGeneratedEffect(s"generated ${kind.label} declaration")
    val module = currentModule
    dataType.foreach(validateShapeType(_, module.handle))
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
      attributes
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

  private def validateShapeType(dataType: DataType[?], owner: Long): Unit =
    val descriptor = CandidateRuntime.typeDescriptor(dataType)
    if descriptor.kind == "Vec" then validateShapeDescriptor(descriptor, owner)

  private def validateShapeDescriptor(descriptor: KernelTypeDescriptor, owner: Long): Unit =
    val element = descriptor.arguments.headOption.collect:
      case candidate: DataType[?] => candidate
    val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
      case values: Seq[?] => values.toVector
      case _ => Vector.empty
    if element.isEmpty || dimensions.isEmpty then
      fail("NODAL-SHAPE-043-001", "Vec requires an element type and at least one dimension")

    dimensions.foreach:
      case value: Int if value > 0 => ()
      case _: Int =>
        fail("NODAL-SHAPE-043-001", "Vec dimensions must be positive")
      case parameter: Param[?] =>
        val reference = Option(declarationIds.get(parameter)).getOrElse(
          fail("NODAL-SHAPE-043-001", "symbolic Vec dimension has no parameter identity")
        )
        if reference.module != owner then
          fail(
            "NODAL-SHAPE-043-001",
            "symbolic Vec dimension must be owned by its declaring Module",
            Some(declarationPath(reference))
          )
        val declaration = records(owner).declarations(reference.index)
        val range =
          for
            lower <- structuralRangeAttribute(declaration, "integer_range_lower")
            upper <- structuralRangeAttribute(declaration, "integer_range_upper")
          yield lower -> upper
        if declaration.kind != KernelSignalKind.Parameter ||
          !declaration.dataType.map(renderType(_, owner)).contains("Integer") ||
          range.forall((lower, upper) => lower <= 0 || lower > upper)
        then
          fail(
            "NODAL-SHAPE-043-001",
            "symbolic Vec dimension requires a positive finite Integer parameter range",
            Some(declarationPath(reference))
          )
        markStructuralParameterEffect(reference, "shape")
        markStructuralParameterEffect(reference, "rank")
      case expression: KernelExpr[?]
          if expression.literal.exists(value =>
            value.kind == "integer" && value.value.toIntOption.exists(_ > 0)
          ) => ()
      case _ =>
        fail(
          "NODAL-SHAPE-043-001",
          "Vec dimensions currently require a positive literal or directly bounded Integer parameter"
        )

    validateShapeType(element.get, owner)

  private def minimumShapeExtent(value: Any, owner: Long): Int = value match
    case literal: Int if literal > 0 => literal
    case parameter: Param[?] =>
      val reference = Option(declarationIds.get(parameter)).getOrElse(
        fail("NODAL-SHAPE-043-002", "symbolic shape extent has no parameter identity")
      )
      if reference.module != owner then
        fail(
          "NODAL-SHAPE-043-002",
          "symbolic shape extent escapes its owning Module",
          Some(declarationPath(reference))
        )
      val declaration = records(owner).declarations(reference.index)
      structuralRangeAttribute(declaration, "integer_range_lower")
        .filter(_ > 0)
        .getOrElse(
          fail(
            "NODAL-SHAPE-043-002",
            "symbolic shape extent has no positive finite minimum",
            Some(declarationPath(reference))
          )
        )
    case expression: KernelExpr[?] if expression.literal.exists(_.kind == "integer") =>
      expression.literal
        .flatMap(_.value.toIntOption)
        .filter(_ > 0)
        .getOrElse(
          fail("NODAL-SHAPE-043-002", "literal shape extent must be positive")
        )
    case _ =>
      fail("NODAL-SHAPE-043-002", "shape extent has no proven finite minimum")

  def registerShapeIndex[A <: Data](
      expression: KernelExpr[A],
      input: Expr[Vec[A]],
      indices: Vector[Dimension]
  ): Unit =
    val module = currentModule
    val inputReference = input match
      case candidate: AnyRef => Option(declarationIds.get(candidate)).getOrElse(
          fail("NODAL-SHAPE-043-002", "indexed value has no declaration identity")
        )
    if inputReference.module != module.handle then
      fail(
        "NODAL-SHAPE-043-002",
        "indexed value must be owned by the active Module",
        Some(declarationPath(inputReference))
      )
    val declaration = records(inputReference.module).declarations(inputReference.index)
    if declaration.kind != KernelSignalKind.Input && declaration.kind != KernelSignalKind.Output
    then
      // Preserve the previously exposed inert expression surface until shaped
      // internal storage has an owned SSA carrier and lowering contract.
      val _ = captureExpression(expression)
    else
      val descriptor = declaration.dataType
        .map(CandidateRuntime.typeDescriptor)
        .filter(_.kind == "Vec")
        .getOrElse(
          fail(
            "NODAL-SHAPE-043-002",
            "indexed declaration must have Vec type",
            Some(declarationPath(inputReference))
          )
        )
      val dimensions = descriptor.arguments.lift(1).toVector.flatMap:
        case values: Seq[?] => values.toVector
        case _ => Vector.empty
      if indices.size != dimensions.size then
        fail(
          "NODAL-SHAPE-043-002",
          "index rank does not match shaped rank",
          Some(declarationPath(inputReference))
        )
      val analysis = new StructuralBoundAnalysis(
        module.handle,
        "NODAL-SHAPE-043-002",
        "shape index"
      )
      val positions = indices.map(value => value -> analysis(value))
      positions.zip(dimensions).zipWithIndex.foreach:
        case (((_, position), dimension), axis) =>
          val minimum = minimumShapeExtent(dimension, module.handle)
          if position.bounds.lower < 0L || position.bounds.upper >= minimum.toLong then
            fail(
              "NODAL-SHAPE-043-002",
              s"index is not in bounds for every legal shape at axis $axis",
              Some(declarationPath(inputReference))
            )
          position.parameters.foreach(markStructuralParameterEffect(_, "shape"))
      val reference = captureExpression(expression).getOrElse(
        fail("NODAL-SHAPE-043-002", "shape index requires an active Module")
      )
      shapeIndices += ShapeIndexRecord(reference, inputReference, positions.map(_._1))

  private final case class ShapeViewFactor(literal: Option[Long], symbol: Option[String], maximum: Long)

  private def shapeViewFactor(value: Any, owner: Long, path: String): ShapeViewFactor = value match
    case literal: Int if literal > 0 => ShapeViewFactor(Some(literal.toLong), None, literal.toLong)
    case expression: KernelExpr[?] if expression.literal.exists(_.kind == "integer") =>
      val value = expression.literal.flatMap(_.value.toLongOption).filter(_ > 0).getOrElse(
        fail("NODAL-SHAPE-043-003", "fixed shape-view dimensions must be positive", Some(path))
      )
      ShapeViewFactor(Some(value), None, value)
    case parameter: Param[?] =>
      val reference = Option(declarationIds.get(parameter)).getOrElse(
        fail("NODAL-SHAPE-043-003", "symbolic shape-view dimension has no declaration identity", Some(path))
      )
      if reference.module != owner then
        fail("NODAL-SHAPE-043-003", "symbolic shape-view dimension escapes its owning Module", Some(declarationPath(reference)))
      val (minimum, maximum) = parameter.integerRange.getOrElse(
        fail("NODAL-SHAPE-043-003", "symbolic shape-view dimension requires a bounded integer parameter", Some(declarationPath(reference)))
      )
      if minimum <= 0 || maximum < minimum then
        fail("NODAL-SHAPE-043-003", "symbolic shape-view dimension requires a positive finite parameter range", Some(declarationPath(reference)))
      ShapeViewFactor(None, Some(declarationName(reference)), maximum.toLong)
    case _ =>
      fail("NODAL-SHAPE-043-003", "shape-view dimensions require positive literals or bounded integer parameters", Some(path))

  private def shapeViewSignature(
      dimensions: Vector[Dimension],
      owner: Long,
      path: String
  ): (BigInt, Vector[String], BigInt, Vector[String]) =
    val factors = dimensions.map(shapeViewFactor(_, owner, path))
    if factors.isEmpty then
      fail("NODAL-SHAPE-043-003", "fixed shape view requires at least one result dimension", Some(path))
    val literalProduct = factors.flatMap(_.literal).foldLeft(BigInt(1))(_ * _)
    val symbols = factors.flatMap(_.symbol).sorted
    val worstCase = factors.foldLeft(BigInt(1))((product, factor) => product * BigInt(factor.maximum))
    (literalProduct, symbols, worstCase, factors.map(f => f.symbol.getOrElse(f.literal.get.toString)))

  def registerShapeView[A <: Data](
      expression: KernelExpr[Vec[A]],
      input: Expr[Vec[A]],
      dimensions: Vector[Dimension]
  ): Unit =
    val module = currentModule
    val inputReference = input match
      case candidate: AnyRef => Option(declarationIds.get(candidate)).getOrElse(
          fail("NODAL-SHAPE-043-003", "shape-view value has no declaration identity")
        )
    if inputReference.module != module.handle then
      fail(
        "NODAL-SHAPE-043-003",
        "shape-view value must be owned by the active Module",
        Some(declarationPath(inputReference))
      )
    val declaration = records(inputReference.module).declarations(inputReference.index)
    if declaration.kind != KernelSignalKind.Input && declaration.kind != KernelSignalKind.Output
    then
      val _ = captureExpression(expression)
    else
      val descriptor = declaration.dataType
        .map(CandidateRuntime.typeDescriptor)
        .filter(_.kind == "Vec")
        .getOrElse(
          fail(
            "NODAL-SHAPE-043-003",
            "shape-view declaration must have Vec type",
            Some(declarationPath(inputReference))
          )
        )
      val sourceDimensions = descriptor.arguments.lift(1).toVector.flatMap:
        case values: Seq[?] =>
          values.toVector.map:
            case value: Int => value
            case value: Expr[Integer] => value
            case _ =>
              fail(
                "NODAL-SHAPE-043-003",
                "Vec dimension is not a supported fixed or bounded symbolic factor",
                Some(path)
              )
        case _ => Vector.empty
      val path = declarationPath(inputReference)
      val (sourceLiteralProduct, sourceSymbols, sourceWorstCase, _) =
        shapeViewSignature(sourceDimensions, module.handle, path)
      val (targetLiteralProduct, targetSymbols, targetWorstCase, targetRendered) =
        shapeViewSignature(dimensions, module.handle, path)
      if sourceLiteralProduct != targetLiteralProduct || sourceSymbols != targetSymbols ||
        sourceWorstCase > BigInt(Long.MaxValue) || targetWorstCase > BigInt(Long.MaxValue)
      then
        fail(
          "NODAL-SHAPE-043-003",
          "shape view requires equal literal products and canonical symbolic parameter multisets within signed 64-bit bounds",
          Some(path)
        )
      val reference = captureExpression(expression).getOrElse(
        fail("NODAL-SHAPE-043-003", "shape view requires an active Module")
      )
      shapeViews += ShapeViewRecord(reference, inputReference, targetRendered)

  private def captureExpression(value: AnyRef): Option[ExpressionRef] =
    moduleStack.lastOption.map: module =>
      val reference = ExpressionRef(module.handle, module.expressionCount)
      module.expressionCount += 1
      expressionIds.put(value, reference)
      val operands = value match
        case expression: KernelExpr[?] =>
          expressionValues.update(reference, expression)
          analogStack.lastOption.foreach(_.expressions += reference)
          expression.operands
        case _ => Vector.empty
      semanticOrigin.captureExpression(module.handle, reference.index, value, operands)
      reference

  def registerExpression(value: AnyRef): Unit =
    val _ = captureExpression(value)

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
      captured
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
        if !record.captured || captured then
          fail("NODAL-HIERARCHY-017", "child Module was attached twice")
        (existing.asInstanceOf[Instance[M]], false)
      case None =>
        val created = new Instance(module)
        attachInstance(created, module, captured)
        (created, true)

  private def instanceRecord(instance: AnyRef): InstanceRecord =
    Option(instanceIds.get(instance)).getOrElse(
      fail("NODAL-BINDING-016", "binding targets an unknown Instance")
    )

  def bindDefault(instance: AnyRef, domain: ClockDomain): Unit =
    instanceRecord(instance).defaultBinding = Some(domain)

  def bindNamed(instance: AnyRef, requirement: ClockDomain, domain: ClockDomain): Unit =
    val record = instanceRecord(instance)
    val requirementReference = domainRef(requirement)
    if requirementReference.module != record.child then
      fail("NODAL-BINDING-019", "selector domain does not belong to the child Instance")
    record.namedBindings += requirement -> domain

  def connectNodes(left: AnyRef, right: AnyRef): Unit =
    requireNoGeneratedEffect("generated conservative connection")
    val parent = currentModule
    val portKinds = Set(
      KernelSignalKind.AnalogInput,
      KernelSignalKind.AnalogOutput,
      KernelSignalKind.AnalogInout
    )

    def endpoint(value: AnyRef): Node[?] =
      val reference = Option(declarationIds.get(value)).getOrElse(
        fail("NODAL-HIERARCHY-038", "connection endpoint is outside this construction transaction")
      )
      val owner = records(reference.module)
      val declaration = owner.declarations(reference.index)
      val node = value match
        case candidate: Node[?] => candidate
        case _ =>
          fail("NODAL-HIERARCHY-039", "conservative connection requires a node or analog port")
      val local = reference.module == parent.handle &&
        (portKinds.contains(declaration.kind) || declaration.kind == KernelSignalKind.AnalogNode)
      // attachInstance alone marks a child attached, after recording it in its exact parent.
      val childPort = owner.attached && owner.parentAtConstruction.contains(parent.handle) &&
        portKinds.contains(declaration.kind)
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
      parameters: Vector[DeclarationRef]
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
      subject: String = "hdlRange"
  ):
    private val cache = mutable.HashMap.empty[ExpressionRef, StructuralBound]
    private val active = mutable.HashSet.empty[ExpressionRef]

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
                    parameterBound(parameter, owner, diagnosticCode, subject)
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
                StructuralBound(expression, bounds, operands.flatMap(_.parameters).distinct)
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
        .flatMap(_.value.toIntOption)
        .getOrElse(invalid("hdlRange literal must be a representable public Integer literal"))
      StructuralBound(expression, IterationDomain.Bounds(literal, literal), Vector.empty)

    def apply(value: Int | Expr[Integer]): StructuralBound = value match
      case literal: Int =>
        StructuralBound(literal, IterationDomain.Bounds(literal, literal), Vector.empty)
      case parameter: Param[?] => parameterBound(parameter, owner, diagnosticCode, subject)
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
        identicalBounds = sameStructuralBound(lower, upperExclusive)
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
    val domainCount = module.domains.size
    val instanceCount = module.instances.size
    val operationCount = operations.size
    val analogRegionCount = analogRegions.size
    try
      body(induction)
      val unsupportedDeclaration = record.declarations.iterator
        .map(reference => module.declarations(reference.index))
        .find(_.kind != KernelSignalKind.AnalogNode)
      unsupportedDeclaration.foreach: declaration =>
        fail(
          "NODAL-ITERATION-043-004",
          s"generated object kind '${declaration.kind.label}' is not enabled by the current F-043 checkpoint",
          Some(declarationPath(declaration.reference))
        )
      if module.domains.size != domainCount || module.instances.size != instanceCount ||
        operations.size != operationCount || analogRegions.size != analogRegionCount
      then
        fail(
          "NODAL-ITERATION-043-004",
          "generated domains, instances, connections, assignments, and analog regions require the next F-043 ownership stage",
          Some(provisionalModulePath(module.handle))
        )
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
    val record =
      new AnalogRegionRecord(module.handle, analogRegions.count(_.module == module.handle))
    analogRegions += record
    analogStack += record
    try body
    finally
      val removed = analogStack.remove(analogStack.size - 1)
      if removed ne record then fail("NODAL-ANALOG-LIFECYCLE-001", "analog region stack is corrupt")

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
      owner,
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
      owner,
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
      source
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
      AnalogEquationRuntime.ValueKind.Real
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

  private def renderAnalogValue(value: Any): String = value match
    case expression: KernelExpr[?] if expression.literal.nonEmpty =>
      val literal = expression.literal.map(_.value).getOrElse("")
      val unit = expression.operands.lift(1).collect { case text: String => text }.getOrElse("")
      if unit.isEmpty then literal else s"$literal $unit"
    case expression: KernelExpr[?] =>
      val operation = expression.operation.getOrElse("expression")
      s"$operation${expression.operands.map(renderAnalogValue).mkString("(", ",", ")")}"
    case branch: Branch[?] =>
      branch.name.filter(_.trim.nonEmpty).getOrElse:
        s"${renderAnalogValue(branch.positive)}->${renderAnalogValue(branch.negative)}"
    case state: AnalogState => s"state:${state.name}"
    case terminal: Terminal[?] => pathOf(terminal).getOrElse(s"terminal:${terminal.name}")
    case node: Node[?] => pathOf(node).getOrElse("analog-node")
    case parameter: Param[?] => pathOf(parameter).getOrElse("parameter")
    case reference: AnyRef => pathOf(reference).getOrElse(stableClassName(reference))
    case other => other.toString

  def operation(kind: String, values: Any*): Unit =
    requireNoGeneratedEffect(s"generated '$kind' effect")
    if kind == "assignment" then
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
    val owner = moduleStack.lastOption.map(_.handle).getOrElse(
      fail("NODAL-LIFECYCLE-018", "operation requires an active Module")
    )
    val captured = Operation(owner, kind, values.toVector)
    operations += captured
    moduleStack.lastOption.foreach(module =>
      semanticOrigin.captureOperation(module.handle, kind, captured.values)
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
    case reference: AnyRef =>
      Option(declarationIds.get(reference))
        .map(declarationPath)
        .orElse(Option(expressionIds.get(reference)).map(expressionPath))
        .orElse(Option(moduleIds.get(reference)).map(handle => modulePath(handle.longValue)))
    case _ => None

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

  private def topology(): Vector[KernelTopologyEdge] =
    val edges = operations.toVector.flatMap: operation =>
      if Set("node-connect", "terminal-connect", "inout-pass-through").contains(operation.kind) &&
        operation.values.size >= 2
      then
        (pathOf(operation.values(0)), pathOf(operation.values(1))) match
          case (Some(left), Some(right)) =>
            val (first, second) =
              if operation.kind == "node-connect" && right < left then right -> left
              else left -> right
            Some(KernelTopologyEdge(modulePath(operation.owner), operation.kind, first, second))
          case _ => None
      else None
    edges.sortBy(edge => (edge.owner, edge.kind, edge.left, edge.right))

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
    case ClockRelation.Ratio(multiply, divide, _) =>
      Vector(
        "clock_relation" -> "ratio",
        "clock_multiply" -> multiply.toString,
        "clock_divide" -> divide.toString
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
            policyAttributes
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
          declaration.attributes.map(value => value._1 -> renderAny(value._2, module.handle))
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
          parameters.sortBy(_._1)
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

  private def shapeIndexSnapshots(): Vector[KernelShapeIndexSnapshot] =
    shapeIndices.toVector.map: index =>
      KernelShapeIndexSnapshot(
        path = expressionPath(index.reference),
        owner = modulePath(index.reference.module),
        input = declarationPath(index.input),
        indices = index.indices.map(generatedBound)
      )

  private def shapeViewSnapshots(): Vector[KernelShapeViewSnapshot] =
    shapeViews.toVector.map: view =>
      KernelShapeViewSnapshot(
        path = expressionPath(view.reference),
        owner = modulePath(view.reference.module),
        input = declarationPath(view.input),
        dimensions = view.dimensions
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
        source = sourceByPath.get(path)
      )
    .sortBy(_.path)

  private def transferOperatorSnapshots(sourceMap: Vector[SourceMapEntry])
      : Vector[KernelTransferOperatorSnapshot] =
    val sources = sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
    transferOperators.toVector.map: (reference, kind, n, d, inputs, dimension) =>
      val path = expressionPath(reference)
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
        sources.get(path)
      )
    .sortBy(_.path)

  private def noiseOperatorSnapshots(sourceMap: Vector[SourceMapEntry])
      : Vector[KernelNoiseOperatorSnapshot] =
    val sources = sourceMap.map(entry => entry.semanticPath -> entry.source).toMap
    noiseOperators.toVector.map: (reference, kind, label, inputs, dimension) =>
      val path = expressionPath(reference)
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
        sources.get(path)
      )
    .sortBy(_.path)

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
          sources.get(path)
        )
    .sortBy(_.path)

  private def analogSnapshots(): Vector[KernelAnalogRegionSnapshot] =
    analogRegions.toVector.sortBy(region => (modulePath(region.module), region.ordinal)).map:
      region =>
        val expressions = region.expressions.distinct.toVector.map: reference =>
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
              kind
            )
        KernelAnalogRegionSnapshot(
          s"${modulePath(region.module)}.analog_${region.ordinal}",
          modulePath(region.module),
          expressions,
          contributions
        )

  private def parameterExpressionSnapshots(): Vector[KernelParameterExpressionSnapshot] =
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

    val roots = rootParameterBindings.iterator.map(_._2) ++ records.valuesIterator.flatMap(
      _.instances.iterator.flatMap(_.parameterOverrides.iterator.map(_._2))
    )
    val generatedRoots = records.valuesIterator.flatMap(
      _.generatedRegions.iterator.flatMap(region =>
        Iterator(region.lower, region.upperExclusive, region.step)
      )
    )
    val shapeIndexRoots = shapeIndices.iterator.flatMap(_.indices.iterator)
    (roots ++ generatedRoots ++ shapeIndexRoots).foreach:
      case expression: KernelExpr[?] if expression.literal.isEmpty => visit(expression)
      case _ => ()

    expressionValues.toVector.collect:
      case (reference, expression) if reachable.contains(reference) =>
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
      "conservative-terminal",
      "analog-signal"
    )
    val analog =
      kinds.exists(analogKinds.contains) || snapshot.continuousOperators.nonEmpty ||
        snapshot.waveformOperators.nonEmpty || snapshot.noiseOperators.nonEmpty ||
        snapshot.transferOperators.nonEmpty || snapshot.analogFunctions.nonEmpty ||
        snapshot.analogRegions.nonEmpty ||
        snapshot.analogProcedural.nonEmpty || snapshot.analogSemantics.equations.nonEmpty ||
        snapshot.analogSemantics.contributions.nonEmpty
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

    val semantic = semanticOrigin.resolve()
    semanticResult = Some(semantic)
    val resolved = resolveDomains()
    val modules = snapshots(resolved)
    val abi = interfaceAbi(resolved)
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
      topology = topology(),
      names = semantic.names,
      origins = semantic.origins,
      generatedNames = semantic.generatedNames,
      sourceMap =
        (semantic.sourceMap ++ AnalogUserFunctionRuntime.sourceMap(
          userFunctions.snapshots(modulePath)
        )).sortBy(_.semanticPath),
      analogRegions = analogSnapshots(),
      continuousOperators = continuousOperatorSnapshots(semantic.sourceMap),
      waveformOperators = waveformOperatorSnapshots(semantic.sourceMap),
      noiseOperators = noiseOperatorSnapshots(semantic.sourceMap),
      transferOperators = transferOperatorSnapshots(semantic.sourceMap),
      analogFunctions = userFunctions.snapshots(modulePath),
      analogSemantics = analogSemanticRecorder.snapshot,
      analogProcedural = AnalogProceduralConstruction.snapshots(module =>
        modulePath(moduleHandle(module))
      ),
      waivers = waiverSnapshots(semantic.sourceMap),
      generatedRegions = generatedSnapshots(),
      shapeIndices = shapeIndexSnapshots(),
      shapeViews = shapeViewSnapshots()
    )
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
