package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

private object GuardedOccurrenceCases:
  val sources: Vector[(String, () => Module, Int)] = Vector(
    ("nested-family", () => new Increment58GuardedNestedFamilyOccurrenceSource, 1),
    ("default", () => new Increment58GuardedDefaultOccurrenceSource, 1),
    ("named", () => new Increment58GuardedNamedOccurrenceSource, 1),
    ("inherited", () => new Increment58GuardedInheritedOccurrenceSource, 1),
    ("resetless", () => new Increment58GuardedResetlessOccurrenceSource, 1),
    ("late-named", () => new Increment58GuardedLateNamedOccurrenceSource, 1),
    ("nested-guard", () => new Increment58GuardedNestedGuardOccurrenceSource, 1),
    ("repeated-root", () => new Increment58GuardedRepeatedRootOccurrenceSource, 2),
    ("required-forwarding", () => new Increment58GuardedRequiredForwardingOccurrenceSource, 1)
  )

object Increment58GuardedOccurrenceTests extends TestSuite:
  val tests = Tests:
    test(
      "final physical captures retain exact roots through inherited nested and forwarded scopes"
    ):
      GuardedOccurrenceCases.sources.foreach: (name, source, count) =>
        val snapshot = ConstructionKernel.inspect(source())
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        assert(snapshot.modules.flatMap(_.instances).count(_.generatedScope.nonEmpty) == count)
        assert(document.text == ScalaToMlirBridge.lower(source()).text)
        val family = snapshot.generatedRegions.head.digitalBody.get
        val controls = family.arguments.filter(argument => Set("Clock", "Reset")(argument.dataType))
        assert(controls.map(_.dataType).distinct.size == controls.size)
        assert(controls.size == (if Set("resetless", "late-named")(name) then 1 else 2))
    test("late named bindings capture only the final effective physical policy"):
      val snapshot = ConstructionKernel.inspect(new Increment58GuardedLateNamedOccurrenceSource)
      val binding =
        snapshot.modules.find(_.path == snapshot.root).get.instances.head.actualBindings.head
      assert(binding.origin == "named", binding.actual.endsWith(".other"), binding.reset.isEmpty)
      val captures =
        snapshot.structuralIfs.flatMap(value => value.trueArm.captures ++ value.falseArm.captures)
      assert(captures.count(_ == binding.clock) == 1)
      assert(!captures.exists(_.endsWith(".core_clock")))
    test("bridge rejects missing forged foreign reordered and isolation-bypassing controls"):
      val snapshot = ConstructionKernel.inspect(new Increment58GuardedDefaultOccurrenceSource)
      val original = ScalaToMlirBridge.fromSnapshot(snapshot).text
      require(original ==
        ScalaToMlirBridge.lower(new Increment58GuardedDefaultOccurrenceSource).text)
      val root = snapshot.modules.find(_.path == snapshot.root).get
      val binding = root.instances.head.actualBindings.head
      val conditional = snapshot.structuralIfs.head
      val arm = conditional.trueArm
      val clockIndex = arm.arguments.indexWhere(_.dataType == "Clock")
      val resetIndex = arm.arguments.indexWhere(_.dataType == "Reset")
      require(clockIndex >= 0 && resetIndex >= 0)
      require(arm.captures.count(_ == binding.clock) == 1)
      require(binding.reset.exists(reset => arm.captures.count(_ == reset) == 1))
      val family = snapshot.generatedRegions.head
      val body = family.digitalBody.get
      val clockBodyIndex = body.arguments.indexWhere(_.dataType == "Clock")
      val spare = root.declarations.find(_.dataType.contains("Clock")).get.copy(
        path = root.path + ".spare_clock",
        name = "spare_clock"
      )
      val withSpare = snapshot.copy(modules =
        snapshot.modules.map(module =>
          if module.path == root.path then module.copy(declarations = module.declarations :+ spare)
          else module
        )
      )
      var count = 0
      def rejected(name: String, value: ConstructionSnapshot, expected: String): Unit =
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(value)).failed.get
        require(
          failure.isInstanceOf[BridgeException] && failure.getMessage.contains(expected),
          name + ": " + failure
        )
        count += 1
        println("BRIDGE_REJECT " + name)
      def changedArm(value: KernelStructuralArmSnapshot, source: ConstructionSnapshot = snapshot) =
        source.copy(structuralIfs =
          source.structuralIfs.map(current =>
            if current.path == conditional.path then current.copy(trueArm = value) else current
          )
        )
      def changedBody(value: KernelDigitalGeneratedBody) =
        snapshot.copy(generatedRegions =
          snapshot.generatedRegions.map(current =>
            if current.path == family.path then current.copy(digitalBody = Some(value)) else current
          )
        )
      rejected(
        "same-type-nonphysical-root",
        changedArm(
          arm.copy(captures = arm.captures.updated(clockIndex, spare.path)),
          withSpare
        ),
        "actual module-owned binding root"
      )
      val childClock =
        snapshot.modules.find(_.path != root.path).get.domains.head.clockProjection.get
      rejected(
        "foreign-root",
        changedArm(arm.copy(captures = arm.captures.updated(clockIndex, childClock))),
        "exact lexical scope"
      )
      rejected(
        "wrong-control-type",
        changedArm(arm.copy(arguments =
          arm.arguments.updated(
            clockIndex,
            arm.arguments(clockIndex).copy(dataType = "Reset")
          )
        )),
        "exact capture role"
      )
      rejected(
        "reordered-control-operands",
        changedArm(arm.copy(captures =
          arm.captures
            .updated(
              clockIndex,
              arm.captures(resetIndex)
            ).updated(resetIndex, arm.captures(clockIndex))
        )),
        "exact capture role"
      )
      rejected(
        "missing-generated-reset",
        changedBody(body.copy(
          arguments = body.arguments.filterNot(_.dataType == "Reset"),
          captures = body.captures.zipWithIndex.filterNot((_, index) =>
            body.arguments(index + 1).dataType == "Reset"
          ).map(_._1)
        )),
        "exact scoped capture"
      )
      rejected(
        "bypassed-isolation",
        changedBody(body.copy(captures = body.captures.updated(clockBodyIndex - 1, binding.clock))),
        "exact lexical scope"
      )
      assert(count == 6)

object Increment58GuardedOccurrenceNativeTests extends TestSuite:
  private def gate(name: String, text: String, accepted: Boolean): Unit =
    val directory =
      Path.of(sys.env("NODAL_ROOT"), ".work", "f58-guarded-occurrence-tests", name).toAbsolutePath
    val _ = Files.createDirectories(directory)
    val _ = Files.writeString(directory.resolve("input.mlir"), text)
    val sha = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x").mkString
    val result = NativeCompilerClient.run(
      NodalMlirDocument(ScalaToMlirBridge.Schema, ScalaToMlirBridge.Version, text, sha),
      NativeCompilerRequest(
        Path.of(sys.env("NODAL_NODALC")).toAbsolutePath,
        Vector("--nodal-gate-default"),
        directory
      )
    )
    val _ = Files.writeString(directory.resolve("diagnostics.txt"), result.standardError)
    assert(result.isInstanceOf[NativeCompilerSuccess] == accepted)
    if !accepted then assert(result.standardError.contains("NODAL-"))

  val tests = Tests:
    test("nine actual guarded control profiles pass mandatory native ownership and domain gates"):
      GuardedOccurrenceCases.sources.foreach: (name, source, _) =>
        gate(name, ScalaToMlirBridge.lower(source()).text, true)
    test("native independently rejects raw isolated edges wrong control kinds and physical roots"):
      val text = ScalaToMlirBridge.lower(new Increment58GuardedDefaultOccurrenceSource).text
      val binding = text.linesIterator.find(_.contains("\"nodal.instance_domain_binding\"(")).get
      val local = text.linesIterator.find(_.contains("\"nodal.local_domain_binding\"(")).get
      def operands(line: String): String = line.substring(line.indexOf('(') + 1, line.indexOf(')'))
      gate(
        "raw-module-controls",
        text.replace(
          binding,
          binding.replace(
            "(" + operands(binding) + ")",
            "(" + operands(local) + ")"
          )
        ),
        false
      )
      val swapped = binding.replace(
        "(" + operands(binding) + ")",
        "(" + operands(binding).split(", ").reverse.mkString(", ") + ")"
      )
        .replace(
          "(!nodal.capability<\"clock\">, !nodal.capability<\"reset\">)",
          "(!nodal.capability<\"reset\">, !nodal.capability<\"clock\">)"
        )
      gate("swapped-control-kinds", text.replace(binding, swapped), false)
      val late = ScalaToMlirBridge.lower(new Increment58GuardedLateNamedOccurrenceSource).text
      val locals = late.linesIterator.filter(_.contains("\"nodal.local_domain_binding\"(")).toVector
      val core = operands(locals.find(_.contains("domain = @\"core\"")).get).split(", ").head
      val other = operands(locals.find(_.contains("domain = @\"other\"")).get)
      val arm = late.linesIterator.find(_.contains("\"nodal.structural_if\"(")).get
      gate("wrong-physical-clock-root", late.replace(arm, arm.replace(other, core)), false)
