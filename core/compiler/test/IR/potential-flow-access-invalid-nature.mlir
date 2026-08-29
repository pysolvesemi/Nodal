module {
  "nodal.nature"() <{abstol = 1.000000e-06 : f64, access = "Across", dimension = "voltage", metadata = {}, sym_name = "VoltageNature", units = "V"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", metadata = {}, potential = @VoltageNature, sym_name = "potentialOnly"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "BadNature"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"potentialOnly">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %bad = "nodal.port_flow_access"(%p) <{function = "flow", kind = "flow", metadata = {}, source_path = "BadNature.bad"}> : (!nodal.terminal<"potentialOnly">) -> !nodal.quantity<"real", "current">
    }) : () -> ()
  }) : () -> ()
}
