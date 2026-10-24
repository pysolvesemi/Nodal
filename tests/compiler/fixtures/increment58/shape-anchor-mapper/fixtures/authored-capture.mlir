module { "nodal.module"() <{sym_name = "AnchorQuery", metadata = {}}> ({
 %zero = "nodal.const_literal"() <{value = 0 : i64, spelling = "0", metadata = {}}> : () -> i64
 %one = "nodal.const_literal"() <{value = 1 : i64, spelling = "1", metadata = {}}> : () -> i64
 %three = "nodal.const_literal"() <{value = 3 : i64, spelling = "3", metadata = {}}> : () -> i64
 %cells = "nodal.node_array"() <{name = "cells", source_path = "AnchorQuery.cells", metadata = {}}> : () -> !nodal.shaped<"3", !nodal.terminal<"electrical">>
 %anchor = "nodal.node"() <{name = "anchor", source_path = "AnchorQuery.anchor", metadata = {}}> : () -> !nodal.terminal<"electrical">
 %point = "nodal.shape_index"(%cells, %zero) <{metadata = {}}> : (!nodal.shaped<"3", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
 "nodal.hardware_loop"(%zero, %three, %one, %cells, %anchor) <{induction = "i", contract_version = "bounded-digital-v1", effect_policy = "ordered", region_id = "AnchorQuery.loop", induction_path = "AnchorQuery.loop.i", metadata = {semantic_path = "AnchorQuery.loop"}}> ({^bb0(%iv: i64, %captured: !nodal.shaped<"3", !nodal.terminal<"electrical">>, %capturedAnchor: !nodal.terminal<"electrical">):
 %selected = "nodal.shape_index"(%captured, %iv) <{metadata = {}}> : (!nodal.shaped<"3", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
 "nodal.connect"(%selected, %capturedAnchor) <{connection_id = "join", source_path = "AnchorQuery.join", metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
"nodal.hardware_loop_end"() : () -> ()
}) : (i64, i64, i64, !nodal.shaped<"3", !nodal.terminal<"electrical">>, !nodal.terminal<"electrical">) -> ()
}) : () -> () }
