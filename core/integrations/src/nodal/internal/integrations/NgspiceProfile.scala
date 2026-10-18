package nodal.internal.integrations

import java.nio.file.Path
import java.time.Duration

/** Consumer pin generated from the backend catalog; it does not define language eligibility. */
private[nodal] final case class NgspiceProfile(
    id: String,
    version: String,
    executableSha256: String,
    platform: String,
    catalogSha256: String
)

private[nodal] final case class NgspiceSimulateRequest(
    executable: Path,
    evidenceDirectory: Path,
    profileId: String,
    compiled: OpenVafCompileResult,
    model: NgspiceModuleBinding,
    analysis: NgspiceAnalysis,
    options: NgspiceSolverOptions = NgspiceSolverOptions(),
    versionTimeout: Duration = Duration.ofSeconds(5),
    runTimeout: Duration = Duration.ofSeconds(30)
)

/** Separate request type prevents generalized benches from silently ignoring legacy settings. */
private[nodal] final case class NgspiceCircuitSimulateRequest(
    executable: Path,
    evidenceDirectory: Path,
    profileId: String,
    compiled: OpenVafCompileResult,
    bench: NgspiceCircuitBench,
    versionTimeout: Duration = Duration.ofSeconds(5),
    runTimeout: Duration = Duration.ofSeconds(30)
)

/** Both entry points use the same process, pin, artifact and evidence owner. */
private[integrations] final case class NgspiceRunRequest(
    executable: Path,
    evidenceDirectory: Path,
    profileId: String,
    compiled: OpenVafCompileResult,
    model: NgspiceModuleBinding | NgspiceCircuitBinding,
    analysis: NgspiceAnalysis | NgspiceCircuitAnalysis,
    options: NgspiceSolverOptions,
    versionTimeout: Duration,
    runTimeout: Duration,
    generate: () => Either[String, NgspiceGeneratedBench],
    circuit: Option[NgspiceCircuitBench]
)

private[nodal] enum NgspiceOutcome(val diagnosticCode: String):
  case Simulated extends NgspiceOutcome("NODAL-NGSPICE-000")
  case InvalidRequest extends NgspiceOutcome("NODAL-NGSPICE-001")
  case ToolUnavailable extends NgspiceOutcome("NODAL-NGSPICE-002")
  case UnsupportedProfile extends NgspiceOutcome("NODAL-NGSPICE-003")
  case ProfileMismatch extends NgspiceOutcome("NODAL-NGSPICE-004")
  case InvalidCompileArtifact extends NgspiceOutcome("NODAL-NGSPICE-005")
  case LoadFailure extends NgspiceOutcome("NODAL-NGSPICE-006")
  case DeckError extends NgspiceOutcome("NODAL-NGSPICE-007")
  case Nonconvergence extends NgspiceOutcome("NODAL-NGSPICE-008")
  case LaunchFailure extends NgspiceOutcome("NODAL-NGSPICE-009")
  case Timeout extends NgspiceOutcome("NODAL-NGSPICE-010")
  case Interrupted extends NgspiceOutcome("NODAL-NGSPICE-011")
  case CaptureFailure extends NgspiceOutcome("NODAL-NGSPICE-012")
  case SimulatorError extends NgspiceOutcome("NODAL-NGSPICE-013")
  case InvalidRaw extends NgspiceOutcome("NODAL-NGSPICE-014")
  case EvidenceFailure extends NgspiceOutcome("NODAL-NGSPICE-015")

private[nodal] final case class NgspiceIdentity(
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
    model: NgspiceModuleBinding | NgspiceCircuitBinding,
    analysis: NgspiceAnalysis | NgspiceCircuitAnalysis,
    options: NgspiceSolverOptions,
    deckSha256: Option[String],
    startupSha256: String,
    arguments: Vector[String],
    environment: Map[String, String],
    platform: String,
    versionTimeout: String,
    runTimeout: String,
    requestSha256: Option[String],
    circuit: Option[NgspiceCircuitBench] = None
)

private[nodal] final case class NgspiceRawArtifact(path: Path, sha256: String, size: Long)

/** Populated expected model-dependent output proves execution, not numerical equivalence. */
private[nodal] final case class NgspiceSimulationResult(
    outcome: NgspiceOutcome,
    message: String,
    identity: NgspiceIdentity,
    compiled: OpenVafCompileResult,
    evidenceDirectory: Option[Path] = None,
    versionProbe: Option[ToolProcessResult] = None,
    simulation: Option[ToolProcessResult] = None,
    raw: Option[NgspiceRawArtifact] = None,
    plot: Option[NgspiceRawPlot] = None
):
  def diagnosticCode: String = outcome.diagnosticCode
