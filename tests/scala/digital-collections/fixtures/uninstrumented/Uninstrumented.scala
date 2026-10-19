package nodal_callback_external

import nodal.*

/** Deliberately compiled without the plugin, then invoked through the public collection API. */
object Uninstrumented:
  var calls = 0

  def map(input: Expr[Vec[UInt]]): Expr[Vec[UInt]] = input.map: value =>
    calls += 1
    value
