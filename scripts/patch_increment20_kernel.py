#!/usr/bin/env python3
"""Add bridge-required literal, domain, and instance metadata for Increment 20."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    candidate_path = root / "core/scala/api/src/nodal/CandidateApi.scala"
    candidate = candidate_path.read_text(encoding="utf-8")
    candidate = replace_once(
        candidate,
        "private[nodal] final class KernelExpr[A <: Data](val operands: Vector[Any]) extends Expr[A]",
        """private[nodal] final case class KernelLiteral(
    kind: String,
    value: String,
    dataType: KernelTypeDescriptor
)

private[nodal] final class KernelExpr[A <: Data](
    val operands: Vector[Any],
    val resultType: Option[KernelTypeDescriptor] = None,
    val literal: Option[KernelLiteral] = None
) extends Expr[A]""",
        "KernelExpr metadata",
    )
    candidate = replace_once(
        candidate,
        """  CandidateRuntime.declare(
    this,
    KernelSignalKind.Parameter,
    attributes = Vector("default" -> default)
  )""",
        """  CandidateRuntime.declare(
    this,
    KernelSignalKind.Parameter,
    dataType = CandidateRuntime.expressionDataType(default),
    attributes = Vector("default" -> default)
  )""",
        "parameter type capture",
    )
    candidate = replace_once(
        candidate,
        """final class ClockDomain private[nodal] (
    val name: String,
    val reset: Expr[Reset],
    kind: KernelDomainKind
):""",
        """final class ClockDomain private[nodal] (
    val name: String,
    val reset: Expr[Reset],
    kind: KernelDomainKind,
    private[nodal] val edge: Option[ClockEdge] = None,
    private[nodal] val resetPolicy: Option[ResetPolicy] = None,
    private[nodal] val resetPolarity: Option[ResetPolarity] = None,
    private[nodal] val relation: Option[ClockRelation] = None
):""",
        "clock domain metadata",
    )
    candidate = replace_once(
        candidate,
        """    new ClockDomain(
      name,
      CandidateRuntime.expr(name, reset),
      KernelDomainKind.External
    )""",
        """    new ClockDomain(
      name,
      CandidateRuntime.expr(name, reset),
      KernelDomainKind.External,
      edge = Some(edge),
      resetPolicy = Some(reset),
      resetPolarity = Some(resetPolarity)
    )""",
        "external domain metadata",
    )
    candidate = replace_once(
        candidate,
        "    new ClockDomain(name, reset, KernelDomainKind.Bound)",
        """    new ClockDomain(
      name,
      reset,
      KernelDomainKind.Bound,
      edge = Some(edge),
      resetPolicy = Some(policy),
      resetPolarity = Some(polarity)
    )""",
        "bound domain metadata",
    )
    candidate = replace_once(
        candidate,
        """    new ClockDomain(name, reset, KernelDomainKind.Generated)""",
        """    new ClockDomain(
      name,
      reset,
      KernelDomainKind.Generated,
      relation = Some(relation)
    )""",
        "generated domain metadata",
    )
    candidate = replace_once(
        candidate,
        """  def integer: Expr[Integer] = CandidateRuntime.expr(value)
  def U(width: Int): Expr[UInt] = CandidateRuntime.expr(value, width)
  def U(width: Expr[Integer]): Expr[UInt] = CandidateRuntime.expr(value, width)""",
        """  def integer: Expr[Integer] = CandidateRuntime.literalInteger(value)
  def U(width: Int): Expr[UInt] = CandidateRuntime.literalUInt(value, width)
  def U(width: Expr[Integer]): Expr[UInt] = CandidateRuntime.expr(value, width)""",
        "integer literals",
    )
    candidate = replace_once(
        candidate,
        """extension (value: Boolean)
  def B: Expr[Bool] = CandidateRuntime.expr(value)

private[nodal] def realLiteral(value: Double, unit: String): Expr[Real] =
  CandidateRuntime.expr(value, unit)""",
        """extension (value: Boolean)
  def B: Expr[Bool] = CandidateRuntime.literalBool(value)

private[nodal] def realLiteral(value: Double, unit: String): Expr[Real] =
  CandidateRuntime.literalReal(value, unit)""",
        "boolean and real literals",
    )
    candidate = replace_once(
        candidate,
        """  def expr[A <: Data](values: Any*): Expr[A] =
    val expression = new KernelExpr[A](values.toVector)
    ConstructionKernel.expression(expression)
    expression""",
        """  private def dataTypeFromDescriptor(
      descriptor: KernelTypeDescriptor
  ): DataType[? <: Data] = descriptor.kind match
    case "Real" => Real
    case "Integer" => Integer
    case "Bool" => Bool
    case _ => new KernelDataType[Data](descriptor)

  def expressionDataType(value: Expr[?]): Option[DataType[? <: Data]] = value match
    case expression: KernelExpr[?] => expression.resultType.map(dataTypeFromDescriptor)
    case _ => None

  private def literal[A <: Data](
      descriptor: KernelTypeDescriptor,
      kind: String,
      value: String,
      operands: Vector[Any]
  ): Expr[A] =
    val expression = new KernelExpr[A](
      operands,
      resultType = Some(descriptor),
      literal = Some(KernelLiteral(kind, value, descriptor))
    )
    ConstructionKernel.expression(expression)
    expression

  def literalInteger(value: Int): Expr[Integer] =
    literal(KernelTypeDescriptor("Integer"), "integer", value.toString, Vector(value))

  def literalUInt(value: Int, width: Int): Expr[UInt] =
    literal(
      KernelTypeDescriptor("UInt", Vector(width)),
      "integer",
      value.toString,
      Vector(value, width)
    )

  def literalBool(value: Boolean): Expr[Bool] =
    literal(KernelTypeDescriptor("Bool"), "boolean", value.toString, Vector(value))

  def literalReal(value: Double, unit: String): Expr[Real] =
    literal(
      KernelTypeDescriptor("Real"),
      "real",
      java.lang.Double.toString(value),
      Vector(value, unit)
    )

  def expr[A <: Data](values: Any*): Expr[A] =
    val expression = new KernelExpr[A](values.toVector)
    ConstructionKernel.expression(expression)
    expression""",
        "literal runtime",
    )
    candidate_path.write_text(candidate, encoding="utf-8")

    kernel_path = root / "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
    kernel = kernel_path.read_text(encoding="utf-8")
    kernel = replace_once(
        kernel,
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
    kernel = replace_once(
        kernel,
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
    kernel = replace_once(
        kernel,
        """    case big: BigInt => big.toString
    case dataType: DataType[?] => renderType(dataType, owner)""",
        """    case big: BigInt => big.toString
    case expression: KernelExpr[?] if expression.literal.nonEmpty =>
      expression.literal.map(_.value).getOrElse("")
    case discipline: NamedDiscipline => discipline.name
    case Electrical => "electrical"
    case mode: DriveMode.Value[?] => mode.name
    case placement: InoutPlacement => placement.toString
    case profile: ResolutionProfile => profile.toString
    case dataType: DataType[?] => renderType(dataType, owner)""",
        "snapshot metadata rendering",
    )
    helper_anchor = "  private def snapshots(resolved: Map[DomainRef, String]): Vector[KernelModuleSnapshot] ="
    helpers = """  private def clockEdgeName(edge: ClockEdge): String = edge match
    case ClockEdge.Rising => "rising"
    case ClockEdge.Falling => "falling"

  private def resetPolicyName(policy: ResetPolicy): String = policy match
    case ResetPolicy.None => "none"
    case ResetPolicy.Sync => "sync"
    case ResetPolicy.Async => "async"
    case _: ResetPolicy.AsyncAssertSyncRelease => "async_assert_sync_release"

  private def resetPolicyAttributes(policy: ResetPolicy): Vector[(String, String)] = policy match
    case ResetPolicy.AsyncAssertSyncRelease(stages) => Vector("reset_stages" -> stages.toString)
    case _ => Vector.empty

  private def resetPolarityName(polarity: ResetPolarity): String = polarity match
    case ResetPolarity.ActiveHigh => "active_high"
    case ResetPolarity.ActiveLow => "active_low"

  private def relationAttributes(relation: ClockRelation): Vector[(String, String)] = relation match
    case ClockRelation.Same => Vector("clock_relation" -> "alias")
    case ClockRelation.Ratio(multiply, divide, _) =>
      Vector("clock_relation" -> "ratio", "clock_multiply" -> multiply.toString, "clock_divide" -> divide.toString)
    case ClockRelation.Synchronous(phaseKnown) =>
      Vector("clock_relation" -> "synchronous", "clock_phase_known" -> phaseKnown.toString)
    case ClockRelation.MutuallyExclusive => Vector("clock_relation" -> "mutually_exclusive")
    case ClockRelation.Asynchronous => Vector("clock_relation" -> "asynchronous")
    case ClockRelation.Unknown => Vector("clock_relation" -> "unknown")

"""
    if helpers not in kernel:
        if kernel.count(helper_anchor) != 1:
            raise RuntimeError("snapshot helper anchor mismatch")
        kernel = kernel.replace(helper_anchor, helpers + helper_anchor, 1)
    kernel = replace_once(
        kernel,
        """      val domains = module.domains.toVector.map: domain =>
        KernelDomainSnapshot(
          domainPath(domain.reference),
          domainName(domain.reference),
          domain.kind.label,
          if domain.kind == KernelDomainKind.Required then resolved.get(domain.reference) else None
        )""",
        """      val domains = module.domains.toVector.map: domain =>
        val policyAttributes =
          domain.domain.resetPolicy.toVector.flatMap(resetPolicyAttributes)
        val metadata =
          domain.domain.resetPolarity
            .map(value => Vector("reset_polarity" -> resetPolarityName(value)))
            .getOrElse(Vector.empty) ++
            domain.domain.relation.map(relationAttributes).getOrElse(Vector.empty) ++
            policyAttributes
        KernelDomainSnapshot(
          domainPath(domain.reference),
          domainName(domain.reference),
          domain.kind.label,
          if domain.kind == KernelDomainKind.Required then resolved.get(domain.reference) else None,
          domain.domain.edge.map(clockEdgeName),
          domain.domain.resetPolicy.map(resetPolicyName),
          metadata.sortBy(_._1)
        )""",
        "domain snapshot construction",
    )
    kernel = replace_once(
        kernel,
        """        KernelInstanceSnapshot(
          instancePath(module.handle, instance.ordinal),
          modulePath(instance.child),
          instance.lexicalDomain.flatMap(domain => resolved.get(domainRef(domain))),
          bindings.toVector
        )""",
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
        )""",
        "instance snapshot construction",
    )
    kernel_path.write_text(kernel, encoding="utf-8")


if __name__ == "__main__":
    main()
