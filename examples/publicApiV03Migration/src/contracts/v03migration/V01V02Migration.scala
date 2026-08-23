package contracts.v03migration

import nodal.*

final class MigratedV01Analog extends Module:
  val input = in(Electrical)
  val output = out(Electrical)
  val gain = param(2.0.real)

  analog:
    V(output) <+ gain * V(input)

final class MigratedV02Clocked extends Module:
  val domain = ClockDomain.required("migrated-v02")
  val input = in(UInt(8))
  val asyncFlag = in(Bool)
  val enable = in(Bool)
  val output = out(UInt(8))

  domain:
    val synchronized = Cdc.sync(asyncFlag, to = domain)
    val state = Reg(0.U(8))
    when(enable && synchronized):
      state := input
    output := state

object MigrationContracts:
  val v01ExplicitBackend = EmitOptions(backend = Backend.VerilogAMS)
  val v02ExplicitBackend = EmitOptions(backend = Backend.VerilogAMS)
  val v03AutomaticBackend = EmitOptions()
  val v03PortableDigital = EmitOptions(
    backend = Backend.Verilog,
    digitalProfile = DigitalProfile.Synthesis
  )
  val analogEmission = Nodal.emit(new MigratedV01Analog, v01ExplicitBackend)
  val clockedEmission = Nodal.emit(new MigratedV02Clocked, v02ExplicitBackend)

  MigrationEvidence.consume(
    v03AutomaticBackend,
    v03PortableDigital,
    analogEmission,
    clockedEmission
  )

object MigrationEvidence:
  def consume(values: Any*): Unit = values.foreach(_ => ())
