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
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical_equivalent"}> : () -> ()
  "nodal.discipline_import"() <{definition_hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", metadata = {}, source = "std://disciplines/electrical@2023", sym_name = "electrical_alias", target = @electrical}> : () -> ()

  "nodal.module"() <{metadata = {root = true}, sym_name = "ElectricalNetwork"}> ({
  ^bb0:
    "nodal.component_contract"() <{connectivity_ownership = "local", kind = "concrete", metadata = {}, source_path = "ElectricalNetwork"}> : () -> ()
    %p = "nodal.terminal"() <{direction = "input", flow_orientation = "into_component", metadata = {}, name = "p", source_path = "ElectricalNetwork.p"}> : () -> !nodal.terminal<"electrical">
    %p_alias = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {}, name = "p_alias", source_path = "ElectricalNetwork.p_alias"}> : () -> !nodal.terminal<"electrical_equivalent">
    %q = "nodal.terminal"() <{direction = "output", flow_orientation = "into_component", metadata = {}, name = "q", source_path = "ElectricalNetwork.q"}> : () -> !nodal.terminal<"electrical">
    %sense = "nodal.terminal"() <{direction = "input", flow_orientation = "out_of_component", metadata = {}, name = "sense", source_path = "ElectricalNetwork.sense"}> : () -> !nodal.terminal<"electrical">
    %mid = "nodal.node"() <{metadata = {}, name = "mid", source_path = "ElectricalNetwork.mid"}> : () -> !nodal.terminal<"electrical">
    %ground = "nodal.node"() <{metadata = {}, name = "ground", source_path = "ElectricalNetwork.ground"}> : () -> !nodal.terminal<"electrical">
    %ground_alias = "nodal.node"() <{metadata = {}, name = "ground_alias", source_path = "ElectricalNetwork.ground_alias"}> : () -> !nodal.terminal<"electrical_alias">

    "nodal.alias"(%p, %p_alias) <{alias_id = "input-alias", metadata = {}, source_path = "ElectricalNetwork.alias.input"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical_equivalent">) -> ()
    "nodal.connect"(%mid, %sense) <{connection_id = "sense-tap", metadata = {}, source_path = "ElectricalNetwork.connect.sense"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
    "nodal.connect"(%ground, %ground_alias) <{connection_id = "ground-alias", metadata = {}, source_path = "ElectricalNetwork.connect.ground"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical_alias">) -> ()
    "nodal.reference"(%ground) <{metadata = {}, scope = "global", source_path = "ElectricalNetwork.reference.ground"}> : (!nodal.terminal<"electrical">) -> ()

    %input_branch = "nodal.branch"(%p, %mid) <{declaration_kind = "named", metadata = {}, name = "input_branch", source_path = "ElectricalNetwork.branch.input"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    %output_branch = "nodal.branch"(%mid, %q) <{declaration_kind = "named", metadata = {}, name = "output_branch", source_path = "ElectricalNetwork.branch.output"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    %ground_branch = "nodal.branch"(%q, %ground) <{declaration_kind = "implicit", metadata = {}, source_path = "ElectricalNetwork.branch.ground"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
  }) : () -> ()

  "nodal.module"() <{metadata = {}, sym_name = "PartialProbe"}> ({
  ^bb0:
    "nodal.component_contract"() <{connectivity_ownership = "extensible", kind = "partial", metadata = {}, source_path = "PartialProbe"}> : () -> ()
    %ext = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {allow_floating = true}, name = "ext", source_path = "PartialProbe.ext"}> : () -> !nodal.terminal<"electrical">
    "nodal.reference"(%ext) <{metadata = {}, scope = "module", source_path = "PartialProbe.reference.ext"}> : (!nodal.terminal<"electrical">) -> ()
  }) : () -> ()
}
