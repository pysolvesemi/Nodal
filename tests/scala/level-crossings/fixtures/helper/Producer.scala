package levelcrossinghelper

import nodal.*

object LevelCrossingHelper:
  def sync(value: Expr[Bool], destination: ClockDomain, stages: Int): Expr[Bool] =
    Cdc.sync(value, destination, stages)
