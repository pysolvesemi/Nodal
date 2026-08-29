module {
  "nodal.nature"() <{abstol = 1.000000e-06 : f64, access = "Across", dimension = "voltage", metadata = {}, sym_name = "VoltageNature", units = "V"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", metadata = {}, potential = @VoltageNature, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "BadProbeProvenance"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {identity = "p_n"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.probe"(%branch) <{constraint_intent = "zero-flow", form = "branch", kind = "potential", metadata = {compiler_owned = false, generated_by = "authored"}, provenance = ["BadProbeProvenance.authored"]}> : (!nodal.branch<"electrical">) -> ()
  }) : () -> ()
}
