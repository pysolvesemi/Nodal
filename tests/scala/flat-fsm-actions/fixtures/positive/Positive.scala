import nodal.*
enum Mode derives HwEnum:
  case Idle, Run
object Positive:
  def build(
      s: StateBuilder[Mode],
      selector: Expr[EnumValue[Mode]],
      condition: Expr[Bool],
      target: Register[UInt],
      input: Expr[UInt]
  ): Unit =
    s.entry { target := input }
    s.active { target := input }
    s.exit { target := input }
    s.on(condition)(Mode.Run)
    s.onWithAction(condition)(Mode.Run) { target := input }
    switchEnum(selector)(invalid = { target := input }): cases =>
      cases.is(Mode.Idle) { target := input }
      cases.is(Mode.Run) { () }

object SameName:
  def entry(body: => Unit): Unit = body
  def on(condition: Boolean)(body: => Unit): Unit = if condition then body
  def check(): Unit =
    entry { println("ordinary unrelated entry") }
    on(true) { println("ordinary unrelated on") }

object NestedActions:
  def build(
      state: StateBuilder[Mode],
      selector: Expr[EnumValue[Mode]],
      condition: Expr[Bool],
      target: Register[UInt],
      input: Expr[UInt]
  ): Unit =
    def update(): Unit = target := input
    state.active:
      when(condition):
        switchEnum(selector)(invalid = { update() }): cases =>
          cases.is(Mode.Idle) { update() }
          cases.is(Mode.Run) { () }

object EnumRegisterActions:
  def build(
      state: StateBuilder[Mode],
      target: Register[EnumValue[Mode]],
      enumType: DataType[EnumValue[Mode]]
  ): Unit =
    state.entry { target := enumLiteral(enumType, Mode.Run) }
