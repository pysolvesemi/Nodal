module {
  "nodal.nature"() <{abstol = 1.000000e-06 : f64, access = "Across", dimension = "voltage", metadata = {}, sym_name = "VoltageNature", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.000000e-09 : f64, access = "Through", dimension = "current", metadata = {}, sym_name = "CurrentNature", units = "A"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @CurrentNature, metadata = {}, potential = @VoltageNature, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "BadProbeKind"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {identity = "p_n"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %potential = "nodal.access"(%branch) <{function = "Across", kind = "potential", metadata = {source_path = "BadProbeKind.potential"}}> : (!nodal.branch<"electrical">) -> !nodal.quantity<"real", "voltage">
      %flow = "nodal.access"(%branch) <{function = "Through", kind = "flow", metadata = {source_path = "BadProbeKind.flow"}}> : (!nodal.branch<"electrical">) -> !nodal.quantity<"real", "current">
    }) : () -> ()
  }) : () -> ()
}
