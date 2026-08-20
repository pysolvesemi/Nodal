package nodal.bootstrap.frontend

import nodal.bootstrap.api.*

final case class ElaboratedModule(module: BootstrapModule):
  def symbol: String = module.name.value
