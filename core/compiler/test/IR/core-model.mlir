module {
  "nodal.interface"() <{
    metadata = {abi = "logical"},
    sym_name = "PixelStream"
  }> ({
    "nodal.interface_role"() <{
      kind = "producer",
      metadata = {},
      sym_name = "source"
    }> : () -> ()
    "nodal.interface_role"() <{
      kind = "consumer",
      metadata = {},
      sym_name = "sink"
    }> : () -> ()
    "nodal.interface_member"() <{
      metadata = {path = "payload"},
      protocol = "stream",
      roles = ["source", "sink"],
      sym_name = "payload",
      type = !nodal.stream<!nodal.shaped<"3", !nodal.uint<8>>>
    }> : () -> ()
  }) : () -> ()

  "nodal.enum"() <{
    encoding = "sequential",
    metadata = {abi = "canonical"},
    sym_name = "ControlState",
    underlying_type = !nodal.uint<2>
  }> ({
    "nodal.enum_case"() <{
      metadata = {},
      sym_name = "Idle",
      value = 0 : i64
    }> : () -> ()
    "nodal.enum_case"() <{
      metadata = {},
      sym_name = "Run",
      value = 1 : i64
    }> : () -> ()
  }) : () -> ()

  "nodal.module"() <{
    metadata = {origin = "core-model.mlir"},
    sym_name = "Top"
  }> ({
    "nodal.domain"() <{
      edge = "rising",
      metadata = {frequency_hz = 100000000 : i64},
      reset_policy = "async_assert_sync_release",
      sym_name = "core"
    }> : () -> ()
    "nodal.domain_requirement"() <{
      metadata = {},
      sym_name = "pixel"
    }> : () -> ()
    "nodal.domain_bind"() <{
      actual = @core,
      metadata = {},
      requirement = @pixel
    }> : () -> ()
    "nodal.clock_relation"() <{
      destination = @core,
      kind = "alias",
      metadata = {},
      source = @core
    }> : () -> ()
    "nodal.reset_relation"() <{
      destination = @core,
      kind = "alias",
      metadata = {},
      source = @core
    }> : () -> ()

    "nodal.port"() <{
      direction = "input",
      domain = @core,
      metadata = {},
      sym_name = "enable",
      type = !nodal.bits<1>
    }> : () -> ()
    "nodal.port"() <{
      direction = "output",
      domain = @core,
      metadata = {},
      sym_name = "data",
      type = !nodal.shaped<"2,WIDTH", !nodal.uint<8>>
    }> : () -> ()
    "nodal.parameter"() <{
      default_value = 8 : i64,
      metadata = {target_visible = true},
      sym_name = "WIDTH",
      type = i64,
      variability = "symbolic"
    }> : () -> ()
    "nodal.instance"() <{
      domain_bindings = {default = @core},
      metadata = {},
      module = @Child,
      parameter_bindings = {WIDTH = 8 : i64},
      sym_name = "child"
    }> : () -> ()
    "nodal.interface_instance"() <{
      definition = @PixelStream,
      metadata = {},
      role = "source",
      sym_name = "stream"
    }> : () -> ()
    %payload = "nodal.member_access"() <{
      instance = @stream,
      metadata = {},
      path = "payload"
    }> : () -> !nodal.stream<!nodal.shaped<"3", !nodal.uint<8>>>
    "nodal.interface_abi"() <{
      layout_policy = "logical_only",
      logical_path = "Top.stream",
      members = ["payload"],
      metadata = {source_map = "retained"}
    }> : () -> ()

    %zero = "nodal.constant"() <{
      metadata = {origin = "Top.zero"},
      value = 0 : i64
    }> : () -> !nodal.uint<8>
    %index = "nodal.constant"() <{
      metadata = {},
      value = 0 : index
    }> : () -> index
    %shaped = "nodal.shape_view"(%zero) <{
      dimensions = "1",
      materialization = "explicit_view",
      metadata = {storage = "structural"},
      observability = "source_mapped",
      origin = "Top.data"
    }> : (!nodal.uint<8>) -> !nodal.shaped<"1", !nodal.uint<8>>
    %element = "nodal.shape_index"(%shaped, %index) <{
      metadata = {formula = "row_major"}
    }> : (!nodal.shaped<"1", !nodal.uint<8>>, index) -> !nodal.uint<8>
    %flat = "nodal.shape_flatten"(%shaped) <{
      layout = "row_major",
      metadata = {target_layout = "deferred"}
    }> : (!nodal.shaped<"1", !nodal.uint<8>>) -> !nodal.bits<8>

    "nodal.generate"() <{
      induction = "g",
      lower = 0 : i64,
      metadata = {kind = "structural"},
      step = 1 : i64,
      upper = 2 : i64
    }> ({
    }) : () -> ()
    "nodal.hardware_loop"() <{
      effect_policy = "ordered",
      induction = "i",
      lower = 0 : i64,
      metadata = {latency = "none"},
      step = 1 : i64,
      upper = 4 : i64
    }> ({
    }) : () -> ()

    %net = "nodal.resolved_net"() <{
      metadata = {resolution = "wired_and"},
      name = "irq"
    }> : () -> !nodal.resolved<"open_drain", !nodal.bits<1>>
    %driver = "nodal.net_driver"(%net) <{
      driver_id = "Top.irq_driver",
      metadata = {}
    }> : (!nodal.resolved<"open_drain", !nodal.bits<1>>) -> !nodal.driver<!nodal.bits<1>>
    %resolved = "nodal.net_read"(%net) <{
      metadata = {}
    }> : (!nodal.resolved<"open_drain", !nodal.bits<1>>) -> !nodal.bits<1>
    %drive_enable = "nodal.constant"() <{
      metadata = {},
      value = true
    }> : () -> i1
    "nodal.net_drive"(%net, %driver, %resolved, %drive_enable) <{
      metadata = {high_impedance_when_disabled = true},
      mode = "open_drain"
    }> : (!nodal.resolved<"open_drain", !nodal.bits<1>>, !nodal.driver<!nodal.bits<1>>, !nodal.bits<1>, i1) -> ()

    %positive = "nodal.terminal"() <{
      metadata = {boundary = true},
      name = "vin"
    }> : () -> !nodal.terminal<"electrical">
    %negative = "nodal.node"() <{
      metadata = {reference = true},
      name = "gnd"
    }> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%positive, %negative) <{
      metadata = {identity = "vin_gnd"}
    }> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    %voltage = "nodal.access"(%branch) <{
      kind = "potential",
      metadata = {dimension = "voltage"}
    }> : (!nodal.branch<"electrical">) -> f64
    %sample = "nodal.bridge"(%voltage) <{
      destination_domain = "core",
      kind = "threshold_sample",
      metadata = {threshold = 0.5 : f64},
      source_domain = "analog"
    }> : (f64) -> i1
    %synced = "nodal.crossing"(%sample) <{
      destination_domain = @core,
      kind = "sync",
      metadata = {stages = 2 : i64},
      source_domain = @pixel
    }> : (i1) -> i1

    "nodal.fsm"() <{
      domain = @core,
      encoding = "compact",
      illegal_policy = "recover",
      metadata = {source_map = "retained"},
      state_type = !nodal.enum<"ControlState", 2>,
      sym_name = "control"
    }> ({
      "nodal.fsm_state"() <{
        initial = true,
        metadata = {},
        sym_name = "Idle",
        terminal = false
      }> ({
        "nodal.fsm_action"() <{
          effect = "clear_counter",
          metadata = {},
          phase = "entry"
        }> : () -> ()
        "nodal.fsm_transition"() <{
          condition = "enable",
          destination = @Run,
          metadata = {},
          priority = 0 : i64
        }> : () -> ()
      }) : () -> ()
      "nodal.fsm_state"() <{
        initial = false,
        metadata = {},
        sym_name = "Run",
        terminal = true
      }> ({
        "nodal.fsm_action"() <{
          effect = "count",
          metadata = {},
          phase = "active"
        }> : () -> ()
      }) : () -> ()
      "nodal.fsm_completion"() <{
        destination = @Idle,
        kind = "restart",
        metadata = {},
        source = @Run
      }> : () -> ()
    }) : () -> ()
    "nodal.state_owner"() <{
      domain = @core,
      metadata = {},
      state = @control::@Run
    }> : () -> ()
    "nodal.timing_provenance"() <{
      metadata = {frequency_hz = 100000000 : i64},
      owner = @control,
      relationship = "single_domain"
    }> : () -> ()
  }) : () -> ()
}
