package nodal.internal.frontend.compiler

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.DefaultGetterName
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report

import scala.collection.mutable

/** Closed, typed combinational profile for callbacks captured once as hardware region templates.
  * Method symbols and actual same-compilation bodies are authoritative, never source spellings.
  * Unsupported host staging fails closed; this is deliberately not a general Scala effect system.
  */
private[compiler] final class DigitalCollectionCallbackPhase extends PluginPhase:
  val phaseName = "nodalDigitalCollectionCallbacks"
  override val runsAfter = Set("nodalConstructorCapture")
  override val runsBefore = Set("pickler")

  private val definitions = mutable.HashMap.empty[Symbol, DefDef]
  private val values = mutable.HashMap.empty[Symbol, ValDef]
  private val templates = mutable.HashMap.empty[Symbol, Template]
  private var scanned = false
  private var collections = Set.empty[Symbol]
  private var pureMethods = Set.empty[Symbol]
  private var pureModules = Set.empty[Symbol]
  private var factories = Set.empty[Symbol]

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
    collections = methods(owner, "map", "zipWith", "fold", "scan", "reduce")
    pureModules += owner
    factories = methods(requiredModule("nodal.DigitalCollectionCallback"), "checked1", "checked2")
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
        moduleMethods("nodal.DigitalEnumApi$package", "asBits", "isValid", "===")
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
    pureMethods ++= pureMethods.flatMap: method =>
      method.paramSymss.flatten.filter(_.isTerm).zipWithIndex.flatMap: (parameter, index) =>
        if parameter.is(HasDefault) then
          method.owner.info.member(DefaultGetterName(method.name.asTermName, index))
            .alternatives.map(_.symbol)
        else Nil

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
              case definition: DefDef => definitions(definition.symbol) = definition
              case definition: ValDef => values(definition.symbol) = definition
              case definition: TypeDef => definition.rhs match
                  case template: Template => templates(definition.symbol) = template
                  case _ => ()
              case reference: RefTree if factories(reference.symbol) =>
                fail(
                  "FORGED",
                  "source must not call compiler-owned callback attestation factories",
                  reference
                )
              case _ => ()
            traverseChildren(current)
        collect.traverse(unit.tpdTree)
    ctx

  private def callbackArity(tree: Tree)(using Context): Int =
    if tree.tpe.widen.dealias.derivesFrom(requiredClass("scala.Function1")) then 1
    else if tree.tpe.widen.dealias.derivesFrom(requiredClass("scala.Function2")) then 2
    else 0

  override def transformApply(tree: Apply)(using Context): Tree =
    if !collections(tree.fun.symbol) then tree
    else
      cpy.Apply(tree)(tree.fun, tree.args.map(checkArgument))

  private def checkArgument(argument: Tree)(using Context): Tree = argument match
    case named: NamedArg => cpy.NamedArg(named)(named.name, checkArgument(named.arg))
    case _ =>
      val arity = callbackArity(argument)
      if arity == 0 then argument
      else if new Validation().callback(argument, 0) then
        val base = argument.tpe.widen.dealias.baseType(requiredClass(s"scala.Function$arity"))
        val arguments = base match
          case AppliedType(_, arguments) => arguments
          case _ => Nil
        ref(requiredMethod(s"nodal.DigitalCollectionCallback.checked$arity"))
          .appliedToTypes(arguments)
          .appliedTo(argument)
          .withSpan(argument.span)
      else argument

  private final class Validation(using Context):
    private var visitedNodes = 0
    private val active = mutable.HashSet.empty[Symbol]
    private val completed = mutable.HashSet.empty[Symbol]
    private val checkedModules = mutable.HashSet.empty[Symbol]
    private val activeModules = mutable.HashSet.empty[Symbol]
    private val callbackAliases = mutable.HashSet.empty[Symbol]

    private def budget(tree: Tree, depth: Int): Boolean =
      visitedNodes += 1
      if visitedNodes > 8192 || depth > 128 || active.size > 128 then
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

    private def closedDispatch(symbol: Symbol): Boolean =
      symbol.is(Private) || symbol.is(Final) || symbol.owner.isTerm ||
        symbol.owner.is(Final) || symbol.owner.is(ModuleClass)

    private def moduleReference(symbol: Symbol, tree: Tree, depth: Int): Boolean =
      if pureModules(symbol) || checkedModules(symbol) then true
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
              case other => check(other, depth + 1)
            activeModules -= symbol
            if valid then checkedModules += symbol
            valid
          case None =>
            fail(
              "UNAVAILABLE",
              "callback singleton initialization is unavailable for typed validation",
              tree
            )

    private def reference(tree: RefTree, depth: Int): Boolean =
      val symbol = tree.symbol
      if !symbol.exists then fail("UNKNOWN", "callback contains an unresolved reference", tree)
      else if !immutable(symbol, tree) then false
      else if symbol.is(Module) then moduleReference(symbol, tree, depth)
      else if symbol.is(Method) then
        if pureMethods(symbol) then true
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
              case _: DefDef => true
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
                  val valid = callback(value.rhs, depth + 1)
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

    private def applied(tree: Tree, depth: Int): Boolean =
      if !budget(tree, depth) then false
      else
        tree match
          case application: Apply =>
            val callbackCall = collections(application.fun.symbol)
            applied(application.fun, depth + 1) && application.args.forall: argument =>
              if callbackCall && callbackArity(argument) > 0 then callback(argument, depth + 1)
              else check(argument, depth + 1)
          case TypeApply(function, _) => applied(function, depth + 1)
          case selection: Select =>
            check(selection.qualifier, depth + 1) &&
            (collections(selection.symbol) || reference(selection, depth + 1))
          case identifier: Ident => collections(identifier.symbol) ||
            reference(identifier, depth + 1)
          case _ => fail("UNKNOWN", "unsupported callback call target", tree)

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
          case _: If | _: Match | _: WhileDo =>
            fail(
              "TOPOLOGY",
              "host control flow cannot determine a captured collection body; use typed hardware selection",
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
