package nodal_external

import nodal.*

/** Existing-API ordinary whole-array fixtures; no generated or per-lane construction. */
final class SymbolicConservativeArrayLeaf(defaultCount: Int = 65536, maximum: Int = 131072)
    extends Module:
  import ConservativeArrayFields.*
  val required = ClockDomain.required("required")
  val n = param(defaultCount.integer, range = 1 to maximum)
  val pins = interfaceArray(outer, connected, n, "pins", required)

final class SymbolicConservativeArrayTop(mode: String = "late", defaultCount: Int = 65536)
    extends Module:
  import ConservativeArrayFields.*
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val p = param(defaultCount.integer, range = 1 to 131072)
  // Equal default and domain do not confer the identity of p.
  val other = param(defaultCount.integer, range = 1 to 131072)
  val pins = interfaceArray(outer, connected, p, "pins", core)
  val childDefinition = new SymbolicConservativeArrayLeaf(
    if mode == "formal-range" then 32768 else defaultCount,
    if mode == "formal-range" then 65535
    else if mode == "unresolved-expression" then 131073 else 131072
  )
  val child = instance(childDefinition).domain(core)
  if mode == "early" then child.param(_.n, p)
  val projected = child(_.pins)
  require(projected eq child(_.pins))
  mode match
    case "early" | "missing-binding" => ()
    case "add-zero" => child.param(_.n, p + 0.integer)
    case "multiply-one" => child.param(_.n, p * 1.integer)
    case "different-identity" => child.param(_.n, other)
    case "foreign-operand" => child.param(_.n, childDefinition.n)
    case "foreign-neutral" => child.param(_.n, childDefinition.n + 0.integer)
    case "runtime-operand" | "runtime-annihilated" =>
      val runtime = in(Integer)
      child.param(_.n, if mode == "runtime-annihilated" then runtime * 0.integer else runtime)
    case "unresolved-expression" => child.param(_.n, p + 1.integer)
    case _ => child.param(_.n, p)
  if mode == "reverse" then projected.forwardExact(pins)
  else pins.forwardExact(projected)
  if mode == "duplicate" then pins.forwardExact(projected)
  if mode == "two-instances" then
    val secondPins = interfaceArray(outer, connected, p, "secondPins", core)
    val second = instance(new SymbolicConservativeArrayLeaf(defaultCount)).domain(core)
    val secondProjected = second(_.pins)
    second.param(_.n, p)
    secondPins.forwardExact(secondProjected)

/** A positive default cannot make a nonpositive legal extent domain safe. */
final class SymbolicConservativeArrayNonpositive extends Module:
  import ConservativeArrayFields.*
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val p = param(65536.integer, range = 0 to 131072)
  val pins = interfaceArray(outer, connected, p, "pins", core)
  val child = instance(new SymbolicConservativeArrayLeaf).domain(core).param(_.n, p)
  pins.forwardExact(child(_.pins))

/** Retains the unsupported unbounded/direct-expression endpoint admission boundary. */
final class SymbolicConservativeArrayUnsupported(mode: String) extends Module:
  import ConservativeArrayFields.*
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val p = if mode == "unbounded" then param(65536.integer)
  else param(65536.integer, range = 1 to 131072)
  val pins = interfaceArray(outer, connected,
    if mode == "expression-endpoint" then p + 0.integer else p, "pins", core)
  val child = instance(new SymbolicConservativeArrayLeaf).domain(core).param(_.n, p)
  pins.forwardExact(child(_.pins))

/** Matching literal defaults/actuals cannot erase the child's symbolic formal declaration. */
final class SymbolicConservativeArrayLiteralParent extends Module:
  import ConservativeArrayFields.*
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val pins = interfaceArray(outer, connected, 3, "pins", core)
  val child = instance(new SymbolicConservativeArrayLeaf(3)).domain(core).param(_.n, 3.integer)
  pins.forwardExact(child(_.pins))
