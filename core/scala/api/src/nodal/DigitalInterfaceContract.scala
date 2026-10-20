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

private[nodal] final case class KernelInterfaceBoundarySnapshot(instance: String, endpoint: String)

private[nodal] final case class KernelInterfaceSnapshot(
    path: String,
    owner: String,
    identity: String,
    role: String,
    domain: String,
    dimensions: Vector[String],
    members: Vector[KernelInterfaceMemberSnapshot],
    roles: Vector[KernelInterfaceRoleSnapshot] = Vector.empty,
    boundary: Option[KernelInterfaceBoundarySnapshot] = None,
    actualMemberTypes: Vector[(String, String)] = Vector.empty
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
    sourceEndpointRole: Option[String] = None
)

/** Typed Interface/access and resolved-net capture; the construction session owns all mutable
  * facts.
  */
private[nodal] object DigitalInterfaceContract:
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
      shapeWitness: DataType[?] = Bool
  )
  final case class Operation(kind: String, values: Vector[Any], owner: String)

  /** One coherent externally observable value, rooted only in actual local drivers. The endpoint's
    * declared domain is deliberately not a proof of its contents.
    */
  final case class ObservationGroup(path: String, owner: String, values: Vector[Expr[?]])

  final case class Capture(
      interfaces: Vector[KernelInterfaceSnapshot],
      connectivity: Vector[KernelDigitalConnectivitySnapshot],
      abi: Vector[InterfaceAbiEntry],
      dataTypes: Vector[DataType[?]],
      valueRoots: Vector[Expr[?]],
      observationGroups: Vector[ObservationGroup]
  )

  def fail(number: Int, message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(f"NODAL-INTERFACE-054-$number%03d", message))
    ).get

  def netExpression[A <: Data](
      operation: String,
      endpoint: DigitalInout[?, ?],
      resultType: DataType[A]
  ): Expr[A] =
    ConstructionKernel.requireNoGeneratedEffect("resolved-net expression")
    if !ValueOperations.contains(operation) then fail(11, "unknown resolved-net expression")
    val expression = new KernelExpr[A](
      Vector(endpoint),
      resultType = Some(CandidateRuntime.typeDescriptor(resultType)),
      operation = Some(operation)
    )
    ConstructionKernel.expression(expression)
    expression

  def requireInvertible(endpoint: InterfacePort[?, ?]): Unit =
    endpoint.role.access.foreach:
      case RoleAccess.In(_) | RoleAccess.Out(_) | RoleAccess.Master(_) | RoleAccess.Slave(_) |
          RoleAccess.Nested(_, _) => ()
      case _ => fail(4, "automatic inversion requires fully complementary digital member access")

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
      ownerOf: Any => Option[String]
  ): Unit =
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
    captured.connectivity.filter(_.kind == "inout-pass-through").foreach: connection =>
      val left = representative(connection.target)
      val right =
        representative(connection.source.getOrElse(fail(11, "pass-through lost its source")))
      if left != right then parents.update(right, left)
    val drivers = captured.connectivity.filter(value =>
      Set("inout-drive", "inout-drive-low", "inout-drive-high").contains(value.kind)
    ).groupBy(value => representative(value.target))
    expressions.foreach: (expression, owner) =>
      expression.operation.filter(ValueOperations.contains).foreach: operation =>
        val endpoint = expression.operands match
          case Vector(value: DigitalInout[?, ?]) => value
          case _ => fail(11, "resolved-net expression requires exactly one typed endpoint")
        if !ownerOf(endpoint).contains(owner) then
          fail(7, "resolved-net expression belongs to another module or transaction")
        val path =
          pathOf(endpoint).getOrElse(fail(7, "resolved-net expression has no endpoint identity"))
        val expected = if operation == "digital_net_enable_view" then KernelTypeDescriptor("Bool")
        else CandidateRuntime.typeDescriptor(endpoint.dataType)
        if !expression.resultType.contains(expected) || expression.literal.nonEmpty then
          fail(8, "resolved-net expression has an incorrect exact result type")
        if operation != "digital_net_read" then
          val selected = drivers.getOrElse(representative(path), Vector.empty)
          if selected.isEmpty then
            fail(12, "split requires an authored conditional driver; no write value is available")
          if selected.size != 1 then
            fail(12, "split is ambiguous because the resolved net has multiple conditional drivers")

  def capture(
      declarations: Vector[Declaration],
      operations: Vector[Operation],
      pathOf: Any => Option[String],
      ownerOf: Any => Option[String]
  ): Capture =
    val owners = (declarations.map(_.owner) ++ operations.map(_.owner)).distinct
    val declarationsByOwner = declarations.groupBy(_.owner)
    val operationsByOwner = operations.groupBy(_.owner)
    val captured = owners.map(owner =>
      captureOwner(
        declarationsByOwner.getOrElse(owner, Vector.empty),
        operationsByOwner.getOrElse(owner, Vector.empty),
        pathOf,
        ownerOf
      )
    )
    Capture(
      captured.flatMap(_.interfaces),
      captured.flatMap(_.connectivity),
      captured.flatMap(_.abi),
      captured.flatMap(_.dataTypes),
      captured.flatMap(_.valueRoots),
      captured.flatMap(_.observationGroups)
    )

  private def captureOwner(
      declarations: Vector[Declaration],
      operations: Vector[Operation],
      pathOf: Any => Option[String],
      ownerOf: Any => Option[String]
  ): Capture =
    val declarationsByValue = new IdentityHashMap[AnyRef, Declaration]()
    declarations.foreach(value => declarationsByValue.put(value.value, value))
    val roles = new IdentityHashMap[InterfaceType[?], mutable.LinkedHashMap[String, Role[?]]]()
    val namedRoles = mutable.HashMap.empty[(String, String), Seq[RoleAccess]]
    val discovered = new IdentityHashMap[InterfaceType[?], java.lang.Boolean]()
    val pending = mutable.ArrayDeque.empty[(InterfaceType[?], Int)]
    def register(definition: InterfaceType[?], requested: Role[?]): Unit =
      val role = if requested.name == "monitor" && requested.access.isEmpty then
        ConstructionInterfaceLayout.monitorRole(definition)
      else requested
      val ordered = role.access.sortBy(ConstructionInterfaceLayout.accessMember)
      namedRoles.get(definition.name -> role.name).foreach: previous =>
        if previous != ordered then
          fail(
            2,
            s"Interface '${definition.name}' has conflicting definitions for role '${role.name}'"
          )
      namedRoles.update(definition.name -> role.name, ordered)
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
        case _ => ()
    operations.foreach: operation =>
      operation.values.foreach:
        case view: InterfacePort[?, ?] if view.origin.nonEmpty =>
          register(view.definition, view.role)
          pending.append((view.definition, 0))
        case _ => ()
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
      mutable.LinkedHashMap.empty[String, (Declaration, Vector[ConstructionInterfaceLayout.Leaf])]
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
        identities.get(definition.name).foreach: (previousContext, previous) =>
          if previous.size != leaves.size || previous.zip(leaves).exists: (before, after) =>
              before.name != after.name || before.protocol != after.protocol ||
                before.mode != after.mode || before.nestedInterfaces != after.nestedInterfaces ||
                before.analogAttributes != after.analogAttributes ||
                !sameLeafType(before, previousContext, after, declaration)
          then fail(5, s"Interface '${definition.name}' has conflicting exact member ABIs")
        identities.update(definition.name, declaration -> leaves)
        val domain =
          declaration.domain.getOrElse(fail(6, "interface endpoint has no resolved domain"))
        def snapshotLeaves(values: Vector[ConstructionInterfaceLayout.Leaf])
            : Vector[KernelInterfaceMemberSnapshot] =
          values.map(value =>
            KernelInterfaceMemberSnapshot(
              s"${declaration.path}.${value.name}",
              value.name,
              value.role,
              value.access,
              value.rendered(declaration.renderFormalType.getOrElse(declaration.renderType)),
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
          else Vector.empty
        )
    def declaration(value: AnyRef, owner: String): Declaration =
      val canonical = value match
        case endpoint: InterfacePort[?, ?] => root(endpoint)
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
    def valueType(value: Expr[?], context: Declaration): String =
      CandidateRuntime.expressionDataType(value).map(context.renderType).getOrElse(
        fail(8, "digital operand requires an exact type")
      )
    def exact(value: Expr[?], expected: String, context: Declaration): String =
      if valueType(value, context) != expected then
        fail(8, s"digital operand must have exact type '$expected'")
      owned(value, context.owner)
    def exactPayload(value: Expr[?], expected: DataType[?], context: Declaration): String =
      val actual = CandidateRuntime.expressionDataType(value).getOrElse(
        fail(8, "digital operand requires an exact type")
      )
      if !context.samePayloadTypes(actual, expected)
      then fail(8, "digital operand must have the exact owned payload type")
      owned(value, context.owner)
    val viewLayouts =
      new IdentityHashMap[InterfacePort[?, ?], Vector[ConstructionInterfaceLayout.Leaf]]()
    def portLeaves(endpoint: InterfacePort[?, ?], depth: Int = 0)
        : Vector[ConstructionInterfaceLayout.Leaf] =
      if depth > 128 then fail(1, "interface view nesting exceeds 128")
      if endpoint.origin.isEmpty then layouts.get(endpoint)
      else
        Option(viewLayouts.get(endpoint)).getOrElse:
          val original = portLeaves(endpoint.origin.get, depth + 1)
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
    def endpointLeaves(endpoint: InterfaceEndpoint): Vector[ConstructionInterfaceLayout.Leaf] =
      endpoint match
        case port: InterfacePort[?, ?] => portLeaves(port)
        case array: InterfaceArray[?, ?] =>
          val leaves = layouts.get(array)
          if leaves.exists(_.mode.nonEmpty) then
            fail(
              9,
              "resolved Interface array effects require an explicit physical component projection"
            )
          leaves
    operations.filter(_.kind == "interface-invert").foreach: operation =>
      operation.values match
        case Vector(original: InterfacePort[?, ?], view: InterfacePort[?, ?]) =>
          val context = declaration(original, operation.owner)
          if root(view) ne root(original) then fail(11, "inverted view changed its root identity")
          val _ = declaration(view, context.owner)
          val _ = endpointLeaves(view)
        case _ => fail(11, "inverted view lost its captured typed endpoints")
    def net(value: DigitalInout[?, ?], owner: String): Declaration =
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
      if !Set("Bits", "UInt", "SInt").contains(descriptor.kind) then
        fail(8, "resolved digital endpoints require a finite bit-vector type")
      if value.profile == ResolutionProfile.PortableBoundaryOnly &&
        value.placement == InoutPlacement.InternalResolvedNet
      then fail(9, "portable boundary resolution does not support an internal resolved net")
      result
    declarations.foreach: candidate =>
      candidate.value match
        case endpoint: DigitalInout[?, ?] =>
          val _ = net(endpoint, candidate.owner)
          declarationTypes += endpoint.dataType
        case _ => ()
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
        sourceEndpointRole
      )
    val connectivity = operations.filter(value => EffectOperations.contains(value.kind)).flatMap:
      operation =>
        operation.values match
          case Vector(left: InterfaceEndpoint, right: InterfaceEndpoint)
              if operation.kind == "interface-connect" =>
            val a = declaration(left, operation.owner)
            val b = declaration(right, operation.owner)
            if left.definition.name != right.definition.name || a.domain != b.domain ||
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
                first.mode != second.mode ||
                first.nestedInterfaces != second.nestedInterfaces ||
                !sameLeafType(first, a, second, b)
              then fail(5, "exact interface connection changed a member ABI")
              val sink = ConstructionInterfaceLayout.connectionSink(
                first.access,
                a.boundary.nonEmpty,
                second.access,
                b.boundary.nonEmpty
              )
              val complementary = sink.nonEmpty ||
                (first.protocol == "resolved" && first.access == "connect" &&
                  second.access == "connect")
              if first.analogType.nonEmpty || !complementary then
                fail(3, "exact digital interface connection requires complementary digital access")
              if first.protocol != "resolved" then
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
            else if context.boundary.nonEmpty then leaf.access == "in"
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
          case Vector(endpoint: DigitalInout[?, ?], value: Expr[?], enable: Expr[?])
              if operation.kind == "inout-drive" =>
            val context = net(endpoint, operation.owner)
            if !Set("push-pull", "open-source").contains(endpoint.mode.name) then
              fail(9, "drive mode forbids an arbitrary value driver")
            val expected = context.renderType(endpoint.dataType)
            Vector(effect(
              operation,
              context.path,
              value = Some(exactPayload(value, endpoint.dataType, context)),
              enable = Some(exact(enable, "Bool", context)),
              dataType = Some(expected)
            ))
          case Vector(endpoint: DigitalInout[?, ?], enable: Expr[?])
              if Set("inout-drive-low", "inout-drive-high").contains(operation.kind) =>
            val context = net(endpoint, operation.owner)
            val required =
              if operation.kind == "inout-drive-low" then "open-drain" else "open-source"
            if endpoint.mode.name != required then
              fail(9, s"${operation.kind} requires $required mode")
            Vector(effect(
              operation,
              context.path,
              enable = Some(exact(enable, "Bool", context)),
              dataType = Some(context.renderType(endpoint.dataType))
            ))
          case Vector(endpoint: DigitalInout[?, ?]) if operation.kind == "inout-high-z" =>
            val context = net(endpoint, operation.owner)
            if endpoint.mode.name == "read-only" then
              fail(9, "read-only endpoint cannot create a high-impedance driver")
            Vector(effect(
              operation,
              context.path,
              dataType = Some(context.renderType(endpoint.dataType))
            ))
          case Vector(left: DigitalInout[?, ?], right: DigitalInout[?, ?])
              if operation.kind == "inout-pass-through" =>
            val a = net(left, operation.owner)
            val b = net(right, operation.owner)
            val expected = a.renderType(left.dataType)
            if a.path == b.path || expected != b.renderType(right.dataType) ||
              left.mode.name != right.mode.name || left.profile != right.profile
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
    Capture(
      interfaces.sortBy(_.path),
      connectivity,
      abi.sortBy(_.logicalPath),
      declarationTypes.toVector,
      valueRoots,
      observations(declarations, interfaces, connectivity, valueRoots, pathOf)
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
      pathOf: Any => Option[String]
  ): Vector[ObservationGroup] =
    val members = interfaces.flatMap(_.members).filter(member =>
      Set("plain", "valid", "stream", "resolved").contains(member.protocol)
    )
    val nets = declarations.filter(_.value.isInstanceOf[DigitalInout[?, ?]])
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
    val resolved = members.filter(_.protocol == "resolved").map(_.path).toSet ++ nets.map(_.path)
    connectivity.foreach: edge =>
      if edge.kind == "inout-pass-through" ||
        edge.kind == "interface-connect" && resolved.contains(edge.target)
      then alias(edge.target, edge.source.get)
    def canonical(path: String): String = paths(names(representative(indices(path))))
    val expressions = valueRoots.map(value =>
      pathOf(value).getOrElse(fail(7, "digital driver has no actual captured identity")) -> value
    ).toMap
    val direct = mutable.HashMap.empty[String, mutable.ArrayBuffer[Expr[?]]]
    val sources = mutable.HashMap.empty[String, String]
    var dependencyEdges = 0
    val boundaries = interfaces.filter(_.boundary.nonEmpty).flatMap(_.members.map(_.path)).toSet
    connectivity.foreach: edge =>
      val target = canonical(edge.target)
      val values = (edge.value.toVector ++ edge.enable.toVector).map(path =>
        expressions.getOrElse(path, fail(7, "digital dependency has no actual captured value"))
      )
      dependencyEdges += values.size
      if dependencyEdges > 262144 then fail(1, "digital driver edges exceed their budget")
      if values.nonEmpty then direct.getOrElseUpdate(target, mutable.ArrayBuffer.empty) ++= values
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
    val cache = mutable.HashMap.empty[String, Vector[Expr[?]]]
    def dependencies(path: String): Vector[Expr[?]] =
      var current = canonical(path)
      val pending = mutable.ArrayBuffer.empty[String]
      val visited = mutable.HashSet.empty[String]
      while !cache.contains(current) && sources.contains(current) do
        if !visited.add(current) then
          fail(11, "ordinary interface connection has a dependency cycle")
        pending += current
        current = sources(current)
      val result =
        cache.getOrElseUpdate(current, direct.get(current).map(_.toVector).getOrElse(Vector.empty))
      pending.foreach(path => cache.update(path, result))
      result
    val groups = mutable.LinkedHashMap.empty[(String, String), mutable.ArrayBuffer[Expr[?]]]
    var observationEdges = 0
    def observe(owner: String, group: String, paths: Vector[String]): Unit =
      val values = paths.flatMap(dependencies)
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
    groups.toVector.sortBy(_._1).map: (key, values) =>
      val seen = new IdentityHashMap[Expr[?], java.lang.Boolean]()
      ObservationGroup(
        key._2,
        key._1,
        values.iterator.filter(value => seen.put(value, true) == null).toVector
      )
