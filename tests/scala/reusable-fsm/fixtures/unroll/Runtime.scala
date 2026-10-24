package nodal
object RuntimeProbe:
  enum Mode derives HwEnum:
    case Work, Done
  enum Other derives HwEnum:
    case Work, Done
  def base: FsmDef[Mode] =
    val owner = FsmDefinition[Mode]("base")
    FsmDef(owner, Mode.Work)(FsmFragment("states")(
      FsmState(Mode.Work).after(1)(Mode.Done),
      FsmState(Mode.Done).terminal()
    ))
  def rejected(part: String)(body: => Any): Unit =
    try
      val _ = body
      throw new java.lang.AssertionError("missing rejection " + part)
    catch
      case error: ConstructionException => assert(error.getMessage.contains(part), error.getMessage)
  def main(args: Array[String]): Unit =
    val _ = args
    val leaf = base
    var calls = 0
    val identity = DigitalCollectionCallback.checkedFsmGraph2[Mode]((_, previous) => {
      calls += 1; previous
    })
    assert(FsmDefinitionRuntime.unroll(0, leaf, identity) eq leaf)
    assert(calls == 0)
    assert(FsmDefinitionRuntime.unroll(127, leaf, identity) eq leaf)
    assert(calls == 127)
    rejected("0 through 127")(FsmDefinitionRuntime.unroll(-1, leaf, identity))
    rejected("0 through 127")(FsmDefinitionRuntime.unroll(128, leaf, identity))
    rejected("typed effect validation")(FsmDefinitionRuntime.unroll(1, leaf, (_, prior) => prior))
    rejected("exact enum evidence")(FsmDefinitionRuntime.unroll(
      1,
      leaf,
      DigitalCollectionCallback.checkedFsmGraph2((_, _) => null)
    ))
    val otherOwner = FsmDefinition[Other]("other")
    val other = FsmDef(otherOwner, Other.Work)(FsmFragment("states")(
      FsmState(Other.Work).after(1)(Other.Done),
      FsmState(Other.Done).terminal()
    ))
    rejected("exact enum evidence")(FsmDefinitionRuntime.unroll(
      1,
      leaf,
      DigitalCollectionCallback.checkedFsmGraph2((_, _) => other.asInstanceOf[FsmDef[Mode]])
    ))
    calls = 0
    val grow = DigitalCollectionCallback.checkedFsmGraph2[Mode]((_, previous) => {
      calls += 1
      val owner = FsmDefinition[Mode]("level")
      val first = FsmChild(owner, "first", previous)
      val second = FsmChild(owner, "second", previous)
      FsmDef(owner, Mode.Work)(FsmFragment("states")(
        FsmState(Mode.Work).children(first, second).onAllComplete()(Mode.Done),
        FsmState(Mode.Done).terminal()
      ))
    })
    rejected("composition exceeds 4096")(FsmDefinitionRuntime.unroll(127, leaf, grow))
    assert(calls == 12, calls)
    println(
      "FINITE_UNROLL_RUNTIME_PASS levels=0/127 invalid=-1/128 null/foreign/unchecked bounded-before-step13"
    )
