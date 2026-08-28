module {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", metadata = {}, potential = @Voltage, sym_name = "voltage_signal"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "InvalidSignalFlow"}> ({
  ^bb0:
    "nodal.component_contract"() <{connectivity_ownership = "local", kind = "concrete", metadata = {}, source_path = "InvalidSignalFlow"}> : () -> ()
    %p = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {}, name = "p", source_path = "InvalidSignalFlow.p"}> : () -> !nodal.terminal<"voltage_signal">
  }) : () -> ()
}
