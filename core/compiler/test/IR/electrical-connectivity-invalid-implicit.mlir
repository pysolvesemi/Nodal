module {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "DuplicateImplicit"}> ({
  ^bb0:
    "nodal.component_contract"() <{connectivity_ownership = "local", kind = "concrete", metadata = {}, source_path = "DuplicateImplicit"}> : () -> ()
    %p = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {}, name = "p", source_path = "DuplicateImplicit.p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {}, name = "n", source_path = "DuplicateImplicit.n"}> : () -> !nodal.terminal<"electrical">
    %a = "nodal.branch"(%p, %n) <{declaration_kind = "implicit", metadata = {}, source_path = "DuplicateImplicit.branch.a"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    %b = "nodal.branch"(%n, %p) <{declaration_kind = "implicit", metadata = {}, source_path = "DuplicateImplicit.branch.b"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
  }) : () -> ()
}
