#!/usr/bin/env python3
"""Patch the Increment 20 branch with bridge-required snapshot metadata."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


def replace_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return updated


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    path = root / "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
    text = path.read_text(encoding="utf-8")

    text = replace_once(
        text,
        re.escape(
            """private[nodal] final case class KernelDomainSnapshot(
    path: String,
    name: String,
    kind: String,
    binding: Option[String]
)"""
        ),
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

    text = replace_once(
        text,
        re.escape(
            """private[nodal] final case class KernelInstanceSnapshot(
    path: String,
    childModule: String,
    lexicalDomain: Option[String],
    bindings: Vector[(String, String)]
)"""
        ),
        """private[nodal] final case class KernelInstanceSnapshot(
    path: String,
    childModule: String,
    lexicalDomain: Option[String],
    bindings: Vector[(String, String)],
    parameterBindings: Vector[(String, String)] = Vector.empty
)""",
        "instance snapshot schema",
    )

    text = replace_once(
        text,
        re.escape(
            """    case big: BigInt => big.toString
    case dataType: DataType[?] => renderType(dataType, owner)"""
        ),
        """    case big: BigInt => big.toString
    case expression: KernelExpr[?] if expression.literal.nonEmpty =>
      expression.literal.map(_.value).getOrElse("")
    case discipline: NamedDiscipline => discipline.name
    case Electrical => "electrical"
    case mode: DriveMode.Value[?] => mode.name
    case placement: InoutPlacement => placement.toString
    case profile: ResolutionProfile => profile.toString
    case dataType: DataType[?] => renderType(dataType, owner)""",
        "bridge metadata rendering",
    )

    helper_anchor = (
        "  private def snapshots(resolved: Map[DomainRef, String]): "
        "Vector[KernelModuleSnapshot] ="
    )
    helpers = """  private def clockEdgeName(edge: ClockEdge): String = edge match
    case ClockEdge.Rising => "rising"
    case ClockEdge.Falling => "falling"

  private def resetPolicyName(policy: ResetPolicy): String = policy match
    case ResetPolicy.None => "none"
    case ResetPolicy.Sync => "sync"
    case ResetPolicy.Async => "async"
    case _: ResetPolicy.AsyncAssertSyncRelease => "async_assert_sync_release"

  private def resetPolicyAttributes(
      policy: ResetPolicy
  ): Vector[(String, String)] = policy match
    case ResetPolicy.AsyncAssertSyncRelease(stages) =>
      Vector("reset_stages" -> stages.toString)
    case _ => Vector.empty

  private def resetPolarityName(polarity: ResetPolarity): String = polarity match
    case ResetPolarity.ActiveHigh => "active_high"
    case ResetPolarity.ActiveLow => "active_low"

  private def relationAttributes(
      relation: ClockRelation
  ): Vector[(String, String)] = relation match
    case ClockRelation.Same => Vector("clock_relation" -> "alias")
    case ClockRelation.Ratio(multiply, divide, _) =>
      Vector(
        "clock_relation" -> "ratio",
        "clock_multiply" -> multiply.toString,
        "clock_divide" -> divide.toString
      )
    case ClockRelation.Synchronous(phaseKnown) =>
      Vector(
        "clock_relation" -> "synchronous",
        "clock_phase_known" -> phaseKnown.toString
      )
    case ClockRelation.MutuallyExclusive =>
      Vector("clock_relation" -> "mutually_exclusive")
    case ClockRelation.Asynchronous =>
      Vector("clock_relation" -> "asynchronous")
    case ClockRelation.Unknown => Vector("clock_relation" -> "unknown")

"""
    if text.count(helper_anchor) != 1:
        raise SystemExit("domain helper insertion: anchor mismatch")
    text = text.replace(helper_anchor, helpers + helper_anchor, 1)

    domain_pattern = re.escape(
        """      val domains = module.domains.toVector.map: domain =>
        KernelDomainSnapshot(
          domainPath(domain.reference),
          domainName(domain.reference),
          domain.kind.label,
          if domain.kind == KernelDomainKind.Required then resolved.get(domain.reference) else None
        )"""
    )
    domain_replacement = """      val domains = module.domains.toVector.map: domain =>
        val policyAttributes =
          domain.domain.resetPolicy.toVector.flatMap(resetPolicyAttributes)
        val metadata =
          domain.domain.resetPolarity
            .map(value => Vector("reset_polarity" -> resetPolarityName(value)))
            .getOrElse(Vector.empty) ++
            domain.domain.relation
              .map(relationAttributes)
              .getOrElse(Vector.empty) ++
            policyAttributes
        KernelDomainSnapshot(
          domainPath(domain.reference),
          domainName(domain.reference),
          domain.kind.label,
          if domain.kind == KernelDomainKind.Required then resolved.get(domain.reference) else None,
          domain.domain.edge.map(clockEdgeName),
          domain.domain.resetPolicy.map(resetPolicyName),
          metadata.sortBy(_._1)
        )"""
    text = replace_once(
        text,
        domain_pattern,
        domain_replacement,
        "domain snapshot construction",
    )

    instance_pattern = re.escape(
        """        KernelInstanceSnapshot(
          instancePath(module.handle, instance.ordinal),
          modulePath(instance.child),
          instance.lexicalDomain.flatMap(domain => resolved.get(domainRef(domain))),
          bindings.toVector
        )"""
    )
    instance_replacement = """        val parameters = instance.parameterOverrides.toVector.map:
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
            if reference.module != instance.child then
              fail(
                "NODAL-PARAMETER-BINDING-017",
                "instance parameter override targets another Module",
                Some(instancePath(module.handle, instance.ordinal))
              )
            declarationName(reference) -> renderAny(value, module.handle)
        KernelInstanceSnapshot(
          instancePath(module.handle, instance.ordinal),
          modulePath(instance.child),
          instance.lexicalDomain.flatMap(domain => resolved.get(domainRef(domain))),
          bindings.toVector.sortBy(_._1),
          parameters.sortBy(_._1)
        )"""
    text = replace_once(
        text,
        instance_pattern,
        instance_replacement,
        "instance snapshot construction",
    )

    path.write_text(text, encoding="utf-8")

    serializer_path = (
        root / "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala"
    )
    serializer = serializer_path.read_text(encoding="utf-8")
    serializer = replace_once(
        serializer,
        re.escape(
            "attributes: Vector[(String, String)] = Vector.empty,"
        ),
        "attributes: Vector[(String, String)],",
        "strict-warning operation attributes",
    )
    serializer_path.write_text(serializer, encoding="utf-8")


if __name__ == "__main__":
    main()
