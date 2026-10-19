package nodal.internal.frontend.compiler

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.report
import dotty.tools.dotc.util.Property

import scala.collection.mutable

/** Exact owned source entry points and typed callback construction. All effects and proof summaries
  * stay with the shared callback validator; predicates reuse the structural grammar.
  */
private[compiler] final class BehavioralGuardCapture(predicates: StructuralIfCapture):
  final case class Guard(name: String, condition: Option[Tree], body: Tree, original: Tree)

  private def entry(symbol: Symbol, names: String*)(using Context): Boolean =
    val owner = requiredModule("nodal.CandidateApi$package").moduleClass
    names.exists(name => owner.info.member(termName(name)).alternatives.exists(_.symbol == symbol))

  private val analogOwned = new Property.StickyKey[Boolean]

  def guard(tree: Tree)(using Context): Option[Guard] =
    if tree.getAttachment(analogOwned).contains(true) then None else rawGuard(tree)

  // Lexically nested definitions/closures can escape their original procedure. Check the actual
  // runtime owner before every preserved analog predicate, including lexically classified guards.
  def lowerAnalog(tree: Tree)(using Context): Option[Tree] =
    Option.when(tree.getAttachment(analogOwned).contains(true)):
      Block(
        List(ref(requiredMethod("nodal.SequentialRuntime.requireAnalogProcedure")).appliedToNone),
        tree
      ).withSpan(tree.span)

  private def rawGuard(tree: Tree)(using Context): Option[Guard] = tree match
    case Apply(Apply(function, List(condition)), List(body))
        if entry(function.symbol, "when", "elsewhen") =>
      Some(Guard(function.symbol.name.toString, Some(condition), body, tree))
    case Apply(function, List(body)) if entry(function.symbol, "otherwise") =>
      Some(Guard("otherwise", None, body, tree))
    case _ => None

  /** Preserve the existing analog owner by exact typed capability/context identity. This is
    * classification before digital validation, never fallback after a failed effect proof.
    * Context-free helpers with no analog identity still require the digital profile.
    */
  def classifyAnalog(tree: Tree, definitions: collection.Map[Symbol, DefDef])(using Context): Unit =
    val analogProcedure = requiredModule("nodal.ContinuousTimeCandidateApi$package")
      .info.member(termName("analogProcedure")).alternatives.map(_.symbol).toSet
    val capabilities =
      List(requiredClass("nodal.Variable"), requiredClass("nodal.VariableSelection"))
    def analogIdentity(tree: Tree): Boolean =
      var found = false
      var visited = 0
      val helpers = mutable.HashSet.empty[Symbol]
      val scan = new TreeTraverser:
        override def traverse(current: Tree)(using Context): Unit =
          visited += 1
          if !found && visited <= 8192 then
            if current.isTerm &&
              capabilities.exists(capability => current.tpe.widen.dealias.derivesFrom(capability))
            then
              found = true
            else
              current match
                case reference: RefTree
                    if definitions.contains(reference.symbol) &&
                      helpers.add(reference.symbol) && helpers.size <= 128 =>
                  traverse(definitions(reference.symbol).rhs)
                case _ => ()
              if !found then traverseChildren(current)
      scan.traverse(tree)
      found
    def preserveChain(statements: List[Tree]): Unit =
      var analog = false
      statements.foreach: current =>
        rawGuard(current) match
          case Some(value) =>
            if value.name == "when" then
              analog = current.getAttachment(analogOwned).contains(true)
            else if analog then
              current.putAttachment(analogOwned, true)
            if value.name == "otherwise" then analog = false
          case _ => analog = false
    var lexical = 0
    val scan = new TreeTraverser:
      override def traverse(current: Tree)(using Context): Unit =
        val boundary = current.isInstanceOf[Apply] && analogProcedure(current.symbol)
        if boundary then lexical += 1
        rawGuard(current).foreach: _ =>
          if lexical > 0 then current.putAttachment(analogOwned, true)
          else if analogIdentity(current) then
            current.putAttachment(analogOwned, true)
        traverseChildren(current)
        current match
          case block: Block => preserveChain(block.stats :+ block.expr)
          case template: Template => preserveChain(template.body)
          case _ => ()
        if boundary then lexical -= 1
    scan.traverse(tree)

  def checkChains(tree: Tree)(using Context): Unit =
    val scan = new TreeTraverser:
      override def traverse(current: Tree)(using Context): Unit =
        current match
          case block: Block => chains(block.stats :+ block.expr)
          case template: Template => chains(template.body)
          case definition: DefDef if !definition.rhs.isInstanceOf[Block] =>
            chains(List(definition.rhs))
          case definition: ValDef if !definition.rhs.isInstanceOf[Block] =>
            chains(List(definition.rhs))
          case application: Apply => guard(application).foreach: value =>
              if !value.body.isInstanceOf[Block] then chains(List(value.body))
          case _ => ()
        traverseChildren(current)
    scan.traverse(tree)

  def chains(statements: List[Tree])(using Context): Unit =
    var open = false
    statements.foreach: tree =>
      guard(tree) match
        case Some(value) if value.name == "when" => open = true
        case Some(value) =>
          if !open then
            report.error(
              "NODAL-SEQUENTIAL-056-CHAIN: elsewhen/otherwise must immediately follow a when/elsewhen in the same source block",
              tree.srcPos
            )
          open = value.name == "elsewhen" && open
        case _ => open = false

  def lower(guard: Guard)(using Context): Tree =
    val closure = Lambda(MethodType(Nil)(_ => Nil, _ => defn.UnitType), _ => guard.body)
    val checked = ref(requiredMethod("nodal.DigitalCollectionCallback.checkedSequential"))
      .appliedTo(closure)
    val arguments = guard.condition.toList.map(condition =>
      predicates.predicate(condition, behavioral = true).get.lower(behavioral = true)
    ) :+ checked
    ref(requiredMethod("nodal.SequentialRuntime." + guard.name))
      .appliedToArgs(arguments).withSpan(guard.original.span)
