#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, before: str, after: str) -> None:
    content = path.read_text(encoding="utf-8")
    if before not in content:
        raise SystemExit(f"expected fragment not found in {path}: {before[:120]!r}")
    path.write_text(content.replace(before, after, 1), encoding="utf-8")


api = Path("core/scala/api/src/nodal/RegisterFactoryApi.scala")
content = api.read_text(encoding="utf-8")
content = content.replace("import scala.language.implicitConversions\n\n", "", 1)
start = content.index("/** Width-safe register-map offset used only during elaboration. */")
end = content.index("/**\n * Immutable bus-neutral programmer-visible register specification.")
replacement = '''/**
 * Constant register-map offset accepted directly from Scala integer literals.
 * Symbolic hardware expressions are deliberately excluded.
 */
type RegisterOffset = Int | Long | BigInt

/** Constant byte/word extent accepted directly from Scala integer literals. */
type RegisterSize = Int | Long | BigInt

/** Field bit selection accepted from either one bit or an explicit range. */
type FieldBits = Int | BitRange

/** Sentinel used when a field intentionally has no declared reset value. */
object FieldReset:
  case object Unspecified

/** Optional reset expression without Option or implicit-conversion ceremony. */
type FieldReset[A <: Data] = Expr[A] | FieldReset.Unspecified.type

/** Static or symbolic repetition count for a register-map array. */
type RegisterCount = Int | Expr[Integer]

'''
api.write_text(content[:start] + replacement + content[end:], encoding="utf-8")

checker = Path("scripts/check_increment116.py")
replace_once(checker, '            "opaque type RegisterOffset",\n', '            "type RegisterOffset = Int | Long | BigInt",\n')
replace_once(checker, '            "opaque type RegisterSize",\n', '            "type RegisterSize = Int | Long | BigInt",\n')
replace_once(checker, '            "sealed trait RegisterCount",\n', '            "type RegisterCount = Int | Expr[Integer]",\n')
replace_once(checker, '            "infix def downto",\n', '            "infix def downto",\n            "type FieldBits = Int | BitRange",\n            "type FieldReset[A <: Data] = Expr[A] | FieldReset.Unspecified.type",\n')
replace_once(
    checker,
    '        "Map[String",\n    ):\n',
    '        "Map[String",\n        "given Conversion[",\n        "scala.language.implicitConversions",\n    ):\n',
)
replace_once(checker, '            "fixed ABI symbols",\n', '            "Fixed ABI symbols",\n')

reference = Path("docs/language-reference/register-factory-api-v0.1.md")
replace_once(
    reference,
    "Offsets are fixed elaboration-time values. A literal `0x04` converts to `RegisterOffset`. A symbolic `Param[Integer]` is intentionally not a `RegisterOffset`.",
    "Offsets are fixed elaboration-time values. `RegisterOffset` accepts Scala `Int`, `Long`, or `BigInt` constant forms directly, so a literal such as `0x04` needs no implicit conversion or language-feature import. A symbolic `Param[Integer]` is intentionally not a `RegisterOffset`.",
)

gate = Path("docs/design-gates/NodalRegisterFactory-DG-v0.1.md")
replace_once(
    gate,
    "Published maps use explicit register offsets and field positions by default. A fixed register offset accepts elaboration-time `RegisterOffset` values through literal conversions; it deliberately does not accept a symbolic `Param`. Symbolic variation is expressed only through separately typed geometry such as `RegisterCount`.",
    "Published maps use explicit register offsets and field positions by default. `RegisterOffset` is a Scala 3 union of `Int`, `Long`, and `BigInt` constant forms, so literals require no implicit conversion, language-feature import, or project compiler flag. It deliberately excludes symbolic `Param` values. Symbolic variation is expressed only through the separately typed `RegisterCount = Int | Expr[Integer]` geometry contract.",
)
replace_once(
    gate,
    "- the register ABI exists before any bus is selected;\n",
    "- the register ABI exists before any bus is selected;\n- concise literal forms use Scala 3 union types rather than implicit conversions or mandatory compiler flags;\n",
)
