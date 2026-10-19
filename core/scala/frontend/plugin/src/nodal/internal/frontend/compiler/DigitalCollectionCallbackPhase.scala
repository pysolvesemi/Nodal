package nodal.internal.frontend.compiler

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Annotations.Annotation
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.DefaultGetterName
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report

import scala.collection.mutable

/** Closed typed effect profiles for callbacks captured once as hardware region templates. Method
  * symbols and actual same-compilation bodies are authoritative, never source spellings.
  * Unsupported host staging fails closed; this is deliberately not a general Scala effect system.
  */
private[compiler] final class DigitalCollectionCallbackPhase extends PluginPhase:
  val phaseName = "nodalDigitalCollectionCallbacks"
  override val runsAfter = Set("nodalConstructorCapture")
  override val runsBefore = Set("pickler")

  private val definitions = mutable.HashMap.empty[Symbol, DefDef]
  private val values = mutable.HashMap.empty[Symbol, ValDef]
  private val templates = mutable.HashMap.empty[Symbol, Template]
  private val sourceDefinitions = mutable.HashMap.empty[Symbol, (Tree, Context)]
  private var scanned = false
  private var collections = Set.empty[Symbol]
  private var pureMethods = Set.empty[Symbol]
  private var pureModules = Set.empty[Symbol]
  private var factories = Set.empty[Symbol]
  private var iterationFactories = Set.empty[Symbol]
  private var loops = Set.empty[Symbol]
  private var ranges = Set.empty[Symbol]
  private var loopMethods = Set.empty[Symbol]
  private var loopModules = Set.empty[Symbol]
  private var rangeMethods = Set.empty[Symbol]
  private var rangeModules = Set.empty[Symbol]
  private var rangeClosures = Set.empty[Symbol]
  private var selectionMethods = Set.empty[Symbol]
  private var constructorClosures = Set.empty[Symbol]
  private var structuralMethods = Set.empty[Symbol]
  private var structuralBranches = Set.empty[Symbol]
  private var structuralFactories = Set.empty[Symbol]
  private var structuralModules = Set.empty[Symbol]
  private val structural = new StructuralIfCapture

  private enum Profile:
    case Pure, Loop, Range, StructuralRange, Selection, StructuralIf

  private final class Budget:
    var visitedNodes = 0

  private def fail(code: String, message: String, tree: Tree)(using Context): Boolean =
    report.error(s"NODAL-COLLECTION-CALLBACK-$code: $message", tree.srcPos)
    false

  private def methods(owner: Symbol, names: String*)(using Context): Set[Symbol] =
    names.flatMap: name =>
      owner.info.member(termName(name)).alternatives.map(_.symbol)
    .toSet

  private def moduleMethods(owner: String, names: String*)(using Context): Set[Symbol] =
    val module = requiredModule(owner)
    pureModules += module
    methods(module, names*)

  private def initializeProfile()(using Context): Unit =
    val owner = requiredModule("nodal.CoreSemanticsCandidateApi$package")
    collections = methods(owner, "map", "zipWith", "fold", "scan", "reduce", "reduceBalancedTree")
    pureModules += owner
    factories = methods(requiredModule("nodal.DigitalCollectionCallback"), "checked1", "checked2")
    val structuralRuntime = requiredModule("nodal.StructuralIfRuntime")
    structuralModules += structuralRuntime
    structuralModules += requiredModule("nodal.DigitalCollectionCallback")
    structuralMethods = methods(structuralRuntime, "compare", "integerOperand", "and", "or", "not")
    structuralBranches = methods(structuralRuntime, "value", "statement")
    structuralFactories = methods(requiredModule("nodal.DigitalCollectionCallback"), "checked0")
    factories ++= structuralFactories
    pureMethods =
      methods(
        requiredClass("nodal.DigitalBitsOperators"),
        "&",
        "|",
        "^",
        "unary_~",
        "===",
        "=/=",
        "concat",
        "extract",
        "<<",
        "logicalShiftRight"
      ) ++
        methods(
          requiredClass("nodal.DigitalNumericOperators"),
          "*",
          "/",
          "%",
          "min",
          "max",
          "unary_-",
          "<",
          "<=",
          ">",
          ">="
        ) ++
        methods(requiredClass("nodal.DigitalUIntOperators"), "-") ++
        moduleMethods("nodal.SInt", "+", "-", ">>") ++
        moduleMethods("nodal.Bits", ">>") ++
        moduleMethods("nodal.UInt") ++
        moduleMethods("nodal.DigitalCombinationalApi$package", "select") ++
        moduleMethods("nodal.DigitalAggregateApi$package", "field", "value", "member") ++
        methods(requiredClass("nodal.StructMember"), "bind") ++
        methods(
          owner,
          "S",
          ">>",
          "extend",
          "truncate",
          "wrap",
          "saturate",
          "resizeChecked",
          "toSigned",
          "toUnsigned",
          "reinterpretSigned",
          "reinterpretUnsigned",
          "at",
          "row",
          "plane",
          "slice",
          "flatten",
          "reshape",
          "named",
          "keep"
        ) ++
        moduleMethods("nodal.CandidateApi$package", "U", "B", "integer", "&&", "||", "unary_!") ++
        moduleMethods("nodal.DigitalEnumApi$package", "asBits", "isValid", "===") ++
        moduleMethods("scala.<:<", "refl")
    val arithmetic = moduleMethods("nodal.CandidateApi$package", "+", "-", "*", "/", "unary_-")
    pureMethods = pureMethods ++ arithmetic.filter { symbol =>
      symbol.info.finalResultType.dealias match
        case AppliedType(_, argument :: Nil) =>
          argument.typeSymbol == requiredClass("nodal.UInt") ||
          argument.typeSymbol == requiredClass("nodal.Integer")
        case _ => false
    }
    // Scalar host arithmetic is useful for literal widths. Host control flow remains excluded.
    List("scala.Int", "scala.Long", "scala.Boolean").foreach: name =>
      pureMethods ++= methods(
        requiredClass(name),
        "+",
        "-",
        "*",
        "unary_-",
        "&",
        "|",
        "^",
        "unary_~",
        "<<",
        ">>",
        ">>>",
        "<",
        "<=",
        ">",
        ">=",
        "unary_!",
        "toInt",
        "toLong"
      )

    // Default expressions are part of the trusted library method's contract too (e.g. slice axis).
    pureMethods ++= defaultGetters(pureMethods)
    initializeIterationProfiles(owner)

  private def defaultGetters(symbols: Set[Symbol])(using Context): Set[Symbol] =
    symbols.flatMap: method =>
      method.paramSymss.flatten.filter(_.isTerm).zipWithIndex.flatMap: (parameter, index) =>
        if parameter.is(HasDefault) then
          method.owner.info.member(DefaultGetterName(method.name.asTermName, index))
            .alternatives.map(_.symbol)
        else Nil

  private def initializeIterationProfiles(owner: Symbol)(using Context): Unit =
    loops = methods(owner, "loop")
    ranges = methods(requiredModule("nodal.CandidateApi$package"), "hdlRange")
    iterationFactories =
      methods(requiredModule("nodal.DigitalIterationCallback"), "checkedLoop", "checkedRange")
    factories ++= iterationFactories
    val selections = requiredModule("nodal.SignalSelection")
    loopModules += selections
    loopMethods = methods(requiredClass("nodal.LoopIndex"), "value") ++
      methods(requiredClass("nodal.Signal"), "writable", ":=") ++
      methods(requiredClass("nodal.SignalSelection"), "value", ":=") ++
      methods(selections, "at", "row", "plane", "slice", "flatten", "reshape")
    loopMethods ++= defaultGetters(loopMethods)

    def rangeModule(owner: String, names: String*): Set[Symbol] =
      val symbol = requiredModule(owner)
      rangeModules += symbol
      methods(symbol, names*)

    // These are exact library entry points with typed construction effects. Arguments, by-name
    // bodies, selectors, user helpers and actual Module initializers remain subject to this walker.
    rangeMethods = methods(
      requiredClass("nodal.Module"),
      "param",
      "in",
      "out",
      "inout",
      "node",
      "wire",
      "variable",
      "instance",
      "connect"
    ) ++
      methods(requiredClass("nodal.Node"), "<>") ++
      methods(
        requiredClass("nodal.NodeArray"),
        "<>",
        "at",
        "row",
        "plane",
        "slice",
        "flatten",
        "reshape"
      ) ++
      methods(requiredClass("nodal.Variable"), ":=", "writable", "initialValue") ++
      methods(requiredClass("nodal.VariableSelection"), ":=", "value") ++
      rangeModule("nodal.VariableSelection", "at", "row", "plane", "slice", "flatten", "reshape") ++
      rangeModule(
        "nodal.CandidateApi$package",
        "analog",
        "V",
        "I",
        "+",
        "-",
        "*",
        "/",
        "unary_-",
        ">",
        ">=",
        "<",
        "<=",
        "<+",
        "real",
        "A",
        "Ohm",
        "kOhm",
        "F",
        "pF",
        "s",
        "ns",
        "ddt",
        "idt",
        "transition",
        "slew",
        "absdelay",
        "abstime",
        "boundStep",
        "on",
        "cross",
        "above",
        "timer",
        "initialStep",
        "finalStep"
      ) ++
      rangeModule(
        "nodal.ContinuousTimeCandidateApi$package",
        "analogProcedure",
        "equations",
        "initialEquations",
        "contributions",
        "whiteNoise",
        "flickerNoise",
        "tableNoise"
      ) ++
      rangeModule(
        "nodal.AnalogControlFlowApi$package",
        "analogConditional",
        "analogWhen",
        "analogElseWhen",
        "analogStaticWhen",
        "analogStaticElseWhen",
        "analogOtherwise",
        "analogCase",
        "analogStaticCase",
        "analogCaseArm",
        "analogCaseDefault",
        "analogRepeat",
        "analogLoop",
        "analogBreak",
        "analogContinue"
      ) ++
      // Runtime analog context reads are existing range effects, never static/pure
      // predicate evidence or digital structural branch effects.
      rangeModule("nodal.EnvironmentContext", "temperature", "nominalTemperature") ++
      rangeModule("nodal.AnalysisContext", "time") ++
      rangeModule("nodal.AnalogTransferApi$package", "laplaceNd", "ziNd") ++
      rangeModule(
        "nodal.AnalogMath",
        "abs",
        "min",
        "max",
        "sqrt",
        "hypot",
        "atan2",
        "pow",
        "exp",
        "ln",
        "log10",
        "sin",
        "cos",
        "tan",
        "asin",
        "acos",
        "atan",
        "sinh",
        "cosh",
        "tanh",
        "asinh",
        "acosh",
        "atanh",
        "floor",
        "ceil"
      ) ++
      rangeModule("nodal.CandidateRuntime", "block") ++
      rangeModule("nodal.NoiseId", "apply") ++
      rangeModule("nodal.NoisePoint", "apply") ++
      rangeModule("nodal.NoiseOptions", "apply") ++
      rangeModule("nodal.Param", "liftReal") ++
      rangeModule("nodal.UInt", "apply") ++
      rangeModule("nodal.SInt", "apply") ++
      rangeModule("nodal.Bits", "apply") ++
      rangeModule("nodal.Vec", "apply", "fill") ++
      rangeModule("scala.Predef", "intWrapper") ++
      methods(requiredClass("scala.runtime.RichInt"), "to", "until") ++
      methods(requiredClass("nodal.Event"), "or", "||") ++
      rangeModule("scala.collection.immutable.Seq", "apply") ++
      rangeModule("scala.Tuple2", "apply") ++
      rangeModule("scala.Tuple3", "apply")
    List("nodal.Electrical", "nodal.Real", "nodal.Integer", "nodal.Bool", "nodal.Edge").foreach:
      owner =>
        rangeModules += requiredModule(owner)
    selectionMethods = methods(
      requiredClass("nodal.NodeArray"),
      "at",
      "row",
      "plane",
      "slice",
      "flatten",
      "reshape"
    )
    selectionMethods ++= defaultGetters(selectionMethods)
    rangeClosures = methods(requiredClass("nodal.Instance"), "apply", "param")
    constructorClosures = rangeModule("nodal.ConstructorCaptureRuntime", "guard", "allocate")
    rangeMethods ++= rangeClosures ++ constructorClosures ++
      rangeModule("nodal.ConstructorCaptureRuntime", "carrierReal", "omittedReal")
    rangeMethods ++= defaultGetters(rangeMethods ++ ranges)

  override def prepareForUnit(tree: Tree)(using Context): Context =
    if !scanned then
      scanned = true
      initializeProfile()
      val scanBase = ctx
      scanBase.run.nn.units.foreach: unit =>
        given Context = scanBase.fresh.setCompilationUnit(unit)
        val collect = new TreeTraverser:
          override def traverse(current: Tree)(using Context): Unit =
            current match
              case definition: DefDef =>
                structural.admitResult(definition.rhs, definition.tpt.tpe)
                definitions(definition.symbol) = definition
                sourceDefinitions(definition.symbol) = ((definition, ctx))
              case definition: ValDef =>
                structural.admitResult(definition.rhs, definition.tpt.tpe)
                values(definition.symbol) = definition
              case typed: Typed => structural.admitResult(typed.expr, typed.tpt.tpe)
              case definition: TypeDef => definition.rhs match
                  case template: Template =>
                    templates(definition.symbol) = template
                    sourceDefinitions(definition.symbol) = ((definition, ctx))
                  case _ => ()
              case reference: RefTree
                  if structuralMethods(reference.symbol) || structuralBranches(reference.symbol) ||
                    structuralFactories(reference.symbol) =>
                report.error(
                  "NODAL-STRUCTURAL-055-FORGED: source must not call compiler-owned structural capture or attestation methods",
                  reference.srcPos
                )
              case reference: RefTree if iterationFactories(reference.symbol) =>
                report.error(
                  "NODAL-ITERATION-CALLBACK-FORGED: source must not call compiler-owned callback attestation factories",
                  reference.srcPos
                )
              case reference: RefTree if factories(reference.symbol) =>
                fail(
                  "FORGED",
                  "source must not call compiler-owned callback attestation factories",
                  reference
                )
              case _ => ()
            traverseChildren(current)
        collect.traverse(unit.tpdTree)
      publishEffectSummaries()
    ctx

  private def effectMarker(using Context): ClassSymbol =
    requiredClass("nodal.ConstructorEffectSummary")

  private def closedDispatch(symbol: Symbol)(using Context): Boolean =
    symbol.is(Private) || symbol.is(Final) || symbol.owner.isTerm ||
      symbol.owner.is(Final) || symbol.owner.is(ModuleClass)

  // This ABI encodes actual symbol/type identity, not display names or erased overloads.
  // Higher-order, dependent and polymorphic factory signatures are outside the bounded profile.
  private def effectType(tpe: Type, depth: Int = 0)(using Context): Option[String] =
    if depth > 32 then None
    else
      tpe.dealias match
        case AppliedType(base, arguments) =>
          val parts = effectType(base, depth + 1).toList ++
            arguments.flatMap(effectType(_, depth + 1))
          if parts.size == arguments.size + 1 then
            Some(parts.head + parts.tail.mkString("[", ",", "]"))
          else None
        case named: NamedType if named.typeSymbol.isClass =>
          Some(named.typeSymbol.fullName.toString)
        case _ => None

  private def defaultConstructor(symbol: Symbol)(using Context): Option[Symbol] =
    if !symbol.is(Method) || !symbol.owner.is(ModuleClass) then None
    else
      val cls = symbol.owner.companionClass
      if !cls.exists || !cls.derivesFrom(requiredClass("nodal.Module")) then None
      else
        cls.primaryConstructor.paramSymss.flatten.zipWithIndex.collectFirst:
          case (parameter, index)
              if parameter.is(HasDefault) &&
                symbol.name == DefaultGetterName(nme.CONSTRUCTOR, index) => cls

  private def effectKind(symbol: Symbol)(using Context): Option[String] =
    if symbol.isClass then
      if symbol.derivesFrom(requiredClass("nodal.Module")) &&
        symbol.hasAnnotation(requiredClass("nodal.ConstructorSchema"))
      then Some("constructor")
      else if symbol.is(ModuleClass) &&
        symbol.info.parents.forall(_.typeSymbol == requiredClass("java.lang.Object"))
      then Some("singleton")
      else None
    else if defaultConstructor(symbol).nonEmpty then Some("default")
    else if symbol.is(Method) && !symbol.isConstructor && !symbol.is(Accessor) &&
      closedDispatch(symbol) &&
      symbol.info.finalResultType.derivesFrom(requiredClass("nodal.Module"))
    then Some("factory")
    else if symbol.is(Method) && !symbol.isConstructor && !symbol.is(Accessor) &&
      closedDispatch(symbol) && (
        symbol.info.finalResultType.derivesFrom(requiredClass("nodal.Expr")) ||
          symbol.info.finalResultType =:= defn.UnitType
      )
    then Some("helper")
    else None

  private def effectSignature(symbol: Symbol, kind: String)(using Context): Option[String] =
    if kind == "singleton" then Some(symbol.fullName.toString + ":java.lang.Object")
    else
      val method = if kind == "constructor" then symbol.primaryConstructor else symbol
      val parameters = method.paramSymss
      val terms = parameters.flatten
      val types = terms.flatMap(parameter => effectType(parameter.info))
      val firstOrder = !method.info.isInstanceOf[PolyType] && terms.forall(parameter =>
        !parameter.isOneOf(Given | Implicit | Erased) &&
          !parameter.info.derivesFrom(requiredClass("scala.Function0")) &&
          !parameter.info.derivesFrom(requiredClass("scala.Function1")) &&
          !parameter.info.derivesFrom(requiredClass("scala.Function2"))
      )
      val result = if kind == "constructor" then Some(symbol.fullName.toString)
      else effectType(method.info.finalResultType)
      if !firstOrder || types.size != terms.size || result.isEmpty then None
      else
        val lists = parameters.map(_.map(parameter =>
          parameter.name.toString + ":" + effectType(parameter.info).get
        ).mkString("(", ",", ")")).mkString
        val base = symbol.fullName.toString + lists + ":" + result.get
        if kind == "default" then
          defaultConstructor(symbol).flatMap(effectSignature(_, "constructor")).map(base + "@" + _)
        else Some(base)

  private def dependencyReference(symbol: Symbol, kind: String, signature: String, profile: Int)(
      using Context
  ): Option[String] =
    val owner = if symbol.isClass then symbol else symbol.owner
    val locator = if owner.is(ModuleClass) then "module:" + owner.sourceModule.fullName.toString
    else "class:" + owner.fullName.toString
    val member = if symbol.isClass then "" else symbol.name.toString
    val fields = List(kind, locator, member, signature, profile.toString)
    if fields.exists(field => field.contains('\t') || field.contains('\n')) then None
    else Some(fields.mkString("\t"))

  private def dependencySymbol(encoded: String)(using Context): Option[(Symbol, String, Int)] =
    val fields = encoded.split("\t", -1).toList
    fields match
      case kind :: locator :: member :: signature :: profile :: Nil
          if Set("1", "3", "4", "8")(profile) =>
        val owner = if locator.startsWith("module:") then
          val name = locator.stripPrefix("module:")
          val direct = getModuleIfDefined(name)
          // Symbol.fullName omits the empty-package owner; staticRef starts at the root
          // package. Resolve that exact owner explicitly for separately compiled local examples.
          val module = if direct.exists || name.contains('.') then direct
          else defn.EmptyPackageClass.info.member(termName(name)).symbol
          if module.exists && module.is(Module) then module.moduleClass else NoSymbol
        else if locator.startsWith("class:") then
          val name = locator.stripPrefix("class:")
          val direct = getClassIfDefined(name)
          if direct.exists || name.contains('.') then direct
          else defn.EmptyPackageClass.info.member(typeName(name)).symbol
        else NoSymbol
        if !owner.exists then None
        else
          val candidates = if member.isEmpty then List(owner)
          else owner.info.decls.toList.filter(_.name.toString == member)
          val matching = candidates.filter(symbol =>
            effectKind(symbol).contains(kind) && effectSignature(symbol, kind).contains(signature)
          )
          matching match
            case symbol :: Nil => Some((symbol, kind, profile.toInt))
            case _ => None
      case _ => None

  private def publishEffectSummaries(): Unit =
    sourceDefinitions.foreach: (symbol, entry) =>
      val (tree, context) = entry
      given Context = context
      effectKind(symbol).foreach: kind =>
        effectSignature(symbol, kind).foreach: signature =>
          val profiles = if kind == "helper" then List(Profile.Pure, Profile.StructuralIf)
          else if kind == "singleton" then
            List(Profile.Pure, Profile.StructuralIf, Profile.StructuralRange, Profile.Range)
          else List(Profile.StructuralRange, Profile.Range)
          val checked = profiles.iterator.flatMap: profile =>
            val validation = new Validation(profile, new Budget, reportErrors = false)
            if validation.summary(symbol, kind, tree) && validation.dependenciesWithinBudget then
              Some((profile, validation.dependencies))
            else None
          .take(1).toList
          checked.foreach: (profile, dependencies) =>
            symbol.addAnnotation(Annotation(
              effectMarker,
              List(
                Literal(Constant(1)),
                Literal(Constant(kind)),
                Literal(Constant(signature)),
                Literal(Constant(profile match
                  case Profile.Pure => 4
                  case Profile.StructuralIf => 8
                  case Profile.StructuralRange => 1
                  case _ => 3)),
                Literal(Constant(dependencies))
              ),
              tree.span
            ))

  override def prepareForValDef(tree: ValDef)(using Context): Context =
    structural.admitResult(tree.rhs, tree.tpt.tpe)
    ctx

  override def prepareForDefDef(tree: DefDef)(using Context): Context =
    structural.admitResult(tree.rhs, tree.tpt.tpe)
    ctx

  override def prepareForTyped(tree: Typed)(using Context): Context =
    structural.admitResult(tree.expr, tree.tpt.tpe)
    ctx

  override def transformIf(tree: If)(using Context): Tree =
    if !structural.moduleContext || !structural.symbolic(tree.cond) then tree
    else
      val validation = new Validation(Profile.StructuralIf, new Budget)
      if validation.structuralIf(tree, 0) then
        structural.lower(tree, structural.predicate(tree.cond).get, structural.resultType(tree).get)
      else tree

  private def callbackArity(tree: Tree)(using Context): Int =
    if tree.tpe.widen.dealias.derivesFrom(requiredClass("scala.Function1")) then 1
    else if tree.tpe.widen.dealias.derivesFrom(requiredClass("scala.Function2")) then 2
    else 0

  override def transformApply(tree: Apply)(using Context): Tree =
    val symbol = tree.fun.symbol
    val profile = if collections(symbol) then Some(Profile.Pure)
    else if loops(symbol) then Some(Profile.Loop)
    else if ranges(symbol) then Some(Profile.Range)
    else None
    profile.fold[Tree](tree): profile =>
      cpy.Apply(tree)(tree.fun, tree.args.map(checkArgument(_, profile)))

  private def checkArgument(argument: Tree, profile: Profile)(using Context): Tree = argument match
    case named: NamedArg => cpy.NamedArg(named)(named.name, checkArgument(named.arg, profile))
    case _ =>
      val arity = callbackArity(argument)
      if arity == 0 then argument
      else if new Validation(profile, new Budget).callback(argument, 0) then
        val attested = if profile == Profile.Pure then
          val base = argument.tpe.widen.dealias.baseType(requiredClass(s"scala.Function$arity"))
          val arguments = base match
            case AppliedType(_, arguments) => arguments
            case _ => Nil
          ref(requiredMethod(s"nodal.DigitalCollectionCallback.checked$arity"))
            .appliedToTypes(arguments)
        else
          val factory = if profile == Profile.Loop then "checkedLoop" else "checkedRange"
          ref(requiredMethod(s"nodal.DigitalIterationCallback.$factory"))
        attested.appliedTo(argument).withSpan(argument.span)
      else argument

  private final class Validation(
      profile: Profile,
      sharedBudget: Budget,
      reportErrors: Boolean = true,
      structuralDiagnostic: Boolean = false
  )(using Context):
    private val permitsStructure = profile == Profile.Range || profile == Profile.StructuralRange
    private val permitsWrites = profile == Profile.Loop || profile == Profile.Range ||
      profile == Profile.StructuralIf
    private def sanctioned(symbol: Symbol): Boolean = pureMethods(symbol) ||
      structuralMethods(symbol) || structuralBranches(symbol) || structuralFactories(symbol) ||
      (permitsWrites && loopMethods(symbol)) || (permitsStructure && rangeMethods(symbol)) ||
      (profile == Profile.Selection && selectionMethods(symbol))
    private def trustedModule(symbol: Symbol): Boolean = pureModules(symbol) ||
      structuralModules(symbol) ||
      (permitsWrites && loopModules(symbol)) || (permitsStructure && rangeModules(symbol))
    private def fail(code: String, message: String, tree: Tree): Boolean =
      if !reportErrors then false
      else if profile == Profile.Pure && !structuralDiagnostic then
        DigitalCollectionCallbackPhase.this.fail(code, message, tree)
      else if profile == Profile.StructuralIf || structuralDiagnostic then
        report.error(s"NODAL-STRUCTURAL-055-$code: $message", tree.srcPos)
        false
      else
        report.error(s"NODAL-ITERATION-CALLBACK-$code: $message", tree.srcPos)
        false
    private val active = mutable.HashSet.empty[Symbol]
    private val completed = mutable.HashSet.empty[Symbol]
    private val checkedModules = mutable.HashSet.empty[Symbol]
    private val activeModules = mutable.HashSet.empty[Symbol]
    private val callbackAliases = mutable.HashSet.empty[Symbol]
    private val activeSummaries = mutable.HashSet.empty[Symbol]
    private val checkedSummaries = mutable.HashMap.empty[Symbol, Int]
    private val importedDependencies = mutable.HashSet.empty[String]
    def dependencies: String = importedDependencies.toList.sorted.mkString("\n")
    def dependenciesWithinBudget: Boolean = importedDependencies.size <= 256 &&
      dependencies.length <= 65536

    private def budget(tree: Tree, depth: Int): Boolean =
      sharedBudget.visitedNodes += 1
      if sharedBudget.visitedNodes > 8192 || depth > 128 || active.size > 128 then
        fail("LIMIT", "callback validation exceeds the bounded typed-body/call-graph profile", tree)
      else true

    private def immutable(symbol: Symbol, tree: Tree): Boolean =
      if symbol.is(Mutable) || (symbol.is(Lazy) && !symbol.is(Module)) || symbol.isSetter then
        fail("MUTABLE", "callback cannot read or write mutable or lazy Scala state", tree)
      else if symbol.is(Accessor) then
        val field = symbol.accessedFieldOrGetter
        if field.exists && field != symbol && (field.is(Mutable) || field.is(Lazy)) then
          fail("MUTABLE", "callback cannot read mutable or lazy Scala fields", tree)
        else true
      else true

    private def moduleReference(symbol: Symbol, tree: Tree, depth: Int): Boolean =
      if trustedModule(symbol) || checkedModules(symbol) then true
      else if activeModules(symbol) then
        fail("CYCLE", "callback reaches cyclic singleton initialization", tree)
      else
        templates.get(symbol.moduleClass) match
          case Some(_)
              if symbol.moduleClass.info.parents.exists(
                _.typeSymbol != requiredClass("java.lang.Object")
              ) =>
            fail(
              "EFFECT",
              "callback helper singleton inheritance may run unvalidated initialization",
              tree
            )
          case Some(template) =>
            activeModules += symbol
            val valid = template.body.forall:
              case _: DefDef | _: TypeDef | _: Import => true
              // The exact selected alias RHS is already checked by callback; inspecting its
              // qualifier must still check every other singleton initializer.
              case definition: ValDef if callbackAliases(definition.symbol) => true
              case other => check(other, depth + 1)
            activeModules -= symbol
            if valid then checkedModules += symbol
            valid
          case None => importedSummary(symbol.moduleClass, "singleton", tree)

    private def reference(tree: RefTree, depth: Int): Boolean =
      val symbol = tree.symbol
      if !symbol.exists then fail("UNKNOWN", "callback contains an unresolved reference", tree)
      else if !immutable(symbol, tree) then false
      else if symbol.is(Module) then moduleReference(symbol, tree, depth)
      else if symbol.is(Method) then
        if sanctioned(symbol) then true
        else if symbol.is(Accessor) && !symbol.is(Deferred) then
          val field = symbol.accessedFieldOrGetter
          if field.exists && field != symbol then immutable(field, tree)
          else helper(symbol, tree, depth)
        else helper(symbol, tree, depth)
      else true

    private def helper(symbol: Symbol, tree: Tree, depth: Int): Boolean =
      if active(symbol) then fail("CYCLE", "recursive callback helpers are unsupported", tree)
      else if completed(symbol) then true
      else if !closedDispatch(symbol) then
        fail(
          "DISPATCH",
          "callback helper requires statically closed dispatch (final/private/local)",
          tree
        )
      else
        definitions.get(symbol) match
          case Some(definition) if !definition.rhs.isEmpty =>
            active += symbol
            val valid = check(definition.rhs, depth + 1)
            active -= symbol
            if valid then completed += symbol
            valid
          case _ =>
            effectKind(symbol) match
              case Some(kind) if kind == "factory" || kind == "default" || kind == "helper" =>
                importedSummary(symbol, kind, tree)
              case _ => fail(
                  "UNAVAILABLE",
                  "callback helper body is unavailable in this compilation: " +
                    symbol.fullName.toString,
                  tree
                )

    def callback(tree: Tree, depth: Int): Boolean =
      if !budget(tree, depth) then false
      else
        tree match
          case Typed(expression, _) => callback(expression, depth + 1)
          case NamedArg(_, argument) => callback(argument, depth + 1)
          case Block(statements, expression) =>
            statements.forall:
              case definition: DefDef =>
                definitions.getOrElseUpdate(definition.symbol, definition)
                true
              case other => check(other, depth + 1)
            && callback(expression, depth + 1)
          case closure: Closure =>
            closure.env.forall(check(_, depth + 1)) &&
            helper(closure.meth.symbol, closure, depth + 1)
          case reference: RefTree
              if !reference.symbol.is(Method) || reference.symbol.is(Accessor) =>
            val symbol = if reference.symbol.is(Accessor) then
              reference.symbol.accessedFieldOrGetter
            else reference.symbol
            if !immutable(reference.symbol, tree) || !immutable(symbol, tree) then false
            else if reference.symbol.is(Accessor) && !closedDispatch(reference.symbol) then
              fail(
                "DISPATCH",
                "callback value requires a statically closed immutable accessor",
                tree
              )
            else if callbackAliases(symbol) then
              fail("CYCLE", "cyclic callback aliases are unsupported", tree)
            else
              values.get(symbol) match
                case Some(value) if !value.rhs.isEmpty =>
                  callbackAliases += symbol
                  val valid = callback(value.rhs, depth + 1) &&
                    (reference match
                      case selection: Select => check(selection.qualifier, depth + 1)
                      case _ => true)
                  callbackAliases -= symbol
                  valid
                case _ => fail(
                    "UNAVAILABLE",
                    "callback value requires a same-compilation immutable closure definition",
                    tree
                  )
          case _ => fail(
              "UNKNOWN",
              "callback must resolve to a typed closure or a same-compilation immutable closure value",
              tree
            )

    private def nestedCallback(tree: Tree, nested: Profile, depth: Int): Boolean =
      if nested == profile then callback(tree, depth)
      else
        val validation = new Validation(nested, sharedBudget, reportErrors, structuralDiagnostic)
        val valid = validation.callback(tree, depth)
        importedDependencies ++= validation.importedDependencies
        valid &&
        (dependenciesWithinBudget ||
          fail("LIMIT", "callback effect dependencies exceed the bounded summary profile", tree))

    def summary(symbol: Symbol, kind: String, tree: Tree): Boolean = kind match
      case "constructor" => constructor(symbol.primaryConstructor, tree, 0)
      case "singleton" => moduleReference(symbol.sourceModule, tree, 0)
      case "factory" | "default" | "helper" => helper(symbol, tree, 0)
      case _ => false

    private def importedSummary(
        symbol: Symbol,
        kind: String,
        tree: Tree,
        expectedProfile: Option[Int] = None
    ): Boolean =
      if !budget(tree, activeSummaries.size + 1) then false
      else if activeSummaries(symbol) then
        fail("SUMMARY", "cyclic constructor effect-summary dependency", tree)
      else if checkedSummaries.contains(symbol) then
        expectedProfile.forall(_ == checkedSummaries(symbol)) ||
        fail("SUMMARY", "constructor effect dependency profile changed; rebuild its producer", tree)
      else
        val annotations = symbol.annotations.filter(_.symbol == effectMarker)
        annotations match
          case Nil => fail(
              "UNAVAILABLE",
              "compiled constructor path lacks a checked effect summary; rebuild the producer with the current plugin: " +
                symbol.fullName.toString,
              tree
            )
          case annotation :: Nil =>
            val version = annotation.argumentConstant(0).map(_.value)
            val declaredKind = annotation.argumentConstantString(1)
            val signature = annotation.argumentConstantString(2)
            val effects = annotation.argumentConstant(3).map(_.value)
            val dependencyText = annotation.argumentConstantString(4)
            // The original allocation profiles (1, 3) never attest a structural branch.
            // Profile 4 is read-only; 8 permits the bounded digital branch/write effects.
            val helperKind = kind == "helper" || kind == "singleton"
            val compatible =
              (helperKind &&
                (effects.contains(4) ||
                  (effects.contains(8) && permitsWrites))) ||
                (kind != "helper" && permitsStructure &&
                  (effects.contains(1) || (effects.contains(3) && permitsWrites)))
            val expected = expectedProfile.forall(profile => effects.contains(profile))
            if annotation.arguments.size != 5 || !version.contains(1) ||
              !effectKind(symbol).contains(kind) || !declaredKind.contains(kind) ||
              !effectSignature(symbol, kind).exists(signature.contains) || !compatible ||
              !expected ||
              dependencyText.isEmpty || dependencyText.exists(_.length > 65536)
            then
              fail("SUMMARY", "stale, malformed or incompatible constructor effect summary", tree)
            else
              val profile = List(1, 3, 4, 8).find(effects.contains).get
              val records = if dependencyText.get.isEmpty then Nil
              else dependencyText.get.split("\n", -1).toList
              activeSummaries += symbol
              val valid = if records.size > 256 || records.distinct.size != records.size then
                fail(
                  "SUMMARY",
                  "constructor effect dependency list exceeds its bound or contains duplicates",
                  tree
                )
              else
                records.forall: record =>
                  dependencySymbol(record) match
                    case Some((dependency, dependencyKind, dependencyProfile)) =>
                      importedSummary(dependency, dependencyKind, tree, Some(dependencyProfile))
                    case None => fail(
                        "SUMMARY",
                        "constructor effect dependency no longer matches its symbol signature: " +
                          record,
                        tree
                      )
              activeSummaries -= symbol
              val reference = dependencyReference(symbol, kind, signature.get, profile)
              if valid && reference.nonEmpty then
                checkedSummaries(symbol) = profile
                importedDependencies += reference.get
                true
              else if !valid then false
              else fail("SUMMARY", "constructor effect dependency identity is unsupported", tree)
          case _ => fail("SUMMARY", "constructor effect summary must be unique", tree)

    private def constructor(symbol: Symbol, tree: Tree, depth: Int): Boolean =
      val cls = symbol.owner
      if !permitsStructure || !cls.derivesFrom(requiredClass("nodal.Module")) ||
        symbol != cls.primaryConstructor ||
        !cls.info.parents.forall(_.typeSymbol == requiredClass("nodal.Module"))
      then
        fail(
          "EFFECT",
          "callback allocation is outside the supported Module constructor profile",
          tree
        )
      else if active(cls) then fail("CYCLE", "recursive Module construction is unsupported", tree)
      else if completed(cls) then true
      else
        templates.get(cls) match
          case Some(template) =>
            active += cls
            val valid = template.body.forall:
              case _: DefDef | _: TypeDef | _: Import => true
              case definition: ValDef if definition.rhs.isEmpty =>
                immutable(definition.symbol, definition)
              case other => check(other, depth + 1)
            active -= cls
            if valid then completed += cls
            valid
          case None => importedSummary(cls, "constructor", tree)

    private def applied(tree: Tree, depth: Int): Boolean =
      if !budget(tree, depth) then false
      else
        tree match
          case application: Apply =>
            val symbol = application.fun.symbol
            if loops(symbol) || (ranges(symbol) && !permitsStructure) then
              fail("EFFECT", "nested effectful digital iteration is unsupported", application)
            else
              applied(application.fun, depth + 1) && application.args.forall: argument =>
                if (structuralBranches(symbol) || structuralFactories(symbol)) &&
                  argument.tpe.widen.dealias.derivesFrom(requiredClass("scala.Function0"))
                then
                  argument match
                    case Apply(function, List(closure)) if structuralFactories(function.symbol) =>
                      callback(closure, depth + 1)
                    case _ => callback(argument, depth + 1)
                else if collections(symbol) && callbackArity(argument) > 0 then
                  nestedCallback(argument, Profile.Pure, depth + 1)
                else if ranges(symbol) && callbackArity(argument) > 0 then
                  nestedCallback(argument, Profile.StructuralRange, depth + 1)
                else if permitsStructure && rangeClosures(symbol) && callbackArity(argument) > 0
                then
                  nestedCallback(argument, Profile.Selection, depth + 1)
                else if permitsStructure && constructorClosures(symbol) &&
                  argument.tpe.widen.dealias.derivesFrom(requiredClass("scala.Function0"))
                then
                  callback(argument, depth + 1)
                else check(argument, depth + 1)
          case TypeApply(function, _) => applied(function, depth + 1)
          case selection: Select
              if selection.qualifier.isInstanceOf[New] && selection.symbol.isConstructor =>
            constructor(selection.symbol, selection, depth + 1)
          case selection: Select =>
            check(selection.qualifier, depth + 1) &&
            (collections(selection.symbol) || (permitsStructure && ranges(selection.symbol)) ||
              reference(selection, depth + 1))
          case identifier: Ident => collections(identifier.symbol) ||
            (permitsStructure && ranges(identifier.symbol)) || reference(identifier, depth + 1)
          case _ => fail("UNKNOWN", "unsupported callback call target", tree)

    def structuralIf(tree: If, depth: Int): Boolean =
      if !budget(tree, depth) then false
      else
        val resultValid = structural.resultType(tree).nonEmpty
        if !resultValid && reportErrors then
          report.error(
            "NODAL-STRUCTURAL-055-001: structural if result must be a read-only Expr; add an explicit Expr[A] ascription",
            tree.srcPos
          )
        val predicateValid = structural.predicate(tree.cond) match
          case Some(predicate) =>
            // Operands and erased equality evidence must be read-only, even when the arms
            // permit continuous writes. Share the same work budget and imported proof graph.
            val validation = new Validation(
              Profile.Pure,
              sharedBudget,
              reportErrors,
              structuralDiagnostic = true
            )
            val valid = predicate.operands.map(validation.check(_, depth + 1)).forall(identity)
            importedDependencies ++= validation.importedDependencies
            valid &&
            (dependenciesWithinBudget || fail(
              "LIMIT",
              "predicate effect dependencies exceed the bounded summary profile",
              tree.cond
            ))
          case None =>
            if reportErrors then
              report.error(
                "NODAL-STRUCTURAL-055-CONDITION: structural if requires a closed direct Integer predicate; mixed host conditions and predicate aliases are unsupported",
                tree.cond.srcPos
              )
            false
        // Both authored arms are checked even when an earlier check fails. No runtime callback
        // exists until this shared validation has succeeded for the whole conditional.
        val trueValid = check(tree.thenp, depth + 1)
        val falseValid = check(tree.elsep, depth + 1)
        resultValid && predicateValid && trueValid && falseValid

    private def check(tree: Tree, depth: Int): Boolean =
      if !budget(tree, depth) then false
      else
        tree match
          case _: Literal | _: This | _: TypeTree => true
          case identifier: Ident => reference(identifier, depth + 1)
          case selection: Select =>
            check(selection.qualifier, depth + 1) && reference(selection, depth + 1)
          case application: Apply => applied(application, depth + 1)
          case application: TypeApply => applied(application, depth + 1)
          case Typed(expression, _) => check(expression, depth + 1)
          case NamedArg(_, argument) => check(argument, depth + 1)
          case SeqLiteral(elements, _) => elements.forall(check(_, depth + 1))
          case Block(statements, expression) =>
            statements.forall:
              case _: DefDef => true
              case other => check(other, depth + 1)
            && check(expression, depth + 1)
          case definition: ValDef =>
            immutable(definition.symbol, definition) && check(definition.rhs, depth + 1)
          case _: DefDef => true
          case _: Assign => fail("MUTABLE", "callback assignments are unsupported", tree)
          // Scala lowers `val _ = expression` to a single irrefutable Unit match. It evaluates
          // no host predicate and cannot choose hardware topology; validate its evaluated value.
          case Match(selector, CaseDef(Ident(name), guard, Literal(constant)) :: Nil)
              if profile != Profile.Pure && name == nme.WILDCARD && guard.isEmpty &&
                constant.value == () => check(selector, depth + 1)
          case conditional: If if structural.symbolic(conditional.cond) =>
            structuralIf(conditional, depth + 1)
          case _: If | _: Match | _: WhileDo =>
            fail(
              "TOPOLOGY",
              "host control flow cannot determine a captured hardware body; use typed hardware selection",
              tree
            )
          case _: Try | _: Return =>
            fail(
              "EFFECT",
              "callback exception handling and nonlocal control effects are unsupported",
              tree
            )
          case _: New =>
            fail("EFFECT", "callback allocation or structural declaration is unsupported", tree)
          case _: Closure =>
            fail("UNKNOWN", "nested functions require a supported collection operation", tree)
          case _ => fail(
              "UNKNOWN",
              "unsupported typed callback expression: " + tree.getClass.getSimpleName,
              tree
            )
