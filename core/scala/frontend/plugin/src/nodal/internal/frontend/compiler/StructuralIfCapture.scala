package nodal.internal.frontend.compiler

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.util.Property

/** Syntax and typed-tree construction only. The shared callback phase remains the sole owner of
  * effect validation, helper summaries, callback attestation and validation budgets.
  */
private[compiler] final class StructuralIfCapture:
  private val expectedReadOnly = new Property.StickyKey[Type]

  sealed trait Predicate:
    def operands: List[Tree]
    def lower(using Context): Tree

  private final case class Comparison(
      left: Tree,
      right: Tree,
      operation: String,
      evidence: List[Tree] = Nil
  ) extends Predicate:
    def operands: List[Tree] = left :: right :: evidence
    def lower(using Context): Tree =
      val rhs = runtime("integerOperand").appliedTo(right)
      runtime("compare").appliedToArgs(List(left, rhs, Literal(Constant(operation))))

  private final case class Binary(left: Predicate, right: Predicate, operation: String)
      extends Predicate:
    def operands: List[Tree] = left.operands ++ right.operands
    def lower(using Context): Tree =
      runtime(operation).appliedToArgs(List(left.lower, right.lower))

  private final case class Negated(value: Predicate) extends Predicate:
    def operands: List[Tree] = value.operands
    def lower(using Context): Tree = runtime("not").appliedTo(value.lower)

  private def exprClass(using Context): ClassSymbol = requiredClass("nodal.Expr")
  private def integerClass(using Context): ClassSymbol = requiredClass("nodal.Integer")
  private def runtime(name: String)(using Context): Tree =
    ref(requiredMethod("nodal.StructuralIfRuntime." + name))
  private def methods(owner: Symbol, names: String*)(using Context): Set[Symbol] =
    names.flatMap(name => owner.info.member(termName(name)).alternatives.map(_.symbol)).toSet
  private def compileTimeOnly(symbol: Symbol)(using Context): Boolean =
    symbol.hasAnnotation(requiredClass("scala.annotation.compileTimeOnly"))
  private def equalityMarkers(using Context): Set[Symbol] =
    methods(exprClass, "==", "!=").filter(symbol =>
      symbol.owner == exprClass && compileTimeOnly(symbol)
    )
  private def orderMarkers(using Context): Set[Symbol] =
    val owner = requiredModule("nodal.CandidateApi$package").moduleClass
    methods(owner, "<", "<=", ">", ">=").filter: symbol =>
      symbol.owner == owner && compileTimeOnly(symbol) &&
        symbol.paramSymss.flatten.headOption.exists(parameter => integer(parameter.info))
  private def marker(symbol: Symbol)(using Context): Boolean =
    equalityMarkers(symbol) || orderMarkers(symbol)
  private def integer(tpe: Type)(using Context): Boolean =
    tpe.widen.dealias.baseType(exprClass) match
      case AppliedType(_, List(kind)) => kind =:= integerClass.typeRef
      case _ => false
  private def legacy(tree: Tree)(using Context): Boolean = tree match
    case Apply(select: Select, List(right)) =>
      methods(defn.AnyClass, "==", "!=")(select.symbol) &&
      integer(select.qualifier.tpe) && integer(right.tpe)
    case _ => false

  def symbolic(tree: Tree)(using Context): Boolean =
    var found = false
    // The compiler already bounds source trees. Stop immediately after finding the owned marker;
    // the closed grammar below applies an additional finite predicate-node/depth limit.
    val scan = new TreeTraverser:
      override def traverse(current: Tree)(using Context): Unit =
        if marker(current.symbol) || legacy(current) then found = true
        if !found then traverseChildren(current)
    scan.traverse(tree)
    found

  def predicate(tree: Tree)(using Context): Option[Predicate] =
    var work = 0
    def parse(current: Tree, depth: Int): Option[Predicate] =
      work += 1
      if work > 8192 || depth > 128 then None
      else
        current match
          case Typed(value, _) => parse(value, depth + 1)
          case Apply(Apply(select: Select, List(right)), evidence)
              if equalityMarkers(select.symbol) && integer(select.qualifier.tpe) =>
            Some(Comparison(
              select.qualifier,
              right,
              if select.name.toString == "==" then "eq" else "ne",
              evidence
            ))
          case Apply(Apply(function, List(left)), List(right)) if orderMarkers(function.symbol) =>
            val operation = function.symbol.name.toString match
              case "<" => "lt"
              case "<=" => "le"
              case ">" => "gt"
              case ">=" => "ge"
            Some(Comparison(left, right, operation))
          case Apply(select: Select, List(right)) if legacy(current) =>
            Some(Comparison(
              select.qualifier,
              right,
              if select.name.toString == "==" then "eq" else "ne"
            ))
          case Apply(select: Select, List(right))
              if methods(defn.BooleanClass, "&&", "||")(select.symbol) =>
            for
              left <- parse(select.qualifier, depth + 1)
              other <- parse(right, depth + 1)
            yield Binary(left, other, if select.name.toString == "&&" then "and" else "or")
          case select: Select if methods(defn.BooleanClass, "unary_!")(select.symbol) =>
            parse(select.qualifier, depth + 1).map(Negated.apply)
          case _ => None
    parse(tree, 0)

  def moduleContext(using Context): Boolean =
    var owner = ctx.owner
    var found = false
    while owner.exists && !found do
      found = owner.isClass && owner.derivesFrom(requiredClass("nodal.Module"))
      owner = owner.owner
    found

  private def readOnly(value: Type)(using Context): Boolean = value.dealias match
    case AppliedType(tycon, List(_)) => tycon.typeSymbol == exprClass
    case _ => false

  // Propagate only an immediate typed result obligation, never through a call or a selection.
  def admitResult(tree: Tree, expected: Type)(using Context): Unit =
    if readOnly(expected) then
      tree match
        case value: If =>
          value.putAttachment(expectedReadOnly, expected)
          admitResult(value.thenp, expected)
          admitResult(value.elsep, expected)
        case Block(_, value) => admitResult(value, expected)
        case Typed(value, target) => admitResult(value, target.tpe)
        case _ => ()

  def resultType(tree: If)(using Context): Option[Type] =
    val result =
      tree.getAttachment(expectedReadOnly).getOrElse(tree.tpe.widenUnion.widenSingletons())
    Option.when(result =:= defn.UnitType || readOnly(result))(result)

  def lower(tree: If, predicate: Predicate, result: Type)(using Context): Tree =
    def branch(body: Tree): Tree =
      val closure = Lambda(MethodType(Nil)(_ => Nil, _ => result), _ => body)
      ref(requiredMethod("nodal.DigitalCollectionCallback.checked0"))
        .appliedToType(result).appliedTo(closure)
    val arguments = List(predicate.lower, branch(tree.thenp), branch(tree.elsep))
    val call = if result =:= defn.UnitType then runtime("statement")
    else
      result.dealias match
        case AppliedType(_, List(kind)) => runtime("value").appliedToType(kind)
    call.appliedToArgs(arguments).withSpan(tree.span)
