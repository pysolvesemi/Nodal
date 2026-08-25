// -----
module attributes {
  nodal.bridge.source_map = [{semantic_path = "Top.link", source_column = 7 : i64, source_end_column = 24 : i64, source_end_line = 12 : i64, source_line = 12 : i64, source_path = "src/Top.scala"}],
  nodal.verify.interface_connections = [{left_role = "source", right_role = "source", semantic_path = "Top.link"}]
}

// -----
module attributes {
  nodal.bridge.source_map = [{semantic_path = "Top.monitor", source_column = 3 : i64, source_end_column = 30 : i64, source_end_line = 18 : i64, source_line = 18 : i64, source_path = "src/Top.scala"}],
  nodal.verify.interface_actions = [{action = "drive", role = "monitor", semantic_path = "Top.monitor"}]
}

// -----
module attributes {
  nodal.bridge.source_map = [{semantic_path = "Top.inverse", source_column = 5 : i64, source_end_column = 20 : i64, source_end_line = 22 : i64, source_line = 22 : i64, source_path = "src/Top.scala"}],
  nodal.verify.interface_inversions = [{destination_role = "monitor", semantic_path = "Top.inverse", source_role = "master"}]
}

// -----
module attributes {
  nodal.bridge.source_map = [{semantic_path = "Top.value", source_column = 9 : i64, source_end_column = 27 : i64, source_end_line = 50 : i64, source_line = 50 : i64, source_path = "src/Top.scala"}],
  nodal.verify.ordinary_drivers = [{count = 2 : i64, path = "Top.value"}]
}

// -----
module attributes {
  nodal.bridge.declarations = [{attributes = {mode = "push-pull"}, path = "Top.outer"}, {attributes = {mode = "open-drain"}, path = "Top.inner"}],
  nodal.bridge.source_map = [{semantic_path = "Top.outer", source_column = 3 : i64, source_end_column = 29 : i64, source_end_line = 80 : i64, source_line = 80 : i64, source_path = "src/Top.scala"}],
  nodal.bridge.topology = [{kind = "inout-pass-through", left = "Top.outer", right = "Top.inner"}]
}

// -----
module attributes {
  nodal.bridge.source_map = [{semantic_path = "Top.analog", source_column = 4 : i64, source_end_column = 25 : i64, source_end_line = 90 : i64, source_line = 90 : i64, source_path = "src/Top.scala"}],
  nodal.verify.ams_connections = [{left_discipline = "electrical", path = "Top.analog", right_discipline = "thermal"}]
}

// -----
module attributes {
  nodal.bridge.source_map = [{semantic_path = "Top.monitor", source_column = 4 : i64, source_end_column = 31 : i64, source_end_line = 96 : i64, source_line = 96 : i64, source_path = "src/Top.scala"}],
  nodal.verify.ams_accesses = [{access = "contribute", allowed = false, path = "Top.monitor"}]
}

// -----
module attributes {
  nodal.bridge.source_map = [{semantic_path = "Top.sample", source_column = 5 : i64, source_end_column = 33 : i64, source_end_line = 102 : i64, source_line = 102 : i64, source_path = "src/Top.scala"}],
  nodal.verify.implicit_bridges = [{implicit = true, path = "Top.sample"}]
}
