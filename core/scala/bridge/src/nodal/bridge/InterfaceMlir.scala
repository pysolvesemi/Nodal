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
      roles: Vector[KernelInterfaceRoleSnapshot],
      context: Option[KernelInterfaceDefinitionContextSnapshot]
  )

  final class Plan(
      snapshot: ConstructionSnapshot,
      sameType: (String, String, String) => Boolean,
      instanceType: (String, String, String, KernelInstanceSnapshot, String) => Boolean,
      selectedBoundary: String => Unit,
      validateEffectScope: (KernelDigitalConnectivitySnapshot, Vector[String]) => Unit,
      boundaryQuery: (
          String,
          StructuralParameterBinding.InstanceQueries
      ) => Option[StructuralParameterBinding.Query],
      validateDefinitionContext: (KernelInterfaceDefinitionContextSnapshot, String) => Unit
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

    private lazy val selectedDeclarationInventory = snapshot.modules.flatMap(module =>
      module.declarations.map(value => value.path -> (module, value))
    ).groupMap(_._1)(_._2)
    private lazy val selectedExpressionInventory = snapshot.parameterExpressions.zipWithIndex
      .groupBy(_._1.path)
    private lazy val selectedRuntimeExpressions = snapshot.analogRegions.flatMap(_.expressions)
      .map(_.path).toSet ++ snapshot.digitalExpressions.map(_.path)

    /** Independently check selected child capability from original immutable inventories. */
    private def validateSelectedConservativeChild(endpoint: KernelInterfaceSnapshot): Unit =
      val path = endpoint.path
      val boundary = endpoint.boundary.get
      val selection = boundary.selection.get
      val owner = snapshot.modules.filter(_.path == endpoint.owner) match
        case Vector(owner) => owner
        case _ => fail("selected conservative endpoint requires its unique owning Module", path)
      val instance = boundary.instance.flatMap(value => owner.instances.find(_.path == value))
        .getOrElse(fail("selected conservative endpoint requires its actual child instance", path))
      val child = endpoints.getOrElse(
        boundary.endpoint,
        fail("selected conservative child endpoint is absent", path)
      )
      val family = snapshot.generatedRegions.find(_.path == selection.family)
        .getOrElse(fail("selected conservative child family is absent", path))
      val scope = KernelGeneratedScope(family.path, family.induction)
      requireValid(
        boundary.region.isEmpty && declarations(path)._2.generatedScope.isEmpty &&
          selection.selectionPrefix.isEmpty && selection.ordinal.toLongOption.exists(value =>
            selection.ordinal == value.toString
          ) &&
          family.owner == endpoint.owner && family.parent.isEmpty && family.lexicalParent.isEmpty &&
          family.digitalBody.nonEmpty && family.yieldedArea.nonEmpty && family.maximumTripCount > 0 &&
          instance.generatedScope.contains(scope) &&
          child.owner == instance.childModule && child.boundary.isEmpty &&
          declarations(child.path)._2.kind == "interface-port" && endpoint.dimensions.isEmpty &&
          endpoint.members.nonEmpty && endpoint.members.forall(member =>
            member.protocol == "conservative" && member.access == "connect" && member.mode.isEmpty
          ),
        "selected conservative child requires its exact constant scalar connect profile",
        path
      )
      // Immutable snapshot admission only; canonical parameter transport owns binding semantics.
      val parameters = modules(instance.childModule).declarations.filter(_.kind == "parameter")
      val parameterNames = parameters.iterator.map(_.name).toSet
      val bindingsByName = instance.parameterBindings.groupBy(_._1)
      def literal(value: String): Boolean = value.toDoubleOption.exists(_.isFinite)
      def ordinaryDimensionlessReal(parameter: KernelDeclarationSnapshot): Boolean =
        val attributes = parameter.attributes
        val uniqueKeys = attributes.iterator.map(_._1).toSet.size == attributes.size
        if !uniqueKeys then false
        else
          val values = attributes.toMap
          parameter.kind == "parameter" && parameter.dataType.contains("Real") &&
          values.get("classification").forall(_ == "ordinary") &&
          values.get("unit").forall(_.isEmpty)
      def parentReal(value: String): Boolean =
        val heights = mutable.HashMap.empty[String, Int]
        val active = mutable.HashSet.empty[String]
        var work = 0
        // The original ordered inventory, not a path spelling or substituted default, owns each node.
        def visit(value: String, depth: Int, before: Int): Int =
          work += 1
          requireValid(
            work <= 65536 && depth < 512,
            "selected conservative parent Real expression exceeds its finite work/depth budget",
            path
          )
          val expression =
            (selectedDeclarationInventory.get(value), selectedExpressionInventory.get(value)) match
              case (Some(Vector((module, parameter))), None)
                  if module.path == owner.path && parameter.generatedScope.isEmpty &&
                    ordinaryDimensionlessReal(parameter) => None
              case (None, Some(Vector((expression, ordinal))))
                  if expression.owner == owner.path && expression.region.isEmpty && ordinal < before &&
                    expression.dataType == "Real" && expression.unit.forall(_.isEmpty) &&
                    !selectedRuntimeExpressions(value) => Some(expression -> ordinal)
              case _ =>
                fail(
                  "selected conservative parent Real value lacks its unique owned identity",
                  path
                )
          requireValid(
            !active(value),
            "selected conservative parent Real expression is cyclic",
            path
          )
          heights.get(value) match
            case Some(height) =>
              requireValid(
                depth + height < 512,
                "selected conservative parent Real expression exceeds its finite depth budget",
                path
              )
              height
            case None =>
              active += value
              val height = expression match
                case None => 0
                case Some((expression, _)) if expression.literal.nonEmpty =>
                  requireValid(
                    expression.operation == "real_literal" && expression.operands.isEmpty &&
                      expression.literal.exists(literal),
                    "selected conservative expression requires finite unitless Real literals",
                    path
                  )
                  0
                case Some((expression, ordinal)) =>
                  val arity = expression.operation match
                    case "analog_add" | "analog_sub" | "analog_mul" => 2
                    case "analog_neg" => 1
                    case _ =>
                      fail("selected conservative parent Real operation is unsupported", path)
                  requireValid(
                    expression.operands.size == arity,
                    "selected conservative parent Real operation has invalid arity",
                    path
                  )
                  expression.operands.iterator.map(operand =>
                    visit(operand, depth + 1, ordinal) + 1
                  ).max
              active -= value
              heights(value) = height
              height
        visit(value, 0, Int.MaxValue)
        true
      requireValid(
        parameterNames.size == parameters.size && parameters.forall(parameter =>
          ordinaryDimensionlessReal(parameter) &&
            parameter.attributes.find(_._1 == "default").exists(entry => literal(entry._2))
        ) && instance.parameterBindings.forall((name, value) =>
          // Preserve literal duplicate transport; symbolic bindings must have one representation.
          parameterNames.contains(name) && (literal(value) ||
            (bindingsByName(name).size == 1 && parentReal(value)))
        ),
        "selected conservative child parameters require ordinary dimensionless Real literals " +
          "or parent Real arithmetic, with literal child defaults",
        path
      )
      val indices = Vector(
        family.lower,
        family.upperExclusive,
        family.step
      ).map(shapeQuery.resolve(endpoint.owner, _))
      val graph = shapeQuery.exportValues(indices)
      val bounds = indices.map(shapeQuery.bounds)
      val fixedFamily = graph.nodes.forall(_.parameter.isEmpty) && bounds.forall(value =>
        value.lower == value.upper
      )
      val roots = graph.dimensions.map(graph.nodes(_))
      val symbolicFamily =
        family.lower == "0" && family.step == "1" &&
          roots(0).literal.contains(0L) && roots(2).literal.contains(1L) &&
          roots(1).literal.isEmpty && roots(1).operation.isEmpty && roots(1).operands.isEmpty &&
          roots(1).parameter.contains(family.upperExclusive) &&
          (selectedDeclarationInventory.get(family.upperExclusive) match
            case Some(Vector((parameterOwner, parameter))) if parameterOwner.path == owner.path =>
              val attributes = parameter.attributes
              val values = attributes.toMap
              parameter.kind == "parameter" && parameter.dataType.contains("Integer") &&
              parameter.generatedScope.isEmpty &&
              !snapshot.generatedRegions.exists(_.declarations.contains(parameter.path)) &&
              attributes.size == values.size && values.get(
                "classification"
              ).contains("structural") &&
              values.get("structural_effects").exists(_.split(",").contains("shape")) &&
              values.get("integer_range_lower").flatMap(_.toIntOption).exists(value =>
                value.toLong == bounds(1).lower
              ) && values.get("integer_range_upper").flatMap(_.toIntOption).exists(value =>
                value.toLong == bounds(1).upper
              )
            case _ => false)
      requireValid(
        (fixedFamily || symbolicFamily) &&
          bounds(2).lower > 0 &&
          IterationDomain.structural(bounds(0), bounds(1), bounds(2), family.maximum)
            .toOption.exists(domain =>
              domain.maximumTripCount == family.maximumTripCount &&
                domain.maximumTripCount > 0 && IterationDomain.verifyOrdinal(
                  domain,
                  IterationDomain.Bounds(selection.ordinal.toLong, selection.ordinal.toLong)
                ).isRight
            ),
        "selected conservative child requires a fixed family or direct structural Integer extent and a universal literal ordinal",
        path
      )
      requireValid(
        endpoints.values.exists(body =>
          body.owner == endpoint.owner &&
            family.declarations.contains(body.path) && declarations(
              body.path
            )._2.generatedScope.contains(scope) &&
            body.boundary.exists(original =>
              original.instance.contains(instance.path) &&
                original.endpoint == child.path && original.selection.isEmpty && original.region.contains(
                  family.path
                )
            )
        ),
        "selected conservative child requires its authentic authored body Interface boundary",
        path
      )

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
      val applications = Vector(
        "" -> endpoint.identity
      ) ++ endpoint.roles.flatMap(_.members.flatMap(_.nestedInterfaces)).distinct
      requireValid(
        endpoint.definitionContexts.map(
          _.application
        ).distinct.size == endpoint.definitionContexts.size &&
          endpoint.definitionContexts.size <= 65536,
        "Interface definition contexts require unique bounded application paths",
        endpoint.path
      )
      endpoint.definitionContexts.foreach: context =>
        requireValid(
          applications.contains(context.application -> context.identity),
          "Interface definition context differs from its complete original application",
          endpoint.path
        )
        validateDefinitionContext(context, endpoint.path)
      requireValid(
        module.path == endpoint.owner && modules.contains(endpoint.owner) &&
          Set("interface-port", "interface-array").contains(declaration.kind) &&
          declaration.generatedScope.forall(scope =>
            snapshot.generatedRegions.exists(family =>
              family.path == scope.owner && family.induction == scope.induction &&
                family.owner == endpoint.owner && family.declarations.contains(endpoint.path) &&
                (endpoint.members.forall(member =>
                  !Set("conservative", "signal-flow")(member.protocol)
                ) ||
                  endpoint.dimensions.isEmpty && endpoint.members.nonEmpty &&
                  endpoint.members.forall(_.protocol == "conservative") &&
                  family.parent.isEmpty && family.lexicalParent.isEmpty)
            )
          ) &&
          declaration.interfaceBoundary == endpoint.boundary &&
          attributes.get("interface").contains(endpoint.identity) &&
          attributes.get("role").contains(endpoint.role) &&
          declaration.domain == endpoint.domain &&
          (endpoint.domain.nonEmpty || ExternalModuleContract.externalInterfacePaths(snapshot)(
            endpoint.path
          )) &&
          endpoint.identity.nonEmpty &&
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
        val child = endpoints.getOrElse(
          boundary.endpoint,
          fail("Interface projection lost its actual original endpoint", endpoint.path)
        )
        val childDeclaration = declarations(child.path)._2
        val instance = boundary.instance.map(path =>
          module.instances.find(_.path == path).getOrElse(
            fail("Interface projection lost its exact immediate child instance", endpoint.path)
          )
        )
        requireValid(
          boundary.selection.nonEmpty || instance.nonEmpty,
          "direct Interface boundary requires an actual child instance",
          endpoint.path
        )
        val selectedConservative =
          boundary.selection.nonEmpty && endpoint.members.exists(_.protocol == "conservative")
        if selectedConservative then validateSelectedConservativeChild(endpoint)
        if boundary.selection.nonEmpty then selectedBoundary(endpoint.path)
        else
          requireValid(
            boundary.region == declaration.generatedScope.map(_.owner) &&
              instance.get.generatedScope == declaration.generatedScope,
            "direct Interface boundary must retain its exact occurrence scope",
            endpoint.path
          )
        def canonical(values: Vector[KernelInterfaceRoleSnapshot]) = values.map(role =>
          role.name -> role.copy(members = role.members.map(value => value.copy(path = value.name)))
        ).toMap
        val endpointRoles = canonical(endpoint.roles)
        val childRoles = canonical(child.roles)
        val exactOwner = instance match
          case Some(value) =>
            val binding =
              value.actualBindings.filter(actual => child.domain.contains(actual.requirement))
            child.owner == value.childModule && childDeclaration.generatedScope.isEmpty &&
            (if child.domain.nonEmpty then
               binding.size == 1 && endpoint.domain.contains(binding.head.actual)
             else
               endpoint.domain.isEmpty && value.actualBindings.isEmpty &&
               value.generatedScope.isEmpty && boundary.selection.isEmpty &&
               ExternalModuleContract.externalInterfacePaths(snapshot)(child.path))
          case None => child.owner == module.path && childDeclaration.generatedScope.nonEmpty &&
            child.domain == endpoint.domain
        requireValid(
          exactOwner && child.boundary.isEmpty &&
            childDeclaration.kind == declaration.kind && endpoint.identity == child.identity &&
            endpoint.role == child.role && endpoint.definitionContexts == child.definitionContexts &&
            endpointRoles.get(endpoint.role) == childRoles.get(child.role) &&
            endpointRoles.forall((name, role) => childRoles.get(name).forall(_ == role)) &&
            endpoint.members.forall(value =>
              Set("plain", "valid", "stream")(value.protocol) ||
                selectedConservative && value.protocol == "conservative" ||
                boundary.selection.isEmpty &&
                (value.protocol == "resolved" && instance.forall(_.generatedScope.isEmpty) ||
                  value.protocol == "conservative" && endpoint.dimensions.isEmpty &&
                  instance.forall(instance =>
                    instance.generatedScope.forall(scope =>
                      declaration.generatedScope.contains(scope) && boundary.region.contains(
                        scope.owner
                      )
                    )
                  ))
            ),
          "Interface projection changes exact owner, selected roles, ABI or domain binding",
          endpoint.path
        )
        val childMembers = child.members.map(value => value.name -> value).toMap
        endpoint.members.foreach: member =>
          val formal = childMembers(member.name)
          requireValid(
            if member.protocol == "conservative" then
              actualType(
                endpoint,
                member
              ) == formal.dataType && member.analogAttributes == formal.analogAttributes
            else
              instance match
                case Some(value) => instanceType(
                    actualType(endpoint, member),
                    formal.dataType,
                    member.path,
                    value,
                    formal.path
                  )
                case None => sameType(actualType(endpoint, member), formal.dataType, member.path),
            "Interface actual payload differs from its exact finalized original type",
            member.path
          )
        if declaration.kind == "interface-array" then
          try
            instance match
              case Some(value) =>
                val query = boundaryQuery(
                  endpoint.path,
                  instanceQueries
                ).getOrElse(instanceQueries.query(module.path, value.path))
                requireValid(
                  endpoint.dimensions.size == child.dimensions.size &&
                    endpoint.dimensions.zip(child.dimensions).forall((actual, formal) =>
                      val original = shapeQuery.resolve(module.path, actual)
                      val imported = query.importValue(
                        module.path,
                        shapeQuery.exportDimensions(module.path, Vector(original))
                      )
                      imported == query.resolve(child.owner, formal)
                    ),
                  "Interface array dimensions differ from the exact occurrence",
                  endpoint.path
                )
              case None => requireValid(
                  endpoint.dimensions.size == child.dimensions.size &&
                    sameType(
                      s"Vec(Bool;${endpoint.dimensions.mkString("x")})",
                      s"Vec(Bool;${child.dimensions.mkString("x")})",
                      endpoint.path
                    ),
                  "selected direct Interface array changed its dimensions",
                  endpoint.path
                )
          catch case error: IllegalArgumentException => fail(error.getMessage, endpoint.path)
      endpoint.roles.foreach(validateRole(endpoint, _))
      requireValid(
        endpoint.roles.find(_.name == endpoint.role).exists(_.members == endpoint.members),
        "selected endpoint role differs from its complete map",
        endpoint.path
      )

    snapshot.resolvedNets.foreach: net =>
      val (module, declaration) =
        declarations.getOrElse(net.path, fail("resolved net has no declaration", net.path))
      val fields = declaration.attributes.toMap
      requireValid(
        declaration.kind == "digital-inout" && declaration.generatedScope.isEmpty &&
          declaration.resolvedBoundary == net.boundary &&
          declaration.resolvedMember == net.member &&
          declaration.interfaceBoundary.isEmpty && declaration.dataType.contains(net.dataType) &&
          fields.get("mode").contains(net.mode) &&
          (if net.member.nonEmpty then
             net.boundary.isEmpty && net.placement.isEmpty && net.profile.isEmpty &&
             !fields.contains("placement") && !fields.contains("profile")
           else
             fields.get("placement").contains(net.placement) &&
             fields.get("profile").contains(net.profile)),
        "resolved-net declaration and typed contract disagree",
        net.path
      )
      net.boundary.foreach: boundary =>
        val instance =
          module.instances.find(value => boundary.instance.contains(value.path)).getOrElse(
            fail("resolved projection lost its exact immediate child instance", net.path)
          )
        val child = nets.getOrElse(
          boundary.endpoint,
          fail("resolved projection lost its actual child net", net.path)
        )
        val (childModule, childDeclaration) = declarations(child.path)
        requireValid(
          childModule.path == instance.childModule && child.boundary.isEmpty &&
            child.member.isEmpty &&
            childDeclaration.kind == "digital-inout" && childDeclaration.generatedScope.isEmpty &&
            instance.generatedScope.isEmpty && declaration.domain.isEmpty &&
            net.mode == child.mode && net.placement == child.placement &&
            net.profile == child.profile &&
            instanceType(net.dataType, child.dataType, net.path, instance, child.path),
          "resolved projection changes exact child, mode, profile, placement or finalized element type",
          net.path
        )
      val _ = mode(net.mode, net.path)
      net.member match
        case Some(origin) =>
          val (endpoint, member) =
            selectedMember(s"${origin.endpoint}.${origin.member}", module.path, Some(origin.role))
          requireValid(
            endpoint.path == origin.endpoint &&
              declarations(endpoint.path)._2.generatedScope.isEmpty &&
              endpoint.boundary.forall(_.selection.isEmpty) &&
              member.name == origin.member && member.protocol == "resolved" &&
              member.mode.contains(net.mode) &&
              sameType(
                net.dataType,
                wholeType(endpoint.dimensions, actualType(endpoint, member)),
                net.path
              ),
            "resolved member view changes its exact endpoint, selected role, path, mode or contextual type",
            net.path
          )
        case None =>
          val selectedPlacement = placement(net.placement, net.path)
          val selectedProfile = profile(net.profile, net.path)
          requireValid(
            selectedPlacement != "internal_resolved_net" ||
              selectedProfile != "portable_boundary_only",
            "internal net exceeds portable-boundary capabilities",
            net.path
          )

    private val projectedNets = snapshot.resolvedNets.flatMap(net =>
      net.boundary.map(boundary => (boundary.instance, boundary.endpoint))
    )
    requireValid(
      projectedNets.distinct.size == projectedNets.size,
      "repeated resolved projection must reuse its existing physical identity",
      snapshot.root
    )

    private val definitions: Vector[Definition] =
      val entries =
        mutable.LinkedHashMap.empty[(String, String, Option[String]), mutable.LinkedHashMap[
          String,
          Vector[KernelInterfaceMemberSnapshot]
        ]]
      val contexts = mutable.HashMap.empty[
        (String, String, Option[String]),
        Option[KernelInterfaceDefinitionContextSnapshot]
      ]
      var count = 0L
      def add(
          owner: String,
          identity: String,
          role: String,
          values: Vector[KernelInterfaceMemberSnapshot],
          context: Option[KernelInterfaceDefinitionContextSnapshot]
      ): Unit =
        val normalized = values
        val key = (owner, identity, context.map(_.region))
        val actual = context.map(_.copy(application = ""))
        contexts.get(key).foreach(previous =>
          requireValid(
            previous == actual,
            "Interface name is not identity for separately constructed lexical definitions",
            identity
          )
        )
        contexts.update(key, actual)
        val known = entries.getOrElseUpdate(key, mutable.LinkedHashMap.empty)
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
      snapshot.interfaceContracts.filterNot(_.boundary.exists(_.selection.nonEmpty))
        .sortBy(_.path).foreach: endpoint =>
          endpoint.roles.foreach: role =>
            val actualMembers = role.members.map(actualMember(endpoint, _))
            add(
              endpoint.owner,
              endpoint.identity,
              role.name,
              actualMembers,
              endpoint.definitionContexts.find(_.application.isEmpty)
            )
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
                  nestedRoles =
                    member.nestedRoles.filter(_._1.startsWith(strip)).map((path, name) =>
                      path.stripPrefix(strip) -> name
                    ),
                  nestedInterfaces = member.nestedInterfaces.filter(_._1.startsWith(strip)).map(
                    (path, name) => path.stripPrefix(strip) -> name
                  )
                )
              add(
                endpoint.owner,
                identity,
                names.head,
                projected,
                endpoint.definitionContexts.find(_.application == prefix)
              )
      entries.toVector.sortBy(_._1).map:
        case (key @ (owner, identity, _), roles) => Definition(
            owner,
            identity,
            roles.toVector.sortBy(_._1).map((name, values) =>
              KernelInterfaceRoleSnapshot(name, values)
            ),
            contexts(key)
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

    private def netPermission(value: KernelResolvedNetSnapshot): Option[String] = value.member.map:
      origin =>
        selectedMember(
          s"${origin.endpoint}.${origin.member}",
          declarations(value.path)._1.path,
          Some(origin.role)
        )._2.access
    private def requireNetAccess(
        value: KernelResolvedNetSnapshot,
        access: String,
        path: String
    ): Unit =
      requireValid(
        netPermission(value).forall(_ == access),
        "resolved member role does not permit " + access,
        path
      )
    private def netProfile(value: KernelResolvedNetSnapshot): String =
      if value.member.nonEmpty then "PortableBoundaryOnly" else value.profile

    snapshot.digitalConnectivity.foreach: effect =>
      requireValid(
        modules.contains(effect.owner) && effect.path.startsWith(effect.owner + ".") &&
          DigitalInterfaceContract.EffectOperations.contains(effect.kind),
        "connectivity effect has no exact kind or owner",
        effect.path
      )
      if effect.kind.startsWith("interface-") then
        val (endpoint, target) = selectedMember(effect.target, effect.owner, effect.endpointRole)
        val sourceEndpoint = effect.source.toVector.map(path =>
          selectedMember(path, effect.owner, effect.sourceEndpointRole)._1.path
        )
        validateEffectScope(effect, Vector(endpoint.path) ++ sourceEndpoint)
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
            val sink = ConstructionInterfaceLayout.connectionSink(
              target.access,
              endpoint.boundary.exists(_.instance.nonEmpty),
              source.access,
              other.boundary.exists(_.instance.nonEmpty)
            )
            requireValid(
              !sink.exists(left =>
                (if left then endpoint else other).boundary
                  .exists(_.selection.nonEmpty)
              ),
              "selected Interface writes require exact selection-qualified driver proof",
              effect.path
            )
            requireValid(
              endpoint.identity == other.identity && endpoint.definitionContexts == other.definitionContexts &&
                (endpoint.domain.isEmpty || other.domain.isEmpty || endpoint.domain == other.domain) &&
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
                  endpoint.boundary.exists(_.instance.nonEmpty),
                  source.access,
                  other.boundary.exists(_.instance.nonEmpty)
                ).nonEmpty ||
                  (target.protocol == "resolved" && target.access == "connect" &&
                    source.access == "connect")),
              "exact connection changes identity, shape, domain, protocol or complementary access",
              effect.path
            )
          case "interface-drive" =>
            requireValid(
              !endpoint.boundary.exists(_.selection.nonEmpty),
              "selected Interface writes require exact selection-qualified driver proof",
              effect.path
            )
            requireValid(
              effect.source.isEmpty && effect.value.nonEmpty &&
                (if target.protocol == "resolved" then target.access == "drive"
                 else if endpoint.boundary.exists(_.instance.nonEmpty) then target.access == "in"
                 else target.access == "out") &&
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
        requireValid(
          effect.region.isEmpty && effect.sourceOrdinal.isEmpty,
          "resolved connectivity cannot acquire generated or selected effect authority",
          effect.path
        )
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
          case "inout-drive" =>
            requireNetAccess(target, "drive", effect.path)
            requireValid(
              Set("push-pull", "open-source").contains(target.mode) && effect.value.nonEmpty &&
                effect.enable.nonEmpty && effect.source.isEmpty,
              "conditional drive violates exact mode/operands",
              effect.path
            )
          case "inout-drive-low" | "inout-drive-high" =>
            requireNetAccess(target, "drive", effect.path)
            val expected = if effect.kind == "inout-drive-low" then "open-drain" else "open-source"
            requireValid(
              target.mode == expected && effect.value.isEmpty && effect.enable.nonEmpty &&
                effect.source.isEmpty,
              "active-level drive violates exact mode/operands",
              effect.path
            )
          case "inout-high-z" =>
            requireNetAccess(target, "drive", effect.path)
            requireValid(
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
            requireNetAccess(target, "connect", effect.path)
            requireNetAccess(source, "connect", effect.path)
            requireValid(
              source.path != target.path && source.dataType == target.dataType &&
                source.mode == target.mode && netProfile(source) == netProfile(target) &&
                effect.value.isEmpty && effect.enable.isEmpty,
              "pass-through changes resolved-net contract",
              effect.path
            )
          case _ => fail("unknown net effect", effect.path)

    // This finite equivalence relation models authored net connections only. It is not a
    // second expression traversal or a simulation of resolved values.
    private val resolvedMemberPaths = members.iterator.collect:
      case (path, (_, member)) if member.protocol == "resolved" => path
    .toSet
    private val netComponents =
      mutable.HashMap.from((nets.keySet ++ resolvedMemberPaths).map(path => path -> path))
    private def component(path: String): String =
      var current = path
      var steps = 0
      while netComponents(current) != current do
        current = netComponents(current)
        steps += 1
        requireValid(steps <= netComponents.size, "resolved-net equivalence cycle", path)
      val root = current
      current = path
      while netComponents(current) != current do
        val next = netComponents(current)
        netComponents.update(current, root)
        current = next
      root
    requireValid(
      nets.size <= 65536 && snapshot.digitalConnectivity.size <= 262144,
      "digital connectivity exceeds the finite structural budget",
      snapshot.root
    )
    private def aliasNet(a: String, b: String): Unit =
      val left = component(a)
      val right = component(b)
      if left != right then netComponents.update(left, right)
    snapshot.resolvedNets.foreach(net =>
      net.member.foreach(origin =>
        aliasNet(net.path, s"${origin.endpoint}.${origin.member}")
      )
    )
    snapshot.digitalConnectivity.filter(effect =>
      effect.kind == "inout-pass-through" ||
        effect.kind == "interface-connect" && resolvedMemberPaths(effect.target)
    ).foreach: effect =>
      aliasNet(effect.target, effect.source.get)
    private val componentDomains = mutable.HashMap.empty[String, String]
    snapshot.interfaceContracts.foreach: endpoint =>
      endpoint.domain.foreach: domain =>
        endpoint.members.filter(_.protocol == "resolved").foreach: member =>
          val key = component(member.path)
          componentDomains.get(key).foreach(previous =>
            requireValid(
              previous == domain,
              "resolved pass-through component crosses exact Interface domain ownership",
              member.path
            )
          )
          componentDomains.update(key, domain)
    private val localComponents =
      netComponents.keysIterator.map(path => path -> component(path)).toMap
    private val driverOperations = snapshot.digitalConnectivity.filter(effect =>
      Set("inout-drive", "inout-drive-low", "inout-drive-high").contains(effect.kind) ||
        effect.kind == "interface-drive" && resolvedMemberPaths(effect.target)
    )
    private val localDrivers = driverOperations.groupBy(effect => component(effect.target))
    private val boundaryNets = snapshot.resolvedNets.flatMap(net =>
      net.boundary.toVector.map(boundary => net.path -> boundary.endpoint)
    ) ++ snapshot.interfaceContracts.flatMap(endpoint =>
      endpoint.boundary.toVector.flatMap(boundary =>
        endpoint.members.filter(_.mode.nonEmpty).map(member =>
          member.path -> s"${boundary.endpoint}.${member.name}"
        )
      )
    )
    boundaryNets.foreach((parent, child) => aliasNet(parent, child))
    private val opaqueExternalComponents =
      ExternalModuleContract.externalNetPaths(snapshot).map(component)
    private val boundaryNetComponents = boundaryNets.map((parent, _) => component(parent)).toSet
    private val drivers = driverOperations.groupBy(effect => component(effect.target))
    snapshot.digitalExpressions.filter(_.hierarchyDriver).foreach(expression =>
      requireValid(
        Set("digital_net_write_view", "digital_net_enable_view").contains(expression.operation),
        "hierarchy-driver intent requires an actual resolved split expression",
        expression.path
      )
    )
    snapshot.digitalConnectivity.flatMap: effect =>
      effect.kind match
        case "interface-drive" if members(effect.target)._2.protocol != "resolved" =>
          Vector(effect.target)
        case "interface-connect" if members(effect.target)._2.protocol != "resolved" =>
          val target = members(effect.target)._1
          val source = members(effect.source.get)._1
          val leftSink = ConstructionInterfaceLayout.connectionSink(
            effect.access.get,
            target.boundary.exists(_.instance.nonEmpty),
            effect.sourceAccess.get,
            source.boundary.exists(_.instance.nonEmpty)
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
        !opaqueExternalComponents(component(endpoint.path)),
        "split cannot infer a unique driver across an opaque external contribution",
        expression.path
      )
      requireValid(
        drivers.getOrElse(component(endpoint.path), Vector.empty).size == 1,
        "split projection requires exactly one actual conditional value driver",
        expression.path
      )
      requireValid(
        drivers(component(endpoint.path)).head.kind != "interface-drive",
        "split cannot invent an enable for an unconditional Interface drive",
        expression.path
      )
      val local = localDrivers.getOrElse(localComponents(endpoint.path), Vector.empty)
      requireValid(
        expression.hierarchyDriver == local.isEmpty &&
          (!expression.hierarchyDriver || boundaryNetComponents(component(endpoint.path))),
        "split hierarchy-driver intent differs from its exact local and boundary component proof",
        expression.path
      )

    def roots(module: KernelModuleSnapshot): Vector[String] =
      (module.declarations.filter(_.kind == "digital-inout").map(_.path) ++
        effects.getOrElse(module.path, Vector.empty).filter(_.sourceOrdinal.isEmpty).flatMap(
          value =>
            value.value.toVector ++ value.enable.toVector ++
              (if value.kind.startsWith("inout-") then Vector(value.target) ++ value.source.toVector
               else Vector.empty)
        )).distinct.sorted

    private def wholeType(dimensions: Vector[String], element: String): String =
      if dimensions.isEmpty then element else s"Vec($element;${dimensions.mkString("x")})"

    private def netGeometry(value: KernelResolvedNetSnapshot): (Vector[String], String) =
      value.member match
        case Some(origin) =>
          val (endpoint, member) = selectedMember(
            s"${origin.endpoint}.${origin.member}",
            declarations(value.path)._1.path,
            Some(origin.role)
          )
          endpoint.dimensions -> actualType(endpoint, member)
        case None => Vector.empty -> value.dataType

    private def netCarrierType(
        value: KernelResolvedNetSnapshot,
        driver: Boolean,
        nativeType: (String, String) => String
    ): String =
      val (dimensions, element) = netGeometry(value)
      val payload = nativeType(element, value.path)
      val leaf = if driver then s"!nodal.driver<$payload>"
      else s"!nodal.resolved<${quoted(mode(value.mode, value.path))}, $payload>"
      if dimensions.isEmpty then leaf
      else s"!nodal.shaped<${quoted(dimensions.mkString(","))}, $leaf>"

    private def netEnableType(value: KernelResolvedNetSnapshot): String =
      wholeType(netGeometry(value)._1, "Bool")

    def declarationType(
        declaration: KernelDeclarationSnapshot,
        payloadNativeType: String,
        nativeType: (String, String) => String
    ): String =
      if declaration.kind != "digital-inout" then payloadNativeType
      else
        val selected = nets.getOrElse(
          declaration.path,
          fail("net declaration lost its typed contract", declaration.path)
        )
        requireValid(
          payloadNativeType == nativeType(selected.dataType, selected.path),
          "resolved capability payload changed its exact geometry",
          selected.path
        )
        netCarrierType(selected, driver = false, nativeType)

    def renderDeclaration(
        declaration: KernelDeclarationSnapshot,
        result: String,
        resultType: String,
        sourceMetadata: String,
        sourceLocation: String,
        symbol: (String, String) => String
    ): Vector[String] =
      val selected = nets.getOrElse(
        declaration.path,
        fail("net declaration lost its typed contract", declaration.path)
      )
      if selected.member.nonEmpty then
        val origin = selected.member.get
        return Vector(operation(
          "nodal.member_access",
          Vector(result -> resultType),
          Vector.empty,
          sourceLocation,
          "instance" ->
            ("@" + quoted(symbol("interface_instance", declarations(origin.endpoint)._2.name))),
          "path" -> quoted(origin.member),
          "metadata" -> metadata(sourceMetadata, "role" -> quoted(origin.role))
        ))
      val identity = selected.boundary match
        case Some(boundary) => Vector(
            "instance" -> ("@" + quoted(symbol(
              "instance",
              boundary.instance.getOrElse(fail(
                "resolved boundary requires an instance",
                boundary.endpoint
              )).split('.').last
            ))),
            "endpoint" -> quoted(declarations(boundary.endpoint)._2.name)
          )
        case None => Vector("name" -> quoted(declaration.name))
      Vector(operation(
        if selected.boundary.nonEmpty then "nodal.net_boundary" else "nodal.resolved_net",
        Vector(result -> resultType),
        Vector.empty,
        sourceLocation,
        (identity :+
          ("metadata" -> metadata(
            sourceMetadata,
            "placement" -> quoted(placement(selected.placement, selected.path)),
            "profile" -> quoted(profile(selected.profile, selected.path))
          )))*
      ))

    def renderValue(
        expression: KernelDigitalExpressionSnapshot,
        result: String,
        resultType: String,
        operands: Vector[(String, String)],
        nativeType: (String, String) => String,
        symbol: (String, String) => String,
        sourceMetadata: String,
        sourceLocation: String
    ): DigitalValueMlir.Projection =
      if expression.operation == DigitalInterfaceContract.OrdinaryReadOperation then
        val origin = expression.interfaceMember.getOrElse(fail(
          "ordinary read has no exact member origin",
          expression.path
        ))
        requireValid(
          expression.operands.isEmpty && operands.isEmpty && expression.literal.isEmpty &&
            expression.enumIdentity.isEmpty && expression.caseIds.isEmpty && !expression.hierarchyDriver,
          "ordinary read changed its closed expression contract",
          expression.path
        )
        val (endpoint, selected) = selectedMember(
          s"${origin.endpoint}.${origin.member}",
          expression.owner,
          Some(origin.role)
        )
        requireValid(
          endpoint.path == origin.endpoint && selected.name == origin.member &&
            selected.protocol == "plain" && selected.mode.isEmpty && selected.analogAttributes.isEmpty &&
            Set("in", "out", "observe")(selected.access),
          "ordinary read requires its exact visible digital Value member",
          expression.path
        )
        val expected = wholeType(endpoint.dimensions, actualType(endpoint, selected))
        requireValid(
          sameType(expression.dataType, expected, expression.path),
          "ordinary read changed its exact contextual payload type",
          expression.path
        )
        val leafType = memberType(actualMember(endpoint, selected), nativeType)
        val expectedNative = if endpoint.dimensions.isEmpty then leafType
        else s"!nodal.shaped<${quoted(endpoint.dimensions.mkString(","))}, $leafType>"
        requireValid(
          resultType == expectedNative,
          "ordinary read changed its exact native payload type",
          expression.path
        )
        return DigitalValueMlir.Projection(
          Vector(operation(
            "nodal.member_access",
            Vector(result -> resultType),
            Vector.empty,
            sourceLocation,
            "instance" -> ("@" + quoted(symbol(
              "interface_instance",
              declarations(endpoint.path)._2.name
            ))),
            "path" -> quoted(selected.name),
            "metadata" -> metadata(sourceMetadata, "role" -> quoted(origin.role))
          )),
          result -> resultType
        )
      requireValid(
        expression.interfaceMember.isEmpty,
        "non-member expression carries ordinary member authority",
        expression.path
      )
      requireValid(
        DigitalInterfaceContract.ValueOperations.contains(expression.operation) &&
          expression.operands.size == 1 && operands.size == 1 && expression.literal.isEmpty &&
          expression.enumIdentity.isEmpty && expression.caseIds.isEmpty,
        "invalid typed net value operation",
        expression.path
      )
      val endpoint = net(expression.operands.head, expression.owner)
      requireValid(
        netPermission(endpoint).forall(_ != "drive"),
        "drive-only resolved member cannot be read or split",
        expression.path
      )
      requireValid(
        expression.dataType ==
          (if expression.operation == "digital_net_enable_view" then netEnableType(endpoint)
           else endpoint.dataType),
        "net value changed the exact payload type",
        expression.path
      )
      val payloadType = nativeType(endpoint.dataType, endpoint.path)
      val expectedType = netCarrierType(endpoint, driver = false, nativeType)
      val enableType = nativeType(netEnableType(endpoint), endpoint.path)
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
            (if expression.operation == "digital_net_enable_view" then enableType
             else payloadType),
          "net split changed the exact projection type",
          expression.path
        )
        val outputs = if expression.operation == "digital_net_write_view" then
          Vector(result -> payloadType, s"${result}_enable" -> enableType)
        else Vector(s"${result}_write" -> payloadType, result -> enableType)
        DigitalValueMlir.Projection(
          Vector(operation(
            "nodal.net_split",
            outputs,
            operands,
            sourceLocation,
            (Vector("metadata" -> metadata(sourceMetadata)) ++
              Option.when(expression.hierarchyDriver)("hierarchy_driver" -> "unit"))*
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
        value.owner == module.path && value.context.isEmpty &&
          localDefinitions(value.identity)
      ).foreach: definition =>
        result ++= renderDefinition(definition, module.path, nativeType, symbol, sourceLocation)
      snapshot.interfaceContracts.filter(endpoint =>
        endpoint.owner == module.path &&
          declarations(endpoint.path)._2.generatedScope.isEmpty &&
          !endpoint.boundary.exists(value => value.selection.nonEmpty || value.region.nonEmpty)
      )
        .sortBy(_.path).foreach: endpoint =>
          result ++= renderEndpoint(
            endpoint.path,
            Vector.empty,
            nativeType,
            symbol,
            sourceMetadata,
            sourceLocation
          )
      result.toVector

    private def renderDefinition(
        definition: Definition,
        owner: String,
        nativeType: (String, String) => String,
        symbol: (String, String) => String,
        sourceLocation: String => String
    ): Vector[String] =
      val result = mutable.ArrayBuffer.empty[String]
      val selected = definition.roles.head.members
      val roles = definition.roles.map: role =>
        operation(
          "nodal.interface_role",
          Vector.empty,
          Vector.empty,
          sourceLocation(owner),
          "sym_name" -> quoted(role.name),
          "kind" -> quoted("explicit"),
          "metadata" -> metadata("{}", "access" -> array(role.members.map(permission)))
        )
      val leaves = selected.map: member =>
        operation(
          "nodal.interface_member",
          Vector.empty,
          Vector.empty,
          sourceLocation(owner),
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
        s"\"nodal.interface\"() <$attributes> ({\n${(roles ++ leaves).map("  " + _).mkString("\n")}\n}) : () -> () ${sourceLocation(owner)}"
      result.toVector

    def regionalDefinitions(owner: String)
        : Vector[(KernelInterfaceDefinitionContextSnapshot, Vector[(String, String)])] =
      definitions.filter(value => value.owner == owner && value.context.nonEmpty).map(value =>
        value.context.get -> value.roles.head.members.map(member => member.dataType -> member.path)
      )

    def renderRegionalDefinition(
        context: KernelInterfaceDefinitionContextSnapshot,
        nativeType: (String, String) => String,
        symbol: (String, String) => String,
        sourceLocation: String => String
    ): Vector[String] =
      val found = definitions.filter(_.context.contains(context))
      requireValid(
        found.size == 1,
        "regional Interface definition lost exact construction identity",
        context.identity
      )
      renderDefinition(found.head, context.owner, nativeType, symbol, sourceLocation)

    /** Original members are scheduled with their definition; boundary actuals belong to the use. */
    def actualTypeRoots(path: String): Vector[(String, String)] =
      val endpoint = endpoints(path)
      endpoint.actualMemberTypes.map((name, dataType) => dataType -> s"$path.$name") ++
        Option.when(
          endpoint.dimensions.nonEmpty
        )(s"Vec(Bool;${endpoint.dimensions.mkString("x")})" -> path)

    def endpointSymbol(path: String, symbol: (String, String) => String): String =
      symbol("interface_instance", declarations(path)._2.name)

    def renderEndpoint(
        path: String,
        selection: Vector[(String, String)],
        nativeType: (String, String) => String,
        symbol: (String, String) => String,
        sourceMetadata: String => String,
        sourceLocation: String => String
    ): Vector[String] =
      val endpoint = endpoints(path)
      val module = modules(endpoint.owner)
      val result = mutable.ArrayBuffer.empty[String]
      locally:
        val declaration = declarations(endpoint.path)._2
        val domains = module.domains.filter(value =>
          endpoint.domain.contains(value.path) || value.binding.exists(endpoint.domain.contains)
        )
        requireValid(
          if endpoint.domain.nonEmpty then domains.size == 1
          else
            domains.isEmpty && ExternalModuleContract.externalInterfacePaths(snapshot)(
              endpoint.path
            )
          ,
          "interface endpoint requires an owned domain or exact external signature",
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
              selection,
              sourceLocation(endpoint.path),
              (Vector(
                "sym_name" -> quoted(symbol("interface_instance", declaration.name)),
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
              ) ++ boundary.instance.toVector.map(instance =>
                "instance" -> ("@" + quoted(symbol("instance", instance.split('.').last)))
              ) ++
                boundary.selection.toVector.map(value => "member" -> quoted(value.member)))*
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
                (domains.map(domain => "domain" -> ("@" + quoted(symbol("domain", domain.name)))) ++
                  Vector("dimensions" -> array(endpoint.dimensions.map(quoted))) ++
                  (if endpoint.domain.nonEmpty then Vector.empty
                   else
                     Vector(
                       "placement" -> quoted("black_box_pin"),
                       "profile" -> quoted("portable_boundary_only")
                     )))*
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

    /** Render an exact conservative view as a member access, never a terminal declaration. */
    def renderConservativeMember(
        declaration: KernelDeclarationSnapshot,
        result: String,
        resultType: String,
        nativeType: (String, String) => String,
        symbol: (String, String) => String,
        sourceMetadata: String => String,
        sourceLocation: String => String
    ): String =
      val origin = declaration.conservativeMember.getOrElse(
        fail("conservative view lost its exact origin", declaration.path)
      )
      val endpoint = endpoints.getOrElse(
        origin.endpoint,
        fail("conservative view requires an actual Interface", declaration.path)
      )
      val owner = declarations(declaration.path)._1.path
      val selected = endpoint.roles.find(_.name == origin.role).toVector.flatMap(_.members)
        .find(_.name == origin.member).getOrElse(
          fail("conservative view lost its complete selected-role leaf", declaration.path)
        )
      val fields = declaration.attributes.toMap
      requireValid(
        owner == endpoint.owner && endpoint.dimensions.isEmpty &&
          (origin.role == endpoint.role || (origin.role == "monitor" && endpoint.boundary.isEmpty)) &&
          declaration.kind == "conservative-terminal" &&
          declaration.generatedScope == declarations(endpoint.path)._2.generatedScope &&
          declaration.generatedScope.forall(scope =>
            snapshot.generatedRegions.exists(family =>
              family.owner == owner && family.path == scope.owner &&
                family.induction == scope.induction && family.yieldedArea.nonEmpty && family.parent.isEmpty &&
                family.lexicalParent.isEmpty && family.declarations.contains(declaration.path) &&
                family.declarations.contains(endpoint.path)
            ) &&
              endpoint.boundary.forall(boundary =>
                boundary.selection.isEmpty &&
                  boundary.region.contains(scope.owner) && boundary.instance.exists(path =>
                    modules(owner).instances.exists(instance =>
                      instance.path == path &&
                        instance.generatedScope.contains(scope)
                    )
                  )
              )
          ) &&
          declaration.resolvedMember.isEmpty && declaration.interfaceBoundary.isEmpty &&
          selected.protocol == "conservative" &&
          selected.analogAttributes.forall((key, value) => fields.get(key).contains(value)) &&
          resultType == memberType(actualMember(endpoint, selected), nativeType),
        "conservative member origin, type or discipline differs from its actual declaration",
        declaration.path
      )
      operation(
        "nodal.member_access",
        Vector(result -> resultType),
        Vector.empty,
        sourceLocation(declaration.path),
        "instance" -> ("@" + quoted(symbol(
          "interface_instance",
          declarations(endpoint.path)._2.name
        ))),
        "path" -> quoted(origin.member),
        "metadata" -> metadata(sourceMetadata(declaration.path), "role" -> quoted(origin.role))
      )

    def renderMemberAccess(
        module: KernelModuleSnapshot,
        path: String,
        role: String,
        scheduledOwner: Option[String],
        nativeType: (String, String) => String,
        symbol: (String, String) => String,
        sourceMetadata: String => String,
        sourceLocation: String => String
    ): DigitalValueMlir.Projection =
      val (endpoint, selected) = selectedMember(path, module.path, Some(role))
      val result =
        "%interface_member_" + hash(path + "\u0000" + role + scheduledOwner.getOrElse(""))
      val leafType = memberType(actualMember(endpoint, selected), nativeType)
      val resultType = if endpoint.dimensions.isEmpty then leafType
      else s"!nodal.shaped<${quoted(endpoint.dimensions.mkString(","))}, $leafType>"
      val identity = scheduledOwner.map(owner => s"$owner.access.$path.$role").getOrElse(path)
      DigitalValueMlir.Projection(
        Vector(operation(
          "nodal.member_access",
          Vector(result -> resultType),
          Vector.empty,
          sourceLocation(path),
          "instance" -> ("@" + quoted(symbol(
            "interface_instance",
            declarations(endpoint.path)._2.name
          ))),
          "path" -> quoted(selected.name),
          "metadata" -> metadata(sourceMetadata(identity), "role" -> quoted(role))
        )),
        result -> resultType
      )

    def renderEffects(
        module: KernelModuleSnapshot,
        resolve: String => (String, String),
        nativeType: (String, String) => String,
        symbol: (String, String) => String,
        sourceMetadata: String => String,
        sourceLocation: String => String,
        validateCapabilitySource: (String, String, String, String) => Unit,
        exactPayloadType: (String, String, String, String) => Boolean,
        selectedEffects: Option[Vector[KernelDigitalConnectivitySnapshot]] = None,
        capturedMember: (String, String) => Option[(String, String)] = (_, _) => None
    ): Vector[String] =
      val output = mutable.ArrayBuffer.empty[String]
      val accessed = mutable.HashMap.empty[(String, String), (String, String)]
      def member(path: String, role: Option[String]): (String, String) =
        val roleName = role.get
        accessed.getOrElseUpdate(
          path -> roleName, {
            val rendered = renderMemberAccess(
              module,
              path,
              roleName,
              selectedEffects.map(_.head.path),
              nativeType,
              symbol,
              sourceMetadata,
              sourceLocation
            )
            capturedMember(path, roleName) match
              case Some(value) =>
                requireValid(
                  value._2 == rendered.value._2,
                  "captured Interface member changed its exact native payload",
                  path
                )
                value
              case None =>
                output ++= rendered.lines
                rendered.value
          }
        )
      selectedEffects.getOrElse(effects.getOrElse(module.path, Vector.empty)
        .filter(_.sourceOrdinal.isEmpty)).sortBy(_.path).foreach: effect =>
        val info = metadata(
          sourceMetadata(effect.path),
          effect.sourceOrdinal.toVector.map(ordinal => "source_ordinal" -> s"$ordinal : i64")*
        )
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
                effect.path,
                effect.target
              ),
              "interface driver value differs from exact member payload",
              effect.path
            )
            validateCapabilitySource(
              effect.value.get,
              module.path,
              members(effect.target)._1.domain.getOrElse(
                fail("ordinary Interface driver requires its actual domain", effect.path)
              ),
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
            val endpoint = nets(effect.target)
            val enableType = nativeType(netEnableType(endpoint), effect.path)
            val driver = "%net_driver_" + hash(effect.path)
            val driverType = netCarrierType(endpoint, driver = true, nativeType)
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
                  exactPayloadType(
                    effect.value.get,
                    effect.dataType.get,
                    effect.path,
                    effect.target
                  ) &&
                    enable._2 == enableType,
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
                  enable._2 == enableType,
                  "active-level driver enable lost its exact pointwise Bool type",
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
