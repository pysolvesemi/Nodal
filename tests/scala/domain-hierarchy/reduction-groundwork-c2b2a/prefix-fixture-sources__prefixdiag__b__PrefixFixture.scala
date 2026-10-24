package prefixdiag.b

/** Same basename and nested type, distinct full owner and source binding. */
object Outer:
  object Inner:
    final class Leaf:
      val constructorB = 3
      def body(): Int =
        val lexicalB = 4
        lexicalB
