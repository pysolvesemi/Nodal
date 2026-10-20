package nodal.internal.bridge

import nodal.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.collection.mutable

/** Projection of closed Interface/access and resolved-net contracts. The shared digital value DAG
  * owns all operand traversal, and the existing native type renderer owns every payload proof.
  */
private[nodal] object InterfaceMlir:
  private def fail(message: String, path: String): Nothing =
    scala.util.Failure[Nothing](
      new BridgeException(BridgeDiagnostic("NODAL-BRIDGE-054-INTERFACE", message, Some(path)))
    ).get

  private def requireValid(condition: Boolean, message: String, path: String): Unit =
    if !condition then fail(message, path)

  private def quoted(value: String): String =
    val escaped = value.flatMap:
      case '\\' => "\\\\"
      case '"' => "\\\""
      case character if character.isControl => f"\\${character.toInt & 0xff}%02X"
      case character => character.toString
    s"\"$escaped\""

  private def array(values: Iterable[String]): String = values.mkString("[", ", ", "]")
  private def dictionary(values: (String, String)*): String =
    values.sortBy(_._1).map((key, value) => s"$key = $value").mkString("{", ", ", "}")

  private def metadata(base: String, extra: (String, String)*): String =
    requireValid(
      base.startsWith("{") && base.endsWith("}"),
      "invalid metadata callback",
      "interface"
    )
    val inside = base.drop(1).dropRight(1).trim
    val fields = (Vector("interface_abi_version" -> "1 : i64") ++ extra).sortBy(_._1)
      .map((key, value) => s"$key = $value")
    (Option.when(inside.nonEmpty)(inside).toVector ++ fields).mkString("{", ", ", "}")

  private def hash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x").mkString

  private def operation(
      name: String,
      results: Vector[(String, String)],
      operands: Vector[(String, String)],
      location: String,
      attributes: (String, String)*
  ): String =
    val lhs = if results.isEmpty then "" else results.map(_._1).mkString(", ") + " = "
    val output = results.map(_._2) match
      case Vector(single) => single
      case values => values.mkString("(", ", ", ")")
    s"$lhs${quoted(name)}(${operands.map(_._1).mkString(", ")}) <${dictionary(attributes*)}> : " +
      s"(${operands.map(_._2).mkString(", ")}) -> $output $location"

  private def mode(value: String, path: String): String = value match
    case "push-pull" => "push_pull"
    case "open-drain" => "open_drain"
    case "open-source" => "open_source"
    case "read-only" => "read_only"
    case "pass-through" => "pass_through"
    case _ => fail("unknown digital drive mode", path)

  private def placement(value: String, path: String): String = value match
    case "TopLevelPin" => "top_level_pin"
    case "BlackBoxPin" => "black_box_pin"
    case "HierarchyPassThrough" => "hierarchy_pass_through"
    case "PadCellBoundary" => "pad_cell_boundary"
    case "InternalResolvedNet" => "internal_resolved_net"
    case _ => fail("unknown resolved-net placement", path)

  private def profile(value: String, path: String): String = value match
    case "PortableBoundaryOnly" => "portable_boundary_only"
    case "FullResolvedSimulation" => "full_resolved_simulation"
    case "TechnologyMapped" => "technology_mapped"
    case _ => fail("unknown resolved-net resolution profile", path)

  private def nestedRoles(values: Vector[(String, String)]): String =
    array(values.map((path, role) => dictionary("path" -> quoted(path), "role" -> quoted(role))))
  private def nestedInterfaces(values: Vector[(String, String)]): String =
    array(values.map((path, identity) =>
      dictionary("path" -> quoted(path), "definition" -> quoted(identity))
    ))

  private def permission(member: KernelInterfaceMemberSnapshot): String = dictionary(
    "path" -> quoted(member.name),
    "access" -> quoted(member.access),
    "leaf_role" -> quoted(member.role),
    "nested_roles" -> nestedRoles(member.nestedRoles),
    "nested_interfaces" -> nestedInterfaces(member.nestedInterfaces)
  )

  private final case class Definition(
      owner: String,
      identity: String,
      roles: Vector[KernelInterfaceRoleSnapshot]
  )

  final class Plan(
      snapshot: ConstructionSnapshot,
      sameType: (String, String, String) => Boolean,
      instanceType: (String, String, String, KernelInstanceSnapshot, String) => Boolean
  ):
    private val modules = snapshot.modules.map(value => value.path -> value).toMap
    private val declarations = snapshot.modules.flatMap(module =>
      module.declarations.map(value => value.path -> (module, value))
    ).toMap
    private val endpoints = snapshot.interfaceContracts.map(value => value.path -> value).toMap
    private val nets = snapshot.resolvedNets.map(value => value.path -> value).toMap
    private val effects = snapshot.digitalConnectivity.groupBy(_.owner)
    private val members = snapshot.interfaceContracts.flatMap(endpoint =>
      endpoint.members.map(value => value.path -> (endpoint, value))
    ).toMap

    private val shapeQuery = new StructuralParameterBinding.Query(snapshot)
    private val instanceQueries =
      new StructuralParameterBinding.InstanceQueries(snapshot, Some(shapeQuery))
    private def sameDimensions(
        left: KernelInterfaceSnapshot,
        right: KernelInterfaceSnapshot
    ): Boolean =
      try
        left.dimensions.size == right.dimensions.size &&
          left.dimensions.zip(right.dimensions).forall((a, b) =>
            shapeQuery.resolve(left.owner, a) == shapeQuery.resolve(right.owner, b)
          )
      catch case _: IllegalArgumentException => false

    requireValid(
      endpoints.size == snapshot.interfaceContracts.size,
      "duplicate interface endpoint identity",
      snapshot.root
    )
    requireValid(
      nets.size == snapshot.resolvedNets.size,
      "duplicate resolved-net identity",
      snapshot.root
    )
    requireValid(
      snapshot.digitalConnectivity.map(_.path).distinct.size == snapshot.digitalConnectivity.size,
      "duplicate digital connectivity identity",
      snapshot.root
    )

    private val actualTypes = snapshot.interfaceContracts.map(endpoint =>
      endpoint.path -> endpoint.actualMemberTypes.toMap
    ).toMap
    private def actualType(
        endpoint: KernelInterfaceSnapshot,
        member: KernelInterfaceMemberSnapshot
    ): String =
      actualTypes(endpoint.path).getOrElse(member.name, member.dataType)
    private def actualMember(
        endpoint: KernelInterfaceSnapshot,
        member: KernelInterfaceMemberSnapshot
    ): KernelInterfaceMemberSnapshot =
      member.copy(dataType = actualType(endpoint, member))
    private def sameShape(
        left: Vector[KernelInterfaceMemberSnapshot],
        right: Vector[KernelInterfaceMemberSnapshot]
    ): Boolean =
      left.size == right.size && left.zip(right).forall: (a, b) =>
        a.name == b.name && a.protocol == b.protocol && a.mode == b.mode &&
          a.nestedInterfaces == b.nestedInterfaces && a.analogAttributes == b.analogAttributes &&
          (a.dataType == b.dataType ||
            (!Set("conservative", "signal-flow").contains(a.protocol) &&
              sameType(a.dataType, b.dataType, a.path)))

    private def sameRole(
        left: Vector[KernelInterfaceMemberSnapshot],
        right: Vector[KernelInterfaceMemberSnapshot]
    ): Boolean =
      sameShape(left, right) && left.zip(right).forall: (a, b) =>
        a.copy(path = a.name, dataType = "") == b.copy(path = b.name, dataType = "")

    private def analog(member: KernelInterfaceMemberSnapshot): String =
      dictionary(member.analogAttributes.map((name, value) => name -> quoted(value))*)

    private def memberMetadata(member: KernelInterfaceMemberSnapshot): String =
      val references = Option.when(member.protocol == "conservative" &&
        member.analogAttributes.toMap.get("discipline").contains("electrical"))(
        "analog_refs" ->
          dictionary("discipline" -> "@electrical", "potential" -> "@Voltage", "flow" -> "@Current")
      ).toVector
      metadata("{}", (Vector("analog" -> analog(member)) ++ references)*)

    private def validateRole(
        endpoint: KernelInterfaceSnapshot,
        role: KernelInterfaceRoleSnapshot
    ): Unit =
      requireValid(
        role.name.nonEmpty && role.members.size <= 65536 &&
          role.members.map(_.name).distinct.size == role.members.size &&
          sameShape(endpoint.members, role.members),
        "interface role must preserve every exact member",
        endpoint.path
      )
      role.members.foreach: member =>
        requireValid(
          member.name.nonEmpty && member.path == s"${endpoint.path}.${member.name}" &&
            member.nestedRoles.map(_._1) == member.nestedInterfaces.map(_._1) &&
            member.nestedRoles.forall((path, selected) =>
              path.nonEmpty && selected.nonEmpty && member.name.startsWith(path + ".")
            ) &&
            member.nestedInterfaces.forall((_, identity) => identity.nonEmpty) &&
            member.role == member.nestedRoles.lastOption.map(_._2).getOrElse(role.name),
          "member or nested role identity is malformed",
          member.path
        )
        requireValid(
          member.nestedRoles.map(_._1).distinct.size == member.nestedRoles.size &&
            member.nestedRoles.map(_._1).sliding(2).forall(value =>
              value.size < 2 || value(1).startsWith(value(0) + ".")
            ),
          "nested role chain must be ordered and unique",
          member.path
        )
        val allowed = member.protocol match
          case "plain" | "valid" | "stream" => Set("in", "out", "observe")
          case "resolved" => Set("read", "drive", "connect", "observe")
          case "conservative" => Set("connect", "sense", "contribute")
          case "signal-flow" => Set("in", "out", "observe")
          case _ => fail("unknown interface protocol", member.path)
        requireValid(
          allowed.contains(member.access) &&
            (role.name != "monitor" ||
              member.access == (if member.protocol == "conservative" then "sense" else "observe")),
          "member access violates its protocol or monitor role",
          member.path
        )
        requireValid(
          member.mode.nonEmpty == (member.protocol == "resolved"),
          "resolved mode differs from member kind",
          member.path
        )
        member.mode.foreach(value => mode(value, member.path))
        val fields = member.analogAttributes.toMap
        requireValid(
          fields.size == member.analogAttributes.size && fields.values.forall(_.nonEmpty),
          "analog member attributes require exact unique nonempty identities",
          member.path
        )
        val validAnalog = member.protocol match
          case "conservative" =>
            fields.keySet == Set("discipline", "potential_nature", "flow_nature") &&
            fields.get("discipline").exists(value => member.dataType == s"Terminal($value)") &&
            (fields.get("discipline") != Some("electrical") ||
              (fields.get("potential_nature").contains("voltage") &&
                fields.get("flow_nature").contains("current")))
          case "signal-flow" => fields.keySet == Set("dimension") &&
            fields.get("dimension").exists(value => member.dataType == s"AnalogSignal($value)")
          case _ => fields.isEmpty
        requireValid(
          validAnalog,
          "analog source contract differs from its exact kind/type",
          member.path
        )

      role.members.filter(value => Set("valid", "stream").contains(value.protocol)).foreach(
        member =>
          requireValid(
            member.name.lastIndexOf('.') > 0,
            "protocol leaf requires its member parent",
            member.path
          )
      )
      role.members.filter(value => Set("valid", "stream").contains(value.protocol))
        .groupBy(value =>
          value.name.substring(0, value.name.lastIndexOf('.')) -> value.protocol
        ).foreach:
          case ((_, protocol), leaves) =>
            val byName = leaves.map(value => value.name.split('.').last -> value).toMap
            val expected = if protocol == "stream" then Set("valid", "payload", "ready")
            else Set("valid", "payload")
            requireValid(
              byName.keySet == expected && byName.size == leaves.size &&
                byName("valid").dataType == "Bool" &&
                byName("valid").access == byName("payload").access,
              "protocol must retain exact valid/payload leaves and direction",
              endpoint.path
            )
            byName.get("ready").foreach: ready =>
              val forward = byName("valid").access
              val inverse =
                if forward == "in" then "out" else if forward == "out" then "in" else "observe"
              requireValid(
                ready.dataType == "Bool" && ready.access == inverse,
                "Stream ready direction or type is not complementary",
                ready.path
              )

    snapshot.interfaceContracts.foreach: endpoint =>
      val (module, declaration) = declarations.getOrElse(
        endpoint.path,
        fail("interface endpoint has no declaration", endpoint.path)
      )
      val attributes = declaration.attributes.toMap
      requireValid(
        module.path == endpoint.owner && modules.contains(endpoint.owner) &&
          Set("interface-port", "interface-array").contains(declaration.kind) &&
          declaration.generatedScope.isEmpty &&
          declaration.interfaceBoundary == endpoint.boundary &&
          attributes.get("interface").contains(endpoint.identity) &&
          attributes.get("role").contains(endpoint.role) &&
          declaration.domain.contains(endpoint.domain) && endpoint.identity.nonEmpty &&
          endpoint.roles.nonEmpty &&
          endpoint.roles.map(_.name).distinct.size == endpoint.roles.size &&
          (if declaration.kind == "interface-port" then endpoint.dimensions.isEmpty
           else
             endpoint.dimensions.size == 1 &&
             declaration.attributes.collect { case ("count", value) => value } ==
               endpoint.dimensions),
        "interface declaration and complete typed contract disagree",
        endpoint.path
      )
      requireValid(
        if endpoint.boundary.isEmpty then endpoint.actualMemberTypes.isEmpty
        else
          endpoint.actualMemberTypes.map(_._1) == endpoint.members.map(_.name) &&
          endpoint.actualMemberTypes.size <= 65536 &&
          endpoint.actualMemberTypes.forall(_._2.nonEmpty)
        ,
        "Interface actual type inventory must preserve every ordered formal member exactly once",
        endpoint.path
      )
      endpoint.boundary.foreach: boundary =>
        val instance = module.instances.find(_.path == boundary.instance).getOrElse(
          fail("Interface projection lost its exact immediate child instance", endpoint.path)
        )
        val child = endpoints.getOrElse(
          boundary.endpoint,
          fail("Interface projection lost its actual child endpoint", endpoint.path)
        )
        val childDeclaration = declarations(child.path)._2
        val binding = instance.actualBindings.filter(_.requirement == child.domain)
        def canonical(values: Vector[KernelInterfaceRoleSnapshot]) = values.map(role =>
          role.name -> role.copy(members = role.members.map(value => value.copy(path = value.name)))
        ).toMap
        val endpointRoles = canonical(endpoint.roles)
        val childRoles = canonical(child.roles)
        requireValid(
          child.owner == instance.childModule && child.boundary.isEmpty &&
            childDeclaration.kind == declaration.kind && childDeclaration.generatedScope.isEmpty &&
            instance.generatedScope.isEmpty &&
            endpoint.identity == child.identity && endpoint.role == child.role &&
            endpointRoles.get(endpoint.role) == childRoles.get(child.role) &&
            endpointRoles.forall((name, role) =>
              childRoles.get(name).forall(_ == role)
            ) &&
            binding.size == 1 && binding.head.actual == endpoint.domain &&
            endpoint.members.forall(value =>
              Set("plain", "valid", "stream").contains(value.protocol)
            ),
          "Interface projection changes exact child, selected roles, ABI or actual domain binding",
          endpoint.path
        )
        val childMembers = child.members.map(value => value.name -> value).toMap
        endpoint.members.foreach: member =>
          val formal = childMembers(member.name)
          requireValid(
            instanceType(
              actualType(endpoint, member),
              formal.dataType,
              member.path,
              instance,
              formal.path
            ),
            "Interface actual payload differs from its exact finalized child type",
            member.path
          )
        if declaration.kind == "interface-array" then
          try
            instanceQueries.validateInterfaceDimensions(
              module.path,
              instance.path,
              child.path,
              endpoint.dimensions
            )
          catch case error: IllegalArgumentException => fail(error.getMessage, endpoint.path)
      endpoint.roles.foreach(validateRole(endpoint, _))
      requireValid(
        endpoint.roles.find(_.name == endpoint.role).exists(_.members == endpoint.members),
        "selected endpoint role differs from its complete map",
        endpoint.path
      )

    snapshot.resolvedNets.foreach: net =>
      val (_, declaration) =
        declarations.getOrElse(net.path, fail("resolved net has no declaration", net.path))
      val fields = declaration.attributes.toMap
      requireValid(
        declaration.kind == "digital-inout" && declaration.generatedScope.isEmpty &&
          declaration.dataType.contains(net.dataType) && fields.get("mode").contains(net.mode) &&
          fields.get("placement").contains(net.placement) &&
          fields.get("profile").contains(net.profile),
        "resolved-net declaration and typed contract disagree",
        net.path
      )
      val _ = mode(net.mode, net.path)
      val selectedPlacement = placement(net.placement, net.path)
      val selectedProfile = profile(net.profile, net.path)
      requireValid(
        selectedPlacement != "internal_resolved_net" || selectedProfile != "portable_boundary_only",
        "internal net exceeds portable-boundary capabilities",
        net.path
      )

    private val definitions: Vector[Definition] =
      val entries = mutable.LinkedHashMap.empty[(String, String), mutable.LinkedHashMap[
        String,
        Vector[KernelInterfaceMemberSnapshot]
      ]]
      var count = 0L
      def add(
          owner: String,
          identity: String,
          role: String,
          values: Vector[KernelInterfaceMemberSnapshot]
      ): Unit =
        val normalized = values
        val known = entries.getOrElseUpdate(owner -> identity, mutable.LinkedHashMap.empty)
        known.headOption.foreach((_, previous) =>
          requireValid(
            sameShape(previous, normalized),
            "interface semantic identity has conflicting exact member types",
            identity
          )
        )
        known.get(role).foreach(previous =>
          requireValid(
            sameRole(previous, normalized),
            "interface role identity has conflicting maps",
            identity
          )
        )
        if !known.contains(role) then
          count += normalized.size
          requireValid(
            count <= 262144L,
            "reachable interface definitions exceed the finite projection budget",
            identity
          )
          known.update(role, values)
      snapshot.interfaceContracts.sortBy(_.path).foreach: endpoint =>
        endpoint.roles.foreach: role =>
          val actualMembers = role.members.map(actualMember(endpoint, _))
          add(endpoint.owner, endpoint.identity, role.name, actualMembers)
          val applications = actualMembers.flatMap(_.nestedInterfaces).distinct
          applications.foreach: (prefix, identity) =>
            val selected = actualMembers.filter(_.name.startsWith(prefix + "."))
            val names = selected.flatMap(_.nestedRoles.find(_._1 == prefix).map(_._2)).distinct
            requireValid(
              names.size == 1,
              "nested role application is incomplete or ambiguous",
              endpoint.path
            )
            val projected = selected.map: member =>
              val strip = prefix + "."
              member.copy(
                name = member.name.stripPrefix(strip),
                nestedRoles = member.nestedRoles.filter(_._1.startsWith(strip)).map((path, name) =>
                  path.stripPrefix(strip) -> name
                ),
                nestedInterfaces = member.nestedInterfaces.filter(_._1.startsWith(strip)).map(
                  (path, name) => path.stripPrefix(strip) -> name
                )
              )
            add(endpoint.owner, identity, names.head, projected)
      entries.toVector.sortBy(_._1).map:
        case ((owner, identity), roles) => Definition(
            owner,
            identity,
            roles.toVector.sortBy(_._1).map((name, values) =>
              KernelInterfaceRoleSnapshot(name, values)
            )
          )

    private def sourceMember(
        path: String,
        owner: String
    ): (KernelInterfaceSnapshot, KernelInterfaceMemberSnapshot) =
      val result =
        members.getOrElse(path, fail("connectivity references an unknown interface member", path))
      requireValid(result._1.owner == owner, "interface member belongs to another module", path)
      result

    private def selectedMember(
        path: String,
        owner: String,
        selectedRole: Option[String]
    ): (KernelInterfaceSnapshot, KernelInterfaceMemberSnapshot) =
      val (endpoint, base) = sourceMember(path, owner)
      val role = selectedRole.getOrElse(fail("member access has no selected endpoint role", path))
      requireValid(
        endpoint.boundary.isEmpty || role == endpoint.role,
        "child Interface access cannot change its declared selected role",
        path
      )
      val selected = endpoint.roles.find(_.name == role).getOrElse(fail(
        "member access selects an absent role map",
        path
      ))
      endpoint -> selected.members.find(_.name == base.name).getOrElse(fail(
        "role map dropped a member",
        path
      ))

    private def net(path: String, owner: String): KernelResolvedNetSnapshot =
      val value = nets.getOrElse(path, fail("connectivity requires an actual resolved net", path))
      requireValid(
        declarations(path)._1.path == owner,
        "resolved net belongs to another module",
        path
      )
      value

    snapshot.digitalConnectivity.foreach: effect =>
      requireValid(
        modules.contains(effect.owner) && effect.path.startsWith(effect.owner + ".") &&
          DigitalInterfaceContract.EffectOperations.contains(effect.kind),
        "connectivity effect has no exact kind or owner",
        effect.path
      )
      if effect.kind.startsWith("interface-") then
        val (endpoint, target) = selectedMember(effect.target, effect.owner, effect.endpointRole)
        if effect.kind != "interface-connect" then
          requireValid(
            effect.sourceRole.isEmpty && effect.sourceAccess.isEmpty &&
              effect.sourceNestedRoles.isEmpty && effect.sourceEndpointRole.isEmpty,
            "one-sided member effect carries foreign source-role authority",
            effect.path
          )
        requireValid(
          effect.member.contains(target.name) &&
            effect.dataType.contains(actualType(endpoint, target)) &&
            effect.role.contains(target.role) && effect.access.contains(target.access) &&
            effect.nestedRoles == target.nestedRoles &&
            !Set("conservative", "signal-flow").contains(target.protocol) && effect.enable.isEmpty,
          "interface effect differs from its selected actual role map",
          effect.path
        )
        effect.kind match
          case "interface-connect" =>
            val (other, source) = selectedMember(
              effect.source.getOrElse(fail("interface connection lost its source", effect.path)),
              effect.owner,
              effect.sourceEndpointRole
            )
            requireValid(
              endpoint.identity == other.identity && endpoint.domain == other.domain &&
                sameDimensions(endpoint, other) &&
                target.name == source.name &&
                sameType(actualType(endpoint, target), actualType(other, source), target.path) &&
                target.protocol == source.protocol && target.mode == source.mode &&
                target.nestedInterfaces == source.nestedInterfaces &&
                effect.sourceRole.contains(source.role) &&
                effect.sourceAccess.contains(source.access) &&
                effect.sourceNestedRoles == source.nestedRoles && effect.value.isEmpty &&
                (ConstructionInterfaceLayout.connectionSink(
                  target.access,
                  endpoint.boundary.nonEmpty,
                  source.access,
                  other.boundary.nonEmpty
                ).nonEmpty ||
                  (target.protocol == "resolved" && target.access == "connect" &&
                    source.access == "connect")),
              "exact connection changes identity, shape, domain, protocol or complementary access",
              effect.path
            )
          case "interface-drive" =>
            requireValid(
              effect.source.isEmpty && effect.value.nonEmpty &&
                (if endpoint.boundary.nonEmpty then target.access == "in"
                 else Set("out", "drive").contains(target.access)) &&
                target.mode.forall(_ == "push-pull"),
              "member drive violates actual role or mode",
              effect.path
            )
          case "interface-observe" =>
            requireValid(
              effect.source.isEmpty && effect.value.isEmpty && target.access != "drive",
              "member observation violates actual role",
              effect.path
            )
          case _ => fail("unknown interface effect", effect.path)
      else
        val target = net(effect.target, effect.owner)
        requireValid(
          effect.dataType.contains(target.dataType) && effect.member.isEmpty &&
            effect.role.isEmpty && effect.access.isEmpty && effect.sourceRole.isEmpty &&
            effect.sourceAccess.isEmpty && effect.nestedRoles.isEmpty &&
            effect.sourceNestedRoles.isEmpty &&
            effect.endpointRole.isEmpty && effect.sourceEndpointRole.isEmpty,
          "net effect differs from its exact endpoint type",
          effect.path
        )
        effect.kind match
          case "inout-drive" => requireValid(
              Set("push-pull", "open-source").contains(target.mode) && effect.value.nonEmpty &&
                effect.enable.nonEmpty && effect.source.isEmpty,
              "conditional drive violates exact mode/operands",
              effect.path
            )
          case "inout-drive-low" | "inout-drive-high" =>
            val expected = if effect.kind == "inout-drive-low" then "open-drain" else "open-source"
            requireValid(
              target.mode == expected && effect.value.isEmpty && effect.enable.nonEmpty &&
                effect.source.isEmpty,
              "active-level drive violates exact mode/operands",
              effect.path
            )
          case "inout-high-z" => requireValid(
              target.mode != "read-only" && effect.value.isEmpty && effect.enable.isEmpty &&
                effect.source.isEmpty,
              "release violates exact mode/operands",
              effect.path
            )
          case "inout-pass-through" =>
            val source = net(
              effect.source.getOrElse(fail("pass-through lost its source", effect.path)),
              effect.owner
            )
            requireValid(
              source.path != target.path && source.dataType == target.dataType &&
                source.mode == target.mode && source.profile == target.profile &&
                effect.value.isEmpty && effect.enable.isEmpty,
              "pass-through changes resolved-net contract",
              effect.path
            )
          case _ => fail("unknown net effect", effect.path)

    // This finite equivalence relation models authored net connections only. It is not a
    // second expression traversal or a simulation of resolved values.
    private val netComponents = mutable.HashMap.from(nets.keys.map(path => path -> path))
    private def component(path: String): String =
      var current = path
      var steps = 0
      while netComponents(current) != current do
        current = netComponents(current)
        steps += 1
        requireValid(steps <= nets.size, "resolved-net equivalence cycle", path)
      current
    requireValid(
      nets.size <= 65536 && snapshot.digitalConnectivity.size <= 262144,
      "digital connectivity exceeds the finite structural budget",
      snapshot.root
    )
    snapshot.digitalConnectivity.filter(_.kind == "inout-pass-through").foreach: effect =>
      val left = component(effect.target)
      val right = component(effect.source.get)
      if left != right then netComponents.update(left, right)
    private val drivers = snapshot.digitalConnectivity.filter(effect =>
      Set("inout-drive", "inout-drive-low", "inout-drive-high").contains(effect.kind)
    ).groupBy(effect => component(effect.target))
    snapshot.digitalConnectivity.flatMap: effect =>
      effect.kind match
        case "interface-drive" if members(effect.target)._2.protocol != "resolved" =>
          Vector(effect.target)
        case "interface-connect" if members(effect.target)._2.protocol != "resolved" =>
          val target = members(effect.target)._1
          val source = members(effect.source.get)._1
          val leftSink = ConstructionInterfaceLayout.connectionSink(
            effect.access.get,
            target.boundary.nonEmpty,
            effect.sourceAccess.get,
            source.boundary.nonEmpty
          ).get
          Vector(if leftSink then effect.target else effect.source.get)
        case _ => Vector.empty
    .groupBy(identity).foreach: (path, drivers) =>
      requireValid(drivers.size == 1, "interface leaf has multiple ordinary drivers", path)
    snapshot.digitalExpressions.filter(expression =>
      Set("digital_net_write_view", "digital_net_enable_view").contains(expression.operation)
    ).foreach: expression =>
      requireValid(
        expression.operands.size == 1 && nets.contains(expression.operands.head),
        "split projection has no exact resolved net",
        expression.path
      )
      val endpoint = net(expression.operands.head, expression.owner)
      requireValid(
        drivers.getOrElse(component(endpoint.path), Vector.empty).size == 1,
        "split projection requires exactly one actual conditional value driver",
        expression.path
      )

    def roots(module: KernelModuleSnapshot): Vector[String] =
      (module.declarations.filter(_.kind == "digital-inout").map(_.path) ++
        effects.getOrElse(module.path, Vector.empty).flatMap(value =>
          value.value.toVector ++ value.enable.toVector ++
            (if value.kind.startsWith("inout-") then Vector(value.target) ++ value.source.toVector
             else Vector.empty)
        )).distinct.sorted

    def declarationType(declaration: KernelDeclarationSnapshot, payloadNativeType: String): String =
      if declaration.kind != "digital-inout" then payloadNativeType
      else
        val selected = nets.getOrElse(
          declaration.path,
          fail("net declaration lost its typed contract", declaration.path)
        )
        s"!nodal.resolved<${quoted(mode(selected.mode, declaration.path))}, $payloadNativeType>"

    def renderDeclaration(
        declaration: KernelDeclarationSnapshot,
        result: String,
        resultType: String,
        sourceMetadata: String,
        sourceLocation: String
    ): Vector[String] =
      val selected = nets.getOrElse(
        declaration.path,
        fail("net declaration lost its typed contract", declaration.path)
      )
      Vector(operation(
        "nodal.resolved_net",
        Vector(result -> resultType),
        Vector.empty,
        sourceLocation,
        "name" -> quoted(declaration.name),
        "metadata" -> metadata(
          sourceMetadata,
          "placement" -> quoted(placement(selected.placement, selected.path)),
          "profile" -> quoted(profile(selected.profile, selected.path))
        )
      ))

    def renderValue(
        expression: KernelDigitalExpressionSnapshot,
        result: String,
        resultType: String,
        operands: Vector[(String, String)],
        nativeType: (String, String) => String,
        sourceMetadata: String,
        sourceLocation: String
    ): DigitalValueMlir.Projection =
      requireValid(
        DigitalInterfaceContract.ValueOperations.contains(expression.operation) &&
          expression.operands.size == 1 && operands.size == 1 && expression.literal.isEmpty &&
          expression.enumIdentity.isEmpty && expression.caseIds.isEmpty,
        "invalid typed net value operation",
        expression.path
      )
      val endpoint = net(expression.operands.head, expression.owner)
      requireValid(
        expression.dataType ==
          (if expression.operation == "digital_net_enable_view" then "Bool" else endpoint.dataType),
        "net value changed the exact payload type",
        expression.path
      )
      val payloadType = nativeType(endpoint.dataType, endpoint.path)
      val expectedType =
        s"!nodal.resolved<${quoted(mode(endpoint.mode, endpoint.path))}, $payloadType>"
      requireValid(
        operands.head._2 == expectedType,
        "net value operand is not the exact resolved capability",
        expression.path
      )
      if expression.operation == "digital_net_read" then
        requireValid(
          resultType == payloadType,
          "net read changed the exact payload type",
          expression.path
        )
        DigitalValueMlir.Projection(
          Vector(operation(
            "nodal.net_read",
            Vector(result -> resultType),
            operands,
            sourceLocation,
            "metadata" -> metadata(sourceMetadata)
          )),
          result -> resultType
        )
      else
        requireValid(
          resultType ==
            (if expression.operation == "digital_net_enable_view" then "!nodal.bits<1>"
             else payloadType),
          "net split changed the exact projection type",
          expression.path
        )
        val outputs = if expression.operation == "digital_net_write_view" then
          Vector(result -> payloadType, s"${result}_enable" -> "!nodal.bits<1>")
        else Vector(s"${result}_write" -> payloadType, result -> "!nodal.bits<1>")
        DigitalValueMlir.Projection(
          Vector(operation(
            "nodal.net_split",
            outputs,
            operands,
            sourceLocation,
            "metadata" -> metadata(sourceMetadata)
          )),
          result -> resultType
        )

    private def memberType(
        member: KernelInterfaceMemberSnapshot,
        nativeType: (String, String) => String
    ): String =
      val payload = nativeType(member.dataType, member.path)
      member.mode.map(value =>
        s"!nodal.resolved<${quoted(mode(value, member.path))}, $payload>"
      ).getOrElse(payload)

    def renderDefinitions(
        module: KernelModuleSnapshot,
        nativeType: (String, String) => String,
        symbol: (String, String) => String,
        sourceMetadata: String => String,
        sourceLocation: String => String
    ): Vector[String] =
      val result = mutable.ArrayBuffer.empty[String]
      val localDefinitions = snapshot.interfaceContracts.filter(endpoint =>
        endpoint.owner == module.path && endpoint.boundary.isEmpty
      ).flatMap(endpoint =>
        Vector(endpoint.identity) ++
          endpoint.roles.flatMap(_.members.flatMap(_.nestedInterfaces.map(_._2)))
      ).toSet
      definitions.filter(value =>
        value.owner == module.path &&
          localDefinitions(value.identity)
      ).foreach: definition =>
        val selected = definition.roles.head.members
        val roles = definition.roles.map: role =>
          operation(
            "nodal.interface_role",
            Vector.empty,
            Vector.empty,
            sourceLocation(module.path),
            "sym_name" -> quoted(role.name),
            "kind" -> quoted("explicit"),
            "metadata" -> metadata("{}", "access" -> array(role.members.map(permission)))
          )
        val leaves = selected.map: member =>
          operation(
            "nodal.interface_member",
            Vector.empty,
            Vector.empty,
            sourceLocation(module.path),
            "sym_name" -> quoted(member.name),
            "type" -> memberType(member, nativeType),
            "protocol" -> quoted(member.protocol.replace('-', '_')),
            "roles" -> array(definition.roles.map(role => quoted(role.name))),
            "metadata" -> memberMetadata(member)
          )
        val attributes = dictionary(
          "sym_name" -> quoted(symbol("interface", definition.identity)),
          "metadata" -> metadata("{}", "semantic_identity" -> quoted(definition.identity))
        )
        result +=
          s"\"nodal.interface\"() <$attributes> ({\n${(roles ++ leaves).map("  " + _).mkString("\n")}\n}) : () -> () ${sourceLocation(module.path)}"
      snapshot.interfaceContracts.filter(_.owner == module.path).sortBy(_.path).foreach: endpoint =>
        val declaration = declarations(endpoint.path)._2
        val domains = module.domains.filter(value =>
          value.path == endpoint.domain || value.binding.contains(endpoint.domain)
        )
        requireValid(
          domains.size == 1,
          "interface endpoint requires one owned clock/reset domain",
          endpoint.path
        )
        if endpoint.dimensions.nonEmpty then
          val _ = nativeType(s"Vec(Bool;${endpoint.dimensions.mkString("x")})", endpoint.path)
        endpoint.boundary match
          case Some(boundary) =>
            val target = declarations(boundary.endpoint)._2
            result += operation(
              "nodal.interface_boundary",
              Vector.empty,
              Vector.empty,
              sourceLocation(endpoint.path),
              "sym_name" -> quoted(symbol("interface_instance", declaration.name)),
              "instance" -> ("@" + quoted(symbol("instance", boundary.instance.split('.').last))),
              "endpoint" -> quoted(symbol("interface_instance", target.name)),
              "metadata" -> metadata(
                sourceMetadata(endpoint.path),
                "dimensions" -> array(endpoint.dimensions.map(quoted)),
                "actual_types" -> array(endpoint.members.map(member =>
                  dictionary(
                    "path" -> quoted(member.name),
                    "type" -> memberType(actualMember(endpoint, member), nativeType)
                  )
                ))
              )
            )
          case None =>
            val instance = "@" + quoted(symbol("interface_instance", declaration.name))
            result += operation(
              "nodal.interface_instance",
              Vector.empty,
              Vector.empty,
              sourceLocation(endpoint.path),
              "sym_name" -> quoted(symbol("interface_instance", declaration.name)),
              "definition" -> ("@" + quoted(symbol("interface", endpoint.identity))),
              "role" -> quoted(endpoint.role),
              "metadata" -> metadata(
                sourceMetadata(endpoint.path),
                "domain" -> ("@" + quoted(symbol("domain", domains.head.name))),
                "dimensions" -> array(endpoint.dimensions.map(quoted))
              )
            )
            val abi = endpoint.members.map(member =>
              dictionary(
                "path" -> quoted(member.name),
                "type" -> memberType(member, nativeType),
                "role" -> quoted(member.role),
                "access" -> quoted(member.access),
                "nested_roles" -> nestedRoles(member.nestedRoles),
                "nested_interfaces" -> nestedInterfaces(member.nestedInterfaces),
                "analog" -> analog(member)
              )
            )
            result += operation(
              "nodal.interface_abi",
              Vector.empty,
              Vector.empty,
              sourceLocation(endpoint.path),
              "logical_path" -> quoted(endpoint.path),
              "members" -> array(abi),
              "layout_policy" -> quoted("structural"),
              "metadata" -> metadata(sourceMetadata(endpoint.path), "instance" -> instance)
            )
      result.toVector

    def renderEffects(
        module: KernelModuleSnapshot,
        resolve: String => (String, String),
        nativeType: (String, String) => String,
        symbol: (String, String) => String,
        sourceMetadata: String => String,
        sourceLocation: String => String,
        validateCapabilitySource: (String, String, String, String) => Unit,
        exactPayloadType: (String, String, String) => Boolean
    ): Vector[String] =
      val output = mutable.ArrayBuffer.empty[String]
      val accessed = mutable.HashMap.empty[(String, String), (String, String)]
      def member(path: String, role: Option[String]): (String, String) =
        val (endpoint, selected) = selectedMember(path, module.path, role)
        val roleName = role.get
        accessed.getOrElseUpdate(
          path -> roleName, {
            val result = "%interface_member_" + hash(path + "\u0000" + roleName)
            val leafType = memberType(actualMember(endpoint, selected), nativeType)
            val resultType = if endpoint.dimensions.isEmpty then leafType
            else s"!nodal.shaped<${quoted(endpoint.dimensions.mkString(","))}, $leafType>"
            output += operation(
              "nodal.member_access",
              Vector(result -> resultType),
              Vector.empty,
              sourceLocation(path),
              "instance" ->
                ("@" + quoted(symbol("interface_instance", declarations(endpoint.path)._2.name))),
              "path" -> quoted(selected.name),
              "metadata" -> metadata(sourceMetadata(path), "role" -> quoted(roleName))
            )
            result -> resultType
          }
        )
      effects.getOrElse(module.path, Vector.empty).sortBy(_.path).foreach: effect =>
        val info = metadata(sourceMetadata(effect.path))
        effect.kind match
          case "interface-connect" =>
            output += operation(
              "nodal.interface_connect",
              Vector.empty,
              Vector(
                member(effect.target, effect.endpointRole),
                member(effect.source.get, effect.sourceEndpointRole)
              ),
              sourceLocation(effect.path),
              "metadata" -> info
            )
          case "interface-drive" =>
            val target = member(effect.target, effect.endpointRole)
            val value = resolve(effect.value.get)
            requireValid(
              exactPayloadType(
                effect.value.get,
                if members(effect.target)._1.dimensions.isEmpty then effect.dataType.get
                else
                  s"Vec(${effect.dataType.get};${members(effect.target)._1.dimensions.mkString("x")})"
                ,
                effect.path
              ),
              "interface driver value differs from exact member payload",
              effect.path
            )
            validateCapabilitySource(
              effect.value.get,
              module.path,
              members(effect.target)._1.domain,
              value._2
            )
            output += operation(
              "nodal.interface_drive",
              Vector.empty,
              Vector(target, value),
              sourceLocation(effect.path),
              "metadata" -> info
            )
          case "interface-observe" =>
            output += operation(
              "nodal.interface_observe",
              Vector.empty,
              Vector(member(effect.target, effect.endpointRole)),
              sourceLocation(effect.path),
              "metadata" -> info
            )
          case "inout-pass-through" =>
            output += operation(
              "nodal.net_pass_through",
              Vector.empty,
              Vector(resolve(effect.target), resolve(effect.source.get)),
              sourceLocation(effect.path),
              "metadata" -> info
            )
          case _ =>
            val target = resolve(effect.target)
            val payload = nativeType(effect.dataType.get, effect.path)
            val driver = "%net_driver_" + hash(effect.path)
            val driverType = s"!nodal.driver<$payload>"
            output += operation(
              "nodal.net_driver",
              Vector(driver -> driverType),
              Vector(target),
              sourceLocation(effect.path),
              "driver_id" -> quoted(effect.path),
              "metadata" -> info
            )
            effect.kind match
              case "inout-high-z" => output += operation(
                  "nodal.net_release",
                  Vector.empty,
                  Vector(target, driver -> driverType),
                  sourceLocation(effect.path),
                  "metadata" -> info
                )
              case "inout-drive" =>
                val value = resolve(effect.value.get)
                val enable = resolve(effect.enable.get)
                requireValid(
                  exactPayloadType(effect.value.get, effect.dataType.get, effect.path) &&
                    enable._2 == "!nodal.bits<1>",
                  "net driver value/enable lost exact types",
                  effect.path
                )
                output += operation(
                  "nodal.net_drive",
                  Vector.empty,
                  Vector(target, driver -> driverType, value, enable),
                  sourceLocation(effect.path),
                  "mode" -> quoted(mode(nets(effect.target).mode, effect.path)),
                  "metadata" -> info
                )
              case "inout-drive-low" | "inout-drive-high" =>
                val enable = resolve(effect.enable.get)
                requireValid(
                  enable._2 == "!nodal.bits<1>",
                  "active-level driver enable lost Bool type",
                  effect.path
                )
                output += operation(
                  "nodal.net_drive_level",
                  Vector.empty,
                  Vector(target, driver -> driverType, enable),
                  sourceLocation(effect.path),
                  "level" -> quoted(if effect.kind == "inout-drive-low" then "low" else "high"),
                  "metadata" -> info
                )
              case _ => fail("unknown resolved-net effect", effect.path)
      output.toVector
