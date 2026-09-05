package nodal.internal.bridge

import nodal.*

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

import scala.collection.mutable

private[nodal] final case class BridgeDiagnostic(
    code: String,
    message: String,
    semanticPath: Option[String] = None,
    hierarchyPath: Option[String] = None,
    indexPath: Option[String] = None,
    sourceRange: Option[String] = None
):
  override def toString: String =
    val context = Vector(
      semanticPath.map(value => s"[semantic-path=$value]"),
      hierarchyPath.map(value => s"[hierarchy-path=$value]"),
      indexPath.map(value => s"[index-path=$value]"),
      sourceRange.map(value => s"[source-range=$value]")
    ).flatten
    if context.isEmpty then s"$code: $message"
    else s"$code: $message ${context.mkString(" ")}"

private[nodal] final class BridgeException(val diagnostic: BridgeDiagnostic)
    extends IllegalArgumentException(diagnostic.toString)

private[nodal] final case class NodalMlirDocument(
    schema: String,
    version: Int,
    text: String,
    sha256: String
)

private[nodal] final case class VerilogAVerticalSliceResult(
    mlir: String,
    verilogA: String
)

private[nodal] object ScalaToMlirBridge:
  val Schema: String = "nodal.scala-to-mlir"
  val Version: Int = 1

  def lower(
      top: => Module,
      options: EmitOptions = EmitOptions()
  ): NodalMlirDocument =
    fromSnapshot(ConstructionKernel.inspect(top, options), options.backend)

  def compile(
      top: => Module,
      request: NativeCompilerRequest,
      options: EmitOptions = EmitOptions()
  ): NativeCompilerResult =
    NativeCompilerClient.run(lower(top, options), request)

  def fromSnapshot(
      snapshot: ConstructionSnapshot,
      backend: Backend = Backend.Auto
  ): NodalMlirDocument =
    val text = new Renderer(snapshot, backend).render()
    NodalMlirDocument(Schema, Version, text, digest(text))

  def compileToVerilogA(
      top: => Module,
      nodalc: Path,
      translator: Path,
      workingDirectory: Path,
      timeout: Duration = Duration.ofSeconds(30)
  ): Either[NativeCompilerFailure, VerilogAVerticalSliceResult] =
    val document = lower(top, EmitOptions(backend = Backend.VerilogA))
    NativeCompilerClient.run(
      document,
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
        val normalizedDocument = NodalMlirDocument(
          Schema,
          Version,
          normalized.normalizedMlir,
          digest(normalized.normalizedMlir)
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
              VerilogAVerticalSliceResult(
                normalized.normalizedMlir,
                emitted.normalizedMlir
              )
            )

  private def digest(text: String): String =
    val bytes = MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
    bytes.map(value => f"${value & 0xff}%02x").mkString

  private final class Renderer(snapshot: ConstructionSnapshot, backend: Backend):
    private val modules = snapshot.modules.sortBy(_.path)
    private val sourceByPath = snapshot.sourceMap
      .sortBy(entry => (entry.semanticPath, entry.source.path, entry.source.line))
      .map(entry => entry.semanticPath -> entry.source)
      .toMap
    private val moduleSymbols = modules.map(module =>
      module.path -> stableModuleSymbol(module.path)
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
        "nodal.bridge.source_map" -> sourceMapInventory,
        "nodal.bridge.analog_semantics" -> analogSemanticInventory,
        "nodal.bridge.continuous_operators" -> continuousOperatorInventory,
        "nodal.bridge.waveform_operators" -> waveformOperatorInventory,
        "nodal.bridge.analog_procedural" -> AnalogProceduralMlir.inventory(snapshot),
        "nodal.target.profile" -> quoted(targetProfile),
        "nodal.backend.profile" -> quoted(backendProfile),
        "nodal.backend.check_profile" -> quoted("default"),
        "nodal.backend.shaped_layout" -> quoted(
          if backendProfile == "verilog-a" then "scalar-or-flat" else "flat-packed"
        ),
        "nodal.backend.materialization" -> quoted(
          if backendProfile == "verilog-a" then "safe-inline" else "readable"
        ),
        "nodal.backend.naming" -> quoted("semantic")
      ) ++ mandatoryVerificationAttributes
      normalize(
        s"""module attributes ${dictionary(attributes)} {
${indent(body, 2)}
}
"""
      )

    private def targetProfile: String =
      val analogKinds = Set(
        "analog-input",
        "analog-output",
        "analog-inout",
        "analog-node",
        "conservative-terminal",
        "analog-signal"
      )
      val declarationKinds = modules.flatMap(_.declarations.map(_.kind)).toSet
      val digitalKinds = Set(
        "input",
        "output",
        "state",
        "wire",
        "register",
        "memory",
        "digital-inout"
      )
      val analog = snapshot.analogRegions.nonEmpty ||
        snapshot.continuousOperators.nonEmpty ||
        snapshot.waveformOperators.nonEmpty ||
        snapshot.analogSemantics.equations.nonEmpty ||
        snapshot.analogSemantics.contributions.nonEmpty ||
        snapshot.analogProcedural.nonEmpty ||
        declarationKinds.exists(analogKinds.contains)
      val digital = declarationKinds.exists(digitalKinds.contains) ||
        snapshot.interfaceAbi.nonEmpty ||
        snapshot.resolvedNets.nonEmpty
      (digital, analog) match
        case (true, true) => "mixed_signal"
        case (false, true) => "analog"
        case (true, false) => "digital"
        case _ => "target_neutral"

    private def analogSemanticInventory: String =
      val equations = snapshot.analogSemantics.equations.map: equation =>
        dictionary(
          Vector(
            "kind" -> quoted(if equation.initialOnly then "initial_equation" else "equation"),
            "identity" -> quoted(equation.identity.value),
            "authored_left" -> quoted(equation.residual.authoredLeft.rendered),
            "authored_right" -> quoted(equation.residual.authoredRight.rendered),
            "dimension" -> quoted(equation.residual.authoredLeft.dimension),
            "analyses" -> array(equation.metadata.analyses.toVector.sorted.map(quoted)),
            "continuity" -> quoted(equation.metadata.continuity),
            "guard" -> quoted(equation.metadata.guard.map(_.rendered).getOrElse("")),
            "owner" -> quoted(equation.metadata.owner),
            "source_file" -> quoted(equation.metadata.source.file),
            "source_line" -> integer(equation.metadata.source.line),
            "source_column" -> integer(equation.metadata.source.column),
            "residual_convention" -> quoted(equation.residual.canonicalConvention),
            "causally_oriented" -> boolean(equation.residual.causallyOriented),
            "divided" -> boolean(equation.residual.divided)
          )
        )
      val contributions = snapshot.analogSemantics.contributions.flatMap: bucket =>
        bucket.terms.map: contribution =>
          dictionary(
            Vector(
              "kind" -> quoted("contribution"),
              "identity" -> quoted(contribution.identity.value),
              "target_identity" -> quoted(bucket.target.identity),
              "target_kind" -> quoted(bucket.target.kind.toString.toLowerCase),
              "target_dimension" -> quoted(bucket.target.dimension),
              "target_orientation" -> quoted(bucket.target.orientation),
              "value" -> quoted(contribution.value.rendered),
              "analyses" -> array(contribution.metadata.analyses.toVector.sorted.map(quoted)),
              "continuity" -> quoted(contribution.metadata.continuity),
              "guard" -> quoted(contribution.metadata.guard.map(_.rendered).getOrElse("")),
              "owner" -> quoted(contribution.metadata.owner),
              "source_file" -> quoted(contribution.metadata.source.file),
              "source_line" -> integer(contribution.metadata.source.line),
              "source_column" -> integer(contribution.metadata.source.column)
            )
          )
      array(equations ++ contributions)

    private def waveformOperatorAttributes(value: KernelWaveformOperatorSnapshot)
        : Vector[(String, String)] =
      Vector(
        "operator_contract" -> quoted("increment36"),
        "operator_id" -> quoted(value.path),
        "owner" -> quoted(value.owner),
        "context" -> quoted(value.context),
        "operand_dimensions" -> array(value.operandDimensions.map(quoted)),
        "result_dimension" -> quoted(value.resultDimension),
        "input_continuity" -> quoted(value.inputContinuity),
        "output_continuity" -> quoted(value.outputContinuity),
        "analyses" -> array(value.analyses.map(quoted))
      ) ++ value.stateId.toVector.map(state => "state_id" -> quoted(state))

    private def waveformOperatorInventory: String =
      array(snapshot.waveformOperators.map: value =>
        dictionary(waveformOperatorAttributes(value) ++ Vector(
          "operation" -> quoted(value.operation),
          "operands" -> array(value.operands.map(quoted))
        ) ++ value.source.toVector.flatMap(source =>
          Vector(
            "source_file" -> quoted(source.path),
            "source_line" -> integer(source.line),
            "source_column" -> integer(source.column)
          )
        )))

    private def continuousOperatorInventory: String =
      array(
        snapshot.continuousOperators.sortBy(_.path).map: operator =>
          dictionary(
            Vector(
              "path" -> quoted(operator.path),
              "operation" -> quoted(operator.operation),
              "owner" -> quoted(operator.owner),
              "context" -> quoted(operator.context),
              "input" -> quoted(operator.input),
              "initial_condition" -> quoted(operator.initialCondition.getOrElse("")),
              "input_dimension" -> quoted(operator.inputDimension),
              "result_dimension" -> quoted(operator.resultDimension),
              "state_id" -> quoted(operator.stateId.getOrElse("")),
              "initialization" -> quoted(operator.initialization),
              "analyses" -> array(operator.analyses.map(quoted)),
              "source_file" -> quoted(operator.source.map(_.path).getOrElse("")),
              "source_line" -> integer(operator.source.map(_.line).getOrElse(0)),
              "source_column" -> integer(operator.source.map(_.column).getOrElse(0))
            )
          )
      )

    private def backendProfile: String = backend match
      case Backend.VerilogA => "verilog-a"
      case Backend.VerilogAMS => "verilog-ams"
      case Backend.Auto => if targetProfile == "analog" then "verilog-a" else "verilog-ams"
      case Backend.Verilog => "verilog"

    private def mandatoryVerificationAttributes: Vector[(String, String)] =
      Vector(
        "analog_topology",
        "assignment_coverage",
        "cdc_rdc_safe",
        "clock_reset_domains",
        "combinational_acyclic",
        "construction_closed",
        "driver_coverage",
        "enum_fsm",
        "hierarchy_closed",
        "latch_free",
        "layout_storage",
        "memory_effects",
        "mixed_signal_bridges",
        "parameters_complete",
        "protocol_pipeline",
        "target_capability",
        "width_sign_shape"
      ).map(name => s"nodal.verify.$name" -> boolean(true))

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
      requireUnique(
        snapshot.continuousOperators.map(_.path),
        "NODAL-ANALOG-035-002",
        "continuous-time operator identity"
      )
      requireUnique(
        snapshot.continuousOperators.flatMap(_.stateId),
        "NODAL-ANALOG-035-005",
        "integral state identity"
      )
      requireUnique(
        snapshot.waveformOperators.map(_.path),
        "NODAL-ANALOG-036-002",
        "waveform identity"
      )
      requireUnique(
        snapshot.waveformOperators.flatMap(_.stateId),
        "NODAL-ANALOG-036-006",
        "waveform state"
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
      val declarationsByPath = module.declarations.map(declaration =>
        declaration.path -> declaration
      ).toMap
      val parameterSymbols = module.declarations
        .filter(_.kind == "parameter")
        .map(declaration =>
          declaration.path -> stableLocalSymbol("parameter", declaration.name)
        )
        .toMap

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

      val accessBranches = mutable.LinkedHashMap.empty[String, (String, String)]
      val accessExpressions = analogRegionsFor(module).flatMap(_.expressions)
        .filter(expression =>
          expression.operation == "potential_access" || expression.operation == "flow_access"
        )
      val branchKeys = accessExpressions.map: expression =>
        if expression.operands.size != 2 then
          fail(
            "NODAL-RC-BRANCH-001",
            "Increment 25 requires a two-terminal V(p,n) or I(p,n) access",
            Some(expression.path)
          )
        expression.operands(0) -> expression.operands(1)
      val uniqueBranchKeys = branchKeys.distinct.sortBy(identity)
      val branchByKey = uniqueBranchKeys.zipWithIndex.map: (key, index) =>
        val (positivePath, negativePath) = key
        val positive = values.getOrElse(
          positivePath,
          fail("NODAL-RC-BRANCH-002", "positive terminal is unavailable", Some(positivePath))
        )
        val negative = values.getOrElse(
          negativePath,
          fail("NODAL-RC-BRANCH-002", "negative terminal is unavailable", Some(negativePath))
        )
        if positive._2 != negative._2 then
          fail(
            "NODAL-RC-BRANCH-003",
            "RC access terminals have incompatible disciplines",
            Some(positivePath)
          )
        val result = s"%analog_branch_$index"
        val resultType =
          s"""!nodal.branch<${quoted(terminalDiscipline(positive._2, positivePath))}>"""
        body += operation(
          "nodal.branch",
          results = Vector(result),
          operands = Vector(positive._1, negative._1),
          operandTypes = Vector(positive._2, negative._2),
          resultTypes = Vector(resultType),
          attributes = Vector(
            "metadata" -> bridgeMetadata(
              s"$positivePath->$negativePath",
              Vector("vertical_slice" -> quoted("rc"))
            )
          ),
          semanticPath = positivePath
        )
        key -> (result -> resultType)
      .toMap
      accessExpressions.foreach: expression =>
        accessBranches.update(
          expression.path,
          branchByKey(expression.operands(0) -> expression.operands(1))
        )

      analogRegionsFor(module).zipWithIndex.foreach: (region, regionIndex) =>
        body += renderAnalogRegion(
          region,
          regionIndex,
          declarationsByPath,
          parameterSymbols,
          accessBranches
        )

      body ++= AnalogProceduralMlir.renderModule(snapshot, module.path)

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

    private def analogRegionsFor(
        module: KernelModuleSnapshot
    ): Vector[KernelAnalogRegionSnapshot] =
      snapshot.analogRegions.filter(_.module == module.path).sortBy(_.path)

    private def continuousOperator(
        path: String,
        expectedOperation: String
    ): KernelContinuousOperatorSnapshot =
      snapshot.continuousOperators.find(_.path == path) match
        case Some(value) if value.operation == expectedOperation => value
        case Some(value) =>
          fail(
            "NODAL-ANALOG-035-002",
            s"continuous-time operator '$path' has operation '${value.operation}', expected '$expectedOperation'",
            Some(path)
          )
        case None =>
          fail(
            "NODAL-ANALOG-035-002",
            s"continuous-time operator '$path' has no semantic contract",
            Some(path)
          )

    private def continuousOperatorAttributes(
        value: KernelContinuousOperatorSnapshot
    ): Vector[(String, String)] =
      Vector(
        "operator_contract" -> quoted("increment35"),
        "operator_id" -> quoted(value.path),
        "owner" -> quoted(value.owner),
        "context" -> quoted(value.context),
        "input_dimension" -> quoted(value.inputDimension),
        "result_dimension" -> quoted(value.resultDimension),
        "initialization" -> quoted(value.initialization),
        "analyses" -> array(value.analyses.map(quoted))
      ) ++ value.stateId.toVector.map(state => "state_id" -> quoted(state)) ++
        value.initialCondition.toVector.map(_ =>
          "initial_dimension" -> quoted(value.resultDimension)
        )

    private def renderAnalogRegion(
        region: KernelAnalogRegionSnapshot,
        regionIndex: Int,
        declarationsByPath: Map[String, KernelDeclarationSnapshot],
        parameterSymbols: Map[String, String],
        accessBranches: mutable.LinkedHashMap[String, (String, String)]
    ): String =
      val lines = mutable.ArrayBuffer.empty[String]
      val values = mutable.LinkedHashMap.empty[String, (String, String)]
      val parameterValues = mutable.LinkedHashMap.empty[String, (String, String)]

      def parameterValue(path: String): (String, String) =
        parameterValues.getOrElseUpdate(
          path,
          declarationsByPath.get(path) match
            case Some(declaration)
                if declaration.kind == "parameter" &&
                  declaration.dataType.contains("Real") =>
              val symbol = parameterSymbols.getOrElse(
                path,
                fail("NODAL-RC-PARAMETER-001", "real parameter symbol is unavailable", Some(path))
              )
              val result = s"%analog_${regionIndex}_parameter_${parameterValues.size}"
              lines += operation(
                "nodal.parameter_ref",
                results = Vector(result),
                resultTypes = Vector("f64"),
                attributes = Vector(
                  "parameter" -> symbolReference(symbol),
                  "metadata" -> bridgeMetadata(path, Vector.empty)
                ),
                semanticPath = path
              )
              result -> "f64"
            case _ =>
              fail(
                "NODAL-RC-PARAMETER-001",
                "analog operand is not an enclosing Real parameter",
                Some(path)
              )
        )

      def operand(path: String): (String, String) =
        values.get(path).orElse(parameterValues.get(path)).getOrElse:
          if parameterSymbols.contains(path) then parameterValue(path)
          else if snapshot.analogProcedural.exists(program =>
              program.owner == region.module &&
                program.variables.exists(record => record.authoredPath.contains(path))
            )
          then
            val variable = snapshot.analogProcedural.filter(_.owner == region.module)
              .flatMap(_.variables).find(_.authoredPath.contains(path)).get
            val result = s"%analog_${regionIndex}_held_${values.size}"
            lines += operation(
              "nodal.analog_held_read",
              results = Vector(result),
              resultTypes = Vector("f64"),
              attributes = Vector(
                "variable" -> quoted(variable.variable.identity),
                "owner" -> quoted(region.module),
                "metadata" -> bridgeMetadata(path, Vector.empty)
              ),
              semanticPath = path
            )
            values.update(path, result -> "f64")
            result -> "f64"
          else
            fail(
              "NODAL-RC-ORDER-001",
              "analog expression operand is unavailable or was defined out of order",
              Some(path)
            )

      region.expressions.zipWithIndex.foreach: (expression, index) =>
        val result = s"%analog_${regionIndex}_expr_$index"
        val metadata = bridgeMetadata(
          expression.path,
          expression.unit.toVector.map(unit => "unit" -> quoted(unit))
        )
        expression.operation match
          case "real_literal" =>
            val value = expression.literal.flatMap(_.toDoubleOption).getOrElse(
              fail("NODAL-RC-LITERAL-001", "real literal is unavailable", Some(expression.path))
            )
            lines += operation(
              "nodal.real_literal",
              results = Vector(result),
              resultTypes = Vector("f64"),
              attributes = Vector(
                "value" -> s"${java.lang.Double.toString(value)} : f64",
                "metadata" -> metadata
              ),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> "f64")
          case "potential_access" | "flow_access" =>
            val branch = accessBranches.getOrElse(
              expression.path,
              fail(
                "NODAL-RC-BRANCH-004",
                "analog access branch is unavailable",
                Some(expression.path)
              )
            )
            val kind = if expression.operation == "potential_access" then "potential" else "flow"
            lines += operation(
              "nodal.access",
              results = Vector(result),
              operands = Vector(branch._1),
              operandTypes = Vector(branch._2),
              resultTypes = Vector("f64"),
              attributes = Vector(
                "kind" -> quoted(kind),
                "metadata" -> metadata
              ),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> "f64")
          case "analog_add" | "analog_sub" | "analog_mul" | "analog_div" =>
            if expression.operands.size != 2 then
              fail(
                "NODAL-RC-ARITY-001",
                "binary analog operation has invalid arity",
                Some(expression.path)
              )
            val lhs = operand(expression.operands(0))
            val rhs = operand(expression.operands(1))
            lines += operation(
              s"nodal.${expression.operation}",
              results = Vector(result),
              operands = Vector(lhs._1, rhs._1),
              operandTypes = Vector(lhs._2, rhs._2),
              resultTypes = Vector("f64"),
              attributes = Vector("metadata" -> metadata),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> "f64")
          case "analog_neg" =>
            val input = operand(expression.operands.head)
            lines += operation(
              "nodal.analog_neg",
              results = Vector(result),
              operands = Vector(input._1),
              operandTypes = Vector(input._2),
              resultTypes = Vector("f64"),
              attributes = Vector("metadata" -> metadata),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> "f64")
          case "analog_ddt" =>
            if expression.operands.size != 1 then
              fail("NODAL-RC-ARITY-001", "ddt operation has invalid arity", Some(expression.path))
            val input = operand(expression.operands.head)
            val contract = continuousOperator(expression.path, "analog_ddt")
            lines += operation(
              "nodal.analog_ddt",
              results = Vector(result),
              operands = Vector(input._1),
              operandTypes = Vector(input._2),
              resultTypes = Vector("f64"),
              attributes =
                continuousOperatorAttributes(contract) :+ ("metadata" -> metadata),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> "f64")
          case "analog_idt" =>
            if expression.operands.size != 1 && expression.operands.size != 2 then
              fail("NODAL-RC-ARITY-001", "idt operation has invalid arity", Some(expression.path))
            val inputs = expression.operands.map(operand)
            val contract = continuousOperator(expression.path, "analog_idt")
            lines += operation(
              "nodal.analog_idt",
              results = Vector(result),
              operands = inputs.map(_._1),
              operandTypes = inputs.map(_._2),
              resultTypes = Vector("f64"),
              attributes =
                continuousOperatorAttributes(contract) :+ ("metadata" -> metadata),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> "f64")
          case "analog_transition" | "analog_slew" | "analog_absdelay" |
              "analog_abstime" | "analog_bound_step" =>
            val contract = snapshot.waveformOperators.find(_.path == expression.path).getOrElse(
              fail(
                "NODAL-ANALOG-036-002",
                "waveform expression has no contract",
                Some(expression.path)
              )
            )
            if contract.operation != expression.operation ||
              contract.operands != expression.operands
            then
              fail(
                "NODAL-ANALOG-036-002",
                "waveform inventory differs from expression",
                Some(expression.path)
              )
            val inputs = expression.operands.map(operand)
            val effect = expression.operation == "analog_bound_step"
            lines += operation(
              s"nodal.${expression.operation}",
              results = if effect then Vector.empty else Vector(result),
              operands = inputs.map(_._1),
              operandTypes = inputs.map(_._2),
              resultTypes = if effect then Vector.empty else Vector("f64"),
              attributes = waveformOperatorAttributes(contract) :+ ("metadata" -> metadata),
              semanticPath = expression.path
            )
            if !effect then values.update(expression.path, result -> "f64")
          case operationName =>
            fail(
              "NODAL-RC-OPERATION-001",
              s"analog operation '$operationName' is outside the Increment 25 RC subset",
              Some(expression.path)
            )

      region.contributions.foreach: contribution =>
        val branch = accessBranches.getOrElse(
          contribution.target,
          fail(
            "NODAL-RC-CONTRIBUTION-001",
            "contribution branch is unavailable",
            Some(contribution.path)
          )
        )
        val value = operand(contribution.value)
        lines += operation(
          "nodal.contribute",
          operands = Vector(branch._1, value._1),
          operandTypes = Vector(branch._2, value._2),
          attributes = Vector(
            "kind" -> quoted(contribution.kind),
            "metadata" -> bridgeMetadata(
              contribution.path,
              Vector("vertical_slice" -> quoted("rc"))
            )
          ),
          semanticPath = contribution.path
        )

      operation(
        "nodal.analog",
        attributes = Vector(
          "metadata" -> bridgeMetadata(
            region.path,
            Vector("vertical_slice" -> quoted("rc"))
          )
        ),
        regions = Vector(lines.mkString("\n")),
        semanticPath = region.path
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
      else if dataType == "f64" then
        value.toDoubleOption
          .map(number => s"${java.lang.Double.toString(number)} : f64")
          .getOrElse(
            fail(
              "NODAL-BRIDGE-021",
              s"unsupported real parameter default '$value'",
              Some(path)
            )
          )
      else
        value.toLongOption match
          case Some(number) => s"$number : i64"
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
        snapshot.origins.sortBy(entry => (entry.semanticPath, entry.id)).map: entry =>
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
      val ordinary = snapshot.sourceMap.sortBy(entry =>
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
      array(ordinary ++ AnalogProceduralMlir.sourceMapEntries(snapshot))

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
        attributes: Vector[(String, String)],
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

    private def stableModuleSymbol(value: String): String =
      val base = normalizeSymbol(lastSegment(value))
      val collisions = modules.count(module => normalizeSymbol(lastSegment(module.path)) == base)
      if collisions == 1 then base
      else s"${base}_${ScalaToMlirBridge.digest(s"module:$value").take(10)}"

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
