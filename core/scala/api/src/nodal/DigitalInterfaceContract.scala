package nodal

import java.util.IdentityHashMap

import scala.collection.mutable

private[nodal] final case class KernelInterfaceMemberSnapshot(
    path: String,
    name: String,
    role: String,
    access: String,
    dataType: String,
    protocol: String,
    mode: Option[String],
    nestedRoles: Vector[(String, String)],
    nestedInterfaces: Vector[(String, String)],
    analogAttributes: Vector[(String, String)] = Vector.empty
)

private[nodal] final case class KernelInterfaceRoleSnapshot(
    name: String,
    members: Vector[KernelInterfaceMemberSnapshot]
)

private[nodal] final case class KernelInterfaceBoundarySnapshot(
    instance: Option[String],
    endpoint: String,
    selection: Option[KernelGeneratedMemberSnapshot] = None,
    region: Option[String] = None
)
private[nodal] object KernelInterfaceBoundarySnapshot:
  def apply(instance: String, endpoint: String): KernelInterfaceBoundarySnapshot =
    new KernelInterfaceBoundarySnapshot(Some(instance), endpoint)

/** One entry for each actual regional definition application, including the empty root path. */
private[nodal] final case class KernelInterfaceDefinitionContextSnapshot(
    application: String,
    identity: String,
    owner: String,
    region: String,
    sourceOrdinal: Int
)

private[nodal] final case class KernelInterfaceSnapshot(
    path: String,
    owner: String,
    identity: String,
    role: String,
    domain: Option[String],
    dimensions: Vector[String],
    members: Vector[KernelInterfaceMemberSnapshot],
    roles: Vector[KernelInterfaceRoleSnapshot] = Vector.empty,
    boundary: Option[KernelInterfaceBoundarySnapshot] = None,
    actualMemberTypes: Vector[(String, String)] = Vector.empty,
    definitionContexts: Vector[KernelInterfaceDefinitionContextSnapshot] = Vector.empty
)

/** Reference fields are semantic edges, never rendered source expressions or operation labels. */
private[nodal] final case class KernelDigitalConnectivitySnapshot(
    path: String,
    owner: String,
    kind: String,
    target: String,
    source: Option[String],
    value: Option[String],
    enable: Option[String],
    dataType: Option[String],
    member: Option[String],
    role: Option[String],
    sourceRole: Option[String],
    access: Option[String],
    sourceAccess: Option[String] = None,
    nestedRoles: Vector[(String, String)] = Vector.empty,
    sourceNestedRoles: Vector[(String, String)] = Vector.empty,
    endpointRole: Option[String] = None,
    sourceEndpointRole: Option[String] = None,
    region: Option[String] = None,
    sourceOrdinal: Option[Int] = None
)

/** Typed Interface/access and resolved-net capture; the construction session owns all mutable
  * facts.
  */
private[nodal] object DigitalInterfaceContract:
  val OrdinaryReadOperation = "interface_member_read"
  val ValueOperations: Set[String] = Set(
    "digital_net_read",
    "digital_net_write_view",
    "digital_net_enable_view"
  )
  val EffectOperations: Set[String] = Set(
    "interface-connect",
    "interface-drive",
    "interface-observe",
    "inout-drive",
    "inout-high-z",
    "inout-drive-low",
    "inout-drive-high",
    "inout-pass-through"
  )

  final case class Declaration(
      value: AnyRef,
      path: String,
      owner: String,
      domain: Option[String],
      dimensions: Vector[String],
      renderType: DataType[?] => String,
      validateProtocolPayload: DataType[?] => Unit,
      validateBoundarySource: Expr[?] => Unit,
      samePayloadTypes: (DataType[?], DataType[?]) => Boolean,
      boundary: Option[KernelInterfaceBoundarySnapshot] = None,
      memberValueType: DataType[?] => DataType[?] = identity,
      actualMemberType: DataType[?] => DataType[?] = identity,
      renderFormalType: Option[DataType[?] => String] = None,
      shapeWitness: DataType[?] = Bool,
      definitionContext: InterfaceType[?] => Option[KernelInterfaceDefinitionContextSnapshot] =
        _ => None,
      renderDefinitionType: Option[(InterfaceType[?], DataType[?]) => String] = None,
      externalOrigin: Option[String] = None
  )
  final case class Operation(
      kind: String,
      values: Vector[Any],
      owner: String,
      region: Option[String] = None,
      sourceOrdinal: Option[Int] = None
  )

  /** One coherent externally observable value, rooted only in actual local drivers. The endpoint's
    * declared domain is deliberately not a proof of its contents.
    */
  final case class ObservationGroup(path: String, owner: String, values: Vector[Expr[?]])

  /** Immutable results of the same ordinary connection resolver used by observations. */
  final case class MemberDependency(
      path: String,
      terminal: String,
      values: Vector[Expr[?]],
      effectRegions: Vector[String]
  )

  final case class Capture(
      interfaces: Vector[KernelInterfaceSnapshot],
      connectivity: Vector[KernelDigitalConnectivitySnapshot],
      abi: Vector[InterfaceAbiEntry],
      dataTypes: Vector[DataType[?]],
      valueRoots: Vector[Expr[?]],
      observationGroups: Vector[ObservationGroup],
      resolvedAliases: Vector[(String, String)] = Vector.empty,
      memberDependencies: Vector[MemberDependency] = Vector.empty
  )

  def fail(number: Int, message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(f"NODAL-INTERFACE-054-$number%03d", message))
    ).get

  def netExpression[A <: Data](
      operation: String,
      endpoint: ResolvedDigitalCapability[?, ?],
      resultType: DataType[A]
  ): Expr[A] =
    ConstructionKernel.requireNoGeneratedEffect("resolved-net expression")
    if !ValueOperations.contains(operation) then fail(11, "unknown resolved-net expression")
    val expression = new KernelExpr[A](
      Vector(endpoint),
      resultType = Some(CandidateRuntime.typeDescriptor(resultType)),
      operation = Some(operation),
      typeIntent = DeferredResultType.intent(Vector(endpoint)):
        if operation == "digital_net_enable_view" then
          CandidateRuntime.typeDescriptor(endpoint.enableDataType)
        else CandidateRuntime.typeDescriptor(endpoint.dataType)
    )
    ConstructionKernel.expression(expression)
    expression

  private def resolutionProfile(endpoint: ResolvedDigitalCapability[?, ?]): ResolutionProfile =
    endpoint match
      case net: DigitalInout[?, ?] => net.profile
      case _: InterfaceResolvedMember[?, ?] => ResolutionProfile.PortableBoundaryOnly

  def requireInvertible(endpoint: InterfacePort[?, ?]): Unit =
    endpoint.role.access.foreach:
      case RoleAccess.In(_) | RoleAccess.Out(_) | RoleAccess.Master(_) | RoleAccess.Slave(_) |
          RoleAccess.Nested(_, _) => ()
      case _ => fail(4, "automatic inversion requires fully complementary digital member access")

  def root(endpoint: InterfaceEndpoint): InterfaceEndpoint = endpoint match
    case port: InterfacePort[?, ?] => root(port)
    case array: InterfaceArray[?, ?] => root(array)

  def root(endpoint: InterfaceArray[?, ?]): InterfaceArray[?, ?] =
    var result = endpoint
    var depth = 0
    while result.origin.nonEmpty do
      if depth >= 128 then fail(1, "interface view nesting exceeds 128")
      result = result.origin.get
      depth += 1
    result

  private def viewOrigin(endpoint: InterfaceEndpoint): Option[InterfaceEndpoint] = endpoint match
    case port: InterfacePort[?, ?] => port.origin
    case array: InterfaceArray[?, ?] => array.origin

  def root(endpoint: InterfacePort[?, ?]): InterfacePort[?, ?] =
    var result = endpoint
    var depth = 0
    while result.origin.nonEmpty do
      if depth >= 128 then fail(1, "interface view nesting exceeds 128")
      result = result.origin.get
      depth += 1
    result

  /** A split is a view of one existing driver, not a second driver or a simulator state. */
  def validateValues(
      expressions: Vector[(KernelExpr[?], String)],
      captured: Capture,
      pathOf: Any => Option[String],
      ownerOf: Any => Option[String],
      resolvedBoundaries: Vector[(String, String)] = Vector.empty,
      externalNets: Set[String] = Set.empty
  ): Set[String] =
    val contextualSplits = mutable.HashSet.empty[String]
    val parents = mutable.HashMap.empty[String, String]
    def representative(path: String): String =
      var current = path
      while parents.get(current).exists(_ != current) do current = parents(current)
      val result = current
      current = path
      while parents.get(current).exists(_ != current) do
        val next = parents(current)
        parents.update(current, result)
        current = next
      result
    def alias(a: String, b: String): Unit =
      val left = representative(a)
      val right = representative(b)
      if left != right then parents.update(right, left)
    val resolvedMembers =
      captured.interfaces.flatMap(_.members.filter(_.mode.nonEmpty)).map(_.path).toSet
    captured.resolvedAliases.foreach((left, right) => alias(left, right))
    captured.connectivity.filter(connection =>
      connection.kind == "inout-pass-through" ||
        connection.kind == "interface-connect" && resolvedMembers(connection.target)
    ).foreach: connection =>
      alias(
        connection.target,
        connection.source.getOrElse(fail(11, "pass-through lost its source"))
      )
    // Preserve the local proof before extending the same component relation with
    // exact child boundaries. No imported expression becomes parent-owned SSA.
    val driverOperations = captured.connectivity.filter(value =>
      Set("inout-drive", "inout-drive-low", "inout-drive-high").contains(value.kind) ||
        value.kind == "interface-drive" && resolvedMembers(value.target)
    )
    val localComponents =
      (captured.connectivity.flatMap(value =>
        Vector(value.target) ++ value.source.toVector
      ) ++ captured.resolvedAliases.flatMap((left, right) => Vector(left, right)) ++
        resolvedBoundaries.flatMap((left, right) => Vector(left, right)) ++
        captured.interfaces.flatMap(_.members.filter(_.mode.nonEmpty).map(_.path)))
        .distinct.map(path => path -> representative(path)).toMap
    val localDrivers = driverOperations.groupBy(value => representative(value.target))
    val interfaceBoundaries = captured.interfaces.flatMap(endpoint =>
      endpoint.boundary.toVector.flatMap(boundary =>
        endpoint.members.filter(_.mode.nonEmpty).map(member =>
          member.path -> s"${boundary.endpoint}.${member.name}"
        )
      )
    )
    val boundaries = resolvedBoundaries ++ interfaceBoundaries
    boundaries.foreach((parent, child) => alias(parent, child))
    val joined = boundaries.map((parent, _) => representative(parent)).toSet
    val opaque = externalNets.map(representative)
    val drivers = driverOperations.groupBy(value => representative(value.target))
    expressions.foreach: (expression, owner) =>
      expression.operation.filter(ValueOperations.contains).foreach: operation =>
        val endpoint = expression.operands match
          case Vector(value: ResolvedDigitalCapability[?, ?]) => value
          case _ => fail(11, "resolved-net expression requires exactly one typed endpoint")
        if !ownerOf(endpoint).contains(owner) then
          fail(7, "resolved-net expression belongs to another module or transaction")
        endpoint match
          case view: InterfaceResolvedMember[?, ?] =>
            val selected =
              ConstructionInterfaceLayout.leaves(view.endpoint.definition, view.endpoint.role)
                .find(_.name == view.member).getOrElse(fail(
                  3,
                  "resolved read lost its selected member"
                ))
            if selected.access == "drive" then
              fail(3, "drive-only resolved member cannot be read or split")
          case _ => ()
        val path =
          pathOf(endpoint).getOrElse(fail(7, "resolved-net expression has no endpoint identity"))
        val expected = if operation == "digital_net_enable_view" then
          CandidateRuntime.typeDescriptor(endpoint.enableDataType)
        else CandidateRuntime.typeDescriptor(endpoint.dataType)
        if !expression.resultDescriptor.contains(expected) || expression.literal.nonEmpty then
          fail(8, "resolved-net expression has an incorrect exact result type")
        if operation != "digital_net_read" then
          if opaque(representative(path)) then
            fail(12, "split cannot infer a unique driver across an opaque external contribution")
          val selected = drivers.getOrElse(representative(path), Vector.empty)
          if selected.isEmpty then
            fail(12, "split requires an authored conditional driver; no write value is available")
          if selected.size != 1 then
            fail(12, "split is ambiguous because the resolved net has multiple conditional drivers")
          if selected.head.kind == "interface-drive" then
            fail(12, "split cannot invent an enable for an unconditional Interface drive")
          val local = localDrivers.getOrElse(localComponents.getOrElse(path, path), Vector.empty)
          if local.isEmpty then
            if !joined(representative(path)) then
              fail(12, "contextual split requires an actual closed-root boundary connection")
            contextualSplits += pathOf(expression).getOrElse(fail(
              11,
              "contextual split has no exact expression identity"
            ))
    contextualSplits.toSet

  def capture(
      declarations: Vector[Declaration],
      operations: Vector[Operation],
      pathOf: Any => Option[String],
      ownerOf: Any => Option[String],
      ordinaryReads: Vector[(KernelExpr[?], String)] = Vector.empty
  ): Capture =
    val owners = (declarations.map(_.owner) ++ operations.map(_.owner)).distinct
    val declarationsByOwner = declarations.groupBy(_.owner)
    val operationsByOwner = operations.groupBy(_.owner)
    val captured = owners.map(owner =>
      captureOwner(
        declarationsByOwner.getOrElse(owner, Vector.empty),
        operationsByOwner.getOrElse(owner, Vector.empty),
        pathOf,
        ownerOf,
        ordinaryReads.collect { case (expression, `owner`) => expression }
      )
    )
    Capture(
      captured.flatMap(_.interfaces),
      captured.flatMap(_.connectivity),
      captured.flatMap(_.abi),
      captured.flatMap(_.dataTypes),
      captured.flatMap(_.valueRoots),
      captured.flatMap(_.observationGroups),
      captured.flatMap(_.resolvedAliases),
      captured.flatMap(_.memberDependencies)
    )

  private def captureOwner(
      declarations: Vector[Declaration],
      operations: Vector[Operation],
      pathOf: Any => Option[String],
      ownerOf: Any => Option[String],
      ordinaryReads: Vector[KernelExpr[?]]
  ): Capture =
    val declarationsByValue = new IdentityHashMap[AnyRef, Declaration]()
    declarations.foreach(value => declarationsByValue.put(value.value, value))
    val roles = new IdentityHashMap[InterfaceType[?], mutable.LinkedHashMap[String, Role[?]]]()
    val namedRoles =
      mutable.HashMap.empty[(String, Option[DigitalValueRegionRecord], String), Seq[RoleAccess]]
    val discovered = new IdentityHashMap[InterfaceType[?], java.lang.Boolean]()
    val pending = mutable.ArrayDeque.empty[(InterfaceType[?], Int)]
    def register(definition: InterfaceType[?], requested: Role[?]): Unit =
      val role = if requested.name == "monitor" && requested.access.isEmpty then
        ConstructionInterfaceLayout.monitorRole(definition)
      else requested
      val ordered = role.access.sortBy(ConstructionInterfaceLayout.accessMember)
      namedRoles.get((
        definition.name,
        definition.constructionContext.map(_.region),
        role.name
      )).foreach: previous =>
        if previous != ordered then
          fail(
            2,
            s"Interface '${definition.name}' has conflicting definitions for role '${role.name}'"
          )
      namedRoles.update(
        (definition.name, definition.constructionContext.map(_.region), role.name),
        ordered
      )
      val known = Option(roles.get(definition)).getOrElse:
        val fresh = mutable.LinkedHashMap.empty[String, Role[?]]
        roles.put(definition, fresh)
        fresh
      known.get(role.name).foreach: previous =>
        if previous.access.sortBy(ConstructionInterfaceLayout.accessMember) !=
            role.access.sortBy(ConstructionInterfaceLayout.accessMember)
        then
          fail(
            2,
            s"Interface '${definition.name}' has conflicting definitions for role '${role.name}'"
          )
      known.update(role.name, role)
    declarations.foreach: declaration =>
      declaration.value match
        case port: InterfacePort[?, ?] =>
          register(port.definition, port.role)
          pending.append((port.definition, 0))
        case array: InterfaceArray[?, ?] =>
          register(array.definition, array.role)
          pending.append((array.definition, 0))
        case view: InterfaceResolvedMember[?, ?] =>
          register(view.endpoint.definition, view.endpoint.role)
          pending.append((view.endpoint.definition, 0))
        case view: InterfaceTerminalView[?, ?] =>
          register(view.endpoint.definition, view.endpoint.role)
          pending.append((view.endpoint.definition, 0))
        case _ => ()
    operations.foreach: operation =>
      operation.values.foreach:
        case view: InterfaceEndpoint if viewOrigin(view).nonEmpty =>
          register(view.definition, view.role)
          pending.append((view.definition, 0))
        case _ => ()
    ordinaryReads.foreach: expression =>
      val read =
        expression.interfaceMember.getOrElse(fail(11, "ordinary read lost its exact origin"))
      register(read.endpoint.definition, read.endpoint.role)
      pending.append((read.endpoint.definition, 0))
    while pending.nonEmpty do
      val (definition, depth) = pending.removeLast()
      if depth > 128 then fail(1, "interface definition nesting exceeds 128")
      if !discovered.containsKey(definition) then
        discovered.put(definition, true)
        if discovered.size > 65536 then fail(1, "interface definition graph exceeds 65536 nodes")
        definition.roles.foreach(register(definition, _))
        definition.members.foreach:
          case nested: InterfaceMember.Nested[?] => pending.append((nested.definition, depth + 1))
          case _ => ()
    def resolve(definition: InterfaceType[?], name: String): Role[?] =
      Option(roles.get(definition)).flatMap(_.get(name)).getOrElse(
        fail(
          2,
          s"Interface '${definition.name}' has no actual definition for nested role '$name'; bind it with withRoles"
        )
      )
    val layouts = new IdentityHashMap[AnyRef, Vector[ConstructionInterfaceLayout.Leaf]]()
    val declarationTypes = mutable.ArrayBuffer.empty[DataType[?]]
    val identities =
      mutable.LinkedHashMap.empty[
        (String, Option[DigitalValueRegionRecord]),
        (Declaration, Vector[ConstructionInterfaceLayout.Leaf])
      ]
    val definitionIdentities = mutable.HashMap.empty[
      (String, Option[DigitalValueRegionRecord]),
      Option[InterfaceDefinitionContext]
    ]
    def sameLeafType(
        left: ConstructionInterfaceLayout.Leaf,
        a: Declaration,
        right: ConstructionInterfaceLayout.Leaf,
        b: Declaration
    ): Boolean =
      (left.dataType, right.dataType) match
        case (Some(x), Some(y)) => a.samePayloadTypes(a.actualMemberType(x), b.actualMemberType(y))
        case (None, None) => left.analogType == right.analogType
        case _ => false
    def actualType(leaf: ConstructionInterfaceLayout.Leaf, context: Declaration): String =
      leaf.rendered(value => context.renderType(context.actualMemberType(value)))
    val interfaces = declarations.flatMap: declaration =>
      val endpoint = declaration.value match
        case value: InterfacePort[?, ?] => Some((value.definition, value.role, value.name))
        case value: InterfaceArray[?, ?] => Some((value.definition, value.role, value.name))
        case _ => None
      endpoint.map: (definition, role, _) =>
        val leaves = ConstructionInterfaceLayout.leaves(definition, role, resolve)
        leaves.filter(leaf => Set("valid", "stream").contains(leaf.protocol))
          .flatMap(
            _.dataType
          ).map(declaration.actualMemberType).foreach(declaration.validateProtocolPayload)
        layouts.put(declaration.value, leaves)
        declarationTypes ++= leaves.flatMap(_.dataType)
        declarationTypes ++= leaves.flatMap(_.dataType).map(declaration.actualMemberType)
        // Selected boundaries specialize an existing definition; they do not declare a new ABI.
        if declaration.boundary.forall(_.selection.isEmpty) then
          val key = definition.name -> definition.constructionContext.map(_.region)
          definitionIdentities.get(key).foreach(previous =>
            if previous != definition.constructionContext then
              fail(
                5,
                "separately constructed lexical Interface definitions cannot share one local name"
              )
          )
          definitionIdentities.update(key, definition.constructionContext)
          identities.get(key).foreach: (previousContext, previous) =>
            if previous.size != leaves.size || previous.zip(leaves).exists: (before, after) =>
                before.name != after.name || before.protocol != after.protocol ||
                  before.mode != after.mode || before.nestedInterfaces != after.nestedInterfaces ||
                  before.analogAttributes != after.analogAttributes ||
                  !sameLeafType(before, previousContext, after, declaration)
            then fail(5, s"Interface '${definition.name}' has conflicting exact member ABIs")
          identities.update(key, declaration -> leaves)
        if declaration.domain.isEmpty && declaration.externalOrigin.isEmpty then
          fail(6, "interface endpoint has no resolved domain")
        val domain = declaration.domain
        def snapshotLeaves(values: Vector[ConstructionInterfaceLayout.Leaf])
            : Vector[KernelInterfaceMemberSnapshot] =
          values.map(value =>
            KernelInterfaceMemberSnapshot(
              s"${declaration.path}.${value.name}",
              value.name,
              value.role,
              value.access,
              value.rendered(dataType =>
                declaration.renderDefinitionType.map(_(value.definition, dataType))
                  .getOrElse(
                    declaration.renderFormalType.getOrElse(declaration.renderType)(dataType)
                  )
              ),
              value.protocol,
              value.mode,
              value.nestedRoles,
              value.nestedInterfaces,
              value.analogAttributes
            )
          )
        val roleMaps = roles.get(definition).values.toVector.sortBy(_.name).map: selected =>
          KernelInterfaceRoleSnapshot(
            selected.name,
            snapshotLeaves(
              ConstructionInterfaceLayout.leaves(definition, selected, resolve)
            )
          )
        val definitionContexts = mutable.ArrayBuffer.empty[KernelInterfaceDefinitionContextSnapshot]
        var contextWork = 0
        def collectContexts(current: InterfaceType[?], prefix: String, depth: Int): Unit =
          contextWork += 1
          if depth > 128 || contextWork > 65536 then
            fail(1, "Interface definition context exceeds structural budget")
          declaration.definitionContext(current).foreach(value =>
            definitionContexts += value.copy(application = prefix)
          )
          current.members.foreach:
            case nested: InterfaceMember.Nested[?] =>
              collectContexts(
                nested.definition,
                if prefix.isEmpty then nested.name else s"$prefix.${nested.name}",
                depth + 1
              )
            case _ => ()
        collectContexts(definition, "", 0)
        KernelInterfaceSnapshot(
          declaration.path,
          declaration.owner,
          definition.name,
          role.name,
          domain,
          declaration.dimensions,
          snapshotLeaves(leaves),
          roleMaps,
          declaration.boundary,
          if declaration.boundary.nonEmpty then
            leaves.map(value => value.name -> actualType(value, declaration))
          else Vector.empty,
          definitionContexts.toVector
        )
    def declaration(value: AnyRef, owner: String): Declaration =
      val canonical = value match
        case endpoint: InterfaceEndpoint => root(endpoint)
        case other => other
      val result = Option(declarationsByValue.get(canonical)).getOrElse(
        fail(7, "connectivity endpoint belongs to another construction transaction")
      )
      if result.owner != owner then fail(7, "connectivity endpoint belongs to another module")
      result
    def owned(value: Any, owner: String): String =
      if !ownerOf(value).contains(owner) then
        fail(7, "digital operand belongs to another module or transaction")
      pathOf(value).getOrElse(fail(7, "digital operand has no captured semantic identity"))
    def exactPayload(value: Expr[?], expected: DataType[?], context: Declaration): String =
      val actual = CandidateRuntime.expressionDataType(value).getOrElse(
        fail(8, "digital operand requires an exact type")
      )
      if !context.samePayloadTypes(actual, expected)
      then fail(8, "digital operand must have the exact owned payload type")
      owned(value, context.owner)
    val viewLayouts =
      new IdentityHashMap[InterfaceEndpoint, Vector[ConstructionInterfaceLayout.Leaf]]()
    def endpointLeaves(endpoint: InterfaceEndpoint, depth: Int = 0)
        : Vector[ConstructionInterfaceLayout.Leaf] =
      if depth > 128 then fail(1, "interface view nesting exceeds 128")
      if viewOrigin(endpoint).isEmpty then layouts.get(endpoint)
      else
        Option(viewLayouts.get(endpoint)).getOrElse:
          val originalEndpoint = viewOrigin(endpoint).get
          if (endpoint.definition ne originalEndpoint.definition) ||
            (endpoint.domainOption != originalEndpoint.domainOption)
          then fail(5, "interface view changed its actual definition or domain identity")
          val original = endpointLeaves(originalEndpoint, depth + 1)
          if root(endpoint).boundary then
            fail(3, "child Interface views cannot invert the declared role")
          if endpoint.role.name != "monitor" && original.exists(value =>
              value.mode.nonEmpty || value.analogType.nonEmpty ||
                !Set("in", "out").contains(value.access)
            )
          then fail(4, "automatic inversion requires fully complementary digital leaves")
          val viewed =
            ConstructionInterfaceLayout.leaves(endpoint.definition, endpoint.role, resolve)
          val context = declarationsByValue.get(root(endpoint))
          if original.size != viewed.size || original.zip(viewed).exists: (before, after) =>
              before.name != after.name ||
                before.rendered(context.renderType) != after.rendered(context.renderType) ||
                before.protocol != after.protocol || before.mode != after.mode ||
                before.nestedInterfaces != after.nestedInterfaces ||
                before.analogAttributes != after.analogAttributes
          then fail(5, "interface view changed the exact member ABI")
          if endpoint.role.name != "monitor" && original.zip(viewed).exists: (before, after) =>
              !Set("in" -> "out", "out" -> "in").contains(before.access -> after.access)
          then fail(4, "automatic inversion requires truly complementary actual nested role maps")
          viewLayouts.put(endpoint, viewed)
          viewed
    operations.filter(_.kind == "interface-invert").foreach: operation =>
      operation.values match
        case Vector(original: InterfacePort[?, ?], view: InterfacePort[?, ?]) =>
          val context = declaration(original, operation.owner)
          if root(view) ne root(original) then fail(11, "inverted view changed its root identity")
          val _ = declaration(view, context.owner)
          val _ = endpointLeaves(view)
        case _ => fail(11, "inverted view lost its captured typed endpoints")
    def net(value: ResolvedDigitalCapability[?, ?], owner: String): Declaration =
      val result = declaration(value, owner)
      val descriptor = CandidateRuntime.typeDescriptor(value.dataType)
      if !Set(
          "push-pull",
          "open-drain",
          "open-source",
          "read-only",
          "pass-through"
        ).contains(value.mode.name)
      then
        fail(9, "resolved digital endpoint has an unknown drive mode")
      val leafDescriptor = value match
        case view: InterfaceResolvedArrayView[?, ?] =>
          CandidateRuntime.typeDescriptor(view.declaration.dataType)
        case _ => descriptor
      if !Set("Bits", "UInt", "SInt").contains(leafDescriptor.kind) ||
        (descriptor.kind == "Vec" && !value.isInstanceOf[InterfaceResolvedArrayView[?, ?]])
      then
        fail(
          8,
          "resolved digital endpoints require an actual scalar or Interface-array bit-vector capability"
        )
      value match
        case net: DigitalInout[?, ?] =>
          if net.profile == ResolutionProfile.PortableBoundaryOnly &&
            net.placement == InoutPlacement.InternalResolvedNet
          then fail(9, "portable boundary resolution does not support an internal resolved net")
        case view: InterfaceResolvedMember[?, ?] =>
          val context = declaration(view.endpoint, owner)
          val selected = ConstructionInterfaceLayout.resolvedMemberPath(
            view.endpoint.definition,
            view.declaration,
            view.through
          )
          val leaf = endpointLeaves(view.endpoint).find(_.name == selected).getOrElse(
            fail(3, "resolved view lost its selected declared member")
          )
          if selected != view.member || leaf.protocol != "resolved" ||
            !leaf.mode.contains(value.mode.name) ||
            !context.samePayloadTypes(
              value.dataType,
              context.memberValueType(view.declaration.dataType)
            )
          then fail(8, "resolved view changed its exact member, mode or contextual type")
      result
    def requireNetAccess(value: ResolvedDigitalCapability[?, ?], access: String): Unit = value match
      case view: InterfaceResolvedMember[?, ?] =>
        val selected = endpointLeaves(view.endpoint).find(_.name == view.member).getOrElse(
          fail(3, "resolved view has no selected role member")
        )
        if selected.access != access then fail(3, s"resolved member role does not permit $access")
      case _ => ()
    declarations.foreach: candidate =>
      candidate.value match
        case endpoint: ResolvedDigitalCapability[?, ?] =>
          val _ = net(endpoint, candidate.owner)
          declarationTypes += endpoint.dataType
        case _ => ()
    ordinaryReads.foreach: expression =>
      val read =
        expression.interfaceMember.getOrElse(fail(11, "ordinary read lost its exact origin"))
      val owner = ownerOf(expression).getOrElse(fail(7, "ordinary read has no actual owner"))
      val context = declaration(read.endpoint, owner)
      val member = ConstructionInterfaceLayout.exactMemberPath(
        read.endpoint.definition,
        read.field,
        read.through
      )
      val leaf = endpointLeaves(read.endpoint).find(_.name == member).getOrElse(
        fail(3, "ordinary read lost its exact role member")
      )
      if !expression.operation.contains(OrdinaryReadOperation) || member != read.member ||
        expression.operands != Vector(root(read.endpoint)) || leaf.protocol != "plain" ||
        leaf.mode.nonEmpty || leaf.analogType.nonEmpty || !Set("in", "out", "observe")(leaf.access)
      then fail(3, "ordinary read changed its exact member capability")
      val actual = CandidateRuntime.expressionDataType(expression).getOrElse(
        fail(8, "ordinary read has no finalized contextual result type")
      )
      DigitalCombinationalContract.validateValueType(CandidateRuntime.typeDescriptor(actual))
      if !context.samePayloadTypes(actual, context.memberValueType(read.field.dataType)) then
        fail(8, "ordinary read changed its exact contextual member type")
    val ordinals = mutable.HashMap.empty[String, Int]
    val driven = mutable.HashSet.empty[String]
    def effect(
        operation: Operation,
        target: String,
        source: Option[String] = None,
        value: Option[String] = None,
        enable: Option[String] = None,
        dataType: Option[String],
        member: Option[String] = None,
        role: Option[String] = None,
        sourceRole: Option[String] = None,
        access: Option[String] = None,
        sourceAccess: Option[String] = None,
        nestedRoles: Vector[(String, String)] = Vector.empty,
        sourceNestedRoles: Vector[(String, String)] = Vector.empty,
        endpointRole: Option[String] = None,
        sourceEndpointRole: Option[String] = None
    ): KernelDigitalConnectivitySnapshot =
      val ordinal = ordinals.getOrElse(operation.owner, 0)
      ordinals.update(operation.owner, ordinal + 1)
      KernelDigitalConnectivitySnapshot(
        s"${operation.owner}.digital_connectivity_$ordinal",
        operation.owner,
        operation.kind,
        target,
        source,
        value,
        enable,
        dataType,
        member,
        role,
        sourceRole,
        access,
        sourceAccess,
        nestedRoles,
        sourceNestedRoles,
        endpointRole,
        sourceEndpointRole,
        operation.region,
        operation.sourceOrdinal
      )
    val connectivity = operations.filter(value => EffectOperations.contains(value.kind)).flatMap:
      operation =>
        operation.values match
          case Vector(left: InterfaceEndpoint, right: InterfaceEndpoint)
              if operation.kind == "interface-connect" =>
            val a = declaration(left, operation.owner)
            val b = declaration(right, operation.owner)
            if left.definition.name != right.definition.name ||
              left.definition.constructionContext != right.definition.constructionContext ||
              (a.domain.nonEmpty && b.domain.nonEmpty && a.domain != b.domain) ||
              a.path == b.path ||
              !a.samePayloadTypes(a.shapeWitness, b.shapeWitness)
            then
              fail(
                5,
                "exact interface connection requires distinct endpoints, one interface identity and one domain"
              )
            val leftLeaves = endpointLeaves(left)
            val rightLeaves = endpointLeaves(right)
            if leftLeaves.size != rightLeaves.size then
              fail(5, "exact interface connection changed members")
            leftLeaves.zip(rightLeaves).map: (first, second) =>
              if first.name != second.name || first.protocol != second.protocol ||
                first.definition.constructionContext != second.definition.constructionContext ||
                first.mode != second.mode ||
                first.nestedInterfaces != second.nestedInterfaces ||
                !sameLeafType(first, a, second, b)
              then fail(5, "exact interface connection changed a member ABI")
              val sink = ConstructionInterfaceLayout.connectionSink(
                first.access,
                a.boundary.exists(_.instance.nonEmpty),
                second.access,
                b.boundary.exists(_.instance.nonEmpty)
              )
              val complementary = sink.nonEmpty ||
                (first.protocol == "resolved" && first.access == "connect" &&
                  second.access == "connect")
              if first.analogType.nonEmpty || !complementary then
                fail(3, "exact digital interface connection requires complementary digital access")
              if first.protocol != "resolved" then
                val sinkContext = if sink.contains(true) then a else b
                if sinkContext.boundary.exists(_.selection.nonEmpty) then
                  fail(3, "selected Interface writes require exact lane-qualified driver proof")
                val sinkPath = if sink.contains(true) then s"${a.path}.${first.name}"
                else s"${b.path}.${second.name}"
                if !driven.add(sinkPath) then
                  fail(10, "ordinary interface member has multiple drivers")
              effect(
                operation,
                s"${a.path}.${first.name}",
                source = Some(s"${b.path}.${second.name}"),
                dataType = Some(actualType(first, a)),
                member = Some(first.name),
                role = Some(first.role),
                sourceRole = Some(second.role),
                access = Some(first.access),
                sourceAccess = Some(second.access),
                nestedRoles = first.nestedRoles,
                sourceNestedRoles = second.nestedRoles,
                endpointRole = Some(left.role.name),
                sourceEndpointRole = Some(right.role.name)
              )
          case Vector(endpoint: InterfaceEndpoint, selected: String, value: Expr[?])
              if operation.kind == "interface-drive" =>
            val context = declaration(endpoint, operation.owner)
            val leaf = endpointLeaves(endpoint).find(_.name == selected).getOrElse(
              fail(
                3,
                "driveMember requires an exact digital leaf name, including protocol payload/valid/ready"
              )
            )
            val writable = if leaf.mode.nonEmpty then leaf.access == "drive"
            else if context.boundary.exists(_.instance.nonEmpty) then leaf.access == "in"
            else leaf.access == "out"
            if leaf.analogType.nonEmpty || !writable || endpoint.role.name == "monitor"
            then
              fail(3, "role does not permit driving this digital member")
            if leaf.mode.exists(_ != "push-pull") then
              fail(
                9,
                "interface resolved drive requires push-pull mode; constrained modes require explicit inout operations"
              )
            val expected = actualType(leaf, context)
            val target = s"${context.path}.${leaf.name}"
            if leaf.mode.isEmpty && !driven.add(target) then
              fail(10, "ordinary interface member has multiple drivers")
            val capturedValue =
              exactPayload(value, context.memberValueType(leaf.dataType.get), context)
            context.validateBoundarySource(value)
            Vector(effect(
              operation,
              target,
              value = Some(capturedValue),
              dataType = Some(expected),
              member = Some(leaf.name),
              role = Some(leaf.role),
              access = Some(leaf.access),
              nestedRoles = leaf.nestedRoles,
              endpointRole = Some(endpoint.role.name)
            ))
          case Vector(endpoint: InterfaceEndpoint, selected: String)
              if operation.kind == "interface-observe" =>
            val context = declaration(endpoint, operation.owner)
            val selectedLeaves = endpointLeaves(endpoint).filter(leaf =>
              leaf.name == selected || leaf.name.startsWith(selected + ".")
            )
            if selectedLeaves.isEmpty then fail(3, "observeMember refers to an unknown member")
            selectedLeaves.map: leaf =>
              if leaf.analogType.nonEmpty || leaf.access == "drive" then
                fail(3, "role does not permit observing this digital member")
              effect(
                operation,
                s"${context.path}.${leaf.name}",
                dataType = Some(actualType(leaf, context)),
                member = Some(leaf.name),
                role = Some(leaf.role),
                access = Some(leaf.access),
                nestedRoles = leaf.nestedRoles,
                endpointRole = Some(endpoint.role.name)
              )
          case Vector(endpoint: ResolvedDigitalCapability[?, ?], value: Expr[?], enable: Expr[?])
              if operation.kind == "inout-drive" =>
            val context = net(endpoint, operation.owner)
            requireNetAccess(endpoint, "drive")
            if !Set("push-pull", "open-source").contains(endpoint.mode.name) then
              fail(9, "drive mode forbids an arbitrary value driver")
            val expected = context.renderType(endpoint.dataType)
            Vector(effect(
              operation,
              context.path,
              value = Some(exactPayload(value, endpoint.dataType, context)),
              enable = Some(exactPayload(enable, endpoint.enableDataType, context)),
              dataType = Some(expected)
            ))
          case Vector(endpoint: ResolvedDigitalCapability[?, ?], enable: Expr[?])
              if Set("inout-drive-low", "inout-drive-high").contains(operation.kind) =>
            val context = net(endpoint, operation.owner)
            requireNetAccess(endpoint, "drive")
            val required =
              if operation.kind == "inout-drive-low" then "open-drain" else "open-source"
            if endpoint.mode.name != required then
              fail(9, s"${operation.kind} requires $required mode")
            Vector(effect(
              operation,
              context.path,
              enable = Some(exactPayload(enable, endpoint.enableDataType, context)),
              dataType = Some(context.renderType(endpoint.dataType))
            ))
          case Vector(endpoint: ResolvedDigitalCapability[?, ?])
              if operation.kind == "inout-high-z" =>
            val context = net(endpoint, operation.owner)
            requireNetAccess(endpoint, "drive")
            if endpoint.mode.name == "read-only" then
              fail(9, "read-only endpoint cannot create a high-impedance driver")
            Vector(effect(
              operation,
              context.path,
              dataType = Some(context.renderType(endpoint.dataType))
            ))
          case Vector(left: ResolvedDigitalCapability[?, ?], right: ResolvedDigitalCapability[?, ?])
              if operation.kind == "inout-pass-through" =>
            val a = net(left, operation.owner)
            val b = net(right, operation.owner)
            requireNetAccess(left, "connect")
            requireNetAccess(right, "connect")
            val expected = a.renderType(left.dataType)
            if a.path == b.path || expected != b.renderType(right.dataType) ||
              left.mode.name != right.mode.name ||
              resolutionProfile(left) != resolutionProfile(right)
            then
              fail(
                8,
                "resolved pass-through requires distinct endpoints with exact type, mode and resolution profile"
              )
            Vector(effect(operation, a.path, source = Some(b.path), dataType = Some(expected)))
          case _ => fail(11, s"malformed typed digital connectivity operation '${operation.kind}'")
    val declarationsByPath = declarations.map(value => value.path -> value).toMap
    val abi = interfaces.flatMap: endpoint =>
      val declaration = declarationsByPath(endpoint.path)
      val name = declaration.value match
        case value: InterfacePort[?, ?] => value.name
        case value: InterfaceArray[?, ?] => value.name
        case _ => fail(11, "interface declaration lost its endpoint")
      val suffix =
        if endpoint.dimensions.isEmpty then "" else endpoint.dimensions.mkString("[", ",", "]")
      val actualTypes = endpoint.actualMemberTypes.toMap
      endpoint.members.map(member =>
        InterfaceAbiEntry(
          s"${endpoint.path}$suffix.${member.name}",
          s"${name}_${member.name.replace('.', '_')}",
          member.role,
          member.access,
          actualTypes.getOrElse(member.name, member.dataType),
          endpoint.domain
        )
      )
    val valueRoots = operations.filter(value => EffectOperations.contains(value.kind))
      .flatMap(_.values.collect { case expression: Expr[?] => expression })
    val resolvedAliases = declarations.flatMap: context =>
      context.value match
        case view: InterfaceResolvedMember[?, ?] =>
          Some(context.path -> s"${declaration(view.endpoint, context.owner).path}.${view.member}")
        case _ => None
    val (observationGroups, memberDependencies) =
      observations(declarations, interfaces, connectivity, valueRoots, pathOf, resolvedAliases)
    Capture(
      interfaces.sortBy(_.path),
      connectivity,
      abi.sortBy(_.logicalPath),
      declarationTypes.toVector,
      valueRoots,
      observationGroups,
      resolvedAliases,
      memberDependencies
    )

  /** Resolve digital connectivity once in its existing typed owner. Ordinary connections are
    * directed dependencies; only resolved physical aliases are unioned. The sampler consumes these
    * actual expressions rather than parsing member names or trusting endpoint domains.
    */
  private def observations(
      declarations: Vector[Declaration],
      interfaces: Vector[KernelInterfaceSnapshot],
      connectivity: Vector[KernelDigitalConnectivitySnapshot],
      valueRoots: Vector[Expr[?]],
      pathOf: Any => Option[String],
      resolvedAliases: Vector[(String, String)]
  ): (Vector[ObservationGroup], Vector[MemberDependency]) =
    val members = interfaces.flatMap(_.members).filter(member =>
      Set("plain", "valid", "stream", "resolved").contains(member.protocol)
    )
    val nets = declarations.filter(_.value.isInstanceOf[ResolvedDigitalCapability[?, ?]])
    val paths = (members.map(_.path) ++ nets.map(_.path)).distinct.sorted
    if paths.size > 65536 || connectivity.size > 262144 then
      fail(1, "digital observation graph exceeds its bounded node or edge budget")
    val indices = paths.zipWithIndex.toMap
    val parents = Array.tabulate(paths.size)(identity)
    val sizes = Array.fill(paths.size)(1)
    val names = Array.tabulate(paths.size)(identity)
    def representative(index: Int): Int =
      var root = index
      while parents(root) != root do root = parents(root)
      var current = index
      while parents(current) != current do
        val next = parents(current)
        parents(current) = root
        current = next
      root
    def alias(left: String, right: String): Unit =
      val a = representative(indices(left))
      val b = representative(indices(right))
      if a != b then
        val (root, child) = if sizes(a) > sizes(b) || sizes(a) == sizes(b) && a < b then
          (a, b)
        else (b, a)
        parents(child) = root
        sizes(root) += sizes(child)
        names(root) = math.min(names(root), names(child))
    resolvedAliases.foreach((left, right) => alias(left, right))
    val resolved = members.filter(_.protocol == "resolved").map(_.path).toSet ++ nets.map(_.path)
    connectivity.foreach: edge =>
      if edge.kind == "inout-pass-through" ||
        edge.kind == "interface-connect" && resolved.contains(edge.target)
      then alias(edge.target, edge.source.get)
    def canonical(path: String): String = paths(names(representative(indices(path))))
    val componentDomains = mutable.HashMap.empty[String, String]
    interfaces.foreach: endpoint =>
      endpoint.domain.foreach: domain =>
        endpoint.members.filter(_.protocol == "resolved").foreach: member =>
          val key = canonical(member.path)
          componentDomains.get(key).foreach: previous =>
            if previous != domain then
              fail(6, "resolved pass-through component crosses exact Interface domain ownership")
          componentDomains.update(key, domain)
    val expressions = valueRoots.map(value =>
      pathOf(value).getOrElse(fail(7, "digital driver has no actual captured identity")) -> value
    ).toMap
    val direct = mutable.HashMap.empty[String, mutable.ArrayBuffer[Expr[?]]]
    val sources = mutable.HashMap.empty[String, String]
    val driverRegions = mutable.HashMap.empty[String, Vector[String]]
    val connectionRegions = mutable.HashMap.empty[String, Vector[String]]
    var dependencyEdges = 0
    val boundaries =
      interfaces.filter(_.boundary.exists(_.instance.nonEmpty)).flatMap(_.members.map(_.path)).toSet
    connectivity.foreach: edge =>
      val target = canonical(edge.target)
      val values = (edge.value.toVector ++ edge.enable.toVector).map(path =>
        expressions.getOrElse(path, fail(7, "digital dependency has no actual captured value"))
      )
      dependencyEdges += values.size
      if dependencyEdges > 262144 then fail(1, "digital driver edges exceed their budget")
      if values.nonEmpty then
        direct.getOrElseUpdate(target, mutable.ArrayBuffer.empty) ++= values
        driverRegions.update(target, driverRegions.getOrElse(target, Vector.empty) ++ edge.region)

      if edge.kind == "interface-connect" && !resolved.contains(edge.target) then
        val other = canonical(edge.source.get)
        val leftSink = ConstructionInterfaceLayout.connectionSink(
          edge.access.get,
          boundaries(edge.target),
          edge.sourceAccess.get,
          boundaries(edge.source.get)
        ).getOrElse(fail(3, "connection lost its exact boundary-facing direction"))
        val (sink, source) = if leftSink then (target, other) else (other, target)
        sources.update(sink, source)
        connectionRegions.update(sink, edge.region.toVector)
    val cache = mutable.HashMap.empty[String, MemberDependency]
    def dependencies(path: String): MemberDependency =
      var current = canonical(path)
      val pending = mutable.ArrayBuffer.empty[String]
      val visited = mutable.HashSet.empty[String]
      while !cache.contains(current) && sources.contains(current) do
        if !visited.add(current) then
          fail(11, "ordinary interface connection has a dependency cycle")
        pending += current
        current = sources(current)
      var result = cache.getOrElseUpdate(
        current,
        MemberDependency(
          current,
          current,
          direct.get(current).map(_.toVector).getOrElse(Vector.empty),
          driverRegions.getOrElse(current, Vector.empty)
        )
      )
      pending.reverseIterator.foreach: path =>
        result = result.copy(
          path = path,
          effectRegions =
            connectionRegions.getOrElse(path, Vector.empty) ++ result.effectRegions
        )
        dependencyEdges += result.effectRegions.size
        if dependencyEdges > 262144 then
          fail(1, "ordinary member region dependencies exceed their budget")
        cache.update(path, result)
      result
    val groups = mutable.LinkedHashMap.empty[(String, String), mutable.ArrayBuffer[Expr[?]]]
    var observationEdges = 0
    def observe(owner: String, group: String, paths: Vector[String]): Unit =
      val values = paths.flatMap(path => dependencies(path).values)
      observationEdges += values.size
      if observationEdges > 262144 then fail(1, "digital observation edges exceed their budget")
      if values.nonEmpty then
        groups.getOrElseUpdate(owner -> group, mutable.ArrayBuffer.empty) ++= values
    val observedResolved = mutable.HashSet.empty[String]
    def observeResolved(owner: String, path: String): Unit =
      val group = canonical(path)
      if observedResolved.add(group) then observe(owner, group, Vector(path))
    interfaces.foreach: endpoint =>
      val forward = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[String]]
      endpoint.members.filter(member => indices.contains(member.path)).foreach: member =>
        if Set("valid", "stream").contains(member.protocol) && !member.name.endsWith(".ready") then
          // These suffixes come from the typed protocol expansion, never an authored label.
          val group = member.path.substring(0, member.path.lastIndexOf('.'))
          forward.getOrElseUpdate(group, mutable.ArrayBuffer.empty) += member.path
        else if member.protocol == "resolved" then
          observeResolved(endpoint.owner, member.path)
        else observe(endpoint.owner, member.path, Vector(member.path))
      forward.foreach((group, members) => observe(endpoint.owner, group, members.toVector))
    nets.foreach(net => observeResolved(net.owner, net.path))
    val observed = groups.toVector.sortBy(_._1).map: (key, values) =>
      val seen = new IdentityHashMap[Expr[?], java.lang.Boolean]()
      ObservationGroup(
        key._2,
        key._1,
        values.iterator.filter(value => seen.put(value, true) == null).toVector
      )
    val ordinary = members.filter(_.protocol == "plain").map(member => dependencies(member.path))
    observed -> ordinary
