package nodal
object GuardedJarProbe:
  def main(args: Array[String]): Unit =
    val _ = args
    val snapshot = ConstructionKernel.inspect(new nodal_external.guardedjar.Consumer)
    val family = snapshot.generatedRegions.head
    assert(family.parent.isEmpty && family.lexicalParent.nonEmpty)
    assert(snapshot.digitalExpressions.flatMap(_.generatedMember).head.access == "read_only")
    println("GUARDED_SEPARATE_JAR_PASS")
