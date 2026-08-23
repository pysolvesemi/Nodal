package contracts.v03

import external.v03.ExplicitV03Adapter
import external.v03.ReusableV03Pipeline
import external.v03.UnifiedLink
import nodal.*

enum UnifiedState derives HwEnum:
  case Idle, Active, Complete, Error

object UnifiedState:
  val canonical: EnumEncoding[UnifiedState] = enumEncoding(
    Idle -> BigInt(0),
    Active -> BigInt(1),
    Complete -> BigInt(2),
    Error -> BigInt(3)
  )

sealed trait LocalLink extends Interface

object LocalLink:
  val definition: InterfaceType[LocalLink] = Interface[LocalLink](
    "LocalLink",
    InterfaceMember.value("frame", Bool),
    InterfaceMember.valid("metadata", UInt(8)),
    InterfaceMember.stream("payload", UInt(32))
  )

  val sourceRole: Role[SourceRole] = Role[SourceRole](
    "source",
    RoleAccess.Out("frame"),
    RoleAccess.Master("metadata"),
    RoleAccess.Master("payload")
  )

  val sinkRole: Role[SinkRole] = Role[SinkRole](
    "sink",
    RoleAccess.In("frame"),
    RoleAccess.Slave("metadata"),
    RoleAccess.Slave("payload")
  )

  val monitorRole: Role[MonitorRole] = Role[MonitorRole](
    "monitor",
    RoleAccess.Observe("frame"),
    RoleAccess.Observe("metadata"),
    RoleAccess.Observe("payload")
  )

final case class ArithmeticInput(
    left: Expr[UInt],
    right: Expr[UInt],
    tag: Expr[UInt]
)

final case class ArithmeticResult(data: Expr[UInt], tag: Expr[UInt])

final class UnifiedPublicApiV03 extends Module:
  val domain = ClockDomain.required("unified-v03")
  val width = param(32.integer)

  val unsignedIn = in(UInt(width))
  val signedIn = in(SInt(width))
  val bitsIn = in(Bits(width))
  val tag = in(UInt(8))
  val enable = in(Bool)
  val packedWrite = in(Bits(8))

  val stateOut = out(UInt(width))
  val signedOut = out(SInt(width))
  val pipelineOut = out(UInt(width))

  domain:
    val state = Reg(0.U(width))
    when(enable):
      state := unsignedIn
    stateOut := state

  val signedResult = (signedIn + 1.S(width)).resizeChecked(16)
  signedOut := signedResult
  val converted = unsignedIn.toSigned
  val reinterpreted = unsignedIn.reinterpretSigned
  val restored = signedIn.toUnsigned

  val packetType = Struct(
    "Packet",
    StructField("data", UInt(width)),
    StructField("valid", Bool)
  )
  val packet = wire(packetType)
  val packetRegister = Reg(packet)
  packet := packetRegister

  val matrixType = Vec(SInt(8), 2, 4, width)
  val matrix = wire(matrixType)
  val element = matrix.at(0, 0, 0)
  val reshaped = matrix.flatten.reshape(8, width)
  val mapped = matrix.map(value => value + 1.S(8))
  val zipped = matrix.zip(mapped)
  val reduced = matrix.reduce((left, right) => left + right)

  generate(width): _ =>
    V03Evidence.consume("symbolic-generate")
  loop(LoopBound.Symbolic(width, maximum = 64)): _ =>
    V03Evidence.consume("bounded-loop")

  val memory = Mem(
    element = UInt(32),
    depth = width,
    readLatency = 1,
    readUnderWrite = ReadUnderWrite.OldData,
    ordering = MemoryOrdering.Ordered,
    domain = domain
  )
  val memoryData = memory.read(unsignedIn)
  memory.write(unsignedIn, memoryData, 15.U(4))

  val external = ExternalOp[UInt, UInt](
    name = "crc32",
    outputType = UInt(32),
    contract = ExternalContract(
      latency = 1,
      initiationInterval = 1,
      effect = Effect.Pure,
      models = Set(ModelAvailability.Simulation, ModelAvailability.Synthesis),
      domain = domain
    )
  )
  val externalResult = external(unsignedIn)

  val supply = 1.0.volts
  val reference = 0.8.volts
  val totalVoltage = supply + reference
  val resistance = 50.0.ohms

  val decoded = decodeEnum(bitsIn, UnifiedState.Error)
  val control = FsmDefinition[UnifiedState]("unified-control")
  fsm(
    definition = control,
    initial = UnifiedState.Idle,
    encoding = FsmEncoding.Compact,
    illegalState = IllegalStatePolicy.RecoverToInitial
  ): machine =>
    machine.state(UnifiedState.Idle): state =>
      state.on(enable)(UnifiedState.Active)
    machine.state(UnifiedState.Active): state =>
      state.after(2)(UnifiedState.Complete)
    machine.state(UnifiedState.Complete): state =>
      state.terminal()
    machine.state(UnifiedState.Error): state =>
      state.terminal()
    machine.parallel("status"):
      V03Evidence.consume("parallel")
    machine.boundedCallStack(4)

  val localSource = interfacePort(
    LocalLink.definition,
    LocalLink.sourceRole,
    "localSource",
    domain
  )
  val localSink = interfacePort(
    LocalLink.definition,
    LocalLink.sinkRole,
    "localSink",
    domain
  )
  localSource.connectExact(localSink)
  val localInverse = localSource.inverted
  val inverseAccess = localInverse.role.access
  val localMonitor = localSource.monitorView
  localMonitor.observeMember("payload")
  val localArray = interfaceArray(
    LocalLink.definition,
    LocalLink.sourceRole,
    width,
    "localArray",
    domain
  )

  val externalSource = interfacePort(
    UnifiedLink.definition,
    UnifiedLink.sourceRole,
    "externalSource",
    domain
  )
  val externalSink = interfacePort(
    UnifiedLink.definition,
    UnifiedLink.sinkRole,
    "externalSink",
    domain
  )
  externalSource.connectExact(externalSink)
  val reusable = instance(new ReusableV03Pipeline)
  val explicitAdapter = instance(new ExplicitV03Adapter)

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

  val openDrain = digitalInout(
    Bits(1),
    DriveMode.openDrain,
    InoutPlacement.HierarchyPassThrough,
    ResolutionProfile.PortableBoundaryOnly,
    "openDrain"
  )
  openDrain.driveLow(enable)
  openDrain.highZ()
  val openDrainCarrier = openDrain.split
  val gpioPad = padAdapter(gpio, "GENERIC_GPIO_PAD")

  val vinP = terminal(Electrical, "vinP")
  val vinN = terminal(Electrical, "vinN")
  vinP.connectView.connectTo(vinN.connectView)
  val sensed = vinP.senseView.potential
  vinN.contributeView.contribute(0.0.real)

  val analogSource = AnalogSignal.source[VoltageDimension]("analogSource", "voltage")
  analogSource.driveAnalog(1.0.real)
  val bridge = BridgeContract(
    sampleTime = 1.0e-9.seconds,
    threshold = Some(0.5.volts),
    hysteresis = Some(0.02.volts),
    quantization = QuantizationPolicy.RoundNearest,
    models = Set(ModelAvailability.Simulation, ModelAvailability.Formal),
    provenance = "public-api-v0.3"
  )
  val sampled = MixedSignalBridge.sample(vinP.senseView, UInt(12), bridge)
  ConservativeSignalBridge.senseToSignal(vinP.senseView, analogSource, bridge)

  val policy = PipelinePolicy(
    latency = Latency.Exact(2),
    throughput = Throughput.EveryCycle,
    target = Some(500.MHz),
    ready = ReadyPath.Registered,
    envelopes = Seq(ParameterEnvelope(width, minimum = 8, maximum = 64))
  )
  val transaction = Txn(ArithmeticInput(unsignedIn, restored, tag))
  val scheduled = pipe(transaction, policy): current =>
    ArithmeticResult(stage(current.left + current.right), current.tag)
  val validResult = pipe(Valid(unsignedIn), policy): payload =>
    stage(payload + payload)
  val streamInput = Stream(unsignedIn)
  val streamResult = pipe(streamInput, policy): payload =>
    sameStage:
      payload + payload
  val delayed = scheduled.delay(1)
  val schedule = inspectSchedule(scheduled, "unified", policy)

  val fixed = FixedLatencyOperator[UInt, UInt](
    "fixed",
    UInt(32),
    ExternalContract(
      latency = 2,
      initiationInterval = 1,
      effect = Effect.Pure,
      models = Set(ModelAvailability.Simulation, ModelAvailability.Synthesis),
      domain = domain
    )
  )
  val fixedStream = fixed(streamInput)
  val variable = VariableLatencyOperator[UInt, UInt](
    "variable",
    UInt(width),
    VariableLatencyContract(
      minimumLatency = 2,
      maximumLatency = 12,
      capacity = 4,
      initiationInterval = 1,
      effect = Effect.Pure,
      models = Set(ModelAvailability.Simulation, ModelAvailability.Synthesis),
      domain = domain
    )
  )
  val variableStream = variable(streamInput)

  pipelineOut := scheduled.value.data

  val quality = EmitQuality(
    temporaries = TemporaryPolicy.InlineSafe,
    naming = NamingPolicy.Semantic,
    checks = CheckProfile.Release,
    waivers = Seq.empty
  )
  val options = EmitOptions(
    backend = Backend.Auto,
    digitalProfile = DigitalProfile.Synthesis,
    interfaceLayout = InterfaceLayoutPolicy(InterfaceLayout.PortableFlattened),
    quality = quality
  )
  val sourceSpan = SourceSpan("UnifiedV03.scala", 1, 1, 1, 10)
  val report = DesignReport(
    designKind = DesignKind.MixedSignal,
    selectedBackend = Backend.VerilogAMS,
    digitalProfile = Some(DigitalProfile.Simulation),
    interfaceAbi = Vector(
      InterfaceAbiEntry(
        "localSource.payload",
        "localSource_payload",
        "source",
        "master",
        "UInt(32)",
        domain.name
      )
    ),
    sourceMap = Vector(SourceMapEntry("UnifiedPublicApiV03", sourceSpan)),
    schedules = Vector(schedule)
  )
  val emission = Emission(Vector.empty, report)

  V03Evidence.consume(
    converted,
    reinterpreted,
    packet,
    element,
    reshaped,
    zipped,
    reduced,
    memoryData,
    externalResult,
    totalVoltage,
    resistance,
    decoded,
    UnifiedState.canonical,
    inverseAccess,
    localMonitor,
    localArray,
    reusable,
    explicitAdapter,
    gpioRead,
    gpioCarrier,
    openDrainCarrier,
    gpioPad,
    sensed,
    sampled,
    validResult,
    streamResult,
    delayed,
    fixedStream,
    variableStream,
    options,
    emission,
    LocalLink.monitorRole
  )

object V03Evidence:
  def consume(values: Any*): Unit = values.foreach(_ => ())
