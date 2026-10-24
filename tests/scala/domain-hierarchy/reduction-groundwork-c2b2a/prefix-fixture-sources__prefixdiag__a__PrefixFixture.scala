package prefixdiag.a

/** Plain diagnostic source text, not a Nodal hardware witness. */
object Outer:
  object Inner:
    final class Leaf:
      val constructorA = 1
      def body(): Int =
        val lexicalA = 2
        lexicalA
