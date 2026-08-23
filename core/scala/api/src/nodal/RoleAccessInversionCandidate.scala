package nodal

/** Built-in role kinds whose interface ownership can be inverted automatically. */
type InvertibleRole =
  MasterRole | SlaveRole | SourceRole | SinkRole | InitiatorRole | TargetRole |
    ControllerRole | PeripheralRole | DeviceRole | EnvironmentRole

/** Compile-time inverse for the built-in complementary role families. */
type InverseRole[R <: InvertibleRole] <: RoleKind = R match
  case MasterRole => SlaveRole
  case SlaveRole => MasterRole
  case SourceRole => SinkRole
  case SinkRole => SourceRole
  case InitiatorRole => TargetRole
  case TargetRole => InitiatorRole
  case ControllerRole => PeripheralRole
  case PeripheralRole => ControllerRole
  case DeviceRole => EnvironmentRole
  case EnvironmentRole => DeviceRole

private[nodal] object RoleAccessInversion:
  def apply(access: RoleAccess): RoleAccess = access match
    case RoleAccess.In(member) => RoleAccess.Out(member)
    case RoleAccess.Out(member) => RoleAccess.In(member)
    case RoleAccess.Observe(member) => RoleAccess.Observe(member)
    case RoleAccess.Master(member) => RoleAccess.Slave(member)
    case RoleAccess.Slave(member) => RoleAccess.Master(member)
    case RoleAccess.Read(member) => RoleAccess.Drive(member)
    case RoleAccess.Drive(member) => RoleAccess.Read(member)
    case RoleAccess.Connect(member) => RoleAccess.Connect(member)
    case RoleAccess.Sense(member) => RoleAccess.Contribute(member)
    case RoleAccess.Contribute(member) => RoleAccess.Sense(member)
    case RoleAccess.Nested(member, role) =>
      RoleAccess.Nested(member, inverseRoleName(role))

  def inverseRoleName(role: String): String = role match
    case "master" => "slave"
    case "slave" => "master"
    case "source" => "sink"
    case "sink" => "source"
    case "initiator" => "target"
    case "target" => "initiator"
    case "controller" => "peripheral"
    case "peripheral" => "controller"
    case "device" => "environment"
    case "environment" => "device"
    case other => s"inverse($other)"

extension [I <: Interface, R <: InvertibleRole](endpoint: InterfacePort[I, R])
  /** Preserve interface-specific member permissions while reversing the role kind. */
  def inverted: InterfacePort[I, InverseRole[R]] =
    val invertedRole = new Role[InverseRole[R]](
      RoleAccessInversion.inverseRoleName(endpoint.role.name),
      endpoint.role.access.map(RoleAccessInversion.apply)
    )
    new InterfacePort[I, InverseRole[R]](
      endpoint.definition,
      invertedRole,
      s"${endpoint.name}.inverse",
      endpoint.domain
    )
