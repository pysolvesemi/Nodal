package nodal.internal.frontend.compiler

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*

/** Exact owned action entry points. The existing Sequential/Pure validator owns all effects; this
  * adapter only identifies source operands and constructs checked Function0 callbacks.
  */
private[compiler] final class FlatFsmCapture:
  final case class Call(
      runtime: String,
      enumType: Type,
      operands: List[Tree],
      bodies: List[Tree],
      graph: Option[Tree],
      original: Tree,
      runtimeOwner: String = "nodal.FlatFsmRuntime",
      binding: Boolean = false
  ):
    def graphOnly: Boolean = runtime.isEmpty

  private def owned(symbol: Symbol, owner: String, names: String*)(using Context): Boolean =
    val cls = requiredClass(owner)
    names.exists(name => cls.info.member(termName(name)).alternatives.exists(_.symbol == symbol))

  private def switchEntry(symbol: Symbol)(using Context): Boolean =
    val owner = requiredModule("nodal.DigitalEnumApi$package")
    owner.info.member(termName("switchEnum")).alternatives.exists(_.symbol == symbol)

  private def source(tree: Tree, lists: List[List[Tree]] = Nil)(using
      Context
  )
      : (Tree, List[Type], List[List[Tree]]) = tree match
    case Apply(function, arguments) => source(function, arguments :: lists)
    case TypeApply(function, arguments) => (function, arguments.map(_.tpe), lists)
    case _ => (tree, Nil, lists)

  private def receiverType(receiver: Tree, owner: String)(using Context): Option[Type] =
    receiver.tpe.widen.dealias.baseType(requiredClass(owner)) match
      case AppliedType(_, argument :: Nil) => Some(argument)
      case _ => None

  def call(tree: Tree)(using Context): Option[Call] =
    val (function, types, lists) = source(tree)
    function match
      case selection: Select if owned(selection.symbol, "nodal.FsmAction", "bind") =>
        lists match
          case List(List(body)) =>
            receiverType(selection.qualifier, "nodal.FsmAction").map(enumType =>
              Call(
                "actionBinding",
                enumType,
                List(selection.qualifier),
                List(body),
                None,
                tree,
                "nodal.FsmDefinitionRuntime",
                binding = true
              )
            )
          case _ => None
      case selection: Select if owned(selection.symbol, "nodal.FsmGuard", "bind") =>
        lists match
          case List(List(value)) =>
            receiverType(selection.qualifier, "nodal.FsmGuard").map(enumType =>
              Call(
                "",
                enumType,
                List(selection.qualifier, value),
                Nil,
                None,
                tree,
                binding = true
              )
            )
          case _ => None
      case selection: Select
          if owned(selection.symbol, "nodal.StateBuilder", "entry", "active", "exit") =>
        lists match
          case List(List(body)) =>
            receiverType(selection.qualifier, "nodal.StateBuilder").map(enumType =>
              Call(
                "action",
                enumType,
                List(selection.qualifier, Literal(Constant(selection.symbol.name.toString))),
                List(body),
                None,
                tree
              )
            )
          case _ => None
      case selection: Select
          if owned(selection.symbol, "nodal.StateBuilder", "on", "onWithAction") =>
        lists match
          case List(List(condition, mode), List(to)) if selection.symbol.name.toString == "on" =>
            receiverType(selection.qualifier, "nodal.StateBuilder").map(enumType =>
              Call(
                "",
                enumType,
                List(selection.qualifier, condition, mode, to),
                Nil,
                None,
                tree
              )
            )
          case List(List(condition, mode), List(to), List(body))
              if selection.symbol.name.toString == "onWithAction" =>
            receiverType(selection.qualifier, "nodal.StateBuilder").map(enumType =>
              Call(
                "transitionAction",
                enumType,
                List(selection.qualifier, condition, mode, to),
                List(body),
                None,
                tree
              )
            )
          case _ => None
      case selection: Select if owned(selection.symbol, "nodal.EnumSwitchBuilder", "is") =>
        lists match
          case List(List(value), List(body)) =>
            receiverType(selection.qualifier, "nodal.EnumSwitchBuilder").map(enumType =>
              Call(
                "switchCase",
                enumType,
                List(selection.qualifier, value),
                List(body),
                None,
                tree
              )
            )
          case _ => None
      case _ if switchEntry(function.symbol) =>
        (types, lists) match
          case (
                enumType :: Nil,
                List(List(selector), List(invalid), List(graph), List(evidence))
              ) =>
            Some(Call(
              "switchEnum",
              enumType,
              List(selector, evidence),
              List(invalid),
              Some(graph),
              tree
            ))
          case _ => None
      case _ => None

  def lower(call: Call)(using Context): Tree =
    if call.graphOnly then call.original
    else
      val checked = call.bodies.map: body =>
        val closure = Lambda(MethodType(Nil)(_ => Nil, _ => defn.UnitType), _ => body)
        ref(requiredMethod("nodal.DigitalCollectionCallback.checkedSequential")).appliedTo(closure)
      val arguments = if call.runtime == "switchEnum" then
        List(call.operands.head, checked.head, call.graph.get, call.operands(1))
      else call.operands ++ checked
      ref(requiredMethod(call.runtimeOwner + "." + call.runtime))
        .appliedToType(call.enumType).appliedToArgs(arguments).withSpan(call.original.span)
