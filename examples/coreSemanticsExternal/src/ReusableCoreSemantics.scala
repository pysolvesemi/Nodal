package external.coresemantics

import nodal.*

enum LibraryMode derives HwEnum:
  case Idle, Active

final class ReusableCoreSemantics extends Module:
  val domain = ClockDomain.required("library")
  val input = in(SInt(12))
  val output = out(SInt(12))

  val delayed = RegNext(input + 1.S(12), 0.S(12))
  output := delayed

  val samples = wire(Vec(SInt(12), 4))
  val first = samples.at(0)
  val folded = samples.reduce((left, right) => left + right)
  CandidateUse.consume(first, folded)

  val stateDef = FsmDefinition[LibraryMode]("library-mode")
  fsm(stateDef, LibraryMode.Idle): machine =>
    machine.state(LibraryMode.Idle): state =>
      state.on(true.B)(LibraryMode.Active)
    machine.state(LibraryMode.Active): state =>
      state.terminal()

object CandidateUse:
  def consume(values: Any*): Unit = values.foreach(_ => ())
