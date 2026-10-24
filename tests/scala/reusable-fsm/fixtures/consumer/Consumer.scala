package reusable.fixture

import nodal.*

object Consumer:
  def bindings(go: Expr[Bool], target: Register[UInt], value: Expr[UInt]): FsmBindings[Mode] =
    FsmBindings(
      Seq(Library.go.bind(go)),
      Seq(Library.action.bind { Library.update(target, value) })
    )
  def capture(bindings: FsmBindings[Mode], output: Signal[Bool]): Unit =
    Library.graph.instantiate(bindings): instance =>
      output := instance.completed
