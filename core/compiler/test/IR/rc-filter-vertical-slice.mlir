module attributes {
  nodal.backend.check_profile = "default",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog",
  nodal.verify.analog_topology = true,
  nodal.verify.assignment_coverage = true,
  nodal.verify.cdc_rdc_safe = true,
  nodal.verify.clock_reset_domains = true,
  nodal.verify.combinational_acyclic = true,
  nodal.verify.construction_closed = true,
  nodal.verify.driver_coverage = true,
  nodal.verify.enum_fsm = true,
  nodal.verify.hierarchy_closed = true,
  nodal.verify.latch_free = true,
  nodal.verify.layout_storage = true,
  nodal.verify.memory_effects = true,
  nodal.verify.mixed_signal_bridges = true,
  nodal.verify.parameters_complete = true,
  nodal.verify.protocol_pipeline = true,
  nodal.verify.target_capability = true,
  nodal.verify.width_sign_shape = true
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "RcFilter"}> ({
  ^bb0:
    "nodal.parameter"() <{default_value = 1.0e-12 : f64, metadata = {unit = "F"}, sym_name = "C", type = f64, variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{default_value = 1000.0 : f64, metadata = {unit = "Ohm"}, sym_name = "R", type = f64, variability = "symbolic"}> : () -> ()
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {vertical_slice = "rc"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {vertical_slice = "rc"}}> ({
    ^bb0:
      %v = "nodal.access"(%branch) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">) -> f64
      %i = "nodal.access"(%branch) <{kind = "flow", metadata = {}}> : (!nodal.branch<"electrical">) -> f64
      %r = "nodal.parameter_ref"() <{metadata = {}, parameter = @R}> : () -> f64
      %resistive = "nodal.analog_div"(%v, %r) <{metadata = {}}> : (f64, f64) -> f64
      %c = "nodal.parameter_ref"() <{metadata = {}, parameter = @C}> : () -> f64
      %dv = "nodal.analog_ddt"(%v) <{metadata = {}}> : (f64) -> f64
      %capacitive = "nodal.analog_mul"(%c, %dv) <{metadata = {}}> : (f64, f64) -> f64
      %total = "nodal.analog_add"(%resistive, %capacitive) <{metadata = {}}> : (f64, f64) -> f64
      "nodal.contribute"(%branch, %total) <{kind = "flow", metadata = {equation = "I = V/R + C*ddt(V)"}}> : (!nodal.branch<"electrical">, f64) -> ()
    }) : () -> ()
  }) : () -> ()
}
