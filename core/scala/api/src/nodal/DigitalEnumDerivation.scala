package nodal

import scala.deriving.Mirror
import scala.quoted.{Expr as QuotedExpr, *}

/** Compile-time authored identity; runtime metadata never depends on JVM class names. */
private[nodal] object DigitalEnumDerivation:
  def derived[A: Type](mirror: QuotedExpr[Mirror.SumOf[A]])(using Quotes): QuotedExpr[HwEnum[A]] =
    import quotes.reflect.*
    val declaration = TypeRepr.of[A].typeSymbol
    def reject(message: String): Nothing =
      report.errorAndAbort(s"NODAL-ENUM-054-001: $message")

    if !declaration.flags.is(Flags.Enum) then
      reject("HwEnum derives only native Scala enums")
    if TypeRepr.of[A].typeArgs.nonEmpty ||
      declaration.primaryConstructor.paramSymss.flatten
        .exists(_.isTypeParam)
    then reject("generic enum declarations cannot define a hardware ABI")

    val cases = declaration.children
    if cases.isEmpty then reject("hardware enums require at least one singleton case")
    if cases.size > DigitalEnumContract.MaximumCases then
      reject(s"hardware enum exceeds ${DigitalEnumContract.MaximumCases} cases")
    if cases.exists(value => !value.isTerm || !value.flags.is(Flags.Enum)) then
      reject("hardware enums do not support payload cases")

    var owner = declaration.owner
    while owner != Symbol.noSymbol && !owner.flags.is(Flags.Package) do
      if owner.isDefDef || (owner.isClassDef && !owner.flags.is(Flags.Module)) then
        reject("hardware enums must be top-level or nested in stable objects")
      owner = owner.owner

    def span(symbol: Symbol): QuotedExpr[Option[SourceSpan]] = symbol.pos match
      case Some(position) =>
        '{
          Some(SourceSpan(
            ${ QuotedExpr(position.sourceFile.path) },
            ${ QuotedExpr(position.startLine + 1) },
            ${ QuotedExpr(position.startColumn + 1) },
            ${ QuotedExpr(position.endLine + 1) },
            ${ QuotedExpr(position.endColumn + 1) }
          ))
        }
      case None => '{ None }

    def authoredIdentity(symbol: Symbol): String =
      if symbol == Symbol.noSymbol || symbol.fullName == "<root>" || symbol.fullName == "<empty>"
      then ""
      else
        val prefix = authoredIdentity(symbol.owner)
        val name = if symbol.flags.is(Flags.Module) && symbol.isClassDef then
          symbol.name.stripSuffix("$")
        else symbol.name
        val component = DigitalEnumContract.identityComponent(name)
        if prefix.isEmpty then component else s"$prefix.$component"
    val identity = authoredIdentity(declaration)
    val values = cases.map: value =>
      val authoredName = value.name
      val caseId = s"$identity.${DigitalEnumContract.identityComponent(authoredName)}"
      val term = Ref(value).asExprOf[A]
      '{
        EnumDeclaredCase[A](
          ${ QuotedExpr(caseId) },
          ${ QuotedExpr(authoredName) },
          $term,
          ${ span(value) }
        )
      }
    val sequence = QuotedExpr.ofSeq(values)
    '{
      val ordered = $sequence.toVector.sortBy(value => $mirror.ordinal(value.value))
      HwEnum.fromDeclaration(EnumDeclaration[A](
        ${ QuotedExpr(identity) },
        ordered,
        ${ span(declaration) }
      ))
    }
