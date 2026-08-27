package nodal.internal.bridge

import nodal.*

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

private[nodal] final case class ReproducibilityArtifact(
    name: String,
    mediaType: String,
    sha256: String,
    bytes: Long
)

private[nodal] final case class ReproducibilityManifest(
    schema: String,
    version: Int,
    text: String,
    sha256: String
)

private[nodal] final case class ReproducibilityBundle(
    construction: String,
    sourceMlir: String,
    normalizedMlir: String,
    hdl: String,
    manifest: ReproducibilityManifest
)

/** Canonical, private artifact contract for Increment 26.
  *
  * The contract intentionally consumes only accepted construction state, canonical Nodal MLIR,
  * verified native MLIR, and transactionally emitted target text. It does not add public API or
  * make local paths, map iteration order, process IDs, timestamps, or temporary directories part of
  * artifact identity.
  */
private[nodal] object ReproducibilityContract:
  val Schema: String = "nodal.reproducibility"
  val Version: Int = 1
  val PublicApi: String = "0.3"

  private val VerificationPattern =
    raw"nodal\.verify\.([A-Za-z0-9_]+)\s*=\s*true".r

  def capture(
      top: => Module,
      nodalc: Path,
      translator: Path,
      workingDirectory: Path,
      timeout: Duration = Duration.ofSeconds(60)
  ): Either[NativeCompilerFailure, ReproducibilityBundle] =
    val snapshot = ConstructionKernel.inspect(
      top,
      EmitOptions(backend = Backend.VerilogA)
    )
    captureSnapshot(snapshot, nodalc, translator, workingDirectory, timeout)

  def captureSnapshot(
      snapshot: ConstructionSnapshot,
      nodalc: Path,
      translator: Path,
      workingDirectory: Path,
      timeout: Duration = Duration.ofSeconds(60)
  ): Either[NativeCompilerFailure, ReproducibilityBundle] =
    val source = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA)
    NativeCompilerClient.run(
      source,
      NativeCompilerRequest(
        executable = nodalc,
        arguments = Vector(
          "--pass-pipeline=builtin.module(nodal-gate-default)"
        ),
        workingDirectory = workingDirectory,
        timeout = timeout
      )
    ) match
      case failure: NativeCompilerFailure => Left(failure)
      case normalized: NativeCompilerSuccess =>
        val normalizedText = normalizeArtifact(normalized.normalizedMlir)
        val normalizedDocument = NodalMlirDocument(
          source.schema,
          source.version,
          normalizedText,
          digest(normalizedText)
        )
        NativeCompilerClient.run(
          normalizedDocument,
          NativeCompilerRequest(
            executable = translator,
            arguments = Vector("--nodal-to-verilog-a"),
            workingDirectory = workingDirectory,
            timeout = timeout
          )
        ) match
          case failure: NativeCompilerFailure => Left(failure)
          case emitted: NativeCompilerSuccess =>
            Right(
              assemble(
                snapshot,
                source.text,
                normalizedText,
                emitted.normalizedMlir,
                "verilog-a"
              )
            )

  /** Build a native-process-free contract bundle for ordering and manifest tests. */
  def describe(
      snapshot: ConstructionSnapshot,
      backend: Backend = Backend.VerilogA
  ): ReproducibilityBundle =
    val source = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
    assemble(
      snapshot,
      source.text,
      source.text,
      "",
      backendName(backend)
    )

  def canonicalSnapshot(snapshot: ConstructionSnapshot): String =
    normalizeArtifact(render(snapshotJson(snapshot)))

  private def assemble(
      snapshot: ConstructionSnapshot,
      sourceMlir: String,
      normalizedMlir: String,
      hdl: String,
      backendProfile: String
  ): ReproducibilityBundle =
    val construction = canonicalSnapshot(snapshot)
    val source = normalizeArtifact(sourceMlir)
    val normalized = normalizeArtifact(normalizedMlir)
    val target = normalizeArtifact(hdl)
    val manifestText = normalizeArtifact(
      render(
        manifestJson(
          snapshot,
          construction,
          source,
          normalized,
          target,
          backendProfile
        )
      )
    )
    ReproducibilityBundle(
      construction,
      source,
      normalized,
      target,
      ReproducibilityManifest(
        Schema,
        Version,
        manifestText,
        digest(manifestText)
      )
    )

  private def backendName(backend: Backend): String = backend match
    case Backend.Auto => "auto"
    case Backend.Verilog => "verilog"
    case Backend.VerilogA => "verilog-a"
    case Backend.VerilogAMS => "verilog-ams"

  private def manifestJson(
      snapshot: ConstructionSnapshot,
      construction: String,
      sourceMlir: String,
      normalizedMlir: String,
      hdl: String,
      backendProfile: String
  ): JsonValue =
    val artifacts = Vector(
      artifact("construction.json", "application/json", construction),
      artifact("source.mlir", "text/x-mlir", sourceMlir),
      artifact("normalized.mlir", "text/x-mlir", normalizedMlir),
      artifact(
        if backendProfile == "verilog-ams" then "output.vams" else "output.va",
        if backendProfile == "verilog-ams" then "text/x-verilog-ams" else "text/x-verilog-a",
        hdl
      )
    )
    obj(
      "schema" -> string(Schema),
      "version" -> number(Version),
      "public_api" -> string(PublicApi),
      "root" -> string(snapshot.root),
      "backend_profile" -> string(backendProfile),
      "artifacts" -> array(artifacts.map(artifactJson)),
      "shape_layout_storage" -> shapeLayoutStorage(snapshot, backendProfile),
      "materialization" -> materialization(snapshot),
      "semantic_names" -> semanticNames(snapshot),
      "expression_source_map" -> expressionSourceMap(snapshot),
      "check_inventory" -> checkInventory(sourceMlir, normalizedMlir),
      "waivers" -> waiverInventory(snapshot),
      "domain_manifest" -> domainManifest(snapshot),
      "cdc_rdc_report" -> crossingReport(snapshot)
    )

  private def artifact(
      name: String,
      mediaType: String,
      content: String
  ): ReproducibilityArtifact =
    val bytes = content.getBytes(StandardCharsets.UTF_8)
    ReproducibilityArtifact(name, mediaType, digestBytes(bytes), bytes.length.toLong)

  private def artifactJson(value: ReproducibilityArtifact): JsonValue =
    obj(
      "name" -> string(value.name),
      "media_type" -> string(value.mediaType),
      "sha256" -> string(value.sha256),
      "bytes" -> number(value.bytes)
    )

  private def shapeLayoutStorage(
      snapshot: ConstructionSnapshot,
      backendProfile: String
  ): JsonValue =
    val declarations = snapshot.modules
      .flatMap(module => module.declarations.map(module.path -> _))
      .sortBy(_._2.path)
      .map: (modulePath, declaration) =>
        obj(
          "module" -> string(modulePath),
          "semantic_path" -> string(declaration.path),
          "declaration_kind" -> string(declaration.kind),
          "data_type" -> optionalString(declaration.dataType),
          "shape" -> string(shapeOf(declaration.dataType)),
          "layout" -> string(layoutOf(declaration.dataType, backendProfile)),
          "storage" -> string(storageOf(declaration.kind))
        )
    array(declarations)

  private def shapeOf(dataType: Option[String]): String = dataType match
    case Some(value) if value.startsWith("Vec(") && value.endsWith(")") =>
      val body = value.drop(4).dropRight(1)
      val separator = body.lastIndexOf(';')
      if separator >= 0 then body.drop(separator + 1) else "ranked"
    case Some(_) => "scalar"
    case None => "connectivity"

  private def layoutOf(
      dataType: Option[String],
      backendProfile: String
  ): String =
    dataType match
      case Some(value) if value.startsWith("Vec(") =>
        if backendProfile == "verilog-a" then "flat-if-supported" else "flat-packed"
      case Some(_) => "scalar"
      case None => "not-applicable"

  private def storageOf(kind: String): String = kind match
    case "parameter" => "symbolic-parameter"
    case "register" => "register"
    case "memory" => "memory"
    case "wire" | "variable" | "analog-signal" => "signal"
    case "input" | "output" | "analog-input" | "analog-output" |
        "analog-inout" | "analog-node" | "conservative-terminal" |
        "interface-port" | "interface-array" | "digital-inout" =>
      "connectivity"
    case _ => "value"

  private def materialization(snapshot: ConstructionSnapshot): JsonValue =
    val entries = snapshot.origins
      .filter(_.kind == "expression")
      .sortBy(origin => (origin.semanticPath, origin.id))
      .map: origin =>
        obj(
          "origin_id" -> string(origin.id),
          "semantic_path" -> string(origin.semanticPath),
          "decision" -> string(if origin.inlined then "inline" else "materialize"),
          "reason" -> string(
            if origin.inlined then "semantic-origin-safe-inline"
            else "semantic-origin-retained"
          ),
          "operation" -> string(origin.operation),
          "parents" -> array(origin.parents.sorted.map(string)),
          "sink" -> optionalString(origin.sink)
        )
    array(entries)

  private def semanticNames(snapshot: ConstructionSnapshot): JsonValue =
    val declared = snapshot.names
      .sortBy(entry => (entry.semanticPath, entry.category, entry.name))
      .map: entry =>
        obj(
          "semantic_path" -> string(entry.semanticPath),
          "name" -> string(entry.name),
          "category" -> string(entry.category),
          "provenance" -> string(entry.provenance),
          "source" -> source(entry.source)
        )
    val generated = snapshot.generatedNames
      .sortBy(entry => (entry.owner, entry.category, entry.name, entry.origin))
      .map: entry =>
        obj(
          "owner" -> string(entry.owner),
          "name" -> string(entry.name),
          "category" -> string(entry.category),
          "origin" -> string(entry.origin)
        )
    obj(
      "declared" -> array(declared),
      "generated" -> array(generated)
    )

  private def expressionSourceMap(snapshot: ConstructionSnapshot): JsonValue =
    val entries = snapshot.origins
      .filter(_.kind == "expression")
      .sortBy(origin => (origin.semanticPath, origin.id))
      .map: origin =>
        obj(
          "origin_id" -> string(origin.id),
          "semantic_path" -> string(origin.semanticPath),
          "source" -> source(origin.source),
          "parents" -> array(origin.parents.sorted.map(string)),
          "sink" -> optionalString(origin.sink),
          "inlined" -> bool(origin.inlined)
        )
    array(entries)

  private def checkInventory(
      sourceMlir: String,
      normalizedMlir: String
  ): JsonValue =
    val checks = VerificationPattern
      .findAllMatchIn(sourceMlir + "\n" + normalizedMlir)
      .map(_.group(1))
      .toVector
      .distinct
      .sorted
    array(checks.map(check => obj("id" -> string(check), "required" -> bool(true))))

  private def waiverInventory(snapshot: ConstructionSnapshot): JsonValue =
    val entries = snapshot.origins
      .filter(origin => origin.operation.toLowerCase(java.util.Locale.ROOT).contains("waive"))
      .sortBy(origin => (origin.semanticPath, origin.id))
      .map: origin =>
        obj(
          "origin_id" -> string(origin.id),
          "semantic_path" -> string(origin.semanticPath),
          "operation" -> string(origin.operation),
          "source" -> source(origin.source),
          "parents" -> array(origin.parents.sorted.map(string)),
          "sink" -> optionalString(origin.sink)
        )
    array(entries)

  private def domainManifest(snapshot: ConstructionSnapshot): JsonValue =
    val entries = snapshot.modules
      .flatMap(module => module.domains.map(module.path -> _))
      .sortBy(_._2.path)
      .map: (modulePath, domain) =>
        obj(
          "module" -> string(modulePath),
          "semantic_path" -> string(domain.path),
          "name" -> string(domain.name),
          "kind" -> string(domain.kind),
          "binding" -> optionalString(domain.binding),
          "edge" -> optionalString(domain.edge),
          "reset_policy" -> optionalString(domain.resetPolicy),
          "attributes" -> attributes(domain.attributes)
        )
    array(entries)

  private def crossingReport(snapshot: ConstructionSnapshot): JsonValue =
    val originEntries = snapshot.origins
      .filter: origin =>
        val operation = origin.operation.toLowerCase(java.util.Locale.ROOT)
        operation.contains("cdc") || operation.contains("rdc") ||
        operation.contains("resetcontroller") || operation.contains("reset_controller")
      .sortBy(origin => (origin.semanticPath, origin.id))
      .map: origin =>
        obj(
          "record_kind" -> string("origin"),
          "origin_id" -> string(origin.id),
          "semantic_path" -> string(origin.semanticPath),
          "operation" -> string(origin.operation),
          "source" -> source(origin.source),
          "parents" -> array(origin.parents.sorted.map(string)),
          "sink" -> optionalString(origin.sink)
        )
    val generatedEntries = snapshot.generatedNames
      .filter(entry =>
        Set("crossing", "synchronizer", "reset-controller").contains(entry.category)
      )
      .sortBy(entry => (entry.owner, entry.category, entry.name, entry.origin))
      .map: entry =>
        obj(
          "record_kind" -> string("generated-name"),
          "owner" -> string(entry.owner),
          "category" -> string(entry.category),
          "name" -> string(entry.name),
          "origin" -> string(entry.origin)
        )
    array(originEntries ++ generatedEntries)

  private def snapshotJson(snapshot: ConstructionSnapshot): JsonValue =
    obj(
      "root" -> string(snapshot.root),
      "modules" -> array(
        snapshot.modules.sortBy(_.path).map: module =>
          obj(
            "path" -> string(module.path),
            "class_name" -> string(module.className),
            "domains" -> array(
              module.domains.sortBy(_.path).map: domain =>
                obj(
                  "path" -> string(domain.path),
                  "name" -> string(domain.name),
                  "kind" -> string(domain.kind),
                  "binding" -> optionalString(domain.binding),
                  "edge" -> optionalString(domain.edge),
                  "reset_policy" -> optionalString(domain.resetPolicy),
                  "attributes" -> attributes(domain.attributes)
                )
            ),
            "declarations" -> array(
              module.declarations.sortBy(_.path).map: declaration =>
                obj(
                  "path" -> string(declaration.path),
                  "kind" -> string(declaration.kind),
                  "name" -> string(declaration.name),
                  "data_type" -> optionalString(declaration.dataType),
                  "domain" -> optionalString(declaration.domain),
                  "attributes" -> attributes(declaration.attributes)
                )
            ),
            "instances" -> array(
              module.instances.sortBy(_.path).map: instance =>
                obj(
                  "path" -> string(instance.path),
                  "child_module" -> string(instance.childModule),
                  "lexical_domain" -> optionalString(instance.lexicalDomain),
                  "bindings" -> attributes(instance.bindings),
                  "parameter_bindings" -> attributes(instance.parameterBindings)
                )
            )
          )
      ),
      "interface_abi" -> array(
        snapshot.interfaceAbi.sortBy(_.logicalPath).map: entry =>
          obj(
            "logical_path" -> string(entry.logicalPath),
            "emitted_path" -> string(entry.emittedPath),
            "role" -> string(entry.role),
            "access" -> string(entry.access),
            "data_type" -> string(entry.dataType),
            "domain" -> string(entry.domain)
          )
      ),
      "resolved_nets" -> array(
        snapshot.resolvedNets.sortBy(_.path).map: net =>
          obj(
            "path" -> string(net.path),
            "data_type" -> string(net.dataType),
            "mode" -> string(net.mode),
            "placement" -> string(net.placement),
            "profile" -> string(net.profile),
            "operations" -> array(net.operations.sorted.map(string))
          )
      ),
      "topology" -> array(
        snapshot.topology.sortBy(edge => (edge.kind, edge.left, edge.right)).map:
          edge =>
            obj(
              "kind" -> string(edge.kind),
              "left" -> string(edge.left),
              "right" -> string(edge.right)
            )
      ),
      "names" -> semanticNames(snapshot),
      "origins" -> array(
        snapshot.origins.sortBy(origin => (origin.semanticPath, origin.kind, origin.id)).map:
          origin =>
            obj(
              "id" -> string(origin.id),
              "semantic_path" -> string(origin.semanticPath),
              "kind" -> string(origin.kind),
              "operation" -> string(origin.operation),
              "source" -> source(origin.source),
              "parents" -> array(origin.parents.sorted.map(string)),
              "sink" -> optionalString(origin.sink),
              "inlined" -> bool(origin.inlined)
            )
      ),
      "source_map" -> array(
        snapshot.sourceMap
          .sortBy(entry =>
            (entry.semanticPath, entry.source.path, entry.source.line, entry.source.column)
          )
          .map: entry =>
            obj(
              "semantic_path" -> string(entry.semanticPath),
              "source" -> source(Some(entry.source))
            )
      ),
      "analog_regions" -> array(
        snapshot.analogRegions.sortBy(_.path).map: region =>
          obj(
            "path" -> string(region.path),
            "module" -> string(region.module),
            "expressions" -> array(
              region.expressions.sortBy(_.path).map: expression =>
                obj(
                  "path" -> string(expression.path),
                  "operation" -> string(expression.operation),
                  "operands" -> array(expression.operands.sorted.map(string)),
                  "literal" -> optionalString(expression.literal),
                  "unit" -> optionalString(expression.unit)
                )
            ),
            "contributions" -> array(
              region.contributions.sortBy(_.path).map: contribution =>
                obj(
                  "path" -> string(contribution.path),
                  "target" -> string(contribution.target),
                  "value" -> string(contribution.value),
                  "kind" -> string(contribution.kind)
                )
            )
          )
      )
    )

  private def attributes(values: Vector[(String, String)]): JsonValue =
    JObject(values.sortBy(_._1).map((key, value) => key -> string(value)))

  private def source(value: Option[SourceSpan]): JsonValue = value match
    case Some(span) =>
      obj(
        "path" -> string(span.path.replace('\\', '/')),
        "line" -> number(span.line),
        "column" -> number(span.column),
        "end_line" -> number(span.endLine),
        "end_column" -> number(span.endColumn)
      )
    case None => JNull

  private def optionalString(value: Option[String]): JsonValue =
    value.map(string).getOrElse(JNull)

  private def normalizeArtifact(text: String): String =
    if text.isEmpty then ""
    else text.replace("\r\n", "\n").replace('\r', '\n').stripTrailing() + "\n"

  private def digest(text: String): String =
    digestBytes(text.getBytes(StandardCharsets.UTF_8))

  private def digestBytes(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(value => f"${value & 0xff}%02x")
      .mkString

  private sealed trait JsonValue
  private final case class JObject(fields: Vector[(String, JsonValue)]) extends JsonValue
  private final case class JArray(values: Vector[JsonValue]) extends JsonValue
  private final case class JString(value: String) extends JsonValue
  private final case class JNumber(value: String) extends JsonValue
  private final case class JBoolean(value: Boolean) extends JsonValue
  private case object JNull extends JsonValue

  private def obj(fields: (String, JsonValue)*): JsonValue = JObject(fields.toVector)
  private def array(values: Iterable[JsonValue]): JsonValue = JArray(values.toVector)
  private def string(value: String): JsonValue = JString(value)
  private def number(value: Int): JsonValue = JNumber(value.toString)
  private def number(value: Long): JsonValue = JNumber(value.toString)
  private def bool(value: Boolean): JsonValue = JBoolean(value)

  private def render(value: JsonValue, depth: Int = 0): String = value match
    case JObject(fields) =>
      val sorted = fields.sortBy(_._1)
      if sorted.isEmpty then "{}"
      else
        sorted
          .map: (key, child) =>
            s"${indent(depth + 1)}${quote(key)}: ${render(child, depth + 1)}"
          .mkString("{\n", ",\n", s"\n${indent(depth)}}")
    case JArray(values) =>
      if values.isEmpty then "[]"
      else
        values
          .map(child => s"${indent(depth + 1)}${render(child, depth + 1)}")
          .mkString("[\n", ",\n", s"\n${indent(depth)}]")
    case JString(value) => quote(value)
    case JNumber(value) => value
    case JBoolean(value) => value.toString
    case JNull => "null"

  private def quote(value: String): String =
    val escaped = value.flatMap:
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case character if character.isControl => f"\\u${character.toInt}%04x"
      case character => character.toString
    s"\"$escaped\""

  private def indent(depth: Int): String = "  " * depth
