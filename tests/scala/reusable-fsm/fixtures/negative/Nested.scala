// Expected: NODAL-SEQUENTIAL-056-EFFECT
package reusable.negative
import nodal.*
object Nested:
  def action[S](slot: FsmAction[S], predicate: FsmGuard[S], flag: Expr[Bool]): FsmActionBinding[S] =
    slot.bind { val _ = predicate.bind(flag) }
