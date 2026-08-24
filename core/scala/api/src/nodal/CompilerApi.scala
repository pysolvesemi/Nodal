package nodal

/** Public HDL backend selection frozen by Nodal public API v0.3. */
enum Backend:
  case Auto, Verilog
  case VerilogA, VerilogAMS

/** Stable design classification reported independently of backend spelling. */
enum DesignKind:
  case DigitalOnly, AnalogOnly, MixedSignal, Unsupported

/** Explicit portable-Verilog intent. These profiles do not change source semantics. */
enum DigitalProfile:
  case Synthesis, Simulation, Formal

/** Backend-neutral emission options. Additive fields may be introduced compatibly. */
final case class EmitOptions(
    backend: Backend = Backend.Auto,
    digitalProfile: DigitalProfile = DigitalProfile.Synthesis,
    interfaceLayout: InterfaceLayoutPolicy = InterfaceLayoutPolicy(
      InterfaceLayout.PortableFlattened
    ),
    quality: EmitQuality = EmitQuality()
)

/** One generated source file, held in memory until the caller chooses where to write it. */
final case class EmittedFile(path: String, content: String)

/** Stable source span used by reports and later diagnostics. */
final case class SourceSpan(
    path: String,
    line: Int,
    column: Int,
    endLine: Int,
    endColumn: Int
)

/** Mapping from one semantic path to its originating Scala span. */
final case class SourceMapEntry(semanticPath: String, source: SourceSpan)

/** Logical Interface ABI entry, independent of flattened or future native target layout. */
final case class InterfaceAbiEntry(
    logicalPath: String,
    emittedPath: String,
    role: String,
    access: String,
    dataType: String,
    domain: String
)

/** Deterministic classification, ABI, source-map, and schedule evidence. */
final case class DesignReport(
    designKind: DesignKind = DesignKind.Unsupported,
    selectedBackend: Backend = Backend.Auto,
    digitalProfile: Option[DigitalProfile] = None,
    interfaceAbi: Vector[InterfaceAbiEntry] = Vector.empty,
    sourceMap: Vector[SourceMapEntry] = Vector.empty,
    schedules: Vector[ScheduleInspection] = Vector.empty
)

/** Deterministically ordered generated sources and their semantic report. */
final case class Emission(
    files: Vector[EmittedFile],
    report: DesignReport = DesignReport()
)

/** Stable public compiler entry point. Increment 16 implements construction only. */
object Nodal:
  // Historical frozen inert return form: Emission(Vector.empty)
  def emit(top: => Module, options: EmitOptions = EmitOptions()): Emission =
    ConstructionKernel.emit(top, options)
