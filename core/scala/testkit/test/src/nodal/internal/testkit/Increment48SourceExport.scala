package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Duration

import nodal.*
import nodal.increment42fixture.*
import nodal.increment44fixture.AnalogContextSource
import nodal.increment46fixture.{AnalogLintSafeSource, AnalogLintSource}
import nodal.increment47fixture.{AnalogCapabilityHierarchy, AnalogCapabilityStorage}
import nodal.internal.bridge.*
import nodal.internal.integrations.*

/** Complete public-source witnesses. Consumer rejection never selects a smaller graph. */
private[testkit] object Increment48PublicSources:
  final case class Witness(
      name: String,
      construct: () => Module,
      modulePaths: Vector[String],
      sourceFile: String
  )

  val all: Vector[Witness] = Vector(
    Witness(
      "rc",
      () => new RcFilter,
      Vector("RcFilter"),
      "core/scala/testkit/test/src/nodal/internal/testkit/RcVerticalSliceTests.scala"
    ),
    Witness(
      "storage",
      () => new AnalogCapabilityStorage,
      Vector("AnalogCapabilityStorage"),
      "examples/continuousTimeApi/src/nodal/increment47fixture/Increment47ConstructionCheck.scala"
    ),
    Witness(
      "hierarchy",
      () => new AnalogCapabilityHierarchy,
      Vector("AnalogCapabilityHierarchy", "AnalogCapabilityHierarchy.child"),
      "examples/continuousTimeApi/src/nodal/increment47fixture/Increment47ConstructionCheck.scala"
    ),
    Witness(
      "hierarchy-equation",
      () => new HierarchyEquationTop,
      Vector("HierarchyEquationTop", "HierarchyEquationTop.child"),
      "examples/continuousTimeApi/src/nodal/increment42fixture/Increment42PredecessorCombinations.scala"
    ),
    Witness(
      "hierarchy-function",
      () => new HierarchyFunctionTop,
      Vector("HierarchyFunctionTop", "HierarchyFunctionTop.child"),
      "examples/continuousTimeApi/src/nodal/increment42fixture/Increment42PredecessorCombinations.scala"
    ),
    Witness(
      "hierarchy-repeated",
      () => new HierarchyRepeatedTop,
      Vector("HierarchyRepeatedTop") ++ Vector("first", "second", "third", "fourth")
        .map(name => s"HierarchyRepeatedTop.$name"),
      "examples/continuousTimeApi/src/nodal/increment42fixture/Increment42PredecessorCombinations.scala"
    ),
    Witness(
      "hierarchy-nested",
      () => new HierarchyNestedTop,
      Vector(
        "HierarchyNestedTop",
        "HierarchyNestedTop.firstBranch",
        "HierarchyNestedTop.firstBranch.leaf",
        "HierarchyNestedTop.secondBranch",
        "HierarchyNestedTop.secondBranch.leaf"
      ),
      "examples/continuousTimeApi/src/nodal/increment42fixture/Increment42PredecessorCombinations.scala"
    ),
    Witness(
      "hierarchy-event",
      () => new HierarchyEventTop,
      Vector("HierarchyEventTop", "HierarchyEventTop.child"),
      "examples/continuousTimeApi/src/nodal/increment42fixture/Increment42PredecessorCombinations.scala"
    ),
    Witness(
      "context",
      () => new AnalogContextSource,
      Vector("AnalogContextSource"),
      "examples/continuousTimeApi/src/nodal/increment44fixture/Increment44ConstructionCheck.scala"
    ),
    Witness(
      "safe-function",
      () => new AnalogLintSafeSource,
      Vector("AnalogLintSafeSource"),
      "examples/continuousTimeApi/src/nodal/increment46fixture/Increment46ConstructionCheck.scala"
    ),
    Witness(
      "ceil-function",
      () => new AnalogLintSource,
      Vector("AnalogLintSource"),
      "examples/continuousTimeApi/src/nodal/increment46fixture/Increment46ConstructionCheck.scala"
    ),
    Witness(
      "persistent-array",
      () => new Increment43WritablePersistentSymbolic,
      Vector("Increment43WritablePersistentSymbolic"),
      "core/scala/testkit/test/src/nodal/internal/testkit/Increment43WritableArrayTests.scala"
    )
  )

  def capture(witness: Witness): ConstructionSnapshot =
    val options = EmitOptions(backend = Backend.VerilogA)
    val first = ConstructionKernel.inspect(witness.construct(), options)
    val second = ConstructionKernel.inspect(witness.construct(), options)
    // Typed equation roots carry per-construction object identity. Compare the canonical
    // semantic snapshot and exact bridge bytes, rather than raw case-class equality.
    if first != second then
      val differing =
        first.productElementNames.zip(first.productIterator.zip(second.productIterator))
          .collect { case (name, (left, right)) if left != right => name }.toVector
      println(
        s"F48 ${witness.name}: raw capture identity differs in ${differing.mkString(",")}; verifying canonical semantics"
      )
    require(
      ReproducibilityContract.canonicalSnapshot(first) ==
        ReproducibilityContract.canonicalSnapshot(second),
      s"${witness.name}: unstable canonical public source capture"
    )
    require(
      ScalaToMlirBridge.fromSnapshot(first, Backend.VerilogA, sourceProvenance = true) ==
        ScalaToMlirBridge.fromSnapshot(second, Backend.VerilogA, sourceProvenance = true),
      s"${witness.name}: unstable exact public-source MLIR"
    )
    require(
      first.sourceMap.exists(entry =>
        entry.semanticPath == first.root && entry.source.path == witness.sourceFile
      ),
      s"${witness.name}: authored source file did not resolve uniquely"
    )
    require(
      first.modules.map(_.path).sorted == witness.modulePaths.sorted,
      s"${witness.name}: incomplete public module graph"
    )
    require(
      first.modules.map(_.instances.size).sum == witness.modulePaths.size - 1,
      s"${witness.name}: incomplete public instance graph"
    )
    first

/** Runs the production bridge/native tools and pinned adapter without target rewriting.
  *
  * Arguments: output-directory nodalc translator openvaf profile-id source-head-sha. The revision
  * identifies the supplied checkout HEAD; generated evidence explicitly describes a working-tree
  * replay, not a claim that the exercised tree is clean or published.
  */
object Increment48SourceExport:
  private def bytes(value: String): Vector[Byte] = value.getBytes(StandardCharsets.UTF_8).toVector
  private def digest(value: String): String = OpenVafDiagnosticMapper.sha256(bytes(value))
  private def json(value: String): String = "\"" + value.flatMap {
    case '"' => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case char if char < ' ' => f"\\u${char.toInt}%04x"
    case char => char.toString
  } + "\""
  private def write(directory: Path, filename: String, value: String): Unit =
    val _ = Files.writeString(
      directory.resolve(filename),
      value,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE_NEW
    )

  def main(arguments: Array[String]): Unit =
    require(
      arguments.length == 6,
      "expected output-directory nodalc translator openvaf profile-id source-head-sha"
    )
    val root = Path.of(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val directory = Files.createTempDirectory(root, "source-replay-")
    val nodalc = Path.of(arguments(1)).toAbsolutePath.normalize()
    val translator = Path.of(arguments(2)).toAbsolutePath.normalize()
    val executable = Path.of(arguments(3)).toAbsolutePath.normalize()
    val profile = arguments(4)
    val sourceRevision = arguments(5)
    require(sourceRevision.matches("[a-fA-F0-9]{40}"), "expected actual full Git HEAD SHA")
    require(OpenVafProfiles.all.exists(_.id == profile), "unknown pinned compiler profile")
    val rows = scala.collection.mutable.ArrayBuffer.empty[String]
    val failures = scala.collection.mutable.ArrayBuffer.empty[String]
    val compiled = scala.collection.mutable.Map.empty[String, OpenVafCompileResult]
    val generated = scala.collection.mutable.Map.empty[String, (String, String)]
    def repositoryRoot(path: Path): Path =
      if Files.isRegularFile(path.resolve("build.mill")) then path
      else
        val parent = Option(path.getParent)
        require(parent.nonEmpty, "evidence directory must be under the Nodal repository")
        repositoryRoot(parent.get)
    val repository = repositoryRoot(directory)
    val sourceFiles =
      (Increment48PublicSources.all.map(_.sourceFile) ++ Vector(
        "core/scala/testkit/test/src/nodal/internal/testkit/Increment48SourceExport.scala",
        "core/scala/testkit/test/src/nodal/internal/testkit/Increment48SourceTests.scala",
        "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
        "core/scala/bridge/src/nodal/bridge/NativeCompilerClient.scala",
        "core/integrations/src/nodal/internal/integrations/OpenVafCompiler.scala",
        "core/integrations/src/nodal/internal/integrations/OpenVafDiagnosticMapper.scala",
        "core/integrations/src/nodal/internal/integrations/OpenVafProfile.scala",
        "core/integrations/src/nodal/internal/integrations/OpenVafProfiles.scala",
        "core/integrations/src/nodal/internal/integrations/ToolProcessRunner.scala",
        "core/compiler/backend-profiles-v0.1.json",
        "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
        "core/compiler/lib/Backend/Backend.cpp",
        "core/compiler/lib/Support/DiagnosticSupport.cpp"
      )).distinct.sorted
    val retainedSources = sourceFiles.map: relative =>
      val data = Files.readAllBytes(repository.resolve(relative))
      val retained = directory.resolve("source-files").resolve(relative + ".bytes")
      Files.createDirectories(retained.getParent)
      Files.write(retained, data, StandardOpenOption.CREATE_NEW)
      s"""{"path":${json(relative)},"retainedPath":${json("source-files/" + relative +
          ".bytes")},"sha256":${json(
          OpenVafDiagnosticMapper.sha256(data.toVector)
        )},"bytes":${data.length}}"""
    write(directory, "source-files.json", retainedSources.mkString("[", ",", "]\n"))
    write(
      directory,
      "run.json",
      s"""{"schema":"nodal.f048.source-replay-v1","sourceRevision":${json(sourceRevision)},"sourceState":"working-tree replay; HEAD does not certify a clean or published exercised tree","profileId":${json(
          profile
        )},"graphPolicy":"whole captured public model; no flattening, leaf extraction, or semantic rewriting","nodalc":${json(
          nodalc.toString
        )},"translator":${json(translator.toString)}}
"""
    )

    def compileCase(name: String, mlir: String, model: String, note: String): Unit =
      val caseDirectory = Files.createDirectory(directory.resolve(name))
      write(caseDirectory, "normalized.mlir", mlir)
      write(caseDirectory, "model.va", model)
      write(
        caseDirectory,
        "case.json",
        s"""{"case":${json(name)},"construction":${json(note)},"sourceRevision":${json(
            sourceRevision
          )},"normalizedMlirSha256":${json(digest(mlir))},"modelSha256":${json(digest(model))}}
"""
      )
      val result = OpenVafCompiler.compile(OpenVafCompileRequest(
        modelBytes = bytes(model),
        logicalFilename = "model.va",
        executable = executable,
        evidenceDirectory = caseDirectory,
        profileId = profile,
        compileTimeout = Duration.ofSeconds(60),
        sourceMap = Some(OpenVafSourceMapBinding(digest(model))),
        provenance = OpenVafProvenance(Some(sourceRevision), Some(digest(mlir)))
      ))
      compiled(name) = result
      write(caseDirectory, "compiler-result.json", OpenVafCompiler.receiptJson(result))
      result.sourceMapping match
        case OpenVafSourceMapping.Valid(_, _) => ()
        case other => failures += s"$name: source mapping $other"
      if !Set(OpenVafOutcome.Compiled, OpenVafOutcome.CompilerError, OpenVafOutcome.CompilerCrash)
          .contains(result.outcome)
      then failures += s"$name: ${result.outcome}: ${result.message}"
      val hash = result.artifact.map(_.sha256).getOrElse("")
      val exit = result.compilation.flatMap(_.exitCode).map(_.toString).getOrElse("")
      rows += s"$name\t${result.outcome}\t$exit\t${digest(model)}\t$hash"
      println(s"F48 $name: ${result.outcome}; evidence=$caseDirectory; artifact-sha256=$hash")

    def checkTargetGraph(name: String, mlir: String, target: String, path: Path): Unit =
      val symbol = "sym_name = \"([^\"]+)\"".r
      val moduleReference = "module = @([A-Za-z_][A-Za-z0-9_$]*)".r
      val definitions = mlir.linesIterator.filter(_.contains("\"nodal.module\"("))
        .flatMap(line => symbol.findFirstMatchIn(line).map(_.group(1))).toVector
      val emittedDefinitions = "(?m)^module ([A-Za-z_][A-Za-z0-9_$]*)(?:\\(|;)".r
        .findAllMatchIn(target).map(_.group(1)).toVector
      val instances = mlir.linesIterator.filter(_.contains("\"nodal.instance\"("))
        .flatMap: line =>
          for
            child <- moduleReference.findFirstMatchIn(line).map(_.group(1))
            instance <- symbol.findFirstMatchIn(line).map(_.group(1))
          yield child -> instance
        .toVector
      val retained = instances.forall: (child, instance) =>
        val pattern =
          ("(?m)^\\s*" + java.util.regex.Pattern.quote(child) +
            "(?:\\s*#\\(.*\\))?\\s+" + java.util.regex.Pattern.quote(instance) + "\\(.*\\);$").r
        pattern.findFirstIn(target).nonEmpty
      val complete = definitions.nonEmpty && definitions.sorted == emittedDefinitions.sorted &&
        definitions.distinct.size == definitions.size && retained
      if !complete then
        failures += s"$name: emitted target did not retain complete module definitions/instances"
      write(
        path,
        "target-graph.json",
        s"""{"complete":$complete,"normalizedModuleSymbols":${definitions.map(json).mkString(
            "[",
            ",",
            "]"
          )},"targetModuleSymbols":${emittedDefinitions.map(json).mkString(
            "[",
            ",",
            "]"
          )},"normalizedInstanceCount":${instances.size},"allInstancesRetained":$retained}
"""
      )

    def runNative(
        name: String,
        document: NodalMlirDocument,
        tool: Path,
        args: Vector[String],
        caseDirectory: Path
    ): Option[NativeCompilerSuccess] =
      val result = NativeCompilerClient.run(
        document,
        NativeCompilerRequest(tool, args, caseDirectory, timeout = Duration.ofSeconds(60))
      )
      write(caseDirectory, s"$name.stdout.log", result.standardOutput)
      write(caseDirectory, s"$name.stderr.log", result.standardError)
      write(caseDirectory, s"$name.command.json", result.command.map(json).mkString("[", ",", "]"))
      result match
        case success: NativeCompilerSuccess => Some(success)
        case failure: NativeCompilerFailure =>
          val reason = failure.diagnostic.toString
          write(
            caseDirectory,
            s"$name.failure.json",
            s"""{"diagnostic":${json(
                reason
              )},"exitCode":${failure.exitCode.map(_.toString).getOrElse("null")}}"""
          )
          failures += s"${caseDirectory.getFileName}: $reason"
          rows +=
            s"${caseDirectory.getFileName}\tNativeFailure\t${failure.exitCode.map(_.toString).getOrElse("")}\t\t"
          None

    Increment48PublicSources.all.foreach: witness =>
      val sourceDirectory = Files.createDirectory(directory.resolve(s"${witness.name}-source"))
      val snapshot = Increment48PublicSources.capture(witness)
      write(
        sourceDirectory,
        "construction.json",
        ReproducibilityContract.canonicalSnapshot(snapshot)
      )
      val source =
        ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA, sourceProvenance = true)
      write(sourceDirectory, "source.mlir", source.text)
      runNative(
        "nodalc",
        source,
        nodalc,
        Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
        sourceDirectory
      ).foreach: normalized =>
        val normalizedDocument = source.copy(
          text = normalized.normalizedMlir,
          sha256 = digest(normalized.normalizedMlir)
        )
        runNative(
          "translator",
          normalizedDocument,
          translator,
          Vector("--nodal-to-verilog-a"),
          sourceDirectory
        ).foreach: emitted =>
          checkTargetGraph(
            witness.name,
            normalized.normalizedMlir,
            emitted.normalizedMlir,
            sourceDirectory
          )
          generated(witness.name) = normalized.normalizedMlir -> emitted.normalizedMlir
          compileCase(
            witness.name,
            normalized.normalizedMlir,
            emitted.normalizedMlir,
            s"unchanged complete public source: ${witness.sourceFile}; modules=${witness.modulePaths.mkString(",")}"
          )

    generated.get("rc").foreach: (mlir, model) =>
      compileCase(
        "rc-repeat",
        mlir,
        model,
        "exact RC bytes replayed in a distinct evidence directory"
      )
      val first = compiled("rc")
      val repeat = compiled("rc-repeat")
      val sameOutcome = first.outcome == repeat.outcome
      val sameArtifact = first.artifact.map(_.sha256) == repeat.artifact.map(_.sha256)
      val sameIdentity = first.identity.requestSha256 == repeat.identity.requestSha256
      write(
        directory,
        "rc-repeat.json",
        s"""{"sameOutcome":$sameOutcome,"sameArtifactSha256":$sameArtifact,"sameRequestIdentity":$sameIdentity,"distinctEvidenceDirectories":${first.evidenceDirectory !=
            repeat.evidenceDirectory}}
"""
      )
      require(first.evidenceDirectory != repeat.evidenceDirectory, "RC evidence directory reused")
      if !sameOutcome || !sameArtifact || !sameIdentity then failures += "RC repeat differs"
      require(model.contains("<+"), "RC malformed control requires an actual contribution")
      val malformed = model.replaceFirst("<\\+", "<+ )")
      compileCase(
        "rc-malformed",
        mlir,
        malformed,
        "intentional malformed derivative: insert ')' after the first generated contribution operator; not a public-source success witness"
      )
      if compiled("rc-malformed").outcome != OpenVafOutcome.CompilerError ||
        compiled("rc-malformed").artifact.nonEmpty
      then
        failures += s"malformed control: ${compiled("rc-malformed").outcome}"

    // The older compiler has a narrower simulator-context query surface.
    val expectedCompiled = Vector("rc", "storage", "safe-function") ++
      Option.when(profile == "openvaf-reloaded-20260616-linux-x86_64")("context")
    expectedCompiled.foreach: name =>
      compiled.get(name).foreach: result =>
        if result.outcome != OpenVafOutcome.Compiled then
          failures += s"positive witness $name: ${result.outcome}"
    write(
      directory,
      "results.tsv",
      "case\toutcome\texitCode\tmodelSha256\tartifactSha256\n" + rows.mkString("\n") + "\n"
    )
    write(
      directory,
      "replay-checks.json",
      s"""{"failures":${failures.map(json).mkString(
          "[",
          ",",
          "]"
        )},"scope":"native replay and finite compiler observations; no model loading or numerical validation"}
"""
    )
    println(s"F48 public-source replay retained at $directory")
    require(failures.isEmpty, failures.mkString("; "))
