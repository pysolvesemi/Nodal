import nodal.*
final class GuardMarker extends Module:
  val MODE = param(0.integer)
  when(MODE == 0) { () }
