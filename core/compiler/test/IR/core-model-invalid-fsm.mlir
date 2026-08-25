module {
  "nodal.module"() <{metadata = {}, sym_name = "BadFsm"}> ({
    "nodal.domain"() <{
      edge = "rising",
      metadata = {},
      reset_policy = "sync",
      sym_name = "core"
    }> : () -> ()
    "nodal.fsm"() <{
      domain = @core,
      encoding = "compact",
      illegal_policy = "error",
      metadata = {},
      state_type = !nodal.enum<"State", 1>,
      sym_name = "control"
    }> ({
      "nodal.fsm_state"() <{
        initial = false,
        metadata = {},
        sym_name = "Idle",
        terminal = true
      }> ({
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
