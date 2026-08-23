package examples.interfacepipeline

import external.interfacepipeline.RegisterBus
import nodal.*

sealed trait PixelLink extends Interface

object PixelLink:
  val definition: InterfaceType[PixelLink] = Interface[PixelLink](
    "PixelLink",
    InterfaceMember.stream("pixels", UInt(24)),
    InterfaceMember.value("frameStart", Bool),
    InterfaceMember.value("lineStart", Bool)
  )

  val sourceRole: Role[SourceRole] = Role[SourceRole](
    "source",
    RoleAccess.Master("pixels"),
    RoleAccess.Out("frameStart"),
    RoleAccess.Out("lineStart")
  )

  val sinkRole: Role[SinkRole] = Role[SinkRole](
    "sink",
    RoleAccess.Slave("pixels"),
    RoleAccess.In("frameStart"),
    RoleAccess.In("lineStart")
  )

  val monitorRole: Role[MonitorRole] = Role[MonitorRole](
    "monitor",
    RoleAccess.Observe("pixels"),
    RoleAccess.Observe("frameStart"),
    RoleAccess.Observe("lineStart")
  )

sealed trait RequestLink extends Interface

object RequestLink:
  val definition: InterfaceType[RequestLink] = Interface[RequestLink](
    "RequestLink",
    InterfaceMember.valid("command", UInt(16)),
    InterfaceMember.stream("response", UInt(32))
  )

  val initiatorRole: Role[InitiatorRole] = Role[InitiatorRole](
    "initiator",
    RoleAccess.Master("command"),
    RoleAccess.Slave("response")
  )

  val targetRole: Role[TargetRole] = Role[TargetRole](
    "target",
    RoleAccess.Slave("command"),
    RoleAccess.Master("response")
  )

sealed trait ControlFabric extends Interface

object ControlFabric:
  val definition: InterfaceType[ControlFabric] = Interface[ControlFabric](
    "ControlFabric",
    InterfaceMember.nested("request", RequestLink.definition),
    InterfaceMember.value("interrupt", Bool)
  )

  val controllerRole: Role[ControllerRole] = Role[ControllerRole](
    "controller",
    RoleAccess.Nested("request", "initiator"),
    RoleAccess.In("interrupt")
  )

  val peripheralRole: Role[PeripheralRole] = Role[PeripheralRole](
    "peripheral",
    RoleAccess.Nested("request", "target"),
    RoleAccess.Out("interrupt")
  )

sealed trait AdcLink extends Interface

object AdcLink:
  val definition: InterfaceType[AdcLink] = Interface[AdcLink](
    "AdcLink",
    InterfaceMember.terminal("vinP", Electrical),
    InterfaceMember.terminal("vinN", Electrical),
    InterfaceMember.value("sample", Bool),
    InterfaceMember.valid("code", UInt(12)),
    InterfaceMember.analogSignal[VoltageDimension]("reference", "voltage")
  )

  val deviceRole: Role[DeviceRole] = Role[DeviceRole](
    "device",
    RoleAccess.Connect("vinP"),
    RoleAccess.Connect("vinN"),
    RoleAccess.In("sample"),
    RoleAccess.Master("code"),
    RoleAccess.In("reference")
  )

  val environmentRole: Role[EnvironmentRole] = Role[EnvironmentRole](
    "environment",
    RoleAccess.Connect("vinP"),
    RoleAccess.Connect("vinN"),
    RoleAccess.Out("sample"),
    RoleAccess.Slave("code"),
    RoleAccess.Out("reference")
  )

  val monitorRole: Role[MonitorRole] = Role[MonitorRole](
    "monitor",
    RoleAccess.Sense("vinP"),
    RoleAccess.Sense("vinN"),
    RoleAccess.Observe("sample"),
    RoleAccess.Observe("code"),
    RoleAccess.Observe("reference")
  )

final case class ArithmeticTransaction(
    a: Expr[UInt],
    b: Expr[UInt],
    c: Expr[UInt],
    d: Expr[UInt],
    tag: Expr[UInt]
)

final case class ArithmeticResult(
    data: Expr[UInt],
    tag: Expr[UInt]
)

final class PipelineInterfaceCandidates extends Module:
  val domain = ClockDomain.required("pipeline-interface")
  val width = param(12.integer)

  val a = in(UInt(width))
  val b = in(UInt(width))
  val c = in(UInt(width))
  val d = in(UInt(width))
  val tag = in(UInt(8))
  val enable = in(Bool)
  val packedWrite = in(Bits(8))
  val result = out(UInt(width))

  // Directionless Struct values are storable; Interface endpoints are not Data values.
  val pixelType = Struct(
    "Pixel",
    StructField("red", UInt(8)),
    StructField("green", UInt(8)),
    StructField("blue", UInt(8))
  )
  val pixel = wire(pixelType)
  val pixelRegister = Reg(pixel)
  pixel := pixelRegister

  // Named roles, legal inversion, monitor view, exact connection, and symbolic arrays.
  val pixelSource = interfacePort(PixelLink.definition, PixelLink.sourceRole, "pixelOut", domain)
  val pixelSink = interfacePort(PixelLink.definition, PixelLink.sinkRole, "pixelIn", domain)
  pixelSource.connectExact(pixelSink)
  val invertedPixelSource = pixelSource.inverted
  val invertedPixelAccess = invertedPixelSource.role.access
  val pixelMonitor = pixelSource.monitorView
  pixelSource.driveMember("frameStart", enable)
  pixelMonitor.observeMember("pixels")
  val pixelLanes = interfaceArray(
    PixelLink.definition,
    PixelLink.sourceRole,
    width,
    "pixelLanes",
    domain
  )

  // Nested request/response roles and external reusable interface definitions.
  val controlController = interfacePort(
    ControlFabric.definition,
    ControlFabric.controllerRole,
    "control",
    domain
  )
  val controlPeripheral = interfacePort(
    ControlFabric.definition,
    ControlFabric.peripheralRole,
    "controlPeer",
    domain
  )
  controlController.connectExact(controlPeripheral)

  val externalInitiator = interfacePort(
    RegisterBus.definition,
    RegisterBus.initiatorRole,
    "externalRegisterBus",
    domain
  )
  val externalTarget = interfacePort(
    RegisterBus.definition,
    RegisterBus.targetRole,
    "externalRegisterBusPeer",
    domain
  )
  externalInitiator.connectExact(externalTarget)

  val portableLayout = InterfaceLayoutPolicy(
    InterfaceLayout.PortableFlattened,
    flattenPrefix = Some("video")
  )
  val nativeLayout = InterfaceLayoutPolicy(InterfaceLayout.FutureSystemVerilogNative)

  // Digital inout has explicit sensing, driving, high-Z, split carrier, pass-through, and pads.
  val gpio = digitalInout(
    Bits(8),
    DriveMode.pushPull,
    InoutPlacement.TopLevelPin,
    ResolutionProfile.PortableBoundaryOnly,
    "gpio"
  )
  val gpioRead = gpio.read
  gpio.drive(packedWrite, enable)
  gpio.highZ()
  val gpioCarrier = gpio.split

  val blackBoxGpio = digitalInout(
    Bits(8),
    DriveMode.pushPull,
    InoutPlacement.BlackBoxPin,
    ResolutionProfile.PortableBoundaryOnly,
    "blackBoxGpio"
  )
  passThrough(gpio, blackBoxGpio)
  val gpioPad = padAdapter(gpio, "GENERIC_GPIO_PAD")

  val i2c = digitalInout(
    Bits(1),
    DriveMode.openDrain,
    InoutPlacement.HierarchyPassThrough,
    ResolutionProfile.PortableBoundaryOnly,
    "i2cSda"
  )
  i2c.driveLow(enable)
  i2c.highZ()
  val i2cCarrier = i2c.split

  val internalResolvedCandidate = digitalInout(
    Bits(4),
    DriveMode.pushPull,
    InoutPlacement.InternalResolvedNet,
    ResolutionProfile.FullResolvedSimulation,
    "internalResolvedCandidate"
  )

  // Conservative terminals and directional analog signal-flow remain distinct.
  val vinP = terminal(Electrical, "vinP")
  val vinN = terminal(Electrical, "vinN")
  vinP.connectView.connectTo(vinN.connectView)
  val sensedPotential = vinP.senseView.potential
  val sensedFlow = vinP.terminalMonitorView.flow
  vinN.contributeView.contribute(0.0.real)

  val referenceSource = AnalogSignal.source[VoltageDimension]("referenceSource", "voltage")
  val referenceSink = AnalogSignal.sink[VoltageDimension]("referenceSink", "voltage")
  referenceSource.driveAnalog(1.0.real)
  val referenceSample = referenceSink.sampleAnalog

  val bridgeContract = BridgeContract(
    sampleTime = 1.0e-9.seconds,
    threshold = Some(0.6.volts),
    hysteresis = Some(0.02.volts),
    quantization = QuantizationPolicy.RoundNearest,
    models = Set(ModelAvailability.Simulation, ModelAvailability.Formal),
    provenance = "increment-14-candidate"
  )
  val sampledCode = MixedSignalBridge.sample(vinP.senseView, UInt(12), bridgeContract)
  MixedSignalBridge.drive(sampledCode, vinN.contributeView, bridgeContract)
  ConservativeSignalBridge.senseToSignal(
    vinP.senseView,
    referenceSource,
    bridgeContract
  )

  val adcDevice = interfacePort(AdcLink.definition, AdcLink.deviceRole, "adc", domain)
  val adcEnvironment = interfacePort(
    AdcLink.definition,
    AdcLink.environmentRole,
    "adcEnvironment",
    domain
  )
  adcDevice.connectExact(adcEnvironment)
  val adcMonitor = adcDevice.monitorView

  // Automatic pipeline policies operate on plain, Valid, and Stream transactions.
  val envelope = ParameterEnvelope(width, minimum = 8, maximum = 64)
  val autoPolicy = PipelinePolicy(
    latency = Latency.Auto,
    throughput = Throughput.EveryCycle,
    target = Some(500.MHz),
    ready = ReadyPath.Auto,
    envelopes = Seq(envelope),
    scheduleFor = EnvelopeSchedule.WorstCase
  )
  val exactPolicy = PipelinePolicy(
    latency = Latency.Exact(3),
    throughput = Throughput.EveryCycle,
    target = Some(400.MHz),
    ready = ReadyPath.Auto
  )
  val rangedPolicy = PipelinePolicy(
    latency = Latency.Range(2, 5),
    throughput = Throughput.EveryCycle,
    target = Some(350.MHz),
    ready = ReadyPath.Registered
  )

  val transaction = Txn(ArithmeticTransaction(a, b, c, d, tag))
  val automatic = pipe(transaction, autoPolicy): current =>
    val left = stage(current.a + current.b)
    val right = sameStage:
      current.c + current.d
    ArithmeticResult(left + right, current.tag)

  val validInput = Valid(a)
  val validOutput = pipe(validInput, exactPolicy): payload =>
    stage(payload + payload)

  val streamInput = Stream(a)
  val streamOutput = pipe(streamInput, rangedPolicy): payload =>
    sameStage:
      payload + payload

  val delayedPlain = a.delay(3)
  val delayedTransaction = automatic.delay(1)
  val delayedValid = validOutput.delay(2)
  val delayedStream = streamOutput.delay(2)
  val schedule = inspectSchedule(automatic, "arithmetic", autoPolicy)

  // Fixed-latency operators support all protocols; variable latency remains elastic-only.
  val fixedOperator = FixedLatencyOperator[UInt, UInt](
    "fixedCrc",
    UInt(32),
    ExternalContract(
      latency = 2,
      initiationInterval = 1,
      effect = Effect.Pure,
      models = Set(ModelAvailability.Simulation, ModelAvailability.Synthesis),
      domain = domain
    )
  )
  val fixedPlain = fixedOperator(a)
  val fixedValid = fixedOperator(validInput)
  val fixedStream = fixedOperator(streamInput)

  val variableOperator = VariableLatencyOperator[UInt, UInt](
    "elasticDivider",
    UInt(width),
    VariableLatencyContract(
      minimumLatency = 2,
      maximumLatency = 16,
      capacity = 4,
      initiationInterval = 1,
      effect = Effect.Pure,
      models = Set(ModelAvailability.Simulation, ModelAvailability.Synthesis),
      domain = domain
    )
  )
  val variableStream = variableOperator(streamInput)

  result := automatic.value.data
  CandidateUse.consume(
    automatic.value.tag,
    validOutput,
    streamOutput,
    delayedPlain,
    delayedTransaction,
    delayedValid,
    delayedStream,
    schedule,
    fixedPlain,
    fixedValid,
    fixedStream,
    variableStream,
    invertedPixelSource,
    invertedPixelAccess,
    pixelMonitor,
    pixelLanes,
    portableLayout,
    nativeLayout,
    gpioRead,
    gpioCarrier,
    gpioPad,
    i2cCarrier,
    internalResolvedCandidate,
    sensedPotential,
    sensedFlow,
    referenceSample,
    sampledCode,
    adcMonitor,
    RequestLink.definition,
    RequestLink.initiatorRole,
    RequestLink.targetRole,
    PixelLink.monitorRole,
    AdcLink.monitorRole
  )

object CandidateUse:
  def consume(values: Any*): Unit = values.foreach(_ => ())
