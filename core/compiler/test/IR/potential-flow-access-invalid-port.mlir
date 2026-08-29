module {
  "nodal.nature"() <{abstol = 1.000000e-09 : f64, access = "Through", dimension = "current", metadata = {}, sym_name = "CurrentNature", units = "A"}> : () -> ()
  "nodal.nature"() <{abstol = 1.000000e-06 : f64, access = "Across", dimension = "voltage", metadata = {}, sym_name = "VoltageNature", units = "V"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @CurrentNature, metadata = {}, potential = @VoltageNature, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "BadPort"}> ({
  ^bb0:
    %internal = "nodal.node"() <{metadata = {}, name = "internal"}> : () -> !nodal.terminal<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %bad = "nodal.port_flow_access"(%internal) <{function = "Through", kind = "flow", metadata = {}, source_path = "BadPort.bad"}> : (!nodal.terminal<"electrical">) -> !nodal.quantity<"real", "current">
    }) : () -> ()
  }) : () -> ()
}
