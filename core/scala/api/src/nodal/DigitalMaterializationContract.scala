package nodal

import java.nio.charset.StandardCharsets

/** Authored intent only. Actual materialization policy is owned by the native SSA analysis. */
private[nodal] enum KernelDigitalIntent:
  case Named(name: String)
  case Keep(reason: String)

private[nodal] object DigitalMaterializationContract:
  val Operation = "digital_alias"
  val MaximumNameBytes = 255
  val MaximumReasonBytes = 1024

  private def fail(message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic("NODAL-MATERIALIZATION-055-001", message))
    ).get

  def validate(intent: KernelDigitalIntent): Unit = intent match
    case KernelDigitalIntent.Named(name) =>
      if Option(name).forall(value =>
          value.getBytes(StandardCharsets.UTF_8).length > MaximumNameBytes ||
            !value.matches("[A-Za-z_][A-Za-z0-9_$]*")
        )
      then fail("digital name requires a nonempty portable identifier of at most 255 bytes")
    case KernelDigitalIntent.Keep(reason) =>
      if Option(reason).forall(value =>
          value.isEmpty || value != value.trim ||
            value.getBytes(StandardCharsets.UTF_8).length > MaximumReasonBytes ||
            value.exists(character => character < ' ' || character == 127.toChar)
        )
      then
        fail(
          "digital keep requires nonempty trimmed text without control characters, at most 1024 bytes"
        )

  private def alias[A <: Data](value: Expr[A], intent: KernelDigitalIntent): Expr[A] =
    validate(intent)
    val descriptor = CandidateRuntime.expressionDataType(value)
      .map(CandidateRuntime.typeDescriptor).getOrElse(
        fail("digital naming and keep require an operand with an exact type")
      )
    DigitalCombinationalContract.validateValueType(descriptor)
    val expression = new KernelExpr[A](
      Vector(value),
      resultType = Some(descriptor),
      operation = Some(Operation),
      materializationIntent = Some(intent)
    )
    ConstructionKernel.expression(expression)
    expression

  def named[A <: Data](value: Expr[A], name: String): Expr[A] =
    alias(value, KernelDigitalIntent.Named(name))

  def keep[A <: Data](value: Expr[A], reason: String): Expr[A] =
    alias(value, KernelDigitalIntent.Keep(reason))
