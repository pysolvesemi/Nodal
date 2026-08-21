package nodal

/** Public HDL backend selection frozen by Nodal public API v0.1. */
enum Backend:
  case VerilogA, VerilogAMS

/** Backend-neutral emission options. Additive fields may be introduced compatibly. */
final case class EmitOptions(backend: Backend = Backend.VerilogAMS)

/** One generated source file, held in memory until the caller chooses where to write it. */
final case class EmittedFile(path: String, content: String)

/** Deterministically ordered generated sources. */
final case class Emission(files: Vector[EmittedFile])

/** Stable public compiler entry point. Its implementation is intentionally deferred. */
object Nodal:
  def emit(top: => Module, options: EmitOptions = EmitOptions()): Emission =
    CandidateRuntime.statement(() => top, options)
    Emission(Vector.empty)
