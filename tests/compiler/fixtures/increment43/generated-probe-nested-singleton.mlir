module attributes {nodal.target.profile = "analog"} {
"nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", dimension = "voltage", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
"nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", dimension = "current", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
"nodal.discipline"() <{domain = "continuous", flow = @Current, potential = @Voltage, metadata = {}, sym_name = "electrical"}> : () -> ()
"nodal.module"() <{sym_name = "Child", metadata = {semantic_path = "Child"}}> ({
"nodal.component_contract"() <{kind = "partial", connectivity_ownership = "extensible", source_path = "Child", metadata = {}}> : () -> ()
}) : () -> ()
"nodal.module"() <{sym_name = "Parent", metadata = {semantic_path = "Parent"}}> ({
"nodal.component_contract"() <{kind = "partial", connectivity_ownership = "extensible", source_path = "Parent", metadata = {}}> : () -> ()
%c0 = "nodal.const_literal"() <{metadata = {}, spelling = "0", value = 0 : i64}> : () -> i64
%c1 = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
%c2 = "nodal.const_literal"() <{metadata = {}, spelling = "2", value = 2 : i64}> : () -> i64
%c3 = "nodal.const_literal"() <{metadata = {}, spelling = "3", value = 3 : i64}> : () -> i64
%c4 = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
%c5 = "nodal.const_literal"() <{metadata = {}, spelling = "5", value = 5 : i64}> : () -> i64
%c6 = "nodal.const_literal"() <{metadata = {}, spelling = "6", value = 6 : i64}> : () -> i64
%c7 = "nodal.const_literal"() <{metadata = {}, spelling = "7", value = 7 : i64}> : () -> i64
%c8 = "nodal.const_literal"() <{metadata = {}, spelling = "8", value = 8 : i64}> : () -> i64
%a = "nodal.node_array"() <{name = "a", source_path = "Parent.a", metadata = {semantic_path = "Parent.a", storage = "structural"}}> : () -> !nodal.shaped<"4", !nodal.terminal<"electrical">>
%n = "nodal.node"() <{name = "n", source_path = "Parent.n", metadata = {semantic_path = "Parent.n"}}> : () -> !nodal.terminal<"electrical">
"nodal.generate"() <{induction = "i", lower = 0 : i64, upper = 1 : i64, step = 1 : i64, region_id = "Parent.gen", induction_path = "Parent.gen.index", metadata = {maximum_trip_count = 1 : i64}}> ({
^bb0(%i: i64):
%ga = "nodal.shape_index"(%a, %i) <{generated_owner = "Parent.gen", generated_induction = "Parent.gen.index", metadata = {input_path = "Parent.a", semantic_path = "Parent.a.at(induction:Parent.gen.index).in(Parent.gen)", source_uses = ["Parent.gen.use_ga"], storage = "structural", selection_kind = "node_array"}}> : (!nodal.shaped<"4", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
"nodal.generate"() <{induction = "j", lower = 0 : i64, upper = 1 : i64, step = 1 : i64, region_id = "Parent.gen.inner", induction_path = "Parent.gen.inner.index", metadata = {maximum_trip_count = 1 : i64}}> ({
^bb0(%j: i64):
"nodal.connect"(%ga, %n) <{connection_id = "Parent.nestedFamily", source_path = "Parent.nestedFamily", generated_owner = "Parent.gen.inner", generated_induction = "Parent.gen.inner.index", metadata = {semantic_path = "Parent.nestedFamily"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
%first = "nodal.shape_index"(%a, %c0) <{generated_owner = "Parent.gen.inner", generated_induction = "Parent.gen.inner.index", metadata = {input_path = "Parent.a", semantic_path = "Parent.a.at(literal:0).in(Parent.gen.inner)", source_uses = ["Parent.gen.inner.use_first"], storage = "structural", selection_kind = "node_array"}}> : (!nodal.shaped<"4", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
%second = "nodal.shape_index"(%a, %c1) <{generated_owner = "Parent.gen.inner", generated_induction = "Parent.gen.inner.index", metadata = {input_path = "Parent.a", semantic_path = "Parent.a.at(literal:1).in(Parent.gen.inner)", source_uses = ["Parent.gen.inner.use_second"], storage = "structural", selection_kind = "node_array"}}> : (!nodal.shaped<"4", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
"nodal.analog"() <{generated_owner = "Parent.gen.inner", generated_induction = "Parent.gen.inner.index", metadata = {}}> ({
%access = "nodal.terminal_access"(%first, %second) <{kind = "flow", function = "I", metadata = {semantic_path = "Parent.access"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.quantity<"real", "current">
"nodal.analog_procedure"() <{owner = "Parent", generated_owner = "Parent.gen.inner", generated_induction = "Parent.gen.inner.index", metadata = {}}> ({
^bb0:
}) : () -> ()
}) : () -> ()
}) : () -> ()
}) : () -> ()
}) : () -> ()
}
