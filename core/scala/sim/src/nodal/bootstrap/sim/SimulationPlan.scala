package nodal.bootstrap.sim

import nodal.bootstrap.api.{BootstrapModule, ModuleName}
import nodal.bootstrap.bridge.TextualMlir
import nodal.bootstrap.integrations.ToolStatus

final case class SimulationPlan(
    module: BootstrapModule,
    payload: TextualMlir,
    tool: ToolStatus,
)

object SimulationPlan:
  def smoke(name: String): SimulationPlan =
    val module = BootstrapModule(ModuleName(name))
    SimulationPlan(
      module = module,
      payload = TextualMlir.from(module),
      tool = ToolStatus.Unavailable("simulator integration is deferred"),
    )
