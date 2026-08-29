module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.nature"() <{abstol = 1.000000e-06 : f64, access = "Across", dimension = "voltage", metadata = {}, sym_name = "VoltageNature", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.000000e-09 : f64, access = "Through", dimension = "current", metadata = {}, sym_name = "CurrentNature", units = "A"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @CurrentNature, metadata = {}, potential = @VoltageNature, sym_name = "customElectrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "PotentialFlowAccess"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"customElectrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"customElectrical">
    %probe_p = "nodal.node"() <{metadata = {}, name = "probe_p"}> : () -> !nodal.terminal<"customElectrical">
    %probe_n = "nodal.node"() <{metadata = {}, name = "probe_n"}> : () -> !nodal.terminal<"customElectrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {identity = "p_n"}}> : (!nodal.terminal<"customElectrical">, !nodal.terminal<"customElectrical">) -> !nodal.branch<"customElectrical">
    %probe_branch = "nodal.branch"(%probe_p, %probe_n) <{metadata = {identity = "probe_p_probe_n"}}> : (!nodal.terminal<"customElectrical">, !nodal.terminal<"customElectrical">) -> !nodal.branch<"customElectrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %branch_potential = "nodal.access"(%branch) <{function = "Across", kind = "potential", metadata = {source_path = "PotentialFlowAccess.branch_potential"}}> : (!nodal.branch<"customElectrical">) -> !nodal.quantity<"real", "voltage">
      %branch_flow = "nodal.access"(%branch) <{function = "Through", kind = "flow", metadata = {source_path = "PotentialFlowAccess.branch_flow"}}> : (!nodal.branch<"customElectrical">) -> !nodal.quantity<"real", "current">
      %generic_pair = "nodal.terminal_access"(%p, %n) <{function = "potential", kind = "potential", metadata = {}, source_path = "PotentialFlowAccess.generic_pair"}> : (!nodal.terminal<"customElectrical">, !nodal.terminal<"customElectrical">) -> !nodal.quantity<"real", "voltage">
      %one_terminal = "nodal.terminal_access"(%p) <{function = "Across", kind = "potential", metadata = {}, source_path = "PotentialFlowAccess.one_terminal"}> : (!nodal.terminal<"customElectrical">) -> !nodal.quantity<"real", "voltage">
      %port_flow = "nodal.port_flow_access"(%p) <{function = "Through", kind = "flow", metadata = {}, source_path = "PotentialFlowAccess.port_flow"}> : (!nodal.terminal<"customElectrical">) -> !nodal.quantity<"real", "current">
      %probe_value = "nodal.access"(%probe_branch) <{function = "potential", kind = "potential", metadata = {source_path = "PotentialFlowAccess.probe"}}> : (!nodal.branch<"customElectrical">) -> !nodal.quantity<"real", "voltage">
      "nodal.contribute"(%branch, %branch_potential) <{kind = "potential", metadata = {}}> : (!nodal.branch<"customElectrical">, !nodal.quantity<"real", "voltage">) -> ()
      "nodal.contribute"(%branch, %branch_flow) <{kind = "flow", metadata = {}}> : (!nodal.branch<"customElectrical">, !nodal.quantity<"real", "current">) -> ()
      "nodal.contribute"(%branch, %generic_pair) <{kind = "potential", metadata = {}}> : (!nodal.branch<"customElectrical">, !nodal.quantity<"real", "voltage">) -> ()
      "nodal.contribute"(%branch, %one_terminal) <{kind = "potential", metadata = {}}> : (!nodal.branch<"customElectrical">, !nodal.quantity<"real", "voltage">) -> ()
      "nodal.contribute"(%branch, %port_flow) <{kind = "flow", metadata = {}}> : (!nodal.branch<"customElectrical">, !nodal.quantity<"real", "current">) -> ()
    }) : () -> ()
  }) : () -> ()
}
