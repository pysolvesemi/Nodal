package nodal.bridge

import nodal.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.collection.mutable

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
    val text = new Renderer(snapshot).render()
    NodalMlirDocument(Schema, Version, text, digest(text))

  private def digest(text: String): String =
    val bytes = MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
    bytes.map(value => f"${value & 0xff}%02x").mkString

  private final class Renderer(snapshot: ConstructionSnapshot):
    private val modules = snapshot.modules.sortBy(_.path)
    private val sourceByPath = snapshot.sourceMap
      .sortBy(entry => (entry.semanticPath, entry.source.path, entry.source.line))
      .map(entry => entry.semanticPath -> entry.source)
      .toMap
    private val moduleSymbols = modules.map(module =>
      module.path -> stableSymbol("module", module.path)
    ).toMap

    def render(): String =
      validate()
      val body = modules.map(renderModule).mkString("\n\n")
      val attributes = Vector(
        "nodal.bridge.schema" -> quoted(Schema),
        "nodal.bridge.version" -> integer(Version),
        "nodal.bridge.root" -> quoted(snapshot.root),
        "nodal.bridge.declarations" -> declarationInventory,
        "nodal.bridge.names" -> nameInventory,
        "nodal.bridge.origins" -> originInventory,
        "nodal.bridge.generated_names" -> generatedNameInventory,
        "nodal.bridge.topology" -> topologyInventory,
        "nodal.bridge.source_map" -> sourceMapInventory
      )
      normalize(
        s"""module attributes ${dictionary(attributes)} {
${indent(body, 2)}
}
"""
      )

    private def validate(): Unit =
      requireUnique(
        modules.map(_.path),
        "NODAL-BRIDGE-001",
        "module semantic path"
      )
      requireUnique(
        modules.flatMap(_.declarations.map(_.path)),
        "NODAL-BRIDGE-002",
        "declaration semantic path"
      )
      requireUnique(
        modules.flatMap(_.instances.map(_.path)),
        "NODAL-BRIDGE-003",
        "instance semantic path"
      )
      requireUnique(
        snapshot.sourceMap.map(_.semanticPath),
        "NODAL-BRIDGE-010",
        "source-map semantic path"
      )
      if !moduleSymbols.contains(snapshot.root) then
        fail(
          "NODAL-BRIDGE-011",
          "root semantic path does not identify a serialized Module",
          Some(snapshot.root)
        )

    private def requireUnique(
        values: Vector[String],
        code: String,
        label: String
    ): Unit =
      values.groupBy(identity).collectFirst:
        case (value, occurrences) if occurrences.size != 1 => value
      match
        case Some(value) => fail(code, s"duplicate $label '$value'", Some(value))
        case None => ()

    private def renderModule(module: KernelModuleSnapshot): String =
      val body = mutable.ArrayBuffer.empty[String]
      val values = mutable.LinkedHashMap.empty[String, (String, String)]
      val localDomains = module.domains.map(domain =>
        domain.name -> stableLocalSymbol("domain", domain.name)
      ).toMap

      module.domains.sortBy(_.path).foreach: domain =>
        val symbol = localDomains(domain.name)
        val metadata = bridgeMetadata(
          domain.path,
          Vector(
            "kind" -> quoted(domain.kind),
            "binding" -> optionalString(domain.binding)
          ) ++ domain.attributes.map((key, value) =>
            normalizeKey(key) -> quoted(value)
          )
        )
        if domain.kind == "required" then
          body += operation(
            "nodal.domain_requirement",
            attributes = Vector(
              "sym_name" -> quoted(symbol),
              "metadata" -> metadata
            ),
            semanticPath = domain.path
          )
          domain.binding.foreach: actual =>
            body += operation(
              "nodal.domain_bind",
              attributes = Vector(
                "requirement" -> symbolReference(symbol),
                "actual" -> symbolReference(
                  stableLocalSymbol("domain", lastSegment(actual))
                ),
                "metadata" -> bridgeMetadata(
                  domain.path,
                  Vector("actual_path" -> quoted(actual))
                )
              ),
              semanticPath = domain.path
            )
        else
          val edge = domain.edge.getOrElse(
            fail(
              "NODAL-BRIDGE-012",
              "domain edge is unavailable and cannot be invented",
              Some(domain.path)
            )
          )
          val resetPolicy = domain.resetPolicy.getOrElse(
            fail(
              "NODAL-BRIDGE-013",
              "reset policy is unavailable and cannot be invented",
              Some(domain.path)
            )
          )
          body += operation(
            "nodal.domain",
            attributes = Vector(
              "sym_name" -> quoted(symbol),
              "edge" -> quoted(edge),
              "reset_policy" -> quoted(resetPolicy),
              "metadata" -> metadata
            ),
            semanticPath = domain.path
          )

      module.declarations.sortBy(_.path).foreach: declaration =>
        declaration.kind match
          case "input" | "output" =>
            body += renderPort(module, declaration, localDomains)
          case "parameter" =>
            body += renderParameter(declaration)
          case _ => ()

      module.instances.sortBy(_.path).foreach: instance =>
        body += renderInstance(instance)

      interfaceEntries(module).foreach: entry =>
        body += operation(
          "nodal.interface_abi",
          attributes = Vector(
            "logical_path" -> quoted(entry.logicalPath),
            "members" -> array(Vector(quoted(lastSegment(entry.logicalPath)))),
            "layout_policy" -> quoted("logical_only"),
            "metadata" -> bridgeMetadata(
              entry.logicalPath,
              Vector(
                "emitted_path" -> quoted(entry.emittedPath),
                "role" -> quoted(entry.role),
                "access" -> quoted(entry.access),
                "data_type" -> quoted(entry.dataType),
                "domain" -> quoted(entry.domain)
              )
            )
          ),
          semanticPath = entry.logicalPath
        )

      resolvedEntries(module).zipWithIndex.foreach: (net, index) =>
        val elementType = parseType(net.dataType, net.path)
        val mode = resolvedMode(net.mode, net.path)
        val resultType =
          s"""!nodal.resolved<${quoted(mode)}, $elementType>"""
        val result = s"%net_$index"
        body += operation(
          "nodal.resolved_net",
          results = Vector(result),
          resultTypes = Vector(resultType),
          attributes = Vector(
            "name" -> quoted(lastSegment(net.path)),
            "metadata" -> bridgeMetadata(
              net.path,
              Vector(
                "placement" -> quoted(net.placement),
                "profile" -> quoted(net.profile),
                "operations" -> array(net.operations.sorted.map(quoted))
              )
            )
          ),
          semanticPath = net.path
        )
        values.update(net.path, result -> resultType)

      terminalDeclarations(module).zipWithIndex.foreach: (declaration, index) =>
        val discipline = declaration.attributes.toMap
          .get("discipline")
          .map(normalizeDiscipline)
          .getOrElse(
            fail(
              "NODAL-BRIDGE-014",
              "conservative declaration lacks discipline identity",
              Some(declaration.path)
            )
          )
        val resultType = s"""!nodal.terminal<${quoted(discipline)}>"""
        val result = s"%terminal_$index"
        val opName =
          if declaration.kind == "analog-node" then "nodal.node"
          else "nodal.terminal"
        body += operation(
          opName,
          results = Vector(result),
          resultTypes = Vector(resultType),
          attributes = Vector(
            "name" -> quoted(declaration.name),
            "metadata" -> bridgeMetadata(
              declaration.path,
              Vector("declaration_kind" -> quoted(declaration.kind))
            )
          ),
          semanticPath = declaration.path
        )
        values.update(declaration.path, result -> resultType)

      topologyEntries(module).zipWithIndex.foreach: (edge, index) =>
        if Set("terminal-connect", "node-connect").contains(edge.kind) then
          (values.get(edge.left), values.get(edge.right)) match
            case (Some((leftValue, leftType)), Some((rightValue, rightType)))
                if leftType == rightType =>
              val discipline = terminalDiscipline(leftType, edge.left)
              body += operation(
                "nodal.branch",
                results = Vector(s"%branch_$index"),
                operands = Vector(leftValue, rightValue),
                operandTypes = Vector(leftType, rightType),
                resultTypes = Vector(
                  s"""!nodal.branch<${quoted(discipline)}>"""
                ),
                attributes = Vector(
                  "metadata" -> bridgeMetadata(
                    s"${edge.left}->${edge.right}",
                    Vector("topology_kind" -> quoted(edge.kind))
                  )
                ),
                semanticPath = edge.left
              )
            case _ => ()

      operation(
        "nodal.module",
        attributes = Vector(
          "sym_name" -> quoted(moduleSymbols(module.path)),
          "metadata" -> bridgeMetadata(
            module.path,
            Vector("class_name" -> quoted(module.className))
          )
        ),
        regions = Vector(body.mkString("\n")),
        semanticPath = module.path
      )

    private def renderPort(
        module: KernelModuleSnapshot,
        declaration: KernelDeclarationSnapshot,
        localDomains: Map[String, String]
    ): String =
      val dataType = declaration.dataType
        .map(parseType(_, declaration.path))
        .getOrElse(
          fail(
            "NODAL-BRIDGE-004",
            "port type is unavailable",
            Some(declaration.path)
          )
        )
      val domainName = localDomainName(module, declaration)
      val domainSymbol = localDomains.getOrElse(
        domainName,
        stableLocalSymbol("domain", domainName)
      )
      operation(
        "nodal.port",
        attributes = Vector(
          "sym_name" -> quoted(stableLocalSymbol("port", declaration.name)),
          "type" -> dataType,
          "direction" -> quoted(declaration.kind),
          "domain" -> symbolReference(domainSymbol),
          "metadata" -> bridgeMetadata(
            declaration.path,
            Vector(
              "resolved_domain" -> optionalString(declaration.domain)
            )
          )
        ),
        semanticPath = declaration.path
      )

    private def localDomainName(
        module: KernelModuleSnapshot,
        declaration: KernelDeclarationSnapshot
    ): String =
      declaration.domain
        .flatMap: resolved =>
          module.domains.find(_.binding.contains(resolved)).map(_.name)
            .orElse(
              module.domains.find(_.path == resolved).map(_.name)
            )
        .orElse:
          module.domains match
            case Vector(single) => Some(single.name)
            case _ => None
        .getOrElse(
          fail(
            "NODAL-BRIDGE-015",
            "port domain is ambiguous or unavailable",
            Some(declaration.path)
          )
        )

    private def renderParameter(
        declaration: KernelDeclarationSnapshot
    ): String =
      val dataType = declaration.dataType
        .map(parseType(_, declaration.path))
        .getOrElse(
          fail(
            "NODAL-BRIDGE-005",
            "parameter type is unavailable",
            Some(declaration.path)
          )
        )
      val attributes = declaration.attributes.toMap
      val defaultValue = attributes.get("default").getOrElse(
        fail(
          "NODAL-BRIDGE-006",
          "parameter default is unavailable",
          Some(declaration.path)
        )
      )
      operation(
        "nodal.parameter",
        attributes = Vector(
          "sym_name" -> quoted(stableLocalSymbol("parameter", declaration.name)),
          "type" -> dataType,
          "default_value" -> typedLiteral(defaultValue, dataType, declaration.path),
          "variability" -> quoted("symbolic"),
          "metadata" -> bridgeMetadata(
            declaration.path,
            declaration.attributes
              .filterNot(_._1 == "default")
              .map((key, value) => normalizeKey(key) -> quoted(value))
          )
        ),
        semanticPath = declaration.path
      )

    private def renderInstance(instance: KernelInstanceSnapshot): String =
      val moduleSymbol = moduleSymbols.getOrElse(
        instance.childModule,
        fail(
          "NODAL-BRIDGE-007",
          "instance child Module is absent from the snapshot",
          Some(instance.path)
        )
      )
      val parameterBindings = dictionary(
        instance.parameterBindings.map((name, value) =>
          stableLocalSymbol("parameter", name) -> untypedBinding(value)
        )
      )
      val domainBindings = dictionary(
        instance.bindings.map((name, value) =>
          stableLocalSymbol("domain", name) ->
            symbolReference(stableLocalSymbol("domain", lastSegment(value)))
        )
      )
      operation(
        "nodal.instance",
        attributes = Vector(
          "sym_name" -> quoted(stableLocalSymbol("instance", lastSegment(instance.path))),
          "module" -> symbolReference(moduleSymbol),
          "parameter_bindings" -> parameterBindings,
          "domain_bindings" -> domainBindings,
          "metadata" -> bridgeMetadata(
            instance.path,
            Vector(
              "child_path" -> quoted(instance.childModule),
              "lexical_domain" -> optionalString(instance.lexicalDomain)
            )
          )
        ),
        semanticPath = instance.path
      )

    private def interfaceEntries(
        module: KernelModuleSnapshot
    ): Vector[InterfaceAbiEntry] =
      snapshot.interfaceAbi
        .filter(entry => owningModule(entry.logicalPath) == module.path)
        .sortBy(_.logicalPath)

    private def resolvedEntries(
        module: KernelModuleSnapshot
    ): Vector[KernelResolvedNetSnapshot] =
      snapshot.resolvedNets
        .filter(entry => owningModule(entry.path) == module.path)
        .sortBy(_.path)

    private def terminalDeclarations(
        module: KernelModuleSnapshot
    ): Vector[KernelDeclarationSnapshot] =
      val terminalKinds = Set(
        "analog-input",
        "analog-output",
        "analog-inout",
        "analog-node",
        "conservative-terminal"
      )
      module.declarations.filter(declaration =>
        terminalKinds.contains(declaration.kind)
      ).sortBy(_.path)

    private def topologyEntries(
        module: KernelModuleSnapshot
    ): Vector[KernelTopologyEdge] =
      snapshot.topology
        .filter(edge =>
          owningModule(edge.left) == module.path &&
            owningModule(edge.right) == module.path
        )
        .sortBy(edge => (edge.kind, edge.left, edge.right))

    private def owningModule(path: String): String =
      modules
        .filter(module =>
          path == module.path || path.startsWith(s"${module.path}.")
        )
        .sortBy(module => -module.path.length)
        .headOption
        .map(_.path)
        .getOrElse(
          fail(
            "NODAL-BRIDGE-008",
            "semantic path has no owning Module",
            Some(path)
          )
        )

    private def parseType(text: String, path: String): String =
      text match
        case "Bool" => "!nodal.bits<1>"
        case "Clock" | "Reset" => "i1"
        case "Integer" => "i64"
        case "Real" => "f64"
        case WidthType(kind, widthText) =>
          val width = widthText.toIntOption.filter(_ > 0).getOrElse(
            fail(
              "NODAL-BRIDGE-016",
              s"non-concrete or invalid width '$widthText'",
              Some(path)
            )
          )
          kind match
            case "Bits" => s"!nodal.bits<$width>"
            case "UInt" => s"!nodal.uint<$width>"
            case "SInt" => s"!nodal.sint<$width>"
        case value if value.startsWith("Vec(") && value.endsWith(")") =>
          val inside = value.drop(4).dropRight(1)
          val split = inside.lastIndexOf(';')
          if split <= 0 || split == inside.length - 1 then
            fail("NODAL-BRIDGE-017", s"invalid Vec type '$value'", Some(path))
          val element = parseType(inside.take(split), path)
          val dimensions = inside.drop(split + 1).split("x").toVector
          if dimensions.isEmpty || dimensions.exists(dimension =>
              !dimension.matches("[1-9][0-9]*|[A-Za-z_][A-Za-z0-9_]*")
            )
          then
            fail(
              "NODAL-BRIDGE-018",
              s"invalid Vec dimensions '${inside.drop(split + 1)}'",
              Some(path)
            )
          s"""!nodal.shaped<${quoted(dimensions.mkString(","))}, $element>"""
        case _ =>
          fail(
            "NODAL-BRIDGE-019",
            s"unsupported exact MLIR type representation '$text'",
            Some(path)
          )

    private object WidthType:
      private val Pattern = raw"(Bits|UInt|SInt)\(([^)]+)\)".r
      def unapply(value: String): Option[(String, String)] = value match
        case Pattern(kind, width) => Some(kind -> width)
        case _ => None

    private def typedLiteral(
        value: String,
        dataType: String,
        path: String
    ): String =
      if value == "true" || value == "false" then
        if dataType == "!nodal.bits<1>" || dataType == "i1" then value
        else
          fail(
            "NODAL-BRIDGE-020",
            "Boolean default is incompatible with parameter type",
            Some(path)
          )
      else
        value.toLongOption match
          case Some(number) if dataType != "f64" => s"$number : i64"
          case _ =>
            fail(
              "NODAL-BRIDGE-021",
              s"unsupported parameter default '$value'",
              Some(path)
            )

    private def untypedBinding(value: String): String =
      if value == "true" || value == "false" then value
      else value.toLongOption.map(number => s"$number : i64").getOrElse(quoted(value))

    private def resolvedMode(mode: String, path: String): String =
      mode match
        case "push-pull" | "push_pull" => "push_pull"
        case "open-drain" | "open_drain" => "open_drain"
        case "open-source" | "open_source" => "open_source"
        case other =>
          fail(
            "NODAL-BRIDGE-022",
            s"resolved-net mode '$other' has no Increment 19 representation",
            Some(path)
          )

    private def normalizeDiscipline(value: String): String =
      value
        .stripSuffix("$")
        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
        .toLowerCase(java.util.Locale.ROOT)
        .replace("named_discipline", "named")

    private def terminalDiscipline(
        terminalType: String,
        path: String
    ): String =
      val prefix = "!nodal.terminal<\""
      val suffix = "\">"
      if terminalType.startsWith(prefix) && terminalType.endsWith(suffix) then
        terminalType.substring(prefix.length, terminalType.length - suffix.length)
      else
        fail(
          "NODAL-BRIDGE-023",
          s"invalid terminal type '$terminalType'",
          Some(path)
        )

    private def bridgeMetadata(
        semanticPath: String,
        additional: Vector[(String, String)]
    ): String =
      val endPosition = sourceByPath.get(semanticPath).toVector.flatMap: source =>
        Vector(
          "source_end_line" -> integer(source.endLine),
          "source_end_column" -> integer(source.endColumn)
        )
      dictionary(
        Vector(
          "bridge_schema" -> quoted(Schema),
          "bridge_version" -> integer(Version),
          "semantic_path" -> quoted(semanticPath)
        ) ++ endPosition ++ additional
      )

    private def declarationInventory: String =
      array(
        modules.flatMap(module =>
          module.declarations.map: declaration =>
            dictionary(
              Vector(
                "path" -> quoted(declaration.path),
                "kind" -> quoted(declaration.kind),
                "name" -> quoted(declaration.name),
                "data_type" -> optionalString(declaration.dataType),
                "domain" -> optionalString(declaration.domain),
                "attributes" -> dictionary(
                  declaration.attributes.map((key, value) =>
                    normalizeKey(key) -> quoted(value)
                  )
                )
              ) ++ sourceFields(declaration.path)
            )
        ).sortBy(identity)
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
            ) ++ entry.source.toVector.flatMap(source => sourceFields(source))
          )
      )

    private def originInventory: String =
      array(
        snapshot.origins.sortBy(entry => (entry.semanticPath, entry.id)).map:
          entry =>
            dictionary(
              Vector(
                "id" -> quoted(entry.id),
                "path" -> quoted(entry.semanticPath),
                "kind" -> quoted(entry.kind),
                "operation" -> quoted(entry.operation),
                "parents" -> array(entry.parents.sorted.map(quoted)),
                "sink" -> optionalString(entry.sink),
                "inlined" -> boolean(entry.inlined)
              ) ++ entry.source.toVector.flatMap(source => sourceFields(source))
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
      sourceByPath.get(path).toVector.flatMap(source => sourceFields(source))

    private def sourceFields(
        source: SourceSpan
    ): Vector[(String, String)] =
      Vector(
        "source_path" -> quoted(source.path),
        "source_line" -> integer(source.line),
        "source_column" -> integer(source.column),
        "source_end_line" -> integer(source.endLine),
        "source_end_column" -> integer(source.endColumn)
      )

    private def operation(
        name: String,
        results: Vector[String] = Vector.empty,
        operands: Vector[String] = Vector.empty,
        attributes: Vector[(String, String)] = Vector.empty,
        regions: Vector[String] = Vector.empty,
        operandTypes: Vector[String] = Vector.empty,
        resultTypes: Vector[String] = Vector.empty,
        semanticPath: String
    ): String =
      if results.size != resultTypes.size then
        fail(
          "NODAL-BRIDGE-024",
          s"operation '$name' result arity mismatch",
          Some(semanticPath)
        )
      if operands.size != operandTypes.size then
        fail(
          "NODAL-BRIDGE-025",
          s"operation '$name' operand arity mismatch",
          Some(semanticPath)
        )
      val resultPrefix =
        if results.isEmpty then ""
        else s"${results.mkString(", ")} = "
      val regionText = regions.map: region =>
        if region.isEmpty then " ({\n})"
        else s""" ({
${indent(region, 2)}
})"""
      val resultSignature = resultTypes match
        case Vector() => "()"
        case Vector(single) => single
        case many => s"(${many.mkString(", ")})"
      s"""$resultPrefix"$name"(${operands.mkString(", ")}) <${dictionary(attributes)}>""" +
        regionText.mkString +
        s" : (${operandTypes.mkString(", ")}) -> $resultSignature" +
        location(semanticPath)

    private def location(semanticPath: String): String =
      sourceByPath.get(semanticPath) match
        case Some(source) =>
          s""" loc(${quoted(source.path)}:${source.line}:${source.column})"""
        case None => " loc(unknown)"

    private def dictionary(entries: Iterable[(String, String)]): String =
      entries.toVector
        .sortBy(_._1)
        .map((key, value) => s"$key = $value")
        .mkString("{", ", ", "}")

    private def array(values: Iterable[String]): String =
      values.mkString("[", ", ", "]")

    private def optionalString(value: Option[String]): String =
      value.map(quoted).getOrElse(quoted(""))

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

    private def integer(value: Int): String = s"$value : i64"

    private def boolean(value: Boolean): String = value.toString

    private def symbolReference(symbol: String): String = s"@$symbol"

    private def stableSymbol(category: String, value: String): String =
      val base = normalizeSymbol(value)
      val suffix = ScalaToMlirBridge.digest(s"$category:$value").take(10)
      s"${base}_$suffix"

    private def stableLocalSymbol(category: String, value: String): String =
      val base = normalizeSymbol(value)
      if base == value && base.length <= 48 then base
      else s"${base.take(36)}_${ScalaToMlirBridge.digest(s"$category:$value").take(8)}"

    private def normalizeSymbol(value: String): String =
      val normalized = value
        .map(character =>
          if character.isLetterOrDigit || character == '_' then character
          else '_'
        )
        .mkString
        .replaceAll("_+", "_")
        .stripPrefix("_")
        .stripSuffix("_")
      val nonEmpty = if normalized.isEmpty then "anonymous" else normalized
      if nonEmpty.head.isDigit then s"n_$nonEmpty" else nonEmpty

    private def normalizeKey(value: String): String =
      normalizeSymbol(value).toLowerCase(java.util.Locale.ROOT)

    private def lastSegment(path: String): String =
      path.split('.').lastOption.filter(_.nonEmpty).getOrElse(path)

    private def indent(text: String, spaces: Int): String =
      val prefix = " " * spaces
      text.linesIterator.map(line => prefix + line).mkString("\n")

    private def normalize(text: String): String =
      text.replace("\r\n", "\n").replace('\r', '\n').stripTrailing() + "\n"

    private def fail(
        code: String,
        message: String,
        semanticPath: Option[String]
    ): Nothing =
      scala.util.Failure[Nothing](
        new BridgeException(BridgeDiagnostic(code, message, semanticPath))
      ).get
