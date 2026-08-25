package nodal.bridge

import nodal.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private[nodal] final case class BridgeDiagnostic(
    code: String,
    message: String,
    semanticPath: Option[String] = None
):
  override def toString: String = semanticPath match
    case Some(path) => s"$code: $message [$path]"
    case None => s"$code: $message"

private[nodal] final class BridgeException(val diagnostic: BridgeDiagnostic)
    extends IllegalArgumentException(diagnostic.toString)

private[nodal] final case class NodalMlirDocument(
    schema: String,
    version: Int,
    text: String,
    sha256: String
)

private[nodal] object ScalaToMlirBridge:
  val Schema: String = "nodal.scala-to-mlir"
  val Version: Int = 1

  def lower(
      top: => Module,
      options: EmitOptions = EmitOptions()
  ): NodalMlirDocument =
    fromSnapshot(ConstructionKernel.inspect(top, options))

  def compile(
      top: => Module,
      request: NativeCompilerRequest,
      options: EmitOptions = EmitOptions()
  ): NativeCompilerResult =
    NativeCompilerClient.run(lower(top, options), request)

  def fromSnapshot(snapshot: ConstructionSnapshot): NodalMlirDocument =
    val text = Renderer(snapshot).render()
    NodalMlirDocument(Schema, Version, text, sha256(text))

  private def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private final case class Renderer(snapshot: ConstructionSnapshot):
    private val modules = snapshot.modules.sortBy(_.path)
    private val sourceByPath = snapshot.sourceMap
      .sortBy(entry => (entry.semanticPath, entry.source.path, entry.source.line))
      .map(entry => entry.semanticPath -> entry.source)
      .toMap
    private val moduleSymbols = modules.map(module =>
      module.path -> stableSymbol(module.path)
    ).toMap

    def render(): String =
      validate()
      val moduleText = modules.map(renderModule).mkString("\n\n")
      normalize(
        s"""module attributes ${dictionary(Vector(
            "nodal.bridge.schema" -> quoted(Schema),
            "nodal.bridge.version" -> integer(Version),
            "nodal.bridge.root" -> quoted(snapshot.root),
            "nodal.bridge.interface_abi" -> interfaceInventory,
            "nodal.bridge.resolved_nets" -> resolvedNetInventory,
            "nodal.bridge.topology" -> topologyInventory,
            "nodal.bridge.names" -> nameInventory,
            "nodal.bridge.origins" -> originInventory,
            "nodal.bridge.generated_names" -> generatedNameInventory,
            "nodal.bridge.source_map" -> sourceMapInventory
          ))} {
${indent(moduleText, 2)}
}
"""
      )

    private def validate(): Unit =
      unique(modules.map(_.path), "NODAL-BRIDGE-001", "module path")
      unique(
        modules.flatMap(_.declarations.map(_.path)),
        "NODAL-BRIDGE-002",
        "declaration path"
      )
      unique(
        modules.flatMap(_.instances.map(_.path)),
        "NODAL-BRIDGE-003",
        "instance path"
      )
      if !moduleSymbols.contains(snapshot.root) then
        fail(
          "NODAL-BRIDGE-004",
          "root does not identify a serialized Module",
          Some(snapshot.root)
        )

    private def unique(
        values: Vector[String],
        code: String,
        label: String
    ): Unit =
      values.groupBy(identity).collectFirst:
        case (value, copies) if copies.size > 1 => value
      match
        case Some(value) => fail(code, s"duplicate $label '$value'", Some(value))
        case None => ()

    private def renderModule(module: KernelModuleSnapshot): String =
      val metadata = dictionary(
        Vector(
          "bridge_schema" -> quoted(Schema),
          "bridge_version" -> integer(Version),
          "semantic_path" -> quoted(module.path),
          "class_name" -> quoted(module.className),
          "domains" -> domainInventory(module),
          "declarations" -> declarationInventory(module),
          "instances" -> instanceInventory(module)
        ) ++ sourceFields(module.path)
      )
      s""""nodal.module"() <{metadata = $metadata, sym_name = ${quoted(moduleSymbols(module.path))}}> ({
^bb0:
}) : () -> ()${location(module.path)}"""

    private def domainInventory(module: KernelModuleSnapshot): String =
      array(
        module.domains.sortBy(_.path).map: domain =>
          dictionary(
            Vector(
              "path" -> quoted(domain.path),
              "name" -> quoted(domain.name),
              "kind" -> quoted(domain.kind),
              "binding" -> optional(domain.binding),
              "edge" -> optional(domain.edge),
              "reset_policy" -> optional(domain.resetPolicy),
              "attributes" -> stringPairs(domain.attributes)
            ) ++ sourceFields(domain.path)
          )
      )

    private def declarationInventory(
        module: KernelModuleSnapshot
    ): String =
      array(
        module.declarations.sortBy(_.path).map: declaration =>
          dictionary(
            Vector(
              "path" -> quoted(declaration.path),
              "kind" -> quoted(declaration.kind),
              "name" -> quoted(declaration.name),
              "data_type" -> optional(declaration.dataType),
              "domain" -> optional(declaration.domain),
              "attributes" -> stringPairs(declaration.attributes)
            ) ++ sourceFields(declaration.path)
          )
      )

    private def instanceInventory(module: KernelModuleSnapshot): String =
      array(
        module.instances.sortBy(_.path).map: instance =>
          if !moduleSymbols.contains(instance.childModule) then
            fail(
              "NODAL-BRIDGE-005",
              "instance child Module is absent from the snapshot",
              Some(instance.path)
            )
          dictionary(
            Vector(
              "path" -> quoted(instance.path),
              "child_path" -> quoted(instance.childModule),
              "child_symbol" -> quoted(moduleSymbols(instance.childModule)),
              "lexical_domain" -> optional(instance.lexicalDomain),
              "domain_bindings" -> stringPairs(instance.bindings),
              "parameter_bindings" -> stringPairs(instance.parameterBindings)
            ) ++ sourceFields(instance.path)
          )
      )

    private def interfaceInventory: String =
      array(
        snapshot.interfaceAbi.sortBy(_.logicalPath).map: entry =>
          dictionary(
            Vector(
              "logical_path" -> quoted(entry.logicalPath),
              "emitted_path" -> quoted(entry.emittedPath),
              "role" -> quoted(entry.role),
              "access" -> quoted(entry.access),
              "data_type" -> quoted(entry.dataType),
              "domain" -> quoted(entry.domain)
            ) ++ sourceFields(entry.logicalPath)
          )
      )

    private def resolvedNetInventory: String =
      array(
        snapshot.resolvedNets.sortBy(_.path).map: net =>
          dictionary(
            Vector(
              "path" -> quoted(net.path),
              "data_type" -> quoted(net.dataType),
              "mode" -> quoted(net.mode),
              "placement" -> quoted(net.placement),
              "profile" -> quoted(net.profile),
              "operations" -> array(net.operations.sorted.map(quoted))
            ) ++ sourceFields(net.path)
          )
      )

    private def topologyInventory: String =
      array(
        snapshot.topology.sortBy(edge =>
          (edge.kind, edge.left, edge.right)
        ).map: edge =>
          dictionary(
            Vector(
              "kind" -> quoted(edge.kind),
              "left" -> quoted(edge.left),
              "right" -> quoted(edge.right)
            )
          )
      )

    private def nameInventory: String =
      array(
        snapshot.names.sortBy(entry =>
          (entry.semanticPath, entry.category, entry.name)
        ).map: entry =>
          dictionary(
            Vector(
              "path" -> quoted(entry.semanticPath),
              "name" -> quoted(entry.name),
              "category" -> quoted(entry.category),
              "provenance" -> quoted(entry.provenance)
            ) ++ entry.source.toVector.flatMap(sourceFields)
          )
      )

    private def originInventory: String =
      array(
        snapshot.origins.sortBy(entry =>
          (entry.semanticPath, entry.id)
        ).map: entry =>
          dictionary(
            Vector(
              "id" -> quoted(entry.id),
              "path" -> quoted(entry.semanticPath),
              "kind" -> quoted(entry.kind),
              "operation" -> quoted(entry.operation),
              "parents" -> array(entry.parents.sorted.map(quoted)),
              "sink" -> optional(entry.sink),
              "inlined" -> entry.inlined.toString
            ) ++ entry.source.toVector.flatMap(sourceFields)
          )
      )

    private def generatedNameInventory: String =
      array(
        snapshot.generatedNames.sortBy(entry =>
          (entry.category, entry.owner, entry.name)
        ).map: entry =>
          dictionary(
            Vector(
              "category" -> quoted(entry.category),
              "name" -> quoted(entry.name),
              "owner" -> quoted(entry.owner),
              "origin" -> quoted(entry.origin)
            )
          )
      )

    private def sourceMapInventory: String =
      array(
        snapshot.sourceMap.sortBy(entry =>
          (
            entry.semanticPath,
            entry.source.path,
            entry.source.line,
            entry.source.column
          )
        ).map: entry =>
          dictionary(
            Vector("semantic_path" -> quoted(entry.semanticPath)) ++
              sourceFields(entry.source)
          )
      )

    private def sourceFields(path: String): Vector[(String, String)] =
      sourceByPath.get(path).toVector.flatMap(sourceFields)

    private def sourceFields(source: SourceSpan): Vector[(String, String)] =
      Vector(
        "source_path" -> quoted(source.path),
        "source_line" -> integer(source.line),
        "source_column" -> integer(source.column),
        "source_end_line" -> integer(source.endLine),
        "source_end_column" -> integer(source.endColumn)
      )

    private def stringPairs(values: Vector[(String, String)]): String =
      array(
        values.sortBy(entry => (entry._1, entry._2)).map: (key, value) =>
          dictionary(Vector("key" -> quoted(key), "value" -> quoted(value)))
      )

    private def location(path: String): String =
      sourceByPath.get(path) match
        case Some(source) =>
          s" loc(${quoted(source.path)}:${source.line}:${source.column})"
        case None => " loc(unknown)"

    private def stableSymbol(value: String): String =
      val base = value
        .map(character =>
          if character.isLetterOrDigit || character == '_' then character
          else '_'
        )
        .mkString
        .replaceAll("_+", "_")
        .stripPrefix("_")
        .stripSuffix("_")
      val normalized = if base.isEmpty then "module" else base
      val legal = if normalized.head.isDigit then s"n_$normalized" else normalized
      s"${legal.take(48)}_${sha256(value).take(10)}"

    private def dictionary(values: Vector[(String, String)]): String =
      values.sortBy(_._1).map: (key, value) =>
        s"$key = $value"
      .mkString("{", ", ", "}")

    private def array(values: Iterable[String]): String =
      values.mkString("[", ", ", "]")

    private def optional(value: Option[String]): String =
      value.map(quoted).getOrElse(quoted(""))

    private def integer(value: Int): String = s"$value : i64"

    private def quoted(value: String): String =
      val escaped = value.flatMap:
        case '\\' => "\\\\"
        case '"' => "\\\""
        case '\n' => "\\0A"
        case '\r' => "\\0D"
        case '\t' => "\\09"
        case character if character.isControl =>
          f"\\${character.toInt & 0xff}%02X"
        case character => character.toString
      s"\"$escaped\""

    private def indent(text: String, spaces: Int): String =
      val prefix = " " * spaces
      text.linesIterator.map(line => prefix + line).mkString("\n")

    private def normalize(text: String): String =
      text.replace("\r\n", "\n").replace('\r', '\n').stripTrailing() + "\n"

    private def fail(
        code: String,
        message: String,
        path: Option[String]
    ): Nothing =
      throw BridgeException(BridgeDiagnostic(code, message, path))
