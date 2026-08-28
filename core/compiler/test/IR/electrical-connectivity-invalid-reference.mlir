module {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "InvalidReference"}> ({
  ^bb0:
    "nodal.component_contract"() <{connectivity_ownership = "local", kind = "concrete", metadata = {}, source_path = "InvalidReference"}> : () -> ()
    %g1 = "nodal.node"() <{metadata = {}, name = "g1", source_path = "InvalidReference.g1"}> : () -> !nodal.terminal<"electrical">
    %g2 = "nodal.node"() <{metadata = {}, name = "g2", source_path = "InvalidReference.g2"}> : () -> !nodal.terminal<"electrical">
    "nodal.connect"(%g1, %g2) <{connection_id = "ground", metadata = {}, source_path = "InvalidReference.connect"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
    "nodal.reference"(%g1) <{metadata = {}, scope = "global", source_path = "InvalidReference.reference.global"}> : (!nodal.terminal<"electrical">) -> ()
    "nodal.reference"(%g2) <{metadata = {}, scope = "module", source_path = "InvalidReference.reference.module"}> : (!nodal.terminal<"electrical">) -> ()
  }) : () -> ()
}
