package nodal

/** One typed role expansion shared by executable connectivity and the public ABI projection. */
private[nodal] object ConstructionInterfaceLayout:
  final case class Leaf(
      name: String,
      role: String,
      access: String,
      dataType: Option[DataType[?]],
      analogType: Option[String],
      protocol: String,
      mode: Option[String],
      nestedRoles: Vector[(String, String)],
      nestedInterfaces: Vector[(String, String)],
      analogAttributes: Vector[(String, String)]
  ):
    def rendered(renderType: DataType[?] => String): String =
      dataType.map(renderType).orElse(analogType).getOrElse(
        DigitalInterfaceContract.fail(1, "interface leaf has no exact type")
      )

  /** True selects the left sink. Declared roles never change across a boundary. */
  def connectionSink(left: String, leftBoundary: Boolean, right: String, rightBoundary: Boolean)
      : Option[Boolean] =
    if leftBoundary != rightBoundary then
      Option.when(left == right && Set("in", "out").contains(left))(
        if leftBoundary then left == "in" else left == "out"
      )
    else Option.when(Set("in" -> "out", "out" -> "in").contains(left -> right))(left == "in")

  def memberName(member: InterfaceMember): String = member match
    case value: InterfaceMember.Value[?] => value.name
    case valid: InterfaceMember.ValidChannel[?] => valid.name
    case stream: InterfaceMember.StreamChannel[?] => stream.name
    case nested: InterfaceMember.Nested[?] => nested.name
    case digital: InterfaceMember.DigitalResolved[?, ?] => digital.name
    case conservative: InterfaceMember.Conservative[?] => conservative.name
    case signal: InterfaceMember.SignalFlow[?] => signal.name

  def accessMember(access: RoleAccess): String = access match
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

  def accessName(access: RoleAccess): String = access match
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

  def validAccess(member: InterfaceMember, access: RoleAccess): Boolean = member match
    case _: InterfaceMember.Value[?] | _: InterfaceMember.SignalFlow[?] => access match
        case RoleAccess.In(_) | RoleAccess.Out(_) | RoleAccess.Observe(_) => true
        case _ => false
    case _: InterfaceMember.ValidChannel[?] | _: InterfaceMember.StreamChannel[?] => access match
        case RoleAccess.Master(_) | RoleAccess.Slave(_) | RoleAccess.Observe(_) => true
        case _ => false
    case _: InterfaceMember.Nested[?] => access match
        case RoleAccess.Nested(_, _) | RoleAccess.Observe(_) => true
        case _ => false
    case _: InterfaceMember.DigitalResolved[?, ?] => access match
        case RoleAccess.Read(_) | RoleAccess.Drive(_) | RoleAccess.Connect(_) |
            RoleAccess.Observe(_) => true
        case _ => false
    case _: InterfaceMember.Conservative[?] => access match
        case RoleAccess.Connect(_) | RoleAccess.Sense(_) | RoleAccess.Contribute(_) => true
        case _ => false

  def monitorRole(definition: InterfaceType[?]): Role[MonitorRole] =
    Role[MonitorRole](
      "monitor",
      definition.members.map:
        case value: InterfaceMember.Conservative[?] => RoleAccess.Sense(value.name)
        case member => RoleAccess.Observe(memberName(member))
      *
    )

  private def explicitRole(definition: InterfaceType[?], name: String): Role[?] =
    definition.roles.find(_.name == name).getOrElse(
      DigitalInterfaceContract.fail(2, s"Interface '${definition.name}' has no bound role '$name'")
    )

  def leaves(
      definition: InterfaceType[?],
      role: Role[?],
      resolveRole: (InterfaceType[?], String) => Role[?] = explicitRole
  ): Vector[Leaf] =
    var count = 0
    def expand(
        current: InterfaceType[?],
        selected: Role[?],
        prefix: String,
        nestedRoles: Vector[(String, String)],
        nestedInterfaces: Vector[(String, String)],
        depth: Int
    ): Vector[Leaf] =
      if depth > 128 then DigitalInterfaceContract.fail(1, "interface nesting exceeds 128")
      val names = current.members.map(memberName)
      if current.name.trim.isEmpty ||
        names.exists(name => name.trim.isEmpty || name.contains('.')) ||
        names.distinct.size != names.size
      then
        DigitalInterfaceContract.fail(1, "interface identity and member names must be unambiguous")
      val effective = if selected.name == "monitor" && selected.access.isEmpty then
        monitorRole(current)
      else selected
      val grouped = effective.access.groupBy(accessMember)
      if effective.name.trim.isEmpty || grouped.keySet != names.toSet ||
        grouped.values.exists(_.size != 1)
      then
        scala.util.Failure[Nothing](new ConstructionException(KernelDiagnostic(
          "NODAL-ROLE-COMPLETE-016",
          s"role '${effective.name}' is incomplete for Interface '${current.name}'"
        ))).get
      current.members.toVector.flatMap: member =>
        count += 1
        if count > 65536 then
          DigitalInterfaceContract.fail(1, "interface expansion exceeds 65536 members")
        val name = memberName(member)
        val path = if prefix.isEmpty then name else s"$prefix.$name"
        val access = grouped(name).head
        if !validAccess(member, access) then
          DigitalInterfaceContract.fail(
            3,
            s"role '${effective.name}' has invalid access for '$path'"
          )
        if effective.name == "monitor" && !Set("observe", "sense").contains(accessName(access)) then
          DigitalInterfaceContract.fail(3, "monitor roles must be read-only")
        def leaf(
            suffix: String,
            permission: String,
            dataType: Option[DataType[?]],
            protocol: String,
            mode: Option[String] = None,
            analogType: Option[String] = None,
            analogAttributes: Vector[(String, String)] = Vector.empty
        ): Leaf = Leaf(
          path + suffix,
          effective.name,
          permission,
          dataType,
          analogType,
          protocol,
          mode,
          nestedRoles,
          nestedInterfaces,
          analogAttributes
        )
        def protocol(payload: DataType[?], stream: Boolean): Vector[Leaf] =
          val forward = access match
            case RoleAccess.Master(_) => "out"
            case RoleAccess.Slave(_) => "in"
            case _ => "observe"
          val backward =
            if forward == "out" then "in" else if forward == "in" then "out" else "observe"
          val kind = if stream then "stream" else "valid"
          val values = Vector(
            leaf(".valid", forward, Some(Bool), kind),
            leaf(".payload", forward, Some(payload), kind)
          )
          if stream then values :+ leaf(".ready", backward, Some(Bool), kind) else values
        member match
          case value: InterfaceMember.Value[?] =>
            Vector(leaf("", accessName(access), Some(value.dataType), "plain"))
          case value: InterfaceMember.ValidChannel[?] => protocol(value.payloadType, false)
          case value: InterfaceMember.StreamChannel[?] => protocol(value.payloadType, true)
          case value: InterfaceMember.Nested[?] =>
            val childRole = access match
              case RoleAccess.Nested(_, selectedName) => resolveRole(value.definition, selectedName)
              case _ => monitorRole(value.definition)
            expand(
              value.definition,
              childRole,
              path,
              nestedRoles :+ (path -> childRole.name),
              nestedInterfaces :+ (path -> value.definition.name),
              depth + 1
            )
          case value: InterfaceMember.DigitalResolved[?, ?] =>
            Vector(leaf(
              "",
              accessName(access),
              Some(value.dataType),
              "resolved",
              Some(value.mode.name)
            ))
          case value: InterfaceMember.Conservative[?] =>
            val attributes = CandidateRuntime.conservativeAttributes(value.discipline).map:
              case (name, text: String) => name -> text
              case _ =>
                DigitalInterfaceContract.fail(1, "conservative member has non-textual attributes")
            Vector(leaf(
              "",
              accessName(access),
              None,
              "conservative",
              analogType = Some(s"Terminal(${attributes.toMap.apply("discipline")})"),
              analogAttributes = attributes
            ))
          case value: InterfaceMember.SignalFlow[?] =>
            Vector(leaf(
              "",
              accessName(access),
              None,
              "signal-flow",
              analogType = Some(s"AnalogSignal(${value.dimension})"),
              analogAttributes = Vector("dimension" -> value.dimension)
            ))
    expand(definition, role, "", Vector.empty, Vector.empty, 0)

  /** Compatibility projection for the existing construction ABI call site. */
  def expandMember(
      member: InterfaceMember,
      access: RoleAccess,
      logical: String,
      emitted: String,
      role: String,
      domain: String,
      renderType: DataType[?] => String
  ): Vector[InterfaceAbiEntry] =
    val definition = new InterfaceType[Interface]("projection", Vector(member))
    val selected = Role[RoleKind](role, access)
    val name = memberName(member)
    leaves(definition, selected).map: leaf =>
      val suffix = leaf.name.stripPrefix(name)
      InterfaceAbiEntry(
        logical + suffix,
        emitted + suffix.replace('.', '_'),
        leaf.role,
        leaf.access,
        leaf.rendered(renderType),
        domain
      )
