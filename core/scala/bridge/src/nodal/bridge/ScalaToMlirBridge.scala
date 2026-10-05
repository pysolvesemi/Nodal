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
    private val modulesByPath = modules.map(module => module.path -> module).toMap
    private val parameterExpressionsByOwner = snapshot.parameterExpressions
      .groupBy(_.owner)
      .view
      .mapValues(_.map(expression => expression.path -> expression).toMap)
      .toMap
    private val generatedRegionsByOwner = snapshot.generatedRegions
      .groupBy(_.owner)
      .view
      .mapValues(_.sortBy(_.path))
      .toMap
    private val shapeIndicesByOwner = snapshot.shapeIndices
      .groupBy(_.owner)
      .view
      .mapValues(_.sortBy(_.path))
      .toMap
    private val topologyByOwner = snapshot.topology
      .groupBy(_.owner)
      .view
      .mapValues(_.sortBy(edge => (edge.kind, edge.left, edge.right)))
      .toMap
    private val topologyEndpointOwners = snapshot.topology.iterator
      .flatMap(edge => Iterator(edge.left, edge.right))
      .toSet
      .map(path => path -> resolveOwningModule(path))
      .toMap
    private val externallyBoundTerminalsByModule = snapshot.topology.iterator
      .flatMap: edge =>
        Iterator(edge.left, edge.right).flatMap: path =>
          val owner = topologyEndpointOwners(path)
          Option.when(edge.owner != owner)(owner -> path)
      .toVector
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap
    private val interfaceEntriesByModule = snapshot.interfaceAbi
      .groupBy(entry => resolveOwningModule(entry.logicalPath))
      .view
      .mapValues(_.sortBy(_.logicalPath))
      .toMap
    private val resolvedEntriesByModule = snapshot.resolvedNets
      .groupBy(entry => resolveOwningModule(entry.path))
      .view
      .mapValues(_.sortBy(_.path))
      .toMap
    private val domainBindingOwners = modules.iterator
      .flatMap(_.domains.iterator.flatMap(_.binding))
      .toSet
      .map(path => path -> resolveOwningModule(path))
      .toMap
    private val sourceByPath = snapshot.sourceMap
      .sortBy(entry => (entry.semanticPath, entry.source.path, entry.source.line))
      .map(entry => entry.semanticPath -> entry.source)
      .toMap
    private var moduleSymbols = modules.map(module =>
      module.path -> stableModuleSymbol(module.path)
    ).toMap
    private var emittedModules = modules

    def render(): String =
      validate()
      canonicalizeModuleDefinitions()
      val body =
        (standardConservativeDeclarations ++ emittedModules.map(renderModule)).mkString("\n\n")
      val attributes = Vector(
        "nodal.bridge.schema" -> quoted(Schema),
        "nodal.bridge.version" -> integer(Version),
        "nodal.bridge.root" -> quoted(snapshot.root),
        "nodal.root.module" -> symbolReference(moduleSymbols(snapshot.root)),
        "nodal.root.parameter_bindings" -> rootParameterBindingInventory,
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
      ) ++
        (if snapshot.transferOperators.isEmpty then Vector.empty
         else
           Vector(
             "nodal.bridge.transfer_operators" -> transferOperatorInventory
           )) ++
        (if snapshot.analogFunctions.isEmpty then Vector.empty
         else
           Vector("nodal.bridge.analog_functions" -> AnalogUserFunctionMlir.inventory(snapshot))) ++
        mandatoryVerificationAttributes
      normalize(
        s"""module attributes ${dictionary(attributes)} {
${indent(body, 2)}
}
"""
      )

    private def canonicalizeModuleDefinitions(): Unit =
      val childrenByPath = modules
        .map(module => module.path -> module.instances.map(_.childModule).distinct)
        .toMap
      val parentsByChild = mutable.Map.empty[String, mutable.ArrayBuffer[String]]
      childrenByPath.foreach: (parent, children) =>
        children.foreach: child =>
          if !modulesByPath.contains(child) then
            fail(
              "NODAL-BRIDGE-007",
              "instance child Module is absent from the snapshot",
              Some(child)
            )
          parentsByChild.getOrElseUpdate(child, mutable.ArrayBuffer.empty) += parent

      val remainingChildren = mutable.Map.from(
        childrenByPath.map((path, children) => path -> children.size)
      )
      val depths = mutable.Map.empty[String, Int]
      val leaves = modules.iterator
        .filter(module => remainingChildren(module.path) == 0)
        .map(_.path)
        .toVector
      leaves.foreach(path => depths.update(path, 0))
      val ready = mutable.ArrayDeque.from(leaves)
      while ready.nonEmpty do
        val child = ready.removeHead()
        val childDepth = depths.getOrElse(child, 0)
        parentsByChild.getOrElse(child, mutable.ArrayBuffer.empty).foreach: parent =>
          depths.update(parent, depths.getOrElse(parent, 0).max(childDepth + 1))
          val next = remainingChildren(parent) - 1
          remainingChildren.update(parent, next)
          if next == 0 then ready.append(parent)
      if depths.size != modules.size then
        val cycle = modules.iterator.map(_.path).filterNot(depths.contains).toVector.sorted.head
        fail(
          "NODAL-BRIDGE-035",
          "module dependency cycle prevents definition canonicalization",
          Some(cycle)
        )

      val representativeByPath = mutable.Map.from(modules.map(module => module.path -> module.path))
      depths.toVector.groupBy(_._2).toVector.sortBy(_._1).foreach: (_, entries) =>
        val representativeByStructure = mutable.LinkedHashMap.empty[String, String]
        entries.map(_._1).sorted.foreach: path =>
          if path != snapshot.root then
            val module = modulesByPath(path)
            val structure = normalizedModuleStructure(module)
            val representative = representativeByStructure.getOrElseUpdate(structure, path)
            representativeByPath.update(path, representative)
        entries.foreach: (path, _) =>
          moduleSymbols = moduleSymbols.updated(
            path,
            stableModuleSymbol(representativeByPath(path))
          )

      emittedModules = modules.filter(module => representativeByPath(module.path) == module.path)
      val emittedPaths = emittedModules.map(_.path)
      val representativeSymbols = emittedPaths.map(path =>
        path -> stableModuleSymbol(path, emittedPaths)
      ).toMap
      moduleSymbols = modules
        .map(module => module.path -> representativeSymbols(representativeByPath(module.path)))
        .toMap

    private def normalizedModuleStructure(module: KernelModuleSnapshot): String =
      val normalizedPath = renderModule(module).replace(module.path, "$module")
      val symbolAttribute = "sym_name = " + quoted(moduleSymbols(module.path))
      val position = normalizedPath.indexOf(symbolAttribute)
      if position < 0 then
        fail(
          "NODAL-BRIDGE-035",
          "module definition has no canonical symbol attribute",
          Some(module.path)
        )
      module.className + "\u0000" +
        normalizedPath.patch(position, "sym_name = \"$module\"", symbolAttribute.length)

    private def standardConservativeDeclarations: Vector[String] =
      val needsElectrical = modules.exists(module => terminalDeclarations(module).nonEmpty)
      if !needsElectrical then Vector.empty
      else
        val standardMetadata = (semanticPath: String) =>
          dictionary(
            Vector(
              "bridge_schema" -> quoted(Schema),
              "bridge_version" -> integer(Version),
              "semantic_path" -> quoted(semanticPath)
            )
          )
        Vector(
          operation(
            "nodal.nature",
            attributes = Vector(
              "sym_name" -> quoted("Voltage"),
              "units" -> quoted("V"),
              "access" -> quoted("V"),
              "abstol" -> "1.0e-6 : f64",
              "dimension" -> quoted("voltage"),
              "metadata" -> standardMetadata("std.Voltage")
            ),
            semanticPath = "std.Voltage"
          ),
          operation(
            "nodal.nature",
            attributes = Vector(
              "sym_name" -> quoted("Current"),
              "units" -> quoted("A"),
              "access" -> quoted("I"),
              "abstol" -> "1.0e-12 : f64",
              "dimension" -> quoted("current"),
              "metadata" -> standardMetadata("std.Current")
            ),
            semanticPath = "std.Current"
          ),
          operation(
            "nodal.discipline",
            attributes = Vector(
              "sym_name" -> quoted("electrical"),
              "domain" -> quoted("continuous"),
              "potential" -> symbolReference("Voltage"),
              "flow" -> symbolReference("Current"),
              "metadata" -> dictionary(
                Vector(
                  "bridge_schema" -> quoted(Schema),
                  "bridge_version" -> integer(Version),
                  "kind" -> quoted("conservative"),
                  "semantic_path" -> quoted("std.electrical")
                )
              )
            ),
            semanticPath = "std.electrical"
          )
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
      val analog = snapshot.analogRegions.nonEmpty || snapshot.analogFunctions.nonEmpty ||
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

    private def transferOperatorAttributes(value: KernelTransferOperatorSnapshot)
        : Vector[(String, String)] =
      Vector(
        "transfer_kind" -> quoted(value.kind),
        "contract_version" -> quoted("1"),
        "numerator_size" -> integer(value.numeratorSize),
        "denominator_size" -> integer(value.denominatorSize),
        "operator_id" -> quoted(value.path),
        "state_id" -> quoted(value.path + ".state"),
        "owner" -> quoted(value.owner),
        "coefficient_order" ->
          quoted(if value.kind == "laplace_nd" then "ascending_s" else "ascending_z_inverse"),
        "initialization" -> quoted("simulator-default"),
        "result_dimension" -> quoted(value.resultDimension)
      )

    private def transferOperatorInventory: String =
      array(snapshot.transferOperators.sortBy(_.path).map: value =>
        dictionary(transferOperatorAttributes(value) ++ Vector(
          "operands" -> array(value.operands.map(quoted))
        ) ++ value.source.toVector.flatMap(source =>
          Vector(
            "source_file" -> quoted(source.path),
            "source_line" -> integer(source.line),
            "source_column" -> integer(source.column)
          )
        )))

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
        snapshot.parameterExpressions.map(_.path),
        "NODAL-BRIDGE-034",
        "parameter-expression semantic path"
      )
      requireUnique(
        snapshot.generatedRegions.map(_.path),
        "NODAL-BRIDGE-043",
        "generated-region semantic path"
      )
      requireUnique(
        snapshot.generatedRegions.map(_.induction),
        "NODAL-BRIDGE-043",
        "generated induction identity"
      )
      requireUnique(
        snapshot.generatedRegions.flatMap(_.declarations),
        "NODAL-BRIDGE-043",
        "generated declaration ownership"
      )
      requireUnique(
        snapshot.shapeIndices.map(_.path),
        "NODAL-BRIDGE-044",
        "shape-index semantic path"
      )
      val generatedByPath =
        snapshot.generatedRegions.map(region => region.path -> region).toMap
      snapshot.generatedRegions.foreach: region =>
        val module = modulesByPath.getOrElse(
          region.owner,
          fail(
            "NODAL-BRIDGE-043",
            "generated region has no owning Module",
            Some(region.path)
          )
        )
        def owned(path: String): Boolean =
          path.startsWith(s"${region.owner}.")
        if !owned(region.path) || !owned(region.induction) ||
          region.path.trim != region.path || region.induction.trim != region.induction
        then
          fail(
            "NODAL-BRIDGE-043",
            "generated region or induction identity is noncanonical or escapes its owning Module",
            Some(region.path)
          )
        val declarationPaths = module.declarations.map(_.path).toSet
        region.declarations.foreach: declaration =>
          if !declarationPaths.contains(declaration) then
            fail(
              "NODAL-BRIDGE-043",
              "generated declaration is absent from its owning Module",
              Some(declaration)
            )
        val seen = mutable.HashSet(region.path)
        var parentPath = region.parent
        while parentPath.nonEmpty do
          val parent = generatedByPath.getOrElse(
            parentPath.get,
            fail(
              "NODAL-BRIDGE-043",
              "generated parent region is absent",
              Some(region.path)
            )
          )
          if !seen.add(parent.path) then
            fail(
              "NODAL-BRIDGE-043",
              "generated parent chain contains a cycle",
              Some(region.path)
            )
          if parent.owner != region.owner ||
            !region.path.startsWith(s"${parent.path}.")
          then
            fail(
              "NODAL-BRIDGE-043",
              "generated parent does not own the nested region",
              Some(region.path)
            )
          parentPath = parent.parent
      snapshot.parameterExpressions.foreach: expression =>
        if !moduleSymbols.contains(expression.owner) then
          fail(
            "NODAL-BRIDGE-034",
            "parameter expression has no owning Module",
            Some(expression.path)
          )
      snapshot.shapeIndices.foreach: index =>
        val module = modulesByPath.getOrElse(
          index.owner,
          fail("NODAL-BRIDGE-044", "shape index has no owning Module", Some(index.path))
        )
        if !index.path.startsWith(s"${index.owner}.") || index.path.trim != index.path ||
          index.input.trim != index.input
        then
          fail(
            "NODAL-BRIDGE-044",
            "shape-index identity is noncanonical or escapes its owning Module",
            Some(index.path)
          )
        if !sourceByPath.contains(index.path) then
          fail(
            "NODAL-BRIDGE-044",
            "shape-index identity has no source-map entry",
            Some(index.path)
          )
        val declaration = module.declarations.find(_.path == index.input).getOrElse(
          fail("NODAL-BRIDGE-044", "shape-index input declaration is absent", Some(index.path))
        )
        if declaration.kind != "input" && declaration.kind != "output" then
          fail(
            "NODAL-BRIDGE-044",
            "shape-index input must be a module port",
            Some(index.path)
          )
        val dataType = declaration.dataType.getOrElse(
          fail("NODAL-BRIDGE-044", "shape-index input type is absent", Some(index.path))
        )
        val (_, dimensions) = vecTypeParts(dataType, index.path)
        if index.indices.size != dimensions.size then
          fail("NODAL-BRIDGE-044", "shape-index rank does not match input rank", Some(index.path))
        index.indices.zip(dimensions).zipWithIndex.foreach:
          case ((position, dimension), axis) =>
            val minimum = shapeDimensionMinimum(module, dimension, index.path)
            if position < 0 || position >= minimum then
              fail(
                "NODAL-BRIDGE-044",
                s"shape index is not valid for every legal extent at axis $axis",
                Some(index.path)
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
      requireUnique(
        snapshot.transferOperators.map(_.path),
        "NODAL-ANALOG-040-002",
        "transfer state"
      )
      AnalogUserFunctionMlir.validate(snapshot)
      requireUnique(
        snapshot.analogFunctions.map(value => s"${value.owner}.${value.definition.name}"),
        "NODAL-ANALOG-041-002",
        "analog function declaration"
      )
      snapshot.analogFunctions.foreach: value =>
        if !moduleSymbols.contains(value.owner) then
          fail("NODAL-ANALOG-041-005", "function has no owning module", Some(value.owner))
      val transfers = snapshot.analogRegions.flatMap(region =>
        region.expressions
          .filter(_.operation.startsWith(AnalogTransferContract.Prefix))
          .map(value => value.path -> (region.module, value))
      ).toMap
      if transfers.keySet != snapshot.transferOperators.map(_.path).toSet then
        fail("NODAL-ANALOG-040-002", "transfer inventory is incomplete or orphaned", None)
      snapshot.transferOperators.foreach: contract =>
        val (owner, expression) = transfers(contract.path)
        val timing = contract.operands.size.toLong - 1L - contract.numeratorSize -
          contract.denominatorSize
        if owner != contract.owner || expression.operands != contract.operands ||
          expression.operation != AnalogTransferContract.Prefix + contract.kind ||
          contract.numeratorSize <= 0 || contract.denominatorSize <= 0 ||
          !(contract.kind == "laplace_nd" && timing == 0 ||
            contract.kind == "zi_nd" && timing >= 1 && timing <= 3)
        then
          fail("NODAL-ANALOG-040-002", "invalid transfer inventory contract", Some(contract.path))
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
      val instancesByChildModule = module.instances
        .groupBy(_.childModule)
        .view
        .mapValues(_.head)
        .toMap
      val expressionsByPath =
        parameterExpressionsByOwner.getOrElse(module.path, Map.empty)
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
          domain.binding.filter(actual => domainBindingOwners(actual) == module.path).foreach:
            actual =>
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
            body ++= renderParameter(declaration)
          case _ => ()

      shapeIndicesByOwner.getOrElse(module.path, Vector.empty).zipWithIndex.foreach:
        (index, ordinal) =>
          val declaration = declarationsByPath(index.input)
          val sourceType = declaration.dataType.getOrElse(
            fail("NODAL-BRIDGE-044", "shape-index input type is absent", Some(index.path))
          )
          val (elementText, _) = vecTypeParts(sourceType, index.path)
          val shapedType = parseType(sourceType, index.path)
          val elementType = parseType(elementText, index.path)
          val inputValue = s"%shape_index_${ordinal}_input"
          body += operation(
            "nodal.port_value",
            results = Vector(inputValue),
            resultTypes = Vector(shapedType),
            attributes = Vector(
              "port" -> symbolReference(stableLocalSymbol("port", declaration.name)),
              "metadata" -> bridgeMetadata(index.input, Vector("use" -> quoted("shape_index")))
            ),
            semanticPath = index.input
          )
          val indexValues = index.indices.zipWithIndex.map: (position, axis) =>
            val result = s"%shape_index_${ordinal}_index_$axis"
            body += operation(
              "nodal.constant",
              results = Vector(result),
              resultTypes = Vector("index"),
              attributes = Vector(
                "value" -> s"$position : index",
                "metadata" -> bridgeMetadata(
                  index.path,
                  Vector("axis" -> integer(axis))
                )
              ),
              semanticPath = index.path
            )
            result
          val result = s"%shape_index_$ordinal"
          body += operation(
            "nodal.shape_index",
            results = Vector(result),
            operands = inputValue +: indexValues,
            operandTypes = shapedType +: Vector.fill(indexValues.size)("index"),
            resultTypes = Vector(elementType),
            attributes = Vector(
              "metadata" -> bridgeMetadata(
                index.path,
                Vector("input_path" -> quoted(index.input))
              )
            ),
            semanticPath = index.path
          )

      module.instances.sortBy(_.path).zipWithIndex.foreach: (instance, index) =>
        body ++= renderInstance(
          instance,
          index,
          parameterSymbols,
          declarationsByPath,
          expressionsByPath
        )

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

      val externallyBoundTerminals =
        externallyBoundTerminalsByModule.getOrElse(module.path, Set.empty)
      val generatedDeclarationPaths =
        generatedRegionsFor(module).flatMap(_.declarations).toSet
      terminalDeclarations(module)
        .filterNot(declaration => generatedDeclarationPaths.contains(declaration.path))
        .zipWithIndex
        .foreach: (declaration, index) =>
          val discipline = conservativeDiscipline(declaration)
          val resultType = s"""!nodal.terminal<${quoted(discipline)}>"""
          val result = s"%terminal_$index"
          val opName =
            if declaration.kind == "analog-node" then "nodal.node"
            else "nodal.terminal"
          val boundaryAttributes = declaration.kind match
            case "analog-input" => Vector(
                "direction" -> quoted("input"),
                "flow_orientation" -> quoted("into_component")
              )
            case "analog-output" => Vector(
                "direction" -> quoted("output"),
                "flow_orientation" -> quoted("into_component")
              )
            case "analog-inout" | "conservative-terminal" => Vector(
                "direction" -> quoted("inout"),
                "flow_orientation" -> quoted("into_component")
              )
            case _ => Vector.empty
          body += operation(
            opName,
            results = Vector(result),
            resultTypes = Vector(resultType),
            attributes = Vector(
              "name" -> quoted(declaration.name),
              "source_path" -> quoted(declaration.path),
              "metadata" -> bridgeMetadata(
                declaration.path,
                Vector(
                  "declaration_kind" -> quoted(declaration.kind),
                  "declared_discipline" -> quoted(
                    declaration.attributes.toMap.getOrElse("discipline", discipline)
                  )
                ) ++ Option.when(externallyBoundTerminals.contains(declaration.path))(
                  "allow_floating" -> boolean(true)
                )
              )
            ) ++ boundaryAttributes,
            semanticPath = declaration.path
          )
          values.update(declaration.path, result -> resultType)

      val generatedStatic = mutable.ArrayBuffer.empty[String]
      val generatedStaticValues = mutable.LinkedHashMap.empty[String, (String, String)]
      var nextGeneratedStaticValue = 0
      def allocateGeneratedStaticValue(): String =
        val result = "%generated_bound_value_" + nextGeneratedStaticValue
        nextGeneratedStaticValue += 1
        result
      val generatedBodies = generatedRegionsFor(module)
        .filter(_.parent.isEmpty)
        .map(region =>
          renderGeneratedRegion(
            module,
            region,
            declarationsByPath,
            parameterSymbols,
            expressionsByPath,
            generatedStatic,
            generatedStaticValues,
            () => allocateGeneratedStaticValue()
          )
        )
      body ++= generatedStatic
      body ++= generatedBodies

      val topology = topologyEntries(module)
      val childEndpoints = topology
        .flatMap(edge => Vector(edge.left, edge.right))
        .distinct
        .filter(path => topologyEndpointOwners(path) != module.path)
        .sorted
      childEndpoints.zipWithIndex.foreach: (path, index) =>
        val owner = topologyEndpointOwners(path)
        val child = modulesByPath.getOrElse(
          owner,
          fail("NODAL-BRIDGE-007", "child Module is absent from the snapshot", Some(path))
        )
        val declaration = child.declarations.find(_.path == path).getOrElse(
          fail("NODAL-BRIDGE-032", "child endpoint declaration is absent", Some(path))
        )
        val instance = instancesByChildModule.getOrElse(
          owner,
          fail(
            "NODAL-BRIDGE-026",
            "child endpoint is not owned by an immediate instance",
            Some(path)
          )
        )
        val direction = declaration.kind match
          case "analog-input" => "input"
          case "analog-output" => "output"
          case "analog-inout" | "conservative-terminal" => "inout"
          case _ =>
            fail("NODAL-BRIDGE-027", "child endpoint is not a boundary terminal", Some(path))
        val discipline = conservativeDiscipline(declaration)
        val resultType = s"""!nodal.terminal<${quoted(discipline)}>"""
        val result = s"%instance_terminal_$index"
        body += operation(
          "nodal.instance_terminal",
          results = Vector(result),
          resultTypes = Vector(resultType),
          attributes = Vector(
            "instance" -> symbolReference(
              stableLocalSymbol("instance", lastSegment(instance.path))
            ),
            "port" -> quoted(declaration.name),
            "name" -> quoted(s"${lastSegment(instance.path)}.${declaration.name}"),
            "direction" -> quoted(direction),
            "flow_orientation" -> quoted("out_of_component"),
            "source_path" -> quoted(path),
            "metadata" -> bridgeMetadata(
              path,
              Vector(
                "child_path" -> quoted(owner),
                "declared_discipline" -> quoted(
                  declaration.attributes.toMap.getOrElse("discipline", discipline)
                )
              )
            )
          ),
          semanticPath = path
        )
        values.update(path, result -> resultType)

      topology.foreach: edge =>
        if Set("terminal-connect", "node-connect").contains(edge.kind) then
          (values.get(edge.left), values.get(edge.right)) match
            case (Some((leftValue, leftType)), Some((rightValue, rightType)))
                if leftType == rightType =>
              body += operation(
                "nodal.connect",
                operands = Vector(leftValue, rightValue),
                operandTypes = Vector(leftType, rightType),
                attributes = Vector(
                  "connection_id" -> quoted(s"${edge.left}<->${edge.right}"),
                  "source_path" -> quoted(s"${edge.left}<->${edge.right}"),
                  "metadata" -> bridgeMetadata(
                    s"${edge.left}->${edge.right}",
                    Vector("topology_kind" -> quoted(edge.kind))
                  )
                ),
                semanticPath = edge.left
              )
            case (Some((_, leftType)), Some((_, rightType))) =>
              fail(
                "NODAL-BRIDGE-028",
                s"conservative connection type mismatch '$leftType' versus '$rightType'",
                Some(edge.left)
              )
            case _ =>
              fail(
                "NODAL-BRIDGE-029",
                "conservative connection endpoint is unavailable",
                Some(edge.left)
              )

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

      body ++= AnalogUserFunctionMlir.renderModule(snapshot, module.path)

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
                  declaration.dataType.exists(value => value == "Real" || value == "Integer") =>
              val symbol = parameterSymbols.getOrElse(
                path,
                fail("NODAL-RC-PARAMETER-001", "real parameter symbol is unavailable", Some(path))
              )
              val result = s"%analog_${regionIndex}_parameter_${parameterValues.size}"
              val outputType = if declaration.dataType.contains("Integer") then
                "!nodal.quantity<\"integer\", \"1\">"
              else "f64"
              lines += operation(
                "nodal.parameter_ref",
                results = Vector(result),
                resultTypes = Vector(outputType),
                attributes = Vector(
                  "parameter" -> symbolReference(symbol),
                  "metadata" -> bridgeMetadata(path, Vector.empty)
                ),
                semanticPath = path
              )
              result -> outputType
            case _ =>
              fail(
                "NODAL-RC-PARAMETER-001",
                "analog operand is not an enclosing Real or Integer parameter",
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
          case "boolean" =>
            val value = expression.literal.flatMap(_.toBooleanOption).getOrElse(
              fail("NODAL-ANALOG-038-003", "Boolean literal is unavailable", Some(expression.path))
            )
            lines += operation(
              "nodal.const_literal",
              results = Vector(result),
              resultTypes = Vector("i1"),
              attributes = Vector(
                "value" -> value.toString,
                "spelling" -> quoted(if value then "1" else "0"),
                "metadata" -> metadata
              ),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> "i1")
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
          case name if name.startsWith(AnalogTransferContract.Prefix) =>
            val contract = snapshot.transferOperators.find(_.path == expression.path).getOrElse(
              fail("NODAL-ANALOG-040-002", "transfer has no state contract", Some(expression.path))
            )
            if name != AnalogTransferContract.Prefix + contract.kind ||
              contract.operands != expression.operands || contract.owner != region.module
            then
              fail(
                "NODAL-ANALOG-040-002",
                "transfer inventory differs from expression",
                Some(expression.path)
              )
            val inputs = expression.operands.map(operand)
            lines += operation(
              "nodal.analog_transfer",
              results = Vector(result),
              operands = inputs.map(_._1),
              operandTypes = inputs.map(_._2),
              resultTypes = Vector("f64"),
              attributes = transferOperatorAttributes(contract) :+ ("metadata" -> metadata),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> "f64")
          case name if name.startsWith(AnalogNoiseContract.Prefix) =>
            val contract = snapshot.noiseOperators.find(_.path == expression.path).getOrElse(
              fail("NODAL-ANALOG-039-002", "noise source has no contract", Some(expression.path))
            )
            if name != AnalogNoiseContract.Prefix + contract.kind ||
              contract.operands != expression.operands || contract.owner != region.module
            then
              fail(
                "NODAL-ANALOG-039-002",
                "noise inventory differs from its expression",
                Some(expression.path)
              )
            val inputs = expression.operands.map(operand)
            lines += operation(
              "nodal.analog_noise",
              results = Vector(result),
              operands = inputs.map(_._1),
              operandTypes = inputs.map(_._2),
              resultTypes = Vector("f64"),
              attributes = Vector(
                "noise_kind" -> quoted(contract.kind),
                "contract_version" -> quoted("1"),
                "noise_name" -> quoted(contract.label),
                "source_id" -> quoted(contract.path),
                "owner" -> quoted(contract.owner),
                "correlation" -> quoted("independent"),
                "analyses" -> "[\"noise\"]",
                "result_dimension" -> quoted(contract.resultDimension),
                "metadata" -> metadata
              ),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> "f64")
          case name if name.startsWith(AnalogUserFunctionRuntime.CallPrefix) =>
            val functionName = name.stripPrefix(AnalogUserFunctionRuntime.CallPrefix)
            val definition = snapshot.analogFunctions.find(value =>
              value.owner == region.module && value.definition.name == functionName
            )
              .map(_.definition).getOrElse(fail(
                "NODAL-ANALOG-041-005",
                "unresolved module-local analog function",
                Some(expression.path)
              ))
            if expression.operands.size != definition.inputs.size then
              fail("NODAL-ANALOG-041-005", "function call arity mismatch", Some(expression.path))
            val inputs = expression.operands.map(operand)
            val outputType = AnalogUserFunctionMlir.callType(definition.result)
            lines += operation(
              "nodal.analog_user_call",
              results = Vector(result),
              operands = inputs.map(_._1),
              operandTypes = inputs.map(_._2),
              resultTypes = Vector(outputType),
              attributes = Vector(
                "callee" -> symbolReference(functionName),
                "contract_version" -> quoted("1"),
                "metadata" -> metadata
              ),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> outputType)
          case "integer" =>
            val value = expression.literal.flatMap(_.toIntOption).getOrElse(
              fail(
                "NODAL-ANALOG-041-003",
                "integer argument literal is unavailable",
                Some(expression.path)
              )
            )
            val outputType = "!nodal.quantity<\"integer\", \"1\">"
            lines += operation(
              "nodal.analog_integer_literal",
              results = Vector(result),
              resultTypes = Vector(outputType),
              attributes = Vector(
                "value" -> s"$value : i32",
                "metadata" -> metadata
              ),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> outputType)
          case name if name.startsWith(AnalogFunctionRegistry.FunctionPrefix) =>
            val id = name.stripPrefix(AnalogFunctionRegistry.FunctionPrefix)
            val descriptor = AnalogFunctionContract.entry(id)
            if expression.operands.size != descriptor.arity then
              fail(
                "NODAL-ANALOG-038-002",
                "function arity differs from registry",
                Some(expression.path)
              )
            val inputs = expression.operands.map(operand)
            lines += operation(
              "nodal.analog_function",
              results = Vector(result),
              operands = inputs.map(_._1),
              operandTypes = inputs.map(_._2),
              resultTypes = Vector("f64"),
              attributes = Vector(
                "function_id" -> quoted(id),
                "registry_version" -> quoted(AnalogFunctionRegistry.Version),
                "metadata" -> metadata
              ),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> "f64")
          case name if name.startsWith(AnalogFunctionRegistry.AnalysisPrefix) =>
            val id = name.stripPrefix(AnalogFunctionRegistry.AnalysisPrefix)
            if !AnalogFunctionRegistry.analysisTargets.contains(id) || expression.operands.nonEmpty
            then
              fail("NODAL-ANALOG-038-001", "invalid analysis query", Some(expression.path))
            lines += operation(
              "nodal.analog_analysis",
              results = Vector(result),
              resultTypes = Vector("i1"),
              attributes = Vector(
                "analysis_kind" -> quoted(id),
                "registry_version" -> quoted(AnalogFunctionRegistry.Version),
                "metadata" -> metadata
              ),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> "i1")
          case "real_gt" | "real_ge" | "real_lt" | "real_le" |
              "bool_and" | "bool_or" | "bool_not" | "analog_select" =>
            val inputs = expression.operands.map(operand)
            val select = expression.operation == "analog_select"
            val compare = expression.operation.startsWith("real_")
            val arity = if select then 3 else if expression.operation == "bool_not" then 1 else 2
            if inputs.size != arity then
              fail("NODAL-ANALOG-038-002", "invalid query expression arity", Some(expression.path))
            val resultType = if select then inputs(1)._2 else "i1"
            val attributes =
              if compare then
                Vector("predicate" -> quoted(expression.operation.stripPrefix("real_")))
              else if select then Vector.empty
              else Vector("operator_name" -> quoted(expression.operation.stripPrefix("bool_")))
            lines += operation(
              if compare then "nodal.analog_compare"
              else if select then "nodal.analog_select"
              else "nodal.analog_logic",
              results = Vector(result),
              operands = inputs.map(_._1),
              operandTypes = inputs.map(_._2),
              resultTypes = Vector(resultType),
              attributes = attributes :+ ("metadata" -> metadata),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> resultType)
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
    ): Vector[String] =
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
      val classification = attributes.getOrElse("classification", "ordinary")
      val parameterSymbol = stableLocalSymbol("parameter", declaration.name)
      val rendered = mutable.ArrayBuffer(
        operation(
          "nodal.parameter",
          attributes = Vector(
            "sym_name" -> quoted(parameterSymbol),
            "type" -> dataType,
            "default_value" -> typedLiteral(defaultValue, dataType, declaration.path),
            "variability" -> quoted("symbolic"),
            "classification" -> quoted(classification),
            "metadata" -> bridgeMetadata(
              declaration.path,
              declaration.attributes
                .filterNot(_._1 == "default")
                .map((key, value) => normalizeKey(key) -> quoted(value))
            )
          ),
          semanticPath = declaration.path
        )
      )
      if classification == "structural" then
        if dataType != "i64" then
          fail(
            "NODAL-BRIDGE-043",
            "structural hdlRange parameters currently require Integer type",
            Some(declaration.path)
          )
        val lower = attributes.get("integer_range_lower").flatMap(_.toIntOption).getOrElse(
          fail(
            "NODAL-BRIDGE-043",
            "structural hdlRange parameter has no finite lower range",
            Some(declaration.path)
          )
        )
        val upper = attributes.get("integer_range_upper").flatMap(_.toIntOption).getOrElse(
          fail(
            "NODAL-BRIDGE-043",
            "structural hdlRange parameter has no finite upper range",
            Some(declaration.path)
          )
        )
        val prefix = stableLocalSymbol("parameter_range", declaration.path)
        val lowerResult = s"%${prefix}_lower"
        val upperResult = s"%${prefix}_upper"
        rendered += operation(
          "nodal.const_literal",
          results = Vector(lowerResult),
          resultTypes = Vector("i64"),
          attributes = Vector(
            "value" -> integer(lower),
            "spelling" -> quoted(lower.toString),
            "metadata" -> bridgeMetadata(s"${declaration.path}.range.lower", Vector.empty)
          ),
          semanticPath = s"${declaration.path}.range.lower"
        )
        rendered += operation(
          "nodal.const_literal",
          results = Vector(upperResult),
          resultTypes = Vector("i64"),
          attributes = Vector(
            "value" -> integer(upper),
            "spelling" -> quoted(upper.toString),
            "metadata" -> bridgeMetadata(s"${declaration.path}.range.upper", Vector.empty)
          ),
          semanticPath = s"${declaration.path}.range.upper"
        )
        rendered += operation(
          "nodal.parameter_constraint",
          operands = Vector(lowerResult, upperResult),
          operandTypes = Vector("i64", "i64"),
          attributes = Vector(
            "parameter" -> symbolReference(parameterSymbol),
            "constraint_kind" -> quoted("range"),
            "lower_inclusive" -> boolean(true),
            "upper_inclusive" -> boolean(true),
            "metadata" -> bridgeMetadata(s"${declaration.path}.range", Vector.empty)
          ),
          semanticPath = s"${declaration.path}.range"
        )
        val effects = attributes
          .getOrElse(
            "structural_effects",
            fail(
              "NODAL-BRIDGE-043",
              "structural parameter has no declared effects",
              Some(declaration.path)
            )
          )
          .split(",")
          .toVector
          .map(_.trim)
          .filter(_.nonEmpty)
          .distinct
          .sorted
        val allowedEffects = Set("topology", "component_count", "equation_count", "shape", "rank")
        if effects.isEmpty || effects.exists(effect => !allowedEffects.contains(effect)) then
          fail(
            "NODAL-BRIDGE-043",
            "structural parameter effects must use the native envelope vocabulary",
            Some(declaration.path)
          )
        rendered += operation(
          "nodal.parameter_envelope",
          attributes = Vector(
            "parameter" -> symbolReference(parameterSymbol),
            "effects" -> array(effects.map(quoted)),
            "policy" -> quoted("static_generate"),
            "metadata" -> bridgeMetadata(s"${declaration.path}.envelope", Vector.empty)
          ),
          semanticPath = s"${declaration.path}.envelope"
        )
      rendered.toVector

    private def staticLiteralAttributes(
        expression: KernelParameterExpressionSnapshot,
        dataType: String
    ): Vector[(String, String)] =
      val value = expression.literal.getOrElse(
        fail(
          "NODAL-BRIDGE-034",
          "parameter literal has no captured value",
          Some(expression.path)
        )
      )
      val spelling =
        if Set("i1", "!nodal.bits<1>").contains(dataType) then
          value.toBooleanOption
            .map(if _ then "1" else "0")
            .getOrElse(
              fail(
                "NODAL-BRIDGE-034",
                "Boolean parameter literal has invalid spelling",
                Some(expression.path)
              )
            )
        else value
      Vector(
        "value" -> typedLiteral(value, dataType, expression.path),
        "spelling" -> quoted(spelling),
        "metadata" -> bridgeMetadata(
          expression.path,
          expression.unit.toVector.map(unit => "unit" -> quoted(unit))
        )
      )

    private def staticConstantOperator(expression: KernelParameterExpressionSnapshot): String =
      expression.operation match
        case "analog_add" => "add"
        case "analog_sub" => "sub"
        case "analog_mul" => "mul"
        case "analog_div" => "div"
        case "analog_neg" => "neg"
        case "real_gt" => "gt"
        case "real_ge" => "ge"
        case "real_lt" => "lt"
        case "real_le" => "le"
        case "bool_and" => "and"
        case "bool_or" => "or"
        case "bool_not" => "not"
        case operation =>
          fail(
            "NODAL-BRIDGE-034",
            s"unsupported static parameter operation '$operation'",
            Some(expression.path)
          )

    private def emitStaticValue(
        path: String,
        parameterSymbols: Map[String, String],
        declarationsByPath: Map[String, KernelDeclarationSnapshot],
        expressionsByPath: Map[String, KernelParameterExpressionSnapshot],
        rendered: mutable.ArrayBuffer[String],
        staticValues: mutable.LinkedHashMap[String, (String, String)],
        allocateStaticValue: () => String,
        requireStructural: Boolean,
        activeValues: mutable.Set[String] = mutable.Set.empty[String]
    ): (String, String) =
      if activeValues.size >= 512 || !activeValues.add(path) then
        fail(
          if requireStructural then "NODAL-BRIDGE-043" else "NODAL-BRIDGE-034",
          "static expression is cyclic or exceeds the depth limit",
          Some(path)
        )
      try
        staticValues.getOrElseUpdate(
          path,
          parameterSymbols.get(path) match
            case Some(symbol) =>
              val declaration = declarationsByPath.getOrElse(
                path,
                fail(
                  "NODAL-BRIDGE-034",
                  "parameter reference has no declaration",
                  Some(path)
                )
              )
              if requireStructural &&
                declaration.attributes.toMap.get("classification") != Some("structural")
              then
                fail(
                  "NODAL-BRIDGE-043",
                  "generated expression bound depends on a non-structural parameter",
                  Some(path)
                )
              val dataType = declaration.dataType.map(parseType(_, path)).getOrElse(
                fail("NODAL-BRIDGE-005", "parameter type is unavailable", Some(path))
              )
              val result = allocateStaticValue()
              rendered += operation(
                "nodal.const_parameter_ref",
                results = Vector(result),
                resultTypes = Vector(dataType),
                attributes = Vector(
                  "parameter" -> symbolReference(symbol),
                  "metadata" -> bridgeMetadata(path, Vector.empty)
                ),
                semanticPath = path
              )
              result -> dataType
            case None =>
              val expression = expressionsByPath.getOrElse(
                path,
                fail(
                  if requireStructural then "NODAL-BRIDGE-043" else "NODAL-BRIDGE-034",
                  "static symbolic value has no canonical expression",
                  Some(path)
                )
              )
              val dataType = parseType(expression.dataType, expression.path)
              val result = allocateStaticValue()
              expression.literal match
                case Some(_) =>
                  rendered += operation(
                    "nodal.const_literal",
                    results = Vector(result),
                    resultTypes = Vector(dataType),
                    attributes = staticLiteralAttributes(expression, dataType),
                    semanticPath = expression.path
                  )
                case None =>
                  val operands = expression.operands.map(operand =>
                    emitStaticValue(
                      operand,
                      parameterSymbols,
                      declarationsByPath,
                      expressionsByPath,
                      rendered,
                      staticValues,
                      allocateStaticValue,
                      requireStructural,
                      activeValues
                    )
                  )
                  val operatorName = staticConstantOperator(expression)
                  val validTypes = operatorName match
                    case "add" | "sub" | "mul" | "div" =>
                      operands.size == 2 && Set("f64", "i64").contains(dataType) &&
                      operands.forall(_._2 == dataType)
                    case "neg" =>
                      operands.size == 1 && Set("f64", "i64").contains(dataType) &&
                      operands.head._2 == dataType
                    case "gt" | "ge" | "lt" | "le" =>
                      operands.size == 2 && Set("i1", "!nodal.bits<1>").contains(dataType) &&
                      operands.map(_._2).distinct.size == 1 &&
                      operands.forall(value => Set("f64", "i64").contains(value._2))
                    case "and" | "or" =>
                      operands.size == 2 && Set("i1", "!nodal.bits<1>").contains(dataType) &&
                      operands.forall(_._2 == dataType)
                    case "not" =>
                      operands.size == 1 && Set("i1", "!nodal.bits<1>").contains(dataType) &&
                      operands.head._2 == dataType
                  if !validTypes then
                    fail(
                      "NODAL-BRIDGE-034",
                      "static parameter expression has incompatible operand or result types",
                      Some(expression.path)
                    )
                  rendered += operation(
                    "nodal.const_expr",
                    results = Vector(result),
                    operands = operands.map(_._1),
                    operandTypes = operands.map(_._2),
                    resultTypes = Vector(dataType),
                    attributes = Vector(
                      "operator_name" -> quoted(operatorName),
                      "metadata" -> bridgeMetadata(expression.path, Vector.empty)
                    ),
                    semanticPath = expression.path
                  )
              result -> dataType
        )
      finally
        val _ = activeValues.remove(path)

    private def renderInstance(
        instance: KernelInstanceSnapshot,
        instanceIndex: Int,
        parameterSymbols: Map[String, String],
        declarationsByPath: Map[String, KernelDeclarationSnapshot],
        expressionsByPath: Map[String, KernelParameterExpressionSnapshot]
    ): Vector[String] =
      val moduleSymbol = moduleSymbols.getOrElse(
        instance.childModule,
        fail(
          "NODAL-BRIDGE-007",
          "instance child Module is absent from the snapshot",
          Some(instance.path)
        )
      )
      val child = modulesByPath.getOrElse(
        instance.childModule,
        fail(
          "NODAL-BRIDGE-007",
          "instance child Module is absent from the snapshot",
          Some(instance.path)
        )
      )
      val childParameters = child.declarations
        .filter(_.kind == "parameter")
        .map(declaration => declaration.name -> declaration)
        .toMap
      val symbolicBindings = mutable.ArrayBuffer.empty[(String, String, String)]
      val literalBindings = instance.parameterBindings.flatMap: (name, value) =>
        val target = childParameters.getOrElse(
          name,
          fail("NODAL-BRIDGE-030", s"unknown child parameter '$name'", Some(instance.path))
        )
        val targetType = target.dataType.map(parseType(_, target.path)).getOrElse(
          fail("NODAL-BRIDGE-005", "parameter type is unavailable", Some(target.path))
        )
        val rendered =
          if parameterSymbols.contains(value) || expressionsByPath.contains(value) then
            symbolicBindings += ((name, value, targetType))
            None
          else Some(typedLiteral(value, targetType, instance.path))
        rendered.map(stableLocalSymbol("parameter", name) -> _)
      val parameterBindings = dictionary(literalBindings)
      val domainBindings = dictionary(
        instance.bindings.map((name, value) =>
          stableLocalSymbol("domain", name) ->
            symbolReference(stableLocalSymbol("domain", lastSegment(value)))
        )
      )
      val instanceSymbol = stableLocalSymbol("instance", lastSegment(instance.path))
      val rendered = mutable.ArrayBuffer(
        operation(
          "nodal.instance",
          attributes = Vector(
            "sym_name" -> quoted(instanceSymbol),
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
      )
      val staticValues = mutable.LinkedHashMap.empty[String, (String, String)]
      var nextStaticValue = 0

      def allocateStaticValue(): String =
        val result = s"%instance_${instanceIndex}_parameter_value_$nextStaticValue"
        nextStaticValue += 1
        result

      symbolicBindings.zipWithIndex.foreach: (binding, bindingIndex) =>
        val (name, sourcePath, dataType) = binding
        val (value, sourceType) = emitStaticValue(
          sourcePath,
          parameterSymbols,
          declarationsByPath,
          expressionsByPath,
          rendered,
          staticValues,
          () => allocateStaticValue(),
          requireStructural = false
        )
        if sourceType != dataType then
          fail(
            "NODAL-BRIDGE-034",
            s"symbolic parameter binding type '$sourceType' does not match '$dataType'",
            Some(instance.path)
          )
        rendered += operation(
          "nodal.parameter_override",
          operands = Vector(value),
          operandTypes = Vector(dataType),
          attributes = Vector(
            "instance" -> symbolReference(instanceSymbol),
            "parameter" -> symbolReference(stableLocalSymbol("parameter", name)),
            "metadata" -> bridgeMetadata(
              instance.path,
              Vector(
                "binding" -> quoted(name),
                "source_value" -> quoted(sourcePath),
                "binding_index" -> integer(bindingIndex)
              )
            )
          ),
          semanticPath = instance.path
        )
      rendered.toVector

    private def interfaceEntries(
        module: KernelModuleSnapshot
    ): Vector[InterfaceAbiEntry] =
      interfaceEntriesByModule.getOrElse(module.path, Vector.empty)

    private def resolvedEntries(
        module: KernelModuleSnapshot
    ): Vector[KernelResolvedNetSnapshot] =
      resolvedEntriesByModule.getOrElse(module.path, Vector.empty)

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

    private def generatedRegionsFor(
        module: KernelModuleSnapshot
    ): Vector[KernelGeneratedRegionSnapshot] =
      generatedRegionsByOwner.getOrElse(module.path, Vector.empty)

    private def generatedBoundAttribute(
        module: KernelModuleSnapshot,
        value: String,
        path: String
    ): String =
      value.toIntOption match
        case Some(literal) => integer(literal)
        case None =>
          val declaration = module.declarations.find(_.path == value).getOrElse(
            fail(
              "NODAL-BRIDGE-043",
              "generated symbolic bound has no Module parameter declaration",
              Some(path)
            )
          )
          if declaration.kind != "parameter" ||
            declaration.attributes.toMap.get("classification") != Some("structural")
          then
            fail(
              "NODAL-BRIDGE-043",
              "generated symbolic bound must reference a structural parameter",
              Some(path)
            )
          symbolReference(stableLocalSymbol("parameter", declaration.name))

    private def generatedBoundValue(
        value: String,
        regionPath: String,
        parameterSymbols: Map[String, String],
        declarationsByPath: Map[String, KernelDeclarationSnapshot],
        expressionsByPath: Map[String, KernelParameterExpressionSnapshot],
        rendered: mutable.ArrayBuffer[String],
        staticValues: mutable.LinkedHashMap[String, (String, String)],
        allocateStaticValue: () => String
    ): (String, String) =
      value.toLongOption match
        case Some(literal) =>
          staticValues.getOrElseUpdate(
            "literal:" + literal, {
              val result = allocateStaticValue()
              rendered += operation(
                "nodal.const_literal",
                results = Vector(result),
                resultTypes = Vector("i64"),
                attributes = Vector(
                  "value" -> (literal.toString + " : i64"),
                  "spelling" -> quoted(literal.toString),
                  "metadata" -> bridgeMetadata(
                    regionPath,
                    Vector("generated_bound" -> boolean(true))
                  )
                ),
                semanticPath = regionPath
              )
              result -> "i64"
            }
          )
        case None =>
          val result = emitStaticValue(
            value,
            parameterSymbols,
            declarationsByPath,
            expressionsByPath,
            rendered,
            staticValues,
            allocateStaticValue,
            requireStructural = true
          )
          if result._2 != "i64" then
            fail(
              "NODAL-BRIDGE-043",
              "generated expression bound must have Integer type, found '" + result._2 + "'",
              Some(value)
            )
          result

    private def isDirectGeneratedBound(module: KernelModuleSnapshot, value: String): Boolean =
      value.toIntOption.nonEmpty ||
        module.declarations.exists(declaration =>
          declaration.path == value && declaration.kind == "parameter"
        )

    private def renderGeneratedRegion(
        module: KernelModuleSnapshot,
        region: KernelGeneratedRegionSnapshot,
        declarationsByPath: Map[String, KernelDeclarationSnapshot],
        parameterSymbols: Map[String, String],
        expressionsByPath: Map[String, KernelParameterExpressionSnapshot],
        generatedStatic: mutable.ArrayBuffer[String],
        generatedStaticValues: mutable.LinkedHashMap[String, (String, String)],
        allocateGeneratedStaticValue: () => String
    ): String =
      val nested = mutable.ArrayBuffer.empty[String]
      region.declarations.sorted.foreach: declarationPath =>
        val declaration = declarationsByPath.getOrElse(
          declarationPath,
          fail(
            "NODAL-BRIDGE-043",
            "generated declaration is absent from its owning Module",
            Some(region.path)
          )
        )
        if declaration.kind != "analog-node" then
          fail(
            "NODAL-BRIDGE-043",
            "generated declaration kind '" + declaration.kind +
              "' is not enabled by this F-043 stage",
            Some(declaration.path)
          )
        val discipline = conservativeDiscipline(declaration)
        val resultType = "!nodal.terminal<" + quoted(discipline) + ">"
        val result = "%" + stableLocalSymbol("generated_node", declaration.path)
        nested += operation(
          "nodal.node",
          results = Vector(result),
          resultTypes = Vector(resultType),
          attributes = Vector(
            "name" -> quoted(declaration.name),
            "source_path" -> quoted(declaration.path),
            "generated_owner" -> quoted(region.path),
            "generated_induction" -> quoted(region.induction),
            "metadata" -> bridgeMetadata(
              declaration.path,
              Vector(
                "declaration_kind" -> quoted(declaration.kind),
                "generated_owner" -> quoted(region.path),
                "induction" -> quoted(region.induction)
              )
            )
          ),
          semanticPath = declaration.path
        )

      generatedRegionsFor(module)
        .filter(_.parent.contains(region.path))
        .sortBy(_.path)
        .foreach(child =>
          nested += renderGeneratedRegion(
            module,
            child,
            declarationsByPath,
            parameterSymbols,
            expressionsByPath,
            generatedStatic,
            generatedStaticValues,
            allocateGeneratedStaticValue
          )
        )

      val bounds = Vector(region.lower, region.upperExclusive, region.step)
      val operandForm = bounds.exists(value => !isDirectGeneratedBound(module, value))
      val boundValues =
        if !operandForm then Vector.empty
        else
          bounds.map(value =>
            generatedBoundValue(
              value,
              region.path,
              parameterSymbols,
              declarationsByPath,
              expressionsByPath,
              generatedStatic,
              generatedStaticValues,
              allocateGeneratedStaticValue
            )
          )
      val boundAttributes =
        if operandForm then Vector.empty
        else
          Vector(
            "lower" -> generatedBoundAttribute(module, region.lower, region.path),
            "upper" -> generatedBoundAttribute(module, region.upperExclusive, region.path),
            "step" -> generatedBoundAttribute(module, region.step, region.path)
          )

      operation(
        "nodal.generate",
        operands = boundValues.map(_._1),
        operandTypes = boundValues.map(_._2),
        attributes = Vector(
          "induction" -> quoted(stableLocalSymbol("induction", lastSegment(region.induction)))
        ) ++ boundAttributes ++ Vector(
          "region_id" -> quoted(region.path),
          "induction_path" -> quoted(region.induction),
          "metadata" -> bridgeMetadata(
            region.path,
            Vector(
              "maximum_trip_count" -> integer(region.maximumTripCount)
            ) ++ region.maximum.toVector.map(value => "declared_maximum" -> integer(value))
          )
        ),
        regions = Vector(if nested.isEmpty then "^bb0:" else nested.mkString("\n")),
        semanticPath = region.path
      )

    private def topologyEntries(
        module: KernelModuleSnapshot
    ): Vector[KernelTopologyEdge] =
      topologyByOwner.getOrElse(module.path, Vector.empty)

    private def resolveOwningModule(path: String): String =
      def resolve(candidate: String): String =
        if modulesByPath.contains(candidate) then candidate
        else
          val separator = candidate.lastIndexOf('.')
          if separator >= 0 then resolve(candidate.take(separator))
          else
            fail(
              "NODAL-BRIDGE-008",
              "semantic path has no owning Module",
              Some(path)
            )
      resolve(path)

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
          val (elementText, dimensions) = vecTypeParts(value, path)
          val element = parseType(elementText, path)
          if dimensions.isEmpty || dimensions.exists(dimension =>
              !validShapeDimension(dimension, path)
            )
          then
            fail(
              "NODAL-BRIDGE-018",
              s"invalid Vec dimensions '${dimensions.mkString("x")}'",
              Some(path)
            )
          s"""!nodal.shaped<${quoted(dimensions.mkString(","))}, $element>"""
        case _ =>
          fail(
            "NODAL-BRIDGE-019",
            s"unsupported exact MLIR type representation '$text'",
            Some(path)
          )

    private def vecTypeParts(text: String, path: String): (String, Vector[String]) =
      if !text.startsWith("Vec(") || !text.endsWith(")") then
        fail("NODAL-BRIDGE-044", s"shape-index input is not Vec type '$text'", Some(path))
      val inside = text.drop(4).dropRight(1)
      val split = inside.lastIndexOf(';')
      if split <= 0 || split == inside.length - 1 then
        fail("NODAL-BRIDGE-017", s"invalid Vec type '$text'", Some(path))
      inside.take(split) -> inside.drop(split + 1).split("x", -1).toVector

    private def shapeDimensionMinimum(
        module: KernelModuleSnapshot,
        dimension: String,
        path: String
    ): Int =
      dimension.toIntOption.filter(_ > 0).getOrElse:
        val declaration = module.declarations.find(_.name == dimension).getOrElse(
          fail("NODAL-BRIDGE-044", "symbolic shape extent is absent", Some(path))
        )
        val attributes = declaration.attributes.toMap
        if declaration.kind != "parameter" || !declaration.dataType.contains("Integer") ||
          !attributes.get("classification").contains("structural") ||
          !attributes
            .get("structural_effects")
            .exists(_.split(",").map(_.trim).contains("shape"))
        then
          fail("NODAL-BRIDGE-044", "symbolic shape extent is not structural", Some(path))
        attributes.get("integer_range_lower").flatMap(_.toIntOption).filter(_ > 0).getOrElse(
          fail("NODAL-BRIDGE-044", "symbolic shape extent has no positive minimum", Some(path))
        )

    private def validShapeDimension(dimension: String, path: String): Boolean =
      dimension.matches("[1-9][0-9]*") ||
        (dimension.matches("[A-Za-z_][A-Za-z0-9_]*") &&
          modulesByPath
            .get(resolveOwningModule(path))
            .flatMap(_.declarations.find(_.name == dimension))
            .exists: declaration =>
              val attributes = declaration.attributes.toMap
              declaration.kind == "parameter" &&
              declaration.dataType.contains("Integer") &&
              attributes.get("classification").contains("structural") &&
              attributes
                .get("structural_effects")
                .exists(_.split(",").map(_.trim).contains("shape")) &&
              attributes.get("integer_range_lower").flatMap(_.toIntOption).exists(_ > 0) &&
              attributes.get("integer_range_upper").flatMap(_.toIntOption).exists: upper =>
                attributes.get("integer_range_lower").flatMap(_.toIntOption).exists(_ <= upper))

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

    private def rootParameterBindingInventory: String =
      val root = modulesByPath.getOrElse(
        snapshot.root,
        fail("NODAL-BRIDGE-011", "root Module is absent from the snapshot", Some(snapshot.root))
      )
      val parameters = root.declarations
        .filter(_.kind == "parameter")
        .map(declaration => declaration.name -> declaration)
        .toMap
      dictionary(snapshot.rootParameterBindings.map: (name, value) =>
        val declaration = parameters.getOrElse(
          name,
          fail("NODAL-BRIDGE-031", s"unknown root parameter '$name'", Some(snapshot.root))
        )
        val dataType = declaration.dataType.map(parseType(_, declaration.path)).getOrElse(
          fail("NODAL-BRIDGE-005", "parameter type is unavailable", Some(declaration.path))
        )
        stableLocalSymbol("parameter", name) -> typedLiteral(value, dataType, declaration.path))

    private def conservativeDiscipline(declaration: KernelDeclarationSnapshot): String =
      val attributes = declaration.attributes.toMap
      val declared = attributes.getOrElse(
        "discipline",
        fail(
          "NODAL-BRIDGE-014",
          "conservative declaration lacks discipline identity",
          Some(declaration.path)
        )
      )
      val potential = attributes.get("potential_nature").map(_.trim.toLowerCase)
      val flow = attributes.get("flow_nature").map(_.trim.toLowerCase)
      (potential, flow) match
        case (Some(left), Some(right))
            if Set("voltage", "potential").contains(left) &&
              Set("current", "flow").contains(right) =>
          "electrical"
        case (Some(left), Some(right)) =>
          fail(
            "NODAL-BRIDGE-033",
            s"unsupported conservative nature pair '$left/$right' for '$declared'",
            Some(declaration.path)
          )
        case _ => normalizeDiscipline(declared)

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
          (edge.owner, edge.kind, edge.left, edge.right)
        ).map: edge =>
          dictionary(
            Vector(
              "owner" -> quoted(edge.owner),
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

    private def stableModuleSymbol(
        value: String,
        definitionPaths: Iterable[String] = modules.map(_.path)
    ): String =
      val base = normalizeSymbol(lastSegment(value))
      val collisions = definitionPaths.count(path => normalizeSymbol(lastSegment(path)) == base)
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
