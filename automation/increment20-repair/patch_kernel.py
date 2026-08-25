#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def main(root: Path) -> None:
    candidate = (root / "core/scala/api/src/nodal/CandidateApi.scala").read_text(
        encoding="utf-8"
    )
    for fragment in (
        "final case class KernelLiteral(",
        "val resultType: Option[KernelTypeDescriptor]",
        "val literal: Option[KernelLiteral]",
        "private[nodal] val edge: Option[ClockEdge]",
        "private[nodal] val resetPolicy: Option[ResetPolicy]",
        "def expressionDataType(value: Expr[?])",
        "def literalUInt(value: Int, width: Int)",
    ):
        if fragment not in candidate:
            raise SystemExit(f"CandidateApi prerequisite is missing: {fragment}")

    path = root / "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
    text = path.read_text(encoding="utf-8")

    if "parameterBindings: Vector[(String, String)]" not in text:
        text = replace_once(
            text,
            """private[nodal] final case class KernelInstanceSnapshot(
    path: String,
    childModule: String,
    lexicalDomain: Option[String],
    bindings: Vector[(String, String)]
)""",
            """private[nodal] final case class KernelInstanceSnapshot(
    path: String,
    childModule: String,
    lexicalDomain: Option[String],
    bindings: Vector[(String, String)],
    parameterBindings: Vector[(String, String)] = Vector.empty
)""",
            "instance snapshot schema",
        )

    if "edge: Option[String] = None" not in text:
        text = replace_once(
            text,
            """private[nodal] final case class KernelDomainSnapshot(
    path: String,
    name: String,
    kind: String,
    binding: Option[String]
)""",
            """private[nodal] final case class KernelDomainSnapshot(
    path: String,
    name: String,
    kind: String,
    binding: Option[String],
    edge: Option[String] = None,
    resetPolicy: Option[String] = None,
    attributes: Vector[(String, String)] = Vector.empty
)""",
            "domain snapshot schema",
        )

    if "case expression: KernelExpr[?] if expression.literal.nonEmpty" not in text:
        text = replace_once(
            text,
            """    case big: BigInt => big.toString
    case dataType: DataType[?] => renderType(dataType, owner)""",
            """    case big: BigInt => big.toString
    case expression: KernelExpr[?] if expression.literal.nonEmpty =>
      expression.literal.map(_.value).getOrElse("")
    case dataType: DataType[?] => renderType(dataType, owner)""",
            "literal rendering",
        )

    helper_anchor = (
        "  private def snapshots(resolved: Map[DomainRef, String]): "
        "Vector[KernelModuleSnapshot] ="
    )
    if "private def bridgeClockEdge" not in text:
        helpers = """  private def bridgeClockEdge(edge: ClockEdge): String = edge match
    case ClockEdge.Rising => "rising"
    case ClockEdge.Falling => "falling"

  private def bridgeResetPolicy(policy: ResetPolicy): String = policy match
    case ResetPolicy.None => "none"
    case ResetPolicy.Sync => "sync"
    case ResetPolicy.Async => "async"
    case _: ResetPolicy.AsyncAssertSyncRelease => "async_assert_sync_release"

  private def bridgeDomainAttributes(
      domain: ClockDomain
  ): Vector[(String, String)] =
    val polarity = domain.resetPolarity.toVector.map: value =>
      "reset_polarity" -> (value match
        case ResetPolarity.ActiveHigh => "active_high"
        case ResetPolarity.ActiveLow => "active_low")
    val stages = domain.resetPolicy.toVector.flatMap:
      case ResetPolicy.AsyncAssertSyncRelease(count) =>
        Vector("reset_stages" -> count.toString)
      case _ => Vector.empty
    val relation = domain.relation.toVector.map: value =>
      "clock_relation" -> (value match
        case ClockRelation.Same => "alias"
        case _: ClockRelation.Ratio => "ratio"
        case _: ClockRelation.Synchronous => "synchronous"
        case ClockRelation.MutuallyExclusive => "mutually_exclusive"
        case ClockRelation.Asynchronous => "asynchronous"
        case ClockRelation.Unknown => "unknown")
    (polarity ++ stages ++ relation).sortBy(_._1)

"""
        if text.count(helper_anchor) != 1:
            raise SystemExit("snapshot helper anchor mismatch")
        text = text.replace(helper_anchor, helpers + helper_anchor, 1)

    old_domain = """      val domains = module.domains.toVector.map: domain =>
        KernelDomainSnapshot(
          domainPath(domain.reference),
          domainName(domain.reference),
          domain.kind.label,
          if domain.kind == KernelDomainKind.Required then resolved.get(domain.reference) else None
        )"""
    if old_domain in text:
        text = text.replace(
            old_domain,
            """      val domains = module.domains.toVector.map: domain =>
        KernelDomainSnapshot(
          domainPath(domain.reference),
          domainName(domain.reference),
          domain.kind.label,
          if domain.kind == KernelDomainKind.Required then resolved.get(domain.reference) else None,
          domain.domain.edge.map(bridgeClockEdge),
          domain.domain.resetPolicy.map(bridgeResetPolicy),
          bridgeDomainAttributes(domain.domain)
        )""",
            1,
        )

    old_instance = """        KernelInstanceSnapshot(
          instancePath(module.handle, instance.ordinal),
          modulePath(instance.child),
          instance.lexicalDomain.flatMap(domain => resolved.get(domainRef(domain))),
          bindings.toVector
        )"""
    if old_instance in text:
        text = text.replace(
            old_instance,
            """        val parameters = instance.parameterOverrides.toVector.map:
          case (parameter, value) =>
            val reference = parameter match
              case candidate: AnyRef =>
                Option(declarationIds.get(candidate)).getOrElse(
                  fail(
                    "NODAL-PARAMETER-BINDING-016",
                    "instance parameter override targets an unknown parameter",
                    Some(instancePath(module.handle, instance.ordinal))
                  )
                )
              case _ =>
                fail(
                  "NODAL-PARAMETER-BINDING-016",
                  "instance parameter override is not a declaration",
                  Some(instancePath(module.handle, instance.ordinal))
                )
            declarationName(reference) -> renderAny(value, module.handle)
        KernelInstanceSnapshot(
          instancePath(module.handle, instance.ordinal),
          modulePath(instance.child),
          instance.lexicalDomain.flatMap(domain => resolved.get(domainRef(domain))),
          bindings.toVector.sortBy(_._1),
          parameters.sortBy(_._1)
        )""",
            1,
        )

    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    import sys

    main(Path(sys.argv[1]).resolve())
