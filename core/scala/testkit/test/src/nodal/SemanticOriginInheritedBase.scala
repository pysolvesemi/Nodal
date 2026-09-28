package nodal.internal.testkit

import nodal.*

final class SemanticOriginInheritedLeaf extends Module

class SemanticOriginInheritedBase extends Module:
  val inheritedChild: Instance[SemanticOriginInheritedLeaf] =
    instance(new SemanticOriginInheritedLeaf)
