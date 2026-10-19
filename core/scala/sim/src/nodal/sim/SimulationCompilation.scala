package nodal.sim

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Duration
import scala.util.control.NonFatal

import nodal.*
import nodal.internal.bridge.*
import nodal.internal.integrations.*

/** A selected electrical boundary handle, evaluated only during source capture. */
final class PortSelection private[sim] (val label: String, private[sim] val value: AnyRef)
object PortSelection:
  def apply(label: String, node: Node[Electrical.type]): PortSelection =
    new PortSelection(label, node)
  def apply(label: String, terminal: Terminal[Electrical.type]): PortSelection =
    new PortSelection(label, terminal)

final case class SimulationError(code: String, message: String)

/** Immutable source-correlated port; only its owning captured model can bind it. */
final class ModelPort private[sim] (
    val label: String,
    val semanticPath: String,
    private[sim] val owner: Object
)

final class SimulationSource private[sim] (
    private[sim] val snapshot: ConstructionSnapshot,
    private[sim] val operationKinds: Vector[String],
    val ports: Vector[ModelPort],
    private[sim] val owner: Object
):
  val semanticPath: String = snapshot.root
  val canonicalConstruction: String = ReproducibilityContract.canonicalSnapshot(snapshot)
  def port(label: String): Either[SimulationError, ModelPort] =
    ports.find(_.label == label).toRight(SimulationError("SIM-PORT", s"unknown port '$label'"))

object SimulationSource:
  private[sim] def validateOperations(kinds: Vector[String]): Unit =
    val supported = Set(
      "metadata",
      "analog-contribute",
      "analog-assignment",
      "node-connect",
      "terminal-connect",
      "node-array-connect",
      "instance-node-array",
      "deferred-node-array"
    )
    require(
      kinds.forall(supported.contains),
      "source operation has no supported simulator lowering: " +
        kinds.filterNot(supported.contains).distinct.mkString(", ")
    )

  def capture[M <: Module](top: => M)(
      select: M => Vector[PortSelection]
  ): Either[SimulationError, SimulationSource] =
    try
      var labels = Vector.empty[String]
      val captured = ConstructionKernel.capture(top, EmitOptions(backend = Backend.VerilogA)):
        module =>
          val selected = select(module)
          require(
            selected.nonEmpty && selected.size <= 32,
            "select between one and 32 electrical ports"
          )
          labels = selected.map(_.label)
          require(
            labels.forall(value => value.nonEmpty && value.length <= 128),
            "invalid port label"
          )
          require(labels.distinct.size == labels.size, "duplicate port label")
          selected.map(_.value)
      require(
        captured.snapshot.analogSemantics.unsupported.isEmpty,
        "simulation cannot discard unsupported source effects: " +
          captured.snapshot.analogSemantics.unsupported.mkString(", ")
      )
      require(
        captured.snapshot.analogSemantics.equations.isEmpty &&
          captured.snapshot.analogSemantics.contributions.isEmpty,
        "source equation/contribution inventories have no executable simulator lowering"
      )
      validateOperations(captured.operationKinds)
      val root = captured.snapshot.modules.find(_.path == captured.snapshot.root).get
      val kinds = Set("analog-input", "analog-output", "analog-inout", "conservative-terminal")
      val boundaries = root.declarations.filter(value => kinds.contains(value.kind))
      require(
        boundaries.forall(_.conservativeDimensions.isEmpty),
        "array boundaries are outside the scalar simulation profile"
      )
      require(
        captured.references.map(_.path).distinct.size == captured.references.size,
        "duplicate selected port"
      )
      require(
        captured.references.forall(reference =>
          reference.owner == root.path && kinds.contains(reference.kind)
        ),
        "selection must name a root electrical boundary"
      )
      require(
        captured.references.map(_.path).toSet == boundaries.map(_.path).toSet,
        "select every root electrical boundary exactly once"
      )
      val owner = new Object
      val ports = labels.zip(captured.references).map((label, reference) =>
        new ModelPort(label, reference.path, owner)
      )
      Right(new SimulationSource(captured.snapshot, captured.operationKinds, ports, owner))
    catch
      case NonFatal(error) => Left(SimulationError(
          "SIM-CAPTURE",
          Option(error.getMessage).getOrElse(error.getClass.getName)
        ))

/** Named emission is preserved unless finite whole-graph materialization is selected explicitly. */
enum AnalogHierarchyMode:
  case PreserveNamed, MaterializeScalar

/** Explicit tools and evidence location; no PATH lookup or hidden profile fallback. */
final case class CompilationTools(
    nodalc: Path,
    translator: Path,
    openVaf: Path,
    compilerProfile: String,
    evidenceRoot: Path,
    timeout: Duration = Duration.ofSeconds(30),
    sourceRevision: Option[String] = None,
    hierarchy: AnalogHierarchyMode = AnalogHierarchyMode.PreserveNamed
)

final case class CommandEvidence(
    command: Vector[String],
    standardOutput: String,
    standardError: String,
    exitCode: Option[Int],
    failure: Option[String]
)

final case class NativeToolIdentity(executable: String, sha256: String)

final case class CompilationEvidence(
    directory: Path,
    sourceMlirSha256: Option[String],
    nativeTools: Vector[NativeToolIdentity],
    native: Vector[CommandEvidence],
    compilerOutcome: Option[String],
    compilerReceipt: Option[Path],
    modelSha256: Option[String],
    artifactSha256: Option[String],
    compilerProfile: String,
    diagnostics: Vector[String]
)

final class CompiledSimulationModel private[sim] (
    val source: SimulationSource,
    val moduleName: String,
    val portsInTargetOrder: Vector[ModelPort],
    val evidence: CompilationEvidence,
    private[nodal] val compiled: OpenVafCompileResult
)

final case class CompilationResult(
    model: Option[CompiledSimulationModel],
    error: Option[SimulationError],
    evidence: Option[CompilationEvidence]
):
  def succeeded: Boolean = model.nonEmpty

object SimulationCompiler:
  private def digest(value: String): String =
    OpenVafDiagnosticMapper.sha256(value.getBytes(StandardCharsets.UTF_8).toVector)

  private def toolIdentity(path: Path): NativeToolIdentity =
    require(
      path.isAbsolute && Files.isRegularFile(path) && Files.isExecutable(path),
      "native tool must be an executable absolute regular file"
    )
    require(Files.size(path) <= 512L * 1024 * 1024, "native tool exceeds identity size bound")
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path)
    try
      val buffer = new Array[Byte](65536)
      var size = input.read(buffer)
      var total = 0L
      while size >= 0 do
        total += size
        require(total <= 512L * 1024 * 1024, "native tool grew beyond identity size bound")
        hash.update(buffer, 0, size)
        size = input.read(buffer)
    finally input.close()
    NativeToolIdentity(path.toString, hash.digest().map(value => f"${value & 0xff}%02x").mkString)

  private def quoted(value: String): String = "\"" + value.flatMap {
    case '\"' => "\\\""
    case '\\' => "\\\\"
    case char if char < ' ' => f"\\u${char.toInt}%04x"
    case char => char.toString
  } + "\""

  def compile(source: SimulationSource, tools: CompilationTools): CompilationResult =
    var evidence: Option[CompilationEvidence] = None
    try
      require(
        source.snapshot.analogSemantics.unsupported.isEmpty,
        "simulation cannot discard unsupported source effects: " +
          source.snapshot.analogSemantics.unsupported.mkString(", ")
      )
      require(
        source.snapshot.analogSemantics.equations.isEmpty &&
          source.snapshot.analogSemantics.contributions.isEmpty,
        "source equation/contribution inventories have no executable simulator lowering"
      )
      SimulationSource.validateOperations(source.operationKinds)
      require(tools.evidenceRoot.isAbsolute, "evidenceRoot must be absolute")
      require(!tools.timeout.isZero && !tools.timeout.isNegative, "timeout must be positive")
      Files.createDirectories(tools.evidenceRoot)
      val directory = Files.createTempDirectory(tools.evidenceRoot, "compile-")
      def write(name: String, value: String): Unit =
        val _ = Files.writeString(
          directory.resolve(name),
          value,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW
        )
      write("construction.json", source.canonicalConstruction)
      write(
        "capture-operations.json",
        source.operationKinds.map(quoted).mkString("[", ",", "]") + "\n"
      )
      evidence = Some(CompilationEvidence(
        directory,
        None,
        Vector.empty,
        Vector.empty,
        None,
        None,
        None,
        None,
        tools.compilerProfile,
        Vector.empty
      ))
      val document =
        ScalaToMlirBridge.fromSnapshot(source.snapshot, Backend.VerilogA, sourceProvenance = true)
      require(
        document.electricalInterface.nonEmpty,
        "source has no supported scalar electrical interface"
      )
      val abi = document.electricalInterface.get
      evidence = evidence.map(_.copy(sourceMlirSha256 = Some(document.sha256)))

      write("source.mlir", document.text)
      val nativeTools = Vector(toolIdentity(tools.nodalc), toolIdentity(tools.translator))
      write(
        "native-tools.json",
        nativeTools.map(value =>
          s"{${quoted("executable")}:${quoted(value.executable)},${quoted("sha256")}:${quoted(value.sha256)}}"
        ).mkString("[", ",", "]") + "\n"
      )
      evidence = evidence.map(_.copy(nativeTools = nativeTools))
      def native(
          input: NodalMlirDocument,
          executable: Path,
          arguments: Vector[String],
          name: String
      ): Either[SimulationError, String] =
        val result = NativeCompilerClient.run(
          input,
          NativeCompilerRequest(executable, arguments, directory, timeout = tools.timeout)
        )
        val command = result match
          case _: NativeCompilerSuccess => CommandEvidence(
              result.command,
              result.standardOutput,
              result.standardError,
              Some(0),
              None
            )
          case failure: NativeCompilerFailure => CommandEvidence(
              result.command,
              result.standardOutput,
              result.standardError,
              failure.exitCode,
              Some(failure.diagnostic.toString)
            )
        evidence = evidence.map(value => value.copy(native = value.native :+ command))
        write(s"$name.stdout", result.standardOutput)
        write(s"$name.stderr", result.standardError)
        write(s"$name.command.json", command.command.map(quoted).mkString("[", ",", "]") + "\n")
        require(
          nativeTools.exists(_ == toolIdentity(executable)),
          "native executable changed during compilation"
        )
        result match
          case success: NativeCompilerSuccess => Right(success.normalizedMlir)
          case failure: NativeCompilerFailure =>
            Left(SimulationError(failure.diagnostic.code, failure.diagnostic.toString))
      val emitted =
        for
          normalized <- native(
            document,
            tools.nodalc,
            Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
            "nodalc"
          )
          _ = write("normalized.mlir", normalized)
          target <- native(
            NodalMlirDocument(
              ScalaToMlirBridge.Schema,
              ScalaToMlirBridge.Version,
              normalized,
              digest(normalized)
            ),
            tools.translator,
            Vector(tools.hierarchy match
              case AnalogHierarchyMode.PreserveNamed => "--nodal-to-verilog-a"
              case AnalogHierarchyMode.MaterializeScalar =>
                "--nodal-to-verilog-a-scalar-hierarchy"),
            "translator"
          )
        yield (normalized, target)
      emitted match
        case Left(error) => CompilationResult(None, Some(error), evidence)
        case Right((normalized, target)) =>
          val expected = s"module ${abi.moduleName}(${abi.ports.map(_.name).mkString(", ")});"
          val headers = target.linesIterator.map(
            _.trim
          ).filter(_.startsWith(s"module ${abi.moduleName}(")).toVector
          require(
            headers == Vector(expected),
            "emitted root terminal ABI differs from canonical source interface"
          )
          val byPath = source.ports.map(port => port.semanticPath -> port).toMap
          require(
            abi.ports.map(_.path).toSet == byPath.keySet,
            "selected source ABI differs from target interface"
          )
          write("model.va", target)
          val modelHash = digest(target)
          val receipt = OpenVafCompiler.compile(OpenVafCompileRequest(
            target.getBytes(StandardCharsets.UTF_8).toVector,
            "model.va",
            tools.openVaf,
            directory.resolve("openvaf"),
            tools.compilerProfile,
            compileTimeout = tools.timeout,
            sourceMap = Some(OpenVafSourceMapBinding(modelHash)),
            provenance = OpenVafProvenance(tools.sourceRevision, Some(digest(normalized)))
          ))
          evidence = evidence.map(_.copy(
            compilerOutcome = Some(receipt.outcome.toString),
            compilerReceipt = receipt.evidenceDirectory,
            modelSha256 = Some(modelHash),
            artifactSha256 = receipt.artifact.map(_.sha256),
            diagnostics = receipt.diagnostics.map(_.toString)
          ))
          if receipt.outcome != OpenVafOutcome.Compiled then
            CompilationResult(
              None,
              Some(SimulationError(receipt.diagnosticCode, receipt.message)),
              evidence
            )
          else
            CompilationResult(
              Some(new CompiledSimulationModel(
                source,
                abi.moduleName,
                abi.ports.map(port => byPath(port.path)),
                evidence.get,
                receipt
              )),
              None,
              evidence
            )
    catch
      case NonFatal(error) => CompilationResult(
          None,
          Some(SimulationError(
            "SIM-COMPILE",
            Option(error.getMessage).getOrElse(error.getClass.getName)
          )),
          evidence
        )
