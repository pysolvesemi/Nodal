package contracts.digitalenum

import external.coresemantics.LibraryMode
import nodal.*

/** F54 source witness: LibraryMode and its derived instance are compiled in another module. */
object DigitalEnumSeparateCompilation:
  def genericLiteral[A: HwEnum](value: A): Expr[EnumValue[A]] =
    enumLiteral(EnumType[A](), value)

  val dataType: DataType[EnumValue[LibraryMode]] = EnumType[LibraryMode]()
  val initial: Expr[EnumValue[LibraryMode]] = genericLiteral(LibraryMode.Idle)
  val matching: Expr[Bool] = initial.isOneOf(LibraryMode.Idle, LibraryMode.Active)
  val selected: Expr[UInt] = selectEnum(initial, 0.U(8))(
    LibraryMode.Idle -> 1.U(8),
    LibraryMode.Active -> 2.U(8)
  )
