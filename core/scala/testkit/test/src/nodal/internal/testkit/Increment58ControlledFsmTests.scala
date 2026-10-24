package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration
import nodal.*
import nodal.internal.bridge.*
import utest.*

object Increment58ControlledFsmTests extends TestSuite:
  private def capture(mode: String = "valid"): ConstructionSnapshot =
    ConstructionKernel.inspect(new nodal_external.Increment58ControlledFsmSource(mode))

  private def reject(body: => Any, expected: String): Unit =
    val failure = scala.util.Try(body).failed.get
    assert(failure.getMessage.contains(expected))

  val tests = Tests:
    test("controlled reuse owns distinct actual state and lifecycle storage"):
      val value = capture()
      val machines = value.flatFsms
      assert(machines.size == 2, machines.forall(_.control.nonEmpty))
      val storage =
        machines.flatMap(machine => Vector(machine.storage, machine.control.get.storage))
      assert(storage.distinct.size == 4)
      assert(machines.map(_.requestedEncoding) == Vector("compact", "one_hot"))
      val lifecycle = value.enumDefinitions.find(_.identity == "nodal.FsmStatus").get
      assert(lifecycle.width == 2)
      assert(lifecycle.cases.map(_.code) == Vector(BigInt(0), BigInt(1), BigInt(2), BigInt(3)))
      val document = ScalaToMlirBridge.fromSnapshot(value)
      assert(document.text.sliding("flat-fsm-v2".length).count(_ == "flat-fsm-v2") == 2)
      assert(!document.text.contains("\"nodal.flat_fsm_entered\""))
      assert(document.text == ScalaToMlirBridge.fromSnapshot(capture()).text)

    test("resetless lifecycle remains deliberately uninitialized"):
      val value = capture("resetless")
      val storage = value.flatFsms.flatMap(machine =>
        Vector(machine.storage, machine.control.get.storage)
      ).toSet
      value.modules.flatMap(_.declarations).filter(declaration =>
        storage(declaration.path)
      ).foreach: declaration =>
        assert(declaration.attributes.contains("initial" -> "none"))
      assert(ScalaToMlirBridge.fromSnapshot(value).text.contains("flat-fsm-v2"))

    test("controlled observations retain lexical lifetime"):
      reject(capture("escape"), "NODAL-FSM-056-001")

    test("controls cannot import a foreign construction"):
      var first: Option[nodal_external.Increment58ControlledFsmSource] = None
      ConstructionKernel.inspect {
        val value = new nodal_external.Increment58ControlledFsmSource()
        first = Some(value)
        value
      }
      val failure = scala.util.Try(ConstructionKernel.inspect(
        new nodal_external.Increment58ControlledFsmSource(previous = first.get.exported)
      )).failed.get
      assert(failure.isInstanceOf[ConstructionException])

    test("bridge rejects forged lifecycle state controls and event identities"):
      val value = capture()
      val machine = value.flatFsms.head
      val control = machine.control.get
      def forged(change: KernelFlatFsmControl): Unit =
        val snapshot =
          value.copy(flatFsms = value.flatFsms.updated(0, machine.copy(control = Some(change))))
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
        assert(failure.isInstanceOf[BridgeException])
      forged(control.copy(storage = machine.storage))
      forged(control.copy(storage = value.flatFsms(1).control.get.storage))
      forged(control.copy(restart = machine.storage))
      forged(control.copy(entered = control.exited))

    test("initial terminal graph uses the same controlled engine"):
      val value =
        ConstructionKernel.inspect(new nodal_external.Increment58InitiallyTerminalFsmSource)
      assert(value.flatFsms.head.states.size == 1, value.flatFsms.head.states.head.terminal)
      assert(ScalaToMlirBridge.fromSnapshot(value).text.contains("flat-fsm-v2"))

    test("configured native verifies actual controlled source and resetless ingress"):
      val executable = sys.env.getOrElse(
        "NODAL_NODALC",
        throw new IllegalStateException("NODAL_NODALC is required for lifecycle acceptance")
      )
      val directory =
        Path.of(".work", "f58-statecharts", "lifecycle", "source-native").toAbsolutePath
      Files.createDirectories(directory)
      Vector("valid", "resetless", "terminal").foreach: mode =>
        val snapshot = if mode == "terminal" then
          ConstructionKernel.inspect(new nodal_external.Increment58InitiallyTerminalFsmSource)
        else capture(mode)
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        Files.writeString(directory.resolve(s"$mode.source.mlir"), document.text)
        // The default gate derives an SSA-use materialization receipt. The target
        // pass validates and recomputes that receipt around normalization; the
        // standalone normalizer instead accepts the original construction form.
        Vector("--nodal-gate-default", "--nodal-normalize-sequential").foreach: pass =>
          val request = NativeCompilerRequest(
            Path.of(executable).toAbsolutePath,
            Vector(pass),
            directory,
            timeout = Duration.ofSeconds(90)
          )
          NativeCompilerClient.run(document, request) match
            case success: NativeCompilerSuccess =>
              if pass == "--nodal-normalize-sequential" then
                assert(!success.normalizedMlir.contains("\"nodal.flat_fsm\""))
                assert(success.normalizedMlir.contains("\"nodal.sequential_update\""))
              else assert(success.normalizedMlir.contains("nodal.digital.materialization"))
            case failure: NativeCompilerFailure =>
              throw new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
