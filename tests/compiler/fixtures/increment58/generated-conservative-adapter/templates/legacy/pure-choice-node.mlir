module attributes {nodal.target.profile = "analog"} {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", dimension = "voltage", metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "std.Voltage"}, sym_name = "Voltage", units = "V"}> : () -> () loc(unknown)
  
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", dimension = "current", metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "std.Current"}, sym_name = "Current", units = "A"}> : () -> () loc(unknown)
  
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, kind = "conservative", semantic_path = "std.electrical"}, potential = @Voltage, sym_name = "electrical"}> : () -> () loc(unknown)
  
  "nodal.module"() <{metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, class_name = "Increment58ConservativeLocal", semantic_path = "Increment58ConservativeLocal", source_end_column = 81 : i64, source_end_line = 21 : i64}, sym_name = "Increment58ConservativeLocal"}> ({
    "nodal.domain"() <{edge = "rising", metadata = {binding = "", bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, clock_source = "Increment58ConservativeLocal.core_clock", frequency_hz = "1.0E8", kind = "external", reset_polarity = "active_high", reset_source = "Increment58ConservativeLocal.external_increment58_conservative_interface_source_l28_76812749", semantic_path = "Increment58ConservativeLocal.core", source_end_column = 12 : i64, source_end_line = 28 : i64}, reset_policy = "none", sym_name = "core"}> : () -> () loc("core/scala/testkit/test/src/nodal_external/Increment58ConservativeInterfaceSource.scala":28:5)
    "nodal.port"() <{direction = "input", domain = @core, metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, resolved_domain = "Increment58ConservativeLocal.core", semantic_path = "Increment58ConservativeLocal.core_clock", source_end_column = 12 : i64, source_end_line = 28 : i64}, sym_name = "core_clock", type = !nodal.capability<"clock">}> : () -> () loc("core/scala/testkit/test/src/nodal_external/Increment58ConservativeInterfaceSource.scala":28:5)
    "nodal.interface"() <{metadata = {interface_abi_version = 1 : i64, semantic_identity = "Increment58ConservativePair"}, sym_name = "Increment58ConservativePair"}> ({
      "nodal.interface_role"() <{kind = "explicit", metadata = {access = [{access = "connect", leaf_role = "connected", nested_interfaces = [], nested_roles = [], path = "p"}, {access = "connect", leaf_role = "connected", nested_interfaces = [], nested_roles = [], path = "n"}], interface_abi_version = 1 : i64}, sym_name = "connected"}> : () -> ()  loc("core/scala/testkit/test/src/nodal_external/Increment58ConservativeInterfaceSource.scala":21:1)
      "nodal.interface_role"() <{kind = "explicit", metadata = {access = [{access = "contribute", leaf_role = "contributed", nested_interfaces = [], nested_roles = [], path = "p"}, {access = "contribute", leaf_role = "contributed", nested_interfaces = [], nested_roles = [], path = "n"}], interface_abi_version = 1 : i64}, sym_name = "contributed"}> : () -> ()  loc("core/scala/testkit/test/src/nodal_external/Increment58ConservativeInterfaceSource.scala":21:1)
      "nodal.interface_member"() <{metadata = {analog = {discipline = "electrical", flow_nature = "current", potential_nature = "voltage"}, analog_refs = {discipline = @electrical, flow = @Current, potential = @Voltage}, interface_abi_version = 1 : i64}, protocol = "conservative", roles = ["connected", "contributed"], sym_name = "p", type = !nodal.terminal<"electrical">}> : () -> ()  loc("core/scala/testkit/test/src/nodal_external/Increment58ConservativeInterfaceSource.scala":21:1)
      "nodal.interface_member"() <{metadata = {analog = {discipline = "electrical", flow_nature = "current", potential_nature = "voltage"}, analog_refs = {discipline = @electrical, flow = @Current, potential = @Voltage}, interface_abi_version = 1 : i64}, protocol = "conservative", roles = ["connected", "contributed"], sym_name = "n", type = !nodal.terminal<"electrical">}> : () -> ()  loc("core/scala/testkit/test/src/nodal_external/Increment58ConservativeInterfaceSource.scala":21:1)
    }) : () -> ()  loc("core/scala/testkit/test/src/nodal_external/Increment58ConservativeInterfaceSource.scala":21:1)
    %digital_value_0 = "nodal.port_value"() <{metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "Increment58ConservativeLocal.core_clock", source_end_column = 12 : i64, source_end_line = 28 : i64}, port = @core_clock}> : () -> !nodal.capability<"clock"> loc("core/scala/testkit/test/src/nodal_external/Increment58ConservativeInterfaceSource.scala":28:5)
    "nodal.local_domain_binding"(%digital_value_0) <{domain = @"core", reset_polarity = "active_high", metadata = {bridge_schema = "nodal.scala-to-mlir", bridge_version = 1 : i64, semantic_path = "Increment58ConservativeLocal.core", source_end_column = 12 : i64, source_end_line = 28 : i64}}> : (!nodal.capability<"clock">) -> () loc("core/scala/testkit/test/src/nodal_external/Increment58ConservativeInterfaceSource.scala":28:5)
%lower = "nodal.const_literal"() <{value = 0 : i64, spelling = "0", metadata = {}}> : () -> i64
%upper = "nodal.const_literal"() <{value = 3 : i64, spelling = "3", metadata = {}}> : () -> i64
%step = "nodal.const_literal"() <{value = 1 : i64, spelling = "1", metadata = {}}> : () -> i64
"nodal.parameter"() <{sym_name = "MODE", type = i64, default_value = 0 : i64, parameter_kind = "integer", classification = "structural", variability = "symbolic", metadata = {semantic_path = "Increment58ConservativeLocal.MODE"}}> : () -> ()
"nodal.parameter_envelope"() <{parameter = @MODE, effects = ["topology"], policy = "static_generate", metadata = {}}> : () -> ()
%mode = "nodal.const_parameter_ref"() <{parameter = @MODE, metadata = {semantic_path = "Increment58ConservativeLocal.mode"}}> : () -> i64
%pred = "nodal.static_compare"(%mode, %lower) <{comparison = "lt", metadata = {semantic_path = "Increment58ConservativeLocal.pred"}}> : (i64, i64) -> !nodal.static_predicate
%joined = "nodal.structural_if"(%pred) <{contract_version = "structural-if-v1", region_id = "Increment58ConservativeLocal.arm", operandSegmentSizes = array<i32: 1, 0, 0>, metadata = {semantic_path = "Increment58ConservativeLocal.arm"}}> ({
%armnode = "nodal.node"() <{name = "armnode", metadata = {}}> : () -> !nodal.terminal<"electrical">
%lit = "nodal.const_literal"() <{value = 0 : i64, spelling = "0", metadata = {}}> : () -> !nodal.uint<8>
"nodal.structural_yield"(%lit) <{metadata = {}}> : (!nodal.uint<8>) -> ()
}, {
%lit = "nodal.const_literal"() <{value = 0 : i64, spelling = "0", metadata = {}}> : () -> !nodal.uint<8>
"nodal.structural_yield"(%lit) <{metadata = {}}> : (!nodal.uint<8>) -> ()
}) : (!nodal.static_predicate) -> !nodal.uint<8>

}) : () -> ()
}
