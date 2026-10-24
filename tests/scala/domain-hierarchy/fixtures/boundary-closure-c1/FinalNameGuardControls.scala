package nodal.internal.testkit
import nodal.*
import scala.collection.mutable
object FinalNameGuardControls:
  import EpochControls.{field, invoke, session}
  private var foreign: Expr[?] = null
  private var predecessor = false
  private var corrupt = true
  private var observed = false
  private var rejectedOwner: AnyRef = null
  def exercise(root: Module): Unit =
    val owner = session()
    invoke(owner, "beginIncrementalBoundaryClosure", root, Int.box(65536))
    val closure = field(owner, "boundaryClosureOwner").asInstanceOf[Option[AnyRef]].get
    assert(field(closure, "poisoned") == java.lang.Boolean.FALSE)
    if corrupt then
      val query =
        invoke(owner, "currentBoundaryEpochQuery").asInstanceOf[StructuralParameterBinding.Query]
      val epoch = field(closure, "epoch").asInstanceOf[java.lang.Long].longValue()
      val regions = field(owner, "collectionRegions").asInstanceOf[mutable.Map[Any, AnyRef]]
      assert(regions.size == 1)
      val captures =
        field(regions.values.head, "captures").asInstanceOf[mutable.Map[Any, (AnyRef, AnyRef)]]
      val (key, (_, argument)) = captures.head
      // Deliberate private registry corruption, not an admissible public capture.
      captures.update(key, foreign -> argument)
      val names = field(closure, "frozenNames").asInstanceOf[Option[AnyRef]].get
      val result = scala.util.Try(invoke(closure, "finalNames", names))
      assert(result.isFailure)
      val failure = result.failed.get
      assert(failure.getMessage.contains("NODAL-COLLECTION-055-005"), failure.toString)
      assert(failure.getStackTrace.exists(_.getMethodName.contains("finalNames")))
      assert(failure.getStackTrace.exists(_.getMethodName.contains("captureRegionSignature")))
      assert(field(owner, "constructorFailure").asInstanceOf[Option[?]].nonEmpty)
      val poisoned = field(closure, "poisoned") == java.lang.Boolean.TRUE
      assert(poisoned == !predecessor)
      val nextEpoch = field(closure, "epoch").asInstanceOf[java.lang.Long].longValue()
      assert(if predecessor then nextEpoch == epoch else nextEpoch > epoch)
      val use = scala.util.Try(query.literal(1L))
      assert(use.isSuccess == predecessor)
      if !predecessor then
        assert(use.failed.get.getMessage.contains("stale boundary snapshot epoch"))
      rejectedOwner = closure
      observed = true
    else
      assert(closure ne rejectedOwner)
      assert(field(owner, "constructorFailure") == None)
  def main(args: Array[String]): Unit =
    assert(args.isEmpty || args.toVector == Vector("predecessor"))
    predecessor = args.nonEmpty
    val _ = ConstructionKernel.inspect:
      val root = new FinalNameForeignRoot
      foreign = root.signal
      root
    val result = scala.util.Try(ConstructionKernel.inspect(new FinalNameGuardRoot))
    assert(
      observed && result.isFailure &&
        result.failed.get.getMessage.contains("invalidated this transaction"),
      result.toString
    )
    println(if predecessor then
      "OBSERVED predecessor owner/ticket survives caught canonical validation failure; publication refused"
    else
      "PASS post-name canonical validation failure retires owner/ticket and prevents publication")
    corrupt = false
    val snapshot = ConstructionKernel.inspect(new FinalNameGuardRoot)
    assert(snapshot.modules.size == 1)
    println("PASS fresh incremental Session recovery")
