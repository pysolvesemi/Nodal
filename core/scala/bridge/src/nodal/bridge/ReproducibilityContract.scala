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
  val Version: Int = 2
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
    try
      val snapshot = ConstructionKernel.inspect(
        top,
        EmitOptions(backend = Backend.VerilogA)
      )
      captureSnapshot(snapshot, nodalc, translator, workingDirectory, timeout)
    catch
      case exception: ConstructionException =>
        Left(constructionFailure(exception))

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
                "verilog-a",
                nativeNormalized = true
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
      backendProfile: String,
      nativeNormalized: Boolean = false
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
          backendProfile,
          nativeNormalized
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

  private def constructionFailure(
      exception: ConstructionException
  ): NativeCompilerFailure =
    NativeCompilerFailure(
      BridgeDiagnostic(
        exception.diagnostic.code,
        exception.diagnostic.message,
        exception.diagnostic.semanticPath
      ),
      Vector.empty,
      "",
      "",
      None
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
      backendProfile: String,
      nativeNormalized: Boolean
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
      "digital_materialization" -> obj(
        "decision_status" -> string(if snapshot.digitalExpressions.isEmpty then "not-applicable"
        else if nativeNormalized then "see-normalized-mlir" else "not-run"),
        "target_requirements" -> string("pending"),
        "inventory_locator" -> string("nodal.module[*].nodal.digital.materialization"),
        "inventory_artifact" -> string("normalized.mlir"),
        "inventory_artifact_sha256" -> string(digest(normalizedMlir)),
        "requested_quality" -> qualityRequested(snapshot.emitQuality)
      ),
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
        obj((Vector(
          "module" -> string(modulePath),
          "semantic_path" -> string(declaration.path),
          "declaration_kind" -> string(declaration.kind),
          "data_type" -> optionalString(declaration.dataType),
          "shape" -> string(if declaration.conservativeDimensions.nonEmpty then
            declaration.conservativeDimensions.mkString("x")
          else shapeOf(declaration.dataType)),
          "layout" -> string(if declaration.conservativeDimensions.nonEmpty then
            "conservative-vector-row-major"
          else if declaration.kind == "variable" &&
            declaration.dataType.exists(_.startsWith("Vec(Real;"))
          then
            "procedural-real-flat-row-major"
          else layoutOf(declaration.dataType, backendProfile)),
          "storage" -> string(if declaration.conservativeDimensions.nonEmpty then "structural"
          else if declaration.kind == "variable" &&
            declaration.dataType.exists(_.startsWith("Vec(Real;"))
          then
            "procedural"
          else storageOf(declaration.kind))
        ) ++ generatedScopeFields(declaration.generatedScope))*)
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
    case "analog-node-array" => "structural"
    case "register" => "register"
    case "memory" => "memory"
    case "wire" | "variable" | "analog-signal" => "signal"
    case "input" | "output" | "analog-input" | "analog-output" |
        "analog-inout" | "analog-node" | "conservative-terminal" |
        "interface-port" | "interface-array" | "digital-inout" =>
      "connectivity"
    case _ => "value"

  private def materialization(snapshot: ConstructionSnapshot): JsonValue =
    val digital = snapshot.digitalExpressions.map(_.path).toSet
    val legacy = snapshot.origins
      .filter(origin => origin.kind == "expression" && !digital.contains(origin.semanticPath))
      .sortBy(origin => (origin.semanticPath, origin.id))
      .map: origin =>
        obj(
          "origin_id" -> string(origin.id),
          "semantic_path" -> string(origin.semanticPath),
          "decision" -> string(if origin.inlined then "inline" else "materialize"),
          "reason" -> string(if origin.inlined then "semantic-origin-safe-inline"
          else "semantic-origin-retained"),
          "operation" -> string(origin.operation),
          "parents" -> array(origin.parents.sorted.map(string)),
          "sink" -> optionalString(origin.sink)
        )
    val authored = snapshot.digitalExpressions.sortBy(_.path).map: expression =>
      obj(
        "semantic_path" -> string(expression.path),
        "operation" -> string(expression.operation),
        "decision" -> string("not-evaluated-by-source"),
        "reason" -> string("authored-intent-only"),
        "intent" -> expression.materializationIntent.map(intentJson).getOrElse(JNull)
      )
    array(legacy ++ authored)

  private def intentJson(intent: KernelDigitalIntent): JsonValue = intent match
    case KernelDigitalIntent.Named(name) =>
      obj("kind" -> string("named"), "value" -> string(name))
    case KernelDigitalIntent.Keep(reason) =>
      obj("kind" -> string("keep"), "value" -> string(reason))

  private def qualityRequested(quality: EmitQuality): JsonValue = obj(
    "temporaries" -> string(quality.temporaries.toString),
    "naming" -> string(quality.naming.toString),
    "checks_requested" -> string(quality.checks.toString),
    "waivers_requested" -> array(quality.waivers.toVector.map(waiver =>
      obj(
        "id" -> string(waiver.id),
        "reason" -> string(waiver.reason),
        "check" -> string(waiver.check)
      )
    ))
  )

  private def hasGeneratedDigitalFacts(value: KernelGeneratedRegionSnapshot): Boolean =
    value.digitalBody.nonEmpty || value.yieldedArea.nonEmpty || value.exports.nonEmpty

  private def digitalGraph(snapshot: ConstructionSnapshot): JsonValue = JObject(Vector(
    "expressions" -> array(snapshot.digitalExpressions.sortBy(_.path).map(value =>
      JObject(Vector(
        "path" -> string(value.path),
        "owner" -> string(value.owner),
        "operation" -> string(value.operation),
        "operands" -> array(value.operands.map(string)),
        "data_type" -> string(value.dataType),
        "literal" -> optionalString(value.literal),
        "enum_identity" -> optionalString(value.enumIdentity),
        "case_ids" -> array(value.caseIds.map(string)),
        "region" -> optionalString(value.region),
        "intent" -> value.materializationIntent.map(intentJson).getOrElse(JNull)
      ) ++ value.reset.toVector.map(reset =>
        "reset" -> obj(
          "kind" -> string(reset.kind),
          "polarity" -> string(reset.polarity),
          "release" -> string(reset.release),
          "destination" -> optionalString(reset.destination),
          "stages" -> number(reset.stages),
          "inputs" -> array(reset.inputs.map(string))
        )
      ) ++ value.waiver.toVector.map(waiver =>
        "waiver" -> obj(waiverFields(waiver)*)
      ) ++ value.crossing.toVector.map(crossing =>
        "crossing" -> obj(
          "kind" -> string(crossing.kind),
          "contract" -> string(crossing.contract),
          "source_domain" -> string(crossing.sourceDomain),
          "destination_domain" -> string(crossing.destinationDomain),
          "stages" -> number(crossing.stages),
          "depth" -> crossing.depth.map(number).getOrElse(JNull),
          "identity" -> optionalString(crossing.identity),
          "result_role" -> string(crossing.resultRole),
          "obligations" -> obj(crossing.obligations.map((name, value) => name -> string(value))*)
        )
      ) ++ value.memory.toVector.map(port =>
        "memory" -> obj("memory" -> string(port.memory), "domain" -> string(port.domain))
      ) ++ value.structure.toVector.map(evidence =>
        "structure" -> obj(
          "schema_type" -> string(evidence.schemaType),
          "ordinal" -> evidence.ordinal.map(number).getOrElse(JNull),
          "name" -> optionalString(evidence.name)
        )
      ) ++ value.generatedMember.toVector.map(member =>
        "generated_member" -> obj((Vector(
          "family" -> string(member.family),
          "ordinal" -> string(member.ordinal),
          "member" -> string(member.member),
          "access" -> string(member.access)
        ) ++ member.familyInput.toVector.map(input =>
          "family_input" -> string(input)
        ) ++ Option.when(member.selectionPrefix.nonEmpty)(
          "selection_prefix" -> array(member.selectionPrefix.map(frame =>
            obj(
              "family" -> string(frame.family),
              "ordinal" -> string(frame.ordinal),
              "member" -> string(frame.member)
            )
          ))
        ).toVector)*)
      ) ++ value.interfaceMember.toVector.map(member =>
        "interface_member" -> obj(
          "endpoint" -> string(member.endpoint),
          "member" -> string(member.member),
          "role" -> string(member.role)
        )
      ) ++ Option.when(value.hierarchyDriver)("hierarchy_driver" -> JBoolean(true)))
    )),
    "assignments" -> array(snapshot.digitalAssignments.sortBy(_.path).map(value =>
      JObject(Vector(
        "path" -> string(value.path),
        "owner" -> string(value.owner),
        "target" -> string(value.target),
        "value" -> string(value.value),
        "kind" -> string(value.kind),
        "region" -> optionalString(value.region),
        "source_ordinal" -> number(value.sourceOrdinal)
      ) ++ value.eventProcess.map(path => "event_process" -> string(path)).toVector)
    )),
    "loops" ->
      array(snapshot.digitalLoops.sortBy(value => (value.owner, value.sourceOrdinal)).map(value =>
        obj(
          "path" -> string(value.path),
          "owner" -> string(value.owner),
          "lower" -> string(value.lower),
          "upper_exclusive" -> string(value.upperExclusive),
          "step" -> string(value.step),
          "maximum" -> value.maximum.map(number).getOrElse(JNull),
          "maximum_trip_count" -> number(value.maximumTripCount),
          "source_ordinal" -> number(value.sourceOrdinal),
          "generated_region" -> optionalString(value.generatedRegion),
          "captures" -> array(value.captures.map(string)),
          "arguments" -> array(value.arguments.map(argument =>
            obj(
              "path" -> string(argument.path),
              "data_type" -> string(argument.dataType),
              "role" -> string(argument.role)
            )
          ))
        )
      )),
    "iteration_indices" -> array(snapshot.iterationIndices.sortBy(_.path).map(value =>
      obj(
        "path" -> string(value.path),
        "owner" -> string(value.owner),
        "region" -> string(value.region),
        "operation" -> string(value.operation),
        "operands" -> array(value.operands.map(string))
      )
    )),
    "collections" -> array(snapshot.digitalCollections.sortBy(_.path).map(value =>
      obj(
        "path" -> string(value.path),
        "owner" -> string(value.owner),
        "parent_region" -> optionalString(value.parentRegion),
        "kind" -> string(value.kind),
        "inputs" -> array(value.inputs.map(string)),
        "seed" -> optionalString(value.seed),
        "captures" -> array(value.captures.map(string)),
        "arguments" -> array(value.arguments.map(argument =>
          obj(
            "path" -> string(argument.path),
            "data_type" -> string(argument.dataType),
            "role" -> string(argument.role)
          )
        )),
        "yield" -> string(value.yieldValue),
        "data_type" -> string(value.dataType)
      )
    ))
  ) ++ Option.when(snapshot.staticPredicates.nonEmpty)("static_predicates" ->
    array(snapshot.staticPredicates.sortBy(_.path).map(value =>
      obj(
        "path" -> string(value.path),
        "owner" -> string(value.owner),
        "region" -> optionalString(value.region),
        "operation" -> string(value.operation),
        "operands" -> array(value.operands.map(string))
      )
    ))).toVector ++ Option.when(snapshot.structuralIfs.nonEmpty)("structural_ifs" ->
    array(snapshot.structuralIfs.sortBy(_.path).map(value =>
      def arm(value: KernelStructuralArmSnapshot): JsonValue = obj(
        "path" -> string(value.path),
        "captures" -> array(value.captures.map(string)),
        "arguments" -> array(value.arguments.map(argument =>
          obj(
            "path" -> string(argument.path),
            "data_type" -> string(argument.dataType),
            "role" -> string(argument.role)
          )
        )),
        "yield" -> optionalString(value.yieldValue)
      )
      obj(
        "path" -> string(value.path),
        "owner" -> string(value.owner),
        "parent_region" -> optionalString(value.parentRegion),
        "predicate" -> string(value.predicate),
        "true_arm" -> arm(value.trueArm),
        "false_arm" -> arm(value.falseArm),
        "data_type" -> optionalString(value.dataType),
        "source_ordinal" -> number(value.sourceOrdinal)
      )
    ))).toVector ++ Option.when(snapshot.behavioralIfs.nonEmpty)("behavioral_ifs" ->
    array(snapshot.behavioralIfs.sortBy(_.path).map(value =>
      def arm(value: KernelStructuralArmSnapshot): JsonValue = obj(
        "path" -> string(value.path),
        "captures" -> array(value.captures.map(string)),
        "arguments" -> array(value.arguments.map(argument =>
          obj(
            "path" -> string(argument.path),
            "data_type" -> string(argument.dataType),
            "role" -> string(argument.role)
          )
        )),
        "yield" -> optionalString(value.yieldValue)
      )
      obj(
        "path" -> string(value.path),
        "owner" -> string(value.owner),
        "parent_region" -> optionalString(value.parentRegion),
        "predicate" -> string(value.predicate),
        "true_arm" -> arm(value.trueArm),
        "false_arm" -> arm(value.falseArm),
        "source_ordinal" -> number(value.sourceOrdinal),
        "root" -> string(value.root)
      )
    ))).toVector ++ Option.when(snapshot.flatFsms.nonEmpty)("flat_fsms" ->
    array(snapshot.flatFsms.sortBy(_.path).map(value =>
      obj((Vector(
        "path" -> string(value.path),
        "owner" -> string(value.owner),
        "parent_region" -> optionalString(value.parentRegion),
        "domain" -> string(value.domain),
        "storage" -> string(value.storage),
        "enum_identity" -> string(value.enumIdentity),
        "requested_encoding" -> string(value.requestedEncoding),
        "resolved_encoding" -> string(value.resolvedEncoding),
        "local_width" -> number(value.localWidth),
        "initial_case" -> string(value.initialCase),
        "illegal_state" -> string(value.illegalState),
        "source_ordinal" -> number(value.sourceOrdinal),
        "next_local" -> string(value.nextLocal),
        "transition_taken" -> string(value.transitionTaken),
        "local_codes" -> array(value.localCodes.map(code =>
          obj(
            "case_id" -> string(code.caseId),
            "code" -> string(code.code)
          )
        )),
        "states" -> array(value.states.map(state =>
          obj(
            "case_id" -> string(state.caseId),
            "terminal" -> bool(state.terminal),
            "source_ordinal" -> number(state.sourceOrdinal)
          )
        )),
        "transitions" -> array(value.transitions.map(edge =>
          obj((Vector(
            "source_case" -> string(edge.sourceCase),
            "destination_case" -> optionalString(edge.destinationCase),
            "mode" -> string(edge.mode),
            "guard" -> string(edge.guard),
            "source_ordinal" -> number(edge.sourceOrdinal)
          ) ++ Option.when(value.stack.nonEmpty)(Vector(
            "kind" -> string(edge.kind),
            "resume_case" -> optionalString(edge.resumeCase)
          )).toVector.flatten)*)
        )),
        "actions" -> array(value.actions.map(action =>
          obj(
            "region" -> sequentialArm(KernelStructuralArmSnapshot(
              action.path,
              action.captures,
              action.arguments,
              None
            )),
            "phase" -> string(action.phase),
            "owner_case" -> optionalString(action.ownerCase),
            "transition_ordinal" -> action.transitionOrdinal.map(number).getOrElse(JNull),
            "source_ordinal" -> number(action.sourceOrdinal)
          )
        ))
      ) ++ value.stack.map(stack =>
        "stack" -> obj(
          "contract" -> string("flat-fsm-v5"),
          "capacity" -> number(stack.capacity),
          "pointer_width" -> number(stack.pointerWidth),
          "pointer_storage" -> string(stack.pointerStorage),
          "frame_storage" -> array(stack.frameStorage.map(string)),
          "actual_stack_bits" -> number(stack.pointerWidth + stack.capacity * value.localWidth),
          "overflow_trap" -> optionalString(stack.overflow),
          "underflow_trap" -> optionalString(stack.underflow),
          "invalid_frame_trap" -> optionalString(stack.invalidFrame),
          "assert_hold" -> string(
            "failed operation holds transitions and stack; active actions continue"
          ),
          "frame_initialization" -> string("uninitialized; pointer reset independently")
        )
      ) ++ value.control.map(control =>
        "control" -> obj(
          "storage" -> string(control.storage),
          "restart" -> string(control.restart),
          "cancel" -> string(control.cancel),
          "enable" -> string(control.enable),
          "entered" -> string(control.entered),
          "exited" -> string(control.exited)
        )
      ) ++ Option.when(value.timerEdges.nonEmpty || value.transferEdges.nonEmpty)(
        "timing" -> obj(
          "contract" -> string("flat-fsm-v4"),
          "timer_storage" -> optionalString(value.timerStorage),
          "actual_timer_bits" -> number(value.timerStorage.size * FsmTimingRuntime.Width),
          "timer_saturation" -> number(FsmTimingRuntime.Saturation),
          "expiry" -> string("registered-elapsed >= cycles-1; enabled operational edges"),
          "timer_edges" -> array(value.timerEdges.map(edge =>
            obj(
              "ordinal" -> number(edge.ordinal),
              "cycles" -> number(edge.cycles),
              "guard" -> string(edge.guard)
            )
          )),
          "transfer_edges" -> array(value.transferEdges.map(edge =>
            obj(
              "ordinal" -> number(edge.ordinal),
              "guard" -> string(edge.guard),
              "payload" -> string(edge.payload),
              "valid" -> string(edge.valid),
              "ready" -> string(edge.ready)
            )
          ))
        )
      ) ++ value.composition.map(composition =>
        "composition" -> obj(
          "contract" -> string("fsm-composition-v1"),
          "root" -> string(composition.root),
          "parent" -> optionalString(composition.parent),
          "owner_case" -> optionalString(composition.ownerCase),
          "slot" -> optionalString(composition.slot),
          "definition_name" -> string(composition.definitionName),
          "definition_hash" -> string(composition.definitionHash),
          "fragments" -> array(composition.fragments.map((name, cases) =>
            obj("name" -> string(name), "cases" -> array(cases.map(string)))
          )),
          "occurrences" -> number(composition.occurrences),
          "records" -> number(composition.records),
          "depth" -> number(composition.depth),
          "structural_completion_possible" -> bool(composition.completionPossible),
          "inactive_observation" -> string(
            "qualify-with-active; storage-may-be-retained-or-uninitialized"
          ),
          "establishment" -> string(composition.establishment),
          "groups" -> array(composition.groups.map(group =>
            obj(
              "owner_case" -> string(group.ownerCase),
              "children" -> array(group.children.map(string)),
              "restart" -> string(group.restart),
              "cancel" -> string(group.cancel),
              "enable" -> string(group.enable)
            )
          )),
          "joins" -> array(composition.joins.map(join =>
            obj(
              "result" -> string(join.result),
              "owner_case" -> string(join.ownerCase),
              "children" -> array(join.children.map(string)),
              "mode" -> string(join.policy),
              "selection" -> string(join.selection)
            )
          ))
        )
      ))*)
    ))).toVector ++ Option.when(snapshot.eventProcesses.nonEmpty)("event_processes" ->
    array(snapshot.eventProcesses.sortBy(_.path).map(value =>
      obj(
        "path" -> string(value.path),
        "owner" -> string(value.owner),
        "trigger" -> string(value.trigger),
        "edge" -> string(value.edge),
        "contract" -> string("root_event_v1"),
        "storage_targets" -> array(value.storageTargets.map(string)),
        "external_captures" -> array(value.externalCaptures.map(string))
      )
    ))).toVector ++ Option.when(snapshot.enumSwitches.nonEmpty)("enum_switches" ->
    array(snapshot.enumSwitches.sortBy(_.path).map(value =>
      obj(
        "path" -> string(value.path),
        "owner" -> string(value.owner),
        "parent_region" -> optionalString(value.parentRegion),
        "selector" -> string(value.selector),
        "enum_identity" -> string(value.enumIdentity),
        "case_ids" -> array(value.caseIds.map(string)),
        "arms" -> array(value.arms.map(sequentialArm)),
        "invalid_arm" -> sequentialArm(value.invalidArm),
        "source_ordinal" -> number(value.sourceOrdinal),
        "root" -> string(value.root)
      )
    ))).toVector ++ Option.when(snapshot.generatedRegions.exists(hasGeneratedDigitalFacts))(
    "digital_generates" -> array(snapshot.generatedRegions.filter(hasGeneratedDigitalFacts)
      .sortBy(_.path).map(value =>
        JObject(Vector(
          "path" -> string(value.path),
          "owner" -> string(value.owner),
          "parent_region" -> optionalString(value.parent),
          "induction" -> string(value.induction),
          "lower" -> string(value.lower),
          "upper_exclusive" -> string(value.upperExclusive),
          "step" -> string(value.step),
          "maximum" -> value.maximum.map(number).getOrElse(JNull),
          "maximum_trip_count" -> number(value.maximumTripCount)
        ) ++ value.digitalBody.toVector.flatMap(body =>
          Vector(
            "source_ordinal" -> number(body.sourceOrdinal),
            "captures" -> array(body.captures.map(string)),
            "arguments" -> array(body.arguments.map(argument =>
              obj(
                "path" -> string(argument.path),
                "data_type" -> string(argument.dataType),
                "role" -> string(argument.role)
              )
            ))
          ) ++ body.parentRegion.toVector.map(path => "body_parent_region" -> string(path))
        ) ++ value.yieldedArea.toVector.map(path => "yielded_area" -> string(path)) ++
          Option.when(value.exports.nonEmpty)(
            "exports" -> array(value.exports.map(exported =>
              obj((Vector(
                "member" -> string(exported.member),
                "target" -> string(exported.target),
                "access" -> string(exported.access)
              ) ++ Option.when(
                exported.kind != "storage"
              )("kind" -> string(exported.kind)).toVector)*)
            ))
          ).toVector)
      ))
  ).toVector ++ Option.when(snapshot.memoryWrites.nonEmpty)(
    "memory_writes" -> array(snapshot.memoryWrites.sortBy(_.path).map(value =>
      obj(
        "path" -> string(value.path),
        "owner" -> string(value.owner),
        "memory" -> string(value.memory),
        "domain" -> string(value.domain),
        "address" -> string(value.address),
        "value" -> string(value.value),
        "mask" -> string(value.mask),
        "region" -> optionalString(value.region),
        "source_ordinal" -> number(value.sourceOrdinal)
      )
    ))
  ).toVector)

  private def sequentialArm(value: KernelStructuralArmSnapshot): JsonValue = obj(
    "path" -> string(value.path),
    "captures" -> array(value.captures.map(string)),
    "arguments" -> array(value.arguments.map(argument =>
      obj(
        "path" -> string(argument.path),
        "data_type" -> string(argument.dataType),
        "role" -> string(argument.role)
      )
    )),
    "yield" -> optionalString(value.yieldValue)
  )

  private def semanticNames(snapshot: ConstructionSnapshot): JsonValue =
    val declared = snapshot.names
      .sortBy(entry => (entry.semanticPath, entry.category, entry.name))
      .map: entry =>
        obj(
          "semantic_path" -> string(entry.semanticPath),
          "name" -> string(entry.name),
          "category" -> string(entry.category),
          "provenance" -> string(entry.provenance),
          "source" -> source(entry.source),
          "aliases" -> array(entry.aliases.sorted.map(string))
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
    val digital = snapshot.digitalExpressions.map(_.path).toSet
    val entries = snapshot.origins
      .filter(_.kind == "expression")
      .sortBy(origin => (origin.semanticPath, origin.id))
      .map: origin =>
        obj((Vector(
          "origin_id" -> string(origin.id),
          "semantic_path" -> string(origin.semanticPath),
          "source" -> source(origin.source),
          "parents" -> array(origin.parents.sorted.map(string)),
          "sink" -> optionalString(origin.sink)
        ) ++
          (if digital.contains(origin.semanticPath) then
             Vector("emission_status" -> string("pending-target"))
           else Vector("inlined" -> bool(origin.inlined))))*)
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
    val entries = snapshot.waivers
      .sortBy(waiver => (waiver.semanticPath, waiver.id))
      .map: waiver =>
        obj(
          "kind" -> string(waiver.kind),
          "id" -> string(waiver.id),
          "reason" -> string(waiver.reason),
          "relation" -> string(waiver.relation),
          "semantic_path" -> string(waiver.semanticPath),
          "source_value" -> optionalString(waiver.sourceValue),
          "destination_domain" -> optionalString(waiver.destinationDomain),
          "source" -> source(waiver.source)
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

  private def waiverFields(waiver: KernelExecutableWaiverSnapshot): Vector[(String, JsonValue)] =
    Vector(
      "contract" -> string(waiver.contract),
      "id" -> string(waiver.id),
      "reason" -> string(waiver.reason),
      "source_domain" -> string(waiver.sourceDomain),
      "destination_domain" -> string(waiver.destinationDomain),
      "relation" -> obj(
        "kind" -> string(waiver.relation.kind),
        "multiply" -> waiver.relation.multiply.map(number).getOrElse(JNull),
        "divide" -> waiver.relation.divide.map(number).getOrElse(JNull),
        "phase" -> optionalString(waiver.relation.phase),
        "phase_known" -> waiver.relation.phaseKnown.map(bool).getOrElse(JNull)
      ),
      "obligations" -> obj(waiver.obligations.map((name, value) => name -> string(value))*)
    )

  private def crossingReport(snapshot: ConstructionSnapshot): JsonValue =
    val executableWaivers = snapshot.digitalExpressions.filter(_.waiver.nonEmpty).sortBy(_.path)
      .map: expression =>
        obj((Vector(
          "record_kind" -> string("executable_waiver"),
          "semantic_path" -> string(expression.path),
          "source_value" -> string(expression.operands.head)
        ) ++ waiverFields(expression.waiver.get))*)
    val executableEntries = snapshot.digitalExpressions.filter(_.crossing.nonEmpty).sortBy(_.path)
      .map: expression =>
        val crossing = expression.crossing.get
        obj(
          "record_kind" -> string("executable_crossing"),
          "kind" -> string(crossing.kind),
          "contract" -> string(crossing.contract),
          "semantic_path" -> string(expression.path),
          "source_value" -> string(expression.operands.head),
          "source_domain" -> string(crossing.sourceDomain),
          "destination_domain" -> string(crossing.destinationDomain),
          "stages" -> number(crossing.stages),
          "depth" -> crossing.depth.map(number).getOrElse(JNull),
          "identity" -> optionalString(crossing.identity),
          "result_role" -> string(crossing.resultRole),
          "obligations" -> obj(crossing.obligations.map((name, value) => name -> string(value))*)
        )
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
    val waiverEntries = snapshot.waivers
      .sortBy(waiver => (waiver.semanticPath, waiver.id))
      .map: waiver =>
        obj(
          "record_kind" -> string("waiver"),
          "kind" -> string(waiver.kind),
          "id" -> string(waiver.id),
          "reason" -> string(waiver.reason),
          "relation" -> string(waiver.relation),
          "semantic_path" -> string(waiver.semanticPath),
          "source_value" -> optionalString(waiver.sourceValue),
          "destination_domain" -> optionalString(waiver.destinationDomain),
          "source" -> source(waiver.source)
        )
    val generatedEntries = snapshot.generatedNames
      .filter(entry =>
        Set("crossing", "synchronizer", "reset-controller", "waiver").contains(entry.category)
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
    array(
      executableEntries ++ executableWaivers ++ originEntries ++ waiverEntries ++ generatedEntries
    )

  /** Artifact projection of the authoritative snapshot graph; these fields are provenance, never an
    * additional binding or shape proof owner.
    */
  private def generatedScopeFields(scope: Option[KernelGeneratedScope])
      : Vector[(String, JsonValue)] =
    scope.toVector.map(value =>
      "generated_scope" -> obj(
        "owner" -> string(value.owner),
        "induction" -> string(value.induction)
      )
    )

  private def parameterExpressionInventory(snapshot: ConstructionSnapshot): (String, JsonValue) =
    "parameter_expressions" -> array(snapshot.parameterExpressions.sortBy(_.path).map: value =>
      obj((Vector(
        "path" -> string(value.path),
        "owner" -> string(value.owner),
        "operation" -> string(value.operation),
        "operands" -> array(value.operands.map(string)),
        "data_type" -> string(value.dataType),
        "literal" -> optionalString(value.literal),
        "unit" -> optionalString(value.unit)
      ) ++ value.region.toVector.map(region => "region" -> string(region)))*))

  private def conservativeBindingGraph(snapshot: ConstructionSnapshot)
      : Vector[(String, JsonValue)] =
    if snapshot.conservativeArrayBindings.isEmpty && snapshot.instanceArrayRoots.isEmpty then
      Vector.empty
    else
      val declarations =
        snapshot.modules.flatMap(_.declarations).map(value => value.path -> value).toMap
      val roots = snapshot.instanceArrayRoots.map(value => value.path -> value).toMap
      val indices = snapshot.shapeIndices.map(value => value.path -> value).toMap
      val views = snapshot.shapeViews.map(value => value.path -> value).toMap
      def dimensions(path: String): Vector[String] =
        declarations.get(path).map(_.conservativeDimensions)
          .orElse(roots.get(path).map(_.dimensions))
          .orElse(indices.get(path).map(_.resultDimensions))
          .orElse(views.get(path).map(_.dimensions)).getOrElse(Vector.empty)
      def chain(path: String): Vector[String] =
        val result = scala.collection.mutable.ArrayBuffer.empty[String]
        val seen = scala.collection.mutable.HashSet.empty[String]
        var next = Option(path)
        while next.nonEmpty do
          val current = next.get
          if !seen.add(current) then
            throw new IllegalArgumentException("cyclic conservative binding artifact graph")
          result += current
          next = indices.get(current).map(_.input).orElse(views.get(current).map(_.input))
        result.reverse.toVector
      def endpoint(path: String): JsonValue =
        val projections = chain(path)
        val root = roots.get(projections.head)
        obj(
          "reference" -> string(path),
          "root" -> string(projections.head),
          "projection_chain" -> array(projections.tail.map(string)),
          "root_dimensions" -> array(dimensions(projections.head).map(string)),
          "effective_dimensions" -> array(dimensions(path).map(string)),
          "instance" -> optionalString(root.map(_.instance)),
          "port" -> optionalString(root.map(_.port)),
          "source" -> source(snapshot.sourceMap.find(_.semanticPath == path).map(_.source))
        )
      Vector(
        "instance_array_roots" -> array(snapshot.instanceArrayRoots.sortBy(_.path).map: root =>
          obj((Vector(
            "path" -> string(root.path),
            "owner" -> string(root.owner),
            "instance" -> string(root.instance),
            "port" -> string(root.port),
            "dimensions" -> array(root.dimensions.map(string))
          ) ++ generatedScopeFields(root.generatedScope))*)),
        "conservative_array_bindings" ->
          array(snapshot.conservativeArrayBindings.sortBy(_.path).map: binding =>
            obj((Vector(
              "path" -> string(binding.path),
              "owner" -> string(binding.owner),
              "left" -> endpoint(binding.left),
              "right" -> endpoint(binding.right),
              "vector_orientation" -> string("ascending"),
              "source" ->
                source(snapshot.sourceMap.find(_.semanticPath == binding.path).map(_.source))
            ) ++ generatedScopeFields(binding.generatedScope))*)),
        "structural_shape_indices" -> array(snapshot.shapeIndices.sortBy(_.path).map: value =>
          obj((Vector(
            "path" -> string(value.path),
            "owner" -> string(value.owner),
            "input" -> string(value.input),
            "indices" -> array(value.indices.map(string)),
            "result_dimensions" -> array(value.resultDimensions.map(string))
          ) ++ generatedScopeFields(value.generatedScope))*)),
        "structural_shape_views" -> array(snapshot.shapeViews.sortBy(_.path).map: value =>
          obj((Vector(
            "path" -> string(value.path),
            "owner" -> string(value.owner),
            "input" -> string(value.input),
            "dimensions" -> array(value.dimensions.map(string)),
            "slice" -> value.slice.map(slice =>
              obj(
                "axis" -> number(slice.axis),
                "lower" -> string(slice.lower),
                "upper_exclusive" -> string(slice.upperExclusive)
              )
            ).getOrElse(JNull)
          ) ++ generatedScopeFields(value.generatedScope))*)),
        parameterExpressionInventory(snapshot)
      )

  private def snapshotJson(snapshot: ConstructionSnapshot): JsonValue =
    val nodeSelections = snapshot.shapeIndices.filter(_.sourceUses.nonEmpty).sortBy(_.path)
    val digitalPaths = snapshot.digitalExpressions.iterator.map(_.path).toSet
    JObject(Vector(
      "root" -> string(snapshot.root),
      "emit_quality_requested" -> qualityRequested(snapshot.emitQuality),
      "digital_graph" -> digitalGraph(snapshot),
      "modules" -> array(
        snapshot.modules.sortBy(_.path).map: module =>
          obj((Vector(
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
                obj((Vector(
                  "path" -> string(declaration.path),
                  "kind" -> string(declaration.kind),
                  "name" -> string(declaration.name),
                  "data_type" -> optionalString(declaration.dataType),
                  "domain" -> optionalString(declaration.domain),
                  "attributes" -> attributes(declaration.attributes)
                ) ++ declaration.instancePort.filter(_.selection.nonEmpty).toVector.map(boundary =>
                  "instance_port" -> obj((Vector(
                    "instance" -> string(boundary.instance),
                    "port" -> string(boundary.port),
                    "direction" -> string(boundary.direction),
                    "selection" -> generatedSelection(boundary.selection.get)
                  ) ++
                    boundary.region.toVector.map(region => "region" -> string(region)))*)
                ) ++ declaration.interfaceBoundary.toVector.map(boundary =>
                  "interface_boundary" -> interfaceBoundary(boundary)
                ) ++ declaration.resolvedBoundary.toVector.map(boundary =>
                  "resolved_boundary" -> interfaceBoundary(boundary)
                ) ++ declaration.resolvedMember.toVector.map(member =>
                  "resolved_member" -> resolvedMember(member)
                ) ++ declaration.conservativeMember.toVector.map(member =>
                  "conservative_member" -> resolvedMember(member)
                ) ++ Option.when(declaration.conservativeDimensions.nonEmpty)(
                  "conservative_dimensions" -> array(declaration.conservativeDimensions.map(string))
                ).toVector ++ generatedScopeFields(declaration.generatedScope))*)
            ),
            "instances" -> array(
              module.instances.sortBy(_.path).map: instance =>
                obj((Vector(
                  "path" -> string(instance.path),
                  "child_module" -> string(instance.childModule),
                  "lexical_domain" -> optionalString(instance.lexicalDomain),
                  "bindings" -> attributes(instance.bindings),
                  "parameter_bindings" -> attributes(instance.parameterBindings)
                ) ++ generatedScopeFields(instance.generatedScope))*)
            )
          ) ++ module.externalName.toVector.map(name => "external_name" -> string(name)))*)
      ),
      "interface_abi" -> array(
        snapshot.interfaceAbi.sortBy(_.logicalPath).map: entry =>
          obj((Vector(
            "logical_path" -> string(entry.logicalPath),
            "emitted_path" -> string(entry.emittedPath),
            "role" -> string(entry.role),
            "access" -> string(entry.access),
            "data_type" -> string(entry.dataType)
          ) ++ entry.domain.toVector.map(value => "domain" -> string(value)))*)
      ),
      "resolved_nets" -> array(
        snapshot.resolvedNets.sortBy(_.path).map: net =>
          obj((Vector(
            "path" -> string(net.path),
            "data_type" -> string(net.dataType),
            "mode" -> string(net.mode),
            "operations" -> array(net.operations.sorted.map(string))
          ) ++ (if net.member.nonEmpty then Vector.empty
                else
                  Vector(
                    "placement" -> string(net.placement),
                    "profile" -> string(net.profile)
                  )) ++ net.boundary.toVector.map(boundary =>
            "boundary" -> interfaceBoundary(boundary)
          ) ++
            net.member.toVector.map(member => "member" -> resolvedMember(member)))*)
      ),
      "topology" -> array(
        snapshot.topology.sortBy(edge => (edge.kind, edge.left, edge.right)).map: edge =>
          obj((Vector(
            "kind" -> string(edge.kind),
            "left" -> string(edge.left),
            "right" -> string(edge.right)
          ) ++ generatedScopeFields(edge.generatedScope) ++
            edge.path.toVector.map(path => "path" -> string(path)))*)
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
              (if digitalPaths.contains(origin.semanticPath) then
                 "origin_inlined_hint"
               else "inlined") -> bool(origin.inlined)
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
      "analog_semantics" -> analogSemantics(snapshot),
      "typed_equation_source" -> string(new AnalogEquationMlir.Plan(snapshot).canonical),
      "analog_regions" -> array(
        snapshot.analogRegions.map: region =>
          obj(
            "path" -> string(region.path),
            "module" -> string(region.module),
            "expressions" -> array(
              region.expressions.map: expression =>
                obj(
                  "path" -> string(expression.path),
                  "operation" -> string(expression.operation),
                  "operands" -> array(expression.operands.map(string)),
                  "literal" -> optionalString(expression.literal),
                  "unit" -> optionalString(expression.unit)
                )
            ),
            "contributions" -> array(
              region.contributions.map: contribution =>
                obj(
                  "path" -> string(contribution.path),
                  "target" -> string(contribution.target),
                  "value" -> string(contribution.value),
                  "kind" -> string(contribution.kind)
                )
            )
          )
      ),
      "waivers" -> waiverInventory(snapshot)
    ) ++ Option.when(snapshot.areas.nonEmpty)(
      "areas" -> array(snapshot.areas.map(area =>
        JObject(Vector(
          "path" -> string(area.path),
          "owner" -> string(area.owner),
          "parent" -> optionalString(area.parent),
          "source" -> source(Some(area.source)),
          "domain" -> optionalString(area.domain),
          "declarations" -> array(area.declarations.map(string)),
          "members" -> array(area.members.map(member =>
            obj(
              "name" -> string(member.name),
              "path" -> string(member.path),
              "target" -> string(member.target)
            )
          ))
        ) ++ generatedScopeFields(area.generatedScope))
      ))
    ).toVector ++ Option.when(snapshot.interfaceContracts.nonEmpty)(
      "interface_contracts" -> array(snapshot.interfaceContracts.sortBy(_.path).map(endpoint =>
        obj((Vector(
          "path" -> string(endpoint.path),
          "owner" -> string(endpoint.owner),
          "identity" -> string(endpoint.identity),
          "role" -> string(endpoint.role)
        ) ++ endpoint.domain.toVector.map(value => "domain" -> string(value)) ++ Vector(
          "dimensions" -> array(endpoint.dimensions.map(string)),
          "members" -> array(endpoint.members.map(interfaceMember)),
          "roles" -> array(endpoint.roles.sortBy(_.name).map(role =>
            obj(
              "name" -> string(role.name),
              "members" -> array(role.members.map(interfaceMember))
            )
          )),
          "boundary" -> endpoint.boundary.map(interfaceBoundary).getOrElse(JNull),
          "actual_member_types" -> array(endpoint.actualMemberTypes.map((name, dataType) =>
            obj("name" -> string(name), "data_type" -> string(dataType))
          ))
        ) ++ Option.when(endpoint.definitionContexts.nonEmpty)("definition_contexts" ->
          array(endpoint.definitionContexts.map(context =>
            obj(
              "application" -> string(context.application),
              "identity" -> string(context.identity),
              "owner" -> string(context.owner),
              "region" -> string(context.region),
              "source_ordinal" -> number(context.sourceOrdinal)
            )
          ))).toVector)*)
      ))
    ).toVector ++ Option.when(snapshot.digitalConnectivity.nonEmpty)(
      "digital_connectivity" -> array(snapshot.digitalConnectivity.sortBy(_.path).map(effect =>
        obj((Vector(
          "path" -> string(effect.path),
          "owner" -> string(effect.owner),
          "kind" -> string(effect.kind),
          "target" -> string(effect.target),
          "source" -> optionalString(effect.source),
          "value" -> optionalString(effect.value),
          "enable" -> optionalString(effect.enable),
          "data_type" -> optionalString(effect.dataType),
          "member" -> optionalString(effect.member),
          "role" -> optionalString(effect.role),
          "source_role" -> optionalString(effect.sourceRole),
          "access" -> optionalString(effect.access),
          "source_access" -> optionalString(effect.sourceAccess),
          "nested_roles" -> interfaceRoles(effect.nestedRoles),
          "source_nested_roles" -> interfaceRoles(effect.sourceNestedRoles),
          "endpoint_role" -> optionalString(effect.endpointRole),
          "source_endpoint_role" -> optionalString(effect.sourceEndpointRole)
        ) ++ effect.region.toVector.map(region => "region" -> string(region)) ++
          effect.sourceOrdinal.toVector.map(ordinal => "source_ordinal" -> number(ordinal)))*)
      ))
    ).toVector ++ Option.when(nodeSelections.nonEmpty)(
      "node_array_selections" -> array(nodeSelections.map: selection =>
        obj((Vector(
          "path" -> string(selection.path),
          "owner" -> string(selection.owner),
          "input" -> string(selection.input),
          "indices" -> array(selection.indices.map(string)),
          "source_uses" -> array(selection.sourceUses.sorted.map(string))
        ) ++ generatedScopeFields(selection.generatedScope))*))
    ).toVector ++
      Option.when(snapshot.modules.exists(_.instances.exists(_.generatedScope.nonEmpty)) ||
        snapshot.topology.exists(_.generatedScope.nonEmpty) ||
        nodeSelections.exists(_.generatedScope.nonEmpty) ||
        snapshot.modules.exists(_.declarations.exists(_.generatedScope.nonEmpty)))(
        "generated_topology_scopes" -> array(snapshot.generatedRegions.sortBy(_.path).map(region =>
          obj(
            "path" -> string(region.path),
            "owner" -> string(region.owner),
            "parent" -> optionalString(region.parent),
            "induction" -> string(region.induction),
            "lower" -> string(region.lower),
            "upper_exclusive" -> string(region.upperExclusive),
            "step" -> string(region.step),
            "maximum_trip_count" -> number(region.maximumTripCount),
            "declarations" -> array(region.declarations.map(string))
          )
        ))
      ).toVector ++ conservativeBindingGraph(snapshot) ++
      Option.when(snapshot.conservativeArrayBindings.isEmpty &&
        snapshot.instanceArrayRoots.isEmpty &&
        snapshot.parameterExpressions.nonEmpty)(parameterExpressionInventory(snapshot)).toVector)

  private def generatedSelection(value: KernelGeneratedMemberSnapshot): JsonValue =
    obj((Vector(
      "family" -> string(value.family),
      "ordinal" -> string(value.ordinal),
      "member" -> string(value.member),
      "access" -> string(value.access)
    ) ++
      value.familyInput.toVector.map(input => "family_input" -> string(input)) ++
      Option.when(value.selectionPrefix.nonEmpty)(
        "selection_prefix" -> array(value.selectionPrefix.map(frame =>
          obj(
            "family" -> string(frame.family),
            "ordinal" -> string(frame.ordinal),
            "member" -> string(frame.member)
          )
        ))
      ).toVector)*)

  private def interfaceBoundary(value: KernelInterfaceBoundarySnapshot): JsonValue =
    obj((value.instance.toVector.map(instance => "instance" -> string(instance)) ++
      Vector("endpoint" -> string(value.endpoint)) ++
      value.selection.toVector.map(selection => "selection" -> generatedSelection(selection)) ++
      value.region.toVector.map(region => "region" -> string(region)))*)

  private def resolvedMember(value: KernelInterfaceMemberOrigin): JsonValue =
    obj(
      "endpoint" -> string(value.endpoint),
      "member" -> string(value.member),
      "role" -> string(value.role)
    )

  private def interfaceRoles(values: Vector[(String, String)]): JsonValue =
    array(values.map((path, role) => obj("path" -> string(path), "role" -> string(role))))

  private def interfaceMember(value: KernelInterfaceMemberSnapshot): JsonValue =
    obj(
      "path" -> string(value.path),
      "name" -> string(value.name),
      "role" -> string(value.role),
      "access" -> string(value.access),
      "data_type" -> string(value.dataType),
      "protocol" -> string(value.protocol),
      "mode" -> optionalString(value.mode),
      "nested_roles" -> interfaceRoles(value.nestedRoles),
      "nested_interfaces" -> array(value.nestedInterfaces.map((path, identity) =>
        obj("path" -> string(path), "identity" -> string(identity))
      )),
      "analog_attributes" -> attributes(value.analogAttributes)
    )

  private def analogSemantics(snapshot: ConstructionSnapshot): JsonValue =
    val equations = snapshot.analogSemantics.equations.map: equation =>
      obj(
        "kind" -> string(if equation.initialOnly then "initial_equation" else "equation"),
        "identity" -> string(equation.identity.value),
        "authored_left" -> string(equation.residual.authoredLeft.rendered),
        "authored_right" -> string(equation.residual.authoredRight.rendered),
        "dimension" -> string(equation.residual.authoredLeft.dimension),
        "analyses" -> array(equation.metadata.analyses.toVector.sorted.map(string)),
        "continuity" -> string(equation.metadata.continuity),
        "guard" -> optionalString(equation.metadata.guard.map(_.rendered)),
        "owner" -> string(equation.metadata.owner),
        "source" -> obj(
          "path" -> string(equation.metadata.source.file),
          "line" -> number(equation.metadata.source.line),
          "column" -> number(equation.metadata.source.column)
        ),
        "residual_convention" -> string(equation.residual.canonicalConvention),
        "causally_oriented" -> bool(equation.residual.causallyOriented),
        "divided" -> bool(equation.residual.divided)
      )
    val contributions = snapshot.analogSemantics.contributions.flatMap: bucket =>
      bucket.terms.map: contribution =>
        obj(
          "kind" -> string("contribution"),
          "identity" -> string(contribution.identity.value),
          "target_identity" -> string(bucket.target.identity),
          "target_kind" -> string(bucket.target.kind.toString.toLowerCase),
          "target_dimension" -> string(bucket.target.dimension),
          "target_orientation" -> string(bucket.target.orientation),
          "value" -> string(contribution.value.rendered),
          "analyses" -> array(contribution.metadata.analyses.toVector.sorted.map(string)),
          "continuity" -> string(contribution.metadata.continuity),
          "guard" -> optionalString(contribution.metadata.guard.map(_.rendered)),
          "owner" -> string(contribution.metadata.owner),
          "source" -> obj(
            "path" -> string(contribution.metadata.source.file),
            "line" -> number(contribution.metadata.source.line),
            "column" -> number(contribution.metadata.source.column)
          )
        )
    array(equations ++ contributions)

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
