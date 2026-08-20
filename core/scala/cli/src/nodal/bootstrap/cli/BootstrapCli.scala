package nodal.bootstrap.cli

import nodal.bootstrap.api.*
import nodal.bootstrap.sim.SimulationPlan

object BootstrapCli:
  def describe(name: String): String =
    val plan = SimulationPlan.smoke(name)
    s"${plan.module.name.value}:${plan.payload.value.trim}"
