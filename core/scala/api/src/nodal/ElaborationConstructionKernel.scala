package nodal

import java.lang.ScopedValue
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

private[nodal] final case class ConstructionSnapshot(
    root: String,
    modules: Vector[KernelModuleSnapshot],
    interfaceAbi: Vector[InterfaceAbiEntry],
    resolvedNets: Vector[KernelResolvedNetSnapshot],
    topology: Vector[KernelTopologyEdge],
    names: Vector[KernelNameSnapshot] = Vector.empty,
    origins: Vector[KernelOriginSnapshot] = Vector.empty,
    generatedNames: Vector[KernelGeneratedNameSnapshot] = Vector.empty,
    sourceMap: Vector[SourceMapEntry] = Vector.empty
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

  def registerExpression(value: AnyRef): Unit = moduleStack.lastOption.foreach: module =>
    val reference = ExpressionRef(module.handle, module.expressionCount)
    module.expressionCount += 1
    expressionIds.put(value, reference)
    val operands = value match
      case expression: KernelExpr[?] => expression.operands
      case _ => Vector.empty
    semanticOrigin.captureExpression(module.handle, reference.index, value, operands)

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

  def currentDomain: Option[ClockDomain] = domainStack.lastOption

  def operation(kind: String, values: Any*): Unit =
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
        val instance = parent.instances.find(_.child == handle).getOrElse(
          fail("NODAL-HIERARCHY-020", "child Module has no Instance record")
        )
        s"${provisionalModulePath(parentHandle)}.${record.className}_${instance.ordinal}"

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
    val analog = kinds.exists(analogKinds.contains)
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
      modulePath(rootHandle),
      modules,
      abi,
      resolvedNets(),
      topology(),
      semantic.names,
      semantic.origins,
      semantic.generatedNames,
      semantic.sourceMap
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

  def currentDomain: Option[ClockDomain] = active.flatMap(_.currentDomain)

  def operation(kind: String, values: Any*): Unit = active.foreach(_.operation(kind, values*))
