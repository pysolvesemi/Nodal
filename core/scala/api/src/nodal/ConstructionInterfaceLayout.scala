package nodal

private object ConstructionInterfaceLayout:
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

  def validAccess(member: InterfaceMember, access: RoleAccess): Boolean = member match
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
      renderType: DataType[?] => String
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
        renderType(payload),
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

  def expandMember(
      member: InterfaceMember,
      access: RoleAccess,
      logical: String,
      emitted: String,
      role: String,
      domain: String,
      renderType: DataType[?] => String
  ): Vector[InterfaceAbiEntry] = member match
    case value: InterfaceMember.Value[?] =>
      Vector(
        InterfaceAbiEntry(
          logical,
          emitted,
          role,
          accessName(access),
          renderType(value.dataType),
          domain
        )
      )
    case valid: InterfaceMember.ValidChannel[?] =>
      protocolAbi(logical, emitted, role, access, valid.payloadType, domain, false, renderType)
    case stream: InterfaceMember.StreamChannel[?] =>
      protocolAbi(logical, emitted, role, access, stream.payloadType, domain, true, renderType)
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
          renderType
        )
    case digital: InterfaceMember.DigitalResolved[?, ?] =>
      Vector(
        InterfaceAbiEntry(
          logical,
          emitted,
          role,
          accessName(access),
          renderType(digital.dataType),
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
