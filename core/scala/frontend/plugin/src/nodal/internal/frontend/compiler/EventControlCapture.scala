package nodal.internal.frontend.compiler

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.util.Property

import scala.collection.mutable

/** Original authored control inventory for the quarantined event profile. This deliberately is not
  * a host purity analysis: ordinary assignments and scalar bookkeeping are allowed. Digital
  * construction, captures and writes still have their independent runtime/native owners. The
  * immutable verdict is retained before structural/behavioral transformations erase source control.
  */
private[compiler] final class EventControlCapture:
  private final case class Call(event: Tree, body: Tree, allowAnalog: Boolean)
  private val verdict = new Property.StickyKey[String]
  private var entries = Set.empty[Symbol]
  private var analogEntries = Set.empty[Symbol]
  private var leaves = Set.empty[Symbol]
  private var controls = Set.empty[Symbol]
  private var collections = Set.empty[Symbol]
  private var modules = Set.empty[Symbol]
  private var owned = Set.empty[Symbol]

  private def methods(owner: Symbol, names: String*)(using Context): Set[Symbol] =
    names.flatMap(name => owner.info.member(termName(name)).alternatives.map(_.symbol)).toSet

  def initialize(
      leafMethods: Set[Symbol],
      controlMethods: Set[Symbol],
      collectionMethods: Set[Symbol],
      trustedModules: Set[Symbol]
  )(using Context): Unit =
    analogEntries = methods(requiredModule("nodal.CandidateApi$package"), "on")
    entries = analogEntries ++ methods(requiredModule("nodal.lowlevel"), "process")
    leaves = leafMethods
    controls = controlMethods ++ entries
    collections = collectionMethods
    modules = trustedModules
    owned = methods(requiredModule("nodal.DigitalEventCallback"), "checked") ++
      methods(requiredModule("nodal.CandidateRuntime"), "checkedEventBlock")

  def compilerOwned(symbol: Symbol): Boolean = owned(symbol)

  private def call(tree: Tree)(using Context): Option[Call] = tree match
    case Apply(Apply(function, List(event)), List(body)) if entries(function.symbol) =>
      Some(Call(event, body, analogEntries(function.symbol)))
    case _ => None

  /** Run only after the complete same-compilation definition inventory exists, before transformIf.
    * Do not report here: Event's runtime identity, not a guessed source type, selects analog versus
    * digital. A rejected certificate is harmless on the unchanged actual analog on(event) path.
    */
  def collect(
      tree: Tree,
      definitions: collection.Map[Symbol, DefDef],
      values: collection.Map[Symbol, ValDef],
      templates: collection.Map[Symbol, Template],
      pureCallback: Tree => Boolean,
      pureSingleton: (Symbol, Tree) => Boolean,
      enumEvidence: Symbol => Boolean
  )(using Context): Unit =
    val scan = new TreeTraverser:
      override def traverse(current: Tree)(using Context): Unit =
        call(current).foreach: event =>
          val inventory =
            new Inventory(definitions, values, templates, pureCallback, pureSingleton, enumEvidence)
          inventory.inspect(event.body)
          current.putAttachment(verdict, inventory.rejection)
        traverseChildren(current)
    scan.traverse(tree)

  def lower(tree: Apply)(using Context): Option[Tree] = call(tree).map: event =>
    val rejection = tree.getAttachment(verdict).getOrElse(
      "original authored event body control inventory is missing; rebuild with the current plugin"
    )
    val closure = Lambda(MethodType(Nil)(_ => Nil, _ => defn.UnitType), _ => event.body)
    val checked = ref(requiredMethod("nodal.DigitalEventCallback.checked"))
      .appliedToArgs(List(closure, Literal(Constant(rejection))))
    ref(requiredMethod("nodal.CandidateRuntime.checkedEventBlock"))
      .appliedToArgs(List(event.event, checked, Literal(Constant(event.allowAnalog))))
      .withSpan(tree.span)

  private final class Inventory(
      definitions: collection.Map[Symbol, DefDef],
      values: collection.Map[Symbol, ValDef],
      templates: collection.Map[Symbol, Template],
      pureCallback: Tree => Boolean,
      pureSingleton: (Symbol, Tree) => Boolean,
      enumEvidence: Symbol => Boolean
  )(using Context):
    private var nodes = 0
    private var failure = ""
    private val active = mutable.HashSet.empty[Symbol]
    private val activeAliases = mutable.HashSet.empty[Symbol]
    private val activeModules = mutable.HashSet.empty[Symbol]
    private val inspectedModules = mutable.HashSet.empty[Symbol]
    private type Arguments = Map[Symbol, Tree]

    def rejection: String = failure
    def inspect(tree: Tree): Unit = check(tree, 0, Map.empty)

    private def reject(message: String): Unit =
      if failure.isEmpty then failure = message

    private def budget(depth: Int): Boolean =
      nodes += 1
      if nodes > 8192 || depth > 128 || active.size > 128 then
        reject("event body control inventory exceeds 8192 typed nodes or 128 helper/tree depth")
      failure.isEmpty

    private def closed(symbol: Symbol): Boolean =
      symbol.is(Private) || symbol.is(Final) || symbol.owner.isTerm ||
        symbol.owner.is(Final) || symbol.owner.is(ModuleClass)

    private def function(tpe: Type): Boolean = (0 to 2).exists(arity =>
      tpe.widen.dealias.typeSymbol == requiredClass(s"scala.Function$arity")
    )

    private def module(symbol: Symbol, tree: Tree, depth: Int, arguments: Arguments): Unit =
      if !modules(symbol) && !inspectedModules(symbol) then
        if activeModules(symbol) then reject("event helper has cyclic singleton initialization")
        else
          templates.get(symbol.moduleClass) match
            case Some(template) =>
              if symbol.moduleClass.info.parents.exists(
                  _.typeSymbol != requiredClass("java.lang.Object")
                )
              then
                if pureSingleton(symbol.moduleClass, tree) then inspectedModules += symbol
                else reject("event helper singleton has uninspectable inherited initialization")
              else
                activeModules += symbol
                template.body.foreach:
                  case _: DefDef | _: TypeDef | _: Import => ()
                  case other => check(other, depth + 1, arguments)
                activeModules -= symbol
                inspectedModules += symbol
            // A getter may execute first-use initialization without an inspectable method body.
            // The shared compatible Pure singleton summary is the only imported proof admitted.
            case None =>
              if pureSingleton(symbol.moduleClass, tree) then inspectedModules += symbol
              else reject("event helper singleton has no compatible checked initialization summary")

    private def reference(tree: RefTree, depth: Int, arguments: Arguments): Unit =
      val symbol = tree.symbol
      if arguments.contains(symbol) then check(arguments(symbol), depth + 1, arguments - symbol)
      else if !symbol.is(Method) && symbol.info.isInstanceOf[ExprType] then
        reject("event by-name body has no inspectable actual expression in this compilation")
      else if enumEvidence(symbol) then
        module(symbol.owner.sourceModule, tree, depth + 1, arguments)
      else if symbol.is(Module) then module(symbol, tree, depth + 1, arguments)
      else if symbol.is(Method) && !leaves(symbol) then
        if symbol.is(Accessor) && !symbol.is(Deferred) then
          if symbol.owner.is(ModuleClass) && !activeModules(symbol.owner.sourceModule) then
            module(symbol.owner.sourceModule, tree, depth + 1, arguments)
          val field = symbol.accessedFieldOrGetter
          if symbol.is(Lazy) || (field.exists && field.is(Lazy)) then
            reject("event body cannot inspect a lazy accessor's hidden initialization")
        else helper(symbol, Nil, depth + 1, arguments)

    private def helper(
        symbol: Symbol,
        actuals: List[Tree],
        depth: Int,
        arguments: Arguments
    ): Unit =
      if active(symbol) then reject("recursive event body helpers are unsupported")
      else if !closed(symbol) then
        reject("event body helper requires statically closed final/private/local dispatch: " +
          symbol.fullName.toString)
      else
        definitions.get(symbol) match
          case Some(definition) if !definition.rhs.isEmpty =>
            active += symbol
            val parameters = symbol.paramSymss.flatten.filter(_.isTerm)
            val bindings = parameters.zip(actuals).toMap
            check(definition.rhs, depth + 1, arguments ++ bindings)
            active -= symbol
          case _ => reject(
              "event body helper or higher-order callback is uninspectable in this compilation: " +
                symbol.fullName.toString
            )

    private def callback(tree: Tree, depth: Int, arguments: Arguments): Unit =
      if budget(depth) then
        tree match
          case Inlined(_, bindings, expansion) =>
            bindings.foreach(check(_, depth + 1, arguments))
            callback(expansion, depth + 1, arguments)
          case Typed(expression, _) => callback(expression, depth + 1, arguments)
          case NamedArg(_, argument) => callback(argument, depth + 1, arguments)
          case Block(statements, expression) =>
            statements.foreach:
              case _: DefDef => ()
              case other => check(other, depth + 1, arguments)
            callback(expression, depth + 1, arguments)
          case closure: Closure =>
            closure.env.foreach(check(_, depth + 1, arguments))
            helper(closure.meth.symbol, Nil, depth + 1, arguments)
          case reference: RefTree if arguments.contains(reference.symbol) =>
            callback(arguments(reference.symbol), depth + 1, arguments - reference.symbol)
          case reference: RefTree =>
            val symbol = if reference.symbol.is(Accessor) then
              reference.symbol.accessedFieldOrGetter
            else reference.symbol
            if symbol.is(Mutable) || symbol.is(Lazy) || activeAliases(symbol) ||
              (reference.symbol.is(Accessor) && !closed(reference.symbol))
            then reject("event callback requires a closed immutable nonrecursive closure value")
            else
              values.get(symbol) match
                case Some(value) if !value.rhs.isEmpty =>
                  activeAliases += symbol
                  callback(value.rhs, depth + 1, arguments)
                  activeAliases -= symbol
                  reference match
                    case selection: Select => check(selection.qualifier, depth + 1, arguments)
                    case _ => ()
                case _ => reject("event higher-order callback has no inspectable closure body")
          case _ => reject("event higher-order callback has no inspectable closure body")

    private def applied(tree: Tree, depth: Int, arguments: Arguments): Unit =
      def flatten(current: Tree, actuals: List[Tree], level: Int): (Tree, List[Tree]) =
        if !budget(depth + level) then (current, Nil)
        else
          current match
            case Apply(function, operands) => flatten(function, operands ::: actuals, level + 1)
            case TypeApply(function, _) => flatten(function, actuals, level + 1)
            case _ => (current, actuals)
      val (target, actuals) = flatten(tree, Nil, 0)
      val symbol = target.symbol
      if controls(symbol) || owned(symbol) then
        reject("event body does not support nested processes, hardware control or hardware loops")
      else if target.isInstanceOf[Select] && function(target.asInstanceOf[Select].qualifier.tpe)
      then
        callback(target.asInstanceOf[Select].qualifier, depth + 1, arguments)
        actuals.foreach(check(_, depth + 1, arguments))
      else
        target match
          case selection: Select => check(selection.qualifier, depth + 1, arguments)
          case _ => ()
        actuals.foreach: actual =>
          if function(actual.tpe) then
            if collections(symbol) && !pureCallback(actual) then
              reject("finite data-map callback lacks the existing pure typed callback proof")
            callback(actual, depth + 1, arguments)
          else check(actual, depth + 1, arguments)
        if !leaves(symbol) && !collections(symbol) then
          target match
            case reference: RefTree if symbol.is(Accessor) && !symbol.is(Deferred) =>
              this.reference(reference, depth + 1, arguments)
            case _ => helper(symbol, actuals, depth + 1, arguments)

    private def check(tree: Tree, depth: Int, arguments: Arguments): Unit =
      if !tree.isEmpty && budget(depth) then
        tree match
          // The compiler's irrefutable Unit discard evaluates its selector but chooses no control.
          case Match(selector, CaseDef(Ident(name), guard, Literal(constant)) :: Nil)
              if name == nme.WILDCARD && guard.isEmpty && constant.value == () =>
            check(selector, depth + 1, arguments)
          case _: If | _: Match | _: WhileDo | _: Try | _: Return =>
            reject("event body contains unsupported authored " + tree.getClass.getSimpleName +
              "; use an unconditional update with pure digital expressions")
          case _: Literal | _: This | _: TypeTree | _: Import => ()
          case identifier: Ident => reference(identifier, depth + 1, arguments)
          case selection: Select =>
            check(selection.qualifier, depth + 1, arguments)
            reference(selection, depth + 1, arguments)
          case application: Apply => applied(application, depth + 1, arguments)
          case application: TypeApply => applied(application, depth + 1, arguments)
          case Inlined(_, bindings, expansion) =>
            bindings.foreach(check(_, depth + 1, arguments))
            check(expansion, depth + 1, arguments)
          case Typed(expression, _) => check(expression, depth + 1, arguments)
          case NamedArg(_, argument) => check(argument, depth + 1, arguments)
          case SeqLiteral(elements, _) => elements.foreach(check(_, depth + 1, arguments))
          case Block(statements, expression) =>
            statements.foreach(check(_, depth + 1, arguments))
            check(expression, depth + 1, arguments)
          case definition: ValDef => check(definition.rhs, depth + 1, arguments)
          case _: DefDef | _: TypeDef =>
            () // Inspect an actual invoked body, not unused definitions.
          case Assign(left, right) =>
            check(left, depth + 1, arguments)
            check(right, depth + 1, arguments)
          case closure: Closure => callback(closure, depth + 1, arguments)
          case _: New => reject("event body contains an unsupported allocation")
          case _ => reject("event body contains an uninspectable typed expression: " +
              tree.getClass.getSimpleName)
