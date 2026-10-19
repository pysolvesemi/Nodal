package nodal.behavioral.fixture
import nodal.*
// Expected: NODAL-SEQUENTIAL-056-FORGED
object KernelForged:
  var effects = 0
  def forge(): Unit =
    val _ = ConstructionKernel.behavioralComparison(0.integer, 0.integer, "eq")
    ConstructionKernel.behavioralBranch("when", Some(false.B), () => { effects += 1 })
