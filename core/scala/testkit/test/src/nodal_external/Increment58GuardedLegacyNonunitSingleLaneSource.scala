package nodal_external
import nodal.*

/** Existing indexed assignment profile: one generated occurrence with actual step two. */
final class Increment58GuardedLegacyNonunitSingleLaneSource extends Module, StructuralIfClock:
  val choice = param(0.integer, range = 0 to 1)
  val input = in(Vec(UInt(8), 1))
  val output = out(Vec(UInt(8), 1))
  if choice == 0 then
    hdlRange(0, 1, 2): i =>
      output.writable.at(i) := input.at(i)
  else output := input
