// -----
module {
  "nodal.module"() <{metadata = {semantic_path = "Top"}, sym_name = "Top"}> ({
    "nodal.port"() <{
      direction = "input",
      domain = @core,
      metadata = {hierarchy_path = "Top", semantic_path = "Top.bus", source_end_column = 18 : i64, source_end_line = 31 : i64},
      sym_name = "bus",
      type = !nodal.interface<"Bus">
    }> : () -> () loc("src/Top.scala":31:7)
  }) : () -> ()
}

// -----
module {
  "nodal.module"() <{metadata = {semantic_path = "Top"}, sym_name = "Top"}> ({
    "nodal.interface_abi"() <{layout_policy = "portable_flattened", logical_path = "Top.left.data", members = ["data"], metadata = {emitted_path = "data", semantic_path = "Top.left.data"}}> : () -> () loc("src/Top.scala":40:3)
    "nodal.interface_abi"() <{layout_policy = "portable_flattened", logical_path = "Top.right.data", members = ["data"], metadata = {emitted_path = "data", semantic_path = "Top.right.data", source_end_column = 28 : i64, source_end_line = 41 : i64}}> : () -> () loc("src/Top.scala":41:3)
  }) : () -> ()
}

// -----
module {
  "nodal.module"() <{metadata = {semantic_path = "Top"}, sym_name = "Top"}> ({
    %net = "nodal.resolved_net"() <{metadata = {resolution_supported = false, semantic_path = "Top.pad", source_end_column = 34 : i64, source_end_line = 60 : i64}, name = "pad"}> : () -> !nodal.resolved<"push_pull", !nodal.bits<1>> loc("src/Top.scala":60:5)
  }) : () -> ()
}

// -----
module {
  "nodal.module"() <{metadata = {semantic_path = "Top"}, sym_name = "Top"}> ({
    %net = "nodal.resolved_net"() <{metadata = {}, name = "irq"}> : () -> !nodal.resolved<"open_drain", !nodal.bits<1>>
    %driver = "nodal.net_driver"(%net) <{driver_id = "Top.irq", metadata = {}}> : (!nodal.resolved<"open_drain", !nodal.bits<1>>) -> !nodal.driver<!nodal.bits<1>>
    %value = "nodal.constant"() <{metadata = {}, value = 1 : i64}> : () -> !nodal.bits<1>
    %enable = "nodal.constant"() <{metadata = {}, value = true}> : () -> i1
    "nodal.net_drive"(%net, %driver, %value, %enable) <{metadata = {drive_level = "high", hierarchy_path = "Top", semantic_path = "Top.irq", source_end_column = 30 : i64, source_end_line = 70 : i64}, mode = "open_drain"}> : (!nodal.resolved<"open_drain", !nodal.bits<1>>, !nodal.driver<!nodal.bits<1>>, !nodal.bits<1>, i1) -> () loc(unknown)
  }) : () -> () loc("src/Top.scala":70:5)
}
