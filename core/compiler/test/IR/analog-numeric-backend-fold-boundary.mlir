module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {}, sym_name = "FoldBoundary"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-input"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {identity = "p_n"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %probe = "nodal.access"(%branch) <{kind = "potential", metadata = {}}> {nodal.folded = true, nodal.folded_dimension = "1", nodal.folded_kind = "real", nodal.folded_provenance = "increment30", nodal.folded_value = 123.0 : f64} : (!nodal.branch<"electrical">) -> !nodal.quantity<"real", "1">
      "nodal.contribute"(%branch, %probe) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">, !nodal.quantity<"real", "1">) -> ()
    }) : () -> ()
  }) : () -> ()
}
