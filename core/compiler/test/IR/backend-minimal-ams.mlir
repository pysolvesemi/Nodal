module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "readable",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-ams",
  nodal.backend.shaped_layout = "flat-packed",
  nodal.target.profile = "mixed_signal"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "MixedTop"}> ({
  ^bb0:
  }) : () -> ()
}
