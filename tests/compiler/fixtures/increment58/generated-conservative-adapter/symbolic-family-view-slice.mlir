module {
"nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", dimension = "voltage", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
"nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", dimension = "current", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
"nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
"nodal.module"() <{metadata = {semantic_path = "Fixture"}, sym_name = "Fixture"}> ({
%nodes = "nodal.node_array"() <{metadata = {semantic_path = "Fixture.nodes", storage = "structural", test_name = "root"}, name = "nodes", source_path = "Fixture.nodes"}> : () -> !nodal.shaped<"2,3", !nodal.terminal<"electrical">>
%zero = "nodal.const_literal"() <{metadata = {test_name = "zero"}, spelling = "0", value = 0 : i64}> : () -> i64
%one = "nodal.const_literal"() <{metadata = {test_name = "one"}, spelling = "1", value = 1 : i64}> : () -> i64
%four = "nodal.const_literal"() <{metadata = {test_name = "four"}, spelling = "4", value = 4 : i64}> : () -> i64
%five = "nodal.const_literal"() <{metadata = {test_name = "five"}, spelling = "5", value = 5 : i64}> : () -> i64
%flat = "nodal.shape_view"(%nodes) <{dimensions = "6", origin = "Fixture.nodes", materialization = "view", observability = "source_mapped", metadata = {semantic_path = "Fixture.flat", input_path = "Fixture.nodes", storage = "structural", access = "read_only"}}> : (!nodal.shaped<"2,3", !nodal.terminal<"electrical">>) -> !nodal.shaped<"6", !nodal.terminal<"electrical">>
%middle = "nodal.shape_slice"(%flat, %one, %five, %four) <{axis = 0 : i64, dimensions = "4", origin = "Fixture.flat", observability = "source_mapped", metadata = {semantic_path = "Fixture.middle", input_path = "Fixture.flat", storage = "structural", access = "read_only", test_name = "middle"}}> : (!nodal.shaped<"6", !nodal.terminal<"electrical">>, i64, i64, i64) -> !nodal.shaped<"4", !nodal.terminal<"electrical">>
%point = "nodal.shape_index"(%middle, %one) <{metadata = {semantic_path = "Fixture.middle.at(literal:1)", test_name = "point", input_path = "Fixture.middle", storage = "structural", selection_kind = "node_array", source_uses = ["Fixture.use_point"]}}> : (!nodal.shaped<"4", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
%marker = "nodal.const_literal"() <{metadata = {test_name = "marker"}, spelling = "997", value = 997 : i64}> : () -> i64
}) : () -> ()
}
