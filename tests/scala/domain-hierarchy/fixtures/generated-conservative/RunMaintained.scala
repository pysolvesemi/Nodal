package nodal.internal.testkit
object RunGeneratedConservativeMaintained:
  def main(args: Array[String]): Unit =
    require(args.isEmpty)
    val results = utest.TestRunner.runAndPrint(
      Increment58GeneratedConservativeInterfaceTests.tests,
      "generated-conservative"
    )
    assert(results.leaves.forall(_.value.isSuccess))
    println(s"CASES ${results.leaves.size} FAILURES 0")
