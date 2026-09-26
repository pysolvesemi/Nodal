package nodal.prototype.negative.effectful

import nodal.prototype.*
import scala.language.implicitConversions

object DefaultEffects:
  var calls = 0
  def next(): Double =
    calls += 1
    2.0

// Must fail with the plugin's bounded-profile diagnostic, not run the effect.
class EffectfulDefault(gain: Param[Real] = DefaultEffects.next()) extends Module

