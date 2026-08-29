module {
  "nodal.nature"() <{abstol = 1.000000e-06 : f64, access = "Across", dimension = "voltage", metadata = {}, sym_name = "VoltageNature", units = "V"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", metadata = {}, potential = @VoltageNature, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "BadReference"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %bad = "nodal.terminal_access"(%p) <{function = "Across", kind = "potential", metadata = {}, reference_identity = "global::wrong", source_path = "BadReference.bad"}> : (!nodal.terminal<"electrical">) -> !nodal.quantity<"real", "voltage">
    }) : () -> ()
  }) : () -> ()
}
