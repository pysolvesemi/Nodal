package nodal.internal.testkit
import nodal.*
sealed trait InterfaceEpochLink extends Interface
object InterfaceEpochSchema:
  val role = Role[SourceRole]("pins", RoleAccess.Connect("data"))
  val link = Interface[InterfaceEpochLink](
    "EpochLink",
    InterfaceMember.digitalInout("data", Bits(1), DriveMode.pushPull)
  )
