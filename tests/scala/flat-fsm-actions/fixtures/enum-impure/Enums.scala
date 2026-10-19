package nodal.flatfsm.enums
import nodal.*
enum Mode derives HwEnum:
  case Idle, Run
object Mode:
  println("replacement enum initializer")
object EnumHelpers:
  def destination: Mode = Mode.Run
  def evidence: HwEnum[Mode] = summon[HwEnum[Mode]]
