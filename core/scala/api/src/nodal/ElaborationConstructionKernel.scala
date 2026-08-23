package nodal

import java.lang.ScopedValue
import java.util.IdentityHashMap
import java.util.concurrent.Callable

import scala.collection.mutable

/** Private implementation kinds. They are deliberately absent from the frozen public API. */
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
  override def toString: String =
    semanticPath match
      case Some(path) => s"$code: $message [$path]"
      case None => s"$code: $message"

private[nodal] final class ConstructionException(
    val diagnostic: KernelDiagnostic
) extends IllegalArgumentException(diagnostic.toString)

private[nodal] final case class KernelDomainSnapshot(
    path: String,
    name: String,
    kind: String,
    binding: Option[String]
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
    bindings: Vector[(String, String)]
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

private[nodal] final case class KernelTopologyEdge(
    kind: String,
    left: String,
    right: String
)

private[nodal] final case class ConstructionSnapshot(
    root: String,
    modules: Vector[KernelModuleSnapshot],
    interfaceAbi: Vector[InterfaceAbiEntry],
    resolvedNets: Vector[KernelResolvedNetSnapshot],
    topology: Vector[KernelTopologyEdge]
)

private[nodal] final case class KernelElaboration(
    emission: Emission,
    snapshot: ConstructionSnapshot
)

private final case class KernelDomainRef(module: Long, index: Int)
private final case class KernelDeclarationRef(module: Long, index: Int)
private final case class KernelExpressionRef(module: Long, index: Int)

private final class MutableDomainRecord(
    val reference: KernelDomainRef,
    val domain: ClockDomain,
    val name: String,
    val kind: KernelDomainKind
)

private final class MutableDeclarationRecord(
    val reference: KernelDeclarationRef,
    val value: AnyRef,
    val kind: KernelSignalKind,
    val dataType: Option[DataType[? <: Data]],
    val explicitName: Option[String],
    val domainCandidate: Option[ClockDomain],
    val attributes: Vector[(String, Any)]
)

private final class MutableInstanceRecord(
    val ordinal: Int,
    val instance: Instance[? <: Module],
    val child: Long,
    val lexicalDomain: Option[ClockDomain]
):
  var defaultBinding: Option[ClockDomain] = None
  val namedBindings: mutable.LinkedHashMap[ClockDomain, ClockDomain] =
    mutable.LinkedHashMap.empty
  val parameterOverrides: mutable.ArrayBuffer[(Any, Any)] = mutable.ArrayBuffer.empty

private final class MutableModuleRecord(
    val handle: Long,
    val module: Module,
    val className: String,
    val parentAtConstruction: Option[Long]
):
  val domains: mutable.ArrayBuffer[MutableDomainRecord] = mutable.ArrayBuffer.empty
  val declarations: mutable.ArrayBuffer[MutableDeclarationRecord] = mutable.ArrayBuffer.empty
  val instances: mutable.ArrayBuffer[MutableInstanceRecord] = mutable.ArrayBuffer.empty
  var attached: Boolean = parentAtConstruction.isEmpty
  var expressionCount: Int = 0

private final case class MutableOperation(
    kind: String,
    values: Vector[Any]
)

/**
  * One mutable construction transaction. The object is allocated by one emit/inspect call and is
  * reachable only through a scoped immutable binding. Stable output identities are derived from
  * hierarchy and local ordinals; JVM identity is used only for transient lookup and is never emitted.
  */
private final class ConstructionSession(val options: EmitOptions):
  private var nextModuleHandle: Long = 0L
  private val modulesByHandle: mutable.LinkedHashMap[Long, MutableModuleRecord] =
    mutable.LinkedHashMap.empty
  private val moduleLookup = new IdentityHashMap[Module, java.lang.Long]()
  private val domainLookup = new IdentityHashMap[ClockDomain, KernelDomainRef]()
  private val declarationLookup = new IdentityHashMap[AnyRef, KernelDeclarationRef]()
  private val expressionLookup = new IdentityHashMap[AnyRef, KernelExpressionRef]()
  private val instanceLookup = new IdentityHashMap[Instance[?], MutableInstanceRecord]()
  private val moduleStack: mutable.ArrayBuffer[MutableModuleRecord] = mutable.ArrayBuffer.empty
  private val domainStack: mutable.ArrayBuffer[ClockDomain] = mutable.ArrayBuffer.empty
  private val operations: mutable.ArrayBuffer[MutableOperation] = mutable.ArrayBuffer.empty

  private def fail(code: String, message: String, path: Option[String] = None): Nothing =
    throw new ConstructionException(KernelDiagnostic(code, message, path))

  private def className(module: Module): String =
    val simple = module.getClass.getSimpleName.stripSuffix("$")
    if simple.nonEmpty then simple else "AnonymousModule"

  private def currentModule: MutableModuleRecord =
    moduleStack.lastOption.getOrElse(
      fail(
        "NODAL-CONSTRUCT-016",
        "hardware construction occurred without an active Module"
      )
    )

  def beginModule(module: Module): Unit =
    if moduleLookup.containsKey(module) then
      fail("NODAL-LIFECYCLE-016", "one Module object entered construction more than once")
    val handle = nextModuleHandle
    nextModuleHandle += 1
    val parent = moduleStack.lastOption.map(_.handle)
    val record = new MutableModuleRecord(handle, module, className(module), parent)
    modulesByHandle += handle -> record
    moduleLookup.put(module, java.lang.Long.valueOf(handle))
    moduleStack += record

  def registerDomain(domain: ClockDomain, kind: KernelDomainKind): Unit =
    val module = currentModule
    if domainLookup.containsKey(domain) then
      fail("NODAL-DOMAIN-016", "one ClockDomain object was registered more than once")
    if module.domains.exists(_.name == domain.name) then
      fail(
        "NODAL-DOMAIN-017",
        s"duplicate domain name '${domain.name}' in ${module.className}"
      )
    val reference = KernelDomainRef(module.handle, module.domains.size)
    module.domains += new MutableDomainRecord(reference, domain, domain.name, kind)
    domainLookup.put(domain, reference)

  def registerDeclaration(
      value: AnyRef,
      kind: KernelSignalKind,
      dataType: Option[DataType[? <: Data]],
      explicitName: Option[String],
      domainCandidate: Option[ClockDomain],
      attributes: Vector[(String, Any)]
  ): Unit =
    val module = currentModule
    if declarationLookup.containsKey(value) then
      fail(
        "NODAL-OWNERSHIP-016",
        s"${kind.label} object was registered more than once"
      )
    val reference = KernelDeclarationRef(module.handle, module.declarations.size)
    module.declarations += new MutableDeclarationRecord(
      reference,
      value,
      kind,
      dataType,
      explicitName,
      domainCandidate,
      attributes
    )
    declarationLookup.put(value, reference)

  def registerExpression(expression: AnyRef): Unit =
    moduleStack.lastOption.foreach: module =>
      val reference = KernelExpressionRef(module.handle, module.expressionCount)
      module.expressionCount += 1
      expressionLookup.put(expression, reference)

  def attachInstance(instance: Instance[? <: Module], childModule: Module): Unit =
    val childHandleValue = moduleLookup.get(childModule)
    if childHandleValue == null then
      fail(
        "NODAL-HIERARCHY-016",
        "child Module was constructed outside the active elaboration transaction"
      )
    val childHandle = childHandleValue.longValue
    val child = modulesByHandle(childHandle)
    if moduleStack.lastOption.forall(_.handle != childHandle) then
      fail(
        "NODAL-HIERARCHY-017",
        "instance(new Child) must attach the child immediately after its construction"
      )
    moduleStack.remove(moduleStack.size - 1)
    val parent = currentModule
    if child.parentAtConstruction != Some(parent.handle) then
      fail("NODAL-HIERARCHY-018", "child construction parent does not match instance owner")
    if child.attached then
      fail("NODAL-HIERARCHY-019", "child Module was attached more than once")
    val record = new MutableInstanceRecord(
      parent.instances.size,
      instance,
      childHandle,
      domainStack.lastOption
    )
    parent.instances += record
    instanceLookup.put(instance, record)
    child.attached = true

  def bindDefault(instance: Instance[?], domain: ClockDomain): Unit =
    val record = Option(instanceLookup.get(instance)).getOrElse(
      fail("NODAL-BINDING-016", "domain binding targets an unknown Instance")
    )
    record.defaultBinding = Some(domain)

  def bindNamed(
      instance: Instance[?],
      requirement: ClockDomain,
      domain: ClockDomain
  ): Unit =
    val record = Option(instanceLookup.get(instance)).getOrElse(
      fail("NODAL-BINDING-017", "named domain binding targets an unknown Instance")
    )
    val requirementRef = Option(domainLookup.get(requirement)).getOrElse(
      fail("NODAL-BINDING-018", "selector did not identify a child domain requirement")
    )
    if requirementRef.module != record.child then
      fail("NODAL-BINDING-019", "selector domain does not belong to the selected child")
    record.namedBindings.update(requirement, domain)

  def overrideParameter(instance: Instance[?], parameter: Any, value: Any): Unit =
    val record = Option(instanceLookup.get(instance)).getOrElse(
      fail("NODAL-PARAM-016", "parameter override targets an unknown Instance")
    )
    record.parameterOverrides += parameter -> value

  def withDomain[A](domain: ClockDomain)(body: => A): A =
    val reference = domainLookup.get(domain)
    if reference == null then
      fail("NODAL-DOMAIN-018", "lexical ClockDomain is not owned by this transaction")
    domainStack += domain
    try body
    finally
      val removed = domainStack.remove(domainStack.size - 1)
      if removed ne domain then
        fail("NODAL-DOMAIN-019", "lexical domain stack was corrupted")

  def currentDomain: Option[ClockDomain] = domainStack.lastOption

  def recordOperation(kind: String, values: Any*): Unit =
    operations += MutableOperation(kind, values.toVector)

  private def moduleHandle(module: Module): Long =
    val value = moduleLookup.get(module)
    if value == null then fail("NODAL-OWNERSHIP-017", "Module is not owned by this transaction")
    value.longValue

  private def modulePath(handle: Long): String =
    val record = modulesByHandle(handle)
    record.parentAtConstruction match
      case None => record.className
      case Some(parentHandle) =>
        val parent = modulesByHandle(parentHandle)
        val instance = parent.instances.find(_.child == handle).getOrElse(
          fail("NODAL-HIERARCHY-020", "constructed child is missing its Instance record")
        )
        s"${modulePath(parentHandle)}.${record.className}_${instance.ordinal}"

  private def declarationPath(reference: KernelDeclarationRef): String =
    val declaration = modulesByHandle(reference.module).declarations(reference.index)
    val localName = declaration.explicitName.getOrElse(
      s"${declaration.kind.label}_${reference.index}"
    )
    s"${modulePath(reference.module)}.$localName"

  private def pathOf(value: Any): Option[String] = value match
    case reference: AnyRef =>
      Option(declarationLookup.get(reference)).map(declarationPath)
        .orElse(
          Option(moduleLookup.get(reference.asInstanceOf[Module])).map(value => modulePath(value.longValue))
            if reference.isInstanceOf[Module]
            else None
        )
    case _ => None

  private def renderDimension(value: Any, owner: Long): String = value match
    case integer: Int => integer.toString
    case big: BigInt => big.toString
    case parameter: Param[?] =>
      Option(declarationLookup.get(parameter)).map(declarationPath).getOrElse("detached-param")
    case expression: Expr[?] =>
      expression match
        case reference: AnyRef =>
          Option(expressionLookup.get(reference)) match
            case Some(found) => s"${modulePath(found.module)}.expr_${found.index}"
            case None => "detached-expr"
    case other => renderStable(other, owner)

  private def renderStable(value: Any, owner: Long): String = value match
    case null => "null"
    case text: String => text
    case boolean: Boolean => boolean.toString
    case integer: Int => integer.toString
    case long: Long => long.toString
    case double: Double => java.lang.Double.toString(double)
    case big: BigInt => big.toString
    case dataType: DataType[?] => renderType(dataType, owner)
    case field: StructField[?] => s"${field.name}:${renderType(field.dataType, owner)}"
    case values: Seq[?] => values.map(renderStable(_, owner)).mkString("[", ",", "]")
    case values: Set[?] => values.toVector.map(renderStable(_, owner)).sorted.mkString("[", ",", "]")
    case option: Option[?] => option.map(renderStable(_, owner)).getOrElse("none")
    case enumValue: scala.reflect.Enum => enumValue.toString
    case reference: AnyRef =>
      pathOf(reference).getOrElse(reference.getClass.getSimpleName.stripSuffix("$"))
    case other => other.getClass.getSimpleName

  private def renderType(dataType: DataType[?], owner: Long): String =
    CandidateRuntime.typeDescriptor(dataType) match
      case KernelTypeDescriptor("Struct", Vector(name: String, fields: Seq[?])) =>
        val rendered = fields.collect:
          case field: StructField[?] => s"${field.name}:${renderType(field.dataType, owner)}"
        s"Struct($name{${rendered.mkString(",")}})"
      case KernelTypeDescriptor("Vec", Vector(element: DataType[?], dimensions: Seq[?])) =>
        val renderedDimensions = dimensions.map(renderDimension(_, owner)).mkString("x")
        s"Vec(${renderType(element, owner)};$renderedDimensions)"
      case KernelTypeDescriptor(kind, arguments) if arguments.nonEmpty =>
        s"$kind(${arguments.map(renderDimension(_, owner)).mkString(",")})"
      case KernelTypeDescriptor(kind, _) => kind

  private def domainReference(domain: ClockDomain): KernelDomainRef =
    Option(domainLookup.get(domain)).getOrElse(
      fail("NODAL-DOMAIN-020", "domain reference is outside the construction transaction")
    )

  private def resolveDomains(): Map[KernelDomainRef, String] =
    val resolved = mutable.LinkedHashMap.empty[KernelDomainRef, String]

    def resolveBinding(domain: ClockDomain): String =
      val reference = domainReference(domain)
      resolved.getOrElse(
        reference,
        fail(
          "NODAL-BINDING-020",
          "a child domain was bound to a domain that is not visible from its parent"
        )
      )

    def visit(handle: Long): Unit =
      val module = modulesByHandle(handle)
      val path = modulePath(handle)
      val concrete = module.domains.filter(_.kind != KernelDomainKind.Required)
      concrete.foreach: domain =>
        resolved.update(domain.reference, s"$path.${domain.name}")

      if module.parentAtConstruction.isEmpty then
        module.domains.filter(_.kind == KernelDomainKind.Required).foreach: requirement =>
          fail(
            "NODAL-ROOT-DOMAIN-016",
            s"top-level domain requirement '${requirement.name}' is unbound",
            Some(path)
          )

      module.instances.foreach: instance =>
        val child = modulesByHandle(instance.child)
        val requirements = child.domains.filter(_.kind == KernelDomainKind.Required).toVector
        val named = instance.namedBindings.toVector.map: (required, actual) =>
          domainReference(required) -> resolveBinding(actual)
        val namedMap = named.toMap
        val parentDomains = module.domains.flatMap(domain => resolved.get(domain.reference)).toVector
        requirements.foreach: requirement =>
          val inferred = namedMap.get(requirement.reference)
            .orElse(
              if requirements.size == 1 then instance.defaultBinding.map(resolveBinding)
              else None
            )
            .orElse(
              if requirements.size == 1 then instance.lexicalDomain.map(resolveBinding)
              else None
            )
            .orElse(
              if requirements.size == 1 && parentDomains.distinct.size == 1 then parentDomains.headOption
              else None
            )
          inferred match
            case Some(binding) => resolved.update(requirement.reference, binding)
            case None =>
              fail(
                "NODAL-CHILD-DOMAIN-016",
                s"child domain requirement '${requirement.name}' is unbound",
                Some(modulePath(instance.child))
              )
        visit(instance.child)

    val roots = modulesByHandle.values.filter(_.parentAtConstruction.isEmpty).toVector
    if roots.size != 1 then
      fail("NODAL-ROOT-016", s"expected one root Module, found ${roots.size}")
    visit(roots.head.handle)
    resolved.toMap

  private def declarationDomain(
      declaration: MutableDeclarationRecord,
      resolvedDomains: Map[KernelDomainRef, String]
  ): Option[String] =
    declaration.domainCandidate match
      case Some(domain) => resolvedDomains.get(domainReference(domain)).orElse(
          fail(
            "NODAL-DECL-DOMAIN-016",
            s"${declaration.kind.label} references an unresolved domain",
            Some(declarationPath(declaration.reference))
          )
        )
      case None if declaration.kind == KernelSignalKind.Register =>
        val module = modulesByHandle(declaration.reference.module)
        val choices = module.domains.flatMap(domain => resolvedDomains.get(domain.reference)).distinct
        choices.toVector match
          case Vector(single) => Some(single)
          case Vector() =>
            fail(
              "NODAL-STATE-DOMAIN-016",
              "state has no lexical or default clock domain",
              Some(declarationPath(declaration.reference))
            )
          case _ =>
            fail(
              "NODAL-MULTI-DOMAIN-016",
              "state in a multi-domain Module requires a lexical ClockDomain",
              Some(declarationPath(declaration.reference))
            )
      case None => None

  private def roleMember(access: RoleAccess): String = access match
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

  private def validateAccess(member: InterfaceMember, access: RoleAccess, path: String): Unit =
    val valid = (member, access) match
      case (_: InterfaceMember.Value[?], RoleAccess.In(_) | RoleAccess.Out(_) | RoleAccess.Observe(_)) => true
      case (_: InterfaceMember.ValidChannel[?], RoleAccess.Master(_) | RoleAccess.Slave(_) | RoleAccess.Observe(_)) => true
      case (_: InterfaceMember.StreamChannel[?], RoleAccess.Master(_) | RoleAccess.Slave(_) | RoleAccess.Observe(_)) => true
      case (_: InterfaceMember.Nested[?], RoleAccess.Nested(_, _) | RoleAccess.Observe(_)) => true
      case (_: InterfaceMember.DigitalResolved[?, ?], RoleAccess.Read(_) | RoleAccess.Drive(_) | RoleAccess.Connect(_) | RoleAccess.Observe(_)) => true
      case (_: InterfaceMember.Conservative[?], RoleAccess.Connect(_) | RoleAccess.Sense(_) | RoleAccess.Contribute(_)) => true
      case (_: InterfaceMember.SignalFlow[?], RoleAccess.In(_) | RoleAccess.Out(_) | RoleAccess.Observe(_)) => true
      case _ => false
    if !valid then
      fail(
        "NODAL-ROLE-ACCESS-016",
        s"access '${accessName(access)}' is not legal for member '${roleMember(access)}'",
        Some(path)
      )

  private def protocolEntries(
      base: String,
      emittedBase: String,
      role: String,
      access: RoleAccess,
      payloadType: DataType[?],
      domain: String,
      stream: Boolean,
      owner: Long
  ): Vector[InterfaceAbiEntry] =
    val accessLabel = accessName(access)
    val payloadAccess = access match
      case RoleAccess.Master(_) => "out"
      case RoleAccess.Slave(_) => "in"
      case RoleAccess.Observe(_) => "observe"
      case _ => accessLabel
    val readyAccess = access match
      case RoleAccess.Master(_) => "in"
      case RoleAccess.Slave(_) => "out"
      case RoleAccess.Observe(_) => "observe"
      case _ => accessLabel
    val common = Vector(
      InterfaceAbiEntry(s"$base.valid", s"${emittedBase}_valid", role, payloadAccess, "Bool", domain),
      InterfaceAbiEntry(
        s"$base.payload",
        s"${emittedBase}_payload",
        role,
        payloadAccess,
        renderType(payloadType, owner),
        domain
      )
    )
    if stream then
      common :+ InterfaceAbiEntry(
        s"$base.ready",
        s"${emittedBase}_ready",
        role,
        readyAccess,
        "Bool",
        domain
      )
    else common

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
      protocolEntries(logical, emitted, role, access, valid.payloadType, domain, stream = false, owner)
    case stream: InterfaceMember.StreamChannel[?] =>
      protocolEntries(logical, emitted, role, access, stream.payloadType, domain, stream = true, owner)
    case nested: InterfaceMember.Nested[?] =>
      nested.definition.members.toVector.flatMap: child =>
        val childName = interfaceMemberName(child)
        expandMember(
          child,
          access,
          s"$logical.$childName",
          s"${emitted}_$childName",
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

  private def interfaceMemberName(member: InterfaceMember): String = member match
    case value: InterfaceMember.Value[?] => value.name
    case valid: InterfaceMember.ValidChannel[?] => valid.name
    case stream: InterfaceMember.StreamChannel[?] => stream.name
    case nested: InterfaceMember.Nested[?] => nested.name
    case digital: InterfaceMember.DigitalResolved[?, ?] => digital.name
    case conservative: InterfaceMember.Conservative[?] => conservative.name
    case signal: InterfaceMember.SignalFlow[?] => signal.name

  private def interfaceAbi(
      resolvedDomains: Map[KernelDomainRef, String]
  ): Vector[InterfaceAbiEntry] =
    modulesByHandle.values.toVector.flatMap: module =>
      module.declarations.toVector.flatMap: declaration =>
        val endpoint = declaration.value match
          case port: InterfacePort[?, ?] =>
            Some((port.definition, port.role, port.name, port.domain, Option.empty[Any]))
          case array: InterfaceArray[?, ?] =>
            Some((array.definition, array.role, array.name, array.domain, Some(array.count)))
          case _ => None
        endpoint.toVector.flatMap: (definition, role, name, domain, count) =>
          val memberNames = definition.members.map(interfaceMemberName)
          if memberNames.distinct.size != memberNames.size then
            fail(
              "NODAL-INTERFACE-MEMBER-016",
              s"Interface '${definition.name}' has duplicate member names",
              Some(declarationPath(declaration.reference))
            )
          val grouped = role.access.groupBy(roleMember)
          val missing = memberNames.filterNot(grouped.contains)
          val unknown = grouped.keySet.diff(memberNames.toSet)
          val duplicate = grouped.collect { case (member, accesses) if accesses.size != 1 => member }
          if missing.nonEmpty || unknown.nonEmpty || duplicate.nonEmpty then
            fail(
              "NODAL-ROLE-COMPLETE-016",
              s"role '${role.name}' is incomplete: missing=${missing.mkString(",")}, unknown=${unknown.mkString(",")}, duplicate=${duplicate.mkString(",")}",
              Some(declarationPath(declaration.reference))
            )
          val resolvedDomain = resolvedDomains.getOrElse(
            domainReference(domain),
            fail(
              "NODAL-INTERFACE-DOMAIN-016",
              s"Interface endpoint '$name' has an unresolved domain"
            )
          )
          val suffix = count.map(value => s"[${renderDimension(value, module.handle)}]").getOrElse("")
          definition.members.toVector.flatMap: member =>
            val memberName = interfaceMemberName(member)
            val access = grouped(memberName).head
            validateAccess(member, access, s"${modulePath(module.handle)}.$name.$memberName")
            expandMember(
              member,
              access,
              s"${modulePath(module.handle)}.$name$suffix.$memberName",
              s"${name}_${memberName}",
              role.name,
              resolvedDomain,
              module.handle
            )
    .sortBy(_.logicalPath)

  private def resolvedNetSnapshots(): Vector[KernelResolvedNetSnapshot] =
    modulesByHandle.values.toVector.flatMap: module =>
      module.declarations.collect:
        case declaration if declaration.value.isInstanceOf[DigitalInout[?, ?]] =>
          val endpoint = declaration.value.asInstanceOf[DigitalInout[Bits, DriveMode]]
          val path = declarationPath(declaration.reference)
          val related = operations.collect:
            case operation if operation.values.exists:
                case reference: AnyRef => reference eq declaration.value
                case _ => false
              => operation.kind
          KernelResolvedNetSnapshot(
            path,
            renderType(endpoint.dataType, module.handle),
            endpoint.mode.name,
            endpoint.placement.toString,
            endpoint.profile.toString,
            related.toVector
          )
    .sortBy(_.path)

  private def topologyEdges(): Vector[KernelTopologyEdge] =
    operations.toVector.flatMap: operation =>
      if Set("node-connect", "terminal-connect", "inout-pass-through").contains(operation.kind) &&
          operation.values.size >= 2
      then
        for
          left <- pathOf(operation.values(0))
          right <- pathOf(operation.values(1))
        yield KernelTopologyEdge(operation.kind, left, right)
      else None
    .sortBy(edge => (edge.kind, edge.left, edge.right))

  private def moduleSnapshots(
      resolvedDomains: Map[KernelDomainRef, String]
  ): Vector[KernelModuleSnapshot] =
    modulesByHandle.values.toVector.sortBy(record => modulePath(record.handle)).map: module =>
      val domains = module.domains.toVector.map: domain =>
        val resolved = resolvedDomains.get(domain.reference)
        val ownPath = s"${modulePath(module.handle)}.${domain.name}"
        KernelDomainSnapshot(
          ownPath,
          domain.name,
          domain.kind.label,
          if domain.kind == KernelDomainKind.Required then resolved else None
        )
      val declarations = module.declarations.toVector.map: declaration =>
        KernelDeclarationSnapshot(
          declarationPath(declaration.reference),
          declaration.kind.label,
          declaration.explicitName.getOrElse(
            s"${declaration.kind.label}_${declaration.reference.index}"
          ),
          declaration.dataType.map(renderType(_, module.handle)),
          declarationDomain(declaration, resolvedDomains),
          declaration.attributes.map: (key, value) => key -> renderStable(value, module.handle)
        )
      val instances = module.instances.toVector.map: instance =>
        val child = modulesByHandle(instance.child)
        val bindings = child.domains.filter(_.kind == KernelDomainKind.Required).flatMap: domain =>
          resolvedDomains.get(domain.reference).map(domain.name -> _)
        KernelInstanceSnapshot(
          s"${modulePath(module.handle)}.instance_${instance.ordinal}",
          modulePath(instance.child),
          instance.lexicalDomain.flatMap(domain => resolvedDomains.get(domainReference(domain))),
          bindings.toVector
        )
      KernelModuleSnapshot(
        modulePath(module.handle),
        module.className,
        domains,
        declarations,
        instances
      )

  private def designKind(snapshot: ConstructionSnapshot): DesignKind =
    val kinds = snapshot.modules.flatMap(_.declarations.map(_.kind)).toSet
    val analogKinds = Set("analog-input", "analog-output", "analog-inout", "analog-node", "conservative-terminal", "analog-signal")
    val digitalKinds = kinds -- analogKinds
    val analog = kinds.exists(analogKinds.contains)
    val digital = digitalKinds.nonEmpty || snapshot.interfaceAbi.nonEmpty || snapshot.resolvedNets.nonEmpty
    (digital, analog) match
      case (true, true) => DesignKind.MixedSignal
      case (true, false) => DesignKind.DigitalOnly
      case (false, true) => DesignKind.AnalogOnly
      case _ => DesignKind.Unsupported

  def finish(root: Module): KernelElaboration =
    val rootHandle = moduleHandle(root)
    if moduleStack.size != 1 || moduleStack.last.handle != rootHandle then
      val open = moduleStack.map(_.className).mkString(" -> ")
      fail(
        "NODAL-LIFECYCLE-017",
        s"construction closed with unattached or unclosed Modules: $open"
      )
    moduleStack.remove(moduleStack.size - 1)
    if domainStack.nonEmpty then
      fail("NODAL-LIFECYCLE-018", "construction closed with an active lexical domain")
    modulesByHandle.values.filterNot(_.attached).foreach: module =>
      fail(
        "NODAL-HIERARCHY-021",
        s"Module '${module.className}' was constructed but not attached"
      )

    val resolvedDomains = resolveDomains()
    val modules = moduleSnapshots(resolvedDomains)
    val abi = interfaceAbi(resolvedDomains)
    val snapshot = ConstructionSnapshot(
      modulePath(rootHandle),
      modules,
      abi,
      resolvedNetSnapshots(),
      topologyEdges()
    )
    val kind = designKind(snapshot)
    val report = DesignReport(
      designKind = kind,
      selectedBackend = options.backend,
      digitalProfile = if kind == DesignKind.AnalogOnly || kind == DesignKind.Unsupported then None else Some(options.digitalProfile),
      interfaceAbi = abi,
      sourceMap = Vector.empty,
      schedules = Vector.empty
    )
    KernelElaboration(Emission(Vector.empty, report), snapshot)

private[nodal] object ConstructionKernel:
  private val Current: ScopedValue[ConstructionSession] =
    ScopedValue.newInstance[ConstructionSession]()

  private def active: Option[ConstructionSession] =
    if Current.isBound then Some(Current.get) else None

  private def inSession[A](session: ConstructionSession)(body: => A): A =
    ScopedValue.where(Current, session).call(
      new Callable[A]:
        override def call(): A = body
    )

  private def elaborate(
      top: => Module,
      options: EmitOptions
  ): KernelElaboration =
    val session = new ConstructionSession(options)
    inSession(session):
      val root = top
      session.finish(root)

  def emit(top: => Module, options: EmitOptions): Emission =
    elaborate(top, options).emission

  def inspect(top: => Module, options: EmitOptions = EmitOptions()): ConstructionSnapshot =
    elaborate(top, options).snapshot

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

  def expression(expression: AnyRef): Unit = active.foreach(_.registerExpression(expression))

  def attachInstance(instance: Instance[? <: Module], child: Module): Unit =
    active.foreach(_.attachInstance(instance, child))

  def bindDefault(instance: Instance[?], domain: ClockDomain): Unit =
    active.foreach(_.bindDefault(instance, domain))

  def bindNamed(
      instance: Instance[?],
      requirement: ClockDomain,
      domain: ClockDomain
  ): Unit = active.foreach(_.bindNamed(instance, requirement, domain))

  def overrideParameter(instance: Instance[?], parameter: Any, value: Any): Unit =
    active.foreach(_.overrideParameter(instance, parameter, value))

  def domainBlock[A](domain: ClockDomain)(body: => A): A =
    active match
      case Some(session) => session.withDomain(domain)(body)
      case None => body

  def block[A](body: => A): A = body

  def currentDomain: Option[ClockDomain] = active.flatMap(_.currentDomain)

  def operation(kind: String, values: Any*): Unit =
    active.foreach(_.recordOperation(kind, values*))
