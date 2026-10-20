package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration
import nodal.*
import nodal.internal.bridge.*
import utest.*

object Increment56MemoryBridgeTests extends TestSuite:
  private def sample: ConstructionSnapshot =
    ConstructionKernel.inspect(new nodal_external.Increment56MemoryOnlySource)

  private def rejected(value: ConstructionSnapshot, message: String): Unit =
    val result = scala.util.Try(ScalaToMlirBridge.fromSnapshot(value))
    assert(result.isFailure)
    assert(result.failed.get.getMessage.contains(message))

  private def changeRead(value: ConstructionSnapshot)(
      change: KernelDigitalExpressionSnapshot => KernelDigitalExpressionSnapshot
  ): ConstructionSnapshot = value.copy(digitalExpressions =
    value.digitalExpressions.map(expression =>
      if expression.operation == "memory_read" then change(expression) else expression
    )
  )

  private def changeMemory(value: ConstructionSnapshot)(
      change: KernelDeclarationSnapshot => KernelDeclarationSnapshot
  ): ConstructionSnapshot = value.copy(modules =
    value.modules.map(module =>
      module.copy(declarations =
        module.declarations.map(declaration =>
          if declaration.kind == "memory" then change(declaration) else declaration
        )
      )
    )
  )

  val tests: Tests = Tests:
    test("public typed ports use one declaration and shared regional statement ordering"):
      val value = ConstructionKernel.inspect(new nodal_external.Increment56MemoryBridgeSource)
      val document = ScalaToMlirBridge.fromSnapshot(value)
      assert(value.memoryWrites.size == 7)
      assert(value.digitalExpressions.count(_.operation == "memory_read") == 1)
      assert(document.text.contains("\"nodal.memory_read\""))
      assert(document.text.contains("\"nodal.memory_write\""))
      assert(document.text.contains("!nodal.uint<32>, !nodal.uint<4>"))
      assert(document.text.contains("\"nodal.behavioral_if\""))
      assert(document.text.contains("\"nodal.flat_fsm\""))
      assert(document.text.contains("\"nodal.enum_switch\""))
      assert(document.text == ScalaToMlirBridge.fromSnapshot(value).text)
      val canonical = ReproducibilityContract.canonicalSnapshot(value)
      assert(canonical.contains("memory_writes"), canonical.contains("memory_read"))
      assert(canonical.contains(value.memoryWrites.head.memory))

    test("FSM control materializes the actual effective reset before regional actions"):
      val value = ConstructionKernel.inspect(new nodal_external.Increment56MemoryBridgeSource)
      val machine = value.flatFsms.head
      val domain = value.modules.head.domains.find(_.path == machine.domain).get
      val fields = domain.attributes.toMap
      val effectiveReset = fields("effective_reset_source")
      val rawReset = fields("reset_source")
      assert(effectiveReset != rawReset)
      val lines = ScalaToMlirBridge.fromSnapshot(value).text.linesIterator.toVector
      val reset = lines.indexWhere(line =>
        line.contains("\"nodal.reset_boundary\"") &&
          line.contains(s"semantic_path = \"$effectiveReset\"")
      )
      val fsm = lines.indexWhere(_.contains("\"nodal.flat_fsm\""))
      val switch = lines.indexWhere(_.contains("\"nodal.enum_switch\""))
      assert(reset >= 0, reset < fsm, fsm < switch)
      val resetValue = lines(reset).trim.takeWhile(_ != ' ')
      val binding = lines.find(_.contains("\"nodal.local_domain_binding\"")).get
      assert(binding.contains(s", $resetValue)"))
      assert(lines(reset).contains("release = \"sync\""))
      assert(lines(reset).contains("release_domain = @\"root\""))

    test("authored declaration and read names cannot collide with owned write effects"):
      Vector(false, true).foreach: late =>
        val value =
          ConstructionKernel.inspect(new nodal_external.Increment56MemoryIdentitySource(late))
        val text = ScalaToMlirBridge.fromSnapshot(value).text
        assert(value.memoryWrites.map(_.path).distinct.size == 2)
        assert(text.linesIterator.count(_.contains("\"nodal.memory_write\"(")) == 2)
        assert(value.memoryWrites.forall(write =>
          !value.names.exists(_.semanticPath == write.path)
        ))
    test("memory-only module retains real clock binding without register state"):
      val value = sample
      val text = ScalaToMlirBridge.fromSnapshot(value).text
      assert(!value.modules.head.declarations.exists(_.kind == "register"))
      assert(text.contains("\"nodal.local_domain_binding\""))
      assert(text.contains("!nodal.capability<\"clock\">"))
      assert(!text.contains("!nodal.capability<\"reset\">"))

    test("unused memory cannot turn forged reset policy into executable release proof"):
      val value = sample
      val descriptor = value.copy(
        memoryWrites = Vector.empty,
        digitalExpressions = value.digitalExpressions.filterNot(_.operation == "memory_read"),
        modules = value.modules.map(module =>
          module.copy(domains =
            module.domains.map(domain =>
              domain.copy(
                resetPolicy = Some("async_assert_sync_release"),
                attributes = domain.attributes :+ ("reset_stages" -> "2")
              )
            )
          )
        )
      )
      rejected(descriptor, "reset-bearing domain has no actual reset")
      rejected(
        descriptor.copy(memoryWrites = value.memoryWrites),
        "reset-bearing domain has no actual reset"
      )

    test("all whole-element kinds retain exact scalar and recursive types"):
      val value = ConstructionKernel.inspect(new nodal_external.Increment56MemoryWholeSource)
      val text = ScalaToMlirBridge.fromSnapshot(value).text
      assert(value.memoryWrites.size == 16)
      assert(value.digitalExpressions.count(_.operation == "memory_read") == 8)
      assert(text.contains("-> i64"), text.contains("-> f64"))
      assert(text.contains("!nodal.enum"), text.contains("!nodal.struct"))
      assert(text.contains("read_latency = 2 : i64"))
      val captures = value.behavioralIfs.head.trueArm.arguments
      assert(captures.exists(value => value.dataType == "Integer" && value.role == "capture"))
      assert(captures.exists(value => value.dataType == "Real" && value.role == "capture"))

    test("constant addresses preserve their full authored widths"):
      val value = ConstructionKernel.inspect(new nodal_external.Increment56MemoryWideAddressSource)
      val text = ScalaToMlirBridge.fromSnapshot(value).text
      assert(text.contains("!nodal.uint<80>"), text.contains("!nodal.uint<128>"))
      assert(value.digitalExpressions.exists(expression =>
        expression.dataType == "UInt(128)" && expression.literal.contains("1")
      ))

    test("symbolic width depth and byte mask preserve actual parameter graphs"):
      val value = ConstructionKernel.inspect(new nodal_external.Increment56MemorySymbolicSource)
      val text = ScalaToMlirBridge.fromSnapshot(value).text
      assert(value.parameterExpressions.exists(_.operation.endsWith("mul")))
      assert(text.contains("\"nodal.parameter\""))
      assert(text.contains("\"nodal.const_parameter_ref\""))
      assert(text.linesIterator.exists(line =>
        line.contains("\"nodal.const_expr\"") && line.contains("operator_name = \"mul\"")
      ))
      assert(text.contains("\"nodal.memory_write\""))
      assert(value.modules.head.declarations.filter(_.kind == "parameter").size == 2)

    test("authored reversal changes the actual ordered operands without specializing read policy"):
      val forward = ConstructionKernel.inspect(new nodal_external.Increment56MemoryPolicySource)
      val reverse =
        ConstructionKernel.inspect(new nodal_external.Increment56MemoryPolicySource(reverse = true))
      def values(value: ConstructionSnapshot): Vector[String] =
        val expressions =
          value.digitalExpressions.map(expression => expression.path -> expression).toMap
        value.memoryWrites.sortBy(_.sourceOrdinal).map(write =>
          expressions(write.value).literal.get
        )
      assert(values(forward) == Vector("4386", "13124"))
      assert(values(reverse) == values(forward).reverse)
      assert(ScalaToMlirBridge.fromSnapshot(forward).text !=
        ScalaToMlirBridge.fromSnapshot(reverse).text)

    test("forged read identity arity ownership scope result and memory association reject"):
      val value = sample
      val read = value.digitalExpressions.find(_.operation == "memory_read").get
      val port = read.memory.get
      rejected(changeRead(value)(_.copy(memory = None)), "memory port identity")
      rejected(changeRead(value)(_.copy(operands = Vector.empty)), "module-level address")
      rejected(
        changeRead(value)(_.copy(operands = read.operands ++ read.operands)),
        "module-level address"
      )
      rejected(
        changeRead(value)(_.copy(region = Some(value.root + ".unowned"))),
        "module-level address"
      )
      rejected(changeRead(value)(_.copy(owner = value.root + ".other")), "module ownership")
      rejected(
        changeRead(value)(_.copy(memory = Some(port.copy(memory = value.root + ".absent")))),
        "owned declaration"
      )
      rejected(
        changeRead(value)(_.copy(memory = Some(port.copy(memory = read.operands.head)))),
        "not a memory"
      )
      rejected(
        changeRead(value)(_.copy(memory = Some(port.copy(domain = value.root + ".foreign")))),
        "declaration association"
      )
      rejected(changeRead(value)(_.copy(dataType = "UInt(7)")), "declared element result type")
      rejected(changeRead(value)(_.copy(literal = Some("0"))), "module-level address")
      rejected(changeRead(value)(_.copy(enumIdentity = Some("forged"))), "module-level address")
      rejected(changeMemory(value)(_.copy(kind = "wire")), "not a memory")

    test("forged write owner operands types order identities and pure scopes reject"):
      val value = sample
      val write = value.memoryWrites.head
      def changed(next: KernelMemoryWriteSnapshot): ConstructionSnapshot =
        value.copy(memoryWrites = Vector(next))
      rejected(changed(write.copy(owner = value.root + ".foreign")), "effect scope")
      rejected(changed(write.copy(address = value.root + ".absent")), "exact lexical scope")
      rejected(changed(write.copy(value = write.address)), "element value types")
      rejected(
        changed(write.copy(mask =
          value.modules.head.declarations.find(_.dataType.contains("Clock")).get.path
        )),
        "element value types"
      )
      rejected(changed(write.copy(sourceOrdinal = -1)), "source order")
      rejected(changed(write.copy(region = Some(value.root + ".pure"))), "effect scope")
      rejected(changed(write.copy(path = write.address)), "effect scope")
      rejected(value.copy(memoryWrites = Vector(write, write)), "duplicate or excessive")
      rejected(
        value.copy(memoryWrites = Vector(write, write.copy(path = write.path + "_other"))),
        "distinct source ordinals"
      )
      val scoped = ConstructionKernel.inspect(new nodal_external.Increment56MemoryBridgeSource)
      val regionWrite = scoped.memoryWrites.find(_.region.nonEmpty).get
      rejected(
        scoped.copy(memoryWrites =
          scoped.memoryWrites.map(current =>
            if current == regionWrite then current.copy(address = write.address) else current
          )
        ),
        "exact lexical scope"
      )
      val primitive = value.digitalExpressions.find(_.literal.nonEmpty).get
      rejected(
        value.copy(digitalExpressions =
          value.digitalExpressions.map(current =>
            if current == primitive then
              current.copy(memory = value.digitalExpressions.find(_.memory.nonEmpty).get.memory)
            else current
          )
        ),
        "only memory reads"
      )

    test("forged physical clock missing type and memory domain association reject"):
      val value = sample
      val module = value.modules.head
      val domain = module.domains.head
      def clock(path: String): ConstructionSnapshot = value.copy(modules =
        Vector(module.copy(
          domains = Vector(domain.copy(attributes =
            domain.attributes.map((key, current) =>
              key -> (if key == "clock_source" then path else current)
            )
          ))
        ))
      )
      rejected(clock(value.root + ".absent"), "actual owned declaration")
      rejected(clock(value.memoryWrites.head.address), "typed input boundary")
      rejected(
        changeMemory(value)(_.copy(domain = Some(value.root + ".absent"))),
        "exactly one owned local domain"
      )

    test("direct requirement binding resolves the actual local port domain independently"):
      val value = sample
      val module = value.modules.head
      val domain = module.domains.head
      val requirement = KernelDomainSnapshot(
        module.path + ".required",
        "required",
        "required",
        Some(domain.path)
      )
      val remapped = changeMemory(value)(declaration =>
        declaration.copy(attributes =
          declaration.attributes.map((key, current) =>
            key -> (if key == "declared_domain" then requirement.path else current)
          )
        )
      )
      val bound = changeRead(remapped.copy(
        modules =
          remapped.modules.map(current =>
            current.copy(
              domains = current.domains :+ requirement,
              declarations = current.declarations.map(declaration =>
                if declaration.kind == "input" && declaration.domain.isEmpty then
                  declaration.copy(domain = Some(domain.path))
                else declaration
              )
            )
          ),
        memoryWrites = remapped.memoryWrites.map(_.copy(domain = requirement.path))
      ))(read => read.copy(memory = read.memory.map(_.copy(domain = requirement.path))))
      val text = ScalaToMlirBridge.fromSnapshot(bound).text
      assert(text.contains("\"nodal.domain_bind\""))
      val lines = text.linesIterator.filter(_.contains("\"nodal.memory_read\"")).toVector
      assert(lines.size == 1, lines.head.contains("domain = @\"root\""))
      assert(text.contains("\"nodal.local_domain_binding\""))
      rejected(
        bound.copy(modules =
          bound.modules.map(current =>
            current.copy(domains =
              current.domains.map(candidate =>
                if candidate == requirement then candidate.copy(binding = Some(requirement.path))
                else candidate
              )
            )
          )
        ),
        "exactly one owned local domain"
      )

    test("configured native replays public types symbolic graphs ordering and dependency cuts"):
      sys.env.get("NODAL_NODALC") match
        case None => println("SKIP memory native replay: NODAL_NODALC is not configured")
        case Some(executable) =>
          val directory = Path.of(".work", "increment56-memory-bridge").toAbsolutePath
          Files.createDirectories(directory)
          val request = NativeCompilerRequest(
            Path.of(executable).toAbsolutePath,
            Vector("--nodal-normalize-sequential"),
            directory,
            timeout = Duration.ofSeconds(90)
          )
          val positives: Vector[(String, () => Module)] = Vector(
            "byte-ordered-actions" -> (() => new nodal_external.Increment56MemoryBridgeSource),
            "whole-types" -> (() => new nodal_external.Increment56MemoryWholeSource),
            "symbolic" -> (() => new nodal_external.Increment56MemorySymbolicSource),
            "memory-only" -> (() => new nodal_external.Increment56MemoryOnlySource),
            "identity-before-write" ->
              (() => new nodal_external.Increment56MemoryIdentitySource(false)),
            "identity-after-write" ->
              (() => new nodal_external.Increment56MemoryIdentitySource(true)),
            "wide-constant-address" ->
              (() => new nodal_external.Increment56MemoryWideAddressSource),
            "synchronous-feedback" -> (() => new nodal_external.Increment56MemoryFeedbackSource(1)),
            "old-data" -> (() => new nodal_external.Increment56MemoryPolicySource),
            "new-data" ->
              (() =>
                new nodal_external.Increment56MemoryPolicySource(collision = ReadUnderWrite.NewData)
              ),
            "no-change-l1" ->
              (() =>
                new nodal_external.Increment56MemoryPolicySource(collision =
                  ReadUnderWrite.NoChange
                )
              ),
            "no-change-l2" ->
              (() => new nodal_external.Increment56MemoryPolicySource(2, ReadUnderWrite.NoChange)),
            "ordered-reverse" ->
              (() => new nodal_external.Increment56MemoryPolicySource(reverse = true)),
            "independent-addresses" ->
              (() =>
                new nodal_external.Increment56MemoryPolicySource(
                  ordering = MemoryOrdering.IndependentPorts,
                  disjoint = true
                )
              ),
            "independent-zero" ->
              (() =>
                new nodal_external.Increment56MemoryPolicySource(
                  ordering = MemoryOrdering.IndependentPorts,
                  zero = true
                )
              ),
            "async-undefined" ->
              (() => new nodal_external.Increment56MemoryPolicySource(0, ReadUnderWrite.Undefined))
          )
          positives.foreach: (name, source) =>
            val document = ScalaToMlirBridge.lower(source())
            Files.writeString(directory.resolve(name + ".source.mlir"), document.text)
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                Files.writeString(
                  directory.resolve(name + ".normalized.mlir"),
                  success.normalizedMlir
                )
                assert(success.normalizedMlir.contains("\"nodal.memory_read\""))
                if name != "synchronous-feedback" then
                  assert(success.normalizedMlir.contains("\"nodal.memory_write_effect\""))
                  assert(!success.normalizedMlir.contains("\"nodal.memory_write\""))
              case failure: NativeCompilerFailure =>
                scala.util.Failure[Nothing](new java.lang.AssertionError(
                  s"$name: ${failure.diagnostic}\n${failure.standardError}"
                )).get
          val negatives: Vector[(String, () => Module, String, String)] = Vector(
            (
              "unsafe-symbolic-address",
              () => new nodal_external.Increment56MemorySymbolicSource(true),
              "NODAL-MEMORY-056-001",
              "runtime address full UInt envelope"
            ),
            (
              "asynchronous-feedback",
              () => new nodal_external.Increment56MemoryFeedbackSource(0),
              "NODAL-DIGITAL-055-010",
              "combinational storage-read cycle"
            ),
            (
              "independent-overlap",
              () =>
                new nodal_external.Increment56MemoryPolicySource(ordering =
                  MemoryOrdering.IndependentPorts
                ),
              "NODAL-MEMORY-056-001",
              "IndependentPorts requires proved"
            ),
            (
              "async-old-data-mixed",
              () => new nodal_external.Increment56MemoryPolicySource(0, ReadUnderWrite.OldData),
              "NODAL-MEMORY-056-001",
              "asynchronous mixed read/write supports Undefined only"
            )
          )
          val sourceCdc = scala.util.Try(
            ConstructionKernel.inspect(new nodal_external.Increment56MemoryForeignSource)
          )
          assert(sourceCdc.isFailure)
          assert(sourceCdc.failed.get.getMessage.contains("NODAL-CDC-001"))
          Files.writeString(
            directory.resolve("foreign-register.capture-error.txt"),
            sourceCdc.failed.get.getMessage
          )
          def nativeRejected(
              name: String,
              document: NodalMlirDocument,
              code: String,
              message: String
          ): Unit =
            Files.writeString(directory.resolve(name + ".source.mlir"), document.text)
            NativeCompilerClient.run(document, request) match
              case failure: NativeCompilerFailure =>
                Files.writeString(directory.resolve(name + ".stderr.txt"), failure.standardError)
                assert(
                  failure.standardError.contains(code),
                  failure.standardError.contains(message)
                )
              case _: NativeCompilerSuccess =>
                scala.util.Failure[Nothing](new java.lang.AssertionError(
                  s"native unexpectedly accepted $name"
                )).get
          // Bypass source admission deliberately: native must reject actual operand provenance.
          val captured = sample
          val originalModule = captured.modules.head
          val actual = originalModule.domains.head
          val module = originalModule.copy(declarations =
            originalModule.declarations.map(declaration =>
              if declaration.kind == "input" && declaration.domain.isEmpty then
                declaration.copy(domain = Some(actual.path))
              else declaration
            )
          )
          val required = KernelDomainSnapshot(module.path + ".unbound", "unbound", "required", None)
          val requiredInput = captured.copy(modules =
            Vector(module.copy(
              domains = module.domains :+ required,
              declarations = module.declarations.map(declaration =>
                if declaration.path == captured.memoryWrites.head.value then
                  declaration.copy(domain = Some(required.path))
                else declaration
              )
            ))
          )
          val clock = module.declarations.find(_.dataType.contains("Clock")).get
          val foreignPath = module.path + ".foreign"
          val foreignClock = clock.copy(
            path = module.path + ".foreign_clock",
            name = "foreign_clock",
            domain = Some(foreignPath)
          )
          val foreign = actual.copy(
            path = foreignPath,
            name = "foreign",
            attributes = actual.attributes.map((key, value) =>
              key -> (if key == "clock_source" then foreignClock.path else value)
            )
          )
          val foreignRegister = captured.copy(modules =
            Vector(module.copy(
              domains = module.domains :+ foreign,
              declarations = module.declarations.map(declaration =>
                if declaration.path == captured.memoryWrites.head.value then
                  declaration.copy(
                    kind = "register",
                    domain = Some(foreign.path),
                    attributes = Vector(
                      "initial" -> "none",
                      "declared_domain" -> foreign.path
                    )
                  )
                else declaration
              ) :+ foreignClock
            ))
          )
          Vector(
            ("unbound-requirement", requiredInput, "foreign or unresolved input domain"),
            ("foreign-register-ingress", foreignRegister, "foreign-domain state is sampled")
          ).foreach: (name, value, message) =>
            nativeRejected(name, ScalaToMlirBridge.fromSnapshot(value), "NODAL-CDC-001", message)
          negatives.foreach: (name, source, code, message) =>
            // Construction and bridge must succeed before the native capability rejection.
            nativeRejected(name, ScalaToMlirBridge.lower(source()), code, message)
