module attributes {nodal.target.profile = "analog", nodal.root.module = @Top, nodal.root.parameter_bindings = {gain = 4.0 : f64}} {

"nodal.nature"() <{sym_name = "Voltage", access = "V", units = "V", dimension = "voltage", abstol = 1.0e-6 : f64, metadata = {}}> : () -> ()
"nodal.nature"() <{sym_name = "Current", access = "I", units = "A", dimension = "current", abstol = 1.0e-12 : f64, metadata = {}}> : () -> ()
"nodal.discipline"() <{sym_name = "electrical", domain = "continuous", potential = @Voltage, flow = @Current, metadata = {}}> : () -> ()
"nodal.module"() <{sym_name = "Top", metadata = {semantic_path = "Top"}}> ({

%p = "nodal.terminal"() <{name = "p", direction = "inout", metadata = {allow_floating = true, declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
%n = "nodal.terminal"() <{name = "n", direction = "inout", metadata = {allow_floating = true, declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
"nodal.parameter"() <{sym_name = "gain", type = f64, default_value = 4.0 : f64, variability = "symbolic", classification = "ordinary", metadata = {}}> : () -> ()
"nodal.instance"() <{sym_name = "child0", module = @Branch, parameter_bindings = {}, domain_bindings = {}, metadata = {child_path = "Top.child0", semantic_path = "Top.child0_instance"}}> : () -> ()
%child0_actual = "nodal.const_parameter_ref"() <{parameter = @gain, metadata = {}}> : () -> f64
%extra = "nodal.const_literal"() <{metadata = {}, spelling = "1.0", value = 1.0 : f64}> : () -> f64
%sum = "nodal.const_expr"(%child0_actual, %extra) <{metadata = {}, operator_name = "add"}> : (f64, f64) -> f64
"nodal.parameter_override"(%sum) <{instance = @child0, parameter = @gain, metadata = {}}> : (f64) -> ()
%child0_p = "nodal.instance_terminal"() <{instance = @child0, port = "p", name = "child0.p", direction = "inout", flow_orientation = "out_of_component", source_path = "Top.child0.p", metadata = {}}> : () -> !nodal.terminal<"electrical">
"nodal.connect"(%child0_p, %p) <{connection_id = "child0_p", source_path = "Top.child0.p", metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
%child0_n = "nodal.instance_terminal"() <{instance = @child0, port = "n", name = "child0.n", direction = "inout", flow_orientation = "out_of_component", source_path = "Top.child0.n", metadata = {}}> : () -> !nodal.terminal<"electrical">
"nodal.connect"(%child0_n, %n) <{connection_id = "child0_n", source_path = "Top.child0.n", metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
"nodal.instance"() <{sym_name = "child1", module = @Branch, parameter_bindings = {gain = 3.0 : f64}, domain_bindings = {}, metadata = {child_path = "Top.child1", semantic_path = "Top.child1_instance"}}> : () -> ()
%child1_p = "nodal.instance_terminal"() <{instance = @child1, port = "p", name = "child1.p", direction = "inout", flow_orientation = "out_of_component", source_path = "Top.child1.p", metadata = {}}> : () -> !nodal.terminal<"electrical">
"nodal.connect"(%child1_p, %p) <{connection_id = "child1_p", source_path = "Top.child1.p", metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
%child1_n = "nodal.instance_terminal"() <{instance = @child1, port = "n", name = "child1.n", direction = "inout", flow_orientation = "out_of_component", source_path = "Top.child1.n", metadata = {}}> : () -> !nodal.terminal<"electrical">
"nodal.connect"(%child1_n, %n) <{connection_id = "child1_n", source_path = "Top.child1.n", metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
"nodal.instance"() <{sym_name = "child2", module = @Branch, parameter_bindings = {gain = 5.0 : f64}, domain_bindings = {}, metadata = {child_path = "Top.child2", semantic_path = "Top.child2_instance"}}> : () -> ()
%child2_p = "nodal.instance_terminal"() <{instance = @child2, port = "p", name = "child2.p", direction = "inout", flow_orientation = "out_of_component", source_path = "Top.child2.p", metadata = {}}> : () -> !nodal.terminal<"electrical">
"nodal.connect"(%child2_p, %p) <{connection_id = "child2_p", source_path = "Top.child2.p", metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
%child2_n = "nodal.instance_terminal"() <{instance = @child2, port = "n", name = "child2.n", direction = "inout", flow_orientation = "out_of_component", source_path = "Top.child2.n", metadata = {}}> : () -> !nodal.terminal<"electrical">
"nodal.connect"(%child2_n, %n) <{connection_id = "child2_n", source_path = "Top.child2.n", metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
}) : () -> ()
"nodal.module"() <{sym_name = "Branch", metadata = {semantic_path = "Top.child0"}}> ({

%p = "nodal.terminal"() <{name = "p", direction = "inout", metadata = {allow_floating = true, declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
%n = "nodal.terminal"() <{name = "n", direction = "inout", metadata = {allow_floating = true, declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
"nodal.parameter"() <{sym_name = "gain", type = f64, default_value = 2.0 : f64, variability = "symbolic", classification = "ordinary", metadata = {}}> : () -> ()
"nodal.instance"() <{sym_name = "leaf", module = @Leaf, parameter_bindings = {}, domain_bindings = {}, metadata = {child_path = "Top.child0.leaf", semantic_path = "Top.child0.leaf_instance"}}> : () -> ()
%leaf_actual = "nodal.const_parameter_ref"() <{parameter = @gain, metadata = {}}> : () -> f64
"nodal.parameter_override"(%leaf_actual) <{instance = @leaf, parameter = @gain, metadata = {}}> : (f64) -> ()
%leaf_p = "nodal.instance_terminal"() <{instance = @leaf, port = "p", name = "leaf.p", direction = "inout", flow_orientation = "out_of_component", source_path = "Top.child0.leaf.p", metadata = {}}> : () -> !nodal.terminal<"electrical">
"nodal.connect"(%leaf_p, %p) <{connection_id = "leaf_p", source_path = "Top.child0.leaf.p", metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
%leaf_n = "nodal.instance_terminal"() <{instance = @leaf, port = "n", name = "leaf.n", direction = "inout", flow_orientation = "out_of_component", source_path = "Top.child0.leaf.n", metadata = {}}> : () -> !nodal.terminal<"electrical">
"nodal.connect"(%leaf_n, %n) <{connection_id = "leaf_n", source_path = "Top.child0.leaf.n", metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
}) : () -> ()
"nodal.module"() <{sym_name = "Leaf", metadata = {semantic_path = "Top.child0.leaf"}}> ({

%p = "nodal.terminal"() <{name = "p", direction = "inout", metadata = {allow_floating = true, declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
%n = "nodal.terminal"() <{name = "n", direction = "inout", metadata = {allow_floating = true, declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
"nodal.parameter"() <{sym_name = "gain", type = f64, default_value = 2.0 : f64, variability = "symbolic", classification = "ordinary", metadata = {}}> : () -> ()

%branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
"nodal.analog_user_function"() <{sym_name = "identitySignal", return_type = !nodal.quantity<"real", "1">, contract_version = "1", metadata = {}}> ({
  %input = "nodal.analog_function_value"() <{kind = "input", name = "signal", argument_index = 0 : i64, metadata = {}}> : () -> !nodal.quantity<"real", "1">
  "nodal.analog_function_return"(%input) <{metadata = {}}> : (!nodal.quantity<"real", "1">) -> ()
}) : () -> ()
"nodal.analog"() <{metadata = {semantic_path = "Top.child0.leaf.analog"}}> ({
  %gain = "nodal.parameter_ref"() <{parameter = @gain, metadata = {}}> : () -> f64
  %value = "nodal.analog_user_call"(%gain) <{callee = @identitySignal, contract_version = "1", metadata = {}}> : (f64) -> f64
  "nodal.contribute"(%branch, %value) <{kind = "flow", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
}) : () -> ()
}) : () -> ()
}
