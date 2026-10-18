package nodal.increment46structuralfixture

import nodal.*

object StructuralEquationContract:
  def concrete(name: String): PhysicalComponentContract = PhysicalComponentContract(
    name,
    ComponentCompleteness.Concrete,
    TopologyOwnership.Complete,
    LocalBalancePolicy.Required
  )
  def withId(name: String): EquationOptions = EquationOptions(id = Some(EquationId(name)))

import StructuralEquationContract.*

/** Two real unknowns and two unordered equations; no source equation becomes an assignment. */
final class BalancedEquationSource extends Module:
  val target = param(2.0.V)
  val gain = param(2.0.real)
  physicalComponent(concrete("pair")):
    val x = analogState("x", PhysicalDimension.Voltage, StateInitialization.Fixed(0.0.V))
    val y = analogState("y", PhysicalDimension.Voltage, StateInitialization.Guess(0.5.V))
    equations:
      equation(x.read + y.read, target, withId("sum"))
      equation(x.read - y.read, target / gain, withId("difference"))
    initialEquations:
      initialEquation(x.read, 0.0.V, InitialEquationOptions(id = Some(EquationId("initial-x"))))

final class UnderconstrainedEquationSource extends Module:
  physicalComponent(concrete("pair")):
    val x = analogState("x", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    val y = analogState("y", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    equations:
      equation(x.read + y.read, 1.0.V, withId("sum"))

final class OverconstrainedEquationSource extends Module:
  physicalComponent(concrete("single")):
    val x = analogState("x", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    equations:
      equation(x.read, 1.0.V, withId("first"))
      equation(x.read, 2.0.V, withId("second"))

/** A square graph with an unused declaration and a deficient incidence matching. */
final class SingularEquationSource extends Module:
  physicalComponent(concrete("pair")):
    val x = analogState("x", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    analogState("unused", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    equations:
      equation(x.read, 1.0.V, withId("first"))
      equation(x.read, 2.0.V, withId("second"))

final class UnusedEquationSource extends Module:
  physicalComponent(concrete("single")):
    analogState("unused", PhysicalDimension.Voltage, StateInitialization.SolverSelected)

final class RepeatedReadEquationSource extends Module:
  physicalComponent(concrete("single")):
    val x = analogState("x", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    equations:
      equation(x.read + x.read + x.read, 3.0.V, withId("repeated"))

final class CompensatingEquationSource extends Module:
  physicalComponent(concrete("under")):
    val x = analogState("x", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    val y = analogState("y", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    equations:
      equation(x.read + y.read, 1.0.V, withId("sum"))
  physicalComponent(concrete("over")):
    val x = analogState("x", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    equations:
      equation(x.read, 1.0.V, withId("first"))
      equation(x.read, 2.0.V, withId("second"))

final class UnsupportedEquationSource extends Module:
  physicalComponent(concrete("single")):
    val x = analogState("x", PhysicalDimension.Dimensionless, StateInitialization.SolverSelected)
    equations:
      equation(AnalogMath.sin(x.read), 0.0.real, withId("sine"))

final class GuardedEquationSource extends Module:
  physicalComponent(concrete("single")):
    val x = analogState("x", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    equations:
      equation(x.read, 1.0.V, withId("guarded").copy(guard = Some(true.B)))

final class PartialEquationSource extends Module:
  physicalComponent(concrete("single").copy(completeness = ComponentCompleteness.Partial)):
    val x = analogState("x", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    equations:
      equation(x.read, 1.0.V, withId("value"))

final class UnscopedEquationSource extends Module:
  val x = analogState("x", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
  equations:
    equation(x.read, 1.0.V, withId("value"))

final class CrossComponentEquationSource extends Module:
  private var shared: Option[AnalogState] = None
  physicalComponent(concrete("first")):
    val x = analogState("x", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    shared = Some(x)
    equations:
      equation(x.read, 1.0.V, withId("value"))
  physicalComponent(concrete("second")):
    val y = analogState("y", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    equations:
      equation(y.read, shared.get.read, withId("cross"))

final class InitializationEquationSource extends Module:
  physicalComponent(concrete("phases")):
    val fixed = analogState("fixed", PhysicalDimension.Voltage, StateInitialization.Fixed(0.0.V))
    val guess = analogState("guess", PhysicalDimension.Voltage, StateInitialization.Guess(0.0.V))
    val initial =
      analogState("initial", PhysicalDimension.Voltage, StateInitialization.InitialEquation)
    val steady = analogState("steady", PhysicalDimension.Voltage, StateInitialization.SteadyState)
    val operating =
      analogState("operating", PhysicalDimension.Voltage, StateInitialization.OperatingPoint)
    val selected =
      analogState("selected", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    equations:
      equation(fixed.read, 1.0.V, withId("fixed"))
      equation(guess.read, 1.0.V, withId("guess"))
      equation(initial.read, 1.0.V, withId("initial"))
      equation(steady.read, 1.0.V, withId("steady"))
      equation(operating.read, 1.0.V, withId("operating"))
      equation(selected.read, 1.0.V, withId("selected"))
    initialEquations:
      initialEquation(
        initial.read,
        0.0.V,
        InitialEquationOptions(id = Some(EquationId("initial-only")))
      )

final class InvalidStateInitializerSource extends Module:
  physicalComponent(concrete("bad")):
    analogState("x", PhysicalDimension.Voltage, StateInitialization.Fixed(1.0.A))

final class HierarchicalEquationSource extends Module:
  val child = instance(new RepeatedReadEquationSource)

final class UnsupportedInitializerEquationSource extends Module:
  physicalComponent(concrete("single")):
    val x = analogState(
      "x",
      PhysicalDimension.Dimensionless,
      StateInitialization.Guess(AnalogMath.sin(0.0.real))
    )
    equations:
      equation(x.read, 0.0.real, withId("value"))

/** Bounded source-path scale witness; each equation has a single sparse dependency. */
final class SparseEquationSource extends Module:
  physicalComponent(concrete("sparse")):
    val states = Vector.tabulate(64)(index =>
      analogState(s"x$index", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    )
    equations:
      states.zipWithIndex.foreach: (state, index) =>
        equation(state.read, 1.0.V, withId(s"e$index"))

/** Reusing the same object creates a shared typed DAG, not duplicate state vertices. */
final class SharedDagEquationSource extends Module:
  physicalComponent(concrete("shared")):
    val x = analogState("x", PhysicalDimension.Voltage, StateInitialization.SolverSelected)
    var value = x.read
    (0 until 8).foreach: _ =>
      value = value + value
    equations:
      equation(value, 256.0.V, withId("shared"))

/** Two occurrences deliberately reuse one canonical child module definition. */
final class RepeatedHierarchyEquationSource extends Module:
  val first = instance(new RepeatedReadEquationSource)
  val second = instance(new RepeatedReadEquationSource)

final class EmptyComponentEquationSource extends Module:
  physicalComponent(concrete("empty")):
    ()
