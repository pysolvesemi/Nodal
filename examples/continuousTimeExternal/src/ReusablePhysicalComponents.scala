package external.continuoustime

import nodal.*

/** Reusable public-contract-only physical component used by the Increment 133 gate. */
abstract class PartialTwoTerminal[D <: Discipline](val terminalDiscipline: D) extends Module:
  val positive: Terminal[D] = terminal(terminalDiscipline, "positive")
  val negative: Terminal[D] = terminal(terminalDiscipline, "negative")
  val path: Branch[D] = branch(positive, negative, "path")

  physicalComponent(
    PhysicalComponentContract(
      name = "PartialTwoTerminal",
      completeness = ComponentCompleteness.Partial,
      topology = TopologyOwnership.Extensible,
      localBalance = LocalBalancePolicy.DeferredToConcrete,
      replaceable = true
    )
  ):
    localBalance(
      BalanceId("two-terminal-flow-balance"),
      positive.senseView.flow,
      negative.senseView.flow
    )

final class Resistor(defaultResistance: Expr[Real] = 1.0.kOhm)
    extends PartialTwoTerminal(Electrical):
  val resistance: Param[Real] = param(defaultResistance)

  physicalComponent(
    PhysicalComponentContract(
      name = "Resistor",
      completeness = ComponentCompleteness.Concrete,
      topology = TopologyOwnership.Complete,
      localBalance = LocalBalancePolicy.Explicit
    )
  ):
    equations:
      path.potential === resistance * path.flow

final class Capacitor(defaultCapacitance: Expr[Real] = 1.0.pF)
    extends PartialTwoTerminal(Electrical):
  val capacitance: Param[Real] = param(defaultCapacitance)

  physicalComponent(
    PhysicalComponentContract(
      name = "Capacitor",
      completeness = ComponentCompleteness.Concrete,
      topology = TopologyOwnership.Complete,
      localBalance = LocalBalancePolicy.Explicit
    )
  ):
    contributions:
      path.flow <+ capacitance * ddt(path.potential)
