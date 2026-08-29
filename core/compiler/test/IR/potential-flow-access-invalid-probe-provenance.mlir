module {
  "nodal.nature"() <{abstol = 1.000000e-06 : f64, access = "Across", dimension = "voltage", metadata = {}, sym_name = "VoltageNature", units = "V"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", metadata = {}, potential = @VoltageNature, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "BadProbeProvenance"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {identity = "p_n"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %potential = "nodal.access"(%branch) <{function = "Across", kind = "potential", metadata = {source_path = "BadProbeProvenance.potential"}}> : (!nodal.branch<"electrical">) -> !nodal.quantity<"real", "voltage">
    }) : () -> ()
    "nodal.probe"(%branch) <{constraint_intent = "zero-flow", form = "branch", kind = "potential", metadata = {compiler_owned = true, generated_by = "increment31-potential-flow-access"}, provenance = ["BadProbeProvenance.forged"]}> : (!nodal.branch<"electrical">) -> ()
  }) : () -> ()
}
