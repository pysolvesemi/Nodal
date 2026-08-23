#!/usr/bin/env python3
"""Consolidate Increment 16 into one compile-oriented deterministic implementation."""

from __future__ import annotations

import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

KERNEL = r'''package nodal

import java.lang.ScopedValue
import java.util.IdentityHashMap
import java.util.concurrent.Callable

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

private[nodal] final case class KernelTopologyEdge(kind: String, left: String, right: String)

private[nodal] final case class ConstructionSnapshot(
    root: String,
    modules: Vector[KernelModuleSnapshot],
    interfaceAbi: Vector[InterfaceAbiEntry],
    resolvedNets: Vector[KernelResolvedNetSnapshot],
    topology: Vector[KernelTopologyEdge]
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
    val instance: AnyRef,
    val child: Long,
    val lexicalDomain: Option[ClockDomain]
):
  var defaultBinding: Option[ClockDomain] = None
  val namedBindings: mutable.ArrayBuffer[(ClockDomain, ClockDomain)] = mutable.ArrayBuffer.empty
  val parameterOverrides: mutable.ArrayBuffer[(Any, Any)] = mutable.ArrayBuffer.empty

private final class ModuleRecord(
    val handle: Long,
    val module: Module,
    val className: String,
    val parentAtConstruction: Option[Long]
):
  val domains: mutable.ArrayBuffer[DomainRecord] = mutable.ArrayBuffer.empty
  val declarations: mutable.ArrayBuffer[DeclarationRecord] = mutable.ArrayBuffer.empty
  val instances: mutable.ArrayBuffer[InstanceRecord] = mutable.ArrayBuffer.empty
  var expressionCount: Int = 0
  var attached: Boolean = parentAtConstruction.isEmpty

private final case class Operation(kind: String, values: Vector[Any])

/**
  * One mutable transaction. JVM identity is used only for transient lookup; stable paths use
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

  private def fail(code: String, message: String, path: Option[String] = None): Nothing =
    throw new ConstructionException(KernelDiagnostic(code, message, path))

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
      module,
      moduleName(module),
      moduleStack.lastOption.map(_.handle)
    )
    records += handle -> record
    moduleIds.put(module, java.lang.Long.valueOf(handle))
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

  def registerExpression(value: AnyRef): Unit = moduleStack.lastOption.foreach: module =>
    val reference = ExpressionRef(module.handle, module.expressionCount)
    module.expressionCount += 1
    expressionIds.put(value, reference)

  def attachInstance(instance: AnyRef, childModule: Module): Unit =
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
      instance,
      childHandle,
      domainStack.lastOption
    )
    parent.instances += record
    child.attached = true
    instanceIds.put(instance, record)

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
    domainRef(domain)
    domainStack += domain
    try body
    finally
      val removed = domainStack.remove(domainStack.size - 1)
      if removed ne domain then fail("NODAL-DOMAIN-019", "lexical domain stack is corrupt")

  def currentDomain: Option[ClockDomain] = domainStack.lastOption

  def operation(kind: String, values: Any*): Unit = operations += Operation(kind, values.toVector)

  private def modulePath(handle: Long): String =
    val record = records(handle)
    record.parentAtConstruction match
      case None => record.className
      case Some(parentHandle) =>
        val parent = records(parentHandle)
        val instance = parent.instances.find(_.child == handle).getOrElse(
          fail("NODAL-HIERARCHY-020", "child Module has no Instance record")
        )
        s"${modulePath(parentHandle)}.${record.className}_${instance.ordinal}"

  private def declarationPath(reference: DeclarationRef): String =
    val declaration = records(reference.module).declarations(reference.index)
    val name = declaration.explicitName.getOrElse(
      s"${declaration.kind.label}_${reference.index}"
    )
    s"${modulePath(reference.module)}.$name"

  private def pathOf(value: Any): Option[String] = value match
    case reference: AnyRef =>
      Option(declarationIds.get(reference)).map(declarationPath).orElse(
        Option(moduleIds.get(reference)).map(handle => modulePath(handle.longValue))
      )
    case _ => None

  private def stableClassName(value: AnyRef): String =
    val name = value.getClass.getSimpleName.stripSuffix("$")
    if name.isEmpty then value.getClass.getName.split('.').last.stripSuffix("$") else name

  private def renderAny(value: Any, owner: Long): String = value match
    case null => "null"
    case text: String => text
    case boolean: Boolean => boolean.toString
    case integer: Int => integer.toString
    case long: Long => long.toString
    case double: Double => java.lang.Double.toString(double)
    case big: BigInt => big.toString
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
        val name = descriptor.arguments.headOption.collect { case value: String => value }.getOrElse("Struct")
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
        resolved.update(domain.reference, s"$path.${domain.name}")

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
        val parentVisible = module.domains.flatMap(domain => resolved.get(domain.reference)).distinct.toVector
        requirements.foreach: requirement =>
          val named = instance.namedBindings.collectFirst:
            case (selected, actual) if domainRef(selected) == requirement.reference => visible(actual)
          val binding = named
            .orElse(if requirements.size == 1 then instance.defaultBinding.map(visible) else None)
            .orElse(if requirements.size == 1 then instance.lexicalDomain.map(visible) else None)
            .orElse(if requirements.size == 1 && parentVisible.size == 1 then parentVisible.headOption else None)
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
      val choices = module.domains.flatMap(domain => resolved.get(domain.reference)).distinct.toVector
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
    case _: InterfaceMember.ValidChannel[?] | _: InterfaceMember.StreamChannel[?] => access match
        case RoleAccess.Master(_) | RoleAccess.Slave(_) | RoleAccess.Observe(_) => true
        case _ => false
    case _: InterfaceMember.Nested[?] => access match
        case RoleAccess.Nested(_, _) | RoleAccess.Observe(_) => true
        case _ => false
    case _: InterfaceMember.DigitalResolved[?, ?] => access match
        case RoleAccess.Read(_) | RoleAccess.Drive(_) | RoleAccess.Connect(_) | RoleAccess.Observe(_) => true
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
    val duplicate = grouped.collect { case (member, accesses) if accesses.size != 1 => member }.toVector
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
    records.values.toVector.flatMap: module =>
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
    .sortBy(_.logicalPath)

  private def attribute(
      declaration: DeclarationRecord,
      name: String,
      owner: Long,
      default: String
  ): String = declaration.attributes.find(_._1 == name)
    .map(value => renderAny(value._2, owner))
    .getOrElse(default)

  private def resolvedNets(): Vector[KernelResolvedNetSnapshot] =
    records.values.toVector.flatMap: module =>
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
    .sortBy(_.path)

  private def topology(): Vector[KernelTopologyEdge] = operations.toVector.flatMap: operation =>
    if Set("node-connect", "terminal-connect", "inout-pass-through").contains(operation.kind) &&
        operation.values.size >= 2
    then
      (pathOf(operation.values(0)), pathOf(operation.values(1))) match
        case (Some(left), Some(right)) => Some(KernelTopologyEdge(operation.kind, left, right))
        case _ => None
    else None
  .sortBy(edge => (edge.kind, edge.left, edge.right))

  private def snapshots(resolved: Map[DomainRef, String]): Vector[KernelModuleSnapshot] =
    records.values.toVector.sortBy(record => modulePath(record.handle)).map: module =>
      val domains = module.domains.toVector.map: domain =>
        KernelDomainSnapshot(
          s"${modulePath(module.handle)}.${domain.name}",
          domain.name,
          domain.kind.label,
          if domain.kind == KernelDomainKind.Required then resolved.get(domain.reference) else None
        )
      val declarations = module.declarations.toVector.map: declaration =>
        KernelDeclarationSnapshot(
          declarationPath(declaration.reference),
          declaration.kind.label,
          declaration.explicitName.getOrElse(
            s"${declaration.kind.label}_${declaration.reference.index}"
          ),
          declaration.dataType.map(renderType(_, module.handle)),
          declarationDomain(declaration, resolved),
          declaration.attributes.map(value => value._1 -> renderAny(value._2, module.handle))
        )
      val instances = module.instances.toVector.map: instance =>
        val child = records(instance.child)
        val bindings = child.domains.filter(_.kind == KernelDomainKind.Required).flatMap: domain =>
          resolved.get(domain.reference).map(domain.name -> _)
        KernelInstanceSnapshot(
          s"${modulePath(module.handle)}.instance_${instance.ordinal}",
          modulePath(instance.child),
          instance.lexicalDomain.flatMap(domain => resolved.get(domainRef(domain))),
          bindings.toVector
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
    val digital = (kinds -- analogKinds).nonEmpty || snapshot.interfaceAbi.nonEmpty || snapshot.resolvedNets.nonEmpty
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

    val resolved = resolveDomains()
    val modules = snapshots(resolved)
    val abi = interfaceAbi(resolved)
    val snapshot = ConstructionSnapshot(
      modulePath(rootHandle),
      modules,
      abi,
      resolvedNets(),
      topology()
    )
    val kind = classify(snapshot)
    val report = DesignReport(
      designKind = kind,
      selectedBackend = options.backend,
      digitalProfile =
        if kind == DesignKind.AnalogOnly || kind == DesignKind.Unsupported then None
        else Some(options.digitalProfile),
      interfaceAbi = abi,
      sourceMap = Vector.empty,
      schedules = Vector.empty
    )
    Emission(Vector.empty, report) -> snapshot

private[nodal] object ConstructionKernel:
  private val Current: ScopedValue[ConstructionSession] = ScopedValue.newInstance()

  private def active: Option[ConstructionSession] =
    if Current.isBound then Some(Current.get) else None

  private def elaborate(top: => Module, options: EmitOptions): (Emission, ConstructionSnapshot) =
    val session = new ConstructionSession(options)
    ScopedValue.where(Current, session).call(
      new Callable[(Emission, ConstructionSnapshot)]:
        override def call(): (Emission, ConstructionSnapshot) =
          val root = top
          session.finish(root)
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
'''

TESTS = r'''package nodal

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

import utest.*

sealed trait KernelPayload extends Struct
sealed trait KernelLink extends Interface
sealed trait KernelProducer extends RoleKind
sealed trait KernelConsumer extends RoleKind
sealed trait NestedKernelLink extends Interface
sealed trait NestedKernelProducer extends RoleKind
sealed trait IncompleteKernelRole extends RoleKind

object KernelContracts:
  val payloadType: DataType[KernelPayload] = Struct(
    "KernelPayload",
    StructField("data", UInt(16)),
    StructField("tag", UInt(4))
  ).asInstanceOf[DataType[KernelPayload]]

  val link: InterfaceType[KernelLink] = Interface[KernelLink]("KernelLink")(
    InterfaceMember.value("control", UInt(8)),
    InterfaceMember.stream("payload", payloadType)
  )

  val producer: Role[KernelProducer] = Role[KernelProducer]("producer")(
    RoleAccess.Out("control"),
    RoleAccess.Master("payload")
  )

  val consumer: Role[KernelConsumer] = Role[KernelConsumer]("consumer")(
    RoleAccess.In("control"),
    RoleAccess.Slave("payload")
  )

  val nested: InterfaceType[NestedKernelLink] = Interface[NestedKernelLink]("NestedKernelLink")(
    InterfaceMember.nested("link", link),
    InterfaceMember.value("enable", Bool)
  )

  val nestedProducer: Role[NestedKernelProducer] = Role[NestedKernelProducer]("nestedProducer")(
    RoleAccess.Nested("link", "producer"),
    RoleAccess.Out("enable")
  )

  val incomplete: Role[IncompleteKernelRole] = Role[IncompleteKernelRole]("incomplete")(
    RoleAccess.Out("control")
  )

final class KernelLeaf extends Module:
  val core: ClockDomain = ClockDomain.required("core")
  val interface: InterfacePort[KernelLink, KernelProducer] =
    interfacePort(KernelContracts.link, KernelContracts.producer, "link", core)
  val nestedInterface: InterfacePort[NestedKernelLink, NestedKernelProducer] =
    interfacePort(KernelContracts.nested, KernelContracts.nestedProducer, "nested", core)
  val memory: Mem[UInt] = Mem(
    UInt(16),
    depth = 64,
    readLatency = 1,
    readUnderWrite = ReadUnderWrite.ReadFirst,
    ordering = MemoryOrdering.Ordered,
    domain = core
  )

  core:
    val state = Reg(0.U(16))
    state := 1.U(16)

final class KernelTop extends Module:
  val root: ClockDomain = ClockDomain.external(
    "root",
    edge = ClockEdge.Rising,
    reset = ResetConfig.asyncAssertSyncDeassert(2),
    resetPolarity = ResetPolarity.ActiveLow,
    frequency = 400.MHz
  )
  val shaped: Signal[Vec[UInt]] = wire(Vec(UInt(8), 2, 3))
  val padOuter: DigitalInout[Bits, DriveMode.PushPull] = digitalInout(
    Bits(1),
    DriveMode.PushPull,
    InoutPlacement.TopLevel,
    ResolutionProfile.FourState,
    "padOuter"
  )
  val padInner: DigitalInout[Bits, DriveMode.PushPull] = digitalInout(
    Bits(1),
    DriveMode.PushPull,
    InoutPlacement.HierarchyPassThrough,
    ResolutionProfile.FourState,
    "padInner"
  )
  val terminalA = terminal(Electrical, "a", TerminalAccess.connect)
  val terminalB = terminal(Electrical, "b", TerminalAccess.connect)

  passThrough(padOuter, padInner)
  terminalA.connectTo(terminalB)

  root:
    val counter = Reg(0.U(8))
    counter := counter + 1.U(8)
    val leaf = instance(new KernelLeaf)
    leaf.domain(root)

final class UnboundKernelRoot extends Module:
  val missing: ClockDomain = ClockDomain.required("missing")

  missing:
    val state = Reg(0.U(1))
    state := 1.U(1)

final class AmbiguousKernelRoot extends Module:
  val fast: ClockDomain = ClockDomain.external(
    "fast",
    edge = ClockEdge.Rising,
    reset = ResetConfig.sync,
    frequency = 800.MHz
  )
  val slow: ClockDomain = ClockDomain.external(
    "slow",
    edge = ClockEdge.Rising,
    reset = ResetConfig.sync,
    frequency = 100.MHz
  )
  val state: Register[UInt] = Reg(0.U(8))

  state := 1.U(8)

final class IncompleteRoleRoot extends Module:
  val root: ClockDomain = ClockDomain.external(
    "root",
    edge = ClockEdge.Rising,
    reset = ResetConfig.sync,
    frequency = 100.MHz
  )
  val interface: InterfacePort[KernelLink, IncompleteKernelRole] =
    interfacePort(KernelContracts.link, KernelContracts.incomplete, "broken", root)

object ConstructionKernelTests extends TestSuite:
  val tests: Tests = Tests:
    test("deterministic hierarchy, domains, shapes, interfaces and topology"):
      val first = ConstructionKernel.inspect(new KernelTop)
      val second = ConstructionKernel.inspect(new KernelTop)

      assert(first == second)
      assert(first.root == "KernelTop")
      assert(first.modules.map(_.path) == Vector("KernelTop", "KernelTop.KernelLeaf_0"))
      assert(first.interfaceAbi.size == 9)
      assert(first.interfaceAbi.exists(_.logicalPath.endsWith("link.payload.ready")))
      assert(first.interfaceAbi.exists(_.logicalPath.endsWith("nested.link.payload.ready")))
      assert(first.resolvedNets.map(_.path) == Vector("KernelTop.padInner", "KernelTop.padOuter"))
      assert(first.topology.exists(_.kind == "inout-pass-through"))
      assert(first.topology.exists(_.kind == "terminal-connect"))

      val shaped = first.modules.head.declarations.find(_.name == "wire_0").get
      assert(shaped.dataType.contains("Vec(UInt(8);2x3)"))

      val leaf = first.modules(1)
      assert(leaf.domains.exists(domain => domain.name == "core" && domain.binding.contains("KernelTop.root")))
      assert(leaf.declarations.exists(declaration => declaration.kind == "register" && declaration.domain.contains("KernelTop.root")))
      assert(leaf.declarations.exists(declaration => declaration.kind == "memory" && declaration.domain.contains("KernelTop.root")))

    test("public emit publishes construction classification and logical ABI"):
      val emission = Nodal.emit(new KernelTop)
      assert(emission.files.isEmpty)
      assert(emission.report.designKind == DesignKind.MixedSignal)
      assert(emission.report.selectedBackend == Backend.Auto)
      assert(emission.report.interfaceAbi.nonEmpty)
      assert(emission.report.sourceMap.isEmpty)
      assert(emission.report.schedules.isEmpty)

    test("unbound root requirement is rejected transactionally"):
      val failure = intercept[ConstructionException]:
        ConstructionKernel.inspect(new UnboundKernelRoot)
      assert(failure.diagnostic.code == "NODAL-ROOT-DOMAIN-016")

      val recovered = ConstructionKernel.inspect(new KernelTop)
      assert(recovered.root == "KernelTop")

    test("unqualified multi-domain state is rejected"):
      val failure = intercept[ConstructionException]:
        ConstructionKernel.inspect(new AmbiguousKernelRoot)
      assert(failure.diagnostic.code == "NODAL-MULTI-DOMAIN-016")

    test("exported interface roles must be complete"):
      val failure = intercept[ConstructionException]:
        ConstructionKernel.inspect(new IncompleteRoleRoot)
      assert(failure.diagnostic.code == "NODAL-ROLE-COMPLETE-016")

    test("parallel elaborations do not share mutable construction state"):
      val snapshots = Await.result(
        Future.sequence(Vector.fill(8)(Future(ConstructionKernel.inspect(new KernelTop)))),
        20.seconds
      )
      assert(snapshots.distinct.size == 1)
'''

CHECKER = r'''#!/usr/bin/env python3
"""Validate Increment 16 construction-kernel contracts and optional execution."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Problem:
    code: str
    message: str


def text(root: Path, path: str, problems: list[Problem], code: str) -> str:
    try:
        return (root / path).read_text(encoding="utf-8")
    except OSError as error:
        problems.append(Problem(code, f"cannot read {path}: {error}"))
        return ""


def object_json(root: Path, path: str, problems: list[Problem], code: str) -> dict[str, object]:
    try:
        value = json.loads((root / path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        problems.append(Problem(code, f"cannot load {path}: {error}"))
        return {}
    if not isinstance(value, dict):
        problems.append(Problem(code, f"{path} is not a JSON object"))
        return {}
    return value


def require(
    source: str,
    fragments: tuple[str, ...],
    problems: list[Problem],
    code: str,
    label: str,
) -> None:
    missing = [fragment for fragment in fragments if fragment not in source]
    if missing:
        problems.append(Problem(code, f"{label} lacks: {', '.join(missing)}"))


def validate_files(root: Path = ROOT) -> list[Problem]:
    problems: list[Problem] = []
    kernel = text(root, "core/scala/api/src/nodal/ElaborationConstructionKernel.scala", problems, "NODAL-INC16-001")
    candidate = text(root, "core/scala/api/src/nodal/CandidateApi.scala", problems, "NODAL-INC16-002")
    core = text(root, "core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala", problems, "NODAL-INC16-003")
    interface = text(root, "core/scala/api/src/nodal/PipelineInterfaceCandidateApi.scala", problems, "NODAL-INC16-004")
    compiler = text(root, "core/scala/api/src/nodal/CompilerApi.scala", problems, "NODAL-INC16-005")
    tests = text(root, "core/scala/testkit/test/src/nodal/ConstructionKernelTests.scala", problems, "NODAL-INC16-006")
    documentation = text(root, "docs/implementation/increment16-construction-kernel.md", problems, "NODAL-INC16-007")
    gate = text(root, "docs/design-gates/NodalConstructionKernel-DG-v1.0.md", problems, "NODAL-INC16-008")
    roadmap = text(root, "docs/roadmap/nodal-development-todo.md", problems, "NODAL-INC16-009")
    predecessor = text(root, "scripts/check_increment15.py", problems, "NODAL-INC16-010")
    command = text(root, "scripts/nodal.py", problems, "NODAL-INC16-011")
    manifest = object_json(root, "tests/api/fixtures/increment16/manifest.json", problems, "NODAL-INC16-012")
    public_manifest = object_json(root, "core/scala/api/public-api-v0.3.json", problems, "NODAL-INC16-013")

    require(
        kernel,
        (
            "java.lang.ScopedValue",
            "final class ConstructionSession",
            "JVM identity is used only for transient lookup",
            "def beginModule(module: Module)",
            "def attachInstance",
            "def resolveDomains",
            "NODAL-ROOT-DOMAIN-016",
            "NODAL-MULTI-DOMAIN-016",
            "NODAL-ROLE-COMPLETE-016",
            "private def interfaceAbi",
            "private def resolvedNets",
            "private def topology",
            "def finish(root: Module)",
            "def inspect(top: => Module",
        ),
        problems,
        "NODAL-INC16-014",
        "construction kernel",
    )
    for forbidden in ("new ThreadLocal", "DynamicVariable", "System.identityHashCode", ".hashCode()"):
        if forbidden in kernel:
            problems.append(Problem("NODAL-INC16-015", f"prohibited mechanism: {forbidden}"))

    require(
        candidate,
        (
            "CandidateRuntime.beginModule(this)",
            "CandidateRuntime.registerDomain(this, kind)",
            "CandidateRuntime.domainBlock(this, body)",
            "CandidateRuntime.attachInstance(this, module)",
            "CandidateRuntime.currentDomain",
            "KernelSignalKind.Register",
        ),
        problems,
        "NODAL-INC16-016",
        "candidate hooks",
    )
    require(
        core,
        (
            'CandidateRuntime.dataType[SInt]("SInt", width)',
            'CandidateRuntime.dataType[Vec[A]]("Vec", element, dimensions.toSeq)',
            "KernelSignalKind.Memory",
        ),
        problems,
        "NODAL-INC16-017",
        "core hooks",
    )
    require(
        interface,
        (
            'CandidateRuntime.dataType[Struct]("Struct", name, fields.toSeq)',
            "KernelSignalKind.InterfacePort",
            "KernelSignalKind.InterfaceArray",
            "KernelSignalKind.DigitalInout",
            "KernelSignalKind.ConservativeTerminal",
            'ConstructionKernel.operation("inout-pass-through"',
            'ConstructionKernel.operation("terminal-connect"',
        ),
        problems,
        "NODAL-INC16-018",
        "interface hooks",
    )
    require(
        compiler,
        ("object Nodal:", "ConstructionKernel.emit(top, options)"),
        problems,
        "NODAL-INC16-019",
        "compiler entry point",
    )
    require(
        tests,
        (
            "deterministic hierarchy, domains, shapes, interfaces and topology",
            "unbound root requirement is rejected transactionally",
            "unqualified multi-domain state is rejected",
            "exported interface roles must be complete",
            "parallel elaborations do not share mutable construction state",
        ),
        problems,
        "NODAL-INC16-020",
        "Scala tests",
    )
    require(
        documentation,
        (
            "Each `Nodal.emit` or private test inspection allocates one construction transaction",
            "identity values, hash codes, reflection order, and allocation addresses are never emitted",
            "Increment 16 does not implement source spans",
        ),
        problems,
        "NODAL-INC16-021",
        "implementation documentation",
    )
    require(
        gate,
        (
            "**Public API:** unchanged at 0.3",
            "no public implicit, given, mutable global, or thread-local",
            "Temporary identity maps locate live Scala objects",
        ),
        problems,
        "NODAL-INC16-022",
        "implementation gate",
    )
    require(
        predecessor,
        ("- [x] **Increment 16 — ", "roadmap does not retain one Increment 16 kernel"),
        problems,
        "NODAL-INC16-023",
        "Increment 15 successor safety",
    )
    require(
        command,
        (
            '_python(root, "check_increment13.py", "--compile-negative")',
            '_python(root, "check_increment14.py", "--compile-negative")',
            '_python(root, "check_increment15.py", "--compile-negative")',
            '_python(root, "check_increment16.py")',
        ),
        problems,
        "NODAL-INC16-024",
        "developer command integration",
    )

    if public_manifest.get("api_version") != "0.3" or public_manifest.get("status") != "frozen":
        problems.append(Problem("NODAL-INC16-025", "public API v0.3 identity changed"))
    if manifest.get("increment") != 16 or manifest.get("public_api_changed") is not False:
        problems.append(Problem("NODAL-INC16-026", "Increment 16 manifest identity is invalid"))
    expected_context = {
        "binding": "java.lang.ScopedValue",
        "mutable_global_state": False,
        "thread_local_state": False,
        "public_scala_implicit": False,
        "jvm_identity_in_output": False,
        "parallel_emit_isolation": True,
    }
    if manifest.get("context_contract") != expected_context:
        problems.append(Problem("NODAL-INC16-027", "context contract changed"))

    implemented_codes = set(re.findall(r'"(NODAL-[A-Z0-9-]+-[0-9]{3})"', kernel))
    listed_codes = manifest.get("diagnostics")
    if not isinstance(listed_codes, list) or not set(listed_codes).issubset(implemented_codes):
        problems.append(Problem("NODAL-INC16-028", "manifest diagnostics are not implemented"))

    unchecked = "- [ ] **Increment 16 — Elaboration, hierarchy, shape, and lexical domain-context kernel**"
    checked = unchecked.replace("[ ]", "[x]", 1)
    status = manifest.get("status")
    validation = manifest.get("validation")
    if status == "preflight-kernel":
        if validation != {
            "pull_request": None,
            "dedicated_workflow_run": None,
            "core_ci_run": None,
        }:
            problems.append(Problem("NODAL-INC16-029", "preflight evidence is malformed"))
        if unchecked not in roadmap or "**Revision:** 1.19" not in roadmap:
            problems.append(Problem("NODAL-INC16-030", "preflight roadmap state is invalid"))
    elif status == "validated-kernel":
        if not isinstance(validation, dict) or not all(
            isinstance(validation.get(key), int)
            for key in ("pull_request", "dedicated_workflow_run", "core_ci_run")
        ):
            problems.append(Problem("NODAL-INC16-031", "final evidence is incomplete"))
        if checked not in roadmap or "**Revision:** 1.20" not in roadmap:
            problems.append(Problem("NODAL-INC16-032", "final roadmap state is invalid"))
        if isinstance(validation, dict):
            values = tuple(validation.get(key) for key in ("pull_request", "dedicated_workflow_run", "core_ci_run"))
            if f"PR [#{values[0]}]" not in roadmap or f"[{values[1]}]" not in roadmap or f"[{values[2]}]" not in roadmap:
                problems.append(Problem("NODAL-INC16-033", "roadmap lacks final evidence"))
    else:
        problems.append(Problem("NODAL-INC16-034", f"unknown status: {status!r}"))

    if "- [ ] **Increment 17 — Source spans, semantic naming, and origin graph**" not in roadmap:
        problems.append(Problem("NODAL-INC16-035", "Increment 17 is not left unchecked"))
    return problems


def execute(root: Path, command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=root,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env={**os.environ, "NO_COLOR": "1"},
    )


def run_compile(root: Path, problems: list[Problem]) -> None:
    mill = root / ("mill.bat" if os.name == "nt" else "mill")
    for index, arguments in enumerate(
        (
            ["mill.scalalib.scalafmt/checkFormatAll"],
            ["scalafix.check"],
            ["core.scala.testkit.test"],
        ),
        start=1,
    ):
        result = execute(root, [str(mill), *arguments])
        if result.returncode != 0:
            problems.append(
                Problem(
                    f"NODAL-INC16-{35 + index:03d}",
                    f"command failed: {' '.join(arguments)}\n{result.stdout}",
                )
            )
            return


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compile", action="store_true")
    args = parser.parse_args()
    problems = validate_files(ROOT)
    if args.compile and not problems:
        run_compile(ROOT, problems)
    if problems:
        for problem in problems:
            print(f"{problem.code}: {problem.message}")
        print(f"Increment 16 check failed with {len(problems)} problem(s)")
        return 1
    print("Increment 16 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
'''

UNIT = r'''#!/usr/bin/env python3
"""Unit coverage for the Increment 16 repository contract checker."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/check_increment16.py"
SPEC = importlib.util.spec_from_file_location("check_increment16", CHECKER)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class Increment16ContractTests(unittest.TestCase):
    def test_repository_contract(self) -> None:
        problems = MODULE.validate_files(ROOT)
        self.assertEqual([], problems, "\n".join(f"{p.code}: {p.message}" for p in problems))


if __name__ == "__main__":
    unittest.main()
'''

READ_ONLY_WORKFLOW = r'''name: Increment 16 Construction Kernel

on:
  push:
    branches:
      - increment/16-elaboration-construction-kernel
  pull_request:
    branches:
      - dev

permissions:
  contents: read

jobs:
  construction-kernel:
    name: increment-16/construction-kernel
    runs-on: ubuntu-24.04
    timeout-minutes: 45

    steps:
      - name: Check out repository
        uses: actions/checkout@v6
        with:
          fetch-depth: 0

      - name: Validate construction kernel and predecessor contracts
        run: |
          ./mill mill.scalalib.scalafmt/checkFormatAll
          ./mill scalafix.check
          python3 scripts/check_increment11.py
          python3 scripts/check_increment12.py --compile-negative
          python3 scripts/check_increment13.py --compile-negative
          python3 scripts/check_increment14.py --compile-negative
          python3 scripts/check_increment15.py --compile-negative
          python3 scripts/check_increment16.py --compile
          python3 tests/api/test_increment16.py
          git diff --check
'''


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.rstrip() + "\n", encoding="utf-8")


def main() -> int:
    apply = ROOT / "scripts/apply_increment16.py"
    if apply.exists():
        subprocess.run(["python3", str(apply)], cwd=ROOT, check=False)
    write("core/scala/api/src/nodal/ElaborationConstructionKernel.scala", KERNEL)
    write("core/scala/testkit/test/src/nodal/ConstructionKernelTests.scala", TESTS)
    write("scripts/check_increment16.py", CHECKER)
    write("tests/api/test_increment16.py", UNIT)
    write(".github/workflows/increment-16-construction-kernel.yml", READ_ONLY_WORKFLOW)

    for path in (
        ".github/workflows/increment-16-repair.yml",
        ".github/workflows/increment-16-repair-round2.yml",
        "scripts/repair_increment16_round1.py",
        "scripts/repair_increment16_round2.py",
        "scripts/materialize_increment16.py",
    ):
        target = ROOT / path
        if target.exists():
            target.unlink()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
