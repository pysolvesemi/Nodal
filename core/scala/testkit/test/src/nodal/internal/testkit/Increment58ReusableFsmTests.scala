package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration
import nodal.*
import nodal.internal.bridge.*
import nodal_external.{ReusableMode, ReusableTransfer}
import utest.*

object Increment58ReusableFsmTests extends TestSuite:
  private def capture(mode: String = "valid"): ConstructionSnapshot =
    ConstructionKernel.inspect(new nodal_external.Increment58ReusableFsmSource(mode))

  private def reject(body: => Any, message: String): Unit =
    val failure = scala.util.Try(body).failed.get
    assert(failure.getMessage.contains(message))

  private def graph(states: FsmState[ReusableMode]*): FsmDef[ReusableMode] =
    FsmDef(ReusableTransfer.owner, ReusableMode.Idle)(FsmFragment("states")(states*))

  val tests = Tests:
    test("immutable graph and fragments retain no runtime callback or value"):
      val state = FsmState(ReusableMode.Idle)
      val changed = state.on(ReusableTransfer.start)(ReusableMode.Run)
      assert(state.commands.isEmpty, changed.commands.size == 1)
      val first = ReusableTransfer.graph
      val second = FsmDef(ReusableTransfer.owner, ReusableMode.Idle)(
        ReusableTransfer.work,
        ReusableTransfer.completion
      )
      assert(first.fragments == second.fragments)
      assert(first.fragments.head eq ReusableTransfer.work)
      assert(first.guards == second.guards, first.actions == second.actions)
      Vector(
        classOf[FsmDef[?]],
        classOf[FsmFragment[?]],
        classOf[FsmState[?]],
        classOf[FsmGuard[?]],
        classOf[FsmAction[?]]
      ).foreach: cls =>
        // Immutable Seq implements Function1[Int, A] for indexed lookup. That does not make
        // an immutable graph vector an authored callback or permit a Function-typed field.
        assert(!cls.getDeclaredFields.exists(field =>
          classOf[Expr[?]].isAssignableFrom(field.getType) ||
            field.getType.getName.startsWith("scala.Function")
        ))

    test("two instances retain independent storage guards actions and stable bridge output"):
      val value = capture()
      assert(value.flatFsms.size == 2)
      val first = value.flatFsms(0)
      val second = value.flatFsms(1)
      assert(first.storage != second.storage)
      assert(first.transitions.head.guard != second.transitions.head.guard)
      assert(first.requestedEncoding == "compact", second.requestedEncoding == "one_hot")
      assert(first.enumIdentity == second.enumIdentity)
      assert(first.actions.map(_.phase) == Vector("transition", "entry", "active"))
      assert(first.actions.size == second.actions.size)
      val document = ScalaToMlirBridge.fromSnapshot(value)
      assert(document.text.contains("\"nodal.flat_fsm\""))
      assert(!document.text.contains("\"nodal.fsm\""))
      assert(document.text == ScalaToMlirBridge.fromSnapshot(capture()).text)
      assert(!value.sourceMap.exists(_.source.path.endsWith("FsmDefinitionRuntime.scala")))

    test("missing foreign duplicate and extra bindings reject"):
      Vector("missing", "foreign", "duplicate", "extra").foreach: mode =>
        reject(capture(mode), "NODAL-FSM-058-001")

    test("read-only instance observations retain lexical scope and driver ownership"):
      reject(capture("escape"), "NODAL-FSM-056-001")
      reject(capture("shared-write"), "NODAL-STATE-001")

    test("explicit bindings do not authorize values from another construction"):
      var first: Option[nodal_external.Increment58CrossSessionBindingsSource] = None
      ConstructionKernel.inspect {
        val source = new nodal_external.Increment58CrossSessionBindingsSource()
        first = Some(source)
        source
      }
      val failure = scala.util.Try(ConstructionKernel.inspect(
        new nodal_external.Increment58CrossSessionBindingsSource(first.get.exported)
      )).failed.get
      assert(failure.isInstanceOf[ConstructionException])

    test("lazy binding and fragment inputs are bounded before materialization"):
      reject(
        FsmBindings(LazyList.continually(null: FsmGuardBinding[ReusableMode])),
        "bounded record limit"
      )
      reject(
        FsmFragment("infinite")(LazyList.continually(FsmState(ReusableMode.Idle))*),
        "bounded record limit"
      )

    test("immutable definition uses shared structural graph admission"):
      reject(graph(FsmState(ReusableMode.Idle).terminal()), "every enum state")
      reject(
        graph(
          FsmState(ReusableMode.Idle).terminal(),
          FsmState(ReusableMode.Run).terminal(),
          FsmState(ReusableMode.Done).terminal()
        ),
        "structurally unreachable"
      )
      reject(
        graph(
          FsmState(ReusableMode.Idle).on(ReusableTransfer.start)(ReusableMode.Run).terminal(),
          FsmState(ReusableMode.Run).on(ReusableTransfer.finish)(ReusableMode.Done),
          FsmState(ReusableMode.Done).terminal()
        ),
        "terminal state has outgoing"
      )
      reject(
        graph(
          FsmState(ReusableMode.Idle),
          FsmState(ReusableMode.Run).on(ReusableTransfer.finish)(ReusableMode.Done),
          FsmState(ReusableMode.Done).terminal()
        ),
        "nonterminal state has no outgoing"
      )

    test("slot identities are exact and names cannot alias"):
      val other = FsmDefinition[ReusableMode]("other")
      reject(
        graph(
          FsmState(ReusableMode.Idle).on(FsmGuard(other, "foreign"))(ReusableMode.Run),
          FsmState(ReusableMode.Run).on(ReusableTransfer.finish)(ReusableMode.Done),
          FsmState(ReusableMode.Done).terminal()
        ),
        "foreign guard or action"
      )
      reject(
        graph(
          FsmState(ReusableMode.Idle).on(ReusableTransfer.start)(ReusableMode.Run),
          FsmState(ReusableMode.Run).on(FsmGuard(
            ReusableTransfer.owner,
            "start"
          ))(ReusableMode.Done),
          FsmState(ReusableMode.Done).terminal()
        ),
        "distinct slots"
      )
      reject(
        FsmDef(ReusableTransfer.owner, ReusableMode.Idle)(
          ReusableTransfer.work,
          ReusableTransfer.work
        ),
        "fragment identity"
      )

    test("custom local encoding remains independent of canonical enum ABI"):
      val value = ConstructionKernel.inspect(new nodal_external.Increment58CustomReusableFsmSource)
      assert(value.flatFsms.head.localCodes.map(_.code) == Vector("3", "7", "9"))
      assert(value.flatFsms.head.localWidth == 4)
      assert(ScalaToMlirBridge.fromSnapshot(value).text.contains("requested_encoding = \"custom\""))

    test("actual instance bridge forgeries remain independently rejected"):
      val value = capture()
      val machine = value.flatFsms.head
      reject(
        ScalaToMlirBridge.fromSnapshot(value.copy(flatFsms =
          value.flatFsms.updated(0, machine.copy(states = machine.states.dropRight(1)))
        )),
        "case and state inventories"
      )
      reject(
        ScalaToMlirBridge.fromSnapshot(value.copy(flatFsms =
          value.flatFsms.updated(0, machine.copy(storage = value.flatFsms(1).storage))
        )),
        "duplicate or excessive"
      )

    test("configured native executes existing flat ingress and rejects a malformed graph"):
      sys.env.get("NODAL_NODALC") match
        case None => println("SKIP reusable FSM native replay: NODAL_NODALC is not configured")
        case Some(executable) =>
          val directory = Path.of(".work", "f58-statecharts", "native").toAbsolutePath
          Files.createDirectories(directory)
          val request = NativeCompilerRequest(
            Path.of(executable).toAbsolutePath,
            Vector("--nodal-normalize-sequential"),
            directory,
            timeout = Duration.ofSeconds(90)
          )
          val document = ScalaToMlirBridge.fromSnapshot(capture())
          Files.writeString(directory.resolve("reusable.source.mlir"), document.text)
          val recovery = ScalaToMlirBridge.fromSnapshot(capture("recovery"))
          Files.writeString(directory.resolve("reusable-recovery.source.mlir"), recovery.text)
          NativeCompilerClient.run(recovery, request) match
            case success: NativeCompilerSuccess =>
              assert(!success.normalizedMlir.contains("\"nodal.sequential_assert\""))
              assert(success.normalizedMlir.contains("\"nodal.sequential_update\""))
            case failure: NativeCompilerFailure =>
              scala.util.Failure[Nothing](new java.lang.AssertionError(
                s"${failure.diagnostic}\n${failure.standardError}"
              )).get
          NativeCompilerClient.run(document, request) match
            case success: NativeCompilerSuccess =>
              assert(!success.normalizedMlir.contains("\"nodal.flat_fsm\""))
              assert(success.normalizedMlir.contains("\"nodal.sequential_update\""))
              Files.writeString(
                directory.resolve("reusable.normalized.mlir"),
                success.normalizedMlir
              )
            case failure: NativeCompilerFailure =>
              scala.util.Failure[Nothing](new java.lang.AssertionError(
                s"${failure.diagnostic}\n${failure.standardError}"
              )).get
          val malformed = document.copy(text =
            document.text.replaceFirst(
              "terminal = false",
              "terminal = true"
            )
          )
          NativeCompilerClient.run(malformed, request) match
            case failure: NativeCompilerFailure =>
              assert(failure.standardError.contains("NODAL-FSM-056-001"))
            case _: NativeCompilerSuccess => assert(false)
