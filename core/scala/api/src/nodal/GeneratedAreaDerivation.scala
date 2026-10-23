package nodal

import scala.quoted.{Expr as QuotedExpr, *}

private[nodal] object GeneratedAreaDerivation:
  private def declared[A <: Area: Type](name: QuotedExpr[String])(using
      Quotes
  )
      : quotes.reflect.TypeRepr =
    import quotes.reflect.*
    val selected = name.valueOrAbort
    def reject(message: String): Nothing = report.errorAndAbort("NODAL-AREA-058-MEMBER: " + message)
    def field(tpe: TypeRepr, depth: Int = 0): Option[TypeRepr] =
      if depth >= 128 then reject("member type exceeds depth budget")
      tpe.dealias match
        case Refinement(parent, member, info) =>
          if member == selected then Some(info) else field(parent, depth + 1)
        case AndType(left, right) => field(left, depth + 1).orElse(field(right, depth + 1))
        case owner =>
          val symbol = owner.typeSymbol.fieldMember(selected)
          if symbol == Symbol.noSymbol then None
          else
            if symbol.flags.is(Flags.Private) || symbol.flags.is(Flags.Protected) ||
              symbol.flags.is(Flags.Mutable) || symbol.flags.is(Flags.Lazy)
            then reject("generated members must be accessible immutable eager vals")
            Some(owner.memberType(symbol))
    field(TypeRepr.of[A]).getOrElse(reject("no such declared member: " + selected))
      .widenTermRefByName.dealias

  def member[A <: Area: Type](
      value: QuotedExpr[GeneratedAreaRef[A]],
      name: QuotedExpr[String]
  )(using Quotes): QuotedExpr[Any] =
    import quotes.reflect.*
    val result = declared[A](name)
    result.baseType(Symbol.requiredClass("nodal.GeneratedAreas")).typeArgs match
      case element :: Nil => element.asType match
          case '[child] => '{ GeneratedAreaRuntime.family[child & Area]($value, $name) }
      case _ => result.baseType(Symbol.requiredClass("nodal.Instance")).typeArgs match
          case element :: Nil => element.asType match
              case '[child] => '{ GeneratedAreaRuntime.instance[child & Module]($value, $name) }
          case _ => valueMember(value, name, result)

  private def valueMember[A <: Area: Type](using quotes: Quotes)(
      value: QuotedExpr[GeneratedAreaRef[A]],
      name: QuotedExpr[String],
      result: quotes.reflect.TypeRepr
  ): QuotedExpr[Any] =
    import quotes.reflect.*
    result.asType match
          case '[member] =>
            if result <:< TypeRepr.of[Area] then
              '{ GeneratedAreaRuntime.nested[member & Area]($value, $name) }
            else if result <:< TypeRepr.of[InterfaceEndpoint] then
              '{ GeneratedAreaRuntime.interface[member & InterfaceEndpoint]($value, $name) }
            else if result <:< TypeRepr.of[Expr[Data]] then
              val kind =
                if result.baseType(Symbol.requiredClass("nodal.Register")).typeArgs.nonEmpty then
                  "register"
                else if result.baseType(Symbol.requiredClass("nodal.Signal")).typeArgs.nonEmpty then
                  "signal"
                else "read_only"
              '{ GeneratedAreaRuntime.member[member]($value, $name, ${ QuotedExpr(kind) }) }
            else
              report.errorAndAbort(
                "NODAL-AREA-058-MEMBER: generated member is not a hardware value, family or nested Area"
              )

  def familyAt[A <: Area: Type](
      value: QuotedExpr[GeneratedAreaRef[A]],
      name: QuotedExpr[String],
      ordinal: QuotedExpr[Int | Expr[Integer]]
  )(using Quotes): QuotedExpr[Any] =
    import quotes.reflect.*
    declared[A](name).baseType(Symbol.requiredClass("nodal.GeneratedAreas")).typeArgs match
      case element :: Nil => element.asType match
          case '[child] =>
            '{ GeneratedAreaRuntime.family[child & Area]($value, $name).apply($ordinal) }
      case _ => report.errorAndAbort(
          "NODAL-AREA-058-MEMBER: only an exact eager GeneratedAreas member can be applied"
        )
