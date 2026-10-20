// Expected: type mismatch
package reusable.negative
import nodal.*
object Dynamic:
  def graph[S](value: S, signal: Expr[Bool]): FsmState[S] =
    FsmState(value).on(signal)(value)
