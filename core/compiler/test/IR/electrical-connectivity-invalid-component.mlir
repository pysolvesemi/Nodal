module {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "InvalidPartial"}> ({
  ^bb0:
    "nodal.component_contract"() <{connectivity_ownership = "local", kind = "partial", metadata = {}, source_path = "InvalidPartial"}> : () -> ()
    %p = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {allow_floating = true}, name = "p", source_path = "InvalidPartial.p"}> : () -> !nodal.terminal<"electrical">
  }) : () -> ()
}
