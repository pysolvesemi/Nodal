package nodal.iteration.fixture

import nodal.*

/** Existing library allocation keeps its constructor protocol outside an attested callback. */
final class SeparateLibraryConstructor extends Module:
  val child = instance(new SeparateChild)
  val p = inout(Electrical)
  child(_.p) <> p
