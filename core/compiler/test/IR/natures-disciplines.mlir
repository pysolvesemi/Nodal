module attributes {
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
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {semantic_path = "std.Voltage"}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", metadata = {semantic_path = "std.Current"}, sym_name = "Current", units = "A"}> : () -> ()
  "nodal.nature_import"() <{definition_hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", metadata = {semantic_path = "imports.Voltage"}, source = "std://disciplines/electrical@2023", sym_name = "VoltageImported", target = @Voltage}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {kind = "conservative"}, potential = @VoltageImported, sym_name = "electrical"}> : () -> ()
  "nodal.discipline_import"() <{definition_hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", metadata = {semantic_path = "imports.electrical"}, source = "std://disciplines/electrical@2023", sym_name = "electricalImported", target = @electrical}> : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "NatureDisciplineFixture"}> ({
  ^bb0:
  }) : () -> ()
}
