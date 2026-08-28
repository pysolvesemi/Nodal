module {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-3 : f64, access = "Temp", metadata = {}, sym_name = "Temperature", units = "K"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-9 : f64, access = "Pwr", metadata = {}, sym_name = "Power", units = "W"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Power, metadata = {}, potential = @Temperature, sym_name = "thermal"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "InvalidConnection"}> ({
  ^bb0:
    "nodal.component_contract"() <{connectivity_ownership = "local", kind = "concrete", metadata = {}, source_path = "InvalidConnection"}> : () -> ()
    %electrical = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {}, name = "electrical", source_path = "InvalidConnection.electrical"}> : () -> !nodal.terminal<"electrical">
    %thermal = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {}, name = "thermal", source_path = "InvalidConnection.thermal"}> : () -> !nodal.terminal<"thermal">
    "nodal.connect"(%electrical, %thermal) <{connection_id = "invalid", metadata = {}, source_path = "InvalidConnection.connect"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"thermal">) -> ()
  }) : () -> ()
}
