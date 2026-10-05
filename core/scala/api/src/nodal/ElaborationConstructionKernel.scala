package nodal

import java.lang.ScopedValue

private[nodal] object ConstructionKernel:
  private val Current: ScopedValue[ConstructionSession] =
    ScopedValue.newInstance[ConstructionSession]()

  private def active: Option[ConstructionSession] =
    AnalogUserFunctionRuntime.rejectEffect()
    if Current.isBound then Some(Current.get) else None

  private def elaborate(top: => Module, options: EmitOptions): (Emission, ConstructionSnapshot) =
    ConstructorCaptureRuntime.withSession:
      AnalogProceduralConstruction.withSession:
        val session = new ConstructionSession(options)
        var result: Option[(Emission, ConstructionSnapshot)] = None
        ScopedValue.where(Current, session).run(
          new Runnable:
            override def run(): Unit =
              val root = top
              result = Some(session.finish(root))
        )
        result.getOrElse(
          scala.util.Failure[(Emission, ConstructionSnapshot)](
            new IllegalStateException("construction transaction did not publish a result")
          ).get
        )

  def emit(top: => Module, options: EmitOptions): Emission = elaborate(top, options)._1

  def inspect(top: => Module, options: EmitOptions = EmitOptions()): ConstructionSnapshot =
    elaborate(top, options)._2

  def failConstructor(code: String, message: String, site: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(code, message, Some(site)))
    ).get

  def prepareConstructorAllocation(site: String): Int = active match
    case Some(session) => session.prepareConstructorAllocation(site)
    case None =>
      failConstructor(
        "NODAL-CONSTRUCTOR-LIFECYCLE-022",
        "captured Module allocation requires an active construction transaction",
        site
      )

  def poisonConstructor(failure: Throwable, site: String): Unit =
    active.foreach(_.poisonConstructor(failure, site))

  def abortConstructorAllocation(
      depth: Int,
      failure: Throwable,
      site: String,
      invalidate: Boolean
  ): Unit =
    active.foreach(_.abortConstructorAllocation(depth, failure, site, invalidate))

  def constructorDepth: Int = active.map(_.constructorDepth).getOrElse(0)

  def bindConstructorParameters(
      module: Module,
      parameters: Vector[ConstructorCapturedParameter]
  ): Unit =
    active match
      case Some(session) => session.bindConstructorParameters(module, parameters)
      case None =>
        failConstructor(
          "NODAL-CONSTRUCTOR-LIFECYCLE-022",
          "constructor parameter binding requires an active construction transaction",
          module.getClass.getName
        )

  def commitConstructorAllocation(
      module: Module,
      parameters: Vector[ConstructorCapturedParameter]
  ): Boolean = active match
    case Some(session) => session.commitConstructorAllocation(module, parameters)
    case None =>
      failConstructor(
        "NODAL-CONSTRUCTOR-LIFECYCLE-022",
        "captured Module commit requires an active construction transaction",
        module.getClass.getName
      )

  def instance[M <: Module](module: M): (Instance[M], Boolean) = active match
    case Some(session) => session.instance(module, captured = false)
    case None =>
      failConstructor(
        "NODAL-CONSTRUCTOR-LIFECYCLE-022",
        "child Instance creation requires an active construction transaction",
        module.getClass.getName
      )

  def beginModule(module: Module): Unit = active.foreach(_.beginModule(module))

  def currentModulePath: String = active
    .map(_.currentModulePath)
    .getOrElse(
      scala.util.Failure[String](
        new IllegalStateException(
          "procedural module construction has no active transaction"
        )
      ).get
    )

  def requireNoGeneratedEffect(role: String): Unit =
    active.foreach(_.requireNoGeneratedEffect(role))

  def generatedRegion(
      lower: Int | Expr[Integer],
      upperExclusive: Int | Expr[Integer],
      step: Int | Expr[Integer],
      maximum: Option[Int]
  )(body: Expr[Integer] => Unit): Unit =
    active match
      case Some(session) =>
        session.withGeneratedRegion(lower, upperExclusive, step, maximum)(body)
      case None =>
        scala.util.Failure[Unit](
          new IllegalStateException("hdlRange requires an active Module")
        ).get

  def captureAnalogProceduralSource: Option[AnalogProceduralRuntime.Source] =
    active.flatMap(_.captureAnalogProceduralSource)

  def analogDimension(value: Any): Option[String] =
    active.map(_.inferAnalogDimension(value).canonical)

  def analogReferencePath(value: Any): Option[String] = active.flatMap(_.pathOf(value))

  def analogEventConstant(value: Any): Option[Double] = value match
    case expression: KernelExpr[?] if expression.literal.exists(_.kind == "integer") =>
      expression.literal.flatMap(_.value.toDoubleOption)
    case _ => active.flatMap(_.waveformConstant(value))
  def registerDomain(domain: ClockDomain, kind: KernelDomainKind): Unit =
    active.foreach(_.registerDomain(domain, kind))

  def declare(
      value: AnyRef,
      kind: KernelSignalKind,
      dataType: Option[DataType[? <: Data]] = None,
      explicitName: Option[String] = None,
      domain: Option[ClockDomain] = None,
      attributes: Vector[(String, Any)] = Vector.empty
  ): Unit = active.foreach(
    _.registerDeclaration(value, kind, dataType, explicitName, domain, attributes)
  )

  def expression(value: AnyRef): Unit =
    if !AnalogUserFunctionRuntime.capture(value) then active.foreach(_.registerExpression(value))

  def defineUserFunction[A <: Data](
      name: String,
      resultType: DataType[A],
      dimension: PhysicalDimension,
      body: AnalogFunctionBody => Expr[A]
  ): AnalogFunction[A] = active match
    case Some(session) =>
      session.requireNoGeneratedEffect("analog function definition")
      session.defineUserFunction(name, resultType, dimension, body)
    case None => AnalogUserFunctionRuntime.fail(1, "analog function requires an active Module")

  def callUserFunction[A <: Data](
      function: AnalogFunction[A],
      arguments: Vector[Expr[?]]
  ): Expr[A] = active match
    case Some(session) =>
      session.requireNoGeneratedEffect("analog function call")
      session.callUserFunction(function, arguments)
    case None => AnalogUserFunctionRuntime.fail(1, "analog function call requires an active Module")

  def analogFunction(value: KernelExpr[Real], id: String): Unit =
    if !AnalogUserFunctionRuntime.capture(value) then
      active.foreach: session =>
        session.requireNoGeneratedEffect("analog function expression")
        session.registerAnalogFunction(value, id)

  def continuousOperator(
      value: KernelExpr[Real],
      operation: String,
      input: Expr[Real],
      initialValue: Option[Expr[Real]]
  ): Unit =
    active.foreach: session =>
      session.requireNoGeneratedEffect(s"continuous-time operator '$operation'")
      session.registerContinuousOperator(value, operation, input, initialValue)

  def transferOperator(
      value: KernelExpr[Real],
      kind: String,
      numeratorSize: Int,
      denominatorSize: Int,
      inputs: Vector[Expr[Real]]
  ): Unit = active match
    case Some(session) =>
      session.requireNoGeneratedEffect(s"analog transfer operator '$kind'")
      session.registerTransferOperator(value, kind, numeratorSize, denominatorSize, inputs)
    case None => AnalogTransferContract.fail(1, "transfer state requires an active Module")

  def noiseOperator(
      value: KernelExpr[Real],
      kind: String,
      label: String,
      inputs: Vector[Expr[Real]]
  ): Unit = active match
    case Some(session) =>
      session.requireNoGeneratedEffect(s"analog noise operator '$kind'")
      session.registerNoiseOperator(value, kind, label, inputs)
    case None => AnalogNoiseContract.fail(1, "noise sources require an active Module construction")

  def waveformOperator(
      value: KernelExpr[Real],
      operation: String,
      inputs: Vector[Expr[Real]]
  ): Unit =
    active.foreach: session =>
      session.requireNoGeneratedEffect(s"analog waveform operator '$operation'")
      session.registerWaveformOperator(value, operation, inputs)

  def waveformForbidden[A](body: => A): A = active match
    case Some(session) => session.withWaveformForbidden(body)
    case None => body

  def attachInstance(instance: Instance[? <: Module], child: Module): Unit =
    active.foreach(_.attachInstance(instance, child))

  def bindDefault(instance: Instance[?], domain: ClockDomain): Unit =
    active.foreach(_.bindDefault(instance, domain))

  def bindNamed(instance: Instance[?], requirement: ClockDomain, domain: ClockDomain): Unit =
    active.foreach(_.bindNamed(instance, requirement, domain))

  def connectNodes(left: AnyRef, right: AnyRef): Unit =
    active.foreach(_.connectNodes(left, right))

  def overrideParameter(instance: Instance[?], parameter: Any, value: Any): Unit =
    active.foreach(_.overrideParameter(instance, parameter, value))

  def domainBlock[A](domain: ClockDomain)(body: => A): A = active match
    case Some(session) => session.withDomain(domain)(body)
    case None => body

  def block[A](body: => A): A = body

  def analogBlock[A](body: => A): A = active match
    case Some(session) =>
      session.requireNoGeneratedEffect("analog continuous region")
      session.withAnalogRegion(body)
    case None => body

  def analogSemanticBlock[A](
      kind: AnalogEquationRuntime.RegionKind
  )(body: => A): A = active match
    case Some(session) =>
      session.requireNoGeneratedEffect(s"analog ${kind.toString.toLowerCase} region")
      session.withAnalogSemanticRegion(kind)(body)
    case None => body

  def analogEquation(
      left: Expr[Real],
      right: Expr[Real],
      options: EquationOptions
  ): Unit = active.foreach(_.recordAnalogEquation(left, right, options))

  def initialAnalogEquation(
      left: Expr[Real],
      right: Expr[Real],
      options: InitialEquationOptions
  ): Unit = active.foreach(_.recordInitialAnalogEquation(left, right, options))

  def analogContribution(
      target: Expr[Real],
      value: Expr[Real],
      options: ContributionOptions
  ): Unit = active.foreach(_.recordAnalogContribution(target, value, options))

  def shortAnalogContribution(target: Expr[Real], value: Expr[Real]): Unit =
    active.foreach(_.recordShortAnalogContribution(target, value))

  def currentDomain: Option[ClockDomain] = active.flatMap(_.currentDomain)

  def operation(kind: String, values: Any*): Unit = active.foreach(_.operation(kind, values*))
