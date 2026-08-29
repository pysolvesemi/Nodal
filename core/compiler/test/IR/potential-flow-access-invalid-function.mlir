module {
  "nodal.nature"() <{abstol = 1.000000e-06 : f64, access = "Across", dimension = "voltage", metadata = {}, sym_name = "VoltageNature", units = "V"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", metadata = {}, potential = @VoltageNature, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "BadFunction"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %bad = "nodal.terminal_access"(%p) <{function = "Wrong", kind = "potential", metadata = {}, source_path = "BadFunction.bad"}> : (!nodal.terminal<"electrical">) -> !nodal.quantity<"real", "voltage">
    }) : () -> ()
  }) : () -> ()
}
