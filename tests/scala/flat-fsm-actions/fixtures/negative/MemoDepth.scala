// Expected: NODAL-SEQUENTIAL-056-LIMIT
package negative.memodepth
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
object MemoDepth:
  final def dest0(): DeepMode = DeepMode.Run
  final def dest1(): DeepMode = dest0()
  final def dest2(): DeepMode = dest1()
  final def dest3(): DeepMode = dest2()
  final def dest4(): DeepMode = dest3()
  final def dest5(): DeepMode = dest4()
  final def dest6(): DeepMode = dest5()
  final def dest7(): DeepMode = dest6()
  final def dest8(): DeepMode = dest7()
  final def dest9(): DeepMode = dest8()
  final def dest10(): DeepMode = dest9()
  final def dest11(): DeepMode = dest10()
  final def dest12(): DeepMode = dest11()
  final def dest13(): DeepMode = dest12()
  final def dest14(): DeepMode = dest13()
  final def dest15(): DeepMode = dest14()
  final def dest16(): DeepMode = dest15()
  def build(state: StateBuilder[DeepMode], flag: Expr[Bool]): Unit =
    state.on(flag)(DeepMode.Run)
    state.on(flag)(dest16())
