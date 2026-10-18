package nodal.internal.integrations

import java.nio.file.Path
import java.time.Duration

/** Consumer facts generated from the backend catalog, not Nodal language admission. */
private[nodal] final case class OpenVafProfile(
    id: String,
    version: String,
    executableSha256: String,
    platform: String,
    compileArguments: Vector[String],
    catalogSha256: String,
    observations: Vector[OpenVafObservation] = Vector.empty
)

/** An exact historical witness observation; it never determines a new process outcome. */
private[nodal] final case class OpenVafObservation(
    id: String,
    modelSha256: String,
    executableSha256: String,
    version: String,
    outcome: String,
    description: String,
    compileArguments: Option[Vector[String]] = None
)

private[nodal] final case class OpenVafSourceMapBinding(modelSha256: String)

private[nodal] final case class OpenVafProvenance(
    sourceRevision: Option[String] = None,
    normalizedMlirSha256: Option[String] = None
)

private[nodal] final case class OpenVafCompileRequest(
    modelBytes: Vector[Byte],
    logicalFilename: String,
    executable: Path,
    evidenceDirectory: Path,
    profileId: String,
    languageProfile: String = "verilog-a",
    versionTimeout: Duration = Duration.ofSeconds(5),
    compileTimeout: Duration = Duration.ofSeconds(30),
    sourceMap: Option[OpenVafSourceMapBinding] = None,
    provenance: OpenVafProvenance = OpenVafProvenance()
)

private[nodal] enum OpenVafOutcome(val diagnosticCode: String):
  case Compiled extends OpenVafOutcome("NODAL-OPENVAF-000")
  case InvalidRequest extends OpenVafOutcome("NODAL-OPENVAF-001")
  case ToolUnavailable extends OpenVafOutcome("NODAL-OPENVAF-002")
  case UnsupportedProfile extends OpenVafOutcome("NODAL-OPENVAF-003")
  case ProfileMismatch extends OpenVafOutcome("NODAL-OPENVAF-004")
  case LaunchFailure extends OpenVafOutcome("NODAL-OPENVAF-005")
  case Timeout extends OpenVafOutcome("NODAL-OPENVAF-006")
  case CompilerCrash extends OpenVafOutcome("NODAL-OPENVAF-007")
  case CompilerError extends OpenVafOutcome("NODAL-OPENVAF-008")
  case InvalidOutput extends OpenVafOutcome("NODAL-OPENVAF-009")
  case MalformedModel extends OpenVafOutcome("NODAL-OPENVAF-010")
  case Interrupted extends OpenVafOutcome("NODAL-OPENVAF-011")
  case CaptureFailure extends OpenVafOutcome("NODAL-OPENVAF-012")
  case EvidenceFailure extends OpenVafOutcome("NODAL-OPENVAF-013")

private[nodal] final case class OpenVafIdentity(
    adapterVersion: String,
    modelSha256: String,
    logicalFilename: String,
    languageProfile: String,
    profileId: String,
    profileSha256: Option[String],
    catalogSha256: Option[String],
    executable: String,
    executableSha256: Option[String],
    version: Option[String],
    compileArguments: Vector[String],
    environment: Map[String, String],
    platform: String,
    versionTimeout: String,
    compileTimeout: String,
    sourceMapBindingSha256: Option[String],
    requestSha256: Option[String],
    provenance: OpenVafProvenance
)

private[nodal] final case class OpenVafArtifact(path: Path, sha256: String, size: Long)

private[nodal] final case class OpenVafCompileResult(
    outcome: OpenVafOutcome,
    message: String,
    identity: OpenVafIdentity,
    evidenceDirectory: Option[Path] = None,
    versionProbe: Option[ToolProcessResult] = None,
    compilation: Option[ToolProcessResult] = None,
    diagnostics: Vector[OpenVafDiagnostic] = Vector.empty,
    sourceMapping: OpenVafSourceMapping = OpenVafSourceMapping.NotRequested,
    artifact: Option[OpenVafArtifact] = None,
    historicalObservations: Vector[OpenVafObservation] = Vector.empty
):
  def diagnosticCode: String = outcome.diagnosticCode
