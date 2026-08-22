package contracts.registerfactory.positive

import nodal.*

final class DemoControlBus

object DemoControlBus:
  given RegisterTransport[DemoControlBus] with
    def capabilities(_bus: DemoControlBus): RegisterTransportCapabilities =
      RegisterTransportCapabilities(
        dataWidth = 32,
        addressWidth = 16,
        byteEnable = true,
        errorResponse = true,
        protection = false,
        backpressure = true,
        maxOutstanding = 1,
        inOrder = true
      )

    def connect(_bus: DemoControlBus, _endpoint: RegisterAccessPort): Unit = ()

final class TransportBindingFixture extends Module:
  val busDomain = ClockDomain.required("bus")

  busDomain:
    val registers = RegisterBlock(UartMap)
    registers.attach(new DemoControlBus)
