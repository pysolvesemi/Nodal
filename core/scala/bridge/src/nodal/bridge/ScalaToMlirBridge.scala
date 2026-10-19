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

private[nodal] final case class ScalarElectricalPort(path: String, name: String)

private[nodal] final case class ScalarElectricalInterface(
    moduleName: String,
    ports: Vector[ScalarElectricalPort]
)

private[nodal] final case class NodalMlirDocument(
    schema: String,
    version: Int,
    text: String,
    sha256: String,
    electricalInterface: Option[ScalarElectricalInterface] = None
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
      backend: Backend = Backend.Auto,
      sourceProvenance: Boolean = false
  ): NodalMlirDocument =
    val renderer = new Renderer(snapshot, backend, sourceProvenance)
    val text = renderer.render()
    NodalMlirDocument(Schema, Version, text, digest(text), renderer.scalarElectricalInterface)

  def compileToVerilogA(
      top: => Module,
      nodalc: Path,
      translator: Path,
      workingDirectory: Path,
      timeout: Duration = Duration.ofSeconds(30),
      sourceProvenance: Boolean = false
  ): Either[NativeCompilerFailure, VerilogAVerticalSliceResult] =
    val options = EmitOptions(backend = Backend.VerilogA)
    val document = fromSnapshot(
      ConstructionKernel.inspect(top, options),
      options.backend,
      sourceProvenance
    )
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

  private final class Renderer(
      snapshot: ConstructionSnapshot,
      backend: Backend,
      sourceProvenance: Boolean
  ):
    private val sourceEquations = new AnalogEquationMlir.Plan(snapshot)
    private val enumValues = new EnumMlir.Plan(snapshot.enumDefinitions, snapshot.root)
    private val digitalValues = new DigitalValueMlir.Plan(snapshot, enumValues)
    private val interfaceValues = new InterfaceMlir.Plan(snapshot)
    private val interfaceContractOwners = snapshot.interfaceContracts.map(_.owner).toSet
    private val interfaceMembersByPath = snapshot.interfaceContracts.flatMap(value =>
      value.members ++ value.roles.flatMap(_.members)
    ).map(member => member.path -> member).toMap
    private val modules = snapshot.modules.sortBy(_.path)
    private val modulesByPath = modules.map(module => module.path -> module).toMap
    private val storageDeclarationPaths = modules.flatMap(_.declarations)
      .filter(value => Set("wire", "register", "memory").contains(value.kind)).map(_.path).toSet
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
    private val generatedByPath = snapshot.generatedRegions.map(value => value.path -> value).toMap
    private val generatedByInduction =
      snapshot.generatedRegions.map(value => value.induction -> value).toMap

    private def scopeAttributes(scope: Option[KernelGeneratedScope]): Vector[(String, String)] =
      scope.toVector.flatMap(value =>
        Vector(
          "generated_owner" -> quoted(value.owner),
          "generated_induction" -> quoted(value.induction)
        )
      )

    private def validateScope(
        scope: Option[KernelGeneratedScope],
        owner: String,
        path: String
    ): Unit =
      scope.foreach: value =>
        if !generatedByPath.get(value.owner).exists(region =>
            region.owner == owner && region.induction == value.induction
          )
        then
          fail(
            "NODAL-BRIDGE-043",
            "generated scope does not resolve to its exact owned region and induction",
            Some(path)
          )

    private def visibleInduction(scope: Option[KernelGeneratedScope], induction: String): Boolean =
      var current = scope.map(_.owner)
      val seen = mutable.HashSet.empty[String]
      var found = false
      while !found && current.nonEmpty && seen.add(current.get) do
        val region = generatedByPath.get(current.get)
        found = region.exists(_.induction == induction)
        current = region.flatMap(_.parent)
      found

    private def sameStaticInteger(
        module: KernelModuleSnapshot,
        left: String,
        right: String,
        path: String
    ): Boolean =
      try StructuralParameterBinding.sameInteger(snapshot, module.path, left, right)
      catch
        case error: IllegalArgumentException =>
          fail("NODAL-BRIDGE-043", error.getMessage, Some(path))

    private def generatedRangeBounds(
        module: KernelModuleSnapshot,
        value: String,
        path: String
    ): IterationDomain.Bounds =
      shapeStaticBounds(
        module,
        value,
        path,
        "NODAL-BRIDGE-043",
        "generated range",
        requiredEffect = "topology"
      )

    private def generatedIndexBounds(
        module: KernelModuleSnapshot,
        value: String,
        path: String
    ): IterationDomain.Bounds =
      generatedByInduction.get(value) match
        case None => shapeIndexBounds(module, value, path)
        case Some(region) =>
          val lower = generatedRangeBounds(module, region.lower, path)
          val upper = generatedRangeBounds(module, region.upperExclusive, path)
          val step = generatedRangeBounds(module, region.step, path)
          if region.owner != module.path || (region.maximumTripCount != 0 && lower.lower < 0) || step.lower <= 0
          then
            fail(
              "NODAL-BRIDGE-044",
              "generated index requires its owned nonnegative positive-step range",
              Some(path)
            )
          IterationDomain.Bounds(
            lower.lower,
            if region.maximumTripCount == 0 then lower.upper
            else math.max(lower.upper, upper.upper - 1L)
          )

    private final case class StructuralShapeOperation(
        record: KernelShapeViewSnapshot | KernelShapeIndexSnapshot,
        ordinal: Int
    ):
      def path: String = record match
        case view: KernelShapeViewSnapshot => view.path
        case index: KernelShapeIndexSnapshot => index.path
      def owner: String = record match
        case view: KernelShapeViewSnapshot => view.owner
        case index: KernelShapeIndexSnapshot => index.owner
      def input: String = record match
        case view: KernelShapeViewSnapshot => view.input
        case index: KernelShapeIndexSnapshot => index.input
      def dimensions: Vector[String] = record match
        case view: KernelShapeViewSnapshot => view.dimensions
        case index: KernelShapeIndexSnapshot => index.resultDimensions
      def region: Option[String] = record match
        case index: KernelShapeIndexSnapshot => index.region
        case view: KernelShapeViewSnapshot => view.region
      def scope: Option[KernelGeneratedScope] = record match
        case index: KernelShapeIndexSnapshot => index.generatedScope
        case view: KernelShapeViewSnapshot => view.generatedScope
      def isView: Boolean = record.isInstanceOf[KernelShapeViewSnapshot]
      def isSlice: Boolean = record match
        case view: KernelShapeViewSnapshot => view.slice.nonEmpty
        case _ => false
      def isNodeSelection: Boolean = record match
        case index: KernelShapeIndexSnapshot => index.sourceUses.nonEmpty
        case _ => false
      def code: String = if isNodeSelection then "NODAL-BRIDGE-048"
      else if isSlice then "NODAL-BRIDGE-046"
      else if isView then "NODAL-BRIDGE-045" else "NODAL-BRIDGE-044"
      def name: String =
        if isSlice then "shape_slice" else if isView then "shape_view" else "shape_index"
      def result: String = s"%${name}_$ordinal"

    private lazy val structuralShapeOperations: Vector[StructuralShapeOperation] =
      val ordinals = mutable.HashMap.empty[(String, Boolean), Int]
      val records: Vector[KernelShapeViewSnapshot | KernelShapeIndexSnapshot] =
        snapshot.shapeViews ++ snapshot.shapeIndices
      val operations = records.map: record =>
        val operation = StructuralShapeOperation(record, 0)
        val key = operation.owner -> operation.isView
        val ordinal = ordinals.getOrElse(key, 0)
        ordinals.update(key, ordinal + 1)
        operation.copy(ordinal = ordinal)
      val byPath = mutable.HashMap.empty[String, Int]
      operations.zipWithIndex.foreach: (operation, index) =>
        if byPath.contains(operation.path) then
          fail(operation.code, "duplicate structural shape identity", Some(operation.path))
        byPath.update(operation.path, index)
      val consumers = Array.fill(operations.size)(mutable.ArrayBuffer.empty[Int])
      val indegree = Array.fill(operations.size)(0)
      val previous = mutable.HashMap.empty[(String, Boolean), Int]
      operations.zipWithIndex.foreach: (operation, index) =>
        val key = operation.owner -> operation.isView
        // Capture order within each record kind is part of the snapshot contract.
        // Input edges interleave the kinds without repairing reordered snapshots.
        val dependencies = (previous.get(key).toVector ++ byPath.get(operation.input)).distinct
        dependencies.foreach: dependency =>
          consumers(dependency) += index
          indegree(index) += 1
        previous.update(key, index)
      val ready = mutable.PriorityQueue.empty[Int](using Ordering.Int.reverse)
      indegree.indices.filter(indegree(_) == 0).foreach(ready.enqueue(_))
      val ordered = Vector.newBuilder[StructuralShapeOperation]
      var visited = 0
      while ready.nonEmpty do
        val index = ready.dequeue()
        ordered += operations(index)
        visited += 1
        consumers(index).foreach: consumer =>
          indegree(consumer) -= 1
          if indegree(consumer) == 0 then ready.enqueue(consumer)
      if visited != operations.size then
        val blocked = operations(indegree.indexWhere(_ > 0))
        fail(
          blocked.code,
          "structural shape dependencies contain a cycle or contradict capture order",
          Some(blocked.path)
        )
      ordered.result()

    private lazy val structuralShapesByOwner = structuralShapeOperations.groupBy(_.owner)
    private lazy val structuralShapesByPath =
      structuralShapeOperations.map(value => value.path -> value).toMap
    private val instanceArrayRootsByPath =
      snapshot.instanceArrayRoots.map(root => root.path -> root).toMap
    private val instanceArrayRootsByOwner = snapshot.instanceArrayRoots.groupBy(_.owner)
    private val conservativeArrayBindingsByOwner = snapshot.conservativeArrayBindings
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

    // This is a projection of the same canonical definitions used for emission, not a
    // second naming registry. Native target verification remains independent.
    def scalarElectricalInterface: Option[ScalarElectricalInterface] =
      modulesByPath.get(snapshot.root).flatMap: root =>
        val boundaryKinds =
          Set("analog-input", "analog-output", "analog-inout", "conservative-terminal")
        val boundary =
          root.declarations.filter(declaration => boundaryKinds.contains(declaration.kind))
        val supported = boundary.nonEmpty && boundary.forall(declaration =>
          declaration.generatedScope.isEmpty && declaration.conservativeDimensions.isEmpty &&
            conservativeDiscipline(declaration) == "electrical"
        ) && !root.declarations.exists(declaration =>
          Set(
            "input",
            "output",
            "digital-inout",
            "interface-port",
            "interface-array"
          ).contains(declaration.kind)
        )
        Option.when(supported)(ScalarElectricalInterface(
          moduleSymbols(snapshot.root),
          boundary.sortBy(_.name).map(declaration =>
            ScalarElectricalPort(declaration.path, declaration.name)
          )
        ))

    def render(): String =
      validate()
      canonicalizeModuleDefinitions()
      val body =
        (enumValues.renderDefinitions ++ standardConservativeDeclarations ++ AnalogEquationMlir.unitDeclarations(
          sourceEquations
        ) ++ emittedModules.map(module =>
          renderModule(module)
        )).mkString("\n\n")
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
        Option.when(sourceProvenance)("nodal.backend.source_provenance" -> "true") ++
        (if snapshot.transferOperators.isEmpty then Vector.empty
         else
           Vector(
             "nodal.bridge.transfer_operators" -> transferOperatorInventory
           )) ++
        (if snapshot.analogFunctions.isEmpty then Vector.empty
         else
           Vector("nodal.bridge.analog_functions" -> AnalogUserFunctionMlir.inventory(snapshot))) ++
        Option.when(sourceEquations.active)(
          "nodal.bridge.source_equations" -> sourceEquations.inventory(
            emittedModules.map(_.path).toSet
          )
        ).toVector ++
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
      val normalizedPath =
        renderModule(module, definitionComparison = true).replace(module.path, "$module")
      val symbolAttribute = "sym_name = " + quoted(moduleSymbols(module.path))
      val position = normalizedPath.indexOf(symbolAttribute)
      if position < 0 then
        fail(
          "NODAL-BRIDGE-035",
          "module definition has no canonical symbol attribute",
          Some(module.path)
        )
      module.className + "\u0000" + normalizeDefinitionSsa(
        normalizedPath.patch(position, "sym_name = \"$module\"", symbolAttribute.length)
      )

    private def normalizeDefinitionSsa(text: String): String =
      // Compare the renderer's generic MLIR modulo SSA binding names. Keep quoted
      // attributes, source identities, types, operation order and use edges intact.
      // Each region owns its bindings, including block arguments and shadowed names.
      val tokens = """"(?:\\.|[^"\\])*"|%[\p{L}\p{N}_.$-]+|\(\{|\}\)""".r
      var scopes = List(mutable.Map.empty[String, String])
      var next = 0
      text.linesIterator.map: line =>
        val trimmed = line.dropWhile(_.isWhitespace)
        val resultEnd = if trimmed.startsWith("%") then line.indexOf(" = ") else -1
        val blockArguments = trimmed.startsWith("^")
        tokens.replaceAllIn(
          line,
          token =>
            val value = token.matched
            val replacement = value match
              case "({" =>
                scopes = mutable.Map.empty[String, String] :: scopes
                value
              case "})" =>
                scopes = scopes.tail
                value
              case _ if value.startsWith("%") =>
                if token.start < resultEnd || blockArguments then
                  val name = s"%definition_$next"
                  next += 1
                  scopes.head.update(value, name)
                  name
                else
                  scopes.iterator.flatMap(_.get(value)).nextOption().getOrElse(
                    fail("NODAL-BRIDGE-035", "module definition has an unbound SSA use", None)
                  )
              case _ => value
            java.util.regex.Matcher.quoteReplacement(replacement)
        )
      .mkString("\n")

    private def standardConservativeDeclarations: Vector[String] =
      val needsElectrical = modules.exists(module =>
        terminalDeclarations(module).nonEmpty ||
          module.declarations.exists(isConservativeArray)
      ) || snapshot.interfaceContracts.exists(_.members.exists(member =>
        member.protocol == "conservative" &&
          member.analogAttributes.toMap.get("discipline").contains("electrical")
      ))
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
        "analog-node-array",
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
      val analog =
        sourceEquations.active || snapshot.analogRegions.nonEmpty || snapshot.analogFunctions.nonEmpty ||
          snapshot.continuousOperators.nonEmpty ||
          snapshot.waveformOperators.nonEmpty ||
          snapshot.analogSemantics.equations.nonEmpty ||
          snapshot.analogSemantics.contributions.nonEmpty ||
          snapshot.analogProcedural.nonEmpty ||
          declarationKinds.exists(analogKinds.contains) ||
          snapshot.interfaceContracts.exists(_.members.exists(member =>
            Set("conservative", "signal-flow").contains(member.protocol)
          ))
      val digital = snapshot.enumDefinitions.nonEmpty || snapshot.digitalExpressions.nonEmpty ||
        declarationKinds.exists(digitalKinds.contains) ||
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
      ) ++ value.generatedOwner.toVector.map(owner => "generated_owner" -> quoted(owner)) ++
        value.generatedInduction.toVector.map(induction =>
          "generated_induction" -> quoted(induction)
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

    private def noiseOperatorAttributes(value: KernelNoiseOperatorSnapshot)
        : Vector[(String, String)] =
      Vector(
        "noise_kind" -> quoted(value.kind),
        "contract_version" -> quoted("1"),
        "noise_name" -> quoted(value.label),
        "source_id" -> quoted(value.path),
        "owner" -> quoted(value.owner),
        "correlation" -> quoted("independent"),
        "analyses" -> "[\"noise\"]",
        "result_dimension" -> quoted(value.resultDimension)
      ) ++ value.generatedOwner.toVector.map(owner => "generated_owner" -> quoted(owner)) ++
        value.generatedInduction.toVector.map(induction =>
          "generated_induction" -> quoted(induction)
        )

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
      ) ++ value.stateId.toVector.map(state => "state_id" -> quoted(state)) ++
        value.generatedOwner.toVector.map(owner => "generated_owner" -> quoted(owner)) ++
        value.generatedInduction.toVector.map(induction =>
          "generated_induction" -> quoted(induction)
        )

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
      requireUnique(
        snapshot.conservativeArrayBindings.map(_.path),
        "NODAL-BRIDGE-050",
        "conservative array binding identity"
      )
      requireUnique(
        snapshot.instanceArrayRoots.map(root => s"${root.instance}:${root.port}"),
        "NODAL-BRIDGE-050",
        "conservative child array root"
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
        val inductionSource = sourceByPath.getOrElse(
          region.induction,
          fail(
            "NODAL-BRIDGE-043",
            "generated induction has no canonical source-map entry",
            Some(region.induction)
          )
        )
        sourceByPath.get(region.path).foreach: regionSource =>
          if regionSource != inductionSource then
            fail(
              "NODAL-BRIDGE-043",
              "generated region and induction source-map entries disagree",
              Some(region.path)
            )
        val boundValues = Vector(region.lower, region.upperExclusive, region.step)
          .map(value => generatedRangeBounds(module, value, region.path))
        def boundIdentity(value: String): String =
          shapeDimensionStaticPath(module, value).getOrElse(value)
        val envelope = IterationDomain.structural(
          boundValues(0),
          boundValues(1),
          boundValues(2),
          region.maximum,
          identicalBounds = sameStaticInteger(
            module,
            boundIdentity(region.lower),
            boundIdentity(region.upperExclusive),
            region.path
          )
        )
          .fold(problem => fail("NODAL-BRIDGE-043", problem.message, Some(region.path)), identity)
        if envelope.maximumTripCount != region.maximumTripCount then
          fail(
            "NODAL-BRIDGE-043",
            "generated maximum count differs from its actual static domain",
            Some(region.path)
          )
        val declarationsByPath = module.declarations.map(declaration =>
          declaration.path -> declaration
        ).toMap
        region.declarations.foreach: path =>
          val declaration = declarationsByPath.getOrElse(
            path,
            fail(
              "NODAL-BRIDGE-043",
              "generated declaration is absent from its owning Module",
              Some(path)
            )
          )
          if !Set("analog-node", "analog-node-array", "variable").contains(declaration.kind) then
            fail(
              "NODAL-BRIDGE-043",
              "generated declaration must be a local conservative node, array or procedural variable",
              Some(path)
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
      modules.foreach: module =>
        module.instances.foreach(instance =>
          validateScope(instance.generatedScope, module.path, instance.path)
        )
      snapshot.topology.foreach: edge =>
        validateScope(edge.generatedScope, edge.owner, edge.path.getOrElse(edge.left))
        if edge.generatedScope.nonEmpty && edge.path.forall(path => !sourceByPath.contains(path))
        then
          fail(
            "NODAL-BRIDGE-043",
            "generated connection lacks its authored source identity",
            edge.path
          )
        Vector(edge.left, edge.right).foreach: endpoint =>
          val endpointOwner = topologyEndpointOwners(endpoint)
          if endpointOwner != edge.owner then
            val instance = modulesByPath(edge.owner).instances.find(_.childModule == endpointOwner)
              .getOrElse(fail(
                "NODAL-BRIDGE-043",
                "connection endpoint is not an immediate child",
                Some(endpoint)
              ))
            if instance.generatedScope != edge.generatedScope then
              fail(
                "NODAL-BRIDGE-043",
                "connection crossed its child's exact generated scope",
                Some(endpoint)
              )
          else
            val endpointScope =
              snapshot.shapeIndices.find(_.path == endpoint).flatMap(_.generatedScope)
                .orElse(snapshot.generatedRegions.find(_.declarations.contains(endpoint)).map(
                  region => KernelGeneratedScope(region.path, region.induction)
                ))
            endpointScope.foreach: scope =>
              if !visibleInduction(edge.generatedScope, scope.induction) then
                fail(
                  "NODAL-BRIDGE-043",
                  "connection endpoint escaped its generated scope",
                  Some(endpoint)
                )
      snapshot.conservativeArrayBindings.foreach: binding =>
        validateScope(binding.generatedScope, binding.owner, binding.path)
      requireUnique(
        snapshot.instanceArrayRoots.map(_.path),
        "NODAL-BRIDGE-050",
        "instance array root identity"
      )
      snapshot.instanceArrayRoots.foreach: root =>
        validateScope(root.generatedScope, root.owner, root.path)
        val module = modulesByPath.getOrElse(
          root.owner,
          fail("NODAL-BRIDGE-050", "instance array root has no owning Module", Some(root.path))
        )
        val instance = module.instances.find(_.path == root.instance).getOrElse(
          fail("NODAL-BRIDGE-050", "instance array root has no exact instance", Some(root.path))
        )
        val port =
          modulesByPath(instance.childModule).declarations.find(_.path == root.port).getOrElse(
            fail("NODAL-BRIDGE-050", "instance array root has no declared port", Some(root.path))
          )
        val expected = root.instance + ".array_port_" + port.name +
          root.generatedScope.map(scope => s".in(${scope.owner})").getOrElse("")
        if root.path != expected || !sourceByPath.contains(root.path) ||
          module.declarations.exists(_.path == root.path)
        then
          fail(
            "NODAL-BRIDGE-050",
            "instance array root requires its unique canonical source identity",
            Some(root.path)
          )
        try StructuralParameterBinding.validateRoot(snapshot, root)
        catch
          case error: IllegalArgumentException =>
            fail("NODAL-BRIDGE-050", error.getMessage, Some(root.path))
      val generatedProcedures = snapshot.analogProcedural.filter(_.generatedOwner.nonEmpty)
      requireUnique(
        generatedProcedures.flatMap(_.generatedOwner),
        "NODAL-BRIDGE-043",
        "generated procedural ownership"
      )
      requireUnique(
        snapshot.analogProcedural.map(program => program.identityPrefix.getOrElse(program.owner)),
        "NODAL-BRIDGE-043",
        "procedural identity namespace"
      )
      requireUnique(
        snapshot.analogProcedural.flatMap(_.variables.map(_.variable.identity)),
        "NODAL-BRIDGE-043",
        "procedural variable identity"
      )
      snapshot.analogProcedural.foreach: program =>
        program.identityPrefix.foreach: prefix =>
          val expected = program.generatedOwner.getOrElse(s"${program.owner}.ordinary_procedure")
          if prefix != expected then
            fail(
              "NODAL-BRIDGE-043",
              "procedural identity prefix disagrees with its actual scope",
              Some(prefix)
            )
        if program.generatedOwner.nonEmpty != program.generatedInduction.nonEmpty then
          fail(
            "NODAL-BRIDGE-043",
            "generated analog procedure requires paired owner and induction identities",
            Some(program.owner)
          )
        program.generatedOwner.foreach: generatedOwner =>
          val region = generatedByPath.getOrElse(
            generatedOwner,
            fail(
              "NODAL-BRIDGE-043",
              "generated analog procedure has no owning generated region",
              Some(generatedOwner)
            )
          )
          val module = modulesByPath(region.owner)
          val declarationsByPath = module.declarations.map(declaration =>
            declaration.path -> declaration
          ).toMap
          val generatedVariables = program.variables.map: record =>
            val path = record.authoredPath.getOrElse(
              fail(
                "NODAL-BRIDGE-043",
                "generated analog variable has no authored declaration identity",
                Some(record.variable.identity)
              )
            )
            if record.variable.owner != program.owner ||
              !declarationsByPath.get(path).exists(_.kind == "variable")
            then
              fail(
                "NODAL-BRIDGE-043",
                "generated analog variable does not match an owned variable declaration",
                Some(path)
              )
            path
          requireUnique(
            generatedVariables,
            "NODAL-BRIDGE-043",
            "generated analog variable declaration"
          )
          val regionVariables = region.declarations.filter(path =>
            declarationsByPath.get(path).exists(_.kind == "variable")
          )
          if generatedVariables.toSet != regionVariables.toSet then
            fail(
              "NODAL-BRIDGE-043",
              "generated variable declarations do not match their procedural ownership",
              Some(generatedOwner)
            )
          if region.owner != program.owner ||
            !program.generatedInduction.contains(region.induction)
          then
            fail(
              "NODAL-BRIDGE-043",
              "generated analog procedure ownership or induction is invalid",
              Some(generatedOwner)
            )
      snapshot.parameterExpressions.foreach: expression =>
        if !moduleSymbols.contains(expression.owner) then
          fail(
            "NODAL-BRIDGE-034",
            "parameter expression has no owning Module",
            Some(expression.path)
          )
      modules.foreach: module =>
        module.declarations.foreach: declaration =>
          if declaration.generatedScope.nonEmpty && declaration.kind != "analog-node-array" then
            fail(
              "NODAL-BRIDGE-043",
              "generated declaration scope is only supported on local conservative arrays",
              Some(declaration.path)
            )
          if isConservativeArray(declaration) then validateNodeArray(module, declaration)
          else if declaration.conservativeDimensions.nonEmpty then
            fail(
              "NODAL-BRIDGE-047",
              "only local or boundary conservative arrays may carry conservative dimensions",
              Some(declaration.path)
            )
      val nodeSelections = mutable.HashMap.empty[(String, Option[KernelGeneratedScope]), Vector[(
          StructuralShapeCoordinates.Region,
          Vector[(BigInt, BigInt)]
      )]]
      val nodeSelectionUses = mutable.HashSet.empty[String]
      val validatedShapes = mutable.HashMap.empty[
        String,
        (String, Vector[String], Option[String], Vector[StructuralShapeCoordinates.Projection])
      ]
      val conservativeRoots = mutable.HashMap.empty[String, String]
      snapshot.instanceArrayRoots.foreach: root =>
        conservativeRoots.update(root.path, root.path)
        validatedShapes.update(
          root.path,
          (root.owner, root.dimensions, Some(root.path), Vector.empty)
        )
      val shapeScopes = structuralShapeOperations.map(shape => shape.path -> shape.scope).toMap
      def validateShapeUse(
          path: String,
          scope: Option[KernelGeneratedScope],
          root: Option[String],
          consumer: String
      ): Unit =
        root.flatMap(path => snapshot.generatedRegions.find(_.declarations.contains(path)))
          .foreach: declarationRegion =>
            if !visibleInduction(scope, declarationRegion.induction) then
              fail(
                "NODAL-BRIDGE-050",
                "conservative array declaration escaped its generated scope",
                Some(consumer)
              )
        root.flatMap(instanceArrayRootsByPath.get).foreach: instanceRoot =>
          if instanceRoot.generatedScope != scope then
            fail(
              "NODAL-BRIDGE-050",
              "child array reference crossed its exact instance scope",
              Some(consumer)
            )
        shapeScopes.get(path).flatten.foreach: captured =>
          if !visibleInduction(scope, captured.induction) then
            fail(
              "NODAL-BRIDGE-050",
              "conservative projection escaped its generated scope",
              Some(consumer)
            )
      def mapToken(module: KernelModuleSnapshot, value: String, path: String): String =
        value.toLongOption.map(_.toString).orElse(
          generatedByInduction.get(value).filter(_.owner == module.path).map(_.induction)
        ).getOrElse(shapeDimensionStaticPath(
          module,
          value
        ).getOrElse(
          fail("NODAL-BRIDGE-048", "node-array map lacks an owned static identity", Some(path))
        ))
      def mapProjection(
          module: KernelModuleSnapshot,
          shape: StructuralShapeOperation
      ): StructuralShapeCoordinates.Projection =
        def token(value: String): String = mapToken(module, value, shape.path)
        shape.record match
          case index: KernelShapeIndexSnapshot => StructuralShapeCoordinates.Projection.Index(
              index.indices.map(token),
              index.resultDimensions.map(token)
            )
          case view: KernelShapeViewSnapshot => StructuralShapeCoordinates.Projection.View(
              view.dimensions.map(token),
              view.slice.map(slice =>
                KernelShapeSliceSnapshot(
                  slice.axis,
                  token(slice.lower),
                  token(slice.upperExclusive)
                )
              )
            )
      structuralShapeOperations.foreach: shape =>
        val module = modulesByPath.getOrElse(
          shape.owner,
          fail(shape.code, "structural shape has no owning Module", Some(shape.path))
        )
        validateScope(shape.scope, shape.owner, shape.path)
        if !shape.path.startsWith(s"${shape.owner}.") || shape.path.trim != shape.path ||
          shape.input.trim != shape.input || !shape.input.startsWith(s"${shape.owner}.")
        then
          fail(
            shape.code,
            "structural shape identity is noncanonical or escapes its owning Module",
            Some(shape.path)
          )
        if !sourceByPath.contains(shape.path) then
          fail(shape.code, "structural shape identity has no source-map entry", Some(shape.path))
        if module.declarations.exists(_.path == shape.path) then
          fail(shape.code, "structural shape identity aliases a declaration", Some(shape.path))
        val (dimensions, nodeRoot, projections) =
          module.declarations.find(_.path == shape.input) match
            case Some(declaration) if isConservativeArray(declaration) =>
              (
                declaration.conservativeDimensions,
                Some(declaration.path),
                Vector.empty[StructuralShapeCoordinates.Projection]
              )
            case Some(declaration) =>
              if shape.isNodeSelection || !Set(
                  "input",
                  "output",
                  "wire",
                  "register"
                ).contains(declaration.kind)
              then
                fail(
                  shape.code,
                  "structural shape input must be an owned port or storage value",
                  Some(shape.path)
                )
              val dataType = declaration.dataType.getOrElse(fail(
                shape.code,
                "structural shape input type is absent",
                Some(shape.path)
              ))
              (
                vecTypeParts(dataType, shape.path)._2,
                None,
                Vector.empty[StructuralShapeCoordinates.Projection]
              )
            case None =>
              validatedShapes.get(shape.input) match
                case Some((owner, dimensions, root, chain)) if owner == shape.owner =>
                  (dimensions, root, chain)
                case _ if digitalValues.valueOwner(shape.input).contains(shape.owner) =>
                  digitalValues.requireVisible(shape.input, shape.owner, shape.region, shape.path)
                  (
                    vecTypeParts(digitalValues.expressionType(shape.input), shape.path)._2,
                    None,
                    Vector.empty[StructuralShapeCoordinates.Projection]
                  )
                case _ => fail(
                    shape.code,
                    "shape input must be an owned visible typed value in the same Module",
                    Some(shape.path)
                  )
        validateShapeUse(shape.input, shape.scope, nodeRoot, shape.path)
        if nodeRoot.isEmpty &&
          (shape.region.nonEmpty || digitalValues.valueOwner(shape.input).nonEmpty)
        then digitalValues.requireVisible(shape.input, shape.owner, shape.region, shape.path)
        if shape.region.nonEmpty && (shape.scope.nonEmpty || nodeRoot.nonEmpty) then
          fail(
            shape.code,
            "collection projections cannot carry structural capabilities",
            Some(shape.path)
          )
        if shape.scope.nonEmpty && nodeRoot.isEmpty then
          fail(
            shape.code,
            "generated structural projection requires a conservative root",
            Some(shape.path)
          )
        if shape.isNodeSelection != (nodeRoot.nonEmpty && shape.dimensions.isEmpty) then
          fail(
            shape.code,
            "scalar conservative selection must retain its node-array capability and source occurrences",
            Some(shape.path)
          )
        shape.record match
          case view: KernelShapeViewSnapshot if view.slice.nonEmpty =>
            validateShapeSlice(module, dimensions, view)
          case view: KernelShapeViewSnapshot =>
            val sourceSignature = shapeViewSignature(module, dimensions, view.path)
            val targetSignature = shapeViewSignature(module, view.dimensions, view.path)
            if sourceSignature != targetSignature then
              fail(
                shape.code,
                "shape-view source and result dimensions are not canonically equal",
                Some(shape.path)
              )
          case index: KernelShapeIndexSnapshot =>
            if shape.isNodeSelection then
              validateNodeSelection(module, dimensions, index, nodeSelectionUses)
            if index.indices.isEmpty || index.indices.size > dimensions.size then
              fail(
                shape.code,
                "shape-index rank is empty or exceeds input rank",
                Some(shape.path)
              )
            val expectedResultDimensions = dimensions.drop(index.indices.size)
            if index.resultDimensions != expectedResultDimensions then
              fail(
                shape.code,
                "shape-index result dimensions do not match the unselected input axes",
                Some(shape.path)
              )
            index.indices.zip(dimensions).zipWithIndex.foreach:
              case ((position, dimension), axis) =>
                val minimum = shapeDimensionMinimum(module, dimension, shape.path)
                val bounds = generatedIndexBounds(module, position, shape.path)
                val valid = generatedByInduction.get(position) match
                  case Some(region) =>
                    val dimensionIdentity =
                      shapeDimensionStaticPath(module, dimension).getOrElse(dimension)
                    val upperIdentity = shapeDimensionStaticPath(
                      module,
                      region.upperExclusive
                    ).getOrElse(region.upperExclusive)
                    visibleInduction(index.generatedScope, position) &&
                    (region.maximumTripCount == 0 || sameStaticInteger(
                      module,
                      upperIdentity,
                      dimensionIdentity,
                      shape.path
                    ) ||
                      generatedRangeBounds(
                        module,
                        region.upperExclusive,
                        shape.path
                      ).upper <= minimum)
                  case None => bounds.lower >= 0L && bounds.upper < minimum
                if !valid then
                  fail(
                    shape.code,
                    s"shape index is not valid for every legal extent at axis $axis",
                    Some(shape.path)
                  )
        val chain =
          if nodeRoot.nonEmpty then projections :+ mapProjection(module, shape) else projections
        if shape.isNodeSelection then
          val root = nodeRoot.get
          val rootShape =
            module.declarations.find(_.path == root).map(_.conservativeDimensions)
              .getOrElse(instanceArrayRootsByPath(root).dimensions).map(mapToken(
                module,
                _,
                shape.path
              ))
          try
            val region = StructuralShapeCoordinates.scalarRegion(rootShape, chain)
            val axes = StructuralShapeCoordinates.rootCoordinates(rootShape, chain).map(
              coordinate =>
                StructuralShapeCoordinates.bounds(
                  coordinate,
                  identity =>
                    val bound = generatedIndexBounds(module, identity, shape.path)
                    BigInt(bound.lower) -> BigInt(bound.upper)
                )
            )
            nodeSelections.getOrElse(root -> shape.scope, Vector.empty).foreach:
              (other, otherAxes) =>
                if region == other then
                  fail(
                    "NODAL-BRIDGE-048",
                    "duplicate canonical node-array physical endpoint",
                    Some(shape.path)
                  )
                if !axes.zip(otherAxes).exists((left, right) =>
                    left._2 < right._1 || right._2 < left._1
                  )
                then
                  fail(
                    "NODAL-BRIDGE-048",
                    "distinct node-array selectors may alias for a legal parameter setting",
                    Some(shape.path)
                  )
            nodeSelections.update(
              root -> shape.scope,
              nodeSelections.getOrElse(root -> shape.scope, Vector.empty) :+ (region -> axes)
            )
          catch
            case error: StructuralShapeCoordinates.InvalidCoordinate =>
              fail("NODAL-BRIDGE-048", error.getMessage, Some(shape.path))
        nodeRoot.foreach(root => conservativeRoots.update(shape.path, root))
        if shape.dimensions.nonEmpty then
          validatedShapes.update(shape.path, (shape.owner, shape.dimensions, nodeRoot, chain))
      val incidentRoots = (snapshot.conservativeArrayBindings.iterator.flatMap(relation =>
        Iterator(relation.left, relation.right)
      ) ++ snapshot.topology.iterator.flatMap(edge =>
        Iterator(edge.left, edge.right)
      )).flatMap(conservativeRoots.get).toSet
      snapshot.instanceArrayRoots.foreach: root =>
        if !incidentRoots.contains(root.path) then
          fail(
            "NODAL-BRIDGE-050",
            "child array root has no actual incident array or scalar connection",
            Some(root.path)
          )
      modules.foreach: module =>
        module.instances.foreach: instance =>
          modulesByPath.get(instance.childModule).foreach: child =>
            child.declarations.filter(declaration =>
              isConservativeArray(declaration) && declaration.kind != "analog-node-array"
            ).foreach: port =>
              if !snapshot.instanceArrayRoots.exists(root =>
                  root.owner == module.path && root.instance == instance.path && root.port == port.path
                )
              then
                fail(
                  "NODAL-BRIDGE-050",
                  "child array port requires an actual full root and incident coverage",
                  Some(port.path)
                )
      snapshot.conservativeArrayBindings.foreach: binding =>
        val module = modulesByPath.getOrElse(
          binding.owner,
          fail("NODAL-BRIDGE-050", "array binding has no owning Module", Some(binding.path))
        )
        if binding.path.trim != binding.path || !binding.path.startsWith(s"${binding.owner}.") ||
          !sourceByPath.contains(binding.path) || module.declarations.exists(_.path == binding.path)
        then
          fail(
            "NODAL-BRIDGE-050",
            "array binding must have a distinct owned source-mapped identity",
            Some(binding.path)
          )
        def dimensions(path: String): Vector[String] =
          module.declarations.find(_.path == path) match
            case Some(declaration) if isConservativeArray(declaration) =>
              validateShapeUse(path, binding.generatedScope, Some(path), binding.path)
              declaration.conservativeDimensions
            case _ =>
              validatedShapes.get(path) match
                case Some((owner, dimensions, root @ Some(_), _)) if owner == binding.owner =>
                  validateShapeUse(path, binding.generatedScope, root, binding.path)
                  dimensions
                case _ => fail(
                    "NODAL-BRIDGE-050",
                    "array relation endpoint must be an owned conservative root or shaped view",
                    Some(binding.path)
                  )
        try
          StructuralParameterBinding.validateRelation(
            snapshot,
            binding,
            dimensions(binding.left),
            dimensions(binding.right)
          )
        catch
          case error: IllegalArgumentException =>
            fail("NODAL-BRIDGE-050", error.getMessage, Some(binding.path))
      requireUnique(
        snapshot.sourceMap.map(_.semanticPath),
        "NODAL-BRIDGE-010",
        "source-map semantic path"
      )
      requireUnique(
        snapshot.shapeViews.map(_.path),
        "NODAL-BRIDGE-045",
        "shape-view identity"
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
      validateProceduralShapes()
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

    private def renderModule(
        module: KernelModuleSnapshot,
        definitionComparison: Boolean = false
    ): String =
      val body = mutable.ArrayBuffer.empty[String]
      val values = mutable.LinkedHashMap.empty[String, (String, String)]
      val localDomains = module.domains.map(domain =>
        domain.name -> stableLocalSymbol("domain", domain.name)
      ).toMap
      val declarationsByPath = module.declarations.map(declaration =>
        declaration.path -> declaration
      ).toMap
      val generatedDeclarationPaths =
        generatedRegionsFor(module).flatMap(_.declarations).toSet
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
            body ++= renderParameter(declaration, definitionComparison)
          case _ => ()

      val shapeStaticValues = mutable.LinkedHashMap.empty[String, (String, String)]
      val emittedShapes =
        mutable.LinkedHashMap.empty[String, (String, String, String)]
      var nextShapeStaticValue = 0
      def allocateShapeStaticValue(): String =
        val result = s"%shape_index_static_$nextShapeStaticValue"
        nextShapeStaticValue += 1
        result

      module.declarations.sortBy(_.path).foreach: declaration =>
        (declaration.dataType.toVector
          .flatMap(compoundShapeDimensionPaths(_, declaration.path)) ++
          declaration.conservativeDimensions.filter(expressionsByPath.contains))
          .distinct
          .foreach: dimensionPath =>
            val (_, dataType) = emitStaticValue(
              dimensionPath,
              parameterSymbols,
              declarationsByPath,
              expressionsByPath,
              body,
              shapeStaticValues,
              () => allocateShapeStaticValue(),
              requireStructural = true
            )
            if dataType != "i64" then
              fail(
                "NODAL-BRIDGE-018",
                "compound Vec dimension must have Integer type",
                Some(dimensionPath)
              )

      val digitalTypeRoots = snapshot.digitalExpressions.filter(_.owner == module.path)
        .map(value => value.path -> value.dataType) ++
        snapshot.digitalCollections.filter(_.owner == module.path).flatMap(_.arguments)
          .map(value => value.path -> value.dataType) ++
        snapshot.interfaceContracts.filter(_.owner == module.path).flatMap(_.members)
          .map(value => value.path -> value.dataType)
      val digitalStaticRoots = digitalTypeRoots.flatMap: (path, dataType) =>
        compoundShapeDimensionPaths(dataType, path).map(path -> _)
      val interfaceStaticRoots = snapshot.interfaceContracts.filter(_.owner == module.path)
        .flatMap(value => value.dimensions.filter(expressionsByPath.contains).map(value.path -> _))
      (digitalStaticRoots ++ interfaceStaticRoots).distinct.sortBy(identity).foreach:
        (path, dimensionPath) =>
          val (_, dataType) = emitStaticValue(
            dimensionPath,
            parameterSymbols,
            declarationsByPath,
            expressionsByPath,
            body,
            shapeStaticValues,
            () => allocateShapeStaticValue(),
            requireStructural = true
          )
          if dataType != "i64" then
            fail(
              "NODAL-BRIDGE-054",
              "digital result width must have Integer type",
              Some(path)
            )

      module.declarations.filter(_.kind == "memory").sortBy(_.path).foreach: declaration =>
        val depth = DigitalStorageMlir.depth(declaration)
        depth.toLongOption.foreach: literal =>
          if depth != literal.toString then
            fail(
              "NODAL-BRIDGE-054",
              "memory depth literal must be canonical",
              Some(declaration.path)
            )
        val reference =
          depth.toLongOption.map(_.toString).orElse(shapeDimensionStaticPath(module, depth))
            .getOrElse(fail(
              "NODAL-BRIDGE-054",
              "memory depth has no owned static identity",
              Some(declaration.path)
            ))
        val bounds = shapeStaticBounds(
          module,
          reference,
          declaration.path,
          "NODAL-BRIDGE-054",
          "memory depth",
          requiredEffect = "shape"
        )
        if bounds.lower <= 0L then
          fail(
            "NODAL-BRIDGE-054",
            "memory depth must be positive for every legal parameter value",
            Some(declaration.path)
          )
        if expressionsByPath.contains(reference) then
          val _ = emitStaticValue(
            reference,
            parameterSymbols,
            declarationsByPath,
            expressionsByPath,
            body,
            shapeStaticValues,
            () => allocateShapeStaticValue(),
            requireStructural = true
          )
        val canonical =
          module.declarations.find(_.path == reference).map(_.name).getOrElse(reference)
        val dataType = declaration.dataType.map(parseType(_, declaration.path)).getOrElse(
          fail("NODAL-BRIDGE-054", "memory element type is absent", Some(declaration.path))
        )
        body += DigitalStorageMlir.renderMemory(
          module,
          declaration,
          dataType,
          canonical,
          stableLocalSymbol,
          bridgeMetadata(declaration.path, Vector.empty),
          location(declaration.path)
        )

      body ++= interfaceValues.renderDefinitions(
        module,
        interfaceNativeType,
        stableLocalSymbol,
        path => bridgeMetadata(path, Vector.empty),
        location
      )

      module.declarations.filter(isConservativeArray)
        .filterNot(declaration => generatedDeclarationPaths.contains(declaration.path))
        .sortBy(_.path).zipWithIndex.foreach: (declaration, index) =>
          val boundary = declaration.kind != "analog-node-array"
          val result = if boundary then s"%terminal_array_$index" else s"%node_array_$index"
          body += renderConservativeArray(declaration, result, None)
          values.update(declaration.path, result -> nodeArrayType(declaration))

      val renderedShapePaths = mutable.HashSet.empty[String]
      def renderShapes(
          shapeBody: mutable.ArrayBuffer[String],
          shapeValues: mutable.LinkedHashMap[String, (String, String)],
          scope: Option[KernelGeneratedScope],
          onlyPath: Option[String] = None
      ): Unit =
        val collectionBody = onlyPath.flatMap(structuralShapesByPath.get).exists(_.region.nonEmpty)
        val selectorBody = if collectionBody then shapeBody else body
        val selectorValues = if collectionBody then
          mutable.LinkedHashMap.empty[String, (String, String)]
        else shapeStaticValues
        instanceArrayRootsByOwner.getOrElse(
          module.path,
          Vector.empty
        ).filter(root => root.generatedScope == scope && onlyPath.forall(_ == root.path))
          .zipWithIndex.foreach: (root, index) =>
            root.dimensions.distinct.filter(expressionsByPath.contains).foreach: path =>
              emitStaticValue(
                path,
                parameterSymbols,
                declarationsByPath,
                expressionsByPath,
                body,
                shapeStaticValues,
                () => allocateShapeStaticValue(),
                requireStructural = true
              )
            val instance = module.instances.find(_.path == root.instance).get
            val declaration =
              modulesByPath(instance.childModule).declarations.find(_.path == root.port).get
            val elementType = "!nodal.terminal<\"electrical\">"
            val resultType =
              s"!nodal.shaped<${quoted(root.dimensions.mkString(","))}, $elementType>"
            val result = if scope.isEmpty then s"%instance_terminal_array_$index"
            else "%" + stableLocalSymbol("instance_terminal_array", root.path)
            shapeBody += operation(
              "nodal.instance_terminal_array",
              results = Vector(result),
              resultTypes = Vector(resultType),
              attributes = Vector(
                "instance" -> symbolReference(stableLocalSymbol(
                  "instance",
                  lastSegment(instance.path)
                )),
                "port" -> quoted(declaration.name),
                "name" -> quoted(s"${lastSegment(instance.path)}.${declaration.name}"),
                "direction" -> quoted(boundaryDirection(declaration)),
                "flow_orientation" -> quoted("out_of_component"),
                "source_path" -> quoted(declaration.path),
                "metadata" -> bridgeMetadata(
                  root.path,
                  Vector(
                    "instance_path" -> quoted(instance.path),
                    "child_path" -> quoted(instance.childModule),
                    "storage" -> quoted("structural")
                  )
                )
              ) ++ scopeAttributes(scope),
              semanticPath = root.path
            )
            shapeValues.update(root.path, result -> resultType)
            emittedShapes.update(root.path, (result, resultType, elementType))
        val selectedShapes = onlyPath match
          case Some(path) => structuralShapesByPath.get(path).toVector
          case None => structuralShapesByOwner.getOrElse(module.path, Vector.empty)
        selectedShapes.filter(shape =>
          shape.scope == scope && (shape.region.isEmpty || onlyPath.contains(shape.path)) &&
            !renderedShapePaths.contains(shape.path)
        ).foreach: shape =>
          shape.dimensions.distinct.filter(expressionsByPath.contains).foreach: dimensionPath =>
            val (_, dataType) = emitStaticValue(
              dimensionPath,
              parameterSymbols,
              declarationsByPath,
              expressionsByPath,
              body,
              shapeStaticValues,
              () => allocateShapeStaticValue(),
              requireStructural = true
            )
            if dataType != "i64" then
              fail(
                shape.code,
                "structural shape expression dimension must have Integer type",
                Some(dimensionPath)
              )
          val (inputValue, inputType, elementText) = declarationsByPath.get(shape.input) match
            case Some(declaration) if isConservativeArray(declaration) =>
              val (value, dataType) = shapeValues(declaration.path)
              (value, dataType, "!nodal.terminal<\"electrical\">")
            case Some(declaration) =>
              val dataType = declaration.dataType.getOrElse(
                fail(shape.code, "structural shape input type is absent", Some(shape.path))
              )
              val (element, _) = vecTypeParts(dataType, shape.path)
              val shapedType = parseType(dataType, shape.path)
              val portValue = shapeValues.get(declaration.path) match
                case Some((value, actualType)) =>
                  if actualType != shapedType then
                    fail(
                      shape.code,
                      "shape root type differs from its actual typed value",
                      Some(shape.path)
                    )
                  value
                case None =>
                  val result = s"${shape.result}_input"
                  shapeBody ++= renderDigitalDeclarationValue(declaration, result, shapedType, None)
                  shapeValues.update(declaration.path, result -> shapedType)
                  result
              (portValue, shapedType, parseType(element, shape.path))
            case None =>
              shapeValues.get(shape.input).filter(_ =>
                digitalValues.valueOwner(shape.input).contains(module.path)
              ) match
                case Some((value, actualType)) =>
                  val sourceType = digitalValues.expressionType(shape.input)
                  val (element, _) = vecTypeParts(sourceType, shape.path)
                  if actualType != parseType(sourceType, shape.input) then
                    fail(
                      shape.code,
                      "shape input carrier differs from its exact value type",
                      Some(shape.path)
                    )
                  (value, actualType, parseType(element, shape.path))
                case None => emittedShapes.getOrElse(
                    shape.input,
                    fail(
                      shape.code,
                      "structural shaped input was not emitted before its consumer",
                      Some(shape.path)
                    )
                  )
          val elementType = elementText
          val conservative = elementType == "!nodal.terminal<\"electrical\">"
          val resultType =
            if shape.dimensions.isEmpty then elementType
            else s"!nodal.shaped<${quoted(shape.dimensions.mkString(","))}, $elementType>"
          shape.record match
            case view: KernelShapeViewSnapshot if view.slice.nonEmpty =>
              val selection = view.slice.get
              def staticValue(token: String): (String, String) =
                token.toLongOption match
                  case Some(literal) =>
                    selectorValues.getOrElseUpdate(
                      s"literal:$literal", {
                        val result = allocateShapeStaticValue()
                        selectorBody += operation(
                          "nodal.const_literal",
                          results = Vector(result),
                          resultTypes = Vector("i64"),
                          attributes = Vector(
                            "value" -> s"$literal : i64",
                            "spelling" -> quoted(literal.toString),
                            "metadata" -> "{use = \"shape_slice_bound\"}"
                          ),
                          semanticPath = view.path
                        )
                        result -> "i64"
                      }
                    )
                  case None =>
                    if collectionBody then
                      fail(
                        shape.code,
                        "collection slice bounds currently require static literals",
                        Some(view.path)
                      )
                    val path = shapeDimensionStaticPath(module, token).getOrElse(
                      fail(shape.code, "shape slice value has no static identity", Some(view.path))
                    )
                    val value = emitStaticValue(
                      path,
                      parameterSymbols,
                      declarationsByPath,
                      expressionsByPath,
                      body,
                      shapeStaticValues,
                      () => allocateShapeStaticValue(),
                      requireStructural = true
                    )
                    if value._2 != "i64" then
                      fail(
                        shape.code,
                        "shape slice operands must have Integer type",
                        Some(view.path)
                      )
                    value
              val bounds = Vector(
                selection.lower,
                selection.upperExclusive,
                view.dimensions(selection.axis)
              ).map(staticValue)
              shapeBody += operation(
                "nodal.shape_slice",
                results = Vector(shape.result),
                operands = inputValue +: bounds.map(_._1),
                operandTypes = inputType +: bounds.map(_._2),
                resultTypes = Vector(resultType),
                attributes = Vector(
                  "axis" -> integer(selection.axis),
                  "dimensions" -> quoted(view.dimensions.mkString(",")),
                  "origin" -> quoted(view.input),
                  "observability" -> quoted("source_mapped"),
                  "metadata" -> bridgeMetadata(
                    view.path,
                    Vector(
                      "input_path" -> quoted(view.input),
                      "storage" -> quoted("structural"),
                      "access" -> quoted("read_only")
                    )
                  )
                ) ++ scopeAttributes(view.generatedScope),
                semanticPath = view.path
              )
            case view: KernelShapeViewSnapshot =>
              shapeBody += operation(
                "nodal.shape_view",
                results = Vector(shape.result),
                operands = Vector(inputValue),
                operandTypes = Vector(inputType),
                resultTypes = Vector(resultType),
                attributes = Vector(
                  "dimensions" -> quoted(view.dimensions.mkString(",")),
                  "origin" -> quoted(view.input),
                  "materialization" -> quoted("view"),
                  "observability" -> quoted("source_mapped"),
                  "metadata" -> bridgeMetadata(
                    view.path,
                    Vector(
                      "input_path" -> quoted(view.input),
                      "storage" -> quoted("structural")
                    ) ++ Option.when(conservative)("access" -> quoted("read_only"))
                  )
                ) ++ scopeAttributes(view.generatedScope),
                semanticPath = view.path
              )
            case index: KernelShapeIndexSnapshot =>
              val indexValues = index.indices.zipWithIndex.map: (position, axis) =>
                position.toIntOption match
                  case Some(literal) =>
                    val result = s"${shape.result}_index_$axis"
                    shapeBody += operation(
                      "nodal.constant",
                      results = Vector(result),
                      resultTypes = Vector("index"),
                      attributes = Vector(
                        "value" -> s"$literal : index",
                        "metadata" -> s"{axis = ${integer(axis)}}"
                      ),
                      semanticPath = index.path
                    )
                    result -> "index"
                  case None =>
                    if collectionBody then
                      fail(
                        shape.code,
                        "collection index currently requires a static literal",
                        Some(index.path)
                      )
                    val value =
                      shapeValues.get(position).filter(_._2 == "i64").getOrElse(emitStaticValue(
                        position,
                        parameterSymbols,
                        declarationsByPath,
                        expressionsByPath,
                        body,
                        shapeStaticValues,
                        () => allocateShapeStaticValue(),
                        requireStructural = true
                      ))
                    if value._2 != "i64" then
                      fail(
                        shape.code,
                        "shape index expression must have Integer type",
                        Some(position)
                      )
                    value
              shapeBody += operation(
                "nodal.shape_index",
                results = Vector(shape.result),
                operands = inputValue +: indexValues.map(_._1),
                operandTypes = inputType +: indexValues.map(_._2),
                resultTypes = Vector(resultType),
                attributes = Vector(
                  "metadata" -> bridgeMetadata(
                    index.path,
                    Vector("input_path" -> quoted(index.input)) ++
                      Option.when(shape.isNodeSelection)(Vector(
                        "selection_kind" -> quoted("node_array"),
                        "storage" -> quoted("structural"),
                        "source_uses" -> array(if definitionComparison then Vector.empty
                        else index.sourceUses.map(quoted))
                      )).toVector.flatten ++
                      Option.when(conservative && !shape.isNodeSelection)(Vector(
                        "storage" -> quoted("structural"),
                        "access" -> quoted("read_only")
                      )).toVector.flatten
                  )
                ) ++ scopeAttributes(index.generatedScope),
                semanticPath = index.path
              )
          shapeValues.update(shape.path, shape.result -> resultType)
          renderedShapePaths.add(shape.path)
          if !shape.isNodeSelection && shape.dimensions.nonEmpty then
            emittedShapes.update(shape.path, (shape.result, resultType, elementText))

      def renderDigitalProjection(path: String, resolve: String => (String, String))
          : DigitalValueMlir.Projection =
        val shape = structuralShapesByPath.getOrElse(
          path,
          fail(
            "NODAL-BRIDGE-054",
            "digital projection has no authoritative shape record",
            Some(path)
          )
        )
        if shape.owner != module.path || shape.scope.nonEmpty || shape.isNodeSelection then
          fail("NODAL-BRIDGE-054", "digital projection has invalid scope or capability", Some(path))
        val input = resolve(shape.input)
        val projectionValues = mutable.LinkedHashMap(shape.input -> input)
        val projected = mutable.ArrayBuffer.empty[String]
        renderShapes(projected, projectionValues, None, Some(path))
        DigitalValueMlir.Projection(
          projected.toVector,
          projectionValues.getOrElse(
            path,
            fail("NODAL-BRIDGE-054", "digital projection has no actual SSA result", Some(path))
          )
        )

      val digital = digitalValues.renderModule(
        module,
        parseType,
        (text, path) => parseTypeChecked(text, path, 0, Array(65536), storageContext = true),
        digitalScalarType,
        digitalWidthRelation,
        digitalWidthFormula,
        exactDigitalValueType,
        vecTypeParts,
        renderDigitalDeclarationValue,
        DigitalStorageMlir.initializer,
        path => bridgeMetadata(path, Vector.empty),
        location,
        renderDigitalProjection,
        interfaceValues.declarationType,
        (expression, result, dataType, operands) =>
          interfaceValues.renderValue(
            expression,
            result,
            dataType,
            operands,
            interfaceNativeType,
            bridgeMetadata(expression.path, Vector.empty),
            location(expression.path)
          ),
        interfaceValues.roots(module)
      )
      body ++= digital.lines
      values ++= digital.values

      renderShapes(body, values, None)

      snapshot.digitalAssignments.filter(_.owner == module.path).sortBy(_.path).foreach:
        assignment =>
          def value(path: String): (String, String) = values.getOrElse(
            path,
            fail(
              "NODAL-BRIDGE-054",
              "digital assignment value has no actual typed SSA carrier",
              Some(assignment.path)
            )
          )
          val target = value(assignment.target)
          val source = value(assignment.value)
          if containsBoundaryCapability(target._2) || containsBoundaryCapability(source._2) then
            val targetRoot = digitalValues.boundaryRoot(assignment.target, module.path)
            val sourceRoot = digitalValues.boundaryRoot(assignment.value, module.path)
            if assignment.kind != "continuous" || targetRoot.kind != "output" || sourceRoot.kind != "input" ||
              localDomainName(module, targetRoot) != localDomainName(module, sourceRoot)
            then
              fail(
                "NODAL-BRIDGE-054",
                "clock/reset wiring requires same-domain input/output boundary roots",
                Some(assignment.path)
              )
          body += DigitalStorageMlir.renderAssignment(
            assignment.path,
            assignment.kind,
            value(assignment.target),
            value(assignment.value),
            bridgeMetadata(assignment.path, Vector.empty),
            location(assignment.path),
            if containsBoundaryCapability(target._2) || containsBoundaryCapability(source._2) then
              target._2 == source._2
            else
              exactDigitalValueType(
                digitalValues.expressionType(assignment.target),
                digitalValues.expressionType(assignment.value),
                assignment.path
              )
          )

      body ++= interfaceValues.renderEffects(
        module,
        path =>
          values.getOrElse(
            path,
            fail(
              "NODAL-BRIDGE-054",
              "digital connectivity requires an actual owned typed SSA value",
              Some(path)
            )
          ),
        interfaceNativeType,
        stableLocalSymbol,
        path => bridgeMetadata(path, Vector.empty),
        location,
        validateInterfaceCapabilitySource,
        (valuePath, expected, usePath) =>
          exactDigitalValueType(digitalValues.expressionType(valuePath), expected, usePath)
      )

      // Keep one canonical static DAG per module: defaults, dimensions and
      // instance overrides can share the same authored expression identity.
      module.declarations.filter(_.kind == "parameter").sortBy(_.path).foreach: declaration =>
        declaration.attributes.toMap.get("default").filter(value =>
          declaration.dataType.contains("Integer") && value.toLongOption.isEmpty
        ).foreach: reference =>
          val (value, dataType) = emitStaticValue(
            reference,
            parameterSymbols,
            declarationsByPath,
            expressionsByPath,
            body,
            shapeStaticValues,
            () => allocateShapeStaticValue(),
            requireStructural = false
          )
          if dataType != "i64" then
            fail(
              "NODAL-BRIDGE-050",
              "dependent Integer parameter default must have Integer type",
              Some(declaration.path)
            )
          body += operation(
            "nodal.parameter_value",
            operands = Vector(value),
            operandTypes = Vector(dataType),
            attributes = Vector(
              "parameter" -> symbolReference(parameterSymbols(declaration.path)),
              "metadata" -> bridgeMetadata(
                declaration.path,
                Vector("source_value" -> quoted(reference))
              )
            ),
            semanticPath = declaration.path
          )

      module.instances.filter(_.generatedScope.isEmpty).sortBy(_.path).foreach: instance =>
        body ++= renderInstance(
          instance,
          parameterSymbols,
          declarationsByPath,
          expressionsByPath,
          shapeStaticValues,
          () => allocateShapeStaticValue()
        )

      interfaceEntries(module).filter(_ => !interfaceContractOwners.contains(module.path)).foreach:
        entry =>
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

      val externallyBoundTerminals =
        externallyBoundTerminalsByModule.getOrElse(module.path, Set.empty)
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
      val generatedStaticValues = shapeStaticValues
      var nextGeneratedStaticValue = 0
      def allocateGeneratedStaticValue(): String =
        val result = "%generated_bound_value_" + nextGeneratedStaticValue
        nextGeneratedStaticValue += 1
        result
      def renderTopology(
          scope: Option[KernelGeneratedScope],
          scopeBody: mutable.ArrayBuffer[String],
          scopeValues: mutable.LinkedHashMap[String, (String, String)]
      ): Unit =
        val topology = topologyEntries(module).filter(_.generatedScope == scope)
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
          val result = if scope.isEmpty then s"%instance_terminal_$index"
          else "%" + stableLocalSymbol("instance_terminal", path)
          scopeBody += operation(
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
            ) ++ scopeAttributes(scope),
            semanticPath = path
          )
          scopeValues.update(path, result -> resultType)

        conservativeArrayBindingsByOwner.getOrElse(module.path, Vector.empty)
          .filter(_.generatedScope == scope).foreach: binding =>
            def endpoint(path: String): (String, String) =
              scopeValues.get(path).orElse(emittedShapes.get(path).map((value, dataType, _) =>
                value -> dataType
              ))
                .getOrElse(fail(
                  "NODAL-BRIDGE-050",
                  "array relation endpoint was not emitted before its use",
                  Some(binding.path)
                ))
            val left = endpoint(binding.left)
            val right = endpoint(binding.right)
            scopeBody += operation(
              "nodal.connect_array",
              operands = Vector(left._1, right._1),
              operandTypes = Vector(left._2, right._2),
              attributes = Vector(
                "connection_id" -> quoted(binding.path),
                "source_path" -> quoted(binding.path),
                "metadata" -> bridgeMetadata(
                  binding.path,
                  Vector(
                    "left_path" -> quoted(binding.left),
                    "right_path" -> quoted(binding.right)
                  )
                )
              ) ++ scopeAttributes(scope),
              semanticPath = binding.path
            )

        topology.foreach: edge =>
          if Set("terminal-connect", "node-connect").contains(edge.kind) then
            (scopeValues.get(edge.left), scopeValues.get(edge.right)) match
              case (Some((leftValue, leftType)), Some((rightValue, rightType)))
                  if leftType == rightType =>
                scopeBody += operation(
                  "nodal.connect",
                  operands = Vector(leftValue, rightValue),
                  operandTypes = Vector(leftType, rightType),
                  attributes = Vector(
                    "connection_id" -> quoted(edge.path.getOrElse(s"${edge.left}<->${edge.right}")),
                    "source_path" -> quoted(edge.path.getOrElse(s"${edge.left}<->${edge.right}")),
                    "metadata" -> bridgeMetadata(
                      edge.path.getOrElse(s"${edge.left}->${edge.right}"),
                      Vector("topology_kind" -> quoted(edge.kind))
                    )
                  ) ++ scopeAttributes(scope),
                  semanticPath = edge.path.getOrElse(edge.left)
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

      renderTopology(None, body, values)

      val accessBranches = mutable.LinkedHashMap.empty[String, (String, String)]
      val accessExpressions = analogRegionsFor(module).flatMap(_.expressions).filter(expression =>
        (expression.operation == "potential_access" || expression.operation == "flow_access") &&
          expression.operands.forall(values.contains)
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
            () => allocateGeneratedStaticValue(),
            accessBranches,
            values.toMap,
            (scopeRegion, scopeBody, scopeValues) =>
              val scope = Some(KernelGeneratedScope(scopeRegion.path, scopeRegion.induction))
              renderShapes(scopeBody, scopeValues, scope)
              module.instances.filter(_.generatedScope == scope).sortBy(_.path).foreach: instance =>
                scopeBody ++= renderInstance(
                  instance,
                  parameterSymbols,
                  declarationsByPath,
                  expressionsByPath,
                  shapeStaticValues,
                  () => allocateShapeStaticValue(),
                  Some(body)
                )
              renderTopology(scope, scopeBody, scopeValues)
          )
        )
      body ++= generatedStatic
      body ++= generatedBodies

      body ++= AnalogUserFunctionMlir.renderModule(snapshot, module.path)
      body ++= sourceEquations.renderModule(module.path, parameterSymbols)

      analogRegionsFor(module).filter(_.generatedOwner.isEmpty).zipWithIndex.foreach:
        (region, regionIndex) =>
          body += renderAnalogRegion(
            region,
            regionIndex,
            declarationsByPath,
            parameterSymbols,
            accessBranches
          )

      body ++= AnalogProceduralMlir.renderModule(
        snapshot,
        module.path,
        token =>
          emitProceduralStatic(
            token,
            module,
            parameterSymbols,
            declarationsByPath,
            expressionsByPath,
            body,
            shapeStaticValues,
            () => allocateShapeStaticValue()
          )
      )

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
      val regions = snapshot.analogRegions.filter(_.module == module.path).sortBy(_.path)
      regions.foreach: analog =>
        if analog.generatedOwner.isDefined != analog.generatedInduction.isDefined then
          fail(
            "NODAL-BRIDGE-043",
            "generated continuous region requires paired owner and induction identities",
            Some(analog.path)
          )
        analog.generatedOwner.foreach: owner =>
          val generated = snapshot.generatedRegions.find(_.path == owner).getOrElse(
            fail(
              "NODAL-BRIDGE-043",
              "generated continuous region has no owning generated region",
              Some(analog.path)
            )
          )
          if generated.owner != module.path ||
            !analog.generatedInduction.contains(generated.induction)
          then
            fail(
              "NODAL-BRIDGE-043",
              "generated continuous region ownership or induction is invalid",
              Some(analog.path)
            )
        val operators = snapshot.continuousOperators.filter(operator =>
          analog.expressions.exists(_.path == operator.path)
        )
        if operators.exists(operator =>
            operator.generatedOwner != analog.generatedOwner ||
              operator.generatedInduction != analog.generatedInduction
          )
        then
          fail(
            "NODAL-BRIDGE-043",
            "continuous operator ownership differs from its analog region",
            Some(analog.path)
          )
        val waveforms = snapshot.waveformOperators.filter(operator =>
          analog.expressions.exists(_.path == operator.path)
        )
        if waveforms.exists(operator =>
            operator.generatedOwner != analog.generatedOwner ||
              operator.generatedInduction != analog.generatedInduction
          )
        then
          fail(
            "NODAL-BRIDGE-043",
            "waveform operator ownership differs from its analog region",
            Some(analog.path)
          )
        val transfers = snapshot.transferOperators.filter(operator =>
          analog.expressions.exists(_.path == operator.path)
        )
        if transfers.exists(operator =>
            operator.generatedOwner != analog.generatedOwner ||
              operator.generatedInduction != analog.generatedInduction
          )
        then
          fail(
            "NODAL-BRIDGE-043",
            "transfer operator ownership differs from its analog region",
            Some(analog.path)
          )
        val noise = snapshot.noiseOperators.filter(operator =>
          analog.expressions.exists(_.path == operator.path)
        )
        if noise.exists(operator =>
            operator.generatedOwner != analog.generatedOwner ||
              operator.generatedInduction != analog.generatedInduction
          )
        then
          fail(
            "NODAL-BRIDGE-043",
            "noise operator ownership differs from its analog region",
            Some(analog.path)
          )
        analog.contributions.foreach: contribution =>
          if contribution.generatedOwner.isDefined != contribution.generatedInduction.isDefined ||
            contribution.generatedOwner != analog.generatedOwner ||
            contribution.generatedInduction != analog.generatedInduction
          then
            fail(
              "NODAL-BRIDGE-043",
              "contribution ownership differs from its analog region",
              Some(contribution.path)
            )
      regions

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
        ) ++ value.generatedOwner.toVector.map(owner => "generated_owner" -> quoted(owner)) ++
        value.generatedInduction.toVector.map(induction =>
          "generated_induction" -> quoted(induction)
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
          expression.unit.toVector.map(unit => "unit" -> quoted(unit)) ++
            region.generatedOwner.toVector.map(owner => "generated_owner" -> quoted(owner)) ++
            region.generatedInduction.toVector.map(induction =>
              "generated_induction" -> quoted(induction)
            )
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
              attributes = noiseOperatorAttributes(contract) :+ ("metadata" -> metadata),
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
          case name if name.startsWith(AnalogContextContract.Prefix) =>
            val descriptor =
              AnalogContextContract.entry(name.stripPrefix(AnalogContextContract.Prefix))
            if expression.operands.nonEmpty then
              fail(
                "NODAL-ANALOG-044-002",
                "simulator context query requires zero operands",
                Some(expression.path)
              )
            if expression.literal.nonEmpty || expression.unit.nonEmpty then
              fail(
                "NODAL-ANALOG-044-004",
                "simulator context query cannot carry a constant or unit override",
                Some(expression.path)
              )
            val outputType =
              s"!nodal.quantity<${quoted(descriptor.resultKind)}, ${quoted(descriptor.dimension)}>"
            lines += operation(
              "nodal.analog_context",
              results = Vector(result),
              resultTypes = Vector(outputType),
              attributes = Vector(
                "query_kind" -> quoted(descriptor.id),
                "registry_version" -> quoted(AnalogContextContract.Version),
                "metadata" -> metadata
              ) ++ region.generatedOwner.toVector.map(owner =>
                "generated_owner" -> quoted(owner)
              ) ++
                region.generatedInduction.toVector.map(induction =>
                  "generated_induction" -> quoted(induction)
                ),
              semanticPath = expression.path
            )
            values.update(expression.path, result -> outputType)
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
          attributes = contribution.generatedOwner.toVector.map(owner =>
            "generated_owner" -> quoted(owner)
          ) ++ contribution.generatedInduction.toVector.map(induction =>
            "generated_induction" -> quoted(induction)
          ) ++ Vector(
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
        attributes = region.generatedOwner.toVector.map(owner =>
          "generated_owner" -> quoted(owner)
        ) ++ region.generatedInduction.toVector.map(induction =>
          "generated_induction" -> quoted(induction)
        ) ++ Vector(
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
        declaration: KernelDeclarationSnapshot,
        definitionComparison: Boolean
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
      if dataType.startsWith("!nodal.capability<") then
        fail(
          "NODAL-BRIDGE-054",
          "clock/reset capabilities cannot be parameter literals",
          Some(declaration.path)
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
      val transportDefault =
        if dataType == "i64" && defaultValue.toLongOption.isEmpty then
          try
            StructuralParameterBinding.defaultInteger(
              snapshot,
              resolveOwningModule(declaration.path),
              defaultValue
            ).toString
          catch
            case error: IllegalArgumentException =>
              fail("NODAL-BRIDGE-050", error.getMessage, Some(declaration.path))
        else defaultValue
      val rendered = mutable.ArrayBuffer(
        operation(
          "nodal.parameter",
          attributes = Vector(
            "sym_name" -> quoted(parameterSymbol),
            "type" -> dataType,
            "default_value" -> (if isDigitalParameterType(dataType) then
                                  digitalValues.parameterDefault(
                                    declaration,
                                    defaultValue,
                                    digitalScalarType
                                  )
                                else typedLiteral(transportDefault, dataType, declaration.path)),
            "variability" -> quoted("symbolic"),
            "classification" -> quoted(classification),
            "metadata" -> bridgeMetadata(
              declaration.path,
              declaration.attributes
                .filterNot(_._1 == "default")
                .map((key, value) => normalizeKey(key) -> quoted(value))
            )
          ) ++ AnalogEquationMlir.parameterUnit(sourceEquations, declaration).toVector.map(unit =>
            "unit" -> symbolReference(unit)
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
        // SSA spellings and occurrence provenance do not distinguish reusable definitions.
        // The comparison renderer retains every typed operation and declaration attribute.
        val prefix = stableLocalSymbol(
          "parameter_range",
          if definitionComparison then declaration.name else declaration.path
        )
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
        val allowedEffects =
          Set("topology", "component_count", "equation_count", "shape", "rank", "width")
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
        case "static_integer_max" => "max"
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

    private def validateProceduralShapes(): Unit =
      import AnalogProceduralRuntime.*
      val eventResources = mutable.HashMap.empty[String, EventResource]
      snapshot.analogProcedural.foreach: program =>
        val module = modulesByPath.getOrElse(
          program.owner,
          fail("NODAL-BRIDGE-049", "procedural storage has no owning module", Some(program.owner))
        )
        val variables =
          program.variables.map(record => record.variable.identity -> record.variable).toMap
        def dimensions(shape: Vector[String], path: String): Unit =
          if shape.nonEmpty then
            val _ = shapeViewSignature(module, shape, path)
        def projection(
            value: StorageProjection,
            input: Vector[String],
            inputPath: String
        ): Vector[String] =
          val (path, owner, source) = value match
            case index: KernelShapeIndexSnapshot => (index.path, index.owner, index.input)
            case view: KernelShapeViewSnapshot => (view.path, view.owner, view.input)
          if owner != program.owner || source != inputPath || !path.startsWith(
              s"${program.owner}."
            ) || path == source
          then
            fail(
              "NODAL-BRIDGE-049",
              "procedural projection has invalid owner or immediate input identity",
              Some(path)
            )
          value match
            case index: KernelShapeIndexSnapshot =>
              if index.sourceUses.nonEmpty || index.indices.isEmpty || index.indices.size > input.size ||
                index.resultDimensions != input.drop(index.indices.size)
              then
                fail(
                  "NODAL-BRIDGE-049",
                  "procedural index rank or result shape is inconsistent",
                  Some(path)
                )
              index.indices.zip(input).foreach: (position, extent) =>
                val minimum = shapeDimensionMinimum(module, extent, path)
                val staticPosition = position.toLongOption.map(_ => position).getOrElse(
                  shapeDimensionStaticPath(module, position).getOrElse(
                    fail(
                      "NODAL-BRIDGE-049",
                      "procedural index has no owned static identity",
                      Some(path)
                    )
                  )
                )
                val bounds = shapeIndexBounds(module, staticPosition, path)
                if bounds.lower < 0L || bounds.upper >= minimum then
                  fail(
                    "NODAL-BRIDGE-049",
                    "procedural index is not in bounds for every legal shape",
                    Some(path)
                  )
              index.resultDimensions
            case view: KernelShapeViewSnapshot =>
              if view.slice.nonEmpty then
                def endpoint(token: String): String = token.toLongOption.map(_ => token).getOrElse(
                  shapeDimensionStaticPath(module, token).getOrElse(
                    fail(
                      "NODAL-BRIDGE-049",
                      "procedural slice has no owned static identity",
                      Some(path)
                    )
                  )
                )
                val selected = view.slice.get
                validateShapeSlice(
                  module,
                  input,
                  view.copy(slice =
                    Some(selected.copy(
                      lower = endpoint(selected.lower),
                      upperExclusive = endpoint(selected.upperExclusive)
                    ))
                  )
                )
              else if shapeViewSignature(
                  module,
                  input,
                  path
                ) != shapeViewSignature(module, view.dimensions, path)
              then
                fail(
                  "NODAL-BRIDGE-049",
                  "procedural view does not preserve exact cardinality",
                  Some(path)
                )
              view.dimensions
        def reference(value: StorageReference): Unit =
          if !variables.get(
              value.root.identity
            ).contains(value.root) || value.root.owner != program.owner
          then
            fail(
              "NODAL-BRIDGE-049",
              "procedural storage reference has a forged or missing root",
              Some(value.identity)
            )
          dimensions(value.root.valueType.dimensions, value.root.identity)
          value match
            case selection: StorageSelection =>
              if selection.projections.size > 512 then
                fail(
                  "NODAL-BRIDGE-049",
                  "procedural projection exceeds the supported dependency depth",
                  Some(value.identity)
                )
              selection.projections.foldLeft(
                value.root.valueType.dimensions -> value.root.identity
              ):
                case ((input, source), step) =>
                  val result = projection(step, input, source)
                  val path = step match
                    case index: KernelShapeIndexSnapshot => index.path
                    case view: KernelShapeViewSnapshot => view.path
                  result -> path
              ()
            case _ => ()
        val expressionIdentities = snapshot.names.iterator
          .filter(_.category == "expression").map(_.semanticPath).toSet
        val valueStates = new java.util.IdentityHashMap[Value, java.lang.Integer]()
        val valueHeights = new java.util.IdentityHashMap[Value, java.lang.Integer]()
        final case class ValueKey(
            rendered: String,
            valueType: ValueType,
            reads: Vector[StorageReference],
            source: Option[(String, Int, Option[StorageProjection], Option[StorageReference])],
            scalarSources: Vector[Int]
        )
        val valueKeys = mutable.HashMap.empty[ValueKey, Int]
        val sourceIdentities = mutable.HashMap.empty[String, Int]
        def invalidValue(message: String, path: String): Nothing =
          fail("NODAL-BRIDGE-049", message, Some(path))
        def sourceIdentity(input: Value): Unit =
          if !input.rendered.startsWith(s"${program.owner}.") ||
            !expressionIdentities.contains(input.rendered)
          then
            invalidValue("procedural value has no owned retained source identity", input.rendered)
        def value(input: Value, depth: Int = 0): Int =
          if depth >= 512 then
            invalidValue("procedural value dependency exceeds supported depth", program.owner)
          val previous = Option(valueStates.get(input))
          if previous.nonEmpty then
            val state = previous.get.intValue
            if state == -1 then
              invalidValue("cyclic retained procedural value source", input.rendered)
            if depth + valueHeights.get(input).intValue >= 512 then
              invalidValue("procedural value dependency exceeds supported depth", program.owner)
            state
          else
            valueStates.put(input, -1)
            dimensions(input.valueType.dimensions, input.rendered)
            if input.valueType.dimensions.nonEmpty && input.valueType.kind != ScalarKind.Real then
              invalidValue("only Real procedural arrays are supported", input.rendered)
            input.reads.foreach(reference)
            val nested = input.scalarSources.map: leaf =>
              if leaf.valueType.dimensions.nonEmpty ||
                !leaf.source.exists(_.isInstanceOf[ShapedSource.View])
              then
                invalidValue(
                  "nested scalar source must retain an actual scalar view",
                  leaf.rendered
                )
              value(leaf, depth + 1)
            val tokens = input.scalarSources.map(_.rendered)
            if tokens.distinct.size != tokens.size then
              invalidValue("duplicate retained scalar source identity", input.rendered)
            val used = scalarLeafOccurrences(input.rendered, tokens)
            if used.toSet != tokens.toSet then
              invalidValue("unused retained scalar source attachment", input.rendered)
            val sourceKey
                : Option[(String, Int, Option[StorageProjection], Option[StorageReference])] =
              input.source.map:
                case ShapedSource.Fill(scalar) =>
                  sourceIdentity(input)
                  val child = value(scalar, depth + 1)
                  if input.valueType.dimensions.isEmpty || scalar.valueType.dimensions.nonEmpty ||
                    input.valueType.kind != ScalarKind.Real || scalar.valueType.kind != ScalarKind.Real ||
                    input.valueType.dimension != scalar.valueType.dimension || input.scalarSources.nonEmpty ||
                    input.reads.exists(read => !scalar.allReads.contains(read))
                  then
                    invalidValue(
                      "array fill must preserve its actual scalar Real source and units",
                      input.rendered
                    )
                  ("fill", child, None, None)
                case ShapedSource.Read(source) =>
                  reference(source)
                  if source.valueType != input.valueType || input.rendered != source.identity ||
                    input.scalarSources.nonEmpty || input.reads.exists(_ != source)
                  then
                    invalidValue(
                      "procedural snapshot has a forged value type or source identity",
                      input.rendered
                    )
                  ("read", 0, None, Some(source))
                case ShapedSource.View(source, step) =>
                  sourceIdentity(input)
                  val child = value(source, depth + 1)
                  val resultPath = step match
                    case index: KernelShapeIndexSnapshot => index.path
                    case view: KernelShapeViewSnapshot => view.path
                  if resultPath != input.rendered then
                    invalidValue(
                      "immutable procedural view has a forged result identity",
                      input.rendered
                    )
                  if source.valueType.dimensions.isEmpty || projection(
                      step,
                      source.valueType.dimensions,
                      source.rendered
                    ) != input.valueType.dimensions ||
                    source.valueType.kind != input.valueType.kind ||
                    source.valueType.dimension != input.valueType.dimension || input.scalarSources.nonEmpty ||
                    input.reads.exists(read => !source.allReads.contains(read))
                  then
                    invalidValue(
                      "immutable procedural view has a forged value type or source",
                      input.rendered
                    )
                  ("view", child, Some(step), None)
            if input.source.isEmpty then
              if input.valueType.dimensions.nonEmpty then
                invalidValue("shaped value is missing its retained source", input.rendered)
              val expected = tokens.toSet ++ input.reads.map(_.identity)
              val opaque = scalarLeafOccurrences(input.rendered, expressionIdentities.toVector)
              if opaque.exists(token => !expected.contains(token)) then
                invalidValue(
                  "scalar expression is missing a retained source attachment",
                  input.rendered
                )
              val nestedReads = input.scalarSources.flatMap(_.allReads).toSet
              val directReads = input.reads.filterNot(nestedReads.contains).map(_.identity).toSet
              if scalarLeafOccurrences(input.rendered, directReads.toVector).toSet != directReads
              then
                invalidValue(
                  "scalar expression contains unused mutable read bindings",
                  input.rendered
                )
            val key = ValueKey(input.rendered, input.valueType, input.reads, sourceKey, nested)
            val identity = valueKeys.getOrElseUpdate(key, valueKeys.size + 1)
            if input.source.exists {
                case _: ShapedSource.Read => false
                case _ => true
              }
            then
              sourceIdentities.get(input.rendered).foreach: prior =>
                if prior != identity then
                  invalidValue("conflicting retained source identity", input.rendered)
              sourceIdentities.update(input.rendered, identity)
            val children = input.scalarSources ++ input.source.toVector.flatMap {
              case ShapedSource.Fill(scalar) => Vector(scalar)
              case ShapedSource.View(source, _) => Vector(source)
              case _: ShapedSource.Read => Vector.empty
            }
            val height = children.foldLeft(0)((maximum, child) =>
              maximum.max(valueHeights.get(child).intValue + 1)
            )
            valueHeights.put(input, height)
            valueStates.put(input, identity)
            identity
        def controlReferences(
            reads: Set[String],
            references: Vector[StorageReference],
            path: String
        ): Unit =
          references.foreach: selected =>
            reference(selected)
            if selected.valueType.dimensions.nonEmpty then
              fail("NODAL-BRIDGE-049", "control storage reference must be scalar", Some(path))
          val identities = references.map(_.identity)
          val hasSelection = references.exists(_.isInstanceOf[StorageSelection])
          if identities.distinct.size != identities.size ||
            (hasSelection && reads != identities.toSet) ||
            (!hasSelection && reads.exists(identity => !variables.contains(identity)))
          then
            fail(
              "NODAL-BRIDGE-049",
              "control storage reference inventory is incomplete or ambiguous",
              Some(path)
            )
        def scalarControl(input: Value, path: String): LiveScalarProgram =
          val _ = value(input)
          if input.valueType.dimensions.nonEmpty then
            invalidValue("control value must have a scalar result", path)
          val derived = try deriveLiveScalars(Vector(input))
          catch
            case error: IllegalArgumentException =>
              invalidValue(error.getMessage, path)
          val reads = derived.roots.head.allReads.distinct
          controlReferences(reads.map(_.identity).toSet, reads, path)
          derived
        def eventReferences(event: AnalogEventRuntime.Expression, path: String): Unit =
          def arguments(event: AnalogEventRuntime.Expression, path: String): Vector[Value] =
            val direct = event.arguments.map: argument =>
              val _ = scalarControl(argument.value, s"$path.argument_${argument.slot}")
              argument.value
            direct ++ event.alternatives.zipWithIndex.flatMap((alternative, index) =>
              arguments(alternative, s"$path.alternative_$index")
            )
          // Validate every retained original before deriving a whole-consumer DAG or estimating
          // expansion. This preserves invalid ownership/index/effect diagnostics ahead of erasure.
          val originals = arguments(event, path)
          val resource = deriveLiveScalars(originals).eventResource(path)
          try
            requireEventResource(resource)
            val total = eventResources.getOrElse(program.owner, EventResource(0L, 1024L)) + resource
            requireEventResource(total, module = true)
            eventResources.update(program.owner, total)
          catch
            case error: IllegalArgumentException => invalidValue(error.getMessage, path)
        program.variables.foreach: record =>
          dimensions(record.variable.valueType.dimensions, record.variable.identity)
          record.initializer.foreach: initializer =>
            val _ = value(initializer)
            if record.variable.declarationScope == Vector("procedure") &&
              initializer.valueType.dimensions.isEmpty && initializer.allReads.nonEmpty &&
              (initializer.scalarSources.nonEmpty || initializer.source.exists(
                _.isInstanceOf[ShapedSource.View]
              ))
            then
              invalidValue(
                "persistent scalar initializer cannot retain mutable fill sources",
                record.variable.identity
              )
        program.assignments.foreach: assignment =>
          reference(assignment.target)
          val _ = value(assignment.value)
        program.controlExpressions.foreach: expression =>
          if expression.role == "loop-bound" then
            val _ = scalarControl(expression.value, expression.identity)
          else
            val _ = value(expression.value)
        def block(input: AnalogControlFlowRuntime.Block): Unit = input.statements.foreach:
          case assignment: AnalogControlFlowRuntime.Statement.Assign =>
            assignment.storageTarget.foreach(reference)
          case scope: AnalogControlFlowRuntime.Statement.Scope => block(scope.body)
          case event: AnalogControlFlowRuntime.Statement.EventControl =>
            eventReferences(event.event, s"${event.identity}.event")
            block(event.body)
          case branch: AnalogControlFlowRuntime.Statement.IfThenElse =>
            branch.branches.zipWithIndex.foreach: (arm, index) =>
              if arm.condition.valueType.dimensions.nonEmpty then
                fail(
                  "NODAL-BRIDGE-049",
                  "condition must have scalar value type",
                  Some(branch.identity)
                )
              if arm.condition.sourceValue.isEmpty &&
                scalarLeafOccurrences(arm.condition.rendered, expressionIdentities.toVector)
                  .exists(token => !arm.condition.reads.contains(token))
              then invalidValue("condition is missing its retained source value", branch.identity)
              arm.condition.sourceValue.foreach: retained =>
                val path = s"${branch.identity}.condition_$index"
                if retained.rendered != arm.condition.rendered || retained.valueType != arm.condition.valueType ||
                  retained.allReads.distinct != arm.condition.selectedReads ||
                  retained.allReads.map(_.identity).toSet != arm.condition.reads
                then invalidValue("condition fields disagree with retained source value", path)
                val _ = scalarControl(retained, path)
              controlReferences(
                arm.condition.reads,
                arm.condition.selectedReads,
                s"${branch.identity}.condition_$index"
              )
              block(arm.body)
            branch.otherwise.foreach(block)
          case selection: AnalogControlFlowRuntime.Statement.CaseStatement =>
            if selection.selector.sourceValue.isEmpty &&
              scalarLeafOccurrences(selection.selector.rendered, expressionIdentities.toVector)
                .exists(token => !selection.selector.reads.contains(token))
            then invalidValue("selector is missing its retained source value", selection.identity)
            selection.selector.sourceValue.foreach: retained =>
              val path = s"${selection.identity}.selector"
              if retained.rendered != selection.selector.rendered ||
                retained.valueType.kind != selection.selector.kind ||
                retained.valueType.dimension != selection.selector.dimension ||
                retained.allReads.distinct != selection.selector.selectedReads ||
                retained.allReads.map(_.identity).toSet != selection.selector.reads
              then invalidValue("selector fields disagree with retained source value", path)
              val _ = scalarControl(retained, path)
            controlReferences(
              selection.selector.reads,
              selection.selector.selectedReads,
              s"${selection.identity}.selector"
            )
            selection.arms.foreach(arm => block(arm.body))
            selection.default.foreach(block)
          case loop: AnalogControlFlowRuntime.Statement.Loop =>
            if loop.boundValueType.dimensions.nonEmpty then
              fail(
                "NODAL-BRIDGE-049",
                "loop bound must have scalar value type",
                Some(loop.identity)
              )
            controlReferences(
              loop.boundReads,
              loop.boundSelectedReads,
              s"${loop.identity}.loop-bound"
            )
            block(loop.body)
          case _ => ()
        program.controlFlow.foreach(control => block(control.root))

    private def emitProceduralStatic(
        token: String,
        module: KernelModuleSnapshot,
        parameterSymbols: Map[String, String],
        declarationsByPath: Map[String, KernelDeclarationSnapshot],
        expressionsByPath: Map[String, KernelParameterExpressionSnapshot],
        rendered: mutable.ArrayBuffer[String],
        staticValues: mutable.LinkedHashMap[String, (String, String)],
        allocate: () => String
    ): (String, String) = token.toLongOption match
      case Some(literal) => staticValues.getOrElseUpdate(
          s"literal:$literal", {
            val result = allocate()
            rendered += operation(
              "nodal.const_literal",
              results = Vector(result),
              resultTypes = Vector("i64"),
              attributes = Vector(
                "value" -> s"$literal : i64",
                "spelling" -> quoted(literal.toString),
                "metadata" -> "{use = \"procedural_shape\"}"
              ),
              semanticPath = module.path
            )
            result -> "i64"
          }
        )
      case None =>
        val path = shapeDimensionStaticPath(module, token).getOrElse(
          fail("NODAL-BRIDGE-049", "procedural shape has no owned static identity", Some(token))
        )
        val result = emitStaticValue(
          path,
          parameterSymbols,
          declarationsByPath,
          expressionsByPath,
          rendered,
          staticValues,
          allocate,
          requireStructural = true
        )
        if result._2 != "i64" then
          fail("NODAL-BRIDGE-049", "procedural shape value must be Integer", Some(token))
        result

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
                    case "max" =>
                      operands.size == 2 && dataType == "i64" && operands.forall(_._2 == "i64")
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
        parameterSymbols: Map[String, String],
        declarationsByPath: Map[String, KernelDeclarationSnapshot],
        expressionsByPath: Map[String, KernelParameterExpressionSnapshot],
        staticValues: mutable.LinkedHashMap[String, (String, String)],
        allocateStaticValue: () => String,
        staticBody: Option[mutable.ArrayBuffer[String]] = None
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
          if targetType.startsWith("!nodal.enum<") then
            Some(digitalValues.literalAttribute(
              value,
              target.dataType.get,
              resolveOwningModule(instance.path),
              instance.path,
              (text, _) => digitalScalarType(text, target.path)
            ))
          else if parameterSymbols.contains(value) || expressionsByPath.contains(value) then
            symbolicBindings += ((name, value, targetType))
            None
          else if isDigitalParameterType(targetType) then
            Some(digitalValues.literalAttribute(
              value,
              target.dataType.get,
              resolveOwningModule(instance.path),
              instance.path,
              (text, _) => digitalScalarType(text, target.path)
            ))
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
          ) ++ scopeAttributes(instance.generatedScope),
          semanticPath = instance.path
        )
      )
      symbolicBindings.zipWithIndex.foreach: (binding, bindingIndex) =>
        val (name, sourcePath, dataType) = binding
        val (value, sourceType) = emitStaticValue(
          sourcePath,
          parameterSymbols,
          declarationsByPath,
          expressionsByPath,
          staticBody.getOrElse(rendered),
          staticValues,
          allocateStaticValue,
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
          ) ++ scopeAttributes(instance.generatedScope),
          semanticPath = instance.path
        )
      rendered.toVector

    private def interfaceEntries(
        module: KernelModuleSnapshot
    ): Vector[InterfaceAbiEntry] =
      interfaceEntriesByModule.getOrElse(module.path, Vector.empty)

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
        terminalKinds.contains(declaration.kind) && declaration.conservativeDimensions.isEmpty
      ).sortBy(_.path)

    private def isConservativeArray(declaration: KernelDeclarationSnapshot): Boolean =
      declaration.kind == "analog-node-array" ||
        (Set("analog-input", "analog-output", "analog-inout").contains(declaration.kind) &&
          declaration.conservativeDimensions.nonEmpty)

    private def boundaryDirection(declaration: KernelDeclarationSnapshot): String =
      declaration.kind match
        case "analog-input" => "input"
        case "analog-output" => "output"
        case "analog-inout" | "conservative-terminal" => "inout"
        case _ =>
          fail(
            "NODAL-BRIDGE-027",
            "child endpoint is not a boundary terminal",
            Some(declaration.path)
          )

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
        allocateGeneratedStaticValue: () => String,
        accessBranches: mutable.LinkedHashMap[String, (String, String)],
        enclosingValues: Map[String, (String, String)],
        renderTopologyScope: (
            KernelGeneratedRegionSnapshot,
            mutable.ArrayBuffer[String],
            mutable.LinkedHashMap[String, (String, String)]
        ) => Unit
    ): String =
      val nested = mutable.ArrayBuffer.empty[String]
      val generatedValues = mutable.LinkedHashMap.from(enclosingValues)
      val inductionArgument =
        Option.when(snapshot.shapeIndices.exists(_.indices.contains(region.induction)))(
          "%" + stableLocalSymbol("generated_index", region.path)
        )
      inductionArgument.foreach(value => generatedValues.update(region.induction, value -> "i64"))
      region.declarations.sorted.foreach: declarationPath =>
        val declaration = declarationsByPath.getOrElse(
          declarationPath,
          fail(
            "NODAL-BRIDGE-043",
            "generated declaration is absent from its owning Module",
            Some(region.path)
          )
        )
        declaration.kind match
          case "analog-node-array" =>
            val result = "%" + stableLocalSymbol("generated_node_array", declaration.path)
            nested += renderConservativeArray(
              declaration,
              result,
              Some(KernelGeneratedScope(region.path, region.induction))
            )
            generatedValues.update(declaration.path, result -> nodeArrayType(declaration))
          case "analog-node" =>
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
                    "allow_floating" -> boolean(true),
                    "declaration_kind" -> quoted(declaration.kind),
                    "generated_owner" -> quoted(region.path),
                    "induction" -> quoted(region.induction)
                  )
                )
              ),
              semanticPath = declaration.path
            )
            generatedValues.update(declaration.path, result -> resultType)
          case "variable"
              if snapshot.analogProcedural.exists(program =>
                program.owner == module.path &&
                  program.generatedOwner.contains(region.path) &&
                  program.generatedInduction.contains(region.induction) &&
                  program.variables.exists(_.authoredPath.contains(declaration.path))
              ) => ()
          case _ =>
            fail(
              "NODAL-BRIDGE-043",
              "generated declaration kind '" + declaration.kind +
                "' is not enabled by this F-043 stage",
              Some(declaration.path)
            )

      renderTopologyScope(region, nested, generatedValues)

      nested ++= AnalogProceduralMlir.renderGenerated(
        snapshot,
        module.path,
        region.path,
        token =>
          emitProceduralStatic(
            token,
            module,
            parameterSymbols,
            declarationsByPath,
            expressionsByPath,
            generatedStatic,
            generatedStaticValues,
            allocateGeneratedStaticValue
          )
      )

      val ownedAnalogRegions = snapshot.analogRegions.filter(_.generatedOwner.contains(region.path))
      val generatedAccessExpressions = ownedAnalogRegions.flatMap(_.expressions).filter(
        expression =>
          (expression.operation == "potential_access" || expression.operation == "flow_access") &&
            !accessBranches.contains(expression.path)
      )
      val generatedBranchKeys = generatedAccessExpressions.map: expression =>
        if expression.operands.size != 2 then
          fail(
            "NODAL-RC-BRANCH-001",
            "Increment 25 requires a two-terminal V(p,n) or I(p,n) access",
            Some(expression.path)
          )
        expression.operands(0) -> expression.operands(1)
      val generatedBranchByKey = generatedBranchKeys.distinct.sorted.map: key =>
        val (positivePath, negativePath) = key
        val positive = generatedValues.getOrElse(
          positivePath,
          fail("NODAL-RC-BRANCH-002", "positive terminal is unavailable", Some(positivePath))
        )
        val negative = generatedValues.getOrElse(
          negativePath,
          fail("NODAL-RC-BRANCH-002", "negative terminal is unavailable", Some(negativePath))
        )
        if positive._2 != negative._2 then
          fail(
            "NODAL-RC-BRANCH-003",
            "RC access terminals have incompatible disciplines '" + positive._2 +
              "' and '" + negative._2 + "'",
            Some(positivePath)
          )
        val result = "%" + stableLocalSymbol(
          "generated_branch",
          s"${region.path}.$positivePath.$negativePath"
        )
        val resultType =
          s"""!nodal.branch<${quoted(terminalDiscipline(positive._2, positivePath))}>"""
        nested += operation(
          "nodal.branch",
          results = Vector(result),
          operands = Vector(positive._1, negative._1),
          operandTypes = Vector(positive._2, negative._2),
          resultTypes = Vector(resultType),
          attributes = Vector(
            "metadata" -> bridgeMetadata(
              s"$positivePath->$negativePath",
              Vector(
                "vertical_slice" -> quoted("rc"),
                "generated_owner" -> quoted(region.path),
                "generated_induction" -> quoted(region.induction)
              )
            )
          ),
          semanticPath = positivePath
        )
        key -> (result -> resultType)
      .toMap
      generatedAccessExpressions.foreach: expression =>
        accessBranches.update(
          expression.path,
          generatedBranchByKey(expression.operands(0) -> expression.operands(1))
        )

      ownedAnalogRegions.zipWithIndex.foreach: (analog, index) =>
        if !analog.generatedInduction.contains(region.induction) then
          fail(
            "NODAL-BRIDGE-043",
            "generated continuous region ownership or induction is invalid",
            Some(analog.path)
          )
        val operators = snapshot.continuousOperators.filter(operator =>
          analog.expressions.exists(_.path == operator.path)
        )
        val waveforms = snapshot.waveformOperators.filter(operator =>
          analog.expressions.exists(_.path == operator.path)
        )
        val transfers = snapshot.transferOperators.filter(operator =>
          analog.expressions.exists(_.path == operator.path)
        )
        val noise = snapshot.noiseOperators.filter(operator =>
          analog.expressions.exists(_.path == operator.path)
        )
        if (operators.isEmpty && waveforms.isEmpty && transfers.isEmpty && noise.isEmpty &&
            analog.contributions.isEmpty && !analog.expressions.exists(_.operation.startsWith(
              AnalogContextContract.Prefix
            ))) ||
          operators.exists(operator =>
            !operator.generatedOwner.contains(region.path) ||
              !operator.generatedInduction.contains(region.induction)
          ) ||
          waveforms.exists(operator =>
            !operator.generatedOwner.contains(region.path) ||
              !operator.generatedInduction.contains(region.induction)
          ) ||
          transfers.exists(operator =>
            !operator.generatedOwner.contains(region.path) ||
              !operator.generatedInduction.contains(region.induction)
          ) ||
          noise.exists(operator =>
            !operator.generatedOwner.contains(region.path) ||
              !operator.generatedInduction.contains(region.induction)
          ) || analog.contributions.exists(contribution =>
            !contribution.generatedOwner.contains(region.path) ||
              !contribution.generatedInduction.contains(region.induction)
          )
        then
          fail(
            "NODAL-BRIDGE-043",
            "generated continuous operator ownership is incomplete",
            Some(analog.path)
          )
        nested += renderAnalogRegion(
          analog,
          index,
          declarationsByPath,
          parameterSymbols,
          accessBranches
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
            allocateGeneratedStaticValue,
            accessBranches,
            generatedValues.toMap,
            renderTopologyScope
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
        regions = Vector(inductionArgument.map(value => s"^bb0($value: i64):\n").getOrElse(
          if nested.isEmpty then "^bb0:" else ""
        ) + nested.mkString("\n")),
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
      snapshot.shapeIndices.find(_.path == path).map(_.owner)
        .orElse(snapshot.shapeViews.find(_.path == path).map(_.owner))
        .orElse(snapshot.instanceArrayRoots.find(_.path == path).map(_.owner))
        .getOrElse(resolve(path))

    private def renderDigitalDeclarationValue(
        declaration: KernelDeclarationSnapshot,
        result: String,
        dataType: String,
        initial: Option[(String, String)]
    ): Vector[String] =
      declaration.kind match
        case "digital-inout" => interfaceValues.renderDeclaration(
            declaration,
            result,
            dataType,
            bridgeMetadata(declaration.path, Vector.empty),
            location(declaration.path)
          )
        case "wire" | "register" =>
          val module = modulesByPath(resolveOwningModule(declaration.path))
          Vector(DigitalStorageMlir.renderValue(
            module,
            declaration,
            result,
            dataType,
            initial,
            stableLocalSymbol,
            bridgeMetadata(declaration.path, Vector.empty),
            location(declaration.path),
            DigitalStorageMlir.initializer(declaration).forall: input =>
              exactDigitalValueType(
                digitalValues.expressionType(input),
                declaration.dataType.getOrElse(
                  fail(
                    "NODAL-BRIDGE-055",
                    "storage declaration has no exact type",
                    Some(declaration.path)
                  )
                ),
                declaration.path
              )
          ))
        case kind =>
          val (name, key, category) = kind match
            case "input" | "output" => ("nodal.port_value", "port", "port")
            case "parameter" => ("nodal.const_parameter_ref", "parameter", "parameter")
            case _ => fail(
                "NODAL-BRIDGE-054",
                "digital operand storage is outside the implemented SSA profile",
                Some(declaration.path)
              )
          Vector(operation(
            name,
            results = Vector(result),
            resultTypes = Vector(dataType),
            attributes = Vector(
              key -> symbolReference(stableLocalSymbol(category, declaration.name)),
              "metadata" -> bridgeMetadata(declaration.path, Vector.empty)
            ),
            semanticPath = declaration.path
          ))

    private def digitalScalarType(text: String, path: String): Option[DigitalValueMlir.ScalarType] =
      text match
        case WidthType(kind, width) =>
          val bounds = width.toLongOption match
            case Some(value) if width == value.toString => IterationDomain.Bounds(value, value)
            case _ =>
              val module = modulesByPath(resolveOwningModule(path))
              val reference = shapeDimensionStaticPath(module, width).getOrElse(
                fail("NODAL-BRIDGE-054", "digital width has no exact owned identity", Some(path))
              )
              shapeStaticBounds(
                module,
                reference,
                path,
                "NODAL-BRIDGE-054",
                "digital width",
                requiredEffect = "width"
              )
          if bounds.lower <= 0L || bounds.upper < bounds.lower then
            fail(
              "NODAL-BRIDGE-054",
              "digital width is not positive for every legal parameter value",
              Some(path)
            )
          Some(DigitalValueMlir.ScalarType(kind, width, bounds.lower, bounds.upper))
        case _ => None

    private def digitalWidthRelation(
        source: DigitalValueMlir.ScalarType,
        result: DigitalValueMlir.ScalarType,
        offset: Int,
        path: String
    ): Boolean =
      val owner = resolveOwningModule(path)
      try
        if offset == 0 then
          StructuralParameterBinding.sameInteger(snapshot, owner, source.width, result.width)
        else
          StructuralParameterBinding.sameIntegerOperation(
            snapshot,
            owner,
            result.width,
            "analog_add",
            Vector(source.width, offset.toString)
          )
      catch
        case error: IllegalArgumentException =>
          fail("NODAL-BRIDGE-054", error.getMessage, Some(path))

    private def digitalWidthFormula(
        result: DigitalValueMlir.ScalarType,
        operation: String,
        operands: Vector[DigitalValueMlir.ScalarType],
        increment: Long,
        path: String
    ): Boolean =
      try
        StructuralParameterBinding.sameIntegerOperation(
          snapshot,
          resolveOwningModule(path),
          result.width,
          operation,
          operands.map(_.width),
          increment
        )
      catch
        case error: IllegalArgumentException =>
          fail("NODAL-BRIDGE-055", error.getMessage, Some(path))

    private def exactDigitalValueType(left: String, right: String, path: String): Boolean =
      // Validate through the existing type owner before comparing semantic dimensions.
      val _ = parseTypeChecked(left, path, 0, Array(65536), storageContext = true)
      val _ = parseTypeChecked(right, path, 0, Array(65536), storageContext = true)
      val owner = resolveOwningModule(path)
      val pending = mutable.ArrayDeque((left, right, 0))
      val visited = mutable.HashSet.empty[(String, String)]
      var equal = true
      def sameDimension(a: String, b: String): Boolean =
        try StructuralParameterBinding.sameInteger(snapshot, owner, a, b)
        catch
          case error: IllegalArgumentException =>
            fail("NODAL-BRIDGE-055", error.getMessage, Some(path))
      def structParts(text: String): (String, Vector[(String, String)]) =
        val inside = text.drop(7).dropRight(1)
        val open = inside.indexOf('{')
        val fields = splitStructFields(inside.drop(open + 1).dropRight(1), path).map: field =>
          val colon = field.indexOf(':')
          field.take(colon) -> field.drop(colon + 1)
        inside.take(open) -> fields
      while pending.nonEmpty && equal do
        val (a, b, depth) = pending.removeLast()
        if depth > 128 || visited.size >= 65536 then
          fail("NODAL-BRIDGE-055", "digital type comparison exceeds structural budget", Some(path))
        if visited.add(a -> b) && a != b then
          (a, b) match
            case (WidthType(aKind, aWidth), WidthType(bKind, bWidth)) =>
              equal = aKind == bKind && sameDimension(aWidth, bWidth)
            case _ if a.startsWith("Vec(") && b.startsWith("Vec(") =>
              val (aElement, aDimensions) = vecTypeParts(a, path)
              val (bElement, bDimensions) = vecTypeParts(b, path)
              equal = aDimensions.size == bDimensions.size &&
                aDimensions.zip(bDimensions).forall((x, y) => sameDimension(x, y))
              pending.append((aElement, bElement, depth + 1))
            case _ if a.startsWith("Struct(") && b.startsWith("Struct(") =>
              val (aName, aFields) = structParts(a)
              val (bName, bFields) = structParts(b)
              equal = aName == bName && aFields.map(_._1) == bFields.map(_._1)
              aFields.zip(bFields).foreach: (x, y) =>
                pending.append((x._2, y._2, depth + 1))
            case _ if a.startsWith("Enum(") && b.startsWith("Enum(") =>
              equal = enumValues.parseType(a, path) == enumValues.parseType(b, path)
            case _ => equal = false
      equal

    // Inputs are already canonical native types produced by the shared type renderer.
    private def containsBoundaryCapability(nativeType: String): Boolean =
      nativeType.contains("!nodal.capability<")

    private def validateInterfaceCapabilitySource(
        path: String,
        owner: String,
        targetDomain: String,
        nativeType: String
    ): Unit =
      if containsBoundaryCapability(nativeType) then
        val module = modulesByPath(owner)
        val source = digitalValues.boundaryRoot(path, owner)
        val domain = module.domains.find(value =>
          value.path == targetDomain || value.binding.contains(targetDomain)
        ).map(_.name)
        if source.kind != "input" || !domain.contains(localDomainName(module, source)) then
          fail(
            "NODAL-BRIDGE-054-INTERFACE",
            "clock/reset member drive requires a same-domain actual input boundary root",
            Some(path)
          )

    private def interfaceNativeType(text: String, path: String): String = text match
      case value if value.startsWith("Terminal(") =>
        val member = interfaceMembersByPath.getOrElse(
          path,
          fail(
            "NODAL-BRIDGE-054-INTERFACE",
            "Interface terminal requires its exact captured conservative contract",
            Some(path)
          )
        )
        val discipline = conservativeDiscipline(member.analogAttributes.toMap, path)
        if value != "Terminal(electrical)" || discipline != "electrical" then
          fail(
            "NODAL-BRIDGE-054-INTERFACE",
            "named Interface discipline requires an existing native analog definition/import owner",
            Some(path)
          )
        s"!nodal.terminal<${quoted(discipline)}>"
      case value if value.startsWith("AnalogSignal(") && value.endsWith(")") =>
        try
          val _ = AnalogUserFunctionRuntime.dimension(value.drop(13).dropRight(1))
        catch
          case error: ConstructionException =>
            fail("NODAL-BRIDGE-054-INTERFACE", error.getMessage, Some(path))
        "f64"
      case _
          if interfaceMembersByPath.get(path).exists(member =>
            Set("valid", "stream").contains(member.protocol)
          ) => parseTypeChecked(text, path, 0, Array(65536), storageContext = true)
      case _ => parseType(text, path)

    private def parseType(text: String, path: String): String =
      parseTypeChecked(text, path, 0, Array(65536), storageDeclarationPaths.contains(path))

    private def parseTypeChecked(
        text: String,
        path: String,
        depth: Int,
        remaining: Array[Int],
        storageContext: Boolean
    ): String =
      remaining(0) -= 1
      if depth > 128 || remaining(0) < 0 || text.length > 1048576 then
        fail("NODAL-BRIDGE-054", "digital type exceeds the supported structural budget", Some(path))
      text match
        case "Bool" => "!nodal.bits<1>"
        case "Clock" | "Reset" =>
          if storageContext then
            fail(
              "NODAL-BRIDGE-054",
              "clock/reset capabilities are not digital storage fields",
              Some(path)
            )
          s"!nodal.capability<${quoted(text.toLowerCase(java.util.Locale.ROOT))}>"
        case "Integer" => "i64"
        case "Real" => "f64"
        case value if value.startsWith("Enum(") => enumValues.parseType(value, path)
        case WidthType(kind, widthText) =>
          val label = kind.toLowerCase(java.util.Locale.ROOT)
          widthText.toLongOption match
            case Some(width) if width > 0L && widthText == width.toString =>
              s"!nodal.$label<$width>"
            case None
                if widthText.length <= 4096 && validShapeDimension(widthText, path, "width") =>
              s"!nodal.digital<${quoted(label)}, ${quoted(widthText)}>"
            case _ => fail(
                "NODAL-BRIDGE-016",
                s"invalid or unproved digital width '$widthText'",
                Some(path)
              )
        case value if value.startsWith("Struct(") && value.endsWith(")") =>
          val inside = value.drop(7).dropRight(1)
          val open = inside.indexOf('{')
          if open <= 0 || !inside.endsWith("}") then
            fail("NODAL-BRIDGE-054", "invalid Struct type representation", Some(path))
          val name = inside.take(open)
          def validName(value: String): Boolean =
            value.length <= 1024 && value.matches("[A-Za-z_][A-Za-z0-9_]*")
          if !validName(name) then fail("NODAL-BRIDGE-054", "invalid Struct identity", Some(path))
          val fields = splitStructFields(inside.drop(open + 1).dropRight(1), path)
          val names = mutable.HashSet.empty[String]
          val rendered = fields.map: field =>
            val colon = field.indexOf(':')
            if colon <= 0 then
              fail("NODAL-BRIDGE-054", "Struct field has no typed identity", Some(path))
            val fieldName = field.take(colon)
            if !validName(fieldName) || !names.add(fieldName) then
              fail("NODAL-BRIDGE-054", "Struct fields require unique portable names", Some(path))
            val fieldType =
              parseTypeChecked(field.drop(colon + 1), path, depth + 1, remaining, true)
            s"{name = ${quoted(fieldName)}, type = $fieldType}"
          s"!nodal.struct<${quoted(name)}, [${rendered.mkString(", ")}]>"
        case value if value.startsWith("Vec(") && value.endsWith(")") =>
          val (elementText, dimensions) = vecTypeParts(value, path)
          val element = parseTypeChecked(elementText, path, depth + 1, remaining, storageContext)
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

    private def splitStructFields(text: String, path: String): Vector[String] =
      val fields = mutable.ArrayBuffer.empty[String]
      val delimiters = mutable.ArrayBuffer.empty[Char]
      var start = 0
      text.indices.foreach: index =>
        text(index) match
          case '(' | '{' => delimiters += text(index)
          case ')' | '}' =>
            val expected = if text(index) == ')' then '(' else '{'
            if delimiters.lastOption != Some(expected) then
              fail("NODAL-BRIDGE-054", "unbalanced Struct type", Some(path))
            delimiters.remove(delimiters.size - 1)
          case ',' if delimiters.isEmpty =>
            fields += text.substring(start, index)
            start = index + 1
          case _ => ()
      if delimiters.nonEmpty then fail("NODAL-BRIDGE-054", "unbalanced Struct type", Some(path))
      fields += text.drop(start)
      if fields.size > 65536 || fields.exists(_.isEmpty) then
        fail("NODAL-BRIDGE-054", "Struct requires nonempty bounded fields", Some(path))
      fields.toVector

    private def vecTypeParts(text: String, path: String): (String, Vector[String]) =
      if !text.startsWith("Vec(") || !text.endsWith(")") then
        fail("NODAL-BRIDGE-044", s"shape-index input is not Vec type '$text'", Some(path))
      val inside = text.drop(4).dropRight(1)
      val split = inside.lastIndexOf(';')
      if split <= 0 || split == inside.length - 1 then
        fail("NODAL-BRIDGE-017", s"invalid Vec type '$text'", Some(path))
      inside.take(split) -> splitShapeDimensions(inside.drop(split + 1), path)

    private def splitShapeDimensions(text: String, path: String): Vector[String] =
      val staticIdentities = modulesByPath
        .get(resolveOwningModule(path))
        .toVector
        .flatMap: module =>
          module.declarations.map(_.name) ++
            parameterExpressionsByOwner.getOrElse(module.path, Map.empty).keys
        .distinct
        .sortBy(identity => -identity.length)
      val dimensions = Vector.newBuilder[String]
      var offset = 0
      while offset < text.length do
        val identity = staticIdentities.find: candidate =>
          text.startsWith(candidate, offset) &&
            (offset + candidate.length == text.length ||
              text.charAt(offset + candidate.length) == 'x')
        identity match
          case Some(candidate) =>
            dimensions += candidate
            offset += candidate.length
          case None =>
            val separator = text.indexOf('x', offset)
            val end = if separator < 0 then text.length else separator
            dimensions += text.substring(offset, end)
            offset = end
        if offset < text.length then offset += 1
      dimensions.result()

    private def renderConservativeArray(
        declaration: KernelDeclarationSnapshot,
        result: String,
        scope: Option[KernelGeneratedScope]
    ): String =
      val boundary = declaration.kind != "analog-node-array"
      operation(
        if boundary then "nodal.terminal_array" else "nodal.node_array",
        results = Vector(result),
        resultTypes = Vector(nodeArrayType(declaration)),
        attributes = Vector(
          "name" -> quoted(declaration.name),
          "source_path" -> quoted(declaration.path),
          "metadata" -> bridgeMetadata(
            declaration.path,
            Vector(
              "declaration_kind" -> quoted(declaration.kind),
              "storage" -> quoted("structural")
            )
          )
        ) ++ scopeAttributes(scope) ++ Option.when(boundary)(Vector(
          "direction" -> quoted(boundaryDirection(declaration)),
          "flow_orientation" -> quoted("into_component")
        )).toVector.flatten,
        semanticPath = declaration.path
      )

    private def nodeArrayType(declaration: KernelDeclarationSnapshot): String =
      s"!nodal.shaped<${quoted(declaration.conservativeDimensions.mkString(","))}, !nodal.terminal<${quoted("electrical")}>>"

    private def validateNodeArray(
        module: KernelModuleSnapshot,
        declaration: KernelDeclarationSnapshot
    ): Unit =
      def invalid(message: String): Nothing =
        fail("NODAL-BRIDGE-047", message, Some(declaration.path))
      val attributes = declaration.attributes.toMap
      val declarationScope =
        snapshot.generatedRegions.find(_.declarations.contains(declaration.path))
          .map(region => KernelGeneratedScope(region.path, region.induction))
      validateScope(declaration.generatedScope, module.path, declaration.path)
      if declaration.generatedScope != declarationScope then
        fail(
          "NODAL-BRIDGE-043",
          "conservative array declaration scope disagrees with its generated-region membership",
          Some(declaration.path)
        )
      if declaration.conservativeDimensions.isEmpty || declaration.dataType.nonEmpty || declaration.domain.nonEmpty ||
        attributes.get("discipline") != Some("electrical") ||
        attributes.get("potential_nature") != Some("voltage") ||
        attributes.get("flow_nature") != Some("current") ||
        declaration.name.isEmpty || declaration.name.trim != declaration.name ||
        declaration.path != s"${module.path}.${declaration.name}" || !sourceByPath.contains(
          declaration.path
        )
      then
        invalid(
          "node array requires an owned source-mapped Electrical declaration and nonempty shape"
        )
      var cardinality = BigInt(1)
      declaration.conservativeDimensions.foreach: dimension =>
        val static = dimension.toLongOption.map(_.toString).getOrElse(
          shapeDimensionStaticPath(
            module,
            dimension
          ).getOrElse(invalid("node array dimension has no owned static identity"))
        )
        val expected = dimension.toLongOption.map(_.toString).getOrElse(
          module.declarations.find(_.path == static).map(_.name).getOrElse:
            parameterExpressionsByOwner.getOrElse(module.path, Map.empty)
              .get(static).flatMap(_.literal).getOrElse(static)
        )
        if dimension != expected then invalid("node array dimension spelling is noncanonical")
        val bounds = shapeStaticBounds(
          module,
          static,
          declaration.path,
          "NODAL-BRIDGE-047",
          "node-array dimension"
        )
        if bounds.lower <= 0L then invalid("node array dimension requires a positive minimum")
        cardinality *= BigInt(bounds.upper)
        if cardinality > BigInt(Long.MaxValue) then
          invalid("node array element count exceeds signed 64-bit bounds")

    private def validateNodeSelection(
        module: KernelModuleSnapshot,
        dimensions: Vector[String],
        index: KernelShapeIndexSnapshot,
        usedSources: mutable.HashSet[String]
    ): Unit =
      def invalid(message: String): Nothing = fail("NODAL-BRIDGE-048", message, Some(index.path))
      val expressions = parameterExpressionsByOwner.getOrElse(module.path, Map.empty)
      def token(value: String): String =
        value.toLongOption match
          case Some(literal) =>
            if value != literal.toString then invalid("node-array index literal is noncanonical")
            s"literal:$literal"
          case None if generatedByInduction.contains(value) =>
            if !visibleInduction(index.generatedScope, value) then
              invalid("node-array selector induction is outside its active scope")
            s"induction:$value"
          case None =>
            val path = shapeDimensionStaticPath(
              module,
              value
            ).getOrElse(invalid("node-array selector has no owned static identity"))
            if value != path then invalid("node-array selector must carry its full static identity")
            module.declarations.find(_.path == path) match
              case Some(_) => s"parameter:$path"
              case None =>
                val expression = expressions(path)
                if expression.literal.nonEmpty then
                  invalid("literal node-array selectors must be normalized to their numeric value")
                s"expression:$path"
      if index.indices.size != dimensions.size || index.resultDimensions.nonEmpty then
        invalid("node-array selection requires full rank and a scalar terminal result")
      val tokens = index.indices.map(token)
      val scopeSuffix = index.generatedScope.map(value => s".in(${value.owner})").getOrElse("")
      if index.path != s"${index.input}.at(${tokens.mkString(",")})$scopeSuffix" then
        invalid("node-array endpoint identity does not match its canonical selector")
      if index.sourceUses.isEmpty || index.sourceUses != index.sourceUses.distinct.sorted then
        invalid("node-array source uses must be nonempty, unique and ordered")
      index.sourceUses.foreach: source =>
        if source.trim != source || !source.startsWith(
            s"${module.path}."
          ) || source == index.path ||
          !sourceByPath.contains(source) || !usedSources.add(source) ||
          module.declarations.exists(_.path == source) || expressions.contains(source)
        then invalid("node-array selection occurrence has no distinct owned source-map identity")

    private def shapeDimensionMinimum(
        module: KernelModuleSnapshot,
        dimension: String,
        path: String
    ): Long =
      dimension.toLongOption.filter(_ > 0L).getOrElse:
        val staticPath = shapeDimensionStaticPath(module, dimension).getOrElse(
          fail("NODAL-BRIDGE-044", "symbolic shape extent is absent", Some(path))
        )
        val bounds = shapeStaticBounds(
          module,
          staticPath,
          path,
          "NODAL-BRIDGE-044",
          "symbolic shape extent"
        )
        if bounds.lower <= 0L then
          fail("NODAL-BRIDGE-044", "symbolic shape extent has no positive minimum", Some(path))
        bounds.lower

    private def compoundShapeDimensionPaths(text: String, path: String): Vector[String] =
      val expressions = parameterExpressionsByOwner.getOrElse(resolveOwningModule(path), Map.empty)
      text match
        case WidthType(_, width) => Vector(width).filter(expressions.contains)
        case value if value.startsWith("Struct(") && value.endsWith(")") =>
          val inside = value.drop(7).dropRight(1)
          val open = inside.indexOf('{')
          if open <= 0 || !inside.endsWith("}") then
            fail("NODAL-BRIDGE-054", "invalid Struct type representation", Some(path))
          splitStructFields(inside.drop(open + 1).dropRight(1), path).flatMap: field =>
            val colon = field.indexOf(':')
            if colon <= 0 then
              fail("NODAL-BRIDGE-054", "Struct field has no typed identity", Some(path))
            compoundShapeDimensionPaths(field.drop(colon + 1), path)
        case value if value.startsWith("Vec(") && value.endsWith(")") =>
          val (element, dimensions) = vecTypeParts(value, path)
          dimensions.filter(expressions.contains) ++ compoundShapeDimensionPaths(element, path)
        case _ => Vector.empty

    private def shapeDimensionStaticPath(
        module: KernelModuleSnapshot,
        dimension: String
    ): Option[String] =
      module.declarations.find(declaration =>
        declaration.name == dimension || declaration.path == dimension
      ).map(_.path).orElse:
        parameterExpressionsByOwner
          .getOrElse(module.path, Map.empty)
          .get(dimension)
          .filter(expression => expression.owner == module.path && expression.path == dimension)
          .map(_.path)

    private def validateShapeSlice(
        module: KernelModuleSnapshot,
        source: Vector[String],
        view: KernelShapeViewSnapshot
    ): Unit =
      val selection = view.slice.get
      def invalid(message: String): Nothing = fail("NODAL-BRIDGE-046", message, Some(view.path))
      val expressions = parameterExpressionsByOwner.getOrElse(module.path, Map.empty)
      def canonical(token: String): Either[Long, String] =
        if token.isEmpty || token.trim != token then invalid("shape slice identity is noncanonical")
        token.toLongOption match
          case Some(value) =>
            if token != value.toString then invalid("shape slice literal is noncanonical")
            Left(value)
          case None =>
            val path = shapeDimensionStaticPath(module, token).getOrElse(
              invalid("shape slice value has no owned static identity")
            )
            expressions.get(path).flatMap(_.literal) match
              case Some(literal) => Left(literal.toLongOption.getOrElse(
                  invalid("shape slice literal exceeds signed 64-bit bounds")
                ))
              case None => Right(path)
      def bounds(token: String): IterationDomain.Bounds =
        val path = token.toLongOption.map(_ => token).getOrElse(
          shapeDimensionStaticPath(
            module,
            token
          ).getOrElse(invalid("shape slice value is not owned"))
        )
        shapeStaticBounds(module, path, view.path, "NODAL-BRIDGE-046", "shape slice")
      if selection.axis < 0 || selection.axis >= source.size || view.dimensions.size != source.size
      then
        invalid("shape slice axis or result rank does not match its input")
      source.indices.filter(_ != selection.axis).foreach: axis =>
        if view.dimensions(axis) != source(axis) then
          invalid("shape slice must retain every unselected dimension")
      // Equal static identities do not make alternate snapshot spellings canonical.
      Vector(selection.lower, selection.upperExclusive).foreach: token =>
        val expected = canonical(token).fold(_.toString, identity)
        if token != expected then
          invalid("shape slice endpoint must use its canonical static identity")
      (source ++ view.dimensions).foreach: token =>
        val expected = canonical(token).fold(
          _.toString,
          path =>
            module.declarations.find(_.path == path).map(_.name).getOrElse(path)
        )
        if token != expected then
          invalid("shape slice dimension must use its canonical extent token")
      val lower = bounds(selection.lower)
      val upper = bounds(selection.upperExclusive)
      val lengthToken = view.dimensions(selection.axis)
      val length = bounds(lengthToken)
      val lowerIdentity = canonical(selection.lower)
      val upperIdentity = canonical(selection.upperExclusive)
      val lengthIdentity = canonical(lengthToken)
      val canonicalLength = (lowerIdentity, upperIdentity) match
        case (Left(lo), Left(hi)) =>
          val difference = BigInt(hi) - BigInt(lo)
          difference.isValidLong && lengthIdentity == Left(difference.toLong)
        case (Left(0L), _) => lengthIdentity == upperIdentity
        case _ =>
          expressions.get(lengthToken).exists(expression =>
            expression.literal.isEmpty && expression.operation == "analog_sub" &&
              expression.operands.size == 2 && canonical(expression.operands(0)) == upperIdentity &&
              canonical(expression.operands(1)) == lowerIdentity
          )
      if !canonicalLength || lower.lower < 0L || length.lower <= 0L then
        invalid(
          "shape slice requires nonnegative lower and canonical strictly positive upper-minus-lower length"
        )
      val sourceBounds = source.map(bounds)
      val resultBounds = view.dimensions.map(bounds)
      if upperIdentity != canonical(source(selection.axis)) && upper.upper > sourceBounds(
          selection.axis
        ).lower
      then
        invalid("shape slice upper bound is not valid for every legal input extent")
      Vector(sourceBounds, resultBounds).foreach: values =>
        if values.exists(_.lower <= 0L) ||
          values.foldLeft(BigInt(1))((count, value) => count * BigInt(value.upper)) > BigInt(
            Long.MaxValue
          )
        then
          invalid(
            "shape slice requires positive dimensions and signed 64-bit maximum element counts"
          )

    private def shapeViewSignature(
        module: KernelModuleSnapshot,
        dimensions: Vector[String],
        path: String
    ): (BigInt, Vector[String]) =
      if dimensions.isEmpty then
        fail("NODAL-BRIDGE-045", "shape view requires at least one dimension", Some(path))
      var literalProduct = BigInt(1)
      var worstCase = BigInt(1)
      val symbols = Vector.newBuilder[String]
      val active = mutable.HashSet.empty[String]

      def factor(dimension: String, depth: Int = 0): Unit =
        if dimension.trim != dimension || dimension.isEmpty then
          fail(
            "NODAL-BRIDGE-045",
            "shape-view factor is not a canonical positive structural dimension",
            Some(path)
          )
        if dimension.forall(_.isDigit) then
          val value = dimension.toLongOption.filter(_ > 0).getOrElse(
            fail(
              "NODAL-BRIDGE-045",
              "shape-view literal exceeds signed 64-bit bounds",
              Some(path)
            )
          )
          literalProduct *= BigInt(value)
          worstCase *= BigInt(value)
        else
          val staticPath = shapeDimensionStaticPath(module, dimension).getOrElse(
            fail("NODAL-BRIDGE-045", "shape-view factor is not module-owned", Some(path))
          )
          if depth >= 512 || !active.add(staticPath) then
            fail(
              "NODAL-BRIDGE-045",
              "shape-view multiplication is cyclic or exceeds the supported depth",
              Some(path)
            )
          try
            val bounds = shapeStaticBounds(
              module,
              staticPath,
              path,
              "NODAL-BRIDGE-045",
              "shape-view factor"
            )
            if bounds.lower <= 0L then
              fail(
                "NODAL-BRIDGE-045",
                "shape-view factor lacks a positive finite structural proof",
                Some(path)
              )
            parameterExpressionsByOwner
              .getOrElse(module.path, Map.empty)
              .get(staticPath) match
              case Some(expression) if expression.literal.nonEmpty =>
                val value = expression.literal.flatMap(_.toLongOption).filter(_ > 0).getOrElse(
                  fail(
                    "NODAL-BRIDGE-045",
                    "shape-view literal expression exceeds signed 64-bit bounds",
                    Some(path)
                  )
                )
                literalProduct *= BigInt(value)
                worstCase *= BigInt(value)
              case Some(expression) if expression.operation == "analog_mul" =>
                if expression.operands.size != 2 then
                  fail(
                    "NODAL-BRIDGE-045",
                    "shape-view multiplication has invalid arity",
                    Some(path)
                  )
                expression.operands.foreach(factor(_, depth + 1))
              case _ =>
                symbols += staticPath
                worstCase *= BigInt(bounds.upper)
          finally
            val _ = active.remove(staticPath)
        if worstCase > BigInt(Long.MaxValue) then
          fail("NODAL-BRIDGE-045", "shape-view worst-case element count overflows", Some(path))

      dimensions.foreach(factor(_))
      literalProduct -> symbols.result().sorted

    private def shapeStaticBounds(
        module: KernelModuleSnapshot,
        value: String,
        diagnosticPath: String,
        diagnosticCode: String,
        subject: String,
        requiredEffect: String = "shape"
    ): IterationDomain.Bounds =
      val expressions = parameterExpressionsByOwner.getOrElse(module.path, Map.empty)
      val declarations =
        module.declarations.map(declaration => declaration.path -> declaration).toMap
      val cache = mutable.HashMap.empty[String, IterationDomain.Bounds]
      val active = mutable.HashSet.empty[String]

      def invalid(message: String, path: String): Nothing =
        fail(diagnosticCode, message, Some(path))

      if value.trim != value || value.isEmpty then
        invalid(s"$subject identity is noncanonical", diagnosticPath)

      def analyze(path: String): IterationDomain.Bounds =
        path.toLongOption.map(value => IterationDomain.Bounds(value, value)).getOrElse:
          cache.getOrElseUpdate(
            path, {
              if active.size >= 512 || !active.add(path) then
                invalid("shape index expression is cyclic or exceeds the depth limit", path)
              try
                declarations.get(path) match
                  case Some(declaration) =>
                    val attributes = declaration.attributes.toMap
                    if declaration.kind != "parameter" ||
                      !declaration.dataType.contains("Integer") ||
                      attributes.get("classification") != Some("structural") ||
                      !attributes
                        .get("structural_effects")
                        .exists(_.split(",").map(_.trim).contains(requiredEffect))
                    then invalid(s"$subject parameter is not structural Integer data", path)
                    val lower = attributes.get("integer_range_lower").flatMap(_.toLongOption)
                      .getOrElse(invalid(s"$subject parameter has no finite lower bound", path))
                    val upper = attributes.get("integer_range_upper").flatMap(_.toLongOption)
                      .getOrElse(invalid(s"$subject parameter has no finite upper bound", path))
                    if lower > upper then
                      invalid(s"$subject parameter range is not ordered", path)
                    IterationDomain.Bounds(lower, upper)
                  case None =>
                    val expression = expressions.getOrElse(
                      path,
                      invalid(s"$subject has no canonical static expression", path)
                    )
                    if expression.owner != module.path || expression.dataType != "Integer" then
                      invalid(s"$subject expression has invalid owner or type", path)
                    expression.literal.flatMap(_.toLongOption) match
                      case Some(literal) => IterationDomain.Bounds(literal, literal)
                      case None =>
                        val operation = expression.operation match
                          case "analog_add" => IterationDomain.Arithmetic.Add
                          case "static_integer_max" => IterationDomain.Arithmetic.Maximum
                          case "analog_sub" => IterationDomain.Arithmetic.Subtract
                          case "analog_mul" => IterationDomain.Arithmetic.Multiply
                          case "analog_div" => IterationDomain.Arithmetic.Divide
                          case "analog_neg" => IterationDomain.Arithmetic.Negate
                          case _ => invalid(s"$subject uses unsupported Integer arithmetic", path)
                        val bounds = expression.operands.map(analyze)
                        if operation == IterationDomain.Arithmetic.Subtract &&
                          expression.operands.size == 2 && expression.operands(
                            0
                          ) == expression.operands(1)
                        then IterationDomain.Bounds(0L, 0L)
                        else
                          IterationDomain.arithmetic(operation, bounds).fold(
                            problem => invalid(problem.message, path),
                            identity
                          )
              finally
                val _ = active.remove(path)
            }
          )

      analyze(value)

    private def shapeIndexBounds(
        module: KernelModuleSnapshot,
        value: String,
        indexPath: String
    ): IterationDomain.Bounds =
      shapeStaticBounds(module, value, indexPath, "NODAL-BRIDGE-044", "shape index")

    private def validShapeDimension(
        dimension: String,
        path: String,
        requiredEffect: String = "shape"
    ): Boolean =
      if dimension != dimension.trim then false
      else if dimension.matches("[1-9][0-9]*") then true
      else
        modulesByPath.get(resolveOwningModule(path)).flatMap: module =>
          shapeDimensionStaticPath(module, dimension).map: staticPath =>
            val bounds = shapeStaticBounds(
              module,
              staticPath,
              path,
              "NODAL-BRIDGE-018",
              s"symbolic $requiredEffect dimension",
              requiredEffect = requiredEffect
            )
            bounds.lower > 0L
        .getOrElse(false)

    private object WidthType:
      private val Pattern = raw"(Bits|UInt|SInt)\(([^)]+)\)".r
      def unapply(value: String): Option[(String, String)] = value match
        case Pattern(kind, width) => Some(kind -> width)
        case _ => None

    private def isDigitalParameterType(dataType: String): Boolean =
      Vector("!nodal.bits<", "!nodal.uint<", "!nodal.sint<", "!nodal.digital<", "!nodal.enum<")
        .exists(dataType.startsWith)

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
        stableLocalSymbol("parameter", name) -> (if isDigitalParameterType(dataType) then
                                                   digitalValues.literalAttribute(
                                                     value,
                                                     declaration.dataType.get,
                                                     root.path,
                                                     declaration.path,
                                                     digitalScalarType
                                                   )
                                                 else
                                                   typedLiteral(value, dataType, declaration.path)))

    private def conservativeDiscipline(declaration: KernelDeclarationSnapshot): String =
      conservativeDiscipline(declaration.attributes.toMap, declaration.path)

    private def conservativeDiscipline(attributes: Map[String, String], path: String): String =
      val declared = attributes.getOrElse(
        "discipline",
        fail(
          "NODAL-BRIDGE-014",
          "conservative declaration lacks discipline identity",
          Some(path)
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
            Some(path)
          )
        case _ => normalizeDiscipline(declared)

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
            ) ++ scopeAttributes(edge.generatedScope) ++ edge.path.toVector.map(path =>
              "path" -> quoted(path)
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
      val generatedRegions = snapshot.generatedRegions
        .filterNot(region => sourceByPath.contains(region.path))
        .sortBy(_.path)
        .map: region =>
          dictionary(
            Vector("semantic_path" -> quoted(region.path)) ++
              sourceFields(sourceByPath(region.induction))
          )
      array(ordinary ++ generatedRegions ++ AnalogProceduralMlir.sourceMapEntries(snapshot))

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
        if region.isEmpty && (name == "nodal.module" || name == "nodal.analog") then
          " ({\n  ^bb0:\n})"
        else if region.isEmpty then " ({\n})"
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
