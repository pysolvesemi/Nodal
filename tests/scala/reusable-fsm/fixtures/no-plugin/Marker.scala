package reusable.fixture
import nodal.*
object Marker:
  def action[S](slot: FsmAction[S]): FsmActionBinding[S] = slot.bind { () }
