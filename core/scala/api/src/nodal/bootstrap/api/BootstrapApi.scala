package nodal.bootstrap.api

opaque type ModuleName = String

object ModuleName:
  def apply(value: String): ModuleName =
    require(value.nonEmpty, "module name must not be empty")
    value

extension (name: ModuleName)
  def value: String = name

final case class BootstrapModule(name: ModuleName)
