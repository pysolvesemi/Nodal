package nodal.bootstrap.testkit

import nodal.bootstrap.cli.BootstrapCli
import nodal.bootstrap.sim.SimulationPlan

object BootstrapFixture:
  val moduleName = "rc_smoke"

  def plan: SimulationPlan = SimulationPlan.smoke(moduleName)
  def report: String = BootstrapCli.describe(moduleName)
