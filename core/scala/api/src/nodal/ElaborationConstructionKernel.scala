package nodal

import java.lang.ScopedValue
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.IdentityHashMap

import scala.collection.mutable

private[nodal] enum KernelSignalKind(val label: String):
  case Parameter extends KernelSignalKind("parameter")
  case Input extends KernelSignalKind("input")
  case Output extends KernelSignalKind("output")
  case Wire extends KernelSignalKind("wire")
  case Variable extends KernelSignalKind("variable")
  case Register extends KernelSignalKind("register")
  case Memory extends KernelSignalKind("memory")
  case AnalogInput extends KernelSignalKind("analog-input")
  case AnalogOutput extends KernelSignalKind("analog-output")
  case AnalogInout extends KernelSignalKind("analog-inout")
  case AnalogNode extends KernelSignalKind("analog-node")
  case InterfacePort extends KernelSignalKind("interface-port")
  case InterfaceArray extends KernelSignalKind("interface-array")
  case DigitalInout extends KernelSignalKind("digital-inout")
  case ConservativeTerminal extends KernelSignalKind("conservative-terminal")
  case AnalogSignal extends KernelSignalKind("analog-signal")

private[nodal] enum KernelDomainKind(val label: String):
  case External extends KernelDomainKind("external")
  case Bound extends KernelDomainKind("bound")
  case Required extends KernelDomainKind("required")
  case Generated extends KernelDomainKind("generated")

private[nodal] final case class KernelTypeDescriptor(
    kind: String,
    arguments: Vector[Any] = Vector.empty
)

private[nodal] trait KernelDescribedType:
  def kernelDescriptor: KernelTypeDescriptor

private[nodal] final case class KernelDiagnostic(
    code: String,
    message: String,
    semanticPath: Option[String] = None
):
  override def toString: String = semanticPath match
    case Some(path) => s"$code: $message [$path]"
    case None => s"$code: $message"

private[nodal] final class ConstructionException(val diagnostic: KernelDiagnostic)
    extends IllegalArgumentException(diagnostic.toString)

private[nodal] final case class KernelDomainSnapshot(
    path: String,
    name: String,
    kind: String,
    binding: Option[String],
    edge: Option[String] = None,
    resetPolicy: Option[String] = None,
    attributes: Vector[(String, String)] = Vector.empty
)

private[nodal] final case class KernelDeclarationSnapshot(
    path: String,
    kind: String,
    name: String,
    dataType: Option[String],
    domain: Option[String],
    attributes: Vector[(String, String)]
)

private[nodal] final case class KernelInstanceSnapshot(
    path: String,
    childModule: String,
    lexicalDomain: Option[String],
    bindings: Vector[(String, String)],
    parameterBindings: Vector[(String, String)] = Vector.empty
)

private[nodal] final case class KernelModuleSnapshot(
    path: String,
    className: String,
    domains: Vector[KernelDomainSnapshot],
    declarations: Vector[KernelDeclarationSnapshot],
    instances: Vector[KernelInstanceSnapshot]
)

private[nodal] final case class KernelResolvedNetSnapshot(
    path: String,
    dataType: String,
    mode: String,
    placement: String,
    profile: String,
    operations: Vector[String]
)

private[nodal] final case class KernelTopologyEdge(kind: String, left: String, right: String)

private[nodal] final case class KernelAnalogExpressionSnapshot(
    path: String,
    operation: String,
    operands: Vector[String],
    literal: Option[String],
    unit: Option[String]
)

private[nodal] final case class KernelAnalogContributionSnapshot(
    path: String,
    target: String,
    value: String,
    kind: String
)

private[nodal] final case class KernelAnalogRegionSnapshot(
    path: String,
    module: String,
    expressions: Vector[KernelAnalogExpressionSnapshot],
    contributions: Vector[KernelAnalogContributionSnapshot]
)

private[nodal] final case class KernelContinuousOperatorSnapshot(
    path: String,
    operation: String,
    owner: String,
    context: String,
    input: String,
    initialCondition: Option[String],
    inputDimension: String,
    resultDimension: String,
    stateId: Option[String],
    initialization: String,
    analyses: Vector[String],
    source: Option[SourceSpan]
)

private[nodal] final case class KernelWaveformOperatorSnapshot(
    path: String,
    operation: String,
    owner: String,
    context: String,
    operands: Vector[String],
    operandDimensions: Vector[String],
    resultDimension: String,
    inputContinuity: String,
    outputContinuity: String,
    stateId: Option[String],
    analyses: Vector[String],
    source: Option[SourceSpan]
)

private[nodal] final case class KernelWaiverSnapshot(
    kind: String,
    id: String,
    reason: String,
    relation: String,
    semanticPath: String,
    sourceValue: Option[String],
    destinationDomain: Option[String],
    source: Option[SourceSpan]
)

private[nodal] final case class ConstructionSnapshot(
    root: String,
    modules: Vector[KernelModuleSnapshot],
    interfaceAbi: Vector[InterfaceAbiEntry],
    resolvedNets: Vector[KernelResolvedNetSnapshot],
    topology: Vector[KernelTopologyEdge],
    names: Vector[KernelNameSnapshot] = Vector.empty,
    origins: Vector[KernelOriginSnapshot] = Vector.empty,
    generatedNames: Vector[KernelGeneratedNameSnapshot] = Vector.empty,
    sourceMap: Vector[SourceMapEntry] = Vector.empty,
    analogRegions: Vector[KernelAnalogRegionSnapshot] = Vector.empty,
    continuousOperators: Vector[KernelContinuousOperatorSnapshot] = Vector.empty,
    waveformOperators: Vector[KernelWaveformOperatorSnapshot] = Vector.empty,
    analogSemantics: AnalogEquationRuntime.Snapshot =
      AnalogEquationRuntime.Snapshot(Vector.empty, Vector.empty),
    analogProcedural: Vector[AnalogProceduralRuntime.Snapshot] = Vector.empty,
    waivers: Vector[KernelWaiverSnapshot] = Vector.empty
)

private final case class DomainRef(module: Long, index: Int)
private final case class DeclarationRef(module: Long, index: Int)
private final case class ExpressionRef(module: Long, index: Int)

private final case class DomainRecord(
    reference: DomainRef,
    domain: ClockDomain,
    name: String,
    kind: KernelDomainKind
)

private final case class DeclarationRecord(
    reference: DeclarationRef,
    value: AnyRef,
    kind: KernelSignalKind,
    dataType: Option[DataType[? <: Data]],
    explicitName: Option[String],
    domainCandidate: Option[ClockDomain],
    attributes: Vector[(String, Any)]
)

private final class InstanceRecord(
    val ordinal: Int,
    val child: Long,
    val lexicalDomain: Option[ClockDomain]
):
  var defaultBinding: Option[ClockDomain] = None
  val namedBindings: mutable.ArrayBuffer[(ClockDomain, ClockDomain)] = mutable.ArrayBuffer.empty
  val parameterOverrides: mutable.ArrayBuffer[(Any, Any)] = mutable.ArrayBuffer.empty

private final class ModuleRecord(
    val handle: Long,
    val className: String,
    val parentAtConstruction: Option[Long]
):
  val domains: mutable.ArrayBuffer[DomainRecord] = mutable.ArrayBuffer.empty
  val declarations: mutable.ArrayBuffer[DeclarationRecord] = mutable.ArrayBuffer.empty
  val instances: mutable.ArrayBuffer[InstanceRecord] = mutable.ArrayBuffer.empty
  var expressionCount: Int = 0
  var attached: Boolean = parentAtConstruction.isEmpty

private final case class Operation(kind: String, values: Vector[Any])

private final class AnalogRegionRecord(val module: Long, val ordinal: Int):
  val expressions: mutable.ArrayBuffer[ExpressionRef] = mutable.ArrayBuffer.empty
  val contributions: mutable.ArrayBuffer[(Any, Any)] = mutable.ArrayBuffer.empty

private final case class AnalogSemanticContext(
    module: Long,
    kind: AnalogEquationRuntime.RegionKind
)

private final case class ContinuousOperatorRecord(
    reference: ExpressionRef,
    operation: String,
    input: Expr[Real],
    initialCondition: Option[Expr[Real]],
    context: String,
    inputDimension: AnalogDimension,
    resultDimension: AnalogDimension
)

private final case class AnalogDimension(
    powers: Map[String, Int],
    isZero: Boolean = false,
    isUnknown: Boolean = false
):
  private def normalized(values: Map[String, Int]): Map[String, Int] =
    values.filter(_._2 != 0)

  def multiply(other: AnalogDimension): AnalogDimension =
    if isUnknown || other.isUnknown then AnalogDimension.Unknown
    else
      val keys = powers.keySet ++ other.powers.keySet
      AnalogDimension(
        normalized(
          keys.iterator
            .map(key => key -> (powers.getOrElse(key, 0) + other.powers.getOrElse(key, 0)))
            .toMap
        ),
        isZero = isZero || other.isZero
      )

  def divide(other: AnalogDimension): AnalogDimension =
    if isUnknown || other.isUnknown then AnalogDimension.Unknown
    else
      val keys = powers.keySet ++ other.powers.keySet
      AnalogDimension(
        normalized(
          keys.iterator
            .map(key => key -> (powers.getOrElse(key, 0) - other.powers.getOrElse(key, 0)))
            .toMap
        ),
        isZero = isZero
      )

  def compatibleAdd(other: AnalogDimension): AnalogDimension =
    if isUnknown || other.isUnknown then AnalogDimension.Unknown
    else if isZero then other.copy(isZero = other.isZero || isZero)
    else if other.isZero then copy(isZero = isZero || other.isZero)
    else if powers == other.powers then copy(isZero = isZero && other.isZero)
    else AnalogDimension.Unknown

  def signature: String =
    if isUnknown then "unknown"
    else if powers.isEmpty then "1"
    else
      powers.toVector
        .sortBy(_._1)
        .map: (name, exponent) =>
          if exponent == 1 then name else s"$name^$exponent"
        .mkString("*")

  def canonical: String =
    if isUnknown then "unknown"
    else
      powers match
        case values if values.isEmpty => "dimensionless"
        case values if values == Map("voltage" -> 1) => "voltage"
        case values if values == Map("current" -> 1) => "current"
        case values if values == Map("time" -> 1) => "time"
        case values if values == Map("time" -> -1) => "frequency"
        case values if values == Map("temperature" -> 1) => "temperature"
        case values if values == Map("current" -> 1, "time" -> 1) => "charge"
        case values if values == Map("voltage" -> 1, "current" -> 1) => "power"
        case values if values == Map("voltage" -> 1, "current" -> -1) => "resistance"
        case values
            if values == Map("current" -> 1, "time" -> 1, "voltage" -> -1) =>
          "capacitance"
        case values =>
          values.toVector
            .sortBy(_._1)
            .map: (name, exponent) =>
              if exponent == 1 then name else s"$name^$exponent"
            .mkString("*")

private object AnalogDimension:
  val Unknown: AnalogDimension = AnalogDimension(Map.empty, isUnknown = true)
  val Dimensionless: AnalogDimension = AnalogDimension(Map.empty)
  val Zero: AnalogDimension = AnalogDimension(Map.empty, isZero = true)
  val Voltage: AnalogDimension = AnalogDimension(Map("voltage" -> 1))
  val Current: AnalogDimension = AnalogDimension(Map("current" -> 1))
  val Time: AnalogDimension = AnalogDimension(Map("time" -> 1))
  val Temperature: AnalogDimension = AnalogDimension(Map("temperature" -> 1))

/** One mutable transaction. JVM identity is used only for transient lookup; stable paths use
  * hierarchy, explicit names, and deterministic local ordinals.
  */
private final class ConstructionSession(val options: EmitOptions):
  private var nextModule: Long = 0L
  private val moduleIds = new IdentityHashMap[AnyRef, java.lang.Long]()
  private val domainIds = new IdentityHashMap[AnyRef, DomainRef]()
  private val declarationIds = new IdentityHashMap[AnyRef, DeclarationRef]()
  private val expressionIds = new IdentityHashMap[AnyRef, ExpressionRef]()
  private val instanceIds = new IdentityHashMap[AnyRef, InstanceRecord]()
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
  private var waveformForbiddenDepth = 0
  private val semanticOrigin = new SemanticOriginBuilder
  private var semanticResult: Option[SemanticOriginResult] = None

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

  def registerDomain(domain: ClockDomain, kind: KernelDomainKind): Unit =
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
    val module = currentModule
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

  // This check is deliberately structural: parameter defaults are not substituted for
  // symbolic overrides, and time-dependent operators are never classified as constants.
  private def waveformStatic(value: Any): Boolean = value match
    case _: Param[?] => true
    case expression: KernelExpr[?] =>
      expression.literal.nonEmpty ||
      (Set("analog_add", "analog_sub", "analog_mul", "analog_div", "analog_neg")
        .contains(expression.operation.getOrElse("")) &&
        expression.operands.forall(waveformStatic))
    case _ => false

  private def waveformConstant(value: Any): Option[Double] = value match
    case expression: KernelExpr[?] =>
      expression.literal.filter(_.kind == "real").flatMap(_.value.toDoubleOption).orElse:
        expression.operands.map(waveformConstant) match
          case Vector(Some(a), Some(b)) => expression.operation match
              case Some("analog_add") => Some(a + b)
              case Some("analog_sub") => Some(a - b)
              case Some("analog_mul") => Some(a * b)
              case Some("analog_div") => Some(a / b)
              case _ => None
          case Vector(Some(a)) if expression.operation.contains("analog_neg") => Some(-a)
          case _ => None
    case _ => None

  private def waveformContinuity(value: Any): String =
    if waveformStatic(value) then "constant"
    else
      value match
        case expression: KernelExpr[?] => expression.operation match
            case Some("analog_transition") => "continuous"
            case Some("analog_slew") if expression.operands.size > 1 => "continuous"
            case Some("analog_slew") => waveformContinuity(expression.operands.head)
            // A changing transport delay can introduce jumps even for a smooth input.
            case Some("analog_absdelay") => "unknown"
            case Some("analog_abstime") => "continuous"
            case Some("analog_select") if expression.operands.drop(1).forall(waveformStatic) =>
              "piecewise-constant"
            case _ => "unknown"
        case _ => "unknown"

  def withWaveformForbidden[A](body: => A): A =
    waveformForbiddenDepth += 1
    try body
    finally waveformForbiddenDepth -= 1

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

  def attachInstance(instance: AnyRef, childModule: Module): Unit =
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
      domainStack.lastOption
    )
    parent.instances += record
    child.attached = true
    instanceIds.put(instance, record)
    semanticOrigin.captureInstance(
      parent.handle,
      record.ordinal,
      childHandle,
      instance,
      childModule
    )

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

  def overrideParameter(instance: AnyRef, parameter: Any, value: Any): Unit =
    instanceRecord(instance).parameterOverrides += parameter -> value

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

  private def inferBooleanExpressionDimension(
      expression: KernelExpr[?]
  ): AnalogDimension =
    expression.operation match
      case Some("real_gt") | Some("real_ge") | Some("real_lt") |
          Some("real_le") =>
        expression.operands match
          case Vector(left, right) =>
            val compatible = inferAnalogDimension(left)
              .compatibleAdd(inferAnalogDimension(right))
            if compatible.isUnknown then AnalogDimension.Unknown
            else AnalogDimension.Dimensionless
          case _ => AnalogDimension.Unknown
      case Some("bool_and") | Some("bool_or") =>
        val dimensions = expression.operands.map(inferAnalogDimension)
        if dimensions.nonEmpty && dimensions.forall(isDimensionlessBoolean) then
          AnalogDimension.Dimensionless
        else AnalogDimension.Unknown
      case Some("bool_not") =>
        expression.operands match
          case Vector(operand)
              if isDimensionlessBoolean(inferAnalogDimension(operand)) =>
            AnalogDimension.Dimensionless
          case _ => AnalogDimension.Unknown
      case _ => AnalogDimension.Dimensionless

  private def isDimensionlessBoolean(dimension: AnalogDimension): Boolean =
    !dimension.isUnknown && dimension.powers.isEmpty

  def inferAnalogDimension(value: Any): AnalogDimension = value match
    case parameter: Param[?] => inferAnalogDimension(parameter.default)
    case state: AnalogState => physicalDimension(state.dimension)

    case variable: Variable[?] =>
      AnalogProceduralConstruction
        .registeredDimension(variable)
        .map(namedAnalogDimension)
        .getOrElse(AnalogDimension.Unknown)
    case expression: KernelExpr[?]
        if expression.resultType.exists(_.kind == "Bool") =>
      inferBooleanExpressionDimension(expression)
    case expression: KernelExpr[?] =>
      expression.literal match
        case Some(literal) if literal.kind == "real" =>
          val unit =
            expression.operands.lift(1).collect { case value: String => value }.getOrElse("")
          val zero = literal.value.toDoubleOption.contains(0.0)
          unitDimension(unit, zero)
        case Some(_) => AnalogDimension.Dimensionless
        case None =>
          expression.operation match
            case Some("analog_add") | Some("analog_sub") =>
              expression.operands.map(inferAnalogDimension).reduceOption(_.compatibleAdd(_))
                .getOrElse(AnalogDimension.Unknown)
            case Some("analog_mul") =>
              expression.operands.map(inferAnalogDimension).reduceOption(_.multiply(_))
                .getOrElse(AnalogDimension.Unknown)
            case Some("analog_div") =>
              expression.operands match
                case Vector(left, right) =>
                  inferAnalogDimension(left).divide(inferAnalogDimension(right))
                case _ => AnalogDimension.Unknown
            case Some("analog_neg") =>
              expression.operands.headOption.map(
                inferAnalogDimension
              ).getOrElse(AnalogDimension.Unknown)
            case Some("analog_select") =>
              expression.operands.drop(1).map(inferAnalogDimension).reduceOption(_.compatibleAdd(_))
                .getOrElse(AnalogDimension.Unknown)
            case Some("analog_ddt") =>
              expression.operands.headOption.map(inferAnalogDimension)
                .map(_.divide(AnalogDimension.Time))
                .getOrElse(AnalogDimension.Unknown)
            case Some("analog_idt") =>
              expression.operands.headOption.map(inferAnalogDimension)
                .map(_.multiply(AnalogDimension.Time))
                .getOrElse(AnalogDimension.Unknown)
            case Some("potential_access") | Some("candidate-branch-potential") =>
              accessDimension(expression.operands, potential = true)
            case Some("flow_access") | Some("candidate-branch-flow") =>
              accessDimension(expression.operands, potential = false)
            case Some("candidate-analog-state") =>
              expression.operands.collectFirst { case state: AnalogState =>
                physicalDimension(state.dimension)
              }.getOrElse(AnalogDimension.Unknown)
            case Some("analog_abstime") => AnalogDimension.Time
            case Some("candidate-analysis-time") => AnalogDimension.Time
            case Some("candidate-analysis-frequency") =>
              AnalogDimension.Dimensionless.divide(AnalogDimension.Time)
            case Some("candidate-environment-temperature") |
                Some("candidate-environment-nominal-temperature") =>
              AnalogDimension.Temperature
            case Some("candidate-operating-condition") | Some("candidate-sweep-coordinate") =>
              expression.operands.collectFirst { case dimension: PhysicalDimension =>
                physicalDimension(dimension)
              }.getOrElse(AnalogDimension.Unknown)
            case _ =>
              expression.operands.headOption.map(inferAnalogDimension)
                .getOrElse(AnalogDimension.Unknown)
    case dimension: PhysicalDimension => physicalDimension(dimension)
    case _ => AnalogDimension.Unknown

  private def accessDimension(values: Vector[Any], potential: Boolean): AnalogDimension =
    val discipline = values.collectFirst:
      case branch: Branch[?] => branch.positive.discipline
      case node: Node[?] => node.discipline
      case terminal: Terminal[?] => terminal.discipline
      case view: TerminalView[?, ?] => view.terminal.discipline
    discipline.map(disciplineDimension(_, potential)).getOrElse:
      if potential then AnalogDimension.Voltage else AnalogDimension.Current

  private def disciplineDimension(
      discipline: Discipline,
      potential: Boolean
  ): AnalogDimension = discipline match
    case Electrical => if potential then AnalogDimension.Voltage else AnalogDimension.Current
    case named: NamedDiscipline =>
      natureDimension(if potential then named.potential else named.flow)

  private def natureDimension(nature: Nature): AnalogDimension =
    nature.name.trim.toLowerCase match
      case "voltage" | "potential" => AnalogDimension.Voltage
      case "current" | "flow" => AnalogDimension.Current
      case "temperature" => AnalogDimension.Temperature
      case _ => AnalogDimension.Unknown

  private def namedAnalogDimension(value: String): AnalogDimension =
    value.trim match
      case "dimensionless" | "1" => AnalogDimension.Dimensionless
      case "voltage" => AnalogDimension.Voltage
      case "current" => AnalogDimension.Current
      case "time" => AnalogDimension.Time
      case "frequency" => AnalogDimension.Dimensionless.divide(AnalogDimension.Time)
      case "temperature" => AnalogDimension.Temperature
      case "charge" => AnalogDimension.Current.multiply(AnalogDimension.Time)
      case "power" => AnalogDimension.Voltage.multiply(AnalogDimension.Current)
      case "resistance" => AnalogDimension.Voltage.divide(AnalogDimension.Current)
      case "capacitance" =>
        AnalogDimension.Current
          .multiply(AnalogDimension.Time)
          .divide(AnalogDimension.Voltage)
      case "" | "unknown" => AnalogDimension.Unknown
      case signature =>
        val factors = signature.split("\\*").toVector
        val parsed = factors.map: factor =>
          factor.split("\\^", 2).toVector match
            case Vector(name) if name.nonEmpty => Some(name -> 1)
            case Vector(name, exponent) if name.nonEmpty =>
              exponent.toIntOption.filter(_ != 0).map(value => name -> value)
            case _ => None
        if factors.nonEmpty && parsed.forall(_.nonEmpty) then
          val powers = parsed.flatten
            .groupMapReduce(_._1)(_._2)(_ + _)
            .filter(_._2 != 0)
          AnalogDimension(powers)
        else AnalogDimension.Unknown
  private def physicalDimension(value: PhysicalDimension): AnalogDimension =
    value.name.trim.toLowerCase match
      case "dimensionless" => AnalogDimension.Dimensionless
      case "voltage" => AnalogDimension.Voltage
      case "current" => AnalogDimension.Current
      case "charge" => AnalogDimension.Current.multiply(AnalogDimension.Time)
      case "temperature" => AnalogDimension.Temperature
      case "time" => AnalogDimension.Time
      case "frequency" => AnalogDimension.Dimensionless.divide(AnalogDimension.Time)
      case "power" => AnalogDimension.Voltage.multiply(AnalogDimension.Current)
      case _ => AnalogDimension.Unknown

  private def unitDimension(unit: String, zero: Boolean): AnalogDimension =
    val dimension = unit match
      case "" => AnalogDimension.Dimensionless
      case "V" => AnalogDimension.Voltage
      case "A" => AnalogDimension.Current
      case "Ohm" => AnalogDimension.Voltage.divide(AnalogDimension.Current)
      case "F" =>
        AnalogDimension.Current
          .multiply(AnalogDimension.Time)
          .divide(AnalogDimension.Voltage)
      case "s" => AnalogDimension.Time
      case _ => AnalogDimension.Unknown
    if zero then dimension.copy(isZero = true) else dimension

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
    val captured = Operation(kind, values.toVector)
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

  private def pathOf(value: Any): Option[String] = value match
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
          Option(expressionIds.get(reference)).map: expression =>
            s"${modulePath(expression.module)}.expr_${expression.index}"
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
          case values: Seq[?] => values.map(renderAny(_, owner))
          case _ => Vector.empty
        s"Vec(${element.getOrElse("unknown")};${dimensions.mkString("x")})"
      case kind if descriptor.arguments.nonEmpty =>
        s"$kind(${descriptor.arguments.map(renderAny(_, owner)).mkString(",")})"
      case kind => kind

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

  private def memberName(member: InterfaceMember): String = member match
    case value: InterfaceMember.Value[?] => value.name
    case valid: InterfaceMember.ValidChannel[?] => valid.name
    case stream: InterfaceMember.StreamChannel[?] => stream.name
    case nested: InterfaceMember.Nested[?] => nested.name
    case digital: InterfaceMember.DigitalResolved[?, ?] => digital.name
    case conservative: InterfaceMember.Conservative[?] => conservative.name
    case signal: InterfaceMember.SignalFlow[?] => signal.name

  private def accessMember(access: RoleAccess): String = access match
    case RoleAccess.In(member) => member
    case RoleAccess.Out(member) => member
    case RoleAccess.Observe(member) => member
    case RoleAccess.Master(member) => member
    case RoleAccess.Slave(member) => member
    case RoleAccess.Read(member) => member
    case RoleAccess.Drive(member) => member
    case RoleAccess.Connect(member) => member
    case RoleAccess.Sense(member) => member
    case RoleAccess.Contribute(member) => member
    case RoleAccess.Nested(member, _) => member

  private def accessName(access: RoleAccess): String = access match
    case RoleAccess.In(_) => "in"
    case RoleAccess.Out(_) => "out"
    case RoleAccess.Observe(_) => "observe"
    case RoleAccess.Master(_) => "master"
    case RoleAccess.Slave(_) => "slave"
    case RoleAccess.Read(_) => "read"
    case RoleAccess.Drive(_) => "drive"
    case RoleAccess.Connect(_) => "connect"
    case RoleAccess.Sense(_) => "sense"
    case RoleAccess.Contribute(_) => "contribute"
    case RoleAccess.Nested(_, role) => s"nested:$role"

  private def validAccess(member: InterfaceMember, access: RoleAccess): Boolean = member match
    case _: InterfaceMember.Value[?] => access match
        case RoleAccess.In(_) | RoleAccess.Out(_) | RoleAccess.Observe(_) => true
        case _ => false
    case _: InterfaceMember.ValidChannel[?] => access match
        case RoleAccess.Master(_) | RoleAccess.Slave(_) | RoleAccess.Observe(_) => true
        case _ => false
    case _: InterfaceMember.StreamChannel[?] => access match
        case RoleAccess.Master(_) | RoleAccess.Slave(_) | RoleAccess.Observe(_) => true
        case _ => false
    case _: InterfaceMember.Nested[?] => access match
        case RoleAccess.Nested(_, _) | RoleAccess.Observe(_) => true
        case _ => false
    case _: InterfaceMember.DigitalResolved[?, ?] => access match
        case RoleAccess.Read(_) | RoleAccess.Drive(_) | RoleAccess.Connect(_) | RoleAccess.Observe(
              _
            ) => true
        case _ => false
    case _: InterfaceMember.Conservative[?] => access match
        case RoleAccess.Connect(_) | RoleAccess.Sense(_) | RoleAccess.Contribute(_) => true
        case _ => false
    case _: InterfaceMember.SignalFlow[?] => access match
        case RoleAccess.In(_) | RoleAccess.Out(_) | RoleAccess.Observe(_) => true
        case _ => false

  private def protocolAbi(
      logical: String,
      emitted: String,
      role: String,
      access: RoleAccess,
      payload: DataType[?],
      domain: String,
      stream: Boolean,
      owner: Long
  ): Vector[InterfaceAbiEntry] =
    val forward = access match
      case RoleAccess.Master(_) => "out"
      case RoleAccess.Slave(_) => "in"
      case RoleAccess.Observe(_) => "observe"
      case _ => accessName(access)
    val backward = access match
      case RoleAccess.Master(_) => "in"
      case RoleAccess.Slave(_) => "out"
      case RoleAccess.Observe(_) => "observe"
      case _ => accessName(access)
    val entries = Vector(
      InterfaceAbiEntry(s"$logical.valid", s"${emitted}_valid", role, forward, "Bool", domain),
      InterfaceAbiEntry(
        s"$logical.payload",
        s"${emitted}_payload",
        role,
        forward,
        renderType(payload, owner),
        domain
      )
    )
    if stream then
      entries :+ InterfaceAbiEntry(
        s"$logical.ready",
        s"${emitted}_ready",
        role,
        backward,
        "Bool",
        domain
      )
    else entries

  private def expandMember(
      member: InterfaceMember,
      access: RoleAccess,
      logical: String,
      emitted: String,
      role: String,
      domain: String,
      owner: Long
  ): Vector[InterfaceAbiEntry] = member match
    case value: InterfaceMember.Value[?] =>
      Vector(
        InterfaceAbiEntry(
          logical,
          emitted,
          role,
          accessName(access),
          renderType(value.dataType, owner),
          domain
        )
      )
    case valid: InterfaceMember.ValidChannel[?] =>
      protocolAbi(logical, emitted, role, access, valid.payloadType, domain, false, owner)
    case stream: InterfaceMember.StreamChannel[?] =>
      protocolAbi(logical, emitted, role, access, stream.payloadType, domain, true, owner)
    case nested: InterfaceMember.Nested[?] =>
      nested.definition.members.toVector.flatMap: child =>
        val name = memberName(child)
        expandMember(
          child,
          access,
          s"$logical.$name",
          s"${emitted}_$name",
          role,
          domain,
          owner
        )
    case digital: InterfaceMember.DigitalResolved[?, ?] =>
      Vector(
        InterfaceAbiEntry(
          logical,
          emitted,
          role,
          accessName(access),
          renderType(digital.dataType, owner),
          domain
        )
      )
    case conservative: InterfaceMember.Conservative[?] =>
      Vector(
        InterfaceAbiEntry(
          logical,
          emitted,
          role,
          accessName(access),
          s"Terminal(${conservative.discipline})",
          domain
        )
      )
    case signal: InterfaceMember.SignalFlow[?] =>
      Vector(
        InterfaceAbiEntry(
          logical,
          emitted,
          role,
          accessName(access),
          s"AnalogSignal(${signal.dimension})",
          domain
        )
      )

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
      expandMember(
        member,
        access,
        s"${modulePath(owner)}.$name$suffix.$memberNameValue",
        s"${name}_$memberNameValue",
        role.name,
        endpointDomain,
        owner
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
          case (Some(left), Some(right)) => Some(KernelTopologyEdge(operation.kind, left, right))
          case _ => None
      else None
    edges.sortBy(edge => (edge.kind, edge.left, edge.right))

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
        snapshot.waveformOperators.nonEmpty
    val digital = (kinds -- analogKinds).nonEmpty || snapshot.interfaceAbi.nonEmpty ||
      snapshot.resolvedNets.nonEmpty
    (digital, analog) match
      case (true, true) => DesignKind.MixedSignal
      case (true, false) => DesignKind.DigitalOnly
      case (false, true) => DesignKind.AnalogOnly
      case _ => DesignKind.Unsupported

  def finish(root: Module): (Emission, ConstructionSnapshot) =
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
      modules = modules,
      interfaceAbi = abi,
      resolvedNets = resolvedNets(),
      topology = topology(),
      names = semantic.names,
      origins = semantic.origins,
      generatedNames = semantic.generatedNames,
      sourceMap = semantic.sourceMap,
      analogRegions = analogSnapshots(),
      continuousOperators = continuousOperatorSnapshots(semantic.sourceMap),
      waveformOperators = waveformOperatorSnapshots(semantic.sourceMap),
      analogSemantics = analogSemanticRecorder.snapshot,
      analogProcedural = AnalogProceduralConstruction.snapshots(module =>
        modulePath(moduleHandle(module))
      ),
      waivers = waiverSnapshots(semantic.sourceMap)
    )
    val kind = classify(snapshot)
    val report = DesignReport(
      designKind = kind,
      selectedBackend = options.backend,
      digitalProfile =
        if kind == DesignKind.AnalogOnly || kind == DesignKind.Unsupported then None
        else Some(options.digitalProfile),
      interfaceAbi = abi,
      sourceMap = semantic.sourceMap,
      schedules = Vector.empty
    )
    Emission(Vector.empty, report) -> snapshot

private[nodal] object ConstructionKernel:
  private val Current: ScopedValue[ConstructionSession] =
    ScopedValue.newInstance[ConstructionSession]()

  private def active: Option[ConstructionSession] =
    if Current.isBound then Some(Current.get) else None

  private def elaborate(top: => Module, options: EmitOptions): (Emission, ConstructionSnapshot) =
    AnalogProceduralConstruction.reset()
    val session = new ConstructionSession(options)
    var result: Option[(Emission, ConstructionSnapshot)] = None
    ScopedValue.where(Current, session).run(
      new Runnable:
        override def run(): Unit =
          val root = top
          result = Some(session.finish(root))
    )
    result.getOrElse(
      scala.util.Failure[(Emission, ConstructionSnapshot)](
        new IllegalStateException("construction transaction did not publish a result")
      ).get
    )

  def emit(top: => Module, options: EmitOptions): Emission = elaborate(top, options)._1

  def inspect(top: => Module, options: EmitOptions = EmitOptions()): ConstructionSnapshot =
    elaborate(top, options)._2

  def beginModule(module: Module): Unit = active.foreach(_.beginModule(module))

  def currentModulePath: String = active
    .map(_.currentModulePath)
    .getOrElse(
      scala.util.Failure[String](
        new IllegalStateException(
          "procedural module construction has no active transaction"
        )
      ).get
    )

  def captureAnalogProceduralSource: Option[AnalogProceduralRuntime.Source] =
    active.flatMap(_.captureAnalogProceduralSource)

  def analogDimension(value: Any): Option[String] =
    active.map(_.inferAnalogDimension(value).canonical)
  def registerDomain(domain: ClockDomain, kind: KernelDomainKind): Unit =
    active.foreach(_.registerDomain(domain, kind))

  def declare(
      value: AnyRef,
      kind: KernelSignalKind,
      dataType: Option[DataType[? <: Data]] = None,
      explicitName: Option[String] = None,
      domain: Option[ClockDomain] = None,
      attributes: Vector[(String, Any)] = Vector.empty
  ): Unit = active.foreach(
    _.registerDeclaration(value, kind, dataType, explicitName, domain, attributes)
  )

  def expression(value: AnyRef): Unit = active.foreach(_.registerExpression(value))

  def continuousOperator(
      value: KernelExpr[Real],
      operation: String,
      input: Expr[Real],
      initialValue: Option[Expr[Real]]
  ): Unit =
    active.foreach(
      _.registerContinuousOperator(value, operation, input, initialValue)
    )

  def waveformOperator(
      value: KernelExpr[Real],
      operation: String,
      inputs: Vector[Expr[Real]]
  ): Unit =
    active.foreach(_.registerWaveformOperator(value, operation, inputs))

  def waveformForbidden[A](body: => A): A = active match
    case Some(session) => session.withWaveformForbidden(body)
    case None => body

  def attachInstance(instance: Instance[? <: Module], child: Module): Unit =
    active.foreach(_.attachInstance(instance, child))

  def bindDefault(instance: Instance[?], domain: ClockDomain): Unit =
    active.foreach(_.bindDefault(instance, domain))

  def bindNamed(instance: Instance[?], requirement: ClockDomain, domain: ClockDomain): Unit =
    active.foreach(_.bindNamed(instance, requirement, domain))

  def overrideParameter(instance: Instance[?], parameter: Any, value: Any): Unit =
    active.foreach(_.overrideParameter(instance, parameter, value))

  def domainBlock[A](domain: ClockDomain)(body: => A): A = active match
    case Some(session) => session.withDomain(domain)(body)
    case None => body

  def block[A](body: => A): A = body

  def analogBlock[A](body: => A): A = active match
    case Some(session) => session.withAnalogRegion(body)
    case None => body

  def analogSemanticBlock[A](
      kind: AnalogEquationRuntime.RegionKind
  )(body: => A): A = active match
    case Some(session) => session.withAnalogSemanticRegion(kind)(body)
    case None => body

  def analogEquation(
      left: Expr[Real],
      right: Expr[Real],
      options: EquationOptions
  ): Unit = active.foreach(_.recordAnalogEquation(left, right, options))

  def initialAnalogEquation(
      left: Expr[Real],
      right: Expr[Real],
      options: InitialEquationOptions
  ): Unit = active.foreach(_.recordInitialAnalogEquation(left, right, options))

  def analogContribution(
      target: Expr[Real],
      value: Expr[Real],
      options: ContributionOptions
  ): Unit = active.foreach(_.recordAnalogContribution(target, value, options))

  def shortAnalogContribution(target: Expr[Real], value: Expr[Real]): Unit =
    active.foreach(_.recordShortAnalogContribution(target, value))

  def currentDomain: Option[ClockDomain] = active.flatMap(_.currentDomain)

  def operation(kind: String, values: Any*): Unit = active.foreach(_.operation(kind, values*))
