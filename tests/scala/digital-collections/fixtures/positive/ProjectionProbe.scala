package nodal.callback.fixture
import nodal.*
object ProjectionProbe:
  def explicit(input: Expr[Vec[Vec[UInt]]]) =
    input.map(row => row.slice(0, 2, axis = 1).flatten.at(1))
  def default(input: Expr[Vec[Vec[UInt]]]) =
    input.map(row => row.slice(0, 2).flatten.at(1))
  def reshaped(input: Expr[Vec[Vec[UInt]]]) = input.map(row => row.reshape(3, 2))
  def namedCallback(input: Expr[Vec[UInt]]): Expr[Vec[UInt]] =
    input.map(function = value => value + 1.U(8))
