// Expected: NODAL-SEQUENTIAL-056-MUTABLE
package reusable.negative
import nodal.*
object Impure:
  var count = 0
  def action[S](slot: FsmAction[S]): FsmActionBinding[S] =
    slot.bind { count += 1 }
