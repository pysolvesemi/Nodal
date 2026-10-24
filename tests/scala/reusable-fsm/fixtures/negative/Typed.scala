// Expected: type mismatch
package reusable.negative
import nodal.*
object Typed:
  def state[A, B](value: A, predicate: FsmGuard[B]): FsmState[A] =
    FsmState(value).on(predicate)(value)
