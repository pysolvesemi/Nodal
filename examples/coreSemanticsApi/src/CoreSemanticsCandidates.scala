package examples.coresemantics

import nodal.*

enum ControlState derives HwEnum:
  case Idle, Load, Run, Error

object ControlState:
  val canonical: EnumEncoding[ControlState] = enumEncoding(
    Idle -> BigInt(0),
    Load -> BigInt(1),
    Run -> BigInt(3),
    Error -> BigInt(7),
  )

final class CoreSemanticsCandidates extends Module:
  val domain = ClockDomain.required("core")
  val width = param(8.integer)

  val unsignedIn = in(UInt(width))
  val signedIn = in(SInt(width))
  val bitsIn = in(Bits(width))
  val condition = in(Bool)

  val unsignedOut = out(UInt(width))
  val signedOut = out(SInt(width))

  // Ordinary Scala values and loops remain elaboration-only.
  val elaborationWidths = Seq(8, 16, 32)
  for currentWidth <- elaborationWidths do
    val temporary = wire(UInt(currentWidth))
    temporary := 0.U(currentWidth)

  // Symbolic structural generation is explicit and distinct from hardware iteration.
  generate(width): _ =>
    CandidateSmoke.consume("symbolic-generate")

  loop(LoopBound.Static(4)): _ =>
    CandidateSmoke.consume("bounded-hardware-loop")

  loop(LoopBound.Symbolic(width, maximum = 32)): _ =>
    CandidateSmoke.consume("bounded-symbolic-hardware-loop")

  // Signedness and narrowing intent are explicit.
  val signedSum = signedIn + 1.S(width)
  val shiftedSigned = signedSum >> 1
  val shiftedUnsigned = unsignedIn >> 1
  val narrowedSigned = signedSum.resizeChecked(8)
  val wrappedUnsigned = unsignedIn.wrap(8)
  val saturatedUnsigned = unsignedIn.saturate(8)
  val convertedSigned = unsignedIn.toSigned
  val reinterpretedSigned = unsignedIn.reinterpretSigned
  val convertedUnsigned = signedIn.toUnsigned

  signedOut := narrowedSigned
  unsignedOut := convertedUnsigned

  // Directionless aggregates and ranked structural values.
  val packetType = Aggregate(
    "Packet",
    AggregateField("data", UInt(width)),
    AggregateField("flag", Bool),
  )
  val packet = wire(packetType)
  packet := packet

  val matrixType = Vec(SInt(8), 2, 3, width)
  val matrix = wire(matrixType)
  val element = matrix.at(0, 1, 0)
  val flattened = matrix.flatten
  val reshaped = flattened.reshape(6, width)
  val mapped = matrix.map(value => value + 1.S(8))
  val zipped = matrix.zip(mapped)
  val reduced = matrix.reduce((left, right) => left + right)
  CandidateSmoke.consume(element, reshaped, zipped, reduced)

  val validValue = Valid(unsignedIn)
  val streamValue = Stream(unsignedIn)
  CandidateSmoke.consume(validValue, streamValue)

  // Target layout is policy evidence, not source storage semantics.
  val portableLayout = LayoutPolicy(TargetLayout.PortableVerilogFlat)
  val systemVerilogLayout = LayoutPolicy(TargetLayout.SystemVerilogUnpacked)
  CandidateSmoke.consume(portableLayout, systemVerilogLayout)

  // Explicit memory and external-operation contracts.
  val memory = Mem(
    element = UInt(32),
    depth = width,
    readLatency = 1,
    readUnderWrite = ReadUnderWrite.OldData,
    ordering = MemoryOrdering.Ordered,
    domain = domain,
  )
  val memoryData = memory.read(unsignedIn)
  memory.write(unsignedIn, memoryData, 15.U(4))

  val crc = ExternalOp[UInt, UInt](
    name = "crc32",
    outputType = UInt(32),
    contract = ExternalContract(
      latency = 1,
      initiationInterval = 1,
      effect = Effect.Pure,
      models = Set(ModelAvailability.Simulation, ModelAvailability.Synthesis),
      domain = domain,
    ),
  )
  val crcValue = crc(unsignedIn)
  CandidateSmoke.consume(crcValue)

  // Dimension-safe quantity candidate.
  val supply = 1.2.volts
  val reference = 0.8.volts
  val totalVoltage = supply + reference
  val resistance = 10.0.ohms
  CandidateSmoke.consume(totalVoltage, resistance)

  // Materialization, naming, and mandatory-check policy candidates.
  val namedResult = (unsignedIn + 1.U(width)).named("next_count").keep("waveform")
  val quality = EmitQuality(
    temporaries = TemporaryPolicy.InlineSafe,
    naming = NamingPolicy.Semantic,
    checks = CheckProfile.Release,
    waivers = Seq(CheckWaiver("WAIVE-001", "external reviewed exception", "portability")),
  )
  CandidateSmoke.consume(namedResult, quality, shiftedSigned, shiftedUnsigned, wrappedUnsigned, saturatedUnsigned, convertedSigned, reinterpretedSigned, bitsIn)

  // Native Scala enum and typed statechart candidates.
  val decoded = decodeEnum(bitsIn, ControlState.Error)
  CandidateSmoke.consume(decoded, ControlState.canonical)

  val control = FsmDefinition[ControlState]("control")
  fsm(
    definition = control,
    initial = ControlState.Idle,
    encoding = FsmEncoding.Compact,
    illegalState = IllegalStatePolicy.RecoverToInitial,
  ): machine =>
    machine.state(ControlState.Idle): state =>
      state.entry(CandidateSmoke.consume("entry"))
      state.on(condition)(ControlState.Load)

    machine.state(ControlState.Load): state =>
      state.active(CandidateSmoke.consume("active"))
      state.after(2)(ControlState.Run)

    machine.state(ControlState.Run): state =>
      state.on(condition, TransitionMode.Priority)(ControlState.Error)

    machine.state(ControlState.Error): state =>
      state.terminal()

    machine.submachine("nested", control)
    machine.parallel("parallel-region"):
      CandidateSmoke.consume("parallel")
    machine.boundedCallStack(depth = 4)

object CandidateSmoke:
  def consume(values: Any*): Unit = values.foreach(_ => ())
