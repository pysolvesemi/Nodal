package nodal.internal.testkit.automaticpolicy

import nodal.*

/** Source-only automatic activation donor: no manual enable/begin/drain. */
object AutomaticPolicy:
  private var session: ConstructionSession = null
  private var root: AutomaticPolicyRoot = null
  private var result: KernelExpr[Data] = null
  private var callbacks = 0
  private var constructorDebt = 0L
  private val policy = Some("registered-construction-diagnostic-131072")

  private def call(target: AnyRef, name: String, args: AnyRef*): AnyRef =
    val method = (target.getClass.getMethods.toVector ++ target.getClass.getDeclaredMethods)
      .find(m =>
        (m.getName == name || m.getName.endsWith("$$" + name)) &&
          m.getParameterCount == args.size
      ).getOrElse(throw new AssertionError(name))
    method.setAccessible(true)
    try method.invoke(target, args*)
    catch case failure: java.lang.reflect.InvocationTargetException => throw failure.getCause
  private def field(target: AnyRef, name: String): AnyRef =
    val matches = target.getClass.getDeclaredFields.filter(_.getName.endsWith(name))
    assert(matches.length == 1, name)
    matches.head.setAccessible(true)
    matches.head.get(target)
  private def owner: Option[AnyRef] =
    field(session, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]]
  private def debt: Long = field(session, "constructionRequestWork").asInstanceOf[Long]

  def bind(value: AutomaticPolicyRoot): Unit =
    session = call(ConstructionKernel, "active").asInstanceOf[Option[ConstructionSession]].get
    root = value
    assert(owner.isEmpty && debt == 0L)
    session.bindDiagnosticConstructionWork(value)
    assert(owner.isEmpty && session.diagnosticConstructionWorkPolicyTag == policy)

  def complete(input: Expr[Vec[SInt]]): KernelExpr[Data] =
    assert(owner.isEmpty)
    val body = DigitalCollectionCallback.reductionBody(DigitalCollectionCallback.checked2(
      (left: Expr[SInt], _: Expr[SInt]) =>
        callbacks += 1
        left
    ))
    result = session.reserveReductionConstructionIntent(input, body, "reduce_left")
    assert(result.registeredConstructionOwnedBy(session))
    assert(result.constructionRequest.get.session eq session)
    call(session, "completeReductionSingleton", result, input, Vector(0))
    assert(result.constructionFinalized && callbacks == 0)
    assert(CandidateRuntime.expressionDataType(result).exists(value =>
      CandidateRuntime.typeDescriptor(value) == KernelTypeDescriptor("SInt", Vector(2))
    ))
    assert(result.constructionDependencies.head.asInstanceOf[AnyRef] eq input)
    assert(owner.isEmpty)
    constructorDebt = debt
    assert(constructorDebt > 0L && constructorDebt <= 65536L)
    result

  def main(args: Array[String]): Unit =
    assert(args.isEmpty)
    val snapshot = ConstructionKernel.inspect(new AutomaticPolicyRoot)
    assert(root != null && (root.result eq result))
    assert(result.constructionFinalized && result.typeFinalized &&
      result.registeredConstructionOwnedBy(session))
    assert(session.diagnosticConstructionWorkPolicyTag == policy && callbacks == 0)
    assert(field(session, "constructorFailure") == None)
    val automaticOwner = owner.get
    assert(field(automaticOwner, "incremental") == Boolean.box(true))
    assert(field(automaticOwner, "poisoned") == Boolean.box(false))
    val remaining = field(automaticOwner, "remaining").asInstanceOf[Long]
    val finalDebt = debt
    assert(finalDebt >= constructorDebt && finalDebt <= 65536L)
    assert(remaining >= 0L && remaining < 131072L)
    assert(131072L - remaining >= finalDebt)
    val names = field(automaticOwner, "frozenNames").asInstanceOf[Option[SemanticOriginResult]].get
    val reference = field(session, "expressionIds")
      .asInstanceOf[java.util.IdentityHashMap[AnyRef, ExpressionRef]].get(result)
    assert(reference eq result.constructionRequest.get.resultReference)
    assert(snapshot.root == names.modulePaths(reference.module))
    assert(snapshot.modules.size == 1 && snapshot.digitalAssignments.isEmpty)
    val inputReference = field(session, "declarationIds")
      .asInstanceOf[java.util.IdentityHashMap[AnyRef, DeclarationRef]].get(root.input)
    assert(inputReference != null)
    val resultPath = names.expressionPaths((reference.module, reference.index))
    val projected = snapshot.shapeIndices.filter(_.path == resultPath)
    assert(projected.size == 1)
    val index = projected.head
    assert(index.owner == snapshot.root &&
      index.input == names.declarationPaths((inputReference.module, inputReference.index)))
    assert(index.indices == Vector("0") && index.resultDimensions.isEmpty &&
      index.sourceUses.isEmpty && index.generatedScope.isEmpty && index.region.isEmpty)
    assert(result.constructionDependencies.head.asInstanceOf[AnyRef] eq root.input)
    // Read-only repeat observations cannot replace owner, spend, refund, or clear debt.
    assert(owner.exists(_ eq automaticOwner))
    assert(field(automaticOwner, "remaining").asInstanceOf[Long] == remaining && debt == finalDebt)
    println(
      s"PASS automatic policy=${policy.get} constructorDebt=$constructorDebt finalDebt=$finalDebt initial=131072 remaining=$remaining cumulativeDebit=${131072L -
          remaining} callbacks=$callbacks"
    )
