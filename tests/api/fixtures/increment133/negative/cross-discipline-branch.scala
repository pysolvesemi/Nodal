package examples.continuoustime

import nodal.*

object CrossDisciplineBranch:
  val thermalPotential = nature("temperature")
  val thermalFlow = nature("heat-flow")
  val thermal = discipline("thermal", thermalPotential, thermalFlow)
  val electricalTerminal = terminal(Electrical, "electrical")
  val thermalTerminal = terminal(thermal, "thermal")

  // diagnostic-anchor: NODAL-ANALOG-133-TYPE-003
  val invalid = branch(electricalTerminal, thermalTerminal)
