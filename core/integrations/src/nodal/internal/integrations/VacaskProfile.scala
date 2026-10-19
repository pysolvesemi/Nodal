package nodal.internal.integrations

import java.nio.file.Path
import java.time.Duration

/** Optional consumer pin, independent of language eligibility and compiler selection. */
private[nodal] final case class VacaskProfile(
    id: String,
    version: String,
    executableSha256: String,
    platform: String,
    catalogSha256: String,
    runtimeLibraries: Vector[(String, String)] = Vector.empty
)

private[nodal] final case class VacaskCircuitSimulateRequest(
    executable: Path,
    evidenceDirectory: Path,
    profileId: String,
    compiled: OpenVafCompileResult,
    bench: AnalogCircuitBench,
    versionTimeout: Duration = Duration.ofSeconds(5),
    runTimeout: Duration = Duration.ofSeconds(30),
    runtimeLibraryDirectories: Vector[Path] = Vector.empty
)

private[nodal] enum VacaskOutcome(val diagnosticCode: String):
  case Simulated extends VacaskOutcome("NODAL-VACASK-000")
  case InvalidRequest extends VacaskOutcome("NODAL-VACASK-001")
  case ToolUnavailable extends VacaskOutcome("NODAL-VACASK-002")
  case UnsupportedProfile extends VacaskOutcome("NODAL-VACASK-003")
  case ProfileMismatch extends VacaskOutcome("NODAL-VACASK-004")
  case InvalidCompileArtifact extends VacaskOutcome("NODAL-VACASK-005")
  case LoadFailure extends VacaskOutcome("NODAL-VACASK-006")
  case DeckError extends VacaskOutcome("NODAL-VACASK-007")
  case Nonconvergence extends VacaskOutcome("NODAL-VACASK-008")
  case LaunchFailure extends VacaskOutcome("NODAL-VACASK-009")
  case Timeout extends VacaskOutcome("NODAL-VACASK-010")
  case Interrupted extends VacaskOutcome("NODAL-VACASK-011")
  case CaptureFailure extends VacaskOutcome("NODAL-VACASK-012")
  case SimulatorError extends VacaskOutcome("NODAL-VACASK-013")
  case InvalidRaw extends VacaskOutcome("NODAL-VACASK-014")
  case EvidenceFailure extends VacaskOutcome("NODAL-VACASK-015")
  case ConsumerFailure extends VacaskOutcome("NODAL-VACASK-016")
  case UnsupportedCapability extends VacaskOutcome("NODAL-VACASK-017")
  case StartupConfiguration extends VacaskOutcome("NODAL-VACASK-018")

private[nodal] final case class VacaskIdentity(
    adapterVersion: String,
    profileId: String,
    profileSha256: Option[String],
    catalogSha256: Option[String],
    executable: String,
    executableSha256: Option[String],
    version: Option[String],
    compileRequestSha256: Option[String],
    modelSha256: String,
    artifactSha256: Option[String],
    artifactSize: Option[Long],
    bench: AnalogCircuitBench,
    deckSha256: Option[String],
    startupPolicy: String,
    runtimeLibraries: Vector[(String, String)],
    arguments: Vector[String],
    environment: Map[String, String],
    platform: String,
    versionTimeout: String,
    runTimeout: String,
    requestSha256: Option[String]
)

private[nodal] final case class VacaskRawArtifact(path: Path, sha256: String, size: Long)

/** Execution evidence does not establish numerical equivalence or language certification. */
private[nodal] final case class VacaskSimulationResult(
    outcome: VacaskOutcome,
    message: String,
    identity: VacaskIdentity,
    compiled: OpenVafCompileResult,
    evidenceDirectory: Option[Path] = None,
    versionProbe: Option[ToolProcessResult] = None,
    simulation: Option[ToolProcessResult] = None,
    raw: Option[VacaskRawArtifact] = None,
    plot: Option[AnalogRawPlot] = None,
    validatedPoints: Option[Int] = None
):
  def diagnosticCode: String = outcome.diagnosticCode

/** No partial aggregate escapes a failed raw validation, integrity check or evidence write. */
private[nodal] final case class VacaskStreamingSimulationResult[A](
    receipt: VacaskSimulationResult,
    schema: Option[AnalogRawSchema] = None,
    pointCount: Int = 0,
    aggregate: Option[A] = None
)
