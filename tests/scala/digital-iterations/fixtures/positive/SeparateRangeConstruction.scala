package nodal.iteration.fixture

import nodal.*

final class SeparateRangeConstruction extends Module:
  val gain = param(real(3.0))
  hdlRange(0, 2): _ =>
    val direct = instance(new SeparateChild)
    val defaulted = instance(new SeparateDefaultChild)
    val factory = instance(SeparateFactory.child())
    val factoryDefault = instance(SeparateFactory.defaultChild())
    val factoryActual = instance(SeparateFactory.actualChild(gain))
    val p = node(Electrical)
    direct(_.p) <> p
    defaulted(_.p) <> p
    factory(_.p) <> p
    factoryDefault(_.p) <> p
    factoryActual(_.p) <> p

final class SeparateDigitalRangeConstruction extends Module:
  hdlRange(0, 2): _ =>
    val _ = instance(new SeparateDigitalChild)
