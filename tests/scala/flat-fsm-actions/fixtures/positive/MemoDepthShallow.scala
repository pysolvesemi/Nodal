package positive.memodepth
import nodal.*
enum DeepMode derives HwEnum:
  case Idle, Run
object DeepMode:
  final def init0(): Int = 0
  final def init1(): Int = init0() + 1
  final def init2(): Int = init1() + 1
  final def init3(): Int = init2() + 1
  final def init4(): Int = init3() + 1
  final def init5(): Int = init4() + 1
  final def init6(): Int = init5() + 1
  final def init7(): Int = init6() + 1
  final def init8(): Int = init7() + 1
  final def init9(): Int = init8() + 1
  final def init10(): Int = init9() + 1
  final def init11(): Int = init10() + 1
  final def init12(): Int = init11() + 1
  val marker: Int = init12()
object MemoDepthShallow:
  def build(state: StateBuilder[DeepMode], flag: Expr[Bool]): Unit =
    state.on(flag)(DeepMode.Run)
