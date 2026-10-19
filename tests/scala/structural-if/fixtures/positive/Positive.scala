import nodal.*
final class StructuralPositive extends Module:
  val MODE = param(1.integer, 0 to 3)
  val LANES = param(3.integer, 1 to 4)
  val laneData = in(Bits(8))
  val output = out(Bits(8))
  val hostBoolean = true
  if hostBoolean then println("ordinary host branch remains ordinary")
  if MODE == 0 then output := ~laneData else output := laneData
  val selected =
    if (MODE != 0) && (MODE < LANES) || !(MODE >= 3) then ~laneData else laneData
  val ordered =
    if (MODE <= LANES) && ((MODE + 1.integer) > 0) then selected else laneData
  val legacyOperands = if MODE == 0.integer then ~laneData else laneData
  hdlRange(0, LANES): i =>
    val chosen = if i == 0 then ~laneData else laneData
    val _ = chosen
  final def helper(index: Expr[Integer], input: Expr[Bits]): Expr[Bits] =
    if index == 0 then
      val inverted = ~input
      inverted
    else input
  val joined: Expr[Bits] = if MODE == 0 then
    val x = helper(MODE, laneData)
    x
  else if MODE != 1 then laneData else ~laneData
  val ascribed = (if MODE == 0 then laneData else output): Expr[Bits]
