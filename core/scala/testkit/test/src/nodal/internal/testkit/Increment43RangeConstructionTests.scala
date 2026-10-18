package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*

import utest.*

final class Increment43SymbolicGeneratedNode extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)

  hdlRange(0, lanes): _ =>
    val tap = node(Electrical)
    val _ = tap
    ()

final class Increment43LiteralGeneratedNode extends Module:
  hdlRange(0, 3): _ =>
    val tap = node(Electrical)
    val _ = tap
    ()

final class Increment43SymbolicGeneratedNodeGroup extends Module:
  val lanes: Param[Integer] = param(3.integer, range = 2 to 5)

  hdlRange(1, lanes): _ =>
    val tap = node(Electrical)
    val returnTap = node(Electrical)
    val _ = (tap, returnTap)
    ()

final class Increment43GeneratedNodeContribution extends Module:
  val lanes: Param[Integer] = param(3.integer, range = 1 to 4)
  val p = inout(Electrical)

  hdlRange(0, lanes): _ =>
    val tap = node(Electrical)
    analog:
      V(tap, p) <+ 1.0.V

final class Increment43GeneratedNodeGroupContribution extends Module:
  val lanes: Param[Integer] = param(3.integer, range = 2 to 5)
  val p = inout(Electrical)

  hdlRange(1, lanes): _ =>
    val tap = node(Electrical)
    val returnTap = node(Electrical)
    val _ = returnTap
    analog:
      V(tap, p) <+ 1.0.V

final class Increment43GeneratedNodeGroupContributions extends Module:
  val lanes: Param[Integer] = param(3.integer, range = 2 to 5)
  val p = inout(Electrical)

  hdlRange(1, lanes): _ =>
    val tap = node(Electrical)
    val returnTap = node(Electrical)
    analog:
      V(tap, p) <+ 1.0.V
      V(returnTap, p) <+ 2.0.V

final class Increment43SharedGeneratedNodeContributions extends Module:
  val lanes: Param[Integer] = param(3.integer, range = 2 to 5)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(1, lanes): _ =>
    val tap = node(Electrical)
    analog:
      V(tap, p) <+ 1.0.V
      V(tap, n) <+ 2.0.V

final class Increment43GeneratedNodePairContribution extends Module:
  val lanes: Param[Integer] = param(3.integer, range = 2 to 5)

  hdlRange(1, lanes): _ =>
    val tap = node(Electrical)
    val returnTap = node(Electrical)
    analog:
      V(tap, returnTap) <+ 1.0.V

final class Increment43GeneratedNodeResistorContribution extends Module:
  val lanes: Param[Integer] = param(7.integer, range = 6 to 10)
  val R: Param[Real] = param(1.kOhm)
  val p = inout(Electrical)

  hdlRange(1, lanes, 2): _ =>
    val tap = node(Electrical)
    analog:
      I(tap, p) <+ (V(tap, p) / R)

final class Increment43SharedGeneratedNodeResistors extends Module:
  val lanes: Param[Integer] = param(7.integer, range = 6 to 10)
  val R: Param[Real] = param(1.kOhm)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(1, lanes, 2): _ =>
    val tap = node(Electrical)
    analog:
      I(tap, p) <+ (V(tap, p) / R)
      I(tap, n) <+ (V(tap, n) / R)

final class Increment43GeneratedPeerNodeResistorContribution extends Module:
  val lanes: Param[Integer] = param(7.integer, range = 6 to 10)
  val R: Param[Real] = param(1.kOhm)

  hdlRange(1, lanes, 2): _ =>
    val tap = node(Electrical)
    val returnTap = node(Electrical)
    analog:
      I(tap, returnTap) <+ (V(tap, returnTap) / R)

final class Increment43GeneratedNodeDdtContribution extends Module:
  val lanes: Param[Integer] = param(3.integer, range = 2 to 5)
  val p = inout(Electrical)

  hdlRange(1, lanes): _ =>
    val tap = node(Electrical)
    analog:
      val voltage = V(tap, p)
      I(tap, p) <+ (1.pF * ddt(voltage))

final class Increment43StridedGeneratedPeerNodeDdtContribution extends Module:
  val lanes: Param[Integer] = param(7.integer, range = 6 to 10)

  hdlRange(1, lanes, 2): _ =>
    val tap = node(Electrical)
    val returnTap = node(Electrical)
    analog:
      val voltage = V(tap, returnTap)
      I(tap, returnTap) <+ (1.pF * ddt(voltage))

final class Increment43GeneratedNodeIdtContribution extends Module:
  val p = inout(Electrical)

  hdlRange(1, 4): _ =>
    val tap = node(Electrical)
    analog:
      val voltage = V(tap, p)
      val voltageRate = voltage / 1.0.s
      V(tap, p) <+ idt(voltageRate, 0.0.V)

final class Increment43StridedGeneratedPeerNodeIdtContribution extends Module:
  val lanes: Param[Integer] = param(7.integer, range = 6 to 10)

  hdlRange(1, lanes, 2): _ =>
    val tap = node(Electrical)
    val returnTap = node(Electrical)
    analog:
      val voltage = V(tap, returnTap)
      val voltageRate = voltage / 1.0.s
      V(tap, returnTap) <+ idt(voltageRate, 0.0.V)

final class Increment43StridedGeneratedPeerNodeScaledIdtContribution extends Module:
  val lanes: Param[Integer] = param(7.integer, range = 6 to 10)

  hdlRange(1, lanes, 2): _ =>
    val tap = node(Electrical)
    val returnTap = node(Electrical)
    analog:
      val voltage = V(tap, returnTap)
      val voltageRate = voltage / 1.0.s
      val integrated = idt(voltageRate, 0.0.V)
      V(tap, returnTap) <+ integrated / 2.0.real

final class Increment43CompoundGeneratedNodeGroupContributions extends Module:
  val lanes: Param[Integer] = param(3.integer, range = 2 to 4)
  val extra: Param[Integer] = param(1.integer, range = 1 to 2)
  val limit: Expr[Integer] = lanes + extra
  val p = inout(Electrical)

  hdlRange(1, limit): _ =>
    val tap = node(Electrical)
    val returnTap = node(Electrical)
    analog:
      V(tap, p) <+ 1.0.V
      V(returnTap, p) <+ 2.0.V

final class Increment43StridedGeneratedNodeGroupContributions extends Module:
  val lanes: Param[Integer] = param(7.integer, range = 6 to 10)
  val p = inout(Electrical)

  hdlRange(1, lanes, 2): _ =>
    val tap = node(Electrical)
    val returnTap = node(Electrical)
    analog:
      V(tap, p) <+ 1.0.V
      V(returnTap, p) <+ 2.0.V

final class Increment43MissingRange extends Module:
  val lanes: Param[Integer] = param(2.integer)

  hdlRange(0, lanes): _ =>
    ()

final class Increment43AmbiguousDirection extends Module:
  val upper: Param[Integer] = param(1.integer, range = -1 to 3)

  hdlRange(0, upper): _ =>
    ()

final class Increment43MaximumTooSmall extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)

  hdlRange(0, lanes, 1, 2): _ =>
    ()

final class Increment43UnsupportedGeneratedWire extends Module:
  hdlRange(0, 2): _ =>
    val generatedWire = wire(UInt(1))
    val _ = generatedWire
    ()

final class Increment43UnsupportedGeneratedVariable extends Module:
  hdlRange(0, 2): _ =>
    val state = variable(Real, 0.0.V)
    val _ = state
    ()

final class Increment43ForgedGeneratedVariableCarrier extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val state = variable(Real, 0.0.V)
      val forged = new Object
      CandidateRuntime.declare(
        forged,
        KernelSignalKind.Variable,
        dataType = Some(Real)
      )
      state := state + 1.0.V

final class Increment43ForgedGeneratedAssignmentCarrier extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val state = variable(Real, 0.0.V)
      ConstructionKernel.operation("assignment", state, state)

final class Increment43GeneratedLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val state = variable(Real, 0.0.V)
      state := state + 1.0.V

final class Increment43GeneratedConditionalLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val select = variable(Bool, false.B)
      val state = variable(Real, 0.0.real)
      analogConditional:
        analogWhen(select):
          state := state + 1.0.real
        analogOtherwise:
          state := state + 2.0.real

final class Increment43GeneratedCaseLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val mode = variable(Integer, 0.integer)
      val state = variable(Real, 0.0.real)
      analogCase(mode):
        analogCaseArm(0):
          state := state + 3.0.real
        analogCaseDefault:
          state := state + 4.0.real

final class Increment43GeneratedLoopLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        state := state + 5.0.real

final class Increment43GeneratedLoopExitLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        state := state + 5.0.real
        analogContinue()
        analogBreak()

final class Increment43GeneratedLoopScopeLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        CandidateRuntime.block:
          state := state + 10.0.real

final class Increment43GeneratedLoopScopeExitLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        CandidateRuntime.block:
          state := state + 10.0.real
          analogContinue()
          analogBreak()

final class Increment43GeneratedLoopConditionalLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val select = variable(Bool, false.B)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        analogConditional:
          analogWhen(select):
            state := state + 6.0.real
          analogOtherwise:
            state := state + 7.0.real

final class Increment43GeneratedLoopConditionalExitLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val select = variable(Bool, false.B)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        analogConditional:
          analogWhen(select):
            state := state + 6.0.real
            analogContinue()
          analogOtherwise:
            state := state + 7.0.real

final class Increment43GeneratedLoopConditionalBreakLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val select = variable(Bool, false.B)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        analogConditional:
          analogWhen(select):
            state := state + 16.0.real
            analogBreak()
          analogOtherwise:
            state := state + 7.0.real

final class Increment43GeneratedLoopConditionalScopeLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val select = variable(Bool, false.B)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        analogConditional:
          analogWhen(select):
            CandidateRuntime.block:
              state := state + 11.0.real
          analogOtherwise:
            state := state + 7.0.real

final class Increment43GeneratedLoopConditionalScopeExitLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val select = variable(Bool, false.B)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        analogConditional:
          analogWhen(select):
            CandidateRuntime.block:
              state := state + 11.0.real
              analogContinue()
          analogOtherwise:
            state := state + 7.0.real

final class Increment43GeneratedLoopConditionalScopeBreakLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val select = variable(Bool, false.B)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        analogConditional:
          analogWhen(select):
            CandidateRuntime.block:
              state := state + 13.0.real
              analogBreak()
          analogOtherwise:
            state := state + 7.0.real

final class Increment43GeneratedLoopCaseLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val mode = variable(Integer, 0.integer)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        analogCase(mode):
          analogCaseArm(0):
            state := state + 8.0.real
          analogCaseDefault:
            state := state + 9.0.real

final class Increment43GeneratedLoopCaseExitLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val mode = variable(Integer, 0.integer)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        analogCase(mode):
          analogCaseArm(0):
            state := state + 8.0.real
          analogCaseDefault:
            state := state + 9.0.real
            analogBreak()

final class Increment43GeneratedLoopCaseContinueLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val mode = variable(Integer, 0.integer)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        analogCase(mode):
          analogCaseArm(0):
            state := state + 8.0.real
          analogCaseDefault:
            state := state + 17.0.real
            analogContinue()

final class Increment43GeneratedLoopCaseScopeLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val mode = variable(Integer, 0.integer)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        analogCase(mode):
          analogCaseArm(0):
            CandidateRuntime.block:
              state := state + 12.0.real
          analogCaseDefault:
            state := state + 9.0.real

final class Increment43GeneratedLoopCaseScopeExitLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val mode = variable(Integer, 0.integer)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        analogCase(mode):
          analogCaseArm(0):
            CandidateRuntime.block:
              state := state + 12.0.real
              analogBreak()
          analogCaseDefault:
            state := state + 9.0.real

final class Increment43GeneratedLoopCaseScopeContinueLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val iterations = variable(Integer, 1.integer)
      val mode = variable(Integer, 0.integer)
      val state = variable(Real, 0.0.real)
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        analogCase(mode):
          analogCaseArm(0):
            CandidateRuntime.block:
              state := state + 13.0.real
              analogContinue()
          analogCaseDefault:
            state := state + 9.0.real

final class Increment43GeneratedStructuredLexicalStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val select = variable(Bool, false.B)
      val mode = variable(Integer, 0.integer)
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.real)
      analogConditional:
        analogWhen(select):
          state := state + 1.0.real
        analogOtherwise:
          state := state + 2.0.real
      analogCase(mode):
        analogCaseArm(0):
          state := state + 3.0.real
        analogCaseDefault:
          state := state + 4.0.real
      analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
        state := state + 5.0.real

final class Increment43GeneratedEventStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val state = variable(Real, 0.0.V)
      on(cross(1.0.V)):
        state := state + 1.0.V

final class Increment43GeneratedConditionalEventStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val select = variable(Bool, false.B)
      val mode = variable(Integer, 0.integer)
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.V)
      on(cross(1.0.V)):
        analogConditional:
          analogWhen(select):
            state := state + 1.0.V
          analogOtherwise:
            state := state + 2.0.V
        analogCase(mode):
          analogCaseArm(0):
            state := state + 3.0.V
          analogCaseDefault:
            state := state + 4.0.V
        analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
          analogConditional:
            analogWhen(select):
              state := state + 5.0.V
              analogContinue()
            analogOtherwise:
              state := state + 6.0.V
          analogCase(mode):
            analogCaseArm(0):
              state := state + 7.0.V
            analogCaseDefault:
              CandidateRuntime.block:
                state := state + 8.0.V
                analogBreak()
          state := state + 9.0.V

final class Increment43GeneratedConditionalContinueEventStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val select = variable(Bool, false.B)
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.V)
      on(cross(1.0.V)):
        analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
          analogConditional:
            analogWhen(select):
              state := state + 28.0.V
              analogContinue()
            analogOtherwise:
              state := state + 29.0.V
          state := state + 30.0.V

final class Increment43GeneratedConditionalBreakEventStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val select = variable(Bool, false.B)
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.V)
      on(cross(1.0.V)):
        analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
          analogConditional:
            analogWhen(select):
              state := state + 31.0.V
              analogBreak()
            analogOtherwise:
              state := state + 32.0.V
          state := state + 33.0.V

final class Increment43GeneratedConditionalScopeEventStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val select = variable(Bool, false.B)
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.V)
      on(cross(1.0.V)):
        analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
          analogConditional:
            analogWhen(select):
              CandidateRuntime.block:
                state := state + 10.0.V
                analogContinue()
            analogOtherwise:
              state := state + 11.0.V
          state := state + 12.0.V

final class Increment43GeneratedConditionalScopeBreakEventStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val select = variable(Bool, false.B)
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.V)
      on(cross(1.0.V)):
        analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
          analogConditional:
            analogWhen(select):
              CandidateRuntime.block:
                state := state + 13.0.V
                analogBreak()
            analogOtherwise:
              state := state + 14.0.V
          state := state + 15.0.V

final class Increment43GeneratedCaseScopeContinueEventStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val mode = variable(Integer, 0.integer)
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.V)
      on(cross(1.0.V)):
        analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
          analogCase(mode):
            analogCaseArm(0):
              CandidateRuntime.block:
                state := state + 16.0.V
                analogContinue()
            analogCaseDefault:
              state := state + 17.0.V
          state := state + 18.0.V

final class Increment43GeneratedCaseScopeBreakEventStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val mode = variable(Integer, 0.integer)
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.V)
      on(cross(1.0.V)):
        analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
          analogCase(mode):
            analogCaseArm(0):
              state := state + 22.0.V
            analogCaseDefault:
              CandidateRuntime.block:
                state := state + 23.0.V
                analogBreak()
          state := state + 24.0.V

final class Increment43GeneratedCaseContinueEventStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val mode = variable(Integer, 0.integer)
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.V)
      on(cross(1.0.V)):
        analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
          analogCase(mode):
            analogCaseArm(0):
              state := state + 19.0.V
            analogCaseDefault:
              state := state + 20.0.V
              analogContinue()
          state := state + 21.0.V

final class Increment43GeneratedCaseBreakEventStorage extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      val mode = variable(Integer, 0.integer)
      val iterations = variable(Integer, 1.integer)
      val state = variable(Real, 0.0.V)
      on(cross(1.0.V)):
        analogLoop(iterations, maximumIterations = 2, minimumIterations = 1):
          analogCase(mode):
            analogCaseArm(0):
              state := state + 25.0.V
            analogCaseDefault:
              state := state + 26.0.V
              analogBreak()
          state := state + 27.0.V

final class Increment43GeneratedEventTarget extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      on(cross(1.0.V)):
        ()

final class Increment43UnsupportedGeneratedContinuousOperator extends Module:
  hdlRange(0, 2): _ =>
    val state = ddt(1.0.V)
    val _ = state
    ()

final class Increment43GeneratedContinuousOperator extends Module:
  hdlRange(0, 2): _ =>
    analog:
      val derivative = ddt(1.0.V)
      val integral = idt(1.0.V)
      val _ = (derivative, integral)

final class Increment43GeneratedWaveformOperator extends Module:
  hdlRange(0, 2): _ =>
    analog:
      val smoothed = transition(1.0.V)
      val limited = slew(1.0.V)
      val delayed = absdelay(1.0.V, 1.0.ns)
      val _ = (smoothed, limited, delayed)

final class Increment43GeneratedTransferOperator extends Module:
  hdlRange(0, 2): _ =>
    analog:
      val continuous = laplaceNd(1.0.V, Seq(1.0.real), Seq(1.0.real))
      val sampled = ziNd(1.0.V, Seq(1.0.real), Seq(1.0.real), 1.0.ns)
      val _ = (continuous, sampled)

final class Increment43UnsupportedGeneratedTransferOperator extends Module:
  hdlRange(0, 2): _ =>
    val filtered = laplaceNd(1.0.V, Seq(1.0.real), Seq(1.0.real))
    val _ = filtered

final class Increment43GeneratedNoiseOperator extends Module:
  hdlRange(0, 2): _ =>
    analog:
      val density = 1.0.A * 1.0.A * 1.0.s
      val white = whiteNoise(NoiseId("generated white"), density)
      val flicker = flickerNoise(NoiseId("generated flicker"), density, 1.0)
      val table = tableNoise(
        NoiseId("generated table"),
        Seq(NoisePoint(1.0.real / 1.0.s, density))
      )
      val _ = (white, flicker, table)

final class Increment43GeneratedContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ 1.0.V
      I(p, n) <+ 1.0.A

final class Increment43GeneratedDdtContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      val voltage = V(p, n)
      I(p, n) <+ (1.pF * ddt(voltage))

final class Increment43GeneratedIdtContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      val voltageRate = 1.0.V / 1.0.s
      V(p, n) <+ idt(voltageRate, 0.0.V)

final class Increment43GeneratedTransitionContribution extends Module:
  val level: Param[Real] = param(1.0.V)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ transition(level)

final class Increment43GeneratedSlewContribution extends Module:
  val level: Param[Real] = param(1.0.V)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ slew(level)

final class Increment43GeneratedAbsdelayContribution extends Module:
  val level: Param[Real] = param(1.0.V)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ absdelay(level, 1.0.ns)

final class Increment43GeneratedAbstimeContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ ((abstime / 1.0.s) * 1.0.V)

final class Increment43GeneratedBoundStep extends Module:
  hdlRange(0, 2): _ =>
    analog:
      boundStep(1.0.ns)

final class Increment43GeneratedLaplaceContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ laplaceNd(V(p, n), Seq(1.0.real), Seq(1.0.real))

final class Increment43GeneratedZiContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      V(p, n) <+ ziNd(V(p, n), Seq(1.0.real), Seq(1.0.real), 1.0.ns)

final class Increment43GeneratedWhiteNoiseContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      val density = 1.0.A * 1.0.A * 1.0.s
      I(p, n) <+ whiteNoise(NoiseId("generated white"), density)

final class Increment43GeneratedFlickerNoiseContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      val density = 1.0.A * 1.0.A * 1.0.s
      I(p, n) <+ flickerNoise(NoiseId("generated flicker"), density, 1.0)

final class Increment43GeneratedTableNoiseContribution extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, 2): _ =>
    analog:
      val density = 1.0.A * 1.0.A * 1.0.s
      val frequency = 1.0.real / 1.0.s
      I(p, n) <+ tableNoise(
        NoiseId("generated table"),
        Seq(NoisePoint(frequency, density))
      )

final class Increment43SymbolicGeneratedContribution extends Module:
  val lanes: Param[Integer] = param(4.integer, range = 1 to 8)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, lanes): _ =>
    analog:
      V(p, n) <+ 1.0.V

final class Increment43CompoundGeneratedContribution extends Module:
  val lanes: Param[Integer] = param(3.integer, range = 1 to 4)
  val extra: Param[Integer] = param(1.integer, range = 1 to 2)
  val limit: Expr[Integer] = lanes + extra
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(0, limit): _ =>
    analog:
      V(p, n) <+ 1.0.V

final class Increment43UnsupportedGeneratedNoiseOperator extends Module:
  hdlRange(0, 2): _ =>
    val source = whiteNoise(NoiseId("unsupported generated"), 1.0.A * 1.0.A * 1.0.s)
    val _ = source

final class Increment43UnsupportedGeneratedBoundStep extends Module:
  hdlRange(0, 2): _ =>
    boundStep(1.0.ns)

final class Increment43UnsupportedGeneratedEvent extends Module:
  hdlRange(0, 2): _ =>
    val event = cross(1.0.V)
    val _ = event
    ()

final class Increment43UnsupportedGeneratedAnalogRegion extends Module:
  hdlRange(0, 2): _ =>
    analog:
      ()

final class Increment43UnsupportedGeneratedEquationRegion extends Module:
  hdlRange(0, 2): _ =>
    equations:
      ()

final class Increment43UnsupportedGeneratedInitialEquationRegion extends Module:
  hdlRange(0, 2): _ =>
    initialEquations:
      ()

final class Increment43UnsupportedGeneratedContributionRegion extends Module:
  hdlRange(0, 2): _ =>
    contributions:
      ()

final class Increment43UnsupportedGeneratedBareProceduralRegion extends Module:
  hdlRange(0, 2): _ =>
    CandidateRuntime.analogSemanticBlock(AnalogEquationRuntime.RegionKind.Procedural, ())

final class Increment43UnsupportedGeneratedProcedure extends Module:
  hdlRange(0, 2): _ =>
    analogProcedure:
      ()

final class Increment43RepeatedSymbolicGeneratedNodes extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)

  hdlRange(0, lanes): _ =>
    val first = node(Electrical)
    val _ = first
    ()

  hdlRange(0, lanes): _ =>
    hdlRange(0, lanes): _ =>
      val nested = node(Electrical)
      val _ = nested
      ()

final class Increment43IndependentStridedNestedGeneratedNodes extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      val _ = nestedTap
      ()

final class Increment43NestedGeneratedNodeResistor extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)
  val resistance: Param[Real] = param(2.kOhm)
  val p = inout(Electrical)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      analog:
        I(nestedTap, p) <+ (V(nestedTap, p) / resistance)

final class Increment43NestedGeneratedNodeUnitSteps extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)
  val p = inout(Electrical)

  hdlRange(1, rows, 1): _ =>
    hdlRange(2, columns, 1): _ =>
      val nestedTap = node(Electrical)
      analog:
        I(nestedTap, p) <+ (V(nestedTap, p) / 2.kOhm)

final class Increment43NestedGeneratedNodeOuterUnitStep extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)
  val p = inout(Electrical)

  hdlRange(1, rows, 1): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      analog:
        I(nestedTap, p) <+ (V(nestedTap, p) / 2.kOhm)

final class Increment43NestedGeneratedNodeInnerUnitStep extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)
  val p = inout(Electrical)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 1): _ =>
      val nestedTap = node(Electrical)
      analog:
        I(nestedTap, p) <+ (V(nestedTap, p) / 2.kOhm)

final class Increment43NestedPeerGeneratedNodeResistor extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)
  val resistance: Param[Real] = param(2.kOhm)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      val nestedReturn = node(Electrical)
      analog:
        I(nestedTap, nestedReturn) <+ (V(nestedTap, nestedReturn) / resistance)

final class Increment43NestedPeerGeneratedNodeDdt extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      val nestedReturn = node(Electrical)
      analog:
        I(nestedTap, nestedReturn) <+ (1.pF * ddt(V(nestedTap, nestedReturn)))

final class Increment43NestedPeerGeneratedNodeIdt extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      val nestedReturn = node(Electrical)
      analog:
        val voltage = V(nestedTap, nestedReturn)
        val voltageRate = voltage / 1.0.s
        V(nestedTap, nestedReturn) <+ idt(voltageRate, 0.0.V)

final class Increment43NestedPeerGeneratedNodeScaledIdt extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      val nestedReturn = node(Electrical)
      analog:
        val voltage = V(nestedTap, nestedReturn)
        val voltageRate = voltage / 1.0.s
        val integrated = idt(voltageRate, 0.0.V)
        V(nestedTap, nestedReturn) <+ integrated / 2.0.real

final class Increment43NestedGeneratedNodeGroupResistors extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)
  val firstResistance: Param[Real] = param(2.kOhm)
  val secondResistance: Param[Real] = param(3.kOhm)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      val nestedReturn = node(Electrical)
      analog:
        I(nestedTap, p) <+ (V(nestedTap, p) / firstResistance)
        I(nestedReturn, n) <+ (V(nestedReturn, n) / secondResistance)

final class Increment43NestedGeneratedNodeGroupIdt extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)
  val secondResistance: Param[Real] = param(3.kOhm)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      val nestedReturn = node(Electrical)
      analog:
        val voltage = V(nestedTap, p)
        val voltageRate = voltage / 1.0.s
        V(nestedTap, p) <+ idt(voltageRate, 0.0.V)
        I(nestedReturn, n) <+ (V(nestedReturn, n) / secondResistance)

final class Increment43NestedGeneratedNodeGroupDdt extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)
  val secondResistance: Param[Real] = param(3.kOhm)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      val nestedReturn = node(Electrical)
      analog:
        I(nestedTap, p) <+ (1.pF * ddt(V(nestedTap, p)))
        I(nestedReturn, n) <+ (V(nestedReturn, n) / secondResistance)

final class Increment43NestedGeneratedNodeGroupMixedOperators extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      val nestedReturn = node(Electrical)
      analog:
        I(nestedTap, p) <+ (1.pF * ddt(V(nestedTap, p)))
        val voltage = V(nestedReturn, n)
        val voltageRate = voltage / 1.0.s
        V(nestedReturn, n) <+ idt(voltageRate, 0.0.V)

final class Increment43NestedGeneratedNodeIdt extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)
  val p = inout(Electrical)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      analog:
        val voltage = V(nestedTap, p)
        val voltageRate = voltage / 1.0.s
        V(nestedTap, p) <+ idt(voltageRate, 0.0.V)

final class Increment43NestedGeneratedNodeDdt extends Module:
  val rows: Param[Integer] = param(8.integer, range = 6 to 10)
  val columns: Param[Integer] = param(11.integer, range = 9 to 13)
  val p = inout(Electrical)

  hdlRange(1, rows, 2): _ =>
    hdlRange(2, columns, 3): _ =>
      val nestedTap = node(Electrical)
      analog:
        val voltage = V(nestedTap, p)
        I(nestedTap, p) <+ (1.pF * ddt(voltage))

final class Increment43RepeatedSiblingGeneratedNodes extends Module:
  val lanes: Param[Integer] = param(7.integer, range = 6 to 10)
  val groups: Param[Integer] = param(3.integer, range = 2 to 5)

  hdlRange(1, lanes, 2): _ =>
    val firstTap = node(Electrical)
    val _ = firstTap
    ()

  hdlRange(0, groups): _ =>
    val secondTap = node(Electrical)
    val _ = secondTap
    ()

final class Increment43RepeatedSiblingGeneratedResistors extends Module:
  val lanes: Param[Integer] = param(7.integer, range = 6 to 10)
  val groups: Param[Integer] = param(3.integer, range = 2 to 5)
  val firstResistance: Param[Real] = param(1.kOhm)
  val secondResistance: Param[Real] = param(2.kOhm)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(1, lanes, 2): _ =>
    val firstTap = node(Electrical)
    analog:
      I(firstTap, p) <+ (V(firstTap, p) / firstResistance)

  hdlRange(0, groups): _ =>
    val secondTap = node(Electrical)
    analog:
      I(secondTap, n) <+ (V(secondTap, n) / secondResistance)

final class Increment43RepeatedSiblingGeneratedIdtEquations extends Module:
  val lanes: Param[Integer] = param(7.integer, range = 6 to 10)
  val groups: Param[Integer] = param(3.integer, range = 2 to 5)
  val p = inout(Electrical)
  val n = inout(Electrical)

  hdlRange(1, lanes, 2): _ =>
    val firstTap = node(Electrical)
    analog:
      val firstVoltage = V(firstTap, p)
      val firstRate = firstVoltage / 1.0.s
      V(firstTap, p) <+ idt(firstRate, 0.0.V)

  hdlRange(0, groups): _ =>
    val secondTap = node(Electrical)
    analog:
      val secondVoltage = V(secondTap, n)
      val secondRate = secondVoltage / 1.0.s
      V(secondTap, n) <+ idt(secondRate, 0.0.V)

object Increment43RangeConstructionTests extends TestSuite:
  private def workDirectory(): Path =
    Files.createTempDirectory("nodal-increment43-range-")

  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try
        val iterator = stream.iterator()
        while iterator.hasNext do delete(iterator.next())
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  private def constructionFailure(body: => Any): ConstructionException =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException]

  val tests: Tests = Tests:
    test("bounded symbolic hdlRange captures stable generated ownership"):
      val first = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)
      val second = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)

      assert(first == second)
      assert(first.generatedRegions.size == 1)
      val region = first.generatedRegions.head
      assert(region.owner == first.root)
      assert(region.parent.isEmpty)
      assert(region.lower == "0")
      assert(region.upperExclusive.endsWith(".lanes"))
      assert(region.step == "1")
      assert(region.maximum.isEmpty)
      assert(region.maximumTripCount == 4)
      assert(region.declarations.size == 1)
      assert(region.declarations.head.endsWith(".tap"))
      assert(first.sourceMap.exists(_.semanticPath == region.induction))

      val parameter = first.modules.head.declarations.find(_.name == "lanes").get
      val attributes = parameter.attributes.toMap
      assert(attributes("integer_range_lower") == "1")
      assert(attributes("integer_range_upper") == "4")
      assert(attributes("classification") == "structural")
      assert(attributes("structural_effects") == "topology")

    test("literal hdlRange retains a concrete finite envelope"):
      val snapshot = ConstructionKernel.inspect(new Increment43LiteralGeneratedNode)
      val region = snapshot.generatedRegions.head
      assert(region.lower == "0")
      assert(region.upperExclusive == "3")
      assert(region.step == "1")
      assert(region.maximumTripCount == 3)

    test("bridge emits native parameter range envelope and generated analog node"):
      val first = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNode)
      val second = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNode)

      assert(first == second)
      assert(first.text.contains("\"nodal.parameter_constraint\""))
      assert(first.text.contains("\"nodal.parameter_envelope\""))
      assert(first.text.contains("classification = \"structural\""))
      assert(first.text.contains("policy = \"static_generate\""))
      assert(first.text.contains("effects = [\"topology\"]"))
      assert(!first.text.contains("effects = [\"generate\"]"))
      assert(first.text.contains("\"nodal.generate\""))
      assert(first.text.contains("\"nodal.node\""))
      assert(first.text.contains("maximum_trip_count = 4 : i64"))
      assert(first.text.contains("upper = @lanes"))
      assert(first.text.indexOf("\"nodal.generate\"") < first.text.indexOf("\"nodal.node\""))
      val sourceMapStart = first.text.indexOf("nodal.bridge.source_map")
      val sourceMapEnd = first.text.indexOf("nodal.bridge.topology", sourceMapStart)
      assert(sourceMapStart >= 0)
      assert(sourceMapEnd > sourceMapStart)
      val sourceMap = first.text.substring(sourceMapStart, sourceMapEnd)
      val region = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)
        .generatedRegions.head
      assert(sourceMap.contains(s"semantic_path = \"${region.path}\""))
      assert(sourceMap.contains(s"semantic_path = \"${region.induction}\""))

    test("repeated nested generation retains one canonical topology effect"):
      val first = ConstructionKernel.inspect(new Increment43RepeatedSymbolicGeneratedNodes)
      val second = ConstructionKernel.inspect(new Increment43RepeatedSymbolicGeneratedNodes)
      assert(first == second)
      assert(first.generatedRegions.size == 3)
      assert(first.generatedRegions.count(_.parent.nonEmpty) == 1)
      assert(first.generatedRegions.flatMap(_.declarations).distinct.size == 2)
      val parameter = first.modules.head.declarations.find(_.name == "lanes").get
      assert(parameter.attributes.toMap.apply("structural_effects") == "topology")
      val document = ScalaToMlirBridge.fromSnapshot(first)
      assert(document.text.contains("effects = [\"topology\"]"))
      assert(!document.text.contains("effects = [\"generate\"]"))
      val sourceMapStart = document.text.indexOf("nodal.bridge.source_map")
      val sourceMapEnd = document.text.indexOf("nodal.bridge.topology", sourceMapStart)
      assert(sourceMapStart >= 0)
      assert(sourceMapEnd > sourceMapStart)
      val sourceMap = document.text.substring(sourceMapStart, sourceMapEnd)
      first.generatedRegions.foreach: region =>
        val regionMarker = s"semantic_path = \"${region.path}\""
        val inductionMarker = s"semantic_path = \"${region.induction}\""
        assert(sourceMap.sliding(regionMarker.length).count(_ == regionMarker) == 1)
        assert(sourceMap.sliding(inductionMarker.length).count(_ == inductionMarker) == 1)

    test("bridge rejects missing empty and unsupported structural effects"):
      val snapshot = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)
      val parameter = snapshot.modules.head.declarations.find(_.name == "lanes").get
      val invalidEffects = Vector(
        None,
        Some(""),
        Some(" , "),
        Some("generate"),
        Some("topology,generate")
      )
      for effect <- invalidEffects do
        val declarations = snapshot.modules.head.declarations.map: declaration =>
          if declaration.path != parameter.path then declaration
          else
            declaration.copy(
              attributes = declaration.attributes.filterNot(_._1 == "structural_effects") ++
                effect.toVector.map(value => "structural_effects" -> value)
            )
        val forged = snapshot.copy(
          modules = snapshot.modules.updated(
            0,
            snapshot.modules.head.copy(declarations = declarations)
          )
        )
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged))
          .failed.get.asInstanceOf[BridgeException]
        assert(failure.diagnostic.code == "NODAL-BRIDGE-043")
        assert(failure.diagnostic.semanticPath.contains(parameter.path))

    test("bridge rejects forged generated ownership and parent cycles"):
      val snapshot = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)
      val region = snapshot.generatedRegions.head
      val forgeries = Vector(
        region.copy(owner = "ForeignModule"),
        region.copy(induction = "ForeignModule.expr_0"),
        region.copy(path = region.path + " "),
        region.copy(induction = region.induction + " "),
        region.copy(declarations = Vector("ForeignModule.tap")),
        region.copy(parent = Some(region.path))
      )
      for forgedRegion <- forgeries do
        val forged = snapshot.copy(generatedRegions = Vector(forgedRegion))
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged))
          .failed.get.asInstanceOf[BridgeException]
        assert(failure.diagnostic.code == "NODAL-BRIDGE-043")

    test("bridge rejects generated induction without a canonical source map"):
      val snapshot = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)
      val region = snapshot.generatedRegions.head
      val missing = snapshot.copy(
        sourceMap = snapshot.sourceMap.filterNot(_.semanticPath == region.induction)
      )
      val missingFailure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(missing))
        .failed.get.asInstanceOf[BridgeException]
      assert(missingFailure.diagnostic.code == "NODAL-BRIDGE-043")
      assert(missingFailure.diagnostic.semanticPath.contains(region.induction))

      val inductionEntry = snapshot.sourceMap.find(_.semanticPath == region.induction).get
      val conflicting = snapshot.copy(
        sourceMap = snapshot.sourceMap :+ inductionEntry.copy(
          semanticPath = region.path,
          source = inductionEntry.source.copy(line = inductionEntry.source.line + 1)
        )
      )
      val conflictingFailure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(conflicting))
        .failed.get.asInstanceOf[BridgeException]
      assert(conflictingFailure.diagnostic.code == "NODAL-BRIDGE-043")
      assert(conflictingFailure.diagnostic.semanticPath.contains(region.path))

    test("symbolic generation rejects missing finite parameter ranges"):
      val failure = constructionFailure(
        ConstructionKernel.inspect(new Increment43MissingRange)
      )
      assert(failure.diagnostic.code == "NODAL-ITERATION-043-001")

    test("symbolic generation rejects direction that changes over legal settings"):
      val failure = constructionFailure(
        ConstructionKernel.inspect(new Increment43AmbiguousDirection)
      )
      assert(failure.diagnostic.code == "NODAL-ITERATION-043-003")

    test("explicit maximum is enforced against the proven parameter envelope"):
      val failure = constructionFailure(
        ConstructionKernel.inspect(new Increment43MaximumTooSmall)
      )
      assert(failure.diagnostic.code == "NODAL-ITERATION-043-001")

    test("unsupported generated object kinds fail closed"):
      val failure = constructionFailure(
        ConstructionKernel.inspect(new Increment43UnsupportedGeneratedWire)
      )
      assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")

    test("generated lexical storage retains immediate region and induction ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedLexicalStorage)
      val second = ConstructionKernel.inspect(new Increment43GeneratedLexicalStorage)
      assert(first == second)
      assert(first.analogProcedural.size == 1)
      val region = first.generatedRegions.head
      val program = first.analogProcedural.head
      assert(program.generatedOwner.contains(region.path))
      assert(program.generatedInduction.contains(region.induction))
      assert(program.variables.size == 1)
      assert(program.assignments.size == 1)

      val document = ScalaToMlirBridge.fromSnapshot(first)
      assert(document.text.contains("\"nodal.generate\""))
      assert(document.text.contains("\"nodal.analog_procedure\""))
      assert(document.text.contains("\"nodal.analog_variable\""))
      assert(document.text.indexOf("\"nodal.generate\"") <
        document.text.indexOf("\"nodal.analog_procedure\""))
      assert(document.text.contains(s"generated_owner = \"${region.path}\""))
      assert(document.text.contains(s"generated_induction = \"${region.induction}\""))

      val forgedPrograms = Vector(
        program.copy(generatedOwner = None),
        program.copy(generatedOwner = Some(region.path + ".forged")),
        program.copy(generatedInduction = Some(region.induction + ".forged")),
        program.copy(variables = program.variables.map(_.copy(authoredPath = None))),
        program.copy(variables =
          program.variables.map(
            _.copy(authoredPath = Some(region.path + ".forged"))
          )
        )
      )
      forgedPrograms.foreach: forgedProgram =>
        val forged = first.copy(analogProcedural = Vector(forgedProgram))
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
          .asInstanceOf[BridgeException]
        assert(failure.diagnostic.code == "NODAL-BRIDGE-043")

      val missingVariableDeclaration = first.copy(
        generatedRegions = Vector(region.copy(declarations = Vector.empty))
      )
      val missingFailure = scala.util.Try(
        ScalaToMlirBridge.fromSnapshot(missingVariableDeclaration)
      ).failed.get.asInstanceOf[BridgeException]
      assert(missingFailure.diagnostic.code == "NODAL-BRIDGE-043")

    test("generated structured lexical storage preserves one owned control tree"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedStructuredLexicalStorage)
      val second = ConstructionKernel.inspect(new Increment43GeneratedStructuredLexicalStorage)
      assert(first == second)
      assert(first.analogProcedural.size == 1)
      val region = first.generatedRegions.head
      val program = first.analogProcedural.head
      assert(program.generatedOwner.contains(region.path))
      assert(program.generatedInduction.contains(region.induction))
      assert(program.variables.size == 4)
      assert(program.assignments.isEmpty)
      assert(program.controlFlow.nonEmpty)

      val document = ScalaToMlirBridge.fromSnapshot(first)
      for operation <- Vector(
          "nodal.analog_if",
          "nodal.analog_case",
          "nodal.analog_loop",
          "nodal.analog_variable",
          "nodal.analog_variable_read",
          "nodal.analog_assign"
        )
      do assert(document.text.contains(s"\"$operation\""))
      val ownerMarker = s"generated_owner = \"${region.path}\""
      val inductionMarker = s"generated_induction = \"${region.induction}\""
      assert(document.text.sliding(ownerMarker.length).count(_ == ownerMarker) > 8)
      assert(document.text.sliding(inductionMarker.length).count(_ == inductionMarker) > 8)

      val forgedProgram = program.copy(generatedInduction = Some(region.induction + ".forged"))
      val failure = scala.util.Try(
        ScalaToMlirBridge.fromSnapshot(first.copy(analogProcedural = Vector(forgedProgram)))
      ).failed.get.asInstanceOf[BridgeException]
      assert(failure.diagnostic.code == "NODAL-BRIDGE-043")
      assert(failure.diagnostic.message.contains("ownership"))

    test("generated analog event retains exact occurrence ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedEventStorage)
      val second = ConstructionKernel.inspect(new Increment43GeneratedEventStorage)
      assert(first == second)
      val region = first.generatedRegions.head
      val program = first.analogProcedural.head
      assert(program.generatedOwner.contains(region.path))
      assert(program.generatedInduction.contains(region.induction))
      val events = program.controlFlow.get.root.statements.collect:
        case event: AnalogControlFlowRuntime.Statement.EventControl => event
      assert(events.size == 1)
      assert(events.head.event.operation == "analog_cross")

      val document = ScalaToMlirBridge.fromSnapshot(first)
      for operation <- Vector("nodal.analog_cross", "nodal.analog_on") do
        val line = document.text.linesIterator.find(_.contains(s"\"$operation\"")).get
        assert(line.contains(s"generated_owner = \"${region.path}\""))
        assert(line.contains(s"generated_induction = \"${region.induction}\""))

    test("generated continuous operators retain exact region ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedContinuousOperator)
      val second = ConstructionKernel.inspect(new Increment43GeneratedContinuousOperator)
      assert(first == second)
      val generated = first.generatedRegions.head
      val analog = first.analogRegions.head
      assert(analog.generatedOwner.contains(generated.path))
      assert(analog.generatedInduction.contains(generated.induction))
      assert(first.continuousOperators.map(_.operation) == Vector("analog_ddt", "analog_idt"))
      assert(first.continuousOperators.forall(_.generatedOwner.contains(generated.path)))
      assert(first.continuousOperators.forall(
        _.generatedInduction.contains(generated.induction)
      ))

      val document = ScalaToMlirBridge.fromSnapshot(first)
      assert(document.text.indexOf("\"nodal.generate\"") <
        document.text.indexOf("\"nodal.analog\""))
      for operation <- Vector("nodal.analog", "nodal.analog_ddt", "nodal.analog_idt") do
        val line = document.text.linesIterator.find(_.contains(s"\"$operation\"")).get
        assert(line.contains(s"generated_owner = \"${generated.path}\""))
        assert(line.contains(s"generated_induction = \"${generated.induction}\""))

      val forgedOperator = first.continuousOperators.head.copy(generatedOwner = None)
      val forged = first.copy(
        continuousOperators = forgedOperator +: first.continuousOperators.tail
      )
      val forgeries = Vector(
        forged,
        first.copy(analogRegions = Vector(analog.copy(generatedOwner = None))),
        first.copy(analogRegions =
          Vector(
            analog.copy(generatedOwner = Some(generated.path + ".forged"))
          )
        )
      )
      forgeries.foreach: candidate =>
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(candidate)).failed.get
          .asInstanceOf[BridgeException]
        assert(failure.diagnostic.code == "NODAL-BRIDGE-043")

    test("generated waveform operators retain exact region ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedWaveformOperator)
      val second = ConstructionKernel.inspect(new Increment43GeneratedWaveformOperator)
      assert(first == second)
      val generated = first.generatedRegions.head
      assert(first.waveformOperators.map(_.operation) ==
        Vector("analog_transition", "analog_slew", "analog_absdelay"))
      assert(first.waveformOperators.forall(_.generatedOwner.contains(generated.path)))
      assert(first.waveformOperators.forall(
        _.generatedInduction.contains(generated.induction)
      ))

      val document = ScalaToMlirBridge.fromSnapshot(first)
      for operation <- Vector(
          "nodal.analog",
          "nodal.analog_transition",
          "nodal.analog_slew",
          "nodal.analog_absdelay"
        )
      do
        val line = document.text.linesIterator.find(_.contains(s"\"$operation\"")).get
        assert(line.contains(s"generated_owner = \"${generated.path}\""))
        assert(line.contains(s"generated_induction = \"${generated.induction}\""))

      val forgedOperator = first.waveformOperators.head.copy(generatedInduction = None)
      val forged = first.copy(
        waveformOperators = forgedOperator +: first.waveformOperators.tail
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
        .asInstanceOf[BridgeException]
      assert(failure.diagnostic.code == "NODAL-BRIDGE-043")

      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                for operation <- Vector(
                    "nodal.analog_transition",
                    "nodal.analog_slew",
                    "nodal.analog_absdelay"
                  )
                do
                  val line = success.normalizedMlir.linesIterator
                    .find(_.contains(s"\"$operation\""))
                    .get
                  assert(line.contains(s"generated_owner = \"${generated.path}\""))
                  assert(line.contains(s"generated_induction = \"${generated.induction}\""))
              case nativeFailure: NativeCompilerFailure =>
                scala.Predef.assert(
                  false,
                  s"${nativeFailure.diagnostic}\n${nativeFailure.standardError}"
                )

            val marker = s"generated_owner = \"${generated.path}\""
            val operationOffset = document.text.indexOf("\"nodal.analog_slew\"")
            val ownerOffset = document.text.indexOf(marker, operationOffset)
            assert(operationOffset >= 0, ownerOffset >= operationOffset)
            val forgedText = document.text.patch(
              ownerOffset,
              s"generated_owner = \"${generated.path}.forged\"",
              marker.length
            )
            NativeCompilerClient.run(document.copy(text = forgedText), request) match
              case nativeFailure: NativeCompilerFailure =>
                assert(nativeFailure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged generated waveform accepted: $success")
          finally delete(directory)

    test("generated transfer operators retain exact region and state ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedTransferOperator)
      val second = ConstructionKernel.inspect(new Increment43GeneratedTransferOperator)
      assert(first == second)
      val generated = first.generatedRegions.head
      assert(first.transferOperators.map(_.kind) == Vector("laplace_nd", "zi_nd"))
      assert(first.transferOperators.forall(_.generatedOwner.contains(generated.path)))
      assert(first.transferOperators.forall(
        _.generatedInduction.contains(generated.induction)
      ))

      val document = ScalaToMlirBridge.fromSnapshot(first)
      val transferLines = document.text.linesIterator
        .filter(_.contains("\"nodal.analog_transfer\""))
        .toVector
      assert(transferLines.size == 2)
      transferLines.foreach: line =>
        assert(line.contains(s"generated_owner = \"${generated.path}\""))
        assert(line.contains(s"generated_induction = \"${generated.induction}\""))

      val forgedOperator = first.transferOperators.head.copy(generatedOwner = None)
      val forged = first.copy(
        transferOperators = forgedOperator +: first.transferOperators.tail
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
        .asInstanceOf[BridgeException]
      assert(failure.diagnostic.code == "NODAL-BRIDGE-043")

      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                val normalizedTransfers = success.normalizedMlir.linesIterator
                  .filter(_.contains("\"nodal.analog_transfer\""))
                  .toVector
                assert(normalizedTransfers.size == 2)
                normalizedTransfers.foreach: line =>
                  assert(line.contains(s"generated_owner = \"${generated.path}\""))
                  assert(line.contains(
                    s"generated_induction = \"${generated.induction}\""
                  ))
              case nativeFailure: NativeCompilerFailure =>
                scala.Predef.assert(
                  false,
                  s"${nativeFailure.diagnostic}\n${nativeFailure.standardError}"
                )

            val marker = s"generated_owner = \"${generated.path}\""
            val transferOffset = document.text.indexOf("\"nodal.analog_transfer\"")
            val ownerOffset = document.text.indexOf(marker, transferOffset)
            assert(transferOffset >= 0, ownerOffset >= transferOffset)
            val forgedText = document.text.patch(
              ownerOffset,
              s"generated_owner = \"${generated.path}.forged\"",
              marker.length
            )
            NativeCompilerClient.run(document.copy(text = forgedText), request) match
              case nativeFailure: NativeCompilerFailure =>
                assert(nativeFailure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged generated transfer accepted: $success")

            val firstPath = first.transferOperators.head.path
            val secondPath = first.transferOperators(1).path
            val duplicateIdentityText = document.text.replace(
              transferLines(1),
              transferLines(1).replace(secondPath, firstPath)
            )
            NativeCompilerClient.run(
              document.copy(text = duplicateIdentityText),
              request
            ) match
              case nativeFailure: NativeCompilerFailure =>
                assert(nativeFailure.diagnostic.code == "NODAL-ANALOG-040-002")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"duplicate generated transfer accepted: $success")
          finally delete(directory)

    test("generated noise operators retain exact region and source ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedNoiseOperator)
      val second = ConstructionKernel.inspect(new Increment43GeneratedNoiseOperator)
      assert(first == second)
      val generated = first.generatedRegions.head
      assert(first.noiseOperators.map(_.kind) == Vector("white", "flicker", "table"))
      assert(first.noiseOperators.forall(_.generatedOwner.contains(generated.path)))
      assert(first.noiseOperators.forall(
        _.generatedInduction.contains(generated.induction)
      ))

      val document = ScalaToMlirBridge.fromSnapshot(first)
      val noiseLines = document.text.linesIterator
        .filter(_.contains("\"nodal.analog_noise\""))
        .toVector
      assert(noiseLines.size == 3)
      noiseLines.foreach: line =>
        assert(line.contains(s"generated_owner = \"${generated.path}\""))
        assert(line.contains(s"generated_induction = \"${generated.induction}\""))

      val forgedOperator = first.noiseOperators.head.copy(generatedOwner = None)
      val forged = first.copy(
        noiseOperators = forgedOperator +: first.noiseOperators.tail
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
        .asInstanceOf[BridgeException]
      assert(failure.diagnostic.code == "NODAL-BRIDGE-043")

      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                val normalizedNoise = success.normalizedMlir.linesIterator
                  .filter(_.contains("\"nodal.analog_noise\""))
                  .toVector
                assert(normalizedNoise.size == 3)
                normalizedNoise.foreach: line =>
                  assert(line.contains(s"generated_owner = \"${generated.path}\""))
                  assert(line.contains(
                    s"generated_induction = \"${generated.induction}\""
                  ))
              case nativeFailure: NativeCompilerFailure =>
                scala.Predef.assert(
                  false,
                  s"${nativeFailure.diagnostic}\n${nativeFailure.standardError}"
                )

            val marker = s"generated_owner = \"${generated.path}\""
            val noiseOffset = document.text.indexOf("\"nodal.analog_noise\"")
            val ownerOffset = document.text.indexOf(marker, noiseOffset)
            assert(noiseOffset >= 0, ownerOffset >= noiseOffset)
            val forgedText = document.text.patch(
              ownerOffset,
              s"generated_owner = \"${generated.path}.forged\"",
              marker.length
            )
            NativeCompilerClient.run(document.copy(text = forgedText), request) match
              case nativeFailure: NativeCompilerFailure =>
                assert(nativeFailure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged generated noise accepted: $success")

            val firstPath = first.noiseOperators.head.path
            val secondPath = first.noiseOperators(1).path
            val duplicateIdentityText = document.text.replace(
              noiseLines(1),
              noiseLines(1).replace(secondPath, firstPath)
            )
            NativeCompilerClient.run(
              document.copy(text = duplicateIdentityText),
              request
            ) match
              case nativeFailure: NativeCompilerFailure =>
                assert(nativeFailure.diagnostic.code == "NODAL-ANALOG-039-002")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"duplicate generated noise accepted: $success")
          finally delete(directory)

    test("generated additive contributions retain exact region ownership"):
      val first = ConstructionKernel.inspect(new Increment43GeneratedContribution)
      val second = ConstructionKernel.inspect(new Increment43GeneratedContribution)
      assert(first == second)
      val generated = first.generatedRegions.head
      val analog = first.analogRegions.head
      assert(analog.generatedOwner.contains(generated.path))
      assert(analog.generatedInduction.contains(generated.induction))
      assert(analog.contributions.map(_.kind) == Vector("potential", "flow"))
      assert(analog.contributions.forall(_.generatedOwner.contains(generated.path)))
      assert(analog.contributions.forall(
        _.generatedInduction.contains(generated.induction)
      ))

      val document = ScalaToMlirBridge.fromSnapshot(first)
      val contributionLines = document.text.linesIterator
        .filter(_.contains("\"nodal.contribute\""))
        .toVector
      assert(contributionLines.size == 2)
      contributionLines.foreach: line =>
        assert(line.contains(s"generated_owner = \"${generated.path}\""))
        assert(line.contains(s"generated_induction = \"${generated.induction}\""))

      val forgedContribution = analog.contributions.head.copy(generatedOwner = None)
      val forged = first.copy(
        analogRegions = Vector(
          analog.copy(contributions = forgedContribution +: analog.contributions.tail)
        )
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
        .asInstanceOf[BridgeException]
      assert(failure.diagnostic.code == "NODAL-BRIDGE-043")
      assert(failure.diagnostic.message.contains("contribution ownership"))

      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                val normalized = success.normalizedMlir.linesIterator
                  .filter(_.contains("\"nodal.contribute\""))
                  .toVector
                assert(normalized.size == 2)
                normalized.foreach: line =>
                  assert(line.contains(s"generated_owner = \"${generated.path}\""))
                  assert(line.contains(
                    s"generated_induction = \"${generated.induction}\""
                  ))
              case nativeFailure: NativeCompilerFailure =>
                scala.Predef.assert(
                  false,
                  s"${nativeFailure.diagnostic}\n${nativeFailure.standardError}"
                )

            val marker = s"generated_owner = \"${generated.path}\""
            val contributionOffset = document.text.indexOf("\"nodal.contribute\"")
            val ownerOffset = document.text.indexOf(marker, contributionOffset)
            assert(contributionOffset >= 0, ownerOffset >= contributionOffset)
            val forgedText = document.text.patch(
              ownerOffset,
              s"generated_owner = \"${generated.path}.forged\"",
              marker.length
            )
            NativeCompilerClient.run(document.copy(text = forgedText), request) match
              case nativeFailure: NativeCompilerFailure =>
                assert(nativeFailure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged generated contribution accepted: $success")
          finally delete(directory)

    test("unsupported generated effects still fail at their capture boundary"):
      val cases = Vector[(String, String, () => Module)](
        ("variable", "generate_0", () => new Increment43UnsupportedGeneratedVariable),
        ("variable", "variable_1", () => new Increment43ForgedGeneratedVariableCarrier),
        ("assignment", "generate_0", () => new Increment43ForgedGeneratedAssignmentCarrier),
        (
          "continuous-time operator",
          "generate_0",
          () => new Increment43UnsupportedGeneratedContinuousOperator
        ),
        (
          "analog waveform operator",
          "generate_0",
          () => new Increment43UnsupportedGeneratedBoundStep
        ),
        (
          "analog transfer operator",
          "generate_0",
          () => new Increment43UnsupportedGeneratedTransferOperator
        ),
        (
          "analog noise operator",
          "generate_0",
          () => new Increment43UnsupportedGeneratedNoiseOperator
        ),
        ("analog event", "generate_0", () => new Increment43UnsupportedGeneratedEvent),
        (
          "continuous region",
          "generate_0",
          () => new Increment43UnsupportedGeneratedAnalogRegion
        ),
        (
          "equation region",
          "generate_0",
          () => new Increment43UnsupportedGeneratedEquationRegion
        ),
        (
          "initialequation region",
          "generate_0",
          () => new Increment43UnsupportedGeneratedInitialEquationRegion
        ),
        (
          "contribution region",
          "generate_0",
          () => new Increment43UnsupportedGeneratedContributionRegion
        ),
        (
          "procedural region",
          "generate_0",
          () => new Increment43UnsupportedGeneratedBareProceduralRegion
        ),
        (
          "procedural region",
          "generate_0",
          () => new Increment43UnsupportedGeneratedProcedure
        )
      )
      cases.foreach: (label, path, construct) =>
        val failure = constructionFailure(ConstructionKernel.inspect(construct()))
        assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
        assert(failure.diagnostic.message.contains(label))
        assert(failure.diagnostic.semanticPath.exists(_.contains(path)))

    test("locked nodalc verifies public symbolic hdlRange when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val document = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNode)
            NativeCompilerClient.run(
              document,
              NativeCompilerRequest(
                executable = Path.of(executable).toAbsolutePath,
                arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case success: NativeCompilerSuccess =>
                assert(success.normalizedMlir.contains("\"nodal.generate\""))
                assert(success.normalizedMlir.contains("\"nodal.parameter_envelope\""))
              case failure: NativeCompilerFailure =>
                scala.Predef.assert(
                  false,
                  s"${failure.diagnostic}\n${failure.standardError}"
                )
          finally delete(directory)

    test("public symbolic generated analog node lowers to one target electrical array"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43SymbolicGeneratedNode,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.generate\""))
            assert(result.mlir.contains("\"nodal.node\""))
            assert(result.verilogA.contains("parameter integer lanes = 2 from [1:4];"))
            assert(result.verilogA.contains("electrical tap[0:(lanes - 1)];"))
            assert(!result.verilogA.contains("genvar generate_0_index"))
            assert(!result.verilogA.contains("analog begin"))
          finally delete(directory)
        case _ => assert(true)

    test("repeated sibling generated nodes retain distinct target arrays and source maps"):
      val snapshot = ConstructionKernel.inspect(new Increment43RepeatedSiblingGeneratedNodes)
      assert(snapshot == ConstructionKernel.inspect(new Increment43RepeatedSiblingGeneratedNodes))
      assert(snapshot.generatedRegions.size == 2)
      assert(snapshot.generatedRegions.forall(_.parent.isEmpty))
      assert(snapshot.generatedRegions.map(_.path).distinct.size == 2)
      assert(snapshot.generatedRegions.map(_.induction).distinct.size == 2)
      assert(snapshot.generatedRegions.flatMap(_.declarations).distinct.size == 2)
      snapshot.generatedRegions.foreach: region =>
        assert(region.declarations.size == 1)
        assert(snapshot.sourceMap.count(_.semanticPath == region.induction) == 1)
        assert(snapshot.sourceMap.count(_.semanticPath == region.declarations.head) == 1)

      val first = ScalaToMlirBridge.lower(new Increment43RepeatedSiblingGeneratedNodes)
      assert(first == ScalaToMlirBridge.lower(new Increment43RepeatedSiblingGeneratedNodes))
      assert(first.text.sliding("\"nodal.generate\"".length).count(_ == "\"nodal.generate\"") == 2)
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      // The bridge derives each region's source entry from its canonical induction mapping.
      val sourceMapStart = first.text.indexOf("nodal.bridge.source_map")
      val sourceMapEnd = first.text.indexOf("nodal.bridge.topology", sourceMapStart)
      assert(sourceMapStart >= 0)
      assert(sourceMapEnd > sourceMapStart)
      val sourceMap = first.text.substring(sourceMapStart, sourceMapEnd)
      snapshot.generatedRegions.foreach: region =>
        Vector(region.path, region.induction, region.declarations.head).foreach: path =>
          val marker = s"semantic_path = \"$path\""
          assert(sourceMap.sliding(marker.length).count(_ == marker) == 1)

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val regions = snapshot.generatedRegions.sortBy(_.path)
            for (original, replacement) <- Vector(
                regions(1).path -> regions.head.path,
                regions(1).induction -> regions.head.induction
              )
            do
              val text = first.text.replace(original, replacement)
              assert(text != first.text)
              val hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))
                .map(value => f"${value & 0xff}%02x").mkString
              NativeCompilerClient.run(
                first.copy(text = text, sha256 = hash),
                NativeCompilerRequest(
                  executable = Path.of(nodalc).toAbsolutePath,
                  arguments = Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                  workingDirectory = directory,
                  timeout = Duration.ofSeconds(30)
                )
              ) match
                case failure: NativeCompilerFailure =>
                  assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(
                    false,
                    s"aliased sibling generated identity accepted: ${success.normalizedMlir}"
                  )

            def compileRepeated() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43RepeatedSiblingGeneratedNodes,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileRepeated()
            assert(result == compileRepeated())
            val declarationNames = snapshot.generatedRegions
              .sortBy(_.path)
              .map(_.declarations.head.split('.').last)
            assert(declarationNames.distinct.size == 2)
            val compactUpper = "(((((lanes - 1) + 2) - 1) / 2) - 1)"
            assert(
              result.verilogA.contains(
                s"  electrical ${declarationNames.head}[0:$compactUpper];"
              )
            )
            assert(
              result.verilogA.contains(
                s"  electrical ${declarationNames(1)}[0:(groups - 1)];"
              )
            )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 2)
            assert(!result.verilogA.contains("genvar"))
            assert(!result.verilogA.contains("analog begin"))
          finally delete(directory)
        case _ => assert(true)

    test("nested generated nodes retain explicit row-major target layout and source maps"):
      val snapshot = ConstructionKernel.inspect(new Increment43RepeatedSymbolicGeneratedNodes)
      assert(snapshot == ConstructionKernel.inspect(new Increment43RepeatedSymbolicGeneratedNodes))
      assert(snapshot.generatedRegions.size == 3)
      assert(snapshot.generatedRegions.count(_.parent.nonEmpty) == 1)
      val declaredRegions = snapshot.generatedRegions.filter(_.declarations.nonEmpty).sortBy(_.path)
      assert(declaredRegions.size == 2)
      assert(declaredRegions.head.parent.isEmpty)
      assert(declaredRegions(1).parent.nonEmpty)
      snapshot.generatedRegions.foreach: region =>
        assert(snapshot.sourceMap.count(_.semanticPath == region.induction) == 1)
        region.declarations.foreach: declaration =>
          assert(snapshot.sourceMap.count(_.semanticPath == declaration) == 1)

      val first = ScalaToMlirBridge.lower(new Increment43RepeatedSymbolicGeneratedNodes)
      assert(first == ScalaToMlirBridge.lower(new Increment43RepeatedSymbolicGeneratedNodes))
      assert(first.text.sliding("\"nodal.generate\"".length).count(_ == "\"nodal.generate\"") == 3)
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      val child = declaredRegions(1)
      val childNodeLine = first.text.linesIterator
        .find(line => line.contains("\"nodal.node\"") && line.contains(child.path))
        .get
      assert(childNodeLine.contains(s"generated_owner = \"${child.path}\""))
      assert(childNodeLine.contains(s"generated_induction = \"${child.induction}\""))

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val childOwner = s"generated_owner = \"${child.path}\""
            val parentOwner = s"generated_owner = \"${child.parent.get}\""
            val forgedNodeLine = childNodeLine.replace(childOwner, parentOwner)
            val nodeOffset = first.text.indexOf(childNodeLine)
            assert(nodeOffset >= 0)
            assert(forgedNodeLine != childNodeLine)
            val forged = first.text.patch(nodeOffset, forgedNodeLine, childNodeLine.length)
            val hash = MessageDigest.getInstance("SHA-256")
              .digest(forged.getBytes(StandardCharsets.UTF_8))
              .map(value => f"${value & 0xff}%02x").mkString
            NativeCompilerClient.run(
              first.copy(text = forged, sha256 = hash),
              NativeCompilerRequest(
                executable = Path.of(nodalc).toAbsolutePath,
                arguments = Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case failure: NativeCompilerFailure =>
                assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(
                  false,
                  s"forged nested generated owner accepted: ${success.normalizedMlir}"
                )

            def compileNested() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43RepeatedSymbolicGeneratedNodes,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNested()
            assert(result == compileNested())
            val names = declaredRegions.map(_.declarations.head.split('.').last)
            assert(result.verilogA.contains(s"  electrical ${names.head}[0:(lanes - 1)];"))
            assert(
              result.verilogA.contains(
                s"  electrical ${names(1)}[0:(((lanes - 0) * (lanes - 0)) - 1)];"
              )
            )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 2)
            assert(!result.verilogA.contains("genvar"))
            assert(!result.verilogA.contains("analog begin"))
          finally delete(directory)
        case _ => assert(true)

    test("independent strided nested nodes retain product layout and exact identities"):
      val snapshot =
        ConstructionKernel.inspect(new Increment43IndependentStridedNestedGeneratedNodes)
      assert(
        snapshot ==
          ConstructionKernel.inspect(new Increment43IndependentStridedNestedGeneratedNodes)
      )
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(child.parent.contains(outer.path))
      assert(outer.lower == "1")
      assert(outer.step == "2")
      assert(child.lower == "2")
      assert(child.step == "3")
      assert(outer.declarations.isEmpty)
      assert(child.declarations.size == 1)
      snapshot.generatedRegions.foreach: region =>
        assert(snapshot.sourceMap.count(_.semanticPath == region.induction) == 1)
        region.declarations.foreach: declaration =>
          assert(snapshot.sourceMap.count(_.semanticPath == declaration) == 1)

      val first =
        ScalaToMlirBridge.lower(new Increment43IndependentStridedNestedGeneratedNodes)
      assert(
        first == ScalaToMlirBridge.lower(new Increment43IndependentStridedNestedGeneratedNodes)
      )
      assert(first.text.sliding("\"nodal.generate\"".length).count(_ == "\"nodal.generate\"") == 2)
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 1)
      val sourceMapStart = first.text.indexOf("nodal.bridge.source_map")
      val sourceMapEnd = first.text.indexOf("nodal.bridge.topology", sourceMapStart)
      assert(sourceMapStart >= 0)
      assert(sourceMapEnd > sourceMapStart)
      val sourceMap = first.text.substring(sourceMapStart, sourceMapEnd)
      snapshot.generatedRegions.foreach: region =>
        val regionMarker = s"semantic_path = \"${region.path}\""
        val inductionMarker = s"semantic_path = \"${region.induction}\""
        assert(sourceMap.sliding(regionMarker.length).count(_ == regionMarker) == 1)
        assert(sourceMap.sliding(inductionMarker.length).count(_ == inductionMarker) == 1)
        region.declarations.foreach: declaration =>
          val declarationMarker = s"semantic_path = \"$declaration\""
          assert(sourceMap.sliding(declarationMarker.length).count(_ == declarationMarker) == 1)

      val childNodeLine = first.text.linesIterator
        .find(line => line.contains("\"nodal.node\"") && line.contains(child.path))
        .get
      assert(childNodeLine.contains(s"generated_owner = \"${child.path}\""))
      assert(childNodeLine.contains(s"generated_induction = \"${child.induction}\""))

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val childInduction = s"generated_induction = \"${child.induction}\""
            val parentInduction = s"generated_induction = \"${outer.induction}\""
            val forgedNodeLine = childNodeLine.replace(childInduction, parentInduction)
            val nodeOffset = first.text.indexOf(childNodeLine)
            assert(nodeOffset >= 0)
            assert(forgedNodeLine != childNodeLine)
            val forged = first.text.patch(nodeOffset, forgedNodeLine, childNodeLine.length)
            val hash = MessageDigest.getInstance("SHA-256")
              .digest(forged.getBytes(StandardCharsets.UTF_8))
              .map(value => f"${value & 0xff}%02x").mkString
            NativeCompilerClient.run(
              first.copy(text = forged, sha256 = hash),
              NativeCompilerRequest(
                executable = Path.of(nodalc).toAbsolutePath,
                arguments = Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case failure: NativeCompilerFailure =>
                assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(
                  false,
                  s"forged nested generated induction accepted: ${success.normalizedMlir}"
                )

            def compileNested() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43IndependentStridedNestedGeneratedNodes,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNested()
            assert(result == compileNested())
            val name = child.declarations.head.split('.').last
            val outerCount = "((((rows - 1) + 2) - 1) / 2)"
            val innerCount = "((((columns - 2) + 3) - 1) / 3)"
            assert(
              result.verilogA.contains(
                s"  electrical $name[0:(($outerCount * $innerCount) - 1)];"
              )
            )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 1)
            assert(!result.verilogA.contains("genvar"))
            assert(!result.verilogA.contains("analog begin"))
          finally delete(directory)
        case _ => assert(true)

    test("nested generated node equation retains row-major child ownership"):
      val snapshot = ConstructionKernel.inspect(new Increment43NestedGeneratedNodeResistor)
      assert(snapshot == ConstructionKernel.inspect(new Increment43NestedGeneratedNodeResistor))
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(child.parent.contains(outer.path))
      assert(outer.declarations.isEmpty)
      assert(child.declarations.size == 1)
      assert(snapshot.analogRegions.size == 1)
      val analog = snapshot.analogRegions.head
      assert(analog.generatedOwner.contains(child.path))
      assert(analog.generatedInduction.contains(child.induction))
      assert(analog.contributions.size == 1)
      assert(analog.contributions.head.generatedOwner.contains(child.path))
      assert(analog.contributions.head.generatedInduction.contains(child.induction))

      val first = ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeResistor)
      assert(first == ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeResistor))
      assert(
        first.text.sliding("\"nodal.generate\"".length).count(_ == "\"nodal.generate\"") == 2
      )
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 1)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 1)
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 1
      )
      val sourceMapStart = first.text.indexOf("nodal.bridge.source_map")
      val sourceMapEnd = first.text.indexOf("nodal.bridge.topology", sourceMapStart)
      assert(sourceMapStart >= 0)
      assert(sourceMapEnd > sourceMapStart)
      val sourceMap = first.text.substring(sourceMapStart, sourceMapEnd)
      snapshot.generatedRegions.foreach: region =>
        val regionMarker = s"semantic_path = \"${region.path}\""
        val inductionMarker = s"semantic_path = \"${region.induction}\""
        assert(sourceMap.sliding(regionMarker.length).count(_ == regionMarker) == 1)
        assert(sourceMap.sliding(inductionMarker.length).count(_ == inductionMarker) == 1)
        region.declarations.foreach: declaration =>
          val declarationMarker = s"semantic_path = \"$declaration\""
          assert(sourceMap.sliding(declarationMarker.length).count(_ == declarationMarker) == 1)

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val branchLine = first.text.linesIterator.find(_.contains("\"nodal.branch\"")).get
            val childOwner = s"generated_owner = \"${child.path}\""
            val parentOwner = s"generated_owner = \"${outer.path}\""
            assert(branchLine.contains(childOwner))
            val forgedBranchLine = branchLine.replace(childOwner, parentOwner)
            val branchOffset = first.text.indexOf(branchLine)
            assert(branchOffset >= 0)
            assert(forgedBranchLine != branchLine)
            val forged = first.text.patch(branchOffset, forgedBranchLine, branchLine.length)
            val hash = MessageDigest.getInstance("SHA-256")
              .digest(forged.getBytes(StandardCharsets.UTF_8))
              .map(value => f"${value & 0xff}%02x").mkString
            NativeCompilerClient.run(
              first.copy(text = forged, sha256 = hash),
              NativeCompilerRequest(
                executable = Path.of(nodalc).toAbsolutePath,
                arguments = Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case failure: NativeCompilerFailure =>
                assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged nested generated branch accepted: $success")

            def compileNestedEquation() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43NestedGeneratedNodeResistor,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNestedEquation()
            assert(result == compileNestedEquation())
            val nodeName = child.declarations.head.split('.').last
            val outerCount = "((((rows - 1) + 2) - 1) / 2)"
            val innerCount = "((((columns - 2) + 3) - 1) / 3)"
            val flatIndex =
              s"((((generate_0_index - 1) / 2) * $innerCount) + ((generate_1_index - 2) / 3))"
            assert(
              result.verilogA.contains(
                s"  electrical $nodeName[0:(($outerCount * $innerCount) - 1)];"
              )
            )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("genvar ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (")) == 2)
            assert(result.verilogA.contains(s"I($nodeName[$flatIndex], p)"))
            assert(result.verilogA.contains(s"V($nodeName[$flatIndex], p)"))
          finally delete(directory)
        case _ => assert(true)

    test("nested unit-step origins use zero-based ordinals without changing direct arrays"):
      val cases = Vector[(String, String, String, () => Module)](
        (
          "(rows - 1)",
          "(columns - 2)",
          "(((generate_0_index - 1) * (columns - 2)) + (generate_1_index - 2))",
          () => new Increment43NestedGeneratedNodeUnitSteps
        ),
        (
          "(rows - 1)",
          "((((columns - 2) + 3) - 1) / 3)",
          "(((generate_0_index - 1) * ((((columns - 2) + 3) - 1) / 3)) + " +
            "((generate_1_index - 2) / 3))",
          () => new Increment43NestedGeneratedNodeOuterUnitStep
        ),
        (
          "((((rows - 1) + 2) - 1) / 2)",
          "(columns - 2)",
          "((((generate_0_index - 1) / 2) * (columns - 2)) + (generate_1_index - 2))",
          () => new Increment43NestedGeneratedNodeInnerUnitStep
        )
      )
      cases.foreach: (outerCount, innerCount, flatIndex, construct) =>
        val snapshot = ConstructionKernel.inspect(construct())
        assert(snapshot == ConstructionKernel.inspect(construct()))
        assert(snapshot.generatedRegions.size == 2)
        val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
        val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
        assert(child.parent.contains(outer.path))
        assert(outer.declarations.isEmpty)
        assert(child.declarations.size == 1)
        val first = ScalaToMlirBridge.lower(construct())
        assert(first == ScalaToMlirBridge.lower(construct()))
        val sourceMap = first.text
          .split("nodal.bridge.source_map", 2)(1)
          .split("nodal.bridge.topology", 2)(0)
        snapshot.generatedRegions.foreach: region =>
          (Vector(region.path, region.induction) ++ region.declarations).foreach: path =>
            val marker = s"semantic_path = \"$path\""
            assert(sourceMap.sliding(marker.length).count(_ == marker) == 1)

        (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
          case (Some(nodalc), Some(translator)) =>
            val directory = workDirectory()
            try
              def compile(module: => Module) =
                ScalaToMlirBridge
                  .compileToVerilogA(
                    module,
                    Path.of(nodalc).toAbsolutePath,
                    Path.of(translator).toAbsolutePath,
                    directory,
                    Duration.ofSeconds(60)
                  )
                  .fold(
                    failure =>
                      scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                    identity
                  )
              val result = compile(construct())
              assert(result == compile(construct()))
              val name = child.declarations.head.split('.').last
              assert(
                result.verilogA.contains(
                  s"  electrical $name[0:(($outerCount * $innerCount) - 1)];"
                )
              )
              assert(result.verilogA.contains(s"I($name[$flatIndex], p) <+"))
              assert(result.verilogA.contains(s"V($name[$flatIndex], p)"))
              assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (")) == 2)
              assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 1)
              val direct = compile(new Increment43GeneratedNodeDdtContribution)
              assert(direct.verilogA.contains("electrical tap[1:(lanes - 1)];"))
              assert(direct.verilogA.contains("I(tap[generate_0_index], p) <+"))
              assert(direct.verilogA.contains("ddt(V(tap[generate_0_index], p))"))
            finally delete(directory)
          case _ => assert(true)

    test("nested peer generated node equation retains both row-major child endpoints"):
      val snapshot = ConstructionKernel.inspect(new Increment43NestedPeerGeneratedNodeResistor)
      assert(
        snapshot == ConstructionKernel.inspect(new Increment43NestedPeerGeneratedNodeResistor)
      )
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(child.parent.contains(outer.path))
      assert(outer.declarations.isEmpty)
      assert(child.declarations.size == 2)
      assert(child.declarations.distinct.size == 2)
      assert(snapshot.analogRegions.size == 1)
      val analog = snapshot.analogRegions.head
      assert(analog.generatedOwner.contains(child.path))
      assert(analog.generatedInduction.contains(child.induction))
      assert(analog.contributions.size == 1)
      assert(analog.contributions.head.generatedOwner.contains(child.path))
      assert(analog.contributions.head.generatedInduction.contains(child.induction))
      snapshot.generatedRegions.foreach: region =>
        assert(snapshot.sourceMap.count(_.semanticPath == region.induction) == 1)
        region.declarations.foreach: declaration =>
          assert(snapshot.sourceMap.count(_.semanticPath == declaration) == 1)

      val first = ScalaToMlirBridge.lower(new Increment43NestedPeerGeneratedNodeResistor)
      assert(first == ScalaToMlirBridge.lower(new Increment43NestedPeerGeneratedNodeResistor))
      assert(
        first.text.sliding("\"nodal.generate\"".length).count(_ == "\"nodal.generate\"") == 2
      )
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 1)
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 1
      )
      val nodeLines = first.text.linesIterator.filter(_.contains("\"nodal.node\"")).toVector
      assert(nodeLines.size == 2)
      nodeLines.foreach: line =>
        assert(line.contains(s"generated_owner = \"${child.path}\""))
        assert(line.contains(s"generated_induction = \"${child.induction}\""))
      val sourceMapStart = first.text.indexOf("nodal.bridge.source_map")
      val sourceMapEnd = first.text.indexOf("nodal.bridge.topology", sourceMapStart)
      assert(sourceMapStart >= 0)
      assert(sourceMapEnd > sourceMapStart)
      val sourceMap = first.text.substring(sourceMapStart, sourceMapEnd)
      snapshot.generatedRegions.foreach: region =>
        val regionMarker = s"semantic_path = \"${region.path}\""
        val inductionMarker = s"semantic_path = \"${region.induction}\""
        assert(sourceMap.sliding(regionMarker.length).count(_ == regionMarker) == 1)
        assert(sourceMap.sliding(inductionMarker.length).count(_ == inductionMarker) == 1)
        region.declarations.foreach: declaration =>
          val declarationMarker = s"semantic_path = \"$declaration\""
          assert(sourceMap.sliding(declarationMarker.length).count(_ == declarationMarker) == 1)

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val secondNodeLine = nodeLines(1)
            val childOwner = s"generated_owner = \"${child.path}\""
            val childInduction = s"generated_induction = \"${child.induction}\""
            val parentOwner = s"generated_owner = \"${outer.path}\""
            val parentInduction = s"generated_induction = \"${outer.induction}\""
            val sourcePathPattern = "source_path = \"[^\"]+\"".r
            val mutations = Vector(
              (
                "owner",
                secondNodeLine.replace(childOwner, parentOwner),
                "NODAL-ITERATION-043-004"
              ),
              (
                "induction",
                secondNodeLine.replace(childInduction, parentInduction),
                "NODAL-ITERATION-043-004"
              ),
              (
                "source",
                sourcePathPattern.replaceFirstIn(secondNodeLine, "source_path = \"\""),
                "NODAL-BACKEND-CAPABILITY-001"
              )
            )
            val secondNodeOffset = first.text.indexOf(secondNodeLine)
            assert(secondNodeOffset >= 0)
            mutations.foreach: (kind, mutatedLine, expectedCode) =>
              assert(mutatedLine != secondNodeLine)
              val forged = first.text.patch(secondNodeOffset, mutatedLine, secondNodeLine.length)
              val hash = MessageDigest.getInstance("SHA-256")
                .digest(forged.getBytes(StandardCharsets.UTF_8))
                .map(value => f"${value & 0xff}%02x").mkString
              NativeCompilerClient.run(
                first.copy(text = forged, sha256 = hash),
                NativeCompilerRequest(
                  executable = Path.of(nodalc).toAbsolutePath,
                  arguments = Vector("--nodal-to-verilog-a"),
                  workingDirectory = directory,
                  timeout = Duration.ofSeconds(30)
                )
              ) match
                case failure: NativeCompilerFailure =>
                  assert(failure.diagnostic.code == expectedCode)
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(
                    false,
                    s"forged nested peer generated node $kind accepted: $success"
                  )

            def compileNestedPeerEquation() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43NestedPeerGeneratedNodeResistor,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNestedPeerEquation()
            assert(result == compileNestedPeerEquation())
            val names = child.declarations.map(_.split('.').last)
            val outerCount = "((((rows - 1) + 2) - 1) / 2)"
            val innerCount = "((((columns - 2) + 3) - 1) / 3)"
            val flatIndex =
              s"((((generate_0_index - 1) / 2) * $innerCount) + ((generate_1_index - 2) / 3))"
            names.foreach: name =>
              assert(
                result.verilogA.contains(
                  s"  electrical $name[0:(($outerCount * $innerCount) - 1)];"
                )
              )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("genvar ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (")) == 2)
            assert(
              result.verilogA.linesIterator.count(
                _.trim ==
                  s"I(${names.head}[$flatIndex], ${names(1)}[$flatIndex]) <+ " +
                  s"(V(${names.head}[$flatIndex], ${names(1)}[$flatIndex]) / resistance);"
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("nested peer generated node ddt retains both row-major child endpoints"):
      val snapshot = ConstructionKernel.inspect(new Increment43NestedPeerGeneratedNodeDdt)
      assert(snapshot == ConstructionKernel.inspect(new Increment43NestedPeerGeneratedNodeDdt))
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(child.parent.contains(outer.path))
      assert(outer.declarations.isEmpty)
      assert(child.declarations.size == 2)
      assert(child.declarations.distinct.size == 2)
      assert(snapshot.analogRegions.size == 1)
      val analog = snapshot.analogRegions.head
      assert(analog.generatedOwner.contains(child.path))
      assert(analog.generatedInduction.contains(child.induction))
      assert(analog.contributions.size == 1)

      val first = ScalaToMlirBridge.lower(new Increment43NestedPeerGeneratedNodeDdt)
      assert(first == ScalaToMlirBridge.lower(new Increment43NestedPeerGeneratedNodeDdt))
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 1)
      assert(
        first.text.sliding("\"nodal.analog_ddt\"".length).count(_ == "\"nodal.analog_ddt\"") == 1
      )
      assert(
        first.text.sliding("\"nodal.analog_mul\"".length).count(_ == "\"nodal.analog_mul\"") == 1
      )
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 1
      )
      val nodeLines = first.text.linesIterator.filter(_.contains("\"nodal.node\"")).toVector
      val ddtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_ddt\"")).get
      assert(nodeLines.size == 2)
      (nodeLines :+ ddtLine).foreach: line =>
        assert(line.contains(s"generated_owner = \"${child.path}\""))
        assert(line.contains(s"generated_induction = \"${child.induction}\""))
      snapshot.generatedRegions.foreach: region =>
        assert(snapshot.sourceMap.count(_.semanticPath == region.induction) == 1)
        region.declarations.foreach: declaration =>
          assert(snapshot.sourceMap.count(_.semanticPath == declaration) == 1)

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def expectNativeFailure(text: String, label: String, expectedCode: String): Unit =
              val hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))
                .map(value => f"${value & 0xff}%02x").mkString
              NativeCompilerClient.run(
                first.copy(text = text, sha256 = hash),
                NativeCompilerRequest(
                  executable = Path.of(nodalc).toAbsolutePath,
                  arguments = Vector("--nodal-to-verilog-a"),
                  workingDirectory = directory,
                  timeout = Duration.ofSeconds(30)
                )
              ) match
                case failure: NativeCompilerFailure =>
                  assert(failure.diagnostic.code == expectedCode)
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(false, s"forged nested peer ddt $label accepted: $success")

            val childOwner = s"generated_owner = \"${child.path}\""
            val parentOwner = s"generated_owner = \"${outer.path}\""
            val forgedDdtLine = ddtLine.replace(childOwner, parentOwner)
            val ddtOffset = first.text.indexOf(ddtLine)
            assert(ddtOffset >= 0)
            assert(forgedDdtLine != ddtLine)
            expectNativeFailure(
              first.text.patch(ddtOffset, forgedDdtLine, ddtLine.length),
              "operator owner",
              "NODAL-ITERATION-043-004"
            )

            val secondNodeLine = nodeLines(1)
            val sourcePathPattern = "source_path = \"[^\"]+\"".r
            val forgedNodeLine = sourcePathPattern.replaceFirstIn(
              secondNodeLine,
              "source_path = \"\""
            )
            val secondNodeOffset = first.text.indexOf(secondNodeLine)
            assert(secondNodeOffset >= 0)
            assert(forgedNodeLine != secondNodeLine)
            expectNativeFailure(
              first.text.patch(secondNodeOffset, forgedNodeLine, secondNodeLine.length),
              "peer source",
              "NODAL-BACKEND-CAPABILITY-001"
            )

            def compileNestedPeerDdt() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43NestedPeerGeneratedNodeDdt,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNestedPeerDdt()
            assert(result == compileNestedPeerDdt())
            val names = child.declarations.map(_.split('.').last)
            val outerCount = "((((rows - 1) + 2) - 1) / 2)"
            val innerCount = "((((columns - 2) + 3) - 1) / 3)"
            val flatIndex =
              s"((((generate_0_index - 1) / 2) * $innerCount) + ((generate_1_index - 2) / 3))"
            names.foreach: name =>
              assert(
                result.verilogA.contains(
                  s"  electrical $name[0:(($outerCount * $innerCount) - 1)];"
                )
              )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("genvar ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (")) == 2)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(
                  s"I(${names.head}[$flatIndex], ${names(1)}[$flatIndex]) <+"
                ) && line.contains("1e-12") && line.contains(
                  s"ddt(V(${names.head}[$flatIndex], ${names(1)}[$flatIndex]))"
                )
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("nested peer generated node idt retains both row-major child endpoints and state"):
      val snapshot = ConstructionKernel.inspect(new Increment43NestedPeerGeneratedNodeIdt)
      assert(snapshot == ConstructionKernel.inspect(new Increment43NestedPeerGeneratedNodeIdt))
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(child.parent.contains(outer.path))
      assert(outer.declarations.isEmpty)
      assert(child.declarations.size == 2)
      assert(child.declarations.distinct.size == 2)
      assert(snapshot.analogRegions.size == 1)
      val analog = snapshot.analogRegions.head
      assert(analog.generatedOwner.contains(child.path))
      assert(analog.generatedInduction.contains(child.induction))
      assert(analog.contributions.size == 1)

      val first = ScalaToMlirBridge.lower(new Increment43NestedPeerGeneratedNodeIdt)
      assert(first == ScalaToMlirBridge.lower(new Increment43NestedPeerGeneratedNodeIdt))
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 1)
      assert(
        first.text.sliding("\"nodal.analog_idt\"".length).count(_ == "\"nodal.analog_idt\"") == 1
      )
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 1
      )
      val nodeLines = first.text.linesIterator.filter(_.contains("\"nodal.node\"")).toVector
      val idtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_idt\"")).get
      assert(nodeLines.size == 2)
      (nodeLines :+ idtLine).foreach: line =>
        assert(line.contains(s"generated_owner = \"${child.path}\""))
        assert(line.contains(s"generated_induction = \"${child.induction}\""))
      assert(idtLine.contains("state_id"))
      snapshot.generatedRegions.foreach: region =>
        assert(snapshot.sourceMap.count(_.semanticPath == region.induction) == 1)
        region.declarations.foreach: declaration =>
          assert(snapshot.sourceMap.count(_.semanticPath == declaration) == 1)

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def expectNativeFailure(text: String, label: String, expectedCode: String): Unit =
              val hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))
                .map(value => f"${value & 0xff}%02x").mkString
              NativeCompilerClient.run(
                first.copy(text = text, sha256 = hash),
                NativeCompilerRequest(
                  executable = Path.of(nodalc).toAbsolutePath,
                  arguments = Vector("--nodal-to-verilog-a"),
                  workingDirectory = directory,
                  timeout = Duration.ofSeconds(30)
                )
              ) match
                case failure: NativeCompilerFailure =>
                  assert(failure.diagnostic.code == expectedCode)
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(false, s"forged nested peer idt $label accepted: $success")

            val childOwner = s"generated_owner = \"${child.path}\""
            val parentOwner = s"generated_owner = \"${outer.path}\""
            val forgedIdtLine = idtLine.replace(childOwner, parentOwner)
            val idtOffset = first.text.indexOf(idtLine)
            assert(idtOffset >= 0)
            assert(forgedIdtLine != idtLine)
            expectNativeFailure(
              first.text.patch(idtOffset, forgedIdtLine, idtLine.length),
              "operator owner",
              "NODAL-ITERATION-043-004"
            )

            val statePattern = "state_id = \"[^\"]+\"".r
            val forgedStateLine = statePattern.replaceFirstIn(
              idtLine,
              "state_id = \"Increment43NestedPeerGeneratedNodeIdt.forged.state\""
            )
            assert(forgedStateLine != idtLine)
            expectNativeFailure(
              first.text.patch(idtOffset, forgedStateLine, idtLine.length),
              "state identity",
              "NODAL-BACKEND-CAPABILITY-001"
            )

            val secondNodeLine = nodeLines(1)
            val sourcePathPattern = "source_path = \"[^\"]+\"".r
            val forgedNodeLine = sourcePathPattern.replaceFirstIn(
              secondNodeLine,
              "source_path = \"\""
            )
            val secondNodeOffset = first.text.indexOf(secondNodeLine)
            assert(secondNodeOffset >= 0)
            assert(forgedNodeLine != secondNodeLine)
            expectNativeFailure(
              first.text.patch(secondNodeOffset, forgedNodeLine, secondNodeLine.length),
              "peer source",
              "NODAL-BACKEND-CAPABILITY-001"
            )

            def compileNestedPeerIdt() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43NestedPeerGeneratedNodeIdt,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNestedPeerIdt()
            assert(result == compileNestedPeerIdt())
            val names = child.declarations.map(_.split('.').last)
            val outerCount = "((((rows - 1) + 2) - 1) / 2)"
            val innerCount = "((((columns - 2) + 3) - 1) / 3)"
            val flatIndex =
              s"((((generate_0_index - 1) / 2) * $innerCount) + ((generate_1_index - 2) / 3))"
            names.foreach: name =>
              assert(
                result.verilogA.contains(
                  s"  electrical $name[0:(($outerCount * $innerCount) - 1)];"
                )
              )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("genvar ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (")) == 2)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"V(${names.head}[$flatIndex], ${names(1)}[$flatIndex]) <+") &&
                  line.contains(
                    s"idt((V(${names.head}[$flatIndex], ${names(1)}[$flatIndex]) / 1), 0)"
                  )
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("nested peer generated node idt result retains one scaled row-major path"):
      val snapshot = ConstructionKernel.inspect(new Increment43NestedPeerGeneratedNodeScaledIdt)
      assert(
        snapshot == ConstructionKernel.inspect(new Increment43NestedPeerGeneratedNodeScaledIdt)
      )
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(child.parent.contains(outer.path))
      assert(outer.declarations.isEmpty)
      assert(child.declarations.size == 2)
      assert(child.declarations.distinct.size == 2)
      assert(snapshot.analogRegions.size == 1)
      val analog = snapshot.analogRegions.head
      assert(analog.generatedOwner.contains(child.path))
      assert(analog.generatedInduction.contains(child.induction))
      assert(analog.contributions.size == 1)

      val first = ScalaToMlirBridge.lower(new Increment43NestedPeerGeneratedNodeScaledIdt)
      assert(first == ScalaToMlirBridge.lower(new Increment43NestedPeerGeneratedNodeScaledIdt))
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 1)
      assert(
        first.text.sliding("\"nodal.analog_idt\"".length).count(_ == "\"nodal.analog_idt\"") == 1
      )
      assert(
        first.text.sliding("\"nodal.analog_div\"".length).count(_ == "\"nodal.analog_div\"") == 2
      )
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 1
      )
      val nodeLines = first.text.linesIterator.filter(_.contains("\"nodal.node\"")).toVector
      val idtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_idt\"")).get
      assert(nodeLines.size == 2)
      (nodeLines :+ idtLine).foreach: line =>
        assert(line.contains(s"generated_owner = \"${child.path}\""))
        assert(line.contains(s"generated_induction = \"${child.induction}\""))
      assert(idtLine.contains("state_id"))
      snapshot.generatedRegions.foreach: region =>
        assert(snapshot.sourceMap.count(_.semanticPath == region.induction) == 1)
        region.declarations.foreach: declaration =>
          assert(snapshot.sourceMap.count(_.semanticPath == declaration) == 1)

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileNestedPeerScaledIdt() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43NestedPeerGeneratedNodeScaledIdt,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNestedPeerScaledIdt()
            assert(result == compileNestedPeerScaledIdt())
            val names = child.declarations.map(_.split('.').last)
            val outerCount = "((((rows - 1) + 2) - 1) / 2)"
            val innerCount = "((((columns - 2) + 3) - 1) / 3)"
            val flatIndex =
              s"((((generate_0_index - 1) / 2) * $innerCount) + ((generate_1_index - 2) / 3))"
            names.foreach: name =>
              assert(
                result.verilogA.contains(
                  s"  electrical $name[0:(($outerCount * $innerCount) - 1)];"
                )
              )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("genvar ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (")) == 2)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"V(${names.head}[$flatIndex], ${names(1)}[$flatIndex]) <+") &&
                  line.contains(
                    s"(idt((V(${names.head}[$flatIndex], ${names(1)}[$flatIndex]) / 1), 0) / 2)"
                  )
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("nested generated node group equations retain independent row-major ownership"):
      val snapshot = ConstructionKernel.inspect(new Increment43NestedGeneratedNodeGroupResistors)
      assert(
        snapshot == ConstructionKernel.inspect(new Increment43NestedGeneratedNodeGroupResistors)
      )
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(child.parent.contains(outer.path))
      assert(outer.declarations.isEmpty)
      assert(child.declarations.size == 2)
      assert(child.declarations.distinct.size == 2)
      assert(snapshot.analogRegions.size == 1)
      val analog = snapshot.analogRegions.head
      assert(analog.generatedOwner.contains(child.path))
      assert(analog.generatedInduction.contains(child.induction))
      assert(analog.contributions.size == 2)
      analog.contributions.foreach: contribution =>
        assert(contribution.generatedOwner.contains(child.path))
        assert(contribution.generatedInduction.contains(child.induction))

      val first = ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeGroupResistors)
      assert(first == ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeGroupResistors))
      assert(
        first.text.sliding("\"nodal.generate\"".length).count(_ == "\"nodal.generate\"") == 2
      )
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 2)
      assert(
        first.text
          .sliding("\"nodal.contribute\"".length)
          .count(_ == "\"nodal.contribute\"") == 2
      )
      val sourceMapStart = first.text.indexOf("nodal.bridge.source_map")
      val sourceMapEnd = first.text.indexOf("nodal.bridge.topology", sourceMapStart)
      assert(sourceMapStart >= 0)
      assert(sourceMapEnd > sourceMapStart)
      val sourceMap = first.text.substring(sourceMapStart, sourceMapEnd)
      snapshot.generatedRegions.foreach: region =>
        val regionMarker = s"semantic_path = \"${region.path}\""
        val inductionMarker = s"semantic_path = \"${region.induction}\""
        assert(sourceMap.sliding(regionMarker.length).count(_ == regionMarker) == 1)
        assert(sourceMap.sliding(inductionMarker.length).count(_ == inductionMarker) == 1)
        region.declarations.foreach: declaration =>
          val declarationMarker = s"semantic_path = \"$declaration\""
          assert(sourceMap.sliding(declarationMarker.length).count(_ == declarationMarker) == 1)

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val branchLines =
              first.text.linesIterator.filter(_.contains("\"nodal.branch\"")).toVector
            assert(branchLines.size == 2)
            val secondBranchLine = branchLines(1)
            val childOwner = s"generated_owner = \"${child.path}\""
            val parentOwner = s"generated_owner = \"${outer.path}\""
            val forgedBranchLine = secondBranchLine.replace(childOwner, parentOwner)
            val branchOffset = first.text.indexOf(secondBranchLine)
            assert(branchOffset >= 0)
            assert(forgedBranchLine != secondBranchLine)
            val forged = first.text.patch(branchOffset, forgedBranchLine, secondBranchLine.length)
            val hash = MessageDigest.getInstance("SHA-256")
              .digest(forged.getBytes(StandardCharsets.UTF_8))
              .map(value => f"${value & 0xff}%02x").mkString
            NativeCompilerClient.run(
              first.copy(text = forged, sha256 = hash),
              NativeCompilerRequest(
                executable = Path.of(nodalc).toAbsolutePath,
                arguments = Vector("--nodal-to-verilog-a"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case failure: NativeCompilerFailure =>
                assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(
                  false,
                  s"forged nested generated node group branch accepted: $success"
                )

            def compileNestedGroupEquations() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43NestedGeneratedNodeGroupResistors,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNestedGroupEquations()
            assert(result == compileNestedGroupEquations())
            val names = child.declarations.map(_.split('.').last)
            val outerCount = "((((rows - 1) + 2) - 1) / 2)"
            val innerCount = "((((columns - 2) + 3) - 1) / 3)"
            val flatIndex =
              s"((((generate_0_index - 1) / 2) * $innerCount) + ((generate_1_index - 2) / 3))"
            names.foreach: name =>
              assert(
                result.verilogA.contains(
                  s"  electrical $name[0:(($outerCount * $innerCount) - 1)];"
                )
              )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("genvar ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (")) == 2)
            assert(
              result.verilogA.linesIterator.count(
                _.trim ==
                  s"I(${names.head}[$flatIndex], p) <+ " +
                  s"(V(${names.head}[$flatIndex], p) / firstResistance);"
              ) == 1
            )
            assert(
              result.verilogA.linesIterator.count(
                _.trim ==
                  s"I(${names(1)}[$flatIndex], n) <+ " +
                  s"(V(${names(1)}[$flatIndex], n) / secondResistance);"
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("nested generated node group idt retains one instance-owned state path"):
      val snapshot = ConstructionKernel.inspect(new Increment43NestedGeneratedNodeGroupIdt)
      assert(
        snapshot == ConstructionKernel.inspect(new Increment43NestedGeneratedNodeGroupIdt)
      )
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(child.parent.contains(outer.path))
      assert(outer.declarations.isEmpty)
      assert(child.declarations.size == 2)
      assert(child.declarations.distinct.size == 2)
      assert(snapshot.analogRegions.size == 1)
      val analog = snapshot.analogRegions.head
      assert(analog.generatedOwner.contains(child.path))
      assert(analog.generatedInduction.contains(child.induction))
      assert(analog.contributions.size == 2)

      val first = ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeGroupIdt)
      assert(first == ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeGroupIdt))
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 2)
      assert(
        first.text.sliding("\"nodal.analog_idt\"".length).count(_ == "\"nodal.analog_idt\"") == 1
      )
      assert(
        first.text
          .sliding("\"nodal.contribute\"".length)
          .count(_ == "\"nodal.contribute\"") == 2
      )
      val idtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_idt\"")).get
      assert(idtLine.contains(s"generated_owner = \"${child.path}\""))
      assert(idtLine.contains(s"generated_induction = \"${child.induction}\""))
      assert(idtLine.contains("state_id"))
      val sourceMapStart = first.text.indexOf("nodal.bridge.source_map")
      val sourceMapEnd = first.text.indexOf("nodal.bridge.topology", sourceMapStart)
      assert(sourceMapStart >= 0)
      assert(sourceMapEnd > sourceMapStart)
      val sourceMap = first.text.substring(sourceMapStart, sourceMapEnd)
      snapshot.generatedRegions.foreach: region =>
        val regionMarker = s"semantic_path = \"${region.path}\""
        val inductionMarker = s"semantic_path = \"${region.induction}\""
        assert(sourceMap.sliding(regionMarker.length).count(_ == regionMarker) == 1)
        assert(sourceMap.sliding(inductionMarker.length).count(_ == inductionMarker) == 1)
        region.declarations.foreach: declaration =>
          val declarationMarker = s"semantic_path = \"$declaration\""
          assert(sourceMap.sliding(declarationMarker.length).count(_ == declarationMarker) == 1)

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val statePattern = "state_id = \"[^\"]+\"".r
            val forgedIdtLine =
              statePattern.replaceFirstIn(idtLine, "state_id = \"forged.group.state\"")
            val idtOffset = first.text.indexOf(idtLine)
            assert(idtOffset >= 0)
            assert(forgedIdtLine != idtLine)
            val forged = first.text.patch(idtOffset, forgedIdtLine, idtLine.length)
            val hash = MessageDigest.getInstance("SHA-256")
              .digest(forged.getBytes(StandardCharsets.UTF_8))
              .map(value => f"${value & 0xff}%02x").mkString
            NativeCompilerClient.run(
              first.copy(text = forged, sha256 = hash),
              NativeCompilerRequest(
                executable = Path.of(nodalc).toAbsolutePath,
                arguments = Vector("--nodal-to-verilog-a"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case _: NativeCompilerFailure => ()
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(
                  false,
                  s"forged nested generated node group idt state accepted: $success"
                )

            def compileNestedGroupIdt() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43NestedGeneratedNodeGroupIdt,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNestedGroupIdt()
            assert(result == compileNestedGroupIdt())
            val names = child.declarations.map(_.split('.').last)
            val outerCount = "((((rows - 1) + 2) - 1) / 2)"
            val innerCount = "((((columns - 2) + 3) - 1) / 3)"
            val flatIndex =
              s"((((generate_0_index - 1) / 2) * $innerCount) + ((generate_1_index - 2) / 3))"
            names.foreach: name =>
              assert(
                result.verilogA.contains(
                  s"  electrical $name[0:(($outerCount * $innerCount) - 1)];"
                )
              )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("genvar ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (")) == 2)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"V(${names.head}[$flatIndex], p) <+") &&
                  line.contains(s"idt((V(${names.head}[$flatIndex], p) / 1), 0)")
              ) == 1
            )
            assert(
              result.verilogA.linesIterator.count(
                _.trim ==
                  s"I(${names(1)}[$flatIndex], n) <+ " +
                  s"(V(${names(1)}[$flatIndex], n) / secondResistance);"
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("nested generated node group ddt retains one instance-owned derivative path"):
      val snapshot = ConstructionKernel.inspect(new Increment43NestedGeneratedNodeGroupDdt)
      assert(
        snapshot == ConstructionKernel.inspect(new Increment43NestedGeneratedNodeGroupDdt)
      )
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(child.parent.contains(outer.path))
      assert(outer.declarations.isEmpty)
      assert(child.declarations.size == 2)
      assert(child.declarations.distinct.size == 2)
      assert(snapshot.analogRegions.size == 1)
      val analog = snapshot.analogRegions.head
      assert(analog.generatedOwner.contains(child.path))
      assert(analog.generatedInduction.contains(child.induction))
      assert(analog.contributions.size == 2)

      val first = ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeGroupDdt)
      assert(first == ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeGroupDdt))
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 2)
      assert(
        first.text.sliding("\"nodal.analog_ddt\"".length).count(_ == "\"nodal.analog_ddt\"") == 1
      )
      assert(
        first.text
          .sliding("\"nodal.contribute\"".length)
          .count(_ == "\"nodal.contribute\"") == 2
      )
      val ddtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_ddt\"")).get
      assert(ddtLine.contains(s"generated_owner = \"${child.path}\""))
      assert(ddtLine.contains(s"generated_induction = \"${child.induction}\""))
      val sourceMapStart = first.text.indexOf("nodal.bridge.source_map")
      val sourceMapEnd = first.text.indexOf("nodal.bridge.topology", sourceMapStart)
      assert(sourceMapStart >= 0)
      assert(sourceMapEnd > sourceMapStart)
      val sourceMap = first.text.substring(sourceMapStart, sourceMapEnd)
      snapshot.generatedRegions.foreach: region =>
        val regionMarker = s"semantic_path = \"${region.path}\""
        val inductionMarker = s"semantic_path = \"${region.induction}\""
        assert(sourceMap.sliding(regionMarker.length).count(_ == regionMarker) == 1)
        assert(sourceMap.sliding(inductionMarker.length).count(_ == inductionMarker) == 1)
        region.declarations.foreach: declaration =>
          val declarationMarker = s"semantic_path = \"$declaration\""
          assert(sourceMap.sliding(declarationMarker.length).count(_ == declarationMarker) == 1)

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val childOwner = s"generated_owner = \"${child.path}\""
            val parentOwner = s"generated_owner = \"${outer.path}\""
            val forgedDdtLine = ddtLine.replace(childOwner, parentOwner)
            val ddtOffset = first.text.indexOf(ddtLine)
            assert(ddtOffset >= 0)
            assert(forgedDdtLine != ddtLine)
            val forged = first.text.patch(ddtOffset, forgedDdtLine, ddtLine.length)
            val hash = MessageDigest.getInstance("SHA-256")
              .digest(forged.getBytes(StandardCharsets.UTF_8))
              .map(value => f"${value & 0xff}%02x").mkString
            NativeCompilerClient.run(
              first.copy(text = forged, sha256 = hash),
              NativeCompilerRequest(
                executable = Path.of(nodalc).toAbsolutePath,
                arguments = Vector("--nodal-to-verilog-a"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case _: NativeCompilerFailure => ()
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(
                  false,
                  s"forged nested generated node group ddt owner accepted: $success"
                )

            def compileNestedGroupDdt() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43NestedGeneratedNodeGroupDdt,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNestedGroupDdt()
            assert(result == compileNestedGroupDdt())
            val names = child.declarations.map(_.split('.').last)
            val outerCount = "((((rows - 1) + 2) - 1) / 2)"
            val innerCount = "((((columns - 2) + 3) - 1) / 3)"
            val flatIndex =
              s"((((generate_0_index - 1) / 2) * $innerCount) + ((generate_1_index - 2) / 3))"
            names.foreach: name =>
              assert(
                result.verilogA.contains(
                  s"  electrical $name[0:(($outerCount * $innerCount) - 1)];"
                )
              )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("genvar ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (")) == 2)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"I(${names.head}[$flatIndex], p) <+") &&
                  line.contains("1e-12") &&
                  line.contains(s"ddt(V(${names.head}[$flatIndex], p))")
              ) == 1
            )
            assert(
              result.verilogA.linesIterator.count(
                _.trim ==
                  s"I(${names(1)}[$flatIndex], n) <+ " +
                  s"(V(${names(1)}[$flatIndex], n) / secondResistance);"
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("nested generated node group retains distinct ddt and idt ownership"):
      val snapshot =
        ConstructionKernel.inspect(new Increment43NestedGeneratedNodeGroupMixedOperators)
      assert(
        snapshot == ConstructionKernel.inspect(
          new Increment43NestedGeneratedNodeGroupMixedOperators
        )
      )
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(child.parent.contains(outer.path))
      assert(outer.declarations.isEmpty)
      assert(child.declarations.size == 2)
      assert(child.declarations.distinct.size == 2)
      assert(snapshot.analogRegions.size == 1)
      val analog = snapshot.analogRegions.head
      assert(analog.generatedOwner.contains(child.path))
      assert(analog.generatedInduction.contains(child.induction))
      assert(analog.contributions.size == 2)

      val first = ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeGroupMixedOperators)
      assert(
        first == ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeGroupMixedOperators)
      )
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 2)
      assert(
        first.text.sliding("\"nodal.analog_ddt\"".length).count(_ == "\"nodal.analog_ddt\"") == 1
      )
      assert(
        first.text.sliding("\"nodal.analog_idt\"".length).count(_ == "\"nodal.analog_idt\"") == 1
      )
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 2
      )
      val ddtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_ddt\"")).get
      val idtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_idt\"")).get
      Seq(ddtLine, idtLine).foreach: line =>
        assert(line.contains(s"generated_owner = \"${child.path}\""))
        assert(line.contains(s"generated_induction = \"${child.induction}\""))
      assert(idtLine.contains("state_id"))
      val sourceMapStart = first.text.indexOf("nodal.bridge.source_map")
      val sourceMapEnd = first.text.indexOf("nodal.bridge.topology", sourceMapStart)
      assert(sourceMapStart >= 0)
      assert(sourceMapEnd > sourceMapStart)
      val sourceMap = first.text.substring(sourceMapStart, sourceMapEnd)
      snapshot.generatedRegions.foreach: region =>
        val regionMarker = s"semantic_path = \"${region.path}\""
        val inductionMarker = s"semantic_path = \"${region.induction}\""
        assert(sourceMap.sliding(regionMarker.length).count(_ == regionMarker) == 1)
        assert(sourceMap.sliding(inductionMarker.length).count(_ == inductionMarker) == 1)
        region.declarations.foreach: declaration =>
          val declarationMarker = s"semantic_path = \"$declaration\""
          assert(sourceMap.sliding(declarationMarker.length).count(_ == declarationMarker) == 1)

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def expectNativeFailure(text: String, label: String): Unit =
              val hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))
                .map(value => f"${value & 0xff}%02x").mkString
              NativeCompilerClient.run(
                first.copy(text = text, sha256 = hash),
                NativeCompilerRequest(
                  executable = Path.of(nodalc).toAbsolutePath,
                  arguments = Vector("--nodal-to-verilog-a"),
                  workingDirectory = directory,
                  timeout = Duration.ofSeconds(30)
                )
              ) match
                case _: NativeCompilerFailure => ()
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(
                    false,
                    s"forged nested mixed operator $label accepted: $success"
                  )

            val childOwner = s"generated_owner = \"${child.path}\""
            val parentOwner = s"generated_owner = \"${outer.path}\""
            val forgedDdtLine = ddtLine.replace(childOwner, parentOwner)
            val ddtOffset = first.text.indexOf(ddtLine)
            assert(ddtOffset >= 0)
            assert(forgedDdtLine != ddtLine)
            expectNativeFailure(
              first.text.patch(ddtOffset, forgedDdtLine, ddtLine.length),
              "ddt owner"
            )

            val statePattern = "state_id = \"[^\"]+\"".r
            val forgedIdtLine =
              statePattern.replaceFirstIn(idtLine, "state_id = \"forged.mixed.state\"")
            val idtOffset = first.text.indexOf(idtLine)
            assert(idtOffset >= 0)
            assert(forgedIdtLine != idtLine)
            expectNativeFailure(
              first.text.patch(idtOffset, forgedIdtLine, idtLine.length),
              "idt state"
            )

            def compileNestedGroupMixedOperators() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43NestedGeneratedNodeGroupMixedOperators,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNestedGroupMixedOperators()
            assert(result == compileNestedGroupMixedOperators())
            val names = child.declarations.map(_.split('.').last)
            val outerCount = "((((rows - 1) + 2) - 1) / 2)"
            val innerCount = "((((columns - 2) + 3) - 1) / 3)"
            val flatIndex =
              s"((((generate_0_index - 1) / 2) * $innerCount) + ((generate_1_index - 2) / 3))"
            names.foreach: name =>
              assert(
                result.verilogA.contains(
                  s"  electrical $name[0:(($outerCount * $innerCount) - 1)];"
                )
              )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("electrical ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("genvar ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (")) == 2)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"I(${names.head}[$flatIndex], p) <+") &&
                  line.contains("1e-12") &&
                  line.contains(s"ddt(V(${names.head}[$flatIndex], p))")
              ) == 1
            )
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"V(${names(1)}[$flatIndex], n) <+") &&
                  line.contains(s"idt((V(${names(1)}[$flatIndex], n) / 1), 0)")
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("nested generated node idt retains child-owned row-major state"):
      val snapshot = ConstructionKernel.inspect(new Increment43NestedGeneratedNodeIdt)
      assert(snapshot == ConstructionKernel.inspect(new Increment43NestedGeneratedNodeIdt))
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(child.parent.contains(outer.path))
      assert(outer.declarations.isEmpty)
      assert(child.declarations.size == 1)
      assert(snapshot.analogRegions.size == 1)
      assert(snapshot.analogRegions.head.generatedOwner.contains(child.path))
      assert(snapshot.analogRegions.head.generatedInduction.contains(child.induction))

      val first = ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeIdt)
      assert(first == ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeIdt))
      assert(
        first.text.sliding("\"nodal.generate\"".length).count(_ == "\"nodal.generate\"") == 2
      )
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 1)
      val idtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_idt\"")).get
      assert(idtLine.contains(s"generated_owner = \"${child.path}\""))
      assert(idtLine.contains(s"generated_induction = \"${child.induction}\""))
      assert(idtLine.contains("state_id"))
      snapshot.generatedRegions.foreach: region =>
        assert(snapshot.sourceMap.count(_.semanticPath == region.induction) == 1)
        region.declarations.foreach: declaration =>
          assert(snapshot.sourceMap.count(_.semanticPath == declaration) == 1)

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val childOwner = s"generated_owner = \"${child.path}\""
            val parentOwner = s"generated_owner = \"${outer.path}\""
            val forgedIdtLine = idtLine.replace(childOwner, parentOwner)
            val idtOffset = first.text.indexOf(idtLine)
            assert(idtOffset >= 0)
            assert(forgedIdtLine != idtLine)
            val forged = first.text.patch(idtOffset, forgedIdtLine, idtLine.length)
            val hash = MessageDigest.getInstance("SHA-256")
              .digest(forged.getBytes(StandardCharsets.UTF_8))
              .map(value => f"${value & 0xff}%02x").mkString
            NativeCompilerClient.run(
              first.copy(text = forged, sha256 = hash),
              NativeCompilerRequest(
                executable = Path.of(nodalc).toAbsolutePath,
                arguments = Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case failure: NativeCompilerFailure =>
                assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged nested generated idt accepted: $success")

            def compileNestedIdt() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43NestedGeneratedNodeIdt,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNestedIdt()
            assert(result == compileNestedIdt())
            val nodeName = child.declarations.head.split('.').last
            val outerCount = "((((rows - 1) + 2) - 1) / 2)"
            val innerCount = "((((columns - 2) + 3) - 1) / 3)"
            val flatIndex =
              s"((((generate_0_index - 1) / 2) * $innerCount) + ((generate_1_index - 2) / 3))"
            assert(
              result.verilogA.contains(
                s"  electrical $nodeName[0:(($outerCount * $innerCount) - 1)];"
              )
            )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("genvar ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (")) == 2)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"V($nodeName[$flatIndex], p) <+") &&
                  line.contains(s"idt((V($nodeName[$flatIndex], p) / 1), 0)")
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("nested generated node ddt retains child-owned row-major derivative"):
      val snapshot = ConstructionKernel.inspect(new Increment43NestedGeneratedNodeDdt)
      assert(snapshot == ConstructionKernel.inspect(new Increment43NestedGeneratedNodeDdt))
      assert(snapshot.generatedRegions.size == 2)
      val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
      val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
      assert(child.parent.contains(outer.path))
      assert(outer.declarations.isEmpty)
      assert(child.declarations.size == 1)
      assert(snapshot.analogRegions.size == 1)
      assert(snapshot.analogRegions.head.generatedOwner.contains(child.path))
      assert(snapshot.analogRegions.head.generatedInduction.contains(child.induction))

      val first = ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeDdt)
      assert(first == ScalaToMlirBridge.lower(new Increment43NestedGeneratedNodeDdt))
      assert(
        first.text.sliding("\"nodal.generate\"".length).count(_ == "\"nodal.generate\"") == 2
      )
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 1)
      val ddtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_ddt\"")).get
      assert(ddtLine.contains(s"generated_owner = \"${child.path}\""))
      assert(ddtLine.contains(s"generated_induction = \"${child.induction}\""))
      snapshot.generatedRegions.foreach: region =>
        assert(snapshot.sourceMap.count(_.semanticPath == region.induction) == 1)
        region.declarations.foreach: declaration =>
          assert(snapshot.sourceMap.count(_.semanticPath == declaration) == 1)

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val childOwner = s"generated_owner = \"${child.path}\""
            val parentOwner = s"generated_owner = \"${outer.path}\""
            val forgedDdtLine = ddtLine.replace(childOwner, parentOwner)
            val ddtOffset = first.text.indexOf(ddtLine)
            assert(ddtOffset >= 0)
            assert(forgedDdtLine != ddtLine)
            val forged = first.text.patch(ddtOffset, forgedDdtLine, ddtLine.length)
            val hash = MessageDigest.getInstance("SHA-256")
              .digest(forged.getBytes(StandardCharsets.UTF_8))
              .map(value => f"${value & 0xff}%02x").mkString
            NativeCompilerClient.run(
              first.copy(text = forged, sha256 = hash),
              NativeCompilerRequest(
                executable = Path.of(nodalc).toAbsolutePath,
                arguments = Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case failure: NativeCompilerFailure =>
                assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged nested generated ddt accepted: $success")

            def compileNestedDdt() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43NestedGeneratedNodeDdt,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileNestedDdt()
            assert(result == compileNestedDdt())
            val nodeName = child.declarations.head.split('.').last
            val outerCount = "((((rows - 1) + 2) - 1) / 2)"
            val innerCount = "((((columns - 2) + 3) - 1) / 3)"
            val flatIndex =
              s"((((generate_0_index - 1) / 2) * $innerCount) + ((generate_1_index - 2) / 3))"
            assert(
              result.verilogA.contains(
                s"  electrical $nodeName[0:(($outerCount * $innerCount) - 1)];"
              )
            )
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("genvar ")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (")) == 2)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"I($nodeName[$flatIndex], p) <+") &&
                  line.contains(s"ddt(V($nodeName[$flatIndex], p))")
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("repeated sibling generated equations retain separate owners and target loops"):
      val snapshot =
        ConstructionKernel.inspect(new Increment43RepeatedSiblingGeneratedResistors)
      assert(
        snapshot ==
          ConstructionKernel.inspect(new Increment43RepeatedSiblingGeneratedResistors)
      )
      assert(snapshot.generatedRegions.size == 2)
      assert(snapshot.generatedRegions.forall(_.parent.isEmpty))
      val regions = snapshot.generatedRegions.sortBy(_.path)
      assert(regions.map(_.path).distinct.size == 2)
      assert(regions.map(_.induction).distinct.size == 2)
      assert(regions.flatMap(_.declarations).distinct.size == 2)
      regions.foreach: region =>
        assert(region.declarations.size == 1)
        assert(snapshot.sourceMap.count(_.semanticPath == region.induction) == 1)
        assert(snapshot.sourceMap.count(_.semanticPath == region.declarations.head) == 1)

      val first = ScalaToMlirBridge.lower(new Increment43RepeatedSiblingGeneratedResistors)
      assert(
        first == ScalaToMlirBridge.lower(new Increment43RepeatedSiblingGeneratedResistors)
      )
      assert(first.text.sliding("\"nodal.generate\"".length).count(_ == "\"nodal.generate\"") == 2)
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 2)
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 2
      )
      val accessLines = first.text.linesIterator.filter(_.contains("\"nodal.access\"")).toVector
      assert(accessLines.size == 4)
      assert(accessLines.count(_.contains("kind = \"flow\"")) == 2)
      assert(accessLines.count(_.contains("kind = \"potential\"")) == 2)
      regions.foreach: region =>
        assert(first.text.contains(s"generated_owner = \"${region.path}\""))
        assert(first.text.contains(s"generated_induction = \"${region.induction}\""))

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val secondOwner = s"generated_owner = \"${regions(1).path}\""
            val forgedOwner = s"generated_owner = \"${regions.head.path}\""
            val secondOwnerOffset = first.text.lastIndexOf(secondOwner)
            assert(secondOwnerOffset >= 0)
            val forged = first.text.patch(secondOwnerOffset, forgedOwner, secondOwner.length)
            val hash = MessageDigest.getInstance("SHA-256")
              .digest(forged.getBytes(StandardCharsets.UTF_8))
              .map(value => f"${value & 0xff}%02x").mkString
            NativeCompilerClient.run(
              first.copy(text = forged, sha256 = hash),
              NativeCompilerRequest(
                executable = Path.of(nodalc).toAbsolutePath,
                arguments = Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case failure: NativeCompilerFailure =>
                assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(
                  false,
                  s"cross-range generated owner accepted: ${success.normalizedMlir}"
                )

            def compileRepeated() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43RepeatedSiblingGeneratedResistors,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileRepeated()
            assert(result == compileRepeated())
            val names = regions.map(_.declarations.head.split('.').last)
            val compactUpper = "(((((lanes - 1) + 2) - 1) / 2) - 1)"
            assert(result.verilogA.contains(s"  electrical ${names.head}[0:$compactUpper];"))
            assert(result.verilogA.contains(s"  electrical ${names(1)}[0:(groups - 1)];"))
            assert(
              result.verilogA.contains(
                "for (generate_0_index = 1; generate_0_index < lanes; " +
                  "generate_0_index = generate_0_index + 2)"
              )
            )
            assert(
              result.verilogA.contains(
                "for (generate_1_index = 0; generate_1_index < groups; " +
                  "generate_1_index = generate_1_index + 1)"
              )
            )
            val mapped = "((generate_0_index - 1) / 2)"
            assert(
              result.verilogA.linesIterator.count(
                _.trim ==
                  s"I(${names.head}[$mapped], p) <+ " +
                  s"(V(${names.head}[$mapped], p) / firstResistance);"
              ) == 1
            )
            assert(
              result.verilogA.linesIterator.count(
                _.trim ==
                  s"I(${names(1)}[generate_1_index], n) <+ " +
                  s"(V(${names(1)}[generate_1_index], n) / secondResistance);"
              ) == 1
            )
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_")) == 2)
          finally delete(directory)
        case _ => assert(true)

    test("repeated sibling generated idt equations retain separate owned state"):
      val snapshot =
        ConstructionKernel.inspect(new Increment43RepeatedSiblingGeneratedIdtEquations)
      assert(
        snapshot ==
          ConstructionKernel.inspect(new Increment43RepeatedSiblingGeneratedIdtEquations)
      )
      assert(snapshot.generatedRegions.size == 2)
      assert(snapshot.generatedRegions.forall(_.parent.isEmpty))
      val regions = snapshot.generatedRegions.sortBy(_.path)
      assert(regions.map(_.path).distinct.size == 2)
      assert(regions.map(_.induction).distinct.size == 2)
      assert(regions.flatMap(_.declarations).distinct.size == 2)
      regions.foreach: region =>
        assert(region.declarations.size == 1)
        assert(snapshot.sourceMap.count(_.semanticPath == region.induction) == 1)
        assert(snapshot.sourceMap.count(_.semanticPath == region.declarations.head) == 1)

      val first = ScalaToMlirBridge.lower(new Increment43RepeatedSiblingGeneratedIdtEquations)
      assert(
        first == ScalaToMlirBridge.lower(new Increment43RepeatedSiblingGeneratedIdtEquations)
      )
      assert(first.text.sliding("\"nodal.generate\"".length).count(_ == "\"nodal.generate\"") == 2)
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 2)
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 2
      )
      val accessLines = first.text.linesIterator.filter(_.contains("\"nodal.access\"")).toVector
      assert(accessLines.size == 4)
      assert(accessLines.count(_.contains("kind = \"flow\"")) == 0)
      assert(accessLines.count(_.contains("kind = \"potential\"")) == 4)
      regions.foreach: region =>
        assert(accessLines.count(_.contains(s"generated_owner = \"${region.path}\"")) == 2)
        assert(accessLines.count(_.contains(s"generated_induction = \"${region.induction}\"")) == 2)
      val idtLines = first.text.linesIterator.filter(_.contains("\"nodal.analog_idt\"")).toVector
      assert(idtLines.size == 2)
      assert(idtLines.distinct.size == 2)
      regions.zip(idtLines).foreach: (region, line) =>
        assert(line.contains(s"generated_owner = \"${region.path}\""))
        assert(line.contains(s"generated_induction = \"${region.induction}\""))
        assert(line.contains("state_id"))

      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val secondOwner = s"generated_owner = \"${regions(1).path}\""
            val forgedOwner = s"generated_owner = \"${regions.head.path}\""
            assert(idtLines(1).contains(secondOwner))
            val forgedIdt = idtLines(1).replace(secondOwner, forgedOwner)
            val idtOffset = first.text.indexOf(idtLines(1))
            assert(idtOffset >= 0)
            val forged = first.text.patch(idtOffset, forgedIdt, idtLines(1).length)
            val hash = MessageDigest.getInstance("SHA-256")
              .digest(forged.getBytes(StandardCharsets.UTF_8))
              .map(value => f"${value & 0xff}%02x").mkString
            NativeCompilerClient.run(
              first.copy(text = forged, sha256 = hash),
              NativeCompilerRequest(
                executable = Path.of(nodalc).toAbsolutePath,
                arguments = Vector("--pass-pipeline=builtin.module(nodal-gate-default)"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case failure: NativeCompilerFailure =>
                assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(
                  false,
                  s"cross-range generated idt owner accepted: ${success.normalizedMlir}"
                )

            def compileRepeated() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43RepeatedSiblingGeneratedIdtEquations,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileRepeated()
            assert(result == compileRepeated())
            val names = regions.map(_.declarations.head.split('.').last)
            val compactUpper = "(((((lanes - 1) + 2) - 1) / 2) - 1)"
            assert(result.verilogA.contains(s"  electrical ${names.head}[0:$compactUpper];"))
            assert(result.verilogA.contains(s"  electrical ${names(1)}[0:(groups - 1)];"))
            assert(
              result.verilogA.contains(
                "for (generate_0_index = 1; generate_0_index < lanes; " +
                  "generate_0_index = generate_0_index + 2)"
              )
            )
            assert(
              result.verilogA.contains(
                "for (generate_1_index = 0; generate_1_index < groups; " +
                  "generate_1_index = generate_1_index + 1)"
              )
            )
            val mapped = "((generate_0_index - 1) / 2)"
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"V(${names.head}[$mapped], p) <+") &&
                  line.contains(s"idt((V(${names.head}[$mapped], p) / 1), 0)")
              ) == 1
            )
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"V(${names(1)}[generate_1_index], n) <+") &&
                  line.contains(s"idt((V(${names(1)}[generate_1_index], n) / 1), 0)")
              ) == 1
            )
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_")) == 2)
            assert(result.verilogA.linesIterator.count(_.contains("idt(")) == 2)
          finally delete(directory)
        case _ => assert(true)

    test("generated node groups retain separate arrays and shared symbolic index identity"):
      val snapshot = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNodeGroup)
      assert(snapshot == ConstructionKernel.inspect(new Increment43SymbolicGeneratedNodeGroup))
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.lower == "1")
      assert(region.maximumTripCount == 4)
      val declarationNames = region.declarations.map(_.split('.').last)
      assert(declarationNames.size == 2)
      assert(declarationNames.count(_ == "tap") == 1)
      val generatedName = declarationNames.find(_ != "tap").get
      assert(generatedName.startsWith("analog_node_increment43_range_construction_tests_l31_"))
      region.declarations.foreach: path =>
        assert(snapshot.sourceMap.exists(_.semanticPath == path))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val first = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNodeGroup)
      assert(first == ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNodeGroup))
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileGroup() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43SymbolicGeneratedNodeGroup,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileGroup()
            assert(result == compileGroup())
            assert(result.verilogA.contains("parameter integer lanes = 3 from [2:5];"))
            assert(
              result.verilogA.contains(
                s"  electrical $generatedName[1:(lanes - 1)];\n" +
                  "  electrical tap[1:(lanes - 1)];"
              )
            )
            assert(
              result.mlir.sliding("\"nodal.generate\"".length).count(_ == "\"nodal.generate\"") == 1
            )
            assert(result.mlir.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
            assert(!result.verilogA.contains("genvar"))
            assert(!result.verilogA.contains("analog begin"))
          finally delete(directory)
        case _ => assert(true)

    test("generated node contribution retains its owned indexed target"):
      val snapshot = ConstructionKernel.inspect(new Increment43GeneratedNodeContribution)
      assert(snapshot == ConstructionKernel.inspect(new Increment43GeneratedNodeContribution))
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.declarations.size == 1)
      assert(snapshot.analogRegions.count(_.generatedOwner.contains(region.path)) == 1)
      val first = ScalaToMlirBridge.lower(new Increment43GeneratedNodeContribution)
      assert(first == ScalaToMlirBridge.lower(new Increment43GeneratedNodeContribution))
      assert(first.text.contains("\"nodal.node\""))
      assert(first.text.contains("\"nodal.branch\""))
      assert(first.text.contains("\"nodal.contribute\""))
      assert(first.text.contains(s"generated_owner = \"${region.path}\""))
      assert(first.text.contains(s"generated_induction = \"${region.induction}\""))
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileMixed() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43GeneratedNodeContribution,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileMixed()
            assert(result == compileMixed())
            assert(result.verilogA.contains("  electrical tap[0:(lanes - 1)];"))
            assert(result.verilogA.contains("  genvar generate_0_index;"))
            assert(result.verilogA.linesIterator.count(
              _.trim == "V(tap[generate_0_index], p) <+ 1;"
            ) == 1)
          finally delete(directory)
        case _ => assert(true)

    test("generated node-group contribution retains separate arrays and one selected target"):
      val snapshot = ConstructionKernel.inspect(new Increment43GeneratedNodeGroupContribution)
      assert(
        snapshot == ConstructionKernel.inspect(new Increment43GeneratedNodeGroupContribution)
      )
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.lower == "1")
      assert(region.declarations.size == 2)
      region.declarations.foreach: path =>
        assert(snapshot.sourceMap.exists(_.semanticPath == path))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      assert(snapshot.analogRegions.count(_.generatedOwner.contains(region.path)) == 1)
      val declarationNames = region.declarations.map(_.split('.').last)
      assert(declarationNames.count(_ == "tap") == 1)
      val generatedName = declarationNames.find(_ != "tap").get
      val first = ScalaToMlirBridge.lower(new Increment43GeneratedNodeGroupContribution)
      assert(first == ScalaToMlirBridge.lower(new Increment43GeneratedNodeGroupContribution))
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 1)
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 1
      )
      assert(first.text.contains(s"generated_owner = \"${region.path}\""))
      assert(first.text.contains(s"generated_induction = \"${region.induction}\""))
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileGroup() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43GeneratedNodeGroupContribution,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileGroup()
            assert(result == compileGroup())
            val generatedDeclaration = s"  electrical $generatedName[1:(lanes - 1)];"
            val tapDeclaration = "  electrical tap[1:(lanes - 1)];"
            assert(result.verilogA.contains(generatedDeclaration))
            assert(result.verilogA.contains(tapDeclaration))
            assert(
              result.verilogA.indexOf(generatedDeclaration) <
                result.verilogA.indexOf(tapDeclaration)
            )
            assert(result.verilogA.linesIterator.count(
              _.trim == "V(tap[generate_0_index], p) <+ 1;"
            ) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("electrical ")) == 3)
            assert(result.verilogA.linesIterator.count(_.contains("genvar generate_0_index")) == 1)
          finally delete(directory)
        case _ => assert(true)

    test("generated node-group contributions retain distinct selected targets"):
      val snapshot = ConstructionKernel.inspect(new Increment43GeneratedNodeGroupContributions)
      assert(
        snapshot == ConstructionKernel.inspect(new Increment43GeneratedNodeGroupContributions)
      )
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.lower == "1")
      assert(region.declarations.size == 2)
      region.declarations.foreach: path =>
        assert(snapshot.sourceMap.exists(_.semanticPath == path))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      assert(snapshot.analogRegions.count(_.generatedOwner.contains(region.path)) == 1)
      val declarationNames = region.declarations.map(_.split('.').last)
      assert(declarationNames.count(_ == "tap") == 1)
      val generatedName = declarationNames.find(_ != "tap").get
      val first = ScalaToMlirBridge.lower(new Increment43GeneratedNodeGroupContributions)
      assert(first == ScalaToMlirBridge.lower(new Increment43GeneratedNodeGroupContributions))
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 2)
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 2
      )
      assert(first.text.contains(s"generated_owner = \"${region.path}\""))
      assert(first.text.contains(s"generated_induction = \"${region.induction}\""))
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileGroup() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43GeneratedNodeGroupContributions,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileGroup()
            assert(result == compileGroup())
            val generatedDeclaration = s"  electrical $generatedName[1:(lanes - 1)];"
            val tapDeclaration = "  electrical tap[1:(lanes - 1)];"
            assert(result.verilogA.contains(generatedDeclaration))
            assert(result.verilogA.contains(tapDeclaration))
            assert(result.verilogA.linesIterator.count(
              _.trim == "V(tap[generate_0_index], p) <+ 1;"
            ) == 1)
            assert(result.verilogA.linesIterator.count(
              _.trim == s"V($generatedName[generate_0_index], p) <+ 2;"
            ) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("electrical ")) == 3)
            assert(result.verilogA.linesIterator.count(_.contains("genvar generate_0_index")) == 1)
          finally delete(directory)
        case _ => assert(true)

    test("compound generated node-group contributions retain symbolic array bounds"):
      val snapshot =
        ConstructionKernel.inspect(new Increment43CompoundGeneratedNodeGroupContributions)
      assert(
        snapshot ==
          ConstructionKernel.inspect(new Increment43CompoundGeneratedNodeGroupContributions)
      )
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.lower == "1")
      assert(region.declarations.size == 2)
      region.declarations.foreach: path =>
        assert(snapshot.sourceMap.exists(_.semanticPath == path))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val declarationNames = region.declarations.map(_.split('.').last)
      assert(declarationNames.count(_ == "tap") == 1)
      val generatedName = declarationNames.find(_ != "tap").get
      val first = ScalaToMlirBridge.lower(new Increment43CompoundGeneratedNodeGroupContributions)
      assert(
        first == ScalaToMlirBridge.lower(new Increment43CompoundGeneratedNodeGroupContributions)
      )
      assert(first.text.contains("operator_name = \"add\""))
      assert(first.text.contains("\"nodal.generate\"(%generated_bound_value_"))
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 2)
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 2
      )
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileGroup() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43CompoundGeneratedNodeGroupContributions,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileGroup()
            assert(result == compileGroup())
            assert(result.verilogA.contains("parameter integer lanes = 3 from [2:4];"))
            assert(result.verilogA.contains("parameter integer extra = 1 from [1:2];"))
            assert(
              result.verilogA.contains(
                s"  electrical $generatedName[1:((lanes + extra) - 1)];\n" +
                  "  electrical tap[1:((lanes + extra) - 1)];"
              )
            )
            assert(
              result.verilogA.contains(
                "for (generate_0_index = 1; generate_0_index < (lanes + extra); " +
                  "generate_0_index = generate_0_index + 1)"
              )
            )
            assert(result.verilogA.linesIterator.count(
              _.trim == "V(tap[generate_0_index], p) <+ 1;"
            ) == 1)
            assert(result.verilogA.linesIterator.count(
              _.trim == s"V($generatedName[generate_0_index], p) <+ 2;"
            ) == 1)
          finally delete(directory)
        case _ => assert(true)

    test("one generated node array may feed multiple direct owned branches"):
      val snapshot = ConstructionKernel.inspect(new Increment43SharedGeneratedNodeContributions)
      assert(
        snapshot == ConstructionKernel.inspect(new Increment43SharedGeneratedNodeContributions)
      )
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.declarations.size == 1)
      assert(snapshot.sourceMap.exists(_.semanticPath == region.declarations.head))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val first = ScalaToMlirBridge.lower(new Increment43SharedGeneratedNodeContributions)
      assert(first == ScalaToMlirBridge.lower(new Increment43SharedGeneratedNodeContributions))
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 1)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 2)
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 2
      )
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileShared() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43SharedGeneratedNodeContributions,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileShared()
            assert(result == compileShared())
            val generatedName = region.declarations.head.split('.').last
            assert(result.verilogA.linesIterator.count(
              _.trim == s"V($generatedName[generate_0_index], p) <+ 1;"
            ) == 1)
            assert(result.verilogA.linesIterator.count(
              _.trim == s"V($generatedName[generate_0_index], n) <+ 2;"
            ) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("electrical ")) == 3)
          finally delete(directory)
        case _ => assert(true)

    test("two generated node arrays may form one direct owned branch"):
      val snapshot = ConstructionKernel.inspect(new Increment43GeneratedNodePairContribution)
      assert(
        snapshot == ConstructionKernel.inspect(new Increment43GeneratedNodePairContribution)
      )
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.declarations.size == 2)
      region.declarations.foreach: path =>
        assert(snapshot.sourceMap.exists(_.semanticPath == path))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val declarationNames = region.declarations.map(_.split('.').last)
      assert(declarationNames.count(_ == "tap") == 1)
      val generatedName = declarationNames.find(_ != "tap").get
      val first = ScalaToMlirBridge.lower(new Increment43GeneratedNodePairContribution)
      assert(first == ScalaToMlirBridge.lower(new Increment43GeneratedNodePairContribution))
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 1)
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 1
      )
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compilePair() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43GeneratedNodePairContribution,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compilePair()
            assert(result == compilePair())
            assert(result.verilogA.linesIterator.count(
              _.trim ==
                s"V(tap[generate_0_index], $generatedName[generate_0_index]) <+ 1;"
            ) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("electrical ")) == 2)
          finally delete(directory)
        case _ => assert(true)

    test("generated node resistor retains compact indexed memoryless access"):
      val snapshot = ConstructionKernel.inspect(new Increment43GeneratedNodeResistorContribution)
      assert(
        snapshot == ConstructionKernel.inspect(new Increment43GeneratedNodeResistorContribution)
      )
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.declarations.size == 1)
      assert(snapshot.sourceMap.exists(_.semanticPath == region.declarations.head))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val first = ScalaToMlirBridge.lower(new Increment43GeneratedNodeResistorContribution)
      assert(first == ScalaToMlirBridge.lower(new Increment43GeneratedNodeResistorContribution))
      val accessLine = first.text.linesIterator.find(_.contains("\"nodal.access\"")).get
      assert(first.text.contains("\"nodal.analog_div\""))
      assert(!first.text.contains("\"nodal.analog_ddt\""))
      assert(accessLine.contains(s"generated_owner = \"${region.path}\""))
      assert(accessLine.contains(s"generated_induction = \"${region.induction}\""))
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileResistor() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43GeneratedNodeResistorContribution,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val index = "((generate_0_index - 1) / 2)"
            val result = compileResistor()
            assert(result == compileResistor())
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"I(tap[$index], p) <+") &&
                  line.contains(s"(V(tap[$index], p) / R)")
              ) == 1
            )
            assert(
              result.verilogA.linesIterator.count(
                _.trim == "electrical tap[0:(((((lanes - 1) + 2) - 1) / 2) - 1)];"
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("shared generated node retains independent branch equations"):
      val snapshot = ConstructionKernel.inspect(new Increment43SharedGeneratedNodeResistors)
      assert(
        snapshot == ConstructionKernel.inspect(new Increment43SharedGeneratedNodeResistors)
      )
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.declarations.size == 1)
      assert(snapshot.sourceMap.exists(_.semanticPath == region.declarations.head))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val first = ScalaToMlirBridge.lower(new Increment43SharedGeneratedNodeResistors)
      assert(first == ScalaToMlirBridge.lower(new Increment43SharedGeneratedNodeResistors))
      val accessLines = first.text.linesIterator.filter(_.contains("\"nodal.access\"")).toVector
      // Raw capture includes the two contribution targets as well as the two voltage reads.
      assert(accessLines.size == 4)
      assert(accessLines.count(_.contains("kind = \"flow\"")) == 2)
      assert(accessLines.count(_.contains("kind = \"potential\"")) == 2)
      assert(first.text.linesIterator.count(_.contains("\"nodal.branch\"")) == 2)
      assert(first.text.linesIterator.count(_.contains("\"nodal.contribute\"")) == 2)
      assert(first.text.contains("\"nodal.analog_div\""))
      assert(!first.text.contains("\"nodal.analog_ddt\""))
      assert(accessLines.forall(_.contains(s"generated_owner = \"${region.path}\"")))
      assert(accessLines.forall(_.contains(s"generated_induction = \"${region.induction}\"")))
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileResistor() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43SharedGeneratedNodeResistors,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val index = "((generate_0_index - 1) / 2)"
            val result = compileResistor()
            assert(result == compileResistor())
            val firstEquation = s"I(tap[$index], p) <+ (V(tap[$index], p) / R);"
            val secondEquation = s"I(tap[$index], n) <+ (V(tap[$index], n) / R);"
            val normalizedAccesses =
              result.mlir.linesIterator.filter(_.contains("\"nodal.access\"")).toVector
            assert(normalizedAccesses.size == 2)
            assert(normalizedAccesses.forall(_.contains("kind = \"potential\"")))
            assert(result.verilogA.contains(secondEquation))
            assert(result.verilogA.indexOf(firstEquation) < result.verilogA.indexOf(secondEquation))
            assert(result.verilogA.linesIterator.count(_.contains(" <+ ")) == 2)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"I(tap[$index], p) <+") &&
                  line.contains(s"(V(tap[$index], p) / R)")
              ) == 1
            )
            assert(
              result.verilogA.linesIterator.count(
                _.trim == "electrical tap[0:(((((lanes - 1) + 2) - 1) / 2) - 1)];"
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("peer generated node resistor retains both indexed endpoints"):
      val snapshot =
        ConstructionKernel.inspect(new Increment43GeneratedPeerNodeResistorContribution)
      assert(
        snapshot ==
          ConstructionKernel.inspect(new Increment43GeneratedPeerNodeResistorContribution)
      )
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.declarations.size == 2)
      region.declarations.foreach: path =>
        assert(snapshot.sourceMap.exists(_.semanticPath == path))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val declarationNames = region.declarations.map(_.split('.').last)
      assert(declarationNames.count(_ == "tap") == 1)
      val peerName = declarationNames.find(_ != "tap").get
      val first = ScalaToMlirBridge.lower(new Increment43GeneratedPeerNodeResistorContribution)
      assert(
        first == ScalaToMlirBridge.lower(new Increment43GeneratedPeerNodeResistorContribution)
      )
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 1)
      val accessLines = first.text.linesIterator.filter(_.contains("\"nodal.access\"")).toVector
      assert(accessLines.size == 2)
      assert(accessLines.count(_.contains("kind = \"flow\"")) == 1)
      assert(accessLines.count(_.contains("kind = \"potential\"")) == 1)
      assert(
        first.text.sliding("\"nodal.analog_div\"".length).count(_ == "\"nodal.analog_div\"") == 1
      )
      accessLines.foreach: line =>
        assert(line.contains(s"generated_owner = \"${region.path}\""))
        assert(line.contains(s"generated_induction = \"${region.induction}\""))
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compilePeerResistor() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43GeneratedPeerNodeResistorContribution,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val index = "((generate_0_index - 1) / 2)"
            val result = compilePeerResistor()
            assert(result == compilePeerResistor())
            assert(
              result.verilogA.linesIterator.count(
                _.trim ==
                  s"I(tap[$index], $peerName[$index]) <+ " +
                  s"(V(tap[$index], $peerName[$index]) / R);"
              ) == 1
            )
            assert(
              result.verilogA.linesIterator.count(
                _.trim == "electrical tap[0:(((((lanes - 1) + 2) - 1) / 2) - 1)];"
              ) == 1
            )
            assert(
              result.verilogA.linesIterator.count(
                _.trim ==
                  s"electrical $peerName[0:(((((lanes - 1) + 2) - 1) / 2) - 1)];"
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("generated node access may feed one direct owned ddt contribution"):
      val snapshot = ConstructionKernel.inspect(new Increment43GeneratedNodeDdtContribution)
      assert(snapshot == ConstructionKernel.inspect(new Increment43GeneratedNodeDdtContribution))
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.declarations.size == 1)
      assert(snapshot.sourceMap.exists(_.semanticPath == region.declarations.head))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val first = ScalaToMlirBridge.lower(new Increment43GeneratedNodeDdtContribution)
      assert(first == ScalaToMlirBridge.lower(new Increment43GeneratedNodeDdtContribution))
      val accessLine = first.text.linesIterator.find(_.contains("\"nodal.access\"")).get
      val ddtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_ddt\"")).get
      assert(accessLine.contains(s"generated_owner = \"${region.path}\""))
      assert(accessLine.contains(s"generated_induction = \"${region.induction}\""))
      assert(ddtLine.contains(s"generated_owner = \"${region.path}\""))
      assert(ddtLine.contains(s"generated_induction = \"${region.induction}\""))
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileDdt() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43GeneratedNodeDdtContribution,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileDdt()
            assert(result == compileDdt())
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("I(tap[generate_0_index], p) <+") &&
                  line.contains("ddt(V(tap[generate_0_index], p))")
              ) == 1
            )
            assert(
              result.verilogA.linesIterator.count(
                _.trim == "electrical tap[1:(lanes - 1)];"
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("strided peer generated node access may feed one direct owned ddt contribution"):
      val snapshot =
        ConstructionKernel.inspect(new Increment43StridedGeneratedPeerNodeDdtContribution)
      assert(
        snapshot ==
          ConstructionKernel.inspect(new Increment43StridedGeneratedPeerNodeDdtContribution)
      )
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.lower == "1")
      assert(region.step == "2")
      assert(region.declarations.size == 2)
      region.declarations.foreach: path =>
        assert(snapshot.sourceMap.exists(_.semanticPath == path))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val declarationNames = region.declarations.map(_.split('.').last)
      assert(declarationNames.count(_ == "tap") == 1)
      val peerName = declarationNames.find(_ != "tap").get
      val first = ScalaToMlirBridge.lower(new Increment43StridedGeneratedPeerNodeDdtContribution)
      assert(
        first == ScalaToMlirBridge.lower(new Increment43StridedGeneratedPeerNodeDdtContribution)
      )
      val accessLines = first.text.linesIterator.filter(_.contains("\"nodal.access\"")).toVector
      assert(accessLines.size == 2)
      assert(accessLines.count(_.contains("kind = \"flow\"")) == 1)
      assert(accessLines.count(_.contains("kind = \"potential\"")) == 1)
      accessLines.foreach: line =>
        assert(line.contains(s"generated_owner = \"${region.path}\""))
        assert(line.contains(s"generated_induction = \"${region.induction}\""))
      val ddtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_ddt\"")).get
      assert(ddtLine.contains(s"generated_owner = \"${region.path}\""))
      assert(ddtLine.contains(s"generated_induction = \"${region.induction}\""))
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileDdt() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43StridedGeneratedPeerNodeDdtContribution,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileDdt()
            assert(result == compileDdt())
            val upper = "(((((lanes - 1) + 2) - 1) / 2) - 1)"
            assert(
              result.verilogA.contains(
                s"  electrical $peerName[0:$upper];\n" +
                  s"  electrical tap[0:$upper];"
              )
            )
            assert(
              result.verilogA.contains(
                "for (generate_0_index = 1; generate_0_index < lanes; " +
                  "generate_0_index = generate_0_index + 2)"
              )
            )
            val mapped = "((generate_0_index - 1) / 2)"
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"I(tap[$mapped], $peerName[$mapped]) <+") &&
                  line.contains(s"ddt(V(tap[$mapped], $peerName[$mapped]))")
              ) == 1
            )
            assert(result.verilogA.linesIterator.count(_.contains("electrical ")) == 2)
          finally delete(directory)
        case _ => assert(true)

    test("generated node access may feed one memoryless path into one owned idt state"):
      val snapshot = ConstructionKernel.inspect(new Increment43GeneratedNodeIdtContribution)
      assert(snapshot == ConstructionKernel.inspect(new Increment43GeneratedNodeIdtContribution))
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.declarations.size == 1)
      assert(snapshot.sourceMap.exists(_.semanticPath == region.declarations.head))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val first = ScalaToMlirBridge.lower(new Increment43GeneratedNodeIdtContribution)
      assert(first == ScalaToMlirBridge.lower(new Increment43GeneratedNodeIdtContribution))
      val accessLine = first.text.linesIterator.find(_.contains("\"nodal.access\"")).get
      assert(accessLine.contains(s"generated_owner = \"${region.path}\""))
      assert(accessLine.contains(s"generated_induction = \"${region.induction}\""))
      val idtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_idt\"")).get
      assert(idtLine.contains(s"generated_owner = \"${region.path}\""))
      assert(idtLine.contains(s"generated_induction = \"${region.induction}\""))
      assert(idtLine.contains("state_id"))
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileIdt() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43GeneratedNodeIdtContribution,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileIdt()
            assert(result == compileIdt())
            assert(result.verilogA.contains("  electrical tap[1:(4 - 1)];"))
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(tap[generate_0_index], p) <+") &&
                  line.contains("idt(") &&
                  line.contains("V(tap[generate_0_index], p)") &&
                  line.contains(", 0)")
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("strided peer generated node access may feed one owned idt state"):
      val snapshot =
        ConstructionKernel.inspect(new Increment43StridedGeneratedPeerNodeIdtContribution)
      assert(
        snapshot ==
          ConstructionKernel.inspect(new Increment43StridedGeneratedPeerNodeIdtContribution)
      )
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.lower == "1")
      assert(region.step == "2")
      assert(region.declarations.size == 2)
      region.declarations.foreach: path =>
        assert(snapshot.sourceMap.exists(_.semanticPath == path))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val declarationNames = region.declarations.map(_.split('.').last)
      assert(declarationNames.count(_ == "tap") == 1)
      val peerName = declarationNames.find(_ != "tap").get
      val first =
        ScalaToMlirBridge.lower(new Increment43StridedGeneratedPeerNodeIdtContribution)
      assert(
        first == ScalaToMlirBridge.lower(new Increment43StridedGeneratedPeerNodeIdtContribution)
      )
      val accessLines = first.text.linesIterator.filter(_.contains("\"nodal.access\"")).toVector
      assert(accessLines.size == 2)
      assert(accessLines.forall(_.contains("kind = \"potential\"")))
      accessLines.foreach: line =>
        assert(line.contains(s"generated_owner = \"${region.path}\""))
        assert(line.contains(s"generated_induction = \"${region.induction}\""))
      val idtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_idt\"")).get
      assert(idtLine.contains(s"generated_owner = \"${region.path}\""))
      assert(idtLine.contains(s"generated_induction = \"${region.induction}\""))
      assert(idtLine.contains("state_id"))
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileIdt() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43StridedGeneratedPeerNodeIdtContribution,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileIdt()
            assert(result == compileIdt())
            val upper = "(((((lanes - 1) + 2) - 1) / 2) - 1)"
            assert(
              result.verilogA.contains(
                s"  electrical $peerName[0:$upper];\n" +
                  s"  electrical tap[0:$upper];"
              )
            )
            assert(
              result.verilogA.contains(
                "for (generate_0_index = 1; generate_0_index < lanes; " +
                  "generate_0_index = generate_0_index + 2)"
              )
            )
            val mapped = "((generate_0_index - 1) / 2)"
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"V(tap[$mapped], $peerName[$mapped]) <+") &&
                  line.contains(s"idt((V(tap[$mapped], $peerName[$mapped]) / 1), 0)")
              ) == 1
            )
            assert(result.verilogA.linesIterator.count(_.contains("electrical ")) == 2)
          finally delete(directory)
        case _ => assert(true)

    test("generated node idt result may retain one owned memoryless path"):
      val snapshot =
        ConstructionKernel.inspect(new Increment43StridedGeneratedPeerNodeScaledIdtContribution)
      assert(
        snapshot ==
          ConstructionKernel.inspect(new Increment43StridedGeneratedPeerNodeScaledIdtContribution)
      )
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.declarations.size == 2)
      region.declarations.foreach: path =>
        assert(snapshot.sourceMap.exists(_.semanticPath == path))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val first =
        ScalaToMlirBridge.lower(new Increment43StridedGeneratedPeerNodeScaledIdtContribution)
      assert(
        first ==
          ScalaToMlirBridge.lower(new Increment43StridedGeneratedPeerNodeScaledIdtContribution)
      )
      val idtLine = first.text.linesIterator.find(_.contains("\"nodal.analog_idt\"")).get
      assert(idtLine.contains(s"generated_owner = \"${region.path}\""))
      assert(idtLine.contains(s"generated_induction = \"${region.induction}\""))
      assert(idtLine.contains("state_id"))
      assert(first.text.linesIterator.count(_.contains("\"nodal.analog_div\"")) == 2)
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileScaledIdt() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43StridedGeneratedPeerNodeScaledIdtContribution,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileScaledIdt()
            assert(result == compileScaledIdt())
            val mapped = "((generate_0_index - 1) / 2)"
            val names = region.declarations.map(_.split('.').last)
            val peer = names.find(_ != "tap").get
            assert(result.verilogA.linesIterator.count(_.contains("electrical ")) == 2)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains(s"V(tap[$mapped], $peer[$mapped]) <+") &&
                  line.contains(s"(idt((V(tap[$mapped], $peer[$mapped]) / 1), 0) / 2)")
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("strided generated node groups retain compact source-index selection"):
      val snapshot =
        ConstructionKernel.inspect(new Increment43StridedGeneratedNodeGroupContributions)
      assert(
        snapshot ==
          ConstructionKernel.inspect(new Increment43StridedGeneratedNodeGroupContributions)
      )
      assert(snapshot.generatedRegions.size == 1)
      val region = snapshot.generatedRegions.head
      assert(region.lower == "1")
      assert(region.step == "2")
      assert(region.declarations.size == 2)
      region.declarations.foreach: path =>
        assert(snapshot.sourceMap.exists(_.semanticPath == path))
      assert(snapshot.sourceMap.exists(_.semanticPath == region.induction))
      val declarationNames = region.declarations.map(_.split('.').last)
      assert(declarationNames.count(_ == "tap") == 1)
      val generatedName = declarationNames.find(_ != "tap").get
      val first = ScalaToMlirBridge.lower(new Increment43StridedGeneratedNodeGroupContributions)
      assert(
        first == ScalaToMlirBridge.lower(new Increment43StridedGeneratedNodeGroupContributions)
      )
      assert(first.text.contains("step = 2 : i64"))
      assert(first.text.sliding("\"nodal.node\"".length).count(_ == "\"nodal.node\"") == 2)
      assert(first.text.sliding("\"nodal.branch\"".length).count(_ == "\"nodal.branch\"") == 2)
      assert(
        first.text.sliding("\"nodal.contribute\"".length).count(_ == "\"nodal.contribute\"") == 2
      )
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            def compileGroup() =
              ScalaToMlirBridge
                .compileToVerilogA(
                  new Increment43StridedGeneratedNodeGroupContributions,
                  Path.of(nodalc).toAbsolutePath,
                  Path.of(translator).toAbsolutePath,
                  directory,
                  Duration.ofSeconds(60)
                )
                .fold(
                  failure =>
                    scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                  identity
                )
            val result = compileGroup()
            assert(result == compileGroup())
            val upper = "(((((lanes - 1) + 2) - 1) / 2) - 1)"
            assert(
              result.verilogA.contains(
                s"  electrical $generatedName[0:$upper];\n" +
                  s"  electrical tap[0:$upper];"
              )
            )
            assert(
              result.verilogA.contains(
                "for (generate_0_index = 1; generate_0_index < lanes; " +
                  "generate_0_index = generate_0_index + 2)"
              )
            )
            val mapped = "((generate_0_index - 1) / 2)"
            assert(result.verilogA.linesIterator.count(
              _.trim == s"V(tap[$mapped], p) <+ 1;"
            ) == 1)
            assert(result.verilogA.linesIterator.count(
              _.trim == s"V($generatedName[$mapped], p) <+ 2;"
            ) == 1)
          finally delete(directory)
        case _ => assert(true)

    test("public fixed generated contributions compile to one Verilog-A loop when configured"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.generate\""))
            assert(result.mlir.contains("\"nodal.contribute\""))
            assert(
              result.mlir.contains(
                "generated_owner = \"Increment43GeneratedContribution.generate_0\""
              )
            )
            assert(result.verilogA.contains("  genvar generate_0_index;"))
            assert(
              result.verilogA.contains(
                "for (generate_0_index = 0; generate_0_index < 2; " +
                  "generate_0_index = generate_0_index + 1) begin : generate_0"
              )
            )
            assert(result.verilogA.linesIterator.count(_.trim == "V(p, n) <+ 1;") == 1)
            assert(result.verilogA.linesIterator.count(_.trim == "I(p, n) <+ 1;") == 1)
          finally delete(directory)
        case _ => assert(true)

    test("public generated ddt contribution retains one owned target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedDdtContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_ddt\""))
            val ddtLine = result.mlir.linesIterator.find(_.contains("\"nodal.analog_ddt\"")).get
            assert(ddtLine.contains("generated_owner"))
            assert(ddtLine.contains("generated_induction"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("I(p, n) <+") && line.contains("ddt(V(p, n))")
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("public generated idt contribution retains one owned stateful target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedIdtContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_idt\""))
            val idtLine = result.mlir.linesIterator.find(_.contains("\"nodal.analog_idt\"")).get
            assert(idtLine.contains("generated_owner"))
            assert(idtLine.contains("generated_induction"))
            assert(idtLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") && line.contains("idt(") && line.contains(", 0)")
              ) == 1
            )
          finally delete(directory)
        case _ => assert(true)

    test("public generated transition retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedTransitionContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_transition\""))
            val transitionLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_transition\"")).get
            assert(transitionLine.contains("generated_owner"))
            assert(transitionLine.contains("generated_induction"))
            assert(transitionLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") && line.contains("transition(level)")
              ) == 1
            )
            assert(!result.verilogA.contains("real waveform_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated slew retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedSlewContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_slew\""))
            val slewLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_slew\"")).get
            assert(slewLine.contains("generated_owner"))
            assert(slewLine.contains("generated_induction"))
            assert(slewLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") && line.contains("slew(level)")
              ) == 1
            )
            assert(!result.verilogA.contains("real waveform_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated absdelay retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedAbsdelayContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_absdelay\""))
            val absdelayLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_absdelay\"")).get
            assert(absdelayLine.contains("generated_owner"))
            assert(absdelayLine.contains("generated_induction"))
            assert(absdelayLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") && line.contains("absdelay(level, 1e-09)")
              ) == 1
            )
            assert(!result.verilogA.contains("real waveform_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated abstime retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedAbstimeContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_abstime\""))
            val abstimeLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_abstime\"")).get
            assert(abstimeLine.contains("generated_owner"))
            assert(abstimeLine.contains("generated_induction"))
            assert(!abstimeLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") && line.contains("$abstime")
              ) == 1
            )
            assert(!result.verilogA.contains("real waveform_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated boundStep retains one per-occurrence target effect"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedBoundStep,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_bound_step\""))
            val boundStepLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_bound_step\"")).get
            assert(boundStepLine.contains("generated_owner"))
            assert(boundStepLine.contains("generated_induction"))
            assert(!boundStepLine.contains("state_id"))
            assert(!boundStepLine.contains(" -> f64"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("$bound_step(1e-09);")) == 1)
            assert(!result.verilogA.contains("real waveform_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated laplaceNd retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLaplaceContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_transfer\""))
            val transferLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_transfer\"")).get
            assert(transferLine.contains("transfer_kind = \"laplace_nd\""))
            assert(transferLine.contains("generated_owner"))
            assert(transferLine.contains("generated_induction"))
            assert(transferLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") &&
                  line.contains("laplace_nd(V(p, n), '{1}, '{1})")
              ) == 1
            )
            assert(!result.verilogA.contains("real transfer_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated ziNd retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedZiContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_transfer\""))
            val transferLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_transfer\"")).get
            assert(transferLine.contains("transfer_kind = \"zi_nd\""))
            assert(transferLine.contains("generated_owner"))
            assert(transferLine.contains("generated_induction"))
            assert(transferLine.contains("state_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("V(p, n) <+") &&
                  line.contains("zi_nd(V(p, n), '{1}, '{1}, 1e-09)")
              ) == 1
            )
            assert(!result.verilogA.contains("real transfer_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated whiteNoise retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedWhiteNoiseContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_noise\""))
            val noiseLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_noise\"")).get
            assert(noiseLine.contains("noise_kind = \"white\""))
            assert(noiseLine.contains("generated_owner"))
            assert(noiseLine.contains("generated_induction"))
            assert(noiseLine.contains("source_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("I(p, n) <+") &&
                  line.contains("white_noise(1, \"generated white\")")
              ) == 1
            )
            assert(!result.verilogA.contains("real noise_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated flickerNoise retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedFlickerNoiseContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_noise\""))
            val noiseLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_noise\"")).get
            assert(noiseLine.contains("noise_kind = \"flicker\""))
            assert(noiseLine.contains("generated_owner"))
            assert(noiseLine.contains("generated_induction"))
            assert(noiseLine.contains("source_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("I(p, n) <+") &&
                  line.contains("flicker_noise(1, 1, \"generated flicker\")")
              ) == 1
            )
            assert(!result.verilogA.contains("real noise_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated tableNoise retains one per-occurrence target expression"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedTableNoiseContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_noise\""))
            val noiseLine =
              result.mlir.linesIterator.find(_.contains("\"nodal.analog_noise\"")).get
            assert(noiseLine.contains("noise_kind = \"table\""))
            assert(noiseLine.contains("generated_owner"))
            assert(noiseLine.contains("generated_induction"))
            assert(noiseLine.contains("source_id"))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.linesIterator.count(line =>
                line.contains("I(p, n) <+") &&
                  line.contains("noise_table('{1, 1}, \"generated table\")")
              ) == 1
            )
            assert(!result.verilogA.contains("real noise_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated lexical storage is local to each target occurrence"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            val loop = result.verilogA.indexOf("for (generate_0_index")
            val declaration = result.verilogA.indexOf(
              "real event_Increment43GeneratedLexicalStorage_state = 0.0;"
            )
            val procedure = result.verilogA.indexOf(
              "begin : event_Increment43GeneratedLexicalStorage_procedure"
            )
            val assignment = result.verilogA.indexOf(
              "event_Increment43GeneratedLexicalStorage_state ="
            )
            assert(result.mlir.contains("\"nodal.analog_variable\""))
            assert(result.mlir.contains("\"nodal.analog_variable_read\""))
            assert(result.mlir.contains("\"nodal.analog_assign\""))
            assert(loop >= 0)
            assert(declaration > loop)
            assert(procedure > declaration)
            assert(assignment > procedure)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains(" real event_")) == 2)
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated conditional lexical storage retains authored arms"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedConditionalLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_if\""))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_if_arm\"")) == 2)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.contains(
                "if (event_Increment43GeneratedConditionalLexicalStorage_select)"
              )
            )
            assert(result.verilogA.contains("end else begin"))
            assert(result.verilogA.contains("+ 1.0"))
            assert(result.verilogA.contains("+ 2.0"))
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated case lexical storage retains authored arms"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedCaseLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_case\""))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_case_arm\"")) == 2)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.contains(
                "case (event_Increment43GeneratedCaseLexicalStorage_mode)"
              )
            )
            assert(result.verilogA.contains("0: begin"))
            assert(result.verilogA.contains("default: begin"))
            assert(result.verilogA.contains("+ 3.0"))
            assert(result.verilogA.contains("+ 4.0"))
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop lexical storage retains per-occurrence state"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_loop\""))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(
              result.verilogA.contains(
                "for (event_Increment43GeneratedLoopLexicalStorage_loop_0_index = 0;"
              )
            )
            assert(result.verilogA.contains("+ 5.0"))
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop lexical scope retains per-occurrence state"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopScopeLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_loop\""))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf(
              "for (event_Increment43GeneratedLoopScopeLexicalStorage_loop_0_index = 0;"
            )
            val loopBegin = result.verilogA.indexOf("begin", runtimeLoop)
            val scope = result.verilogA.indexOf("begin", loopBegin + "begin".length)
            val assignment = result.verilogA.indexOf("+ 10.0", scope)
            assert(runtimeLoop >= 0)
            assert(loopBegin > runtimeLoop)
            assert(scope > loopBegin)
            assert(assignment > scope)
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop lexical scope exits retain per-occurrence control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopScopeExitLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (event_")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val loopBegin = result.verilogA.indexOf("begin", runtimeLoop)
            val scope = result.verilogA.indexOf("begin", loopBegin + "begin".length)
            val assignment = result.verilogA.indexOf(" + 10.0);", scope)
            val loopContinue = result.verilogA.indexOf("_continue = 1;", assignment)
            val loopBreak = result.verilogA.indexOf("_break = 1;", loopContinue)
            assert(runtimeLoop >= 0)
            assert(
              loopBegin > runtimeLoop && scope > loopBegin && assignment > scope &&
                loopContinue > assignment && loopBreak > loopContinue
            )
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop lexical exits retain per-occurrence control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopExitLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (event_")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.endsWith("_continue = 1;")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.endsWith("_break = 1;")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val assignment = result.verilogA.indexOf(" + 5.0);", runtimeLoop)
            val loopContinue = result.verilogA.indexOf("_continue = 1;", assignment)
            val loopBreak = result.verilogA.indexOf("_break = 1;", loopContinue)
            assert(runtimeLoop >= 0)
            assert(
              assignment > runtimeLoop && loopContinue > assignment && loopBreak > loopContinue
            )
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop conditional lexical storage retains authored arms"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopConditionalLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_loop\""))
            assert(result.mlir.contains("\"nodal.analog_if\""))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_if_arm\"")) == 2)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf(
              "for (event_Increment43GeneratedLoopConditionalLexicalStorage_loop_0_index = 0;"
            )
            val conditional = result.verilogA.indexOf(
              "if (event_Increment43GeneratedLoopConditionalLexicalStorage_select)",
              runtimeLoop
            )
            assert(runtimeLoop >= 0)
            assert(conditional > runtimeLoop)
            assert(result.verilogA.contains("end else begin"))
            assert(result.verilogA.contains("+ 6.0"))
            assert(result.verilogA.contains("+ 7.0"))
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop conditional-arm exit retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopConditionalExitLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_if\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 0)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val conditional = result.verilogA.indexOf("if (event_", runtimeLoop)
            val assignment = result.verilogA.indexOf(" + 6.0);", conditional)
            val loopContinue = result.verilogA.indexOf("_continue = 1;", assignment)
            val otherwise = result.verilogA.indexOf("else begin", loopContinue)
            assert(runtimeLoop >= 0)
            assert(
              conditional > runtimeLoop && assignment > conditional &&
                loopContinue > assignment && otherwise > loopContinue
            )
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop conditional-arm break retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopConditionalBreakLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_if\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 0)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val conditional = result.verilogA.indexOf("if (event_", runtimeLoop)
            val assignment = result.verilogA.indexOf(" + 16.0);", conditional)
            val loopBreak = result.verilogA.indexOf("_break = 1;", assignment)
            val otherwise = result.verilogA.indexOf("else begin", loopBreak)
            assert(runtimeLoop >= 0)
            assert(
              conditional > runtimeLoop && assignment > conditional &&
                loopBreak > assignment && otherwise > loopBreak
            )
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop conditional lexical scope retains per-occurrence state"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopConditionalScopeLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_loop\""))
            assert(result.mlir.contains("\"nodal.analog_if\""))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf(
              "for (event_Increment43GeneratedLoopConditionalScopeLexicalStorage_loop_0_index = 0;"
            )
            val conditional = result.verilogA.indexOf(
              "if (event_Increment43GeneratedLoopConditionalScopeLexicalStorage_select)",
              runtimeLoop
            )
            val conditionalBegin = result.verilogA.indexOf("begin", conditional)
            val scope = result.verilogA.indexOf(
              "begin",
              conditionalBegin + "begin".length
            )
            val scopedAssignment = result.verilogA.indexOf("+ 11.0", scope)
            val otherwise = result.verilogA.indexOf("else begin", scopedAssignment)
            val otherwiseAssignment = result.verilogA.indexOf("+ 7.0", otherwise)
            assert(runtimeLoop >= 0)
            assert(conditional > runtimeLoop)
            assert(conditionalBegin > conditional)
            assert(scope > conditionalBegin)
            assert(scopedAssignment > scope)
            assert(otherwise > scopedAssignment)
            assert(otherwiseAssignment > otherwise)
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop conditional-arm lexical scope exit retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopConditionalScopeExitLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_if\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 0)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val conditional = result.verilogA.indexOf("if (event_", runtimeLoop)
            val conditionalBegin = result.verilogA.indexOf("begin", conditional)
            val scope = result.verilogA.indexOf("begin", conditionalBegin + "begin".length)
            val assignment = result.verilogA.indexOf(" + 11.0);", scope)
            val loopContinue = result.verilogA.indexOf("_continue = 1;", assignment)
            val otherwise = result.verilogA.indexOf("else begin", loopContinue)
            val otherwiseAssignment = result.verilogA.indexOf(" + 7.0);", otherwise)
            assert(runtimeLoop >= 0)
            assert(
              conditional > runtimeLoop && conditionalBegin > conditional &&
                scope > conditionalBegin && assignment > scope &&
                loopContinue > assignment && otherwise > loopContinue &&
                otherwiseAssignment > otherwise
            )
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop conditional-arm lexical scope break retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopConditionalScopeBreakLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_if\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 0)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val conditional = result.verilogA.indexOf("if (event_", runtimeLoop)
            val conditionalBegin = result.verilogA.indexOf("begin", conditional)
            val scope = result.verilogA.indexOf("begin", conditionalBegin + "begin".length)
            val assignment = result.verilogA.indexOf(" + 13.0);", scope)
            val loopBreak = result.verilogA.indexOf("_break = 1;", assignment)
            val otherwise = result.verilogA.indexOf("else begin", loopBreak)
            val otherwiseAssignment = result.verilogA.indexOf(" + 7.0);", otherwise)
            assert(runtimeLoop >= 0)
            assert(
              conditional > runtimeLoop && conditionalBegin > conditional &&
                scope > conditionalBegin && assignment > scope &&
                loopBreak > assignment && otherwise > loopBreak &&
                otherwiseAssignment > otherwise
            )
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop case lexical storage retains authored arms"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopCaseLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_loop\""))
            assert(result.mlir.contains("\"nodal.analog_case\""))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_case_arm\"")) == 2)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf(
              "for (event_Increment43GeneratedLoopCaseLexicalStorage_loop_0_index = 0;"
            )
            val caseTree = result.verilogA.indexOf(
              "case (event_Increment43GeneratedLoopCaseLexicalStorage_mode)",
              runtimeLoop
            )
            assert(runtimeLoop >= 0)
            assert(caseTree > runtimeLoop)
            assert(result.verilogA.contains("0: begin"))
            assert(result.verilogA.contains("default: begin"))
            assert(result.verilogA.contains("+ 8.0"))
            assert(result.verilogA.contains("+ 9.0"))
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop case-arm exit retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopCaseExitLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_case\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 0)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val caseTree = result.verilogA.indexOf("case (event_", runtimeLoop)
            val defaultArm = result.verilogA.indexOf("default: begin", caseTree)
            val assignment = result.verilogA.indexOf(" + 9.0);", defaultArm)
            val loopBreak = result.verilogA.indexOf("_break = 1;", assignment)
            assert(runtimeLoop >= 0)
            assert(
              caseTree > runtimeLoop && defaultArm > caseTree &&
                assignment > defaultArm && loopBreak > assignment
            )
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop case-arm continue retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopCaseContinueLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_case\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 0)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val caseTree = result.verilogA.indexOf("case (event_", runtimeLoop)
            val defaultArm = result.verilogA.indexOf("default: begin", caseTree)
            val assignment = result.verilogA.indexOf(" + 17.0);", defaultArm)
            val loopContinue = result.verilogA.indexOf("_continue = 1;", assignment)
            assert(runtimeLoop >= 0)
            assert(
              caseTree > runtimeLoop && defaultArm > caseTree &&
                assignment > defaultArm && loopContinue > assignment
            )
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop case-arm scope retains authored lexical nesting"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopCaseScopeLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("\"nodal.analog_loop\""))
            assert(result.mlir.contains("\"nodal.analog_case\""))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_case_arm\"")) == 2)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf(
              "for (event_Increment43GeneratedLoopCaseScopeLexicalStorage_loop_0_index = 0;"
            )
            val caseTree = result.verilogA.indexOf(
              "case (event_Increment43GeneratedLoopCaseScopeLexicalStorage_mode)",
              runtimeLoop
            )
            val arm = result.verilogA.indexOf("0: begin", caseTree)
            val armBegin = result.verilogA.indexOf("begin", arm)
            val scope = result.verilogA.indexOf("begin", armBegin + "begin".length)
            val scopedAssignment = result.verilogA.indexOf("+ 12.0", scope)
            val default = result.verilogA.indexOf("default: begin", scopedAssignment)
            val defaultAssignment = result.verilogA.indexOf("+ 9.0", default)
            assert(runtimeLoop >= 0)
            assert(caseTree > runtimeLoop)
            assert(arm > caseTree)
            assert(armBegin > arm)
            assert(scope > armBegin)
            assert(scopedAssignment > scope)
            assert(default > scopedAssignment)
            assert(defaultAssignment > default)
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop case-arm lexical scope exit retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopCaseScopeExitLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_case\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 0)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val caseTree = result.verilogA.indexOf("case (event_", runtimeLoop)
            val arm = result.verilogA.indexOf("0: begin", caseTree)
            val armBegin = result.verilogA.indexOf("begin", arm)
            val scope = result.verilogA.indexOf("begin", armBegin + "begin".length)
            val assignment = result.verilogA.indexOf(" + 12.0);", scope)
            val loopBreak = result.verilogA.indexOf("_break = 1;", assignment)
            val default = result.verilogA.indexOf("default: begin", loopBreak)
            val defaultAssignment = result.verilogA.indexOf(" + 9.0);", default)
            assert(runtimeLoop >= 0)
            assert(
              caseTree > runtimeLoop && arm > caseTree && armBegin > arm && scope > armBegin &&
                assignment > scope && loopBreak > assignment && default > loopBreak &&
                defaultAssignment > default
            )
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated loop case-arm lexical scope continue retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedLoopCaseScopeContinueLexicalStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_case\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 0)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val caseTree = result.verilogA.indexOf("case (event_", runtimeLoop)
            val arm = result.verilogA.indexOf("0: begin", caseTree)
            val armBegin = result.verilogA.indexOf("begin", arm)
            val scope = result.verilogA.indexOf("begin", armBegin + "begin".length)
            val assignment = result.verilogA.indexOf(" + 13.0);", scope)
            val loopContinue = result.verilogA.indexOf("_continue = 1;", assignment)
            val default = result.verilogA.indexOf("default: begin", loopContinue)
            val defaultAssignment = result.verilogA.indexOf(" + 9.0);", default)
            assert(runtimeLoop >= 0)
            assert(
              caseTree > runtimeLoop && arm > caseTree && armBegin > arm && scope > armBegin &&
                assignment > scope && loopContinue > assignment && default > loopContinue &&
                defaultAssignment > default
            )
            assert(!result.verilogA.contains("@("))
          finally delete(directory)
        case _ => assert(true)

    test("public generated event retains one non-unrolled target occurrence"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedEventTarget,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            val region = ConstructionKernel
              .inspect(new Increment43GeneratedEventTarget)
              .generatedRegions.head
            for operation <- Vector(
                "nodal.analog_procedure",
                "nodal.analog_cross",
                "nodal.analog_on"
              )
            do
              val line = result.mlir.linesIterator.find(_.contains(s"\"$operation\"")).get
              assert(line.contains(s"generated_owner = \"${region.path}\""))
              assert(line.contains(s"generated_induction = \"${region.induction}\""))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("@(cross(1")) == 1)
            assert(!result.verilogA.contains("real event_"))
            assert(!result.verilogA.contains("integer event_"))
          finally delete(directory)
        case _ => assert(true)

    test("public generated event storage is local to each target occurrence"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val module = new Increment43GeneratedEventStorage
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                module,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            val region = ConstructionKernel
              .inspect(new Increment43GeneratedEventStorage)
              .generatedRegions
              .head
            for operation <- Vector(
                "nodal.analog_variable",
                "nodal.analog_variable_read",
                "nodal.analog_assign",
                "nodal.analog_cross",
                "nodal.analog_on"
              )
            do
              val line = result.mlir.linesIterator.find(_.contains(s"\"$operation\"")).get
              assert(line.contains(s"generated_owner = \"${region.path}\""))
              assert(line.contains(s"generated_induction = \"${region.induction}\""))
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("@(cross(1")) == 1)
            val loopOffset = result.verilogA.indexOf("for (generate_0_index")
            val procedureOffset = result.verilogA.indexOf("begin : event_", loopOffset)
            val declarations = result.verilogA.linesIterator
              .filter(line =>
                val trimmed = line.trim
                (trimmed.startsWith("real event_") || trimmed.startsWith("integer event_")) &&
                trimmed.endsWith(";")
              )
              .toVector
            assert(declarations.size == 2)
            for declaration <- declarations do
              val offset = result.verilogA.indexOf(declaration)
              assert(loopOffset < offset)
              assert(offset < procedureOffset)
            val assignments = result.verilogA.linesIterator
              .map(_.trim)
              .filter(line => line.startsWith("event_") && line.contains(" = "))
              .toVector
            assert(assignments.size == 2)
            assert(assignments.exists(_.contains(" + 1.0")))
          finally delete(directory)
        case _ => assert(true)

    test("public generated event storage retains bounded target control trees"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedConditionalEventStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            val region = ConstructionKernel
              .inspect(new Increment43GeneratedConditionalEventStorage)
              .generatedRegions
              .head
            for operation <- Vector(
                "nodal.analog_variable",
                "nodal.analog_variable_read",
                "nodal.analog_assign",
                "nodal.analog_cross",
                "nodal.analog_on"
              )
            do
              val lines = result.mlir.linesIterator.filter(_.contains(s"\"$operation\"")).toVector
              assert(lines.nonEmpty)
              assert(lines.forall(_.contains(s"generated_owner = \"${region.path}\"")))
              assert(lines.forall(_.contains(s"generated_induction = \"${region.induction}\"")))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_if\"")) == 2)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_case\"")) == 2)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("@(cross(1")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("if (event_")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim == "else begin") == 2)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("case (event_")) == 2)
            assert(result.verilogA.linesIterator.count(_.trim == "0: begin") == 2)
            assert(result.verilogA.linesIterator.count(_.trim == "default: begin") == 2)
            assert(result.verilogA.linesIterator.count(_.contains(" + 1.0")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains(" + 2.0")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains(" + 3.0")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains(" + 4.0")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (event_")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains(" + 5.0")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains(" + 6.0")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains(" + 7.0")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains(" + 8.0")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains(" + 9.0")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.endsWith("_continue = 1;")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.endsWith("_break = 1;")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val loopIf = result.verilogA.indexOf("if (event_", runtimeLoop)
            val loopElse = result.verilogA.indexOf("else begin", loopIf)
            val loopContinue = result.verilogA.indexOf("_continue = 1;", loopIf)
            val loopCase = result.verilogA.indexOf("case (event_", runtimeLoop)
            val loopDefault = result.verilogA.indexOf("default: begin", loopCase)
            val loopScopedAssignment = result.verilogA.indexOf(" + 8.0", loopDefault)
            val loopBreak = result.verilogA.indexOf("_break = 1;", loopDefault)
            assert(runtimeLoop >= 0)
            assert(loopIf > runtimeLoop && loopContinue > loopIf && loopContinue < loopElse)
            assert(
              loopCase > runtimeLoop && loopDefault > loopCase &&
                loopScopedAssignment > loopDefault && loopBreak > loopScopedAssignment
            )
          finally delete(directory)
        case _ => assert(true)

    test("public generated event conditional-arm continue retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedConditionalContinueEventStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            val region = ConstructionKernel
              .inspect(new Increment43GeneratedConditionalContinueEventStorage)
              .generatedRegions
              .head
            for operation <- Vector(
                "nodal.analog_variable",
                "nodal.analog_variable_read",
                "nodal.analog_assign"
              )
            do
              val lines = result.mlir.linesIterator.filter(_.contains(s"\"$operation\"")).toVector
              assert(lines.nonEmpty)
              assert(lines.forall(_.contains(s"generated_owner = \"${region.path}\"")))
              assert(lines.forall(_.contains(s"generated_induction = \"${region.induction}\"")))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_if\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 0)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 0)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("@(cross(1")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (event_")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val conditional = result.verilogA.indexOf("if (event_", runtimeLoop)
            val assignment = result.verilogA.indexOf(" + 28.0", conditional)
            val loopContinue = result.verilogA.indexOf("_continue = 1;", assignment)
            val otherwise = result.verilogA.indexOf("else begin", loopContinue)
            val otherwiseAssignment = result.verilogA.indexOf(" + 29.0", otherwise)
            val trailingAssignment = result.verilogA.indexOf(" + 30.0", otherwiseAssignment)
            assert(runtimeLoop >= 0 && conditional > runtimeLoop)
            assert(assignment > conditional && loopContinue > assignment)
            assert(otherwise > loopContinue && otherwiseAssignment > otherwise)
            assert(trailingAssignment > otherwiseAssignment)
          finally delete(directory)
        case _ => assert(true)

    test("public generated event conditional-arm break retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedConditionalBreakEventStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            val region = ConstructionKernel
              .inspect(new Increment43GeneratedConditionalBreakEventStorage)
              .generatedRegions
              .head
            for operation <- Vector(
                "nodal.analog_variable",
                "nodal.analog_variable_read",
                "nodal.analog_assign"
              )
            do
              val lines = result.mlir.linesIterator.filter(_.contains(s"\"$operation\"")).toVector
              assert(lines.nonEmpty)
              assert(lines.forall(_.contains(s"generated_owner = \"${region.path}\"")))
              assert(lines.forall(_.contains(s"generated_induction = \"${region.induction}\"")))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_if\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 0)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 0)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("@(cross(1")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (event_")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val conditional = result.verilogA.indexOf("if (event_", runtimeLoop)
            val assignment = result.verilogA.indexOf(" + 31.0", conditional)
            val loopBreak = result.verilogA.indexOf("_break = 1;", assignment)
            val otherwise = result.verilogA.indexOf("else begin", loopBreak)
            val otherwiseAssignment = result.verilogA.indexOf(" + 32.0", otherwise)
            val trailingAssignment = result.verilogA.indexOf(" + 33.0", otherwiseAssignment)
            assert(runtimeLoop >= 0 && conditional > runtimeLoop)
            assert(assignment > conditional && loopBreak > assignment)
            assert(otherwise > loopBreak && otherwiseAssignment > otherwise)
            assert(trailingAssignment > otherwiseAssignment)
          finally delete(directory)
        case _ => assert(true)

    test("public generated event conditional-arm scope continue retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedConditionalScopeEventStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            val region = ConstructionKernel
              .inspect(new Increment43GeneratedConditionalScopeEventStorage)
              .generatedRegions
              .head
            for operation <- Vector(
                "nodal.analog_variable",
                "nodal.analog_variable_read",
                "nodal.analog_assign",
                "nodal.analog_scope",
                "nodal.analog_continue"
              )
            do
              val lines = result.mlir.linesIterator.filter(_.contains(s"\"$operation\"")).toVector
              assert(lines.nonEmpty)
              if operation != "nodal.analog_scope" && operation != "nodal.analog_continue" then
                assert(lines.forall(_.contains(s"generated_owner = \"${region.path}\"")))
                assert(lines.forall(_.contains(s"generated_induction = \"${region.induction}\"")))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_if\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 0)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("@(cross(1")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (event_")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val conditional = result.verilogA.indexOf("if (event_", runtimeLoop)
            val scopedAssignment = result.verilogA.indexOf(" + 10.0", conditional)
            val scopedContinue = result.verilogA.indexOf("_continue = 1;", scopedAssignment)
            val otherwise = result.verilogA.indexOf("else begin", scopedContinue)
            val otherwiseAssignment = result.verilogA.indexOf(" + 11.0", otherwise)
            val trailingAssignment = result.verilogA.indexOf(" + 12.0", otherwiseAssignment)
            assert(runtimeLoop >= 0 && conditional > runtimeLoop)
            assert(scopedAssignment > conditional && scopedContinue > scopedAssignment)
            assert(otherwise > scopedContinue && otherwiseAssignment > otherwise)
            assert(trailingAssignment > otherwiseAssignment)
          finally delete(directory)
        case _ => assert(true)

    test("public generated event conditional-arm scope break retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedConditionalScopeBreakEventStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            val region = ConstructionKernel
              .inspect(new Increment43GeneratedConditionalScopeBreakEventStorage)
              .generatedRegions
              .head
            for operation <- Vector(
                "nodal.analog_variable",
                "nodal.analog_variable_read",
                "nodal.analog_assign",
                "nodal.analog_scope",
                "nodal.analog_break"
              )
            do
              val lines = result.mlir.linesIterator.filter(_.contains(s"\"$operation\"")).toVector
              assert(lines.nonEmpty)
              if operation != "nodal.analog_scope" && operation != "nodal.analog_break" then
                assert(lines.forall(_.contains(s"generated_owner = \"${region.path}\"")))
                assert(lines.forall(_.contains(s"generated_induction = \"${region.induction}\"")))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_if\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 0)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("@(cross(1")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (event_")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val conditional = result.verilogA.indexOf("if (event_", runtimeLoop)
            val scopedAssignment = result.verilogA.indexOf(" + 13.0", conditional)
            val scopedBreak = result.verilogA.indexOf("_break = 1;", scopedAssignment)
            val otherwise = result.verilogA.indexOf("else begin", scopedBreak)
            val otherwiseAssignment = result.verilogA.indexOf(" + 14.0", otherwise)
            val trailingAssignment = result.verilogA.indexOf(" + 15.0", otherwiseAssignment)
            assert(runtimeLoop >= 0 && conditional > runtimeLoop)
            assert(scopedAssignment > conditional && scopedBreak > scopedAssignment)
            assert(otherwise > scopedBreak && otherwiseAssignment > otherwise)
            assert(trailingAssignment > otherwiseAssignment)
          finally delete(directory)
        case _ => assert(true)

    test("public generated event case-arm scope continue retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedCaseScopeContinueEventStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            val region = ConstructionKernel
              .inspect(new Increment43GeneratedCaseScopeContinueEventStorage)
              .generatedRegions
              .head
            for operation <- Vector(
                "nodal.analog_variable",
                "nodal.analog_variable_read",
                "nodal.analog_assign",
                "nodal.analog_scope",
                "nodal.analog_continue"
              )
            do
              val lines = result.mlir.linesIterator.filter(_.contains(s"\"$operation\"")).toVector
              assert(lines.nonEmpty)
              if operation != "nodal.analog_scope" && operation != "nodal.analog_continue" then
                assert(lines.forall(_.contains(s"generated_owner = \"${region.path}\"")))
                assert(lines.forall(_.contains(s"generated_induction = \"${region.induction}\"")))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_case\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 0)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("@(cross(1")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (event_")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val caseTree = result.verilogA.indexOf("case (event_", runtimeLoop)
            val arm = result.verilogA.indexOf("0: begin", caseTree)
            val scopedAssignment = result.verilogA.indexOf(" + 16.0", arm)
            val scopedContinue = result.verilogA.indexOf("_continue = 1;", scopedAssignment)
            val default = result.verilogA.indexOf("default: begin", scopedContinue)
            val defaultAssignment = result.verilogA.indexOf(" + 17.0", default)
            val trailingAssignment = result.verilogA.indexOf(" + 18.0", defaultAssignment)
            assert(runtimeLoop >= 0 && caseTree > runtimeLoop && arm > caseTree)
            assert(scopedAssignment > arm && scopedContinue > scopedAssignment)
            assert(default > scopedContinue && defaultAssignment > default)
            assert(trailingAssignment > defaultAssignment)
          finally delete(directory)
        case _ => assert(true)

    test("public generated event case-arm scope break retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedCaseScopeBreakEventStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            val region = ConstructionKernel
              .inspect(new Increment43GeneratedCaseScopeBreakEventStorage)
              .generatedRegions
              .head
            for operation <- Vector(
                "nodal.analog_variable",
                "nodal.analog_variable_read",
                "nodal.analog_assign",
                "nodal.analog_scope",
                "nodal.analog_break"
              )
            do
              val lines = result.mlir.linesIterator.filter(_.contains(s"\"$operation\"")).toVector
              assert(lines.nonEmpty)
              if operation != "nodal.analog_scope" && operation != "nodal.analog_break" then
                assert(lines.forall(_.contains(s"generated_owner = \"${region.path}\"")))
                assert(lines.forall(_.contains(s"generated_induction = \"${region.induction}\"")))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_case\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 0)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("@(cross(1")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (event_")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val caseTree = result.verilogA.indexOf("case (event_", runtimeLoop)
            val arm = result.verilogA.indexOf("0: begin", caseTree)
            val armAssignment = result.verilogA.indexOf(" + 22.0", arm)
            val default = result.verilogA.indexOf("default: begin", armAssignment)
            val scopedAssignment = result.verilogA.indexOf(" + 23.0", default)
            val scopedBreak = result.verilogA.indexOf("_break = 1;", scopedAssignment)
            val trailingAssignment = result.verilogA.indexOf(" + 24.0", scopedBreak)
            assert(runtimeLoop >= 0 && caseTree > runtimeLoop && arm > caseTree)
            assert(armAssignment > arm && default > armAssignment)
            assert(scopedAssignment > default && scopedBreak > scopedAssignment)
            assert(trailingAssignment > scopedBreak)
          finally delete(directory)
        case _ => assert(true)

    test("public generated event case-arm continue retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedCaseContinueEventStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            val region = ConstructionKernel
              .inspect(new Increment43GeneratedCaseContinueEventStorage)
              .generatedRegions
              .head
            for operation <- Vector(
                "nodal.analog_variable",
                "nodal.analog_variable_read",
                "nodal.analog_assign"
              )
            do
              val lines = result.mlir.linesIterator.filter(_.contains(s"\"$operation\"")).toVector
              assert(lines.nonEmpty)
              assert(lines.forall(_.contains(s"generated_owner = \"${region.path}\"")))
              assert(lines.forall(_.contains(s"generated_induction = \"${region.induction}\"")))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_case\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 0)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 0)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("@(cross(1")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (event_")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val caseTree = result.verilogA.indexOf("case (event_", runtimeLoop)
            val arm = result.verilogA.indexOf("0: begin", caseTree)
            val armAssignment = result.verilogA.indexOf(" + 19.0", arm)
            val default = result.verilogA.indexOf("default: begin", armAssignment)
            val defaultAssignment = result.verilogA.indexOf(" + 20.0", default)
            val loopContinue = result.verilogA.indexOf("_continue = 1;", defaultAssignment)
            val trailingAssignment = result.verilogA.indexOf(" + 21.0", loopContinue)
            assert(runtimeLoop >= 0 && caseTree > runtimeLoop && arm > caseTree)
            assert(armAssignment > arm && default > armAssignment)
            assert(defaultAssignment > default && loopContinue > defaultAssignment)
            assert(trailingAssignment > loopContinue)
          finally delete(directory)
        case _ => assert(true)

    test("public generated event case-arm break retains authored control"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43GeneratedCaseBreakEventStorage,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            val region = ConstructionKernel
              .inspect(new Increment43GeneratedCaseBreakEventStorage)
              .generatedRegions
              .head
            for operation <- Vector(
                "nodal.analog_variable",
                "nodal.analog_variable_read",
                "nodal.analog_assign"
              )
            do
              val lines = result.mlir.linesIterator.filter(_.contains(s"\"$operation\"")).toVector
              assert(lines.nonEmpty)
              assert(lines.forall(_.contains(s"generated_owner = \"${region.path}\"")))
              assert(lines.forall(_.contains(s"generated_induction = \"${region.induction}\"")))
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_loop\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_case\"")) == 1)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_scope\"")) == 0)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_continue\"")) == 0)
            assert(result.mlir.linesIterator.count(_.contains("\"nodal.analog_break\"")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("for (generate_0_index")) == 1)
            assert(result.verilogA.linesIterator.count(_.contains("@(cross(1")) == 1)
            assert(result.verilogA.linesIterator.count(_.trim.startsWith("for (event_")) == 1)
            val runtimeLoop = result.verilogA.indexOf("for (event_")
            val caseTree = result.verilogA.indexOf("case (event_", runtimeLoop)
            val arm = result.verilogA.indexOf("0: begin", caseTree)
            val armAssignment = result.verilogA.indexOf(" + 25.0", arm)
            val default = result.verilogA.indexOf("default: begin", armAssignment)
            val defaultAssignment = result.verilogA.indexOf(" + 26.0", default)
            val loopBreak = result.verilogA.indexOf("_break = 1;", defaultAssignment)
            val trailingAssignment = result.verilogA.indexOf(" + 27.0", loopBreak)
            assert(runtimeLoop >= 0 && caseTree > runtimeLoop && arm > caseTree)
            assert(armAssignment > arm && default > armAssignment)
            assert(defaultAssignment > default && loopBreak > defaultAssignment)
            assert(trailingAssignment > loopBreak)
          finally delete(directory)
        case _ => assert(true)

    test("public symbolic generated contributions retain one parameterized Verilog-A loop"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43SymbolicGeneratedContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("upper = @lanes"))
            assert(result.verilogA.contains("parameter integer lanes = 4 from [1:8];"))
            assert(
              result.verilogA.contains(
                "for (generate_0_index = 0; generate_0_index < lanes; " +
                  "generate_0_index = generate_0_index + 1) begin : generate_0"
              )
            )
            assert(result.verilogA.linesIterator.count(_.trim == "V(p, n) <+ 1;") == 1)
          finally delete(directory)
        case _ => assert(true)

    test("public compound generated contributions retain one structural Verilog-A loop"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new Increment43CompoundGeneratedContribution,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](new java.lang.AssertionError(failure.toString)).get,
                identity
              )
            assert(result.mlir.contains("operator_name = \"add\""))
            assert(result.mlir.contains("\"nodal.generate\"(%generated_bound_value_"))
            assert(result.verilogA.contains("parameter integer lanes = 3 from [1:4];"))
            assert(result.verilogA.contains("parameter integer extra = 1 from [1:2];"))
            assert(
              result.verilogA.contains(
                "for (generate_0_index = 0; generate_0_index < (lanes + extra); " +
                  "generate_0_index = generate_0_index + 1) begin : generate_0"
              )
            )
            assert(result.verilogA.linesIterator.count(_.trim == "V(p, n) <+ 1;") == 1)
          finally delete(directory)
        case _ => assert(true)

    test("native verifies generated lexical storage ownership when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val snapshot = ConstructionKernel.inspect(new Increment43GeneratedLexicalStorage)
            val region = snapshot.generatedRegions.head
            val document = ScalaToMlirBridge.fromSnapshot(snapshot)
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                assert(success.normalizedMlir.contains("\"nodal.analog_variable\""))
                assert(success.normalizedMlir.contains(region.path))
              case failure: NativeCompilerFailure =>
                scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")

            val marker = s"generated_owner = \"${region.path}\""
            val variableOffset = document.text.indexOf("\"nodal.analog_variable\"")
            val ownerOffset = document.text.indexOf(marker, variableOffset)
            assert(variableOffset >= 0)
            assert(ownerOffset >= variableOffset)
            val forgedText = document.text.patch(
              ownerOffset,
              s"generated_owner = \"${region.path}.forged\"",
              marker.length
            )
            val forged = document.copy(text = forgedText)
            NativeCompilerClient.run(forged, request) match
              case _: NativeCompilerSuccess =>
                scala.Predef.assert(false, "forged generated storage ownership was accepted")
              case failure: NativeCompilerFailure =>
                assert(failure.standardError.contains("NODAL-ITERATION-043-004"))
          finally delete(directory)

    test("native verifies generated structured lexical storage when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val snapshot = ConstructionKernel.inspect(
              new Increment43GeneratedStructuredLexicalStorage
            )
            val region = snapshot.generatedRegions.head
            val document = ScalaToMlirBridge.fromSnapshot(snapshot)
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                assert(success.normalizedMlir.contains("\"nodal.analog_if\""))
                assert(success.normalizedMlir.contains("\"nodal.analog_case\""))
                assert(success.normalizedMlir.contains("\"nodal.analog_loop\""))
              case failure: NativeCompilerFailure =>
                scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")

            val marker = s"generated_owner = \"${region.path}\""
            val assignmentOffset = document.text.indexOf("\"nodal.analog_assign\"")
            val ownerOffset = document.text.indexOf(marker, assignmentOffset)
            assert(assignmentOffset >= 0)
            assert(ownerOffset >= assignmentOffset)
            val forgedText = document.text.patch(
              ownerOffset,
              s"generated_owner = \"${region.path}.forged\"",
              marker.length
            )
            NativeCompilerClient.run(document.copy(text = forgedText), request) match
              case failure: NativeCompilerFailure =>
                assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
              case success: NativeCompilerSuccess =>
                scala.Predef.assert(false, s"forged structured ownership accepted: $success")
          finally delete(directory)

    test("native verifies generated event occurrence ownership when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val snapshot = ConstructionKernel.inspect(new Increment43GeneratedEventStorage)
            val region = snapshot.generatedRegions.head
            val document = ScalaToMlirBridge.fromSnapshot(snapshot)
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                assert(success.normalizedMlir.contains("\"nodal.analog_cross\""))
                assert(success.normalizedMlir.contains("\"nodal.analog_on\""))
                for operation <- Vector(
                    "nodal.analog_variable",
                    "nodal.analog_variable_read",
                    "nodal.analog_assign",
                    "nodal.analog_cross",
                    "nodal.analog_on"
                  )
                do
                  val line = success.normalizedMlir.linesIterator
                    .find(_.contains(s"\"$operation\""))
                    .get
                  assert(line.contains(s"generated_owner = \"${region.path}\""))
                  assert(line.contains(s"generated_induction = \"${region.induction}\""))
              case failure: NativeCompilerFailure =>
                scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")

            val marker = s"generated_owner = \"${region.path}\""
            for operation <- Vector(
                "nodal.analog_variable",
                "nodal.analog_variable_read",
                "nodal.analog_assign",
                "nodal.analog_cross",
                "nodal.analog_on"
              )
            do
              val operationOffset = document.text.indexOf(s"\"$operation\"")
              val ownerOffset = document.text.indexOf(marker, operationOffset)
              assert(operationOffset >= 0)
              assert(ownerOffset >= operationOffset)
              val forgedText = document.text.patch(
                ownerOffset,
                s"generated_owner = \"${region.path}.forged\"",
                marker.length
              )
              NativeCompilerClient.run(document.copy(text = forgedText), request) match
                case failure: NativeCompilerFailure =>
                  assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(
                    false,
                    s"forged generated event ownership accepted: $success"
                  )
          finally delete(directory)

    test(
      "native preserves public nested generation and rejects aliased identities when configured"
    ):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val snapshot = ConstructionKernel.inspect(new Increment43RepeatedSymbolicGeneratedNodes)
            val document = ScalaToMlirBridge.fromSnapshot(snapshot)
            val roots = snapshot.generatedRegions.filter(_.parent.isEmpty).sortBy(_.path)
            val child = snapshot.generatedRegions.find(_.parent.nonEmpty).get
            assert(roots.size == 2)
            val request = NativeCompilerRequest(
              executable = Path.of(executable).toAbsolutePath,
              arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
              workingDirectory = directory,
              timeout = Duration.ofSeconds(30)
            )
            NativeCompilerClient.run(document, request) match
              case success: NativeCompilerSuccess =>
                snapshot.generatedRegions.foreach(region =>
                  assert(success.normalizedMlir.contains(region.path))
                )
              case failure: NativeCompilerFailure =>
                scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")

            val forgeries = Vector(
              roots(1).path -> roots.head.path,
              roots(1).induction -> roots.head.induction,
              child.path -> s"${snapshot.root}.orphan"
            )
            for (original, replacement) <- forgeries do
              val text = document.text.replace(original, replacement)
              assert(text != document.text)
              val hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))
                .map(value => f"${value & 0xff}%02x").mkString
              NativeCompilerClient.run(document.copy(text = text, sha256 = hash), request) match
                case failure: NativeCompilerFailure =>
                  assert(failure.exitCode.contains(1))
                  assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(
                    false,
                    s"aliased ownership accepted: ${success.normalizedMlir}"
                  )
          finally delete(directory)

    test("native independently rejects forged envelope effects when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val document = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNode)
            assert(document.text.contains("effects = [\"topology\"]"))
            for replacement <- Vector("effects = [\"generate\"]", "effects = []") do
              val text = document.text.replace("effects = [\"topology\"]", replacement)
              assert(text != document.text)
              val hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))
                .map(value => f"${value & 0xff}%02x").mkString
              val forged = document.copy(text = text, sha256 = hash)
              NativeCompilerClient.run(
                forged,
                NativeCompilerRequest(
                  executable = Path.of(executable).toAbsolutePath,
                  arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
                  workingDirectory = directory,
                  timeout = Duration.ofSeconds(30)
                )
              ) match
                case failure: NativeCompilerFailure =>
                  assert(failure.exitCode.contains(1))
                  assert(failure.diagnostic.code == "NODAL-PARAMETER-ENVELOPE-001")
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(
                    false,
                    s"invalid envelope accepted: ${success.normalizedMlir}"
                  )
          finally delete(directory)

    test("native independently rejects forged generated ownership when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val snapshot = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)
            val region = snapshot.generatedRegions.head
            val document = ScalaToMlirBridge.fromSnapshot(snapshot)
            val forgeries = Vector(
              s"""generated_owner = "${region.path}"""" ->
                s"""generated_owner = "${region.path}.forged"""",
              s"""generated_induction = "${region.induction}"""" ->
                s"""generated_induction = "${region.induction}.forged""""
            )
            for (needle, replacement) <- forgeries do
              assert(document.text.contains(needle))
              val text = document.text.replace(needle, replacement)
              val hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))
                .map(value => f"${value & 0xff}%02x").mkString
              val forged = document.copy(text = text, sha256 = hash)
              NativeCompilerClient.run(
                forged,
                NativeCompilerRequest(
                  executable = Path.of(executable).toAbsolutePath,
                  arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
                  workingDirectory = directory,
                  timeout = Duration.ofSeconds(30)
                )
              ) match
                case failure: NativeCompilerFailure =>
                  assert(failure.exitCode.contains(1))
                  assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")
                case success: NativeCompilerSuccess =>
                  scala.Predef.assert(
                    false,
                    s"forged generated ownership accepted: ${success.normalizedMlir}"
                  )
          finally delete(directory)
