package nodal

/** Compiler-owned behavioral capture. The kernel checks digital callbacks before execution. */
private[nodal] object SequentialRuntime:
  val PredicateOperation = "behavioral_predicate"

  def integerOperand(value: Int | Expr[Integer]): Expr[Integer] =
    StructuralIfRuntime.integerOperand(value)

  def staticCompare(left: Expr[Integer], right: Expr[Integer], operation: String): Expr[Bool] =
    ConstructionKernel.behavioralComparison(left, right, operation)

  def and(left: Expr[Bool], right: Expr[Bool]): Expr[Bool] =
    CandidateRuntime.booleanExpr("bool_and", left, right)

  def or(left: Expr[Bool], right: Expr[Bool]): Expr[Bool] =
    CandidateRuntime.booleanExpr("bool_or", left, right)

  def not(value: Expr[Bool]): Expr[Bool] = CandidateRuntime.booleanExpr("bool_not", value)

  def when(condition: Expr[Bool], body: () => Unit): Unit =
    if AnalogProceduralConstruction.procedureActive then
      CandidateRuntime.block(CandidateRuntime.event(condition), body())
    else ConstructionKernel.behavioralBranch("when", Some(condition), body)

  def elsewhen(condition: Expr[Bool], body: () => Unit): Unit =
    if AnalogProceduralConstruction.procedureActive then
      CandidateRuntime.block(CandidateRuntime.event(condition), body())
    else ConstructionKernel.behavioralBranch("elsewhen", Some(condition), body)

  def otherwise(body: () => Unit): Unit =
    if AnalogProceduralConstruction.procedureActive then CandidateRuntime.block(body())
    else ConstructionKernel.behavioralBranch("otherwise", None, body)

  def requireAnalogProcedure(): Unit =
    if !AnalogProceduralConstruction.procedureActive then missing()

  def missing(): Nothing =
    val failure = new ConstructionException(KernelDiagnostic(
      "NODAL-SEQUENTIAL-056-MISSING",
      "behavioral guard requires typed effect validation by the Nodal compiler plugin"
    ))
    ConstructionKernel.poisonConstructor(failure, "behavioral guard")
    scala.util.Failure[Nothing](failure).get
