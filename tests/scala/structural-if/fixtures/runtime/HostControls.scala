import nodal.*
object HostControls:
  def main(arguments: Array[String]): Unit =
    val _ = arguments
    var left = 0
    var right = 0
    val selected = if true then { left += 1; 17 }
    else { right += 1; 19 }
    assert(selected == 17 && left == 1 && right == 0)
    val first = 0.integer
    val second = 0.integer
    assert(first == first)
    assert(!(first == second))
    println("STRUCTURAL_HOST_CONTROL_PASS")
