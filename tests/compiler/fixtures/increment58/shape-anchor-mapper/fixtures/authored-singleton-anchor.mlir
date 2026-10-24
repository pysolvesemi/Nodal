module { "nodal.module"() <{sym_name = "AnchorQuery", metadata = {}}> ({
 %zero = "nodal.const_literal"() <{value = 0 : i64, spelling = "0", metadata = {}}> : () -> i64
 %one = "nodal.const_literal"() <{value = 1 : i64, spelling = "1", metadata = {}}> : () -> i64
 %three = "nodal.const_literal"() <{value = 3 : i64, spelling = "3", metadata = {}}> : () -> i64
 %cells = "nodal.node_array"() <{name = "cells", source_path = "AnchorQuery.cells", metadata = {}}> : () -> !nodal.shaped<"3", !nodal.terminal<"electrical">>
 %anchor = "nodal.node"() <{name = "anchor", source_path = "AnchorQuery.anchor", metadata = {}}> : () -> !nodal.terminal<"electrical">
 %point = "nodal.shape_index"(%cells, %zero) <{metadata = {}}> : (!nodal.shaped<"3", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
 "nodal.generate"(%zero, %one, %one) <{induction = "single", region_id = "AnchorQuery.single", induction_path = "AnchorQuery.single.index", metadata = {maximum_trip_count = 1 : i64}}> ({^bb0(%single: i64):
 %selected = "nodal.shape_index"(%cells, %single) <{metadata = {}}> : (!nodal.shaped<"3", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
 "nodal.connect"(%selected, %anchor) <{connection_id = "join", source_path = "AnchorQuery.join", metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
}) : (i64, i64, i64) -> ()
}) : () -> () }
