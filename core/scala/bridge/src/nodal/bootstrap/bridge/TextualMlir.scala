package nodal.bootstrap.bridge

import nodal.bootstrap.api.BootstrapModule
import nodal.bootstrap.frontend.ElaboratedModule

final case class TextualMlir(value: String)

object TextualMlir:
  def from(module: BootstrapModule): TextualMlir =
    val elaborated = ElaboratedModule(module)
    val escaped = elaborated.symbol.replace("\\", "\\\\").replace("\"", "\\\"")
    TextualMlir(s"module attributes {nodal.bootstrap.module = \"$escaped\"}\n")
