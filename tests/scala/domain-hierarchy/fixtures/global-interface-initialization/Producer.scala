package nodal_external
import nodal.*
sealed trait GlobalInitializationLink extends Interface
object GlobalInitializationSchemas:
  val producer = Role[SourceRole]("producer", RoleAccess.Out("data"))
  def make(width: Expr[Integer]): InterfaceType[GlobalInitializationLink] =
    Interface[GlobalInitializationLink](
      "GlobalInitializationLink",
      InterfaceMember.value("data", UInt(width))
    )
      .withRoles(producer)
  val eager = make(8.integer)
object GlobalInitializationEffectfulFactory:
  def make(width: Expr[Integer]): InterfaceType[GlobalInitializationLink] =
    println("forbidden factory effect")
    GlobalInitializationSchemas.make(width)
