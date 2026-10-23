package nodal

/** Internal result-type obligation. The operation's existing owner supplies inference; this record
  * retains only the exact dependency edges and once-only finalization lifecycle.
  */
private[nodal] final class KernelResultTypeIntent(
    val dependencies: Vector[Any],
    val resolve: () => KernelTypeDescriptor
)

private[nodal] object DeferredResultType:
  def pending(value: Any): Boolean = value match
    case expression: KernelExpr[?] => !expression.typeFinalized
    case signal: Signal[?] => pendingType(signal.dataType)
    case endpoint: ResolvedDigital[?, ?] => pendingType(endpoint.dataType)
    case _ => false

  /** The existing descriptor/intent remains the proof obligation after once-only sealing. */
  def hasTypeObligation(value: Any): Boolean = value match
    case expression: KernelExpr[?] => expression.typeIntent.nonEmpty
    case signal: Signal[?] => signal.dataType.isInstanceOf[KernelInstanceDataType[?]]
    case endpoint: ResolvedDigital[?, ?] =>
      endpoint.dataType.isInstanceOf[KernelInstanceDataType[?]]
    case _ => false

  private def pendingType(value: DataType[?]): Boolean = value match
    case deferred: KernelInstanceDataType[?] => !deferred.finalized
    case _ => false

  def intent(dependencies: Vector[Any])(resolve: => KernelTypeDescriptor)
      : Option[KernelResultTypeIntent] =
    if dependencies.size > 4098 then
      scala.util.Failure[Nothing](new ConstructionException(KernelDiagnostic(
        "NODAL-HIERARCHY-058-TYPE",
        "deferred type dependency inventory exceeds the finite operand budget"
      ))).get
    Option.when(dependencies.exists(pending))(
      new KernelResultTypeIntent(dependencies, () => resolve)
    )

  def requireFinal(values: Iterable[Any], operation: String): Unit =
    if values.exists(pending) then
      scala.util.Failure[Nothing](new ConstructionException(KernelDiagnostic(
        "NODAL-HIERARCHY-058-TYPE",
        s"$operation requires finalized boundary operand types; deferred inference is not implemented"
      ))).get
